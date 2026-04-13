# Rally Integration Plugin

## Project Overview

This is a code project for an **IntelliJ IDEA Plugin** that integrates **Rally** (Broadcom Agile Central) directly into the IDE. It allows developers to browse, view details, search, and work with Rally User Stories, Defects, and Tasks without switching context to a web browser.

**Key Technologies:**
- **Language:** Kotlin (1.9.25), Java 17
- **Build System:** Gradle (8.5+) using `build.gradle.kts`
- **Platform:** IntelliJ Platform Plugin SDK (version 2024.1)
- **API:** Rally WSAPI v2.0 REST API using `Gson` for JSON serialization/deserialization.

**Architecture:**
The plugin follows a layered architecture focused on responsiveness and separation of concerns:
- **Gateway Layer (`api/`):** `RallyApiClient` handles HTTP communication with the Rally WSAPI, encapsulating authentication, caching, and mapping to typed DTOs (`RallyApiModels`).
- **Domain Services (`settings/`, `export/`):** Manages persisted plugin configuration (`RallySettings`) and feature-specific logic like exporting artifacts.
- **Presentation Layer (`ui/`):** `RallyToolWindowPanel` and `RallyDetailPanel` handle the UI. UI interactions delegate to domain services via coroutines/futures to keep network calls off the Event Dispatch Thread (EDT).

## Building and Running

The project uses the Gradle wrapper for all build and execution tasks:

- **Build the plugin:**
  ```bash
  ./gradlew buildPlugin
  ```
  *(The output will be in `build/distributions/rally-plugin-1.0.0.zip`)*

- **Run in a test IDE:**
  ```bash
  ./gradlew runIde
  # Alternatively, use the provided shell script:
  ./run-test-ide.sh
  ```

- **Run unit tests:**
  ```bash
  ./gradlew test
  ```

- **Verify plugin compatibility:**
  ```bash
  ./gradlew verifyPlugin
  ```

## Development Conventions

When contributing or modifying this codebase, adhere to the following guidelines inferred from the architecture and documentation:

- **Coding Style:** Follow standard [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html). Use clear, meaningful names for variables and functions.
- **Threading Model:** **Crucial:** Never make network calls or perform heavy computation on the UI thread (EDT). Controllers must dispatch work to background threads/executors and marshal results back using `invokeLater` or similar mechanisms.
- **Documentation:** Provide KDoc comments for public APIs and complex logic.
- **Testing:** Write unit tests for new features. HTTP logic should be verifiable via contract/mock tests.
- **Security:** Never log API keys or sensitive credentials. Ensure sensitive fields are redacted at the API gateway boundary.
- **Dependencies:** The project relies heavily on `Gson` for JSON processing. Ensure changes to `RallyApiModels.kt` correctly map to the Rally WSAPI JSON structure.