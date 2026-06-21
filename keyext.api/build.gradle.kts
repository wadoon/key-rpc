plugins {
    id("buildlogic.java-library-conventions")
}

dependencies {
    api(libs.key.core)
    api(libs.key.ui) {
        // saves 50% -> ca. 30 MB.
        exclude(group = "org.key-project", module = "keyext.proofmanagement")
        exclude(group = "org.key-project", module = "keyext.isabelletranslation")
        exclude(group = "org.key-project", module = "keyext.slicing")
        exclude(group = "org.key-project", module = "keyext.caching")
        exclude(group = "org.key-project", module = "keyext.ui.testgen")
        exclude(group = "org.key-project", module = "key.core.testgen")
        exclude(group = "org.key-project", module = "key.core.symbolic_execution")
        exclude(group = "org.key-project", module = "key.core.proof_references")
    }

    api(libs.lsp4j.jsonrpc)
    implementation(libs.lsp4j.websocket.jakarta)
    implementation(libs.jetty.websocket.javax.server)
    implementation(libs.picocli)
    implementation(libs.guava)
    annotationProcessor(libs.therapi.runtime.javadoc.scribe)
    api(libs.therapi.runtime.javadoc)
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-parameters") // for having parameter name in reflection
}
