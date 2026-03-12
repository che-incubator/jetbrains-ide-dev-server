plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.1"
}

group = "io.github.che.integration"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2025.1.4.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.kubernetes:client-java:25.0.0") {
        // Exclude Jackson from being bundled - IDE already provides it
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-annotations")
    }
    // Bundle only the Kotlin module classes, exclude Jackson core (already provided by IDE)    // IDE already provides Jackson core classes, we just need the Kotlin module
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2") {
        // Exclude Jackson core dependencies - use IDE's version at runtime
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-annotations")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    
    // Exclude from plugin JAR - IDE already provides them
    // Keep the Kotlin module classes (com.fasterxml.jackson.module.kotlin)
    jar {
        exclude("com/fasterxml/jackson/core/**")
        exclude("com/fasterxml/jackson/databind/**")
        exclude("com/fasterxml/jackson/annotation/**")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
