# Rally Plugin for IntelliJ IDEA

## Project Overview

IntelliJ IDEA plugin that provides a **Tool Window** for browsing and managing Rally (Broadcom Agile Central) work items directly from the IDE. Uses Rally WSAPI 2.0 REST API with API key authentication.

## Tech Stack

- **Language**: Kotlin 2.0.21 (JVM 17) — upgraded from 1.9.25 to unblock Gradle's configuration cache (enabled in `gradle.properties`) and clear a Gradle-10 deprecation. Kotlin 2.0 creates a `.kotlin/` build-cache dir (gitignored)
- **Build**: Gradle with Kotlin DSL, `org.jetbrains.intellij.platform` plugin 2.16.0 (IntelliJ Platform Gradle Plugin 2.x). `instrumentCode = false` (all-Kotlin module, zero `.java`/`.form` files, so the form/@NotNull instrumentation pass is pure overhead)
- **Target IDE**: IntelliJ IDEA Community 2024.1 (builds 241–263.*)
- **Dependencies**: Gson 2.10.1 (JSON), JUnit 4.13.2 (tests)
- **Plugin ID**: `com.github.halmurat.rally` (NOT `com.intellij.*` — that prefix is reserved by JetBrains)

## Architecture

```
src/main/kotlin/com/github/halmurat/rally/
├── api/
│   ├── RallyApiClient.kt         # HTTP client for Rally WSAPI 2.0 (caching + parallel queries; requireNoErrors, queryAllArtifactsParallel, parseCreateResult/checkOperationResult helpers)
│   ├── RallyApiModels.kt         # Data classes + ArtifactQueryResult (partial-failure wrapper) + requireNoErrors extension
│   ├── RallyArtifactExt.kt       # RallyArtifact extensions: effectiveState/effectiveStateOrEmpty/storyPoints + RallyType _type constants
│   └── RallyApiException.kt      # Exception hierarchy
├── export/
│   └── RallyExporter.kt          # JSON/Markdown export (attachments + inline images downloaded on a dedicated pool; artifact-object overloads)
├── settings/
│   ├── RallySettings.kt          # Application-level persistent settings (@State)
│   └── RallySettingsConfigurable.kt  # Settings UI (Settings > Tools > Rally)
├── ui/
│   ├── RallyToolWindowFactory.kt  # ToolWindowFactory + DumbAware
│   ├── RallyToolWindowPanel.kt    # Main UI: toolbar, filters, project switcher, ticket list, detail panel, sprint summary
│   ├── RallyDetailPanel.kt        # Detail panel: description (HTML) + tabbed pane (Test Cases/Steps, Tasks, Attachments)
│   ├── DetailCellRenderers.kt     # Extracted detail-tab renderers (TestCase/Task/Step/Attachment)
│   ├── ArtifactCellRenderer.kt    # Extracted ticket-list cell renderer
│   ├── AbstractCreateArtifactDialog.kt # Shared Create dialog base (form + combos + validation + resolved-ref properties)
│   ├── CreateUserStoryDialog.kt   # Create User Story dialog (extends the base)
│   ├── CreateDefectDialog.kt      # Create Defect dialog (adds Severity/Priority rows)
│   ├── RallyFilters.kt            # Scope / StateFilter enums (single source of truth for filter display strings)
│   ├── ThinDividerSplitPaneUI.kt  # Extracted thin-divider split-pane UI
│   ├── StatusBadge.kt             # Tinted-chip status badge component (update() is a pure setter; refresh() schedules repaint)
│   └── RallyColors.kt             # Shared color constants + StateColors chips + forState()/forMethod() lookup
├── util/
│   └── RallyHtmlUtils.kt          # Shared HTML/image utilities + author-color stripping
```

## Key Design Decisions

