plugins {
    id("com.diffplug.spotless")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlin.multiplatform")

}

repositories{mavenCentral()}

kotlin {
    jvm("desktop")
    js() {
        browser()
        nodejs()
    }

    // --- Source Sets ---
    sourceSets {
        commonMain.dependencies {
            //implementation(libs.kotlinx.coroutines.core)
            //implementation(libs.kotlinx.serialization.json)
            //implementation(libs.ktor.client.core)
        }

        /*
        androidMain.dependencies {
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        */
        val desktopMain by getting {
            dependencies {
                //implementation(libs.ktor.client.cio)
            }
        }
    }
}

//sourceSets.main.get().kotlin.srcDir("src/gen/kotlin")