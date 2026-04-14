import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.github.halmuratuyghur"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")

    // Configure IntelliJ Platform target + bundled plugins through the
    // 2.x DSL (replaces the old `intellij { ... plugins.set(...) }` block).
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    buildSearchableOptions = true
    instrumentCode = true
    sandboxContainer = layout.projectDirectory.dir(".sandbox")

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "261.*"
        }

        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
                <li>Browse User Stories and Defects with scope, state, project, and sprint filters</li>
                <li>Detail panel with description, test cases, tasks, attachments, and test steps</li>
                <li>Create User Stories with project, sprint, and owner assignment</li>
                <li>Change ticket state (Defined, In-Progress, Completed)</li>
                <li>Export artifacts and test cases to JSON/Markdown</li>
                <li>Sprint summary, auto-load on startup, parallel API queries with caching</li>
            </ul>
        """.trimIndent()
    }

    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN").map { layout.projectDirectory.file(it) }.orNull
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY").map { layout.projectDirectory.file(it) }.orNull
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