- **Application-level settings** — Rally credentials are per-user, not per-project. Settings: Server URL, API Key, Workspace Ref, Username, Export Directory
- **Project switcher in tool window** — Projects are fetched from Rally API (`queryProjects()`) and shown in a dropdown in the toolbar. Selection is persisted in `selectedProject` setting and restored on next launch. "All Projects" queries across the entire workspace
- **Collapsible detail panel** — Main panel uses a horizontal split: ticket list (left) + detail panel (right). Detail panel starts collapsed (dividerSize=0), auto-expands when a ticket is selected, auto-collapses when selection clears. Thin dark divider (3px) instead of default Swing divider
- **Detail panel layout** — Vertical split: description (top, 40%) + JBTabbedPane (bottom, 60%) with three tabs: Test Cases, Tasks, Attachments. When a Test Case is selected, tabs switch to show Test Steps instead. Tab titles show counts (e.g., "Test Cases (5)")
- **Lazy description loading** — List queries use `LIST_FIELDS` (no Description) for smaller payloads. Description is fetched on demand via `fetchDescription()` when the detail panel opens
- **Parallel detail loading** — Description runs synchronously on the pooled thread (fetch + image resolution + `wrapHtml` all off-EDT), while test cases, tasks, and attachments load concurrently via `CompletableFuture` on apiExecutor. Generation-based cancellation (AtomicLong) prevents stale selections from continuing to update the UI
- **Theme-pinned description colors** — `wrapHtml` (the single chokepoint for every non-empty `descriptionPane.text` assignment) runs descriptions through `RallyHtmlUtils.stripInlineColors`: inline `color`/`background-color`/`background` declarations, presentational `color`/`bgcolor` attributes, and whole `<style>`/`<link>` elements are removed — Rally colors are authored for its light web UI and render as white blocks / dark-on-dark text on dark themes. Body/link/error colors are then pinned from the current theme (`UIUtil.getLabelForeground()`, `JBUI.CurrentTheme.Link.Foreground.ENABLED`, `Label.errorForeground` via the `.rally-error` class). Stripping is required because Swing's HTMLEditorKit gives author inline styles precedence over stylesheet rules (no `!important`). No live re-render on LaF switch — reselecting the ticket re-renders; a `LafManagerListener` is a known follow-up. Exporter paths intentionally keep author colors
- **Caching** — LRU query cache (access-ordered `LinkedHashMap`, max 200 entries) with 2-minute TTL. Downloaded images use a bounded in-memory cache (10 MB cap, 1 MB per-image cap). Bulk export mode extends TTL to 15 minutes via a **reentrant `bulkModeDepth` AtomicInteger** (`enterBulkMode`/`exitBulkMode` increment/decrement; floor at 0) so overlapping exports/browse don't stomp each other's extended-TTL window. `getUserByUsername` is cached; `clearArtifactCache` no longer evicts `currentIteration` (date-derived, mutation-independent)
- **Threading**: `executeOnPooledThread` for API calls, `invokeLater` for UI updates, `CompletableFuture.supplyAsync` for parallel operations. Dedicated `apiExecutor` thread pool in RallyApiClient (4 daemon threads)
- **Disposal safety** — `RallyToolWindowPanel` implements `Disposable` with a `disposed` flag. `dispose()` and `getClient()` are synchronized on `clientLock` so no client can be created after disposal begins. All `getClient()` call sites are guarded with try/catch to prevent late background tasks from crashing
- **HTTP/2** — Enabled for connection multiplexing on parallel requests. Respects IDE proxy settings
- **No external Rally SDK** — uses Java's built-in `HttpClient` with `zsessionid` header for API key auth
- **State field logic**: User Stories use `ScheduleState`; Defects carry both `ScheduleState` and `State`; Tasks use `State`
- **Client-side state filtering** — because ScheduleState vs State differs by artifact type, the state filter is applied **client-side at display time** (`applyStateFilter` inside `applySearchFilter`) over the scope-filtered `allArtifacts`. A state-combo change therefore re-filters in memory with **no network round trip**; only scope/project/sprint changes call `loadTickets()`. `applyScopeFilter`/`applyStateFilter`/`applyClientFilter` and the `Scope`/`StateFilter` enums (`RallyFilters.kt`) are the single source of truth
- **Effective-state & story-points helpers** — `RallyArtifact.effectiveState` (TestCase → LastVerdict; else ScheduleState ?: State ?: "Unknown"), `effectiveStateOrEmpty` (filter form, "" fallback), and `storyPoints` (PlanEstimate for stories/defects) in `RallyArtifactExt.kt` replace ~7 drifting copies. `_type` discriminators are constants in `RallyType`
- **Server-side errors surfaced, not swallowed** — Rally WSAPI returns HTTP 200 with a populated `Errors` array for field/permission/scoping errors. `QueryResultData.requireNoErrors(ctx)` is called after every `gson.fromJson` so those become a thrown `RallyApiException` instead of a silent empty list; `Warnings` are logged. `queryAllArtifactsParallel`/`searchArtifacts` return `ArtifactQueryResult` carrying partial-failure reasons so the list shows a warning icon + "(incomplete)" rather than looking complete
- **Create/upload split** — the shared `executeCreate` helper (used by both Create dialogs) creates the artifact in Phase 1 (optimistic insert + success balloon immediately) and uploads the attachment in a separate Phase 2 `try/catch`; a post-create upload failure reports "created, but attachment upload failed" instead of "Create failed" (which previously hid the created artifact and invited duplicates)
- **Server-side owner filtering** — `(Owner.UserName = "...")` is applied as a Rally query
- **Workspace/Project refs** — Rally WSAPI requires full API URLs for workspace/project params. The `normalizeRef()` method in RallyApiClient handles conversion from bare IDs, ref paths, or full URLs
- **Iteration filtering** — Iterations are scoped to the selected project via server-side project filtering in `queryIterations()`
- **Sandbox persistence** — Gradle sandbox moved to `.sandbox/` (outside `build/`) so settings survive `./gradlew clean`
- **Shared color constants** — `RallyColors` object eliminates color duplication across renderers
- **invokeLaterIfAlive helper** — private inline function replaces 19 disposed-guard boilerplate instances
- **Balloon notifications** — non-modal success feedback via JBPopupFactory
- **Generic plugin design** — No workflow-specific or company-specific custom fields hardcoded. Only standard Rally fields (Method, ScheduleState, etc.) are used

