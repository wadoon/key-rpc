plugins {
    id("org.jetbrains.dokka")
    `java-library`
}

repositories {
    mavenCentral()
}

tasks.named<JavaCompile>("compileJava") {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jspecify:jspecify:1.0.0")
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    testCompileOnly("org.projectlombok:lombok:1.18.46")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.46")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

sourceSets.main.get().java.srcDir("src/gen/java")