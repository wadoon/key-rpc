/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package edu.kit.keyext.client;

import java.io.IOException;

import org.key_project.key.api.client.BaseRemote;
import org.key_project.key.api.client.RPCLayer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Manual integration demo: spawns a built KeY server jar and performs one call.
 * Disabled in CI because it needs a freshly built {@code keyext.api} all-in-one
 * jar on disk.
 *
 * @author Alexander Weigl
 * @version 1 (24.11.24)
 */
public class Starter {
    @Test
    @Disabled("manual integration demo; requires a built keyext.api server jar")
    void test() throws IOException {
        var file = "../keyext.api/build/libs/keyext.api-2.12.4-dev-all.jar";
        final var rpcLayer = RPCLayer.startWithCLI(file);
        rpcLayer.start();
        var remote = new BaseRemote(rpcLayer);
        String version = remote._call_sync(String.class, "meta/version");
        System.out.println(version);
    }
}
