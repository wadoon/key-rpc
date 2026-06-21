import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins { id("org.jetbrains.dokka") }

repositories { mavenCentral() }

dependencies {
    dokka(project(":keyext.api"))
    dokka(project(":keyext.api.doc"))
    dokka(project(":keyext.api.app"))
    dokka(project(":keyext.api.client"))
}

dokka {
    dokkaPublications.html {
        includes.from(file("gradle/index.md"))
        suppressInheritedMembers.set(false)
        suppressObviousFunctions.set(true)
        offlineMode.set(false)
    }
}