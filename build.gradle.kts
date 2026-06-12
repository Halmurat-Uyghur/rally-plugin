import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.16.0"
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

    pluginVerification {
        ides {
            // Pinned instead of the default dynamic recommended() list: that feed now
            // serves 2025.3.x distributions, whose layout drops
            // modules/module-descriptors.jar — the newest Plugin Verifier (1.405) cannot
            // read them (InvalidIdeException) and the whole verifyPlugin task dies before
            // verifying anything else. This list covers the declared 241–261 range minus
            // that one unreadable release line; re-add 2025.3 (or go back to
            // recommended()) once the verifier understands the new layout.
            create("IC", "2024.1.7")
            create("IC", "2024.2.6")
            create("IC", "2024.3.7.1")
            create("IC", "2025.1.7.1")
            create("IC", "2025.2.6.2")
            create("IU", "2026.1.3")
        }
    }

    signing {
        // CERTIFICATE_CHAIN and PRIVATE_KEY carry PEM *content* (the convention the
        // 1.x DSL established), not file paths — map them to the 2.x content
        // properties, not certificateChainFile/privateKeyFile. Mapping content to
        // the file properties treats a multi-line PEM blob as a project-relative
        // path: file-not-found at signPlugin, or InvalidPathException on Windows
        // at configuration time.
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
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
