import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.github.halmurat"
version = "1.0.1"

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
    // Building searchable options boots a headless IDE per buildPlugin run to index the
    // Settings page, so Settings search finds "API key", "workspace", etc. Every shipped ZIP
    // is built locally (there is no CI), so the index is ON by default; pass
    // -PskipSearchableOptions=true on the command line to skip it while iterating (L10).
    // Don't put it in ~/.gradle/gradle.properties: release ZIPs would silently lose the index.
    buildSearchableOptions = providers.gradleProperty("skipSearchableOptions")
        .map { !it.toBoolean() }
        .orElse(true)
    // This module has zero .form files and no @NotNull-annotated Java (its one .java file,
    // CredentialAttributesCompat, exists only to bind a non-deprecated constructor), so the
    // form binding / @NotNull bytecode instrumentation pass has nothing to do — disabling
    // it removes pure build overhead.
    instrumentCode = false
    sandboxContainer = layout.projectDirectory.dir(".sandbox")

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "262.*"
        }

        changeNotes = """
            <h3>1.0.1</h3>
            <ul>
                <li>Replaced deprecated platform APIs</li>
            </ul>
            <h3>1.0.0</h3>
            <p>First public release.</p>
            <ul>
                <li>Tool window for User Stories, Defects, and Test Cases with search and scope, state, project, and sprint filters</li>
                <li>Detail panel with description, linked test cases (and their steps), tasks, and attachments</li>
                <li>Create User Stories, Defects, and Tasks</li>
                <li>Change ticket state (multi-select) and edit story points</li>
                <li>Start Working: create a Git branch, move the ticket to In-Progress, and assign it to you</li>
                <li>Export tickets and linked test cases to JSON and Markdown, with attachments and images</li>
                <li>Sprint summary with points, planned velocity, and days remaining</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        // Deprecated and scheduled-for-removal API usages fail verifyPlugin, not just
        // incompatibilities, so a new deprecation shows up before Marketplace flags it.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
        ides {
            recommended()
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
        // The plugin runs on the IDE's bundled Kotlin stdlib (not bundled here), which is
        // 1.9.x on 2024.1/2024.2 (the sinceBuild floor). Pin the API level so a stdlib call
        // added in 2.0 fails to compile instead of throwing NoSuchMethodError on those IDEs.
        compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
}
