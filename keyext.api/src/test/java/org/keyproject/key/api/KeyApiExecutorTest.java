/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.keyproject.key.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Smoke test that operations dispatched through the dedicated executor (instead
 * of the common ForkJoinPool) still complete correctly.
 */
class KeyApiExecutorTest {
    @Test
    void executorBackedOperationsComplete() throws Exception {
        var api = new KeyApiImpl();
        var proofId = api.loadTerm("true").get();

        // root()/goals() are dispatched on the dedicated executor; a completing
        // future proves the executor accepts and runs the task.
        var root = api.root(proofId).get();
        Assertions.assertNotNull(root);
        var goals = api.goals(proofId, false, false).get();
        Assertions.assertNotNull(goals);
        Assertions.assertFalse(goals.isEmpty());
    }
}
