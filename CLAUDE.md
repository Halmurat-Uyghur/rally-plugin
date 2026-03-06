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
src/main/kotlin/com/github/halmuratuyghur/rally/
├── api/
│   ├── RallyApiClient.kt         # HTTP client for Rally WSAPI 2.0 (with caching + parallel queries)
│   ├── RallyApiModels.kt         # Data classes (RallyUserStory, RallyDefect, RallyTaskItem, RallyTestCase, RallyTestCaseStep, RallyAttachment, etc.)
│   └── RallyApiException.kt      # Exception hierarchy
├── export/
│   └── RallyExporter.kt          # JSON/Markdown export for artifacts and test cases (with attachments + inline images)
├── settings/
│   ├── RallySettings.kt          # Application-level persistent settings (@State)
│   └── RallySettingsConfigurable.kt  # Settings UI (Settings > Tools > Rally)
├── ui/
│   ├── RallyToolWindowFactory.kt  # ToolWindowFactory + DumbAware
│   ├── RallyToolWindowPanel.kt    # Main UI: toolbar, filters, project switcher, ticket list, detail panel, sprint summary
│   ├── RallyDetailPanel.kt        # Detail panel: description (HTML) + tabbed pane (Test Cases/Steps, Tasks, Attachments)
│   └── RallyIcons.kt             # Icon loader for /icons/rally.svg
└── vcs/
    ├── RallyWorkSession.kt        # Project-level service tracking active work session (ticket ID, branch, type)
    └── RallyCheckinHandler.kt     # VCS checkin handler that appends "Refs: <ticketID>" to commit messages
