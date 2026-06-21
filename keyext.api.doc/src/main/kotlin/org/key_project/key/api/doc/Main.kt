package org.key_project.key.api.doc

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.Json
import org.key_project.key.api.doc.Metamodel.KeyApi
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

/**
 * @author Alexander Weigl
 * @version 1 (7/8/25)
 */
class Main : CliktCommand(name = "gendoc") {
    private val source: Path by option(
        "-s", "--source",
        metavar = "FOLDER", help = "Source folder for getting JavaDoc"
    )
        .path(mustExist = true, canBeFile = false, canBeDir = true)
        .default(Paths.get("keyext.api/src/main/java"))

    private val outputPython: Path by option("--output-python", help = "Output folder")
        .path().default(Paths.get("../keyext.client.python/"))

    private val outputJava: Path by option("--output-java", help = "Output folder")
        .path().default(Paths.get("../keyext.api.client/src/gen/java/"))

    private val outputWeb: Path by option("--output-web", help = "Output folder")
        .path().default(Paths.get("../out"))

    override fun run() {
        val metadata = ExtractMetaData()
        metadata.run()

        Files.createDirectories(outputWeb)
        Files.createDirectories(outputPython)
        Files.createDirectories(outputJava)

        runGenerator(metadata.api, "api.meta.json", outputWeb) {
            Json {
                prettyPrint = true
            }.encodeToString(it)
        }

        generatePages(metadata.api, outputWeb)

        runGenerator(metadata.api, "keydata.py", outputPython) { PythonGenerator.PyDataGen(it).get() }
        runGenerator(metadata.api, "server.py", outputPython) { PythonGenerator.PyApiGen(it).get() }

        // Java
        val cus = listOf(
            JavaGenerator.JavaApiGenServer(metadata.api),
            JavaGenerator.JavaApiGenClient(metadata.api),
            JavaGenerator.JavaDataGen(metadata.api)
        )
        cus.asSequence().map { it.get() }.forEach {
            val resolve = outputJava.resolve(it.packageDeclaration()?.nameAsString?.replace('.', '/') ?: "")
            resolve.createDirectories()
            it.setStorage(resolve.resolve(it.types.first().nameAsString + ".java"))
            it.storage.get().path.writeText(
                it.toString()
            )
        }
    }

    // / Poor man's static site generator
    private fun generatePages(metadata: KeyApi, outputWeb: Path) {
        val base = Paths.get("../doc")
        val resources = base.walk().toList()

        // copy non-markdown files
        resources.filter { it.nameWithoutExtension != "md" }
            .forEach { copyResources(it, base, outputWeb) }

        val dokkaLink = Link("dokka/index.html", "Dokka", 11000)
        val keyLink = Link("https://keyproject.github.io/key-docs/", "KeY Documentation", 10000)
        val bookLink = Link("https://key-project.org/thebook2", "KeY Book", 10001)

        // generate html pages
        val pages = (
            listOf(ReferencePageResource(), dokkaLink, keyLink, bookLink) +
            resources.filter { it.extension == "md" }.map { MdPageResource(it) }
        )
                .sortedBy { it.order }

        pages.forEach {
            val target = outputWeb / it.relativeHtmlPath
            it.write(target, metadata, pages)
        }
    }

    private fun copyResources(source: Path, base: Path, targetFolder: Path) {
        val target = targetFolder / (source.relativeTo(base))
        target.deleteIfExists()
        source.copyTo(target)
    }

    private fun runGenerator(keyApi: KeyApi, target: String, folder: Path, api: (KeyApi) -> String) {
        try {
            val n: String = api(keyApi)
            Files.writeString(folder.resolve(target), n)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}

fun main(args: Array<String>) = Main().main(args)
