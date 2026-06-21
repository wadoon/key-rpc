import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    id("buildlogic.java-library-conventions")
}

dependencies {
    api(libs.key.core)
    api(libs.key.ui)

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