## Performance Optimizations

| Layer | Technique | Impact |
|-------|-----------|--------|
| **Caching** | LRU query cache (200 entries, 2-min TTL), bounded image cache (10 MB cap) | Eliminates redundant API calls without unbounded heap growth |
| **List queries** | `LIST_FIELDS` excludes Description field | Smaller payloads for 200+ items |
| **Lazy description** | `fetchDescription()` on demand when detail panel opens | Faster initial list load |
| **Parallel list** | `queryAllArtifactsParallel` runs user stories + defects concurrently (defects on `apiExecutor`, stories inline) — called from the **outer pooled thread** (not `apiExecutor`) so it only ever blocks on one sub-task slot, staying deadlock-free. `queryAllArtifacts` keeps its sequential path for `apiExecutor` callers | ~½ the cold-load latency (`max` instead of `stories+defects` RTT) |
| **Client-side state filter** | State-combo changes re-filter `allArtifacts` in memory (`applyStateFilter` at display time) instead of re-querying Rally | Instant state switches, zero network |
| **Parallel detail** | Description + test cases + tasks + attachments via CompletableFuture | ~3-4x faster detail load |
| **Parallel sprint** | Sprint summary loads alongside artifact list | Removes serial bottleneck |
| **HTTP/2** | `HttpClient.Version.HTTP_2` for connection multiplexing | Better throughput for parallel requests |
| **Generation counter** | AtomicLong in RallyDetailPanel cancels stale async work on selection change | Prevents wasted work and UI flicker |
| **Inline image cap** | Max 10 inline images per description in detail panel | Prevents thread pool saturation |
| **JBColor pre-allocation** | Static color constants in `RallyColors` object shared across renderers | Avoids GC pressure from repeated allocations |
| **Precompiled regex** | Static Regex patterns in RallyExporter for HTML stripping | Avoids re-creation per call during bulk export |
| **Retry with backoff** | Exponential backoff + Retry-After (delta-seconds; HTTP-date falls back to backoff) for 429/502/503/504 — creates/uploads retry only on 429 (rejected before processing, replay-safe) and never on ambiguous 502/504/connection errors | Resilient to transient errors without duplicate-artifact risk |
| **Pre-allocated TypeToken** | Static TypeToken fields in companion object | Avoids repeated reflection per API call |
| **Bulk mode in export** | `enterBulkMode()`/`exitBulkMode()` wraps export operations | 15-min cache TTL prevents re-fetching during long exports |
| **Reduced thread pool** | API executor reduced from 8 to 4 threads | Prevents thread saturation while maintaining parallelism |
| **Reduced cache size** | LRU cache reduced from 500 to 200 entries | Lower memory footprint with same hit rate |
| **Jittered backoff** | ±30% jitter on retry delays | Prevents thundering herd on transient failures |
| **gzip transport** | `Accept-Encoding: gzip` + transparent decode for all JSON responses (raw-body fallback on mislabeled encoding) | 5-10x smaller payloads on cold loads |
| **Parallel iterations** | Sprint list loads concurrently with artifacts when no saved sprint needs validation | Removes a serial RTT from cold start/project switch |
| **In-place row patching** | `patchArtifactsInModel` replaces full list rebuilds after optimistic updates (server-search rows get the same optimistic transform applied in place) | Selection preserved, single-cell repaint |
| **Fixed cell height** | Prototype-measured `fixedCellHeight` on the ticket list AND the detail-tab lists (test cases/tasks/steps/attachments), prototypes populated so the StatusBadge chip isn't clipped | O(1) instead of O(n) layout per model event |
| **StatusBadge pure setter** | `update()` no longer calls `revalidate()`/`repaint()` (rubber-stamp renderers don't need it); real-container badges call `refresh()` | Removes per-cell repaint-queue churn on 200-row lists |
| **Off-EDT description pipeline** | Description fetch + image resolution + `wrapHtml` run on the unbounded pooled thread, not apiExecutor/EDT | apiExecutor never parks on image joins; EDT stays responsive on multi-MB descriptions |
| **Export download dedup** | Inline images + attachments deduplicated across JSON+Markdown passes (thread-safe `ConcurrentHashMap`); raw-bytes attachment endpoint with base64 fallback; exporter bypasses the UI image cache | Halves export downloads, ~1x peak heap, no UI-cache eviction |
| **Parallel export downloads** | Per-artifact inline images and attachments download on a **dedicated** `Executors` pool (separate from `apiExecutor` to avoid self-deadlock), `allOf().join()` + `shutdownNow()` in `finally`; output order preserved via index/key maps | Image/attachment-heavy exports no longer serialize each download |
| **Export skips redundant lookup** | `exportArtifact{Json,Markdown}(artifact, dir)` overloads reuse the in-memory artifact + `fetchDescription` (ref GET) instead of re-running a FormattedID search per artifact | One fewer search query per exported artifact |
| **Reentrant bulk mode** | `enterBulkMode`/`exitBulkMode` use an `AtomicInteger` depth counter (floor 0) | Overlapping exports/browse keep a consistent extended-TTL window |
| **Streamed JSON export** | `gson.toJson(output, bufferedWriter)` instead of full-string materialization | Halves peak memory of export write phase |

## Rally WSAPI Gotchas

- Query syntax requires binary nesting for AND/OR: `((a) AND ((b) AND (c)))` not `(a AND b AND c)`
- `/user` endpoint returns `{"User": {...}}` with API key auth, not `{"QueryResult": {...}}`
- Workspace and project must be passed as URL params (not query conditions) and must be full Rally API URLs
- `ScheduleState` values: Idea, Defined, In-Progress, Completed, Accepted
- `State` values (for Defects): Submitted, Open, Fixed, Closed
- Test cases use `WorkProduct` ref to link to parent user stories/defects
- Attachment content is fetched via a Content ref that returns base64-encoded data
- Inline images in descriptions use `/slm/attachment/<OID>/<filename>` URLs, downloaded via zsessionid auth
- Iterations are scoped per-project; the plugin pins `queryIterations()` to the selected project (projectScopeUp/Down=false) and dedupes by name to avoid duplicate same-named sprints
- Query values escape `"` as `\"` and `\` as `\\` (Broadcom-documented); other documented escapes (`\q` for `'`, `\l`/`\g` for `<`/`>`) are deliberately not applied — those characters round-trip fine unescaped in practice
- Rally's attachment upload limit is 50 MB; the create dialogs validate size in `doValidate()` and re-check on the pooled thread BEFORE the artifact is created (a post-create upload failure would orphan the new artifact)
- `requireSameHost` pins host, scheme, AND effective port — the configured Server URL must match the host/port Rally uses in its `_ref` URLs (relevant behind reverse proxies with port rewriting)

## Data Model (Tier 1 Fields)

Core artifact models (`RallyUserStory`, `RallyDefect`, `RallyTaskItem`) include: FormattedID, Name, ScheduleState/State, Owner, Project, Iteration, PlanEstimate, Description, CreationDate, LastUpdateDate, plus extended fields: Blocked, BlockedReason, Release, Ready.

## Testing

176 unit tests across 14 classes: Rally API JSON parsing, query-value escaping, Retry-After parsing, host/scheme/port validation, field-update bodies, exporter formatting, file/HTML utils (incl. inline-color/embedded-stylesheet stripping), sprint summary, gzip body decoding, status color mapping, StatusBadge behavior, plus the audit additions: `RallyArtifactExt` (effectiveState/storyPoints), `Scope`/`StateFilter` enums, `requireNoErrors` + `ArtifactQueryResult` (the 200-with-Errors / partial-failure paths), and the `RallyApiClient` instance helpers (`normalizeRef`, reentrant bulk-mode depth, `checkOperationResult`/`parseCreateResult`). Run via `./gradlew test`.

## Build & Run

```bash
./gradlew clean build          # Build plugin
./gradlew verifyPlugin         # Verify plugin structure
./gradlew runIde               # Launch sandbox IDE with plugin
```

Warnings during `runIde` about GradleJvmSupportMatrix, Maven, or memory leaks on UI switch are IntelliJ 2024.1 internal issues — not from this plugin.

The build uses Gradle's **configuration cache** (enabled in `gradle.properties`, unblocked by the Kotlin 2.0 upgrade) — `compileKotlin`/`test`/`buildPlugin` all store/reuse a config-cache entry. If a future change reintroduces a config-cache incompatibility, the line in `gradle.properties` can be removed without losing the Kotlin upgrade. `verifyPlugin` downloads several full IDE distributions and needs multiple GB of free disk.

`verifyPlugin` uses a pinned IDE list (`pluginVerification.ides`, one release per major across 241–261) instead of the default dynamic `recommended()` feed: that feed serves 2025.3.x distributions whose layout (no `modules/module-descriptors.jar`) the newest Plugin Verifier (1.405) cannot read, which kills the whole task with `InvalidIdeException`. Re-add 2025.3 or return to `recommended()` once the verifier supports the new layout. Verifier-reported deprecated/scheduled-for-removal API usages (7 on newer IDEs) are the deliberate 241-floor keeps.

## Current Filter Options (in Tool Window)

All Tickets, My Tickets, User Stories, Defects, Test Cases, Recent Activity

## State Filter Options

Any State, Idea, Defined, In-Progress, Completed, Accepted, Active (excludes Accepted/Completed/Idea)

## What's Implemented

- Tool window with ticket list, search (debounced client-side + server-side fallback), filters, and sprint summary
- **Detail panel** — selecting a ticket shows its HTML description (inline images resolved to base64 data URIs, capped at 10 and guarded against stale selections; Rally's light-UI author colors stripped and body/link/error colors pinned to the IDE theme) and three tabs:
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
- **Start Working** — dialog lets user choose branch prefix (feature, bugfix, hotfix, refactor, chore, test) with auto-selection based on ticket type. Creates/checks out branch, moves ticket to In-Progress, assigns owner. Verifies branch checkout before proceeding with Rally state changes
- **Security** — Rally query value escaping, attachment filename sanitization with path traversal prevention, canonical path verification
- **Threading safety** — PasswordSafe access cached off-EDT, project/iteration selection read from cached data instead of Swing state, generation-based stale result prevention
- **Performance** — caching, parallel queries, lazy description loading, HTTP/2, generation-based cancellation, disposed-client guards (see Performance Optimizations table)
- **Create Defect dialog** — full dialog with Name, Project, Sprint, Severity, Priority, Assign to me, Description, and ZIP attachment upload
- **Create Task** — create task from detail panel Tasks tab, linked to the selected work product
- **Edit Points** — edit PlanEstimate via context menu on user stories/defects
- **Finish Working** — toolbar button moves ticket to Completed state
- **Metadata strip** — detail panel header shows Owner, Points, Sprint, Severity, Priority
- **Balloon notifications** — non-modal success feedback via JBPopupFactory for create, export, and other actions
- **Keyboard accessibility** — focusable buttons, Enter key support on ticket list
- **Search placeholder text** — search field shows hint text when empty
- **Page Size configurable** — settings UI allows configuring query page size
- **Blocked/BlockedReason indicator** — visual indicator for blocked artifacts in detail panel
- **PlannedVelocity and days remaining** — sprint summary shows planned velocity and days remaining in current sprint
- **Status color chips** — ticket state renders as a tinted chip (`StatusBadge`, opaque pastel fill, screen-reader accessible) in the ticket list, detail panel header, Tasks tab, and Test Cases tab (Method + Verdict). State→color mapping centralized in `RallyColors.forState()`/`forMethod()` covering ScheduleState, Defect State (Submitted/Open/Fixed/Closed), and verdicts

## API Methods (RallyApiClient)

| Method | Purpose |
|--------|---------|
| `queryUserStories()` | Query user stories with filters |
| `queryDefects()` | Query defects with filters |
| `queryAllArtifacts()` | Combined user stories + defects (sequential, cached) |
| `queryAllTestCases()` | Query all test cases in workspace/project |
| `queryTestCases(workProductRef)` | Test cases linked to a user story/defect (cached) |
| `queryTasksForWorkProduct(ref)` | Tasks linked to a user story/defect (cached) |
| `queryTestCaseByFormattedId(id)` | Single test case lookup |
| `queryTestSteps(formattedId)` | Test steps for a test case |
| `queryAttachments(formattedId)` | Attachments for any artifact (cached) |
| `searchArtifacts(text, scope)` | Server-side search by Name/FormattedID |
| `fetchDescription(artifactRef)` | On-demand description fetch (cached) |
| `getArtifactByFormattedId(id)` | Lookup by FormattedID (US/DE/TA) |
| `updateArtifactState(ref, type, state)` | Change ScheduleState/State |
| `updateArtifactOwner(ref, type, ownerRef)` | Change Owner |
| `getAttachmentContent(contentRef)` | Get base64 attachment content |
| `downloadAttachment(url, cache)` | Download attachment bytes via HTTP (bounded image cache unless cache=false, 10 MB cap) |
| `queryProjects()` / `queryIterations()` | Project and sprint lists |
| `queryCurrentIteration()` | Find active sprint by today's date |
| `createUserStory()` | Create a new user story |
| `createDefect()` | Create a new defect |
| `createTask()` | Create a new task linked to a work product |
| `updateArtifactField(ref, type, field, value)` | Update any field on an artifact |
| `uploadAttachment(ref, path)` | Two-step attachment upload (content + link) |
| `getUserByUsername(username)` | Lookup user by email |
| `clearCache()` / `clearArtifactCache()` | Cache invalidation |
| `enterBulkMode()` / `exitBulkMode()` | Extended cache TTL for exports |
| `getCurrentUser()` | Get authenticated user info |
| `buildWebUrl(artifact)` | Construct Rally web UI URL |
| `queryIterationArtifacts(name)` | Artifacts in a named iteration |

## Git Commit Rules

- **Do NOT include `Co-Authored-By: Claude`** or any AI attribution in commit messages
- Follow conventional commit format: `type(scope): description`

## Not Yet Implemented

- Create Test Case dialog
- Bulk Export UI (backend implemented in RallyExporter but no dedicated bulk-select UI)
