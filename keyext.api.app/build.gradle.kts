plugins {
    id("buildlogic.java-application-conventions")
    application
    id("com.gradleup.shadow") version "9.4.2"
}

description "Verification server interface via JSON-RPC"

application {
    mainClass.set("org.keyproject.key.api.StartServer")
}
