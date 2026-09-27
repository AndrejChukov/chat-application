plugins {
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.chat"
version = "1.0.0"

repositories {
    mavenCentral()
}

val vertxVersion = "4.5.10"

dependencies {
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("io.vertx:vertx-pg-client:$vertxVersion")
    implementation("com.ongres.scram:client:2.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.8")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.chat.Main")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("chat-application.jar")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
