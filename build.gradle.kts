plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.github.halmuratuyghur"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

// Configure Gradle IntelliJ Plugin
intellij {
    version.set("2024.1")
    type.set("IC") // IntelliJ IDEA Community Edition
    plugins.set(listOf("vcs-git"))
    sandboxDir.set(layout.projectDirectory.dir(".sandbox").toString())
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("253.*")

        changeNotes.set("""
            <h3>1.0.0</h3>
            <ul>
                <li>Browse User Stories and Defects with scope, state, project, and sprint filters</li>
                <li>Detail panel with description, test cases, tasks, attachments, and test steps</li>
                <li>Create User Stories with project, sprint, and owner assignment</li>
                <li>Change ticket state (Defined, In-Progress, Completed)</li>
                <li>Export artifacts and test cases to JSON/Markdown</li>
                <li>Sprint summary, auto-load on startup, parallel API queries with caching</li>
            </ul>
        """.trimIndent())
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    buildSearchableOptions {
        enabled = false
    }
}
