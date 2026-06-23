/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.keyproject.key.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import de.uka.ilkd.key.control.AbstractUserInterfaceControl;
import de.uka.ilkd.key.control.DefaultUserInterfaceControl;
import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.gui.ExampleChooser;
import de.uka.ilkd.key.macros.ProofMacro;
import de.uka.ilkd.key.macros.ProofMacroFinishedInfo;
import de.uka.ilkd.key.nparser.ParsingFacade;
import de.uka.ilkd.key.pp.IdentitySequentPrintFilter;
import de.uka.ilkd.key.pp.LogicPrinter;
import de.uka.ilkd.key.pp.NotationInfo;
import de.uka.ilkd.key.pp.PosTableLayouter;
import de.uka.ilkd.key.pp.PositionTable;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Node;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.ProofAggregate;
import de.uka.ilkd.key.proof.init.*;
import de.uka.ilkd.key.proof.io.AbstractProblemLoader;
import de.uka.ilkd.key.proof.io.OutputStreamProofSaver;
import de.uka.ilkd.key.proof.io.ProblemLoaderException;
import de.uka.ilkd.key.rule.TacletApp;
import de.uka.ilkd.key.scripts.ProofScriptCommand;
import de.uka.ilkd.key.scripts.ProofScriptEngine;
import de.uka.ilkd.key.scripts.ScriptException;
import de.uka.ilkd.key.speclang.PositionedString;
import de.uka.ilkd.key.strategy.StrategyProperties;
import de.uka.ilkd.key.util.KeYConstants;

import org.key_project.prover.engine.ProverTaskListener;
import org.key_project.prover.engine.TaskFinishedInfo;
import org.key_project.util.collection.ImmutableList;
import org.key_project.util.collection.ImmutableSet;
import org.key_project.util.reflection.ClassLoaderUtil;

import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.keyproject.key.api.data.*;
import org.keyproject.key.api.data.KeyIdentifications.*;
import org.keyproject.key.api.remoteapi.KeyApi;
import org.keyproject.key.api.remoteclient.ClientApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.keyproject.key.api.data.ProofNodeDescription.collectPathInformation;

