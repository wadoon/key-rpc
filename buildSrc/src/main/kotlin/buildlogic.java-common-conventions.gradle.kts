import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    java
    id("com.diffplug.spotless")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

spotless {
    java {
        targetExclude("build/**")
        toggleOffOn()
        removeUnusedImports()
        eclipse().configFile("$rootDir/gradle/keyCodeStyle.xml")
        trimTrailingWhitespace()
        importOrder("java|javax", "de.uka", "org.key_project", "", "\\#")
        licenseHeaderFile("$rootDir/gradle/header", "(package|import|//)")
    }

    kotlin {
        target("src/**/*.kt")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("*.gradle.kts", "buildSrc/**/*.gradle.kts")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dokka {
    dokkaSourceSets {
        configureEach {
            documentedVisibilities.set(setOf(VisibilityModifier.Protected))
            reportUndocumented.set(false)
            skipEmptyPackages.set(true)
            skipDeprecated.set(false)
            suppressGeneratedFiles.set(true)
            //samples.from("samples/Basic.kt", "samples/Advanced.kt")

            sourceLink {
                remoteUrl("https://github.com/keyproject/key-rpc/tree/main/")
                remoteLineSuffix.set("#L")
                localDirectory.set(rootDir)
            }
            perPackageOption {
                // Package options section
            }
            externalDocumentationLinks {
                register("key.core") {
                    url("https://javadoc.io/doc/org.key-project/key.core/latest/")
                    packageListUrl("https://javadoc.io/doc/org.key-project/key.core/2.12.3/element-list")
                }

                register("key.util") {
                    url("https://javadoc.io/doc/org.key-project/key.util")
                    packageListUrl("https://javadoc.io/doc/org.key-project/key.util/2.12.3/element-list")
                }

                register("key.ui") {
                    url("https://javadoc.io/doc/org.key-project/key.ui/latest/")
                    packageListUrl("https://javadoc.io/doc/org.key-project/key.ui/2.12.3/element-list")
                }

                externalDocumentationLinks.register("guava") {
                    url("https://javadoc.io/doc/com.google.guava/guava/latest/")
                    packageListUrl("https://javadoc.io/doc/com.google.guava/guava/33.0.0-jre/element-list")
                }
            }
        }
    }

    dokkaPublications.html {
        //moduleName.set()
        val exists = layout.projectDirectory.file("README.md").asFile
        if (exists.exists()) {
            includes.from("README.md")
        }
    }
}

// To generate documentation in HTML
val dokkaHtmlJar by tasks.registering(Jar::class) {
    description = "A HTML Documentation JAR containing Dokka HTML"
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("html-doc")
}

/*
// To generate documentation in Javadoc
val dokkaJavadocJar by tasks.registering(Jar::class) {
    description = "A Javadoc JAR containing Dokka Javadoc"
    from(tasks.dokkaGeneratePublicationJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}
*/