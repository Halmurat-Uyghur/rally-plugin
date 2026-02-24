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
│   ├── RallyApiModels.kt         # Data classes (RallyUserStory, RallyDefect, RallyTaskItem, RallyTestCase, RallyTestCaseStep, RallyAttachment, etc.)
│   └── RallyApiException.kt      # Exception hierarchy
├── export/
│   └── RallyExporter.kt          # JSON/Markdown export for artifacts and test cases (with attachments + inline images)
├── settings/
│   ├── RallySettings.kt          # Application-level persistent settings (@State)
│   └── RallySettingsConfigurable.kt  # Settings UI (Settings > Tools > Rally)
└── ui/
    ├── RallyToolWindowFactory.kt  # ToolWindowFactory + DumbAware
    ├── RallyToolWindowPanel.kt    # Main UI: toolbar, filters, project switcher, ticket list, detail panel, sprint summary
    ├── RallyDetailPanel.kt        # Detail panel: description (HTML) + linked test cases list with context menu
    └── RallyIcons.kt             # Icon loader for /icons/rally.svg
```

## Key Design Decisions

- **Application-level settings** — Rally credentials are per-user, not per-project. Settings: Server URL, API Key, Workspace Ref, Username, Export Directory
- **Project switcher in tool window** — Projects are fetched from Rally API (`queryProjects()`) and shown in a dropdown in the toolbar. Selection is persisted in `selectedProject` setting and restored on next launch. "All Projects" queries across the entire workspace
- **Split pane layout** — Main panel uses a horizontal split: ticket list (left) + detail panel (right). Detail panel has a vertical split: description (top, 40%) + test cases (bottom, 60%)
- **Lazy test case loading** — Test cases are fetched on ticket selection, with a stale-response guard (`currentArtifactRef`) to prevent race conditions
- **Threading**: `executeOnPooledThread` for API calls, `invokeLater` for UI updates
- **No external Rally SDK** — uses Java's built-in `HttpClient` with `zsessionid` header for API key auth
- **State field logic**: User Stories and Defects use `ScheduleState`, Tasks use `State`
- **Client-side state filtering** — because ScheduleState vs State differs by artifact type, filter queries for state are applied client-side after fetching
- **Server-side owner filtering** — `(Owner.UserName = "...")` is applied as a Rally query
- **Workspace/Project refs** — Rally WSAPI requires full API URLs for workspace/project params. The `normalizeRef()` method in RallyApiClient handles conversion from bare IDs, ref paths, or full URLs
- **Generic plugin design** — No workflow-specific or company-specific custom fields hardcoded. Only standard Rally fields (Method, ScheduleState, etc.) are used

## Rally WSAPI Gotchas

- Query syntax requires binary nesting for AND/OR: `((a) AND ((b) AND (c)))` not `(a AND b AND c)`
- `/user` endpoint returns `{"User": {...}}` with API key auth, not `{"QueryResult": {...}}`
- Workspace and project must be passed as URL params (not query conditions) and must be full Rally API URLs
- `ScheduleState` values: Idea, Defined, In-Progress, Completed, Accepted
- `State` values (for Defects): Submitted, Open, Fixed, Closed
- Test cases use `WorkProduct` ref to link to parent user stories/defects
- Attachment content is fetched via a Content ref that returns base64-encoded data
- Inline images in descriptions use `/slm/attachment/<OID>/<filename>` URLs, downloaded via zsessionid auth

## Build & Run

```bash
./gradlew clean build          # Build plugin
./gradlew verifyPlugin         # Verify plugin structure
./gradlew runIde               # Launch sandbox IDE with plugin
```

Warnings during `runIde` about GradleJvmSupportMatrix, Maven, or memory leaks on UI switch are IntelliJ 2024.1 internal issues — not from this plugin.

## Current Filter Options (in Tool Window)

All Tickets, My Tickets, User Stories, Defects, Recent Activity

## What's Implemented

- Tool window with ticket list, search, filters, and sprint summary
- **Detail panel** — selecting a ticket shows its HTML description and linked test cases with Method (Automated/Manual) badges and LastVerdict (Pass/Fail)
- Project switcher dropdown — fetches projects from Rally, persists selection across sessions
- Iteration (sprint) switcher dropdown
- Settings page (Server URL, API Key, Workspace Ref, Username, Export Directory) with Test Connection and folder chooser
- State change actions (In-Progress, Completed, Defined) via toolbar and context menu — supports multi-select
- Open in Browser, Copy FormattedID via context menu
- **Export** — toolbar Export button exports selected artifact(s) + their linked test cases to JSON and Markdown. Also available via right-click context menu on both artifact list and test case list
- **Test case context menu** — Mark Automated (sets Method field), Export to JSON/Markdown, Open in Browser
- Inline image downloading during export (replaces Rally image URLs with local paths)
- Attachment downloading during export (via base64 content API)
- Create User Story API method (not yet wired to UI)

## API Methods (RallyApiClient)

| Method | Purpose |
|--------|---------|
| `queryUserStories()` | Query user stories with filters |
| `queryDefects()` | Query defects with filters |
| `queryTasks()` | Query tasks with filters |
| `queryAllArtifacts()` | Combined user stories + defects |
| `queryTestCases(workProductRef)` | Test cases linked to a user story/defect |
| `queryTestCaseByFormattedId(id)` | Single test case lookup |
| `queryTestSteps(formattedId)` | Test steps for a test case |
| `queryAttachments(formattedId)` | Attachments for any artifact |
| `queryUnautomatedTestCases()` | All TCs where Method != Automated |
| `updateArtifactState(ref, type, state)` | Change ScheduleState/State |
| `updateTestCaseField(ref, field, value)` | Update any field on a test case |
| `getAttachmentContent(contentRef)` | Get base64 attachment content |
| `downloadAttachment(url)` | Download attachment bytes via HTTP |
| `queryProjects()` / `queryIterations()` | Project and sprint lists |
| `createUserStory()` | Create a new user story |

## Not Yet Implemented

- Create User Story dialog (API exists in RallyApiClient, needs UI)
- Create Test Case dialog
- Test case steps viewer in detail panel
