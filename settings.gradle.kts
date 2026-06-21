plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "key-rpc"
include("keyext.api.app", "keyext.api", "keyext.api.doc", "keyext.api.client")
include("keyext.api.ktclient")
