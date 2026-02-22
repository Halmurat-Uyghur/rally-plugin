# Rally Plugin for IntelliJ IDEA

## Project Overview

IntelliJ IDEA plugin that provides a **Tool Window** for browsing and managing Rally (Broadcom Agile Central) work items directly from the IDE. Uses Rally WSAPI 2.0 REST API with API key authentication.

## Tech Stack

- **Language**: Kotlin 1.9.25 (JVM 17)
- **Build**: Gradle with Kotlin DSL, `org.jetbrains.intellij` plugin 1.17.4
- **Target IDE**: IntelliJ IDEA Community 2024.1 (builds 241–243.*)
- **Dependencies**: Gson 2.10.1 (JSON), JUnit 4.13.2 (tests)
- **Plugin ID**: `com.github.rally` (NOT `com.intellij.*` — that prefix is reserved by JetBrains)

## Architecture

```
src/main/kotlin/com/intellij/plugins/rally/
├── api/
│   ├── RallyApiClient.kt         # HTTP client for Rally WSAPI 2.0
│   ├── RallyApiModels.kt         # Data classes (RallyUserStory, RallyDefect, RallyTaskItem, etc.)
│   └── RallyApiException.kt      # Exception hierarchy
├── settings/
│   ├── RallySettings.kt          # Application-level persistent settings (@State)
│   └── RallySettingsConfigurable.kt  # Settings UI (Settings > Tools > Rally)
└── ui/
    ├── RallyToolWindowFactory.kt  # ToolWindowFactory + DumbAware
    ├── RallyToolWindowPanel.kt    # Main UI: toolbar, filters, ticket list, sprint summary
    └── RallyIcons.kt             # Icon loader for /icons/rally.svg
```

## Key Design Decisions

- **Application-level settings** — Rally credentials are per-user, not per-project
- **Threading**: `executeOnPooledThread` for API calls, `invokeLater` for UI updates
- **No external Rally SDK** — uses Java's built-in `HttpClient` with `zsessionid` header for API key auth
- **State field logic**: User Stories and Defects use `ScheduleState`, Tasks use `State`
- **Client-side state filtering** — because ScheduleState vs State differs by artifact type, filter queries for state are applied client-side after fetching
- **Server-side owner filtering** — `(Owner.UserName = "...")` is applied as a Rally query
- **Workspace/Project refs** — Rally WSAPI requires full API URLs for workspace/project params. The `normalizeRef()` method in RallyApiClient handles conversion from bare IDs, ref paths, or full URLs

## Rally WSAPI Gotchas

- Query syntax requires binary nesting for AND/OR: `((a) AND ((b) AND (c)))` not `(a AND b AND c)`
- `/user` endpoint returns `{"User": {...}}` with API key auth, not `{"QueryResult": {...}}`
- Workspace and project must be passed as URL params (not query conditions) and must be full Rally API URLs
- `ScheduleState` values: Idea, Defined, In-Progress, Completed, Accepted
- `State` values (for Defects): Submitted, Open, Fixed, Closed

## Build & Run

```bash
./gradlew clean build          # Build plugin
./gradlew verifyPlugin         # Verify plugin structure
./gradlew runIde               # Launch sandbox IDE with plugin
```

Warnings during `runIde` about GradleJvmSupportMatrix, Maven, or memory leaks on UI switch are IntelliJ 2024.1 internal issues — not from this plugin.

## Current Filter Options (in Tool Window)

All Tickets, My Tickets, My In-Progress, My Defined, My Idea, My Completed, Active Tickets, My User Stories, My Defects, Recent Activity

## What's Implemented

- Tool window with ticket list, search, filters, and sprint summary
- Settings page with Test Connection
- State change actions (In-Progress, Completed, Defined) via toolbar and context menu
- Open in Browser, Copy FormattedID via context menu
- Create User Story API method (not yet wired to UI)

## Not Yet Implemented

- Create User Story dialog (API exists in RallyApiClient, needs UI)
- Bulk state changes (multi-select)
- RallyTestCase model / unautomated test cases view