```

## Key Design Decisions

- **Application-level settings** — Rally credentials are per-user, not per-project. Settings: Server URL, API Key, Workspace Ref, Username, Export Directory
- **Project switcher in tool window** — Projects are fetched from Rally API (`queryProjects()`) and shown in a dropdown in the toolbar. Selection is persisted in `selectedProject` setting and restored on next launch. "All Projects" queries across the entire workspace
- **Collapsible detail panel** — Main panel uses a horizontal split: ticket list (left) + detail panel (right). Detail panel starts collapsed (dividerSize=0), auto-expands when a ticket is selected, auto-collapses when selection clears. Thin dark divider (3px) instead of default Swing divider
- **Detail panel layout** — Vertical split: description (top, 40%) + JBTabbedPane (bottom, 60%) with three tabs: Test Cases, Tasks, Attachments. When a Test Case is selected, tabs switch to show Test Steps instead. Tab titles show counts (e.g., "Test Cases (5)")
- **Lazy description loading** — List queries use `LIST_FIELDS` (no Description) for smaller payloads. Description is fetched on demand via `fetchDescription()` when the detail panel opens
- **Parallel detail loading** — Description, test cases, tasks, and attachments all load concurrently via `CompletableFuture`. Generation-based cancellation (AtomicLong) prevents stale selections from continuing to update the UI
- **Caching** — LRU query cache (access-ordered `LinkedHashMap`, max 500 entries) with 2-minute TTL. Downloaded images use a bounded in-memory cache (10 MB cap, 1 MB per-image cap). Bulk export mode extends TTL to 15 minutes
- **Threading**: `executeOnPooledThread` for API calls, `invokeLater` for UI updates, `CompletableFuture.supplyAsync` for parallel operations. Dedicated `apiExecutor` thread pool in RallyApiClient (4 daemon threads)
- **Disposal safety** — `RallyToolWindowPanel` implements `Disposable` with a `disposed` flag. `getClient()` throws after disposal; all call sites are guarded with try/catch to prevent late background tasks from crashing
- **HTTP/2** — Enabled for connection multiplexing on parallel requests. Respects IDE proxy settings
- **No external Rally SDK** — uses Java's built-in `HttpClient` with `zsessionid` header for API key auth
- **State field logic**: User Stories and Defects use `ScheduleState`, Tasks use `State`
- **Client-side state filtering** — because ScheduleState vs State differs by artifact type, filter queries for state are applied client-side after fetching
- **Server-side owner filtering** — `(Owner.UserName = "...")` is applied as a Rally query
- **Workspace/Project refs** — Rally WSAPI requires full API URLs for workspace/project params. The `normalizeRef()` method in RallyApiClient handles conversion from bare IDs, ref paths, or full URLs
- **Iteration deduplication** — Rally returns the same iteration per project; deduplicated with `distinctBy { it.name }`
- **Sandbox persistence** — Gradle sandbox moved to `.sandbox/` (outside `build/`) so settings survive `./gradlew clean`
- **Generic plugin design** — No workflow-specific or company-specific custom fields hardcoded. Only standard Rally fields (Method, ScheduleState, etc.) are used

## Performance Optimizations

| Layer | Technique | Impact |
|-------|-----------|--------|
| **Caching** | LRU query cache (500 entries, 2-min TTL), bounded image cache (10 MB cap) | Eliminates redundant API calls without unbounded heap growth |
| **List queries** | `LIST_FIELDS` excludes Description field | Smaller payloads for 200+ items |
| **Lazy description** | `fetchDescription()` on demand when detail panel opens | Faster initial list load |
| **Parallel list** | User stories + defects fetched concurrently in `queryAllArtifacts` | ~2x faster list load |
| **Parallel detail** | Description + test cases + tasks + attachments via CompletableFuture | ~3-4x faster detail load |
| **Parallel sprint** | Sprint summary loads alongside artifact list | Removes serial bottleneck |
| **HTTP/2** | `HttpClient.Version.HTTP_2` for connection multiplexing | Better throughput for parallel requests |
| **Generation counter** | AtomicLong in RallyDetailPanel cancels stale async work on selection change | Prevents wasted work and UI flicker |
| **Inline image cap** | Max 10 inline images per description in detail panel | Prevents thread pool saturation |
| **JBColor pre-allocation** | Static color constants in companion objects for cell renderers | Avoids GC pressure from repeated allocations |
| **Precompiled regex** | Static Regex patterns in RallyExporter for HTML stripping | Avoids re-creation per call during bulk export |
| **Retry with backoff** | Exponential backoff + Retry-After for 429/502/503/504 | Resilient to transient Rally API errors |

## Rally WSAPI Gotchas

- Query syntax requires binary nesting for AND/OR: `((a) AND ((b) AND (c)))` not `(a AND b AND c)`
- `/user` endpoint returns `{"User": {...}}` with API key auth, not `{"QueryResult": {...}}`
- Workspace and project must be passed as URL params (not query conditions) and must be full Rally API URLs
- `ScheduleState` values: Idea, Defined, In-Progress, Completed, Accepted
- `State` values (for Defects): Submitted, Open, Fixed, Closed
- Test cases use `WorkProduct` ref to link to parent user stories/defects
- Attachment content is fetched via a Content ref that returns base64-encoded data
- Inline images in descriptions use `/slm/attachment/<OID>/<filename>` URLs, downloaded via zsessionid auth
- Iterations are returned per-project, causing duplicates when querying at workspace level — must deduplicate by name

## Build & Run

```bash
./gradlew clean build          # Build plugin
./gradlew verifyPlugin         # Verify plugin structure
./gradlew runIde               # Launch sandbox IDE with plugin
```

Warnings during `runIde` about GradleJvmSupportMatrix, Maven, or memory leaks on UI switch are IntelliJ 2024.1 internal issues — not from this plugin.

## Current Filter Options (in Tool Window)

All Tickets, My Tickets, User Stories, Defects, Test Cases, Recent Activity

## State Filter Options

Any State, Idea, Defined, In-Progress, Completed, Accepted, Deployed, Active (excludes Accepted/Completed/Deployed/Idea)

## What's Implemented

- Tool window with ticket list, search (debounced client-side + server-side fallback), filters, and sprint summary
- **Detail panel** — selecting a ticket shows its HTML description (with inline images resolved to base64 data URIs, capped at 10 and guarded against stale selections) and three tabs:
  - **Test Cases** — linked test cases with Method (Automated/Manual) badges and LastVerdict (Pass/Fail)
  - **Tasks** — child tasks with State badge, Owner name, ToDo hours
  - **Attachments** — linked files with content-type icon and human-readable file size. Double-click to save to disk. Context menu: Save to Disk, Open in Browser
- **Test Case detail view** — selecting a Test Case in the main list shows its description + Test Steps tab (StepIndex, Input, Expected Result)
- **Collapsible detail panel** — starts hidden, auto-expands on ticket selection, auto-collapses on deselection, thin dark divider for manual resize
- **Create User Story dialog** — full dialog with Name, Project, Sprint, Assign to me, Description, and ZIP attachment upload
- Project switcher dropdown — fetches projects from Rally, persists selection across sessions
- Iteration (sprint) switcher dropdown — shows date ranges in dropdown (e.g., "Sprint 42 (2026-02-10 → 2026-02-24)"), deduplicates iterations, defaults to "All Sprints"
- Settings page (Server URL, API Key, Workspace Ref, Username, Page Size, Export Directory) with Test Connection (shows DisplayName + UserName) and folder chooser
- State change actions (In-Progress, Completed, Defined) via toolbar and context menu — supports multi-select with optimistic UI updates
- Open in Browser, Copy FormattedID via context menu and detail panel header
- **Export** — toolbar Export button exports selected artifact(s) + their linked test cases to JSON and Markdown. Also available via right-click context menu
- Inline image downloading during export (replaces Rally image URLs with local paths)
- Attachment downloading during export (via base64 content API, deduplicated across JSON+Markdown)
- **Start Working** — creates/checks out `feature/<ticketID>` branch, moves ticket to In-Progress, assigns owner, activates work session (commit message prefixing via `RallyCheckinHandler`)
- **Finish Working** — moves ticket to Completed, deactivates work session, opens IntelliJ's Create Pull Request dialog
- **Performance** — caching, parallel queries, lazy description loading, HTTP/2, generation-based cancellation, disposed-client guards (see Performance Optimizations table)

## API Methods (RallyApiClient)

| Method | Purpose |
|--------|---------|
| `queryUserStories()` | Query user stories with filters |
| `queryDefects()` | Query defects with filters |
| `queryTasks()` | Query tasks with filters |
| `queryAllArtifacts()` | Combined user stories + defects (parallel, cached) |
| `queryAllTestCases()` | Query all test cases in workspace/project |
| `queryTestCases(workProductRef)` | Test cases linked to a user story/defect (cached) |
| `queryTasksForWorkProduct(ref)` | Tasks linked to a user story/defect (cached) |
| `queryTestCaseByFormattedId(id)` | Single test case lookup |
| `queryTestSteps(formattedId)` | Test steps for a test case |
| `queryAttachments(formattedId)` | Attachments for any artifact (cached) |
| `queryUnautomatedTestCases()` | All TCs where Method != Automated |
| `searchArtifacts(text, scope)` | Server-side search by Name/FormattedID |
| `fetchDescription(artifactRef)` | On-demand description fetch (cached) |
| `getArtifactByFormattedId(id)` | Lookup by FormattedID (US/DE/TA) |
| `updateArtifactState(ref, type, state)` | Change ScheduleState/State |
| `updateArtifactOwner(ref, type, ownerRef)` | Change Owner |
| `getAttachmentContent(contentRef)` | Get base64 attachment content |
| `downloadAttachment(url)` | Download attachment bytes via HTTP (cached, 10 MB cap) |
| `queryProjects()` / `queryIterations()` | Project and sprint lists |
| `queryCurrentIteration()` | Find active sprint by today's date |
| `createUserStory()` | Create a new user story |
| `uploadAttachment(ref, path)` | Two-step attachment upload (content + link) |
| `getUserByUsername(username)` | Lookup user by email |
| `clearCache()` / `clearArtifactCache()` | Cache invalidation |
| `enterBulkMode()` / `exitBulkMode()` | Extended cache TTL for exports |

## Git Commit Rules

- **Do NOT include `Co-Authored-By: Claude`** or any AI attribution in commit messages
- Follow conventional commit format: `type(scope): description`

## Not Yet Implemented

- Create Test Case dialog
- Bulk Export (consolidated JSON/Markdown for all loaded artifacts)
