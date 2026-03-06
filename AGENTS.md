# Repository Guidelines

## Project Structure & Module Organization
The IntelliJ plugin code lives in `src/main/kotlin/com/github/halmuratuyghur/rally`, grouped by feature (`api`, `ui`, `settings`, `export`). UI resources and `META-INF/plugin.xml` stay under `src/main/resources`. Built ZIPs land in `build/distributions`, while Gradle and wrapper files (`build.gradle.kts`, `gradlew`) sit at the repo root. Keep Rally-specific assets (icons, mock data) beside the feature code they support.

## Build, Test, and Development Commands
- `./gradlew buildPlugin` — compiles the Kotlin sources, runs unit tests, and assembles the distributable ZIP.
- `./gradlew runIde` — launches a sandboxed IntelliJ IDEA with the plugin installed; ideal for smoke tests.
- `./gradlew test` — executes JVM tests once we add them under `src/test`.
- `./run-test-ide.sh` — convenience wrapper that enforces Java 21, builds quietly, and then calls `runIde`.

## Coding Style & Naming Conventions
Follow Kotlin official style with 4-space indentation and trailing commas where JetBrains formatter inserts them. UI code favors explicit Swing types (`JBLabel`, `ComboBox`) and `val` over `var` except for mutable UI state. Keep packages singular (`settings`, not `setting`), align file names with public classes, and register extensions in `plugin.xml` using lowerCamelCase IDs. Run `./gradlew ktlintFormat` if you introduce ktlint; otherwise rely on IDE auto-format (`⌥⌘L`).

## Testing Guidelines
Primary manual verification uses `./gradlew runIde`, as outlined in `TESTING_GUIDE.md`. When adding automated tests, place them under `src/test/kotlin`, mirror the production package path, and name files with the `*Test.kt` suffix. Favor JUnit 5 with Truth/Kotest style assertions. Keep API fixtures deterministic and gate network calls behind fakes.

## Commit & Pull Request Guidelines
History shows Conventional Commit headers (`feat(ui): …`, `fix(api): …`), so continue using `<type>(<scope>): <imperative>` with concise bodies describing rationale and testing evidence. Each pull request should link the Rally artifact or GitHub issue, describe user-facing impact, include before/after screenshots for UI tweaks, and mention how `runIde` or `./gradlew test` was used to verify the change. Request at least one reviewer familiar with IntelliJ plugin APIs.

## Security & Configuration Tips
Never commit Rally API keys; load them from IDE settings or environment variables when scripting. When sharing logs, scrub `zsessionid` headers and workspace IDs. The plugin targets WSAPI v2.0—test against non-production workspaces before toggling server URLs, and default branches should remain free of proprietary customer data.