public final class KeyApiImpl implements KeyApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(KeyApiImpl.class);

    private final KeyIdentifications data = new KeyIdentifications();

    private Function<Void, Boolean> exitHandler;

    private ClientApi clientApi;
    private final ProverTaskListener clientListener = new ProverTaskListener() {
        @Override
        public void taskStarted(org.key_project.prover.engine.TaskStartedInfo info) {
            clientApi.taskStarted(TaskStartedInfo.from(info));
        }

        @Override
        public void taskProgress(int position) {
            clientApi.taskProgress(position);
        }

        @Override
        public void taskFinished(TaskFinishedInfo info) {
            clientApi.taskFinished(org.keyproject.key.api.data.TaskFinishedInfo.from(info));
        }
    };
    private final AtomicInteger uniqueCounter = new AtomicInteger();

    // Available macros and script commands are discovered via the service loader
    // (a classpath scan). They don't change at runtime, so scan once here instead
    // of on every getAvailableMacros/getAvailableScriptCommands/macro request.
    private final List<ProofMacro> availableMacros = loadAll(ProofMacro.class);
    private final List<ProofScriptCommand> availableScriptCommands =
        loadAll(ProofScriptCommand.class);
    private final Map<String, ProofMacro> macrosByName = availableMacros.stream()
            .collect(Collectors.toUnmodifiableMap(ProofMacro::getName, m -> m, (a, b) -> a));

    /// Dedicated executor for KeY operations. Several of them (auto, macro,
    /// script, ...) block their worker thread for the whole proof run, so running
    /// them on the shared ForkJoinPool.commonPool() could starve unrelated work
    /// across the JVM. Daemon threads, so the pool never keeps the JVM alive.
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "key-api-worker");
        thread.setDaemon(true);
        return thread;
    });

    public KeyApiImpl() {
    }

    /// Runs {@code task} on the dedicated {@link #executor} rather than the
    /// common pool.
    private <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    private static <T> List<T> loadAll(Class<T> service) {
        return StreamSupport.stream(
            ClassLoaderUtil.loadServices(service).spliterator(), false).toList();
    }

    @Override
    @JsonRequest
    public CompletableFuture<List<ExampleDesc>> examples() {
        return CompletableFutures
                .computeAsync((c) -> ExampleChooser.listExamples(ExampleChooser.lookForExamples())
                        .stream().map(ExampleDesc::from).toList());
    }

    @Override
    public CompletableFuture<Boolean> shutdown() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void exit() {
        executor.shutdownNow();
        this.exitHandler.apply(null);
    }

    public void setExitHandler(Function<Void, Boolean> exitHandler) {
        this.exitHandler = exitHandler;
    }

    @Override
    public void setTrace(SetTraceParams params) {

    }

    @Override
    public CompletableFuture<String> getVersion() {
        return CompletableFuture.completedFuture(KeYConstants.VERSION);
    }

    @Override
    public CompletableFuture<List<ProofMacroDesc>> getAvailableMacros() {
        return CompletableFuture.completedFuture(
            availableMacros.stream().map(ProofMacroDesc::from).toList());
    }

    @Override
    public CompletableFuture<List<ProofScriptCommandDesc>> getAvailableScriptCommands() {
        return CompletableFuture.completedFuture(
            availableScriptCommands.stream().map(ProofScriptCommandDesc::from).toList());
    }

    @Override
    public CompletableFuture<MacroStatistic> script(ProofId proofId, String scriptLine,
            StrategyOptions options) {
        return async(() -> {
            var proof = data.find(proofId);
            var env = data.find(proofId.env());
            var script = ParsingFacade.parseScript(scriptLine);
            var pe = new ProofScriptEngine(script);

            try {
                pe.execute((AbstractUserInterfaceControl) env.getProofControl(), proof);
                return new MacroStatistic(proofId, scriptLine, -1, -1);
            } catch (IOException | InterruptedException | ScriptException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<MacroStatistic> macro(ProofId proofId, String macroName,
            StrategyOptions options) {
        return async(() -> {
            var proof = data.find(proofId);
            var env = data.find(proofId.env());
            var macro = macrosByName.get(macroName);
            if (macro == null) {
                throw new NoSuchElementException("No macro named '" + macroName + "'");
            }

            try {
                var info =
                    macro.applyTo(env.getUi(), proof, proof.openGoals(), null, clientListener);
                return MacroStatistic.from(proofId, info);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

    }

    @Override
    public CompletableFuture<ProofStatus> auto(ProofId proofId, StrategyOptions options) {
        return async(() -> {
            var proof = data.find(proofId);
            var env = data.find(proofId.env());
            options.configure(proof);
            try {
                LOGGER.debug("Starting proof with stop mode {}",
                    proof.getSettings().getStrategySettings().getActiveStrategyProperties()
                            .getProperty(StrategyProperties.STOPMODE_OPTIONS_KEY));
                env.getProofControl().startAndWaitForAutoMode(proof);
                // clientListener);
                return ProofStatus.from(proofId, proof);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

    }

    @Override
    public CompletableFuture<Boolean> dispose(ProofId id) {
        data.dispose(id);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<NodeDesc>> goals(ProofId proofId, boolean onlyOpened,
            boolean onlyEnabled) {
        return async(() -> {
            var proof = data.find(proofId);
            if (onlyOpened && !onlyEnabled) {
                return asNodeDesc(proofId, proof.openGoals());
            } else if (onlyEnabled && onlyOpened) {
                return asNodeDesc(proofId, proof.openEnabledGoals());
            } else {
                return asNodeDesc(proofId, proof.allGoals());
            }
        });
    }

    private List<NodeDesc> asNodeDesc(ProofId proofId, ImmutableList<Goal> goals) {
        return asNodeDesc(proofId, goals.stream().map(Goal::node));
    }

    private List<NodeDesc> asNodeDesc(ProofId proofId, Stream<Node> nodes) {
        return nodes.map(it -> asNodeDesc(proofId, it)).toList();
    }

    private NodeDesc asNodeDesc(ProofId proofId, Node it) {
        return new NodeDesc(proofId, it.serialNr(), it.getNodeInfo().getBranchLabel(),
            it.getNodeInfo().getScriptRuleApplication(), collectPathInformation(it));
    }

    @Override
    public CompletableFuture<NodeDesc> tree(ProofId proofId) {
        return async(() -> {
            var proof = data.find(proofId);
            return asNodeDescRecursive(proofId, proof.root());
        });
    }

    private NodeDesc asNodeDescRecursive(ProofId proofId, Node root) {
        final List<NodeDesc> list =
            root.childrenStream().map(it -> asNodeDescRecursive(proofId, it)).toList();
        return new NodeDesc(new NodeId(proofId, "" + root.serialNr()),
            root.getNodeInfo().getBranchLabel(),
            root.getNodeInfo().getScriptRuleApplication(),
            list, collectPathInformation(root));
    }


    @Override
    public CompletableFuture<List<NodeDesc>> children(NodeId nodeId) {
        return async(() -> {
            var node = data.find(nodeId);
            return asNodeDesc(nodeId.proofId(), node.childrenStream());
        });
    }

    @Override
    public CompletableFuture<List<NodeDesc>> pruneTo(NodeId nodeId) {
        return async(() -> {
            var proof = data.find(nodeId.proofId());
            var node = data.find(nodeId);

            var nodes = proof.pruneProof(node);
            if (nodes == null) {
                return List.of();
            }

            return asNodeDesc(nodeId.proofId(), nodes.stream());
        });
    }

    /*
     * @Override
     * public CompletableFuture<Statistics> statistics(ProofId proofId) {
     * return async(() -> {
     * var proof = data.find(proofId);
     * return proof.getStatistics();
     * });
     * }
     */

    @Override
    public CompletableFuture<Boolean> save(ProofId proofId, String path) {
        return async(() -> {
            var proof = data.find(proofId);
            var saver = new OutputStreamProofSaver(proof);

            try {
                var file = new File(path);
                var writer = new FileOutputStream(file);
                saver.save(file.toPath(), writer);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return true;
        });
    }

    @Override
    public CompletableFuture<TreeNodeDesc> treeRoot(ProofId proof) {
        return CompletableFuture.completedFuture(
            TreeNodeDesc.from(proof, data.find(proof).root()));
    }

    @Override
    public CompletableFuture<NodeDesc> root(ProofId proofId) {
        return async(() -> {
            var proof = data.find(proofId);
            return asNodeDesc(proofId, proof.root());
        });
    }

    @Override
    public CompletableFuture<List<ProofId>> list() {
        return CompletableFuture.completedFuture(data.allProofIds());
    }


    @Override
    public CompletableFuture<List<TreeNodeDesc>> treeChildren(ProofId proof, TreeNodeId nodeId) {
        return async(() -> {
            var serial = Integer.parseInt(nodeId.id());

            Node root = data.find(proof).root();
            var stack = new Stack<Node>();
            stack.push(root);

            while (!stack.empty()) {
                var node = stack.pop();
                if (node.serialNr() == serial) {
                    var children = new ArrayList<TreeNodeDesc>();

                    var iter = node.childrenIterator();
                    while (iter.hasNext()) {
                        var child_node = iter.next();
                        children.add(TreeNodeDesc.from(proof, child_node));
                    }

                    return children;
                }

                var iter = node.childrenIterator();
                while (iter.hasNext()) {
                    var child_node = iter.next();
                    stack.push(child_node);
                }
            }

            return List.of();
        });
    }

    @Override
    public CompletableFuture<List<TreeNodeDesc>> treeSubtree(ProofId proof, TreeNodeId nodeId) {
        return async(() -> {
            var serial = Integer.parseInt(nodeId.id());
            Node root = data.find(proof).root();

            // locate the requested node by its serial number
            Node start = null;
            var search = new Stack<Node>();
            search.push(root);
            while (!search.empty()) {
                var node = search.pop();
                if (node.serialNr() == serial) {
                    start = node;
                    break;
                }
                var it = node.childrenIterator();
                while (it.hasNext()) {
                    search.push(it.next());
                }
            }
            if (start == null) {
                return List.of();
            }

            // collect the whole subtree rooted at `start`, in pre-order
            // (the node itself followed by its descendants)
            var result = new ArrayList<TreeNodeDesc>();
            var stack = new Stack<Node>();
            stack.push(start);
            while (!stack.empty()) {
                var node = stack.pop();
                result.add(TreeNodeDesc.from(proof, node));
                var children = new ArrayList<Node>();
                var it = node.childrenIterator();
                while (it.hasNext()) {
                    children.add(it.next());
                }
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.push(children.get(i));
                }
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<List<SortDesc>> sorts(EnvironmentId envId) {
        return async(() -> {
            var env = data.find(envId);
            var sorts = env.getServices().getNamespaces().sorts().allElements();
            return sorts.stream().map(SortDesc::from).toList();
        });
    }

    @Override
    public CompletableFuture<List<FunctionDesc>> functions(EnvironmentId envId) {
        return async(() -> {
            var env = data.find(envId);
            var functions = env.getServices().getNamespaces().functions().allElements();
            return functions.stream().map(FunctionDesc::from).toList();
        });
    }

    @Override
    public CompletableFuture<List<ContractDesc>> contracts(EnvironmentId envId) {
        return async(() -> {
            var env = data.find(envId);
            var contracts = env.getProofContracts();
            return contracts.stream().map(it -> ContractDesc.from(envId, env.getServices(), it))
                    .toList();
        });
    }

    @Override
    public CompletableFuture<ProofId> openContract(ContractId contractId) {
        return async(() -> {
            var env = data.find(contractId.envId());
            var contracts = env.getProofContracts();
            var contract =
                contracts.stream()
                        .filter(it -> Objects.equals(it.getName(), contractId.contractId()))
                        .findFirst();
            if (contract.isPresent()) {
                try {
                    var proof = env.createProof(contract.get().createProofObl(env.getInitConfig()));
                    return data.register(contractId.envId(), proof);
                } catch (ProofInputException e) {
                    throw new RuntimeException(e);
                }
            } else {
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> envDispose(EnvironmentId environmentId) {
        data.dispose(environmentId);
        return CompletableFuture.completedFuture(
            true);
    }

    @Override
    public CompletableFuture<NodeTextDesc> print(NodeId nodeId, PrintOptions options) {
        return async(() -> {
            var node = data.find(nodeId);
            var env = data.find(nodeId.proofId().env());
            var notInfo = new NotationInfo();
            final var layouter =
                new PosTableLayouter(options.width(), options.indentation(), false);
            var lp = new LogicPrinter(notInfo, env.getServices(), layouter);
            lp.printSequent(node.sequent());

            var id = new NodeTextId(nodeId, uniqueCounter.getAndIncrement());
            var t = new NodeText(lp.result(), layouter.getInitialPositionTable());
            data.register(id, t);

            String tacletApplicationInfo = null;
            var rule = node.getAppliedRuleApp();
            if (rule instanceof TacletApp tapp) {
                var taclet = tapp.taclet();
                tacletApplicationInfo = taclet.toString();
            }

            var terms = expandTermsForTable(layouter.getInitialPositionTable());
            return new NodeTextDesc(id, lp.result(), terms, tacletApplicationInfo);
        });
    }

    private NodeTextSpan[] expandTermsForTable(PositionTable table) {
        int nonEmptyRanges = 0;
        for (int i = 0; i < table.getRows(); i++) {
            if (table.getRange(i).length() != 0) {
                nonEmptyRanges++;
            }
        }

        var terms = new NodeTextSpan[nonEmptyRanges];
        int j = 0;
        for (int i = 0; i < table.getRows(); i++) {
            var range = table.getRange(i);
            if (range.length() == 0) {
                continue;
            }

            var children = expandTermsForTable(table.getChild(i));
            terms[j] = new NodeTextSpan(range.start(), range.end(), children);
            j++;
        }

        return terms;
    }

    @Override
    public CompletableFuture<List<TermActionDesc>> actions(NodeTextId printId, int caretPos) {
        return async(() -> {
            var node = data.find(printId.nodeId());
            var proof = data.find(printId.nodeId().proofId());
            var goal = proof.getOpenGoal(node);
            var nodeText = data.find(printId);

            var filter = new IdentitySequentPrintFilter();
            filter.setSequent(node.sequent());

            var pis = nodeText.table().getPosInSequent(caretPos, filter);
            return new TermActionUtil(printId, data.find(printId.nodeId().proofId().env()), pis,
                goal, caretPos)
                    .getActions();
        });

    }

    @Override
    public CompletableFuture<Boolean> applyAction(TermActionId id) {
        // FIXME: We can probably cache this work in `actions`.
        return async(() -> {
            var node = data.find(id.nodeTextId().nodeId());
            var proof = data.find(id.nodeTextId().nodeId().proofId());
            var goal = proof.getOpenGoal(node);
            var nodeText = data.find(id.nodeTextId());

            var filter = new IdentitySequentPrintFilter();
            filter.setSequent(node.sequent());

            var pis = nodeText.table().getPosInSequent(id.caretPos(), filter);
            var util = new TermActionUtil(id.nodeTextId(),
                data.find(id.nodeTextId().nodeId().proofId().env()), pis, goal, id.caretPos());

            var env = data.find(id.nodeTextId().nodeId().proofId().env());
            return util.applyAction(id, env.getServices());
        });
    }

    @Override
    public void freePrint(NodeTextId printId) {
        CompletableFuture.runAsync(() -> data.dispose(printId));
    }

    public void setClientApi(ClientApi remoteProxy) {
        clientApi = remoteProxy;
    }

    private final DefaultUserInterfaceControl control = new MyDefaultUserInterfaceControl();

    @Override
    public CompletableFuture<ProofId> loadExample(String name) {
        return CompletableFutures.computeAsync((c) -> {
            var examples = ExampleChooser.listExamples(ExampleChooser.lookForExamples())
                    .stream().filter(it -> it.getName().equals(name)).findFirst();
            if (examples.isPresent()) {
                var ex = examples.get();
                Proof proof = null;
                KeYEnvironment<?> env = null;
                try {
                    var loader = control.load(JavaProfile.getDefaultProfile(),
                        ex.getObligationFile(),
                        null, null, null, null, true, null);
                    InitConfig initConfig = loader.getInitConfig();

                    env = new KeYEnvironment<>(control, initConfig, loader.getProof(),
                        loader.getProofScript(), loader.getResult());
                    var envId = new EnvironmentId(env.toString());
                    data.register(envId, env);
                    proof = Objects.requireNonNull(env.getLoadedProof());
                    var proofId = new ProofId(envId, proof.name().toString());
                    return data.register(proofId, proof);
                } catch (ProblemLoaderException e) {
                    if (env != null)
                        env.dispose();
                    throw new RuntimeException(e);
                }
            }
            throw new IllegalArgumentException("Unknown example");
        });
    }

    @Override
    public CompletableFuture<ProofId> loadProblem(ProblemDefinition problem) {
        return CompletableFutures.computeAsync((c) -> {
            Proof proof = null;
            KeYEnvironment<?> env = null;
            /*
             * var loader = control.load(JavaProfile.getDefaultProfile(),
             * ex.getObligationFile(), null, null, null, null, true, null);
             * InitConfig initConfig = loader.getInitConfig();
             *
             * env = new KeYEnvironment<>(control, initConfig, loader.getProof(),
             * loader.getProofScript(), loader.getResult());
             * var envId = new EnvironmentId(env.toString());
             * data.register(envId, env);
             * proof = Objects.requireNonNull(env.getLoadedProof());
             * var proofId = new ProofId(envId, proof.name().toString());
             * return data.register(proofId, proof);
             */
            return null;
        });

    }

    @Override
    public CompletableFuture<ProofId> loadKey(String content) {
        return CompletableFutures.computeAsync((c) -> {
            Proof proof = null;
            KeYEnvironment<?> env = null;
            File tempFile = null;
            try {
                tempFile = File.createTempFile("json-rpc-", ".key");
                Files.writeString(tempFile.toPath(), content);
                var loader = control.load(JavaProfile.getDefaultProfile(),
                    tempFile.toPath(), null, null, null, null, true, null);
                InitConfig initConfig = loader.getInitConfig();
                env = new KeYEnvironment<>(control, initConfig, loader.getProof(),
                    loader.getProofScript(), loader.getResult());
                var envId = new EnvironmentId(env.toString());
                data.register(envId, env);
                proof = Objects.requireNonNull(env.getLoadedProof());
                var proofId = new ProofId(envId, proof.name().toString());
                return data.register(proofId, proof);
            } catch (ProblemLoaderException | IOException e) {
                if (env != null)
                    env.dispose();
                throw new RuntimeException(e);
            } finally {
                // The loader reads the problem from disk during loading, so the
                // temp file is no longer needed afterwards. Delete it to avoid
                // accumulating one file per loadKey/loadTerm/loadProblem call.
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile.toPath());
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        });
    }

    @Override
    public CompletableFuture<ProofId> loadTerm(String term) {
        return loadKey("\\problem{ " + term + " }");
    }

    @Override
    public CompletableFuture<Either<EnvironmentId, ProofId>> load(LoadParams params) {
        return CompletableFutures.computeAsync((c) -> {
            Proof proof;
            KeYEnvironment<?> env;
            try {
                var loader = control.load(JavaProfile.getDefaultProfile(),
                    params.problemFile() != null ? params.problemFile().asPath() : null,
                    params.classPath() != null
                            ? params.classPath().stream().map(Uri::asPath).toList()
                            : null,
                    params.bootClassPath() != null ? params.bootClassPath().asPath() : null,
                    params.includes() != null ? params.includes().stream().map(Uri::asPath).toList()
                            : null,
                    null,
                    true,
                    null);
                InitConfig initConfig = loader.getInitConfig();
                env = new KeYEnvironment<>(control, initConfig, loader.getProof(),
                    loader.getProofScript(), loader.getResult());
                var envId = new EnvironmentId(env.toString());
                data.register(envId, env);
                if ((proof = env.getLoadedProof()) != null) {
                    var proofId = new ProofId(envId, proof.name().toString());
                    return Either.forRight(data.register(proofId, proof));
                } else {
                    return Either.forLeft(envId);
                }
            } catch (ProblemLoaderException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private class MyDefaultUserInterfaceControl extends DefaultUserInterfaceControl {
        @Override
        public void taskStarted(org.key_project.prover.engine.TaskStartedInfo info) {
            clientApi.taskStarted(TaskStartedInfo.from(info));
        }

        @Override
        public void taskProgress(int position) {
            clientApi.taskProgress(position);
        }

        @Override
        public void taskFinished(TaskFinishedInfo info) {
            clientApi.taskFinished(org.keyproject.key.api.data.TaskFinishedInfo.from(info));
        }

        @Override
        protected void macroStarted(org.key_project.prover.engine.TaskStartedInfo info) {
            clientApi.taskStarted(TaskStartedInfo.from(info));
        }

        @Override
        protected synchronized void macroFinished(ProofMacroFinishedInfo info) {
            clientApi.taskFinished(org.keyproject.key.api.data.TaskFinishedInfo.from(info));
        }

        @Override
        public void loadingStarted(AbstractProblemLoader loader) {
            super.loadingStarted(loader);
        }

        @Override
        public void loadingFinished(AbstractProblemLoader loader,
                IPersistablePO.LoadedPOContainer poContainer, ProofAggregate proofList,
                AbstractProblemLoader.ReplayResult result) throws ProblemLoaderException {
            super.loadingFinished(loader, poContainer, proofList, result);
        }

        @Override
        public void progressStarted(Object sender) {
            super.progressStarted(sender);
        }

        @Override
        public void progressStopped(Object sender) {
            super.progressStopped(sender);
        }

        @Override
        public void reportStatus(Object sender, String status, int progress) {
            super.reportStatus(sender, status, progress);
        }

        @Override
        public void reportStatus(Object sender, String status) {
            super.reportStatus(sender, status);
        }

        @Override
        public void resetStatus(Object sender) {
            super.resetStatus(sender);
        }

        @Override
        public void reportException(Object sender, ProofOblInput input, Exception e) {
            super.reportException(sender, input, e);
        }

        @Override
        public void setProgress(int progress) {
            super.setProgress(progress);
        }

        @Override
        public void setMaximum(int maximum) {
            super.setMaximum(maximum);
        }

        @Override
        public void reportWarnings(ImmutableSet<PositionedString> warnings) {
            super.reportWarnings(warnings);
        }

        @Override
        public void showIssueDialog(Collection<PositionedString> issues) {
            super.showIssueDialog(issues);
        }
    }
}
