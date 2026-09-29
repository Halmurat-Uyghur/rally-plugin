# Rally Plugin for IntelliJ IDEA

## Project Overview

IntelliJ IDEA plugin that provides a **Tool Window** for browsing and managing Rally (Broadcom Agile Central) work items directly from the IDE. Uses Rally WSAPI 2.0 REST API with API key authentication.

## Tech Stack

- **Language**: Kotlin 2.0.21 (JVM 17) — upgraded from 1.9.25 to unblock Gradle's configuration cache (enabled in `gradle.properties`) and clear a Gradle-10 deprecation. Kotlin 2.0 creates a `.kotlin/` build-cache dir (gitignored)
- **Build**: Gradle with Kotlin DSL, `org.jetbrains.intellij.platform` plugin 2.16.0 (IntelliJ Platform Gradle Plugin 2.x). `instrumentCode = false` (no `.form` files and no `@NotNull`-annotated Java — the only `.java` file is `settings/CredentialAttributesCompat.java` — so the form/@NotNull instrumentation pass is pure overhead)
- **Target IDE**: IntelliJ IDEA Community 2024.1 (builds 241–262.*)
- **Dependencies**: Gson 2.10.1 (JSON), JUnit 4.13.2 (tests)
- **License**: MIT (`LICENSE`, © Halmurat Tahir) — same as the author's StepScout plugin. The Marketplace listing's description (`plugin.xml`) and README carry a "not affiliated with Broadcom" trademark disclaimer; keep it when editing either. Marketplace icon: `META-INF/pluginIcon.svg`
- **Plugin ID**: `com.github.halmuratuyghur.rally` (NOT `com.intellij.*` — that prefix is reserved by JetBrains). FROZEN, like the settings persistence keys: the Kotlin package is `com.github.halmurat.rally`, but the plugin `<id>` and the configurable id `com.github.halmuratuyghur.rally.settings` keep the original names so installs update in place. Pinned by `PluginDescriptorIdentityTest`

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
│   ├── RallySettingsConfigurable.kt  # Settings UI (Settings > Tools > Rally) + Test Connection report (WorkspaceCheck/UsernameCheck)
│   ├── RallySettingsListener.kt  # Application message-bus topic published when Settings are applied
│   ├── RallyApiKeyUnavailableException.kt # Thrown by getClient() while the PasswordSafe read is pending or after it failed
│   └── ApiKeyStatusText.kt       # Pure model for the API-key status line under the API Key field
├── ui/
│   ├── RallyToolWindowFactory.kt  # ToolWindowFactory + DumbAware
│   ├── RallyToolWindowPanel.kt    # Main UI: toolbar, filters, project switcher, ticket list, detail panel, sprint summary
│   ├── RallyDetailPanel.kt        # Detail panel: description (HTML) + tabbed pane (Test Cases/Steps, Tasks, Attachments)
│   ├── DetailCellRenderers.kt     # Extracted detail-tab renderers (TestCase/Task/Step/Attachment)
│   ├── ArtifactCellRenderer.kt    # Extracted ticket-list cell renderer
│   ├── AbstractCreateArtifactDialog.kt # Shared Create dialog base (form + combos + validation + resolved-ref properties)
│   ├── CreateUserStoryDialog.kt   # Create User Story dialog (extends the base)
│   ├── CreateDefectDialog.kt      # Create Defect dialog (adds Severity/Priority rows)
│   ├── RallyFilters.kt            # Scope / StateFilter enums (single source of truth for filter display strings) + ticketLoadPlan/buildTicketQuery
│   ├── RallyLoadStatus.kt         # Ticket-list status text, stateChangeAction, loadErrorLabel
│   ├── RallySprintSummary.kt      # Pure sprint-footer helpers (state counts, points, days remaining)
│   ├── RallyExportSummary.kt      # buildExportSummary: export outcome → balloon/status text + warn flag
│   ├── RallyWorkActions.kt        # State-change / Start Working decisions (confirmation, button state, owner step, outcome text, withState/withOwner)
│   ├── ThinDividerSplitPaneUI.kt  # Extracted thin-divider split-pane UI
│   ├── StatusBadge.kt             # Tinted-chip status badge component (update() schedules nothing, only invalidate()s on a text change; refresh() schedules revalidate+repaint)
│   ├── SafeHtmlEditorKit.kt       # Fail-closed HTML kit for the description pane: data:image-only images (declared-size limits + per-description pixel budget), inert form/object/frame views, no stylesheet/background/list-image loads
│   ├── RallyComboRenderers.kt     # Dropdown renderers for Rally names (html.disable, escaped tooltips, plain accessible text) + rallyNameCombo() for the Create dialogs
│   └── RallyColors.kt             # Shared color constants + StateColors chips + forState()/forMethod() lookup
├── util/
│   ├── RallyHtmlUtils.kt          # Description sanitizer: SwingTokenizer (mirrors javax.swing Parser) behind stripInlineColors/neutralizeActiveContent + inline-image URL helpers
│   ├── DescriptionHtml.kt         # Description wrap pipeline (strip colors → neutralize → themed shell), plain-text fallback, escapeHtml/literalHtmlTooltip/literalHtmlMessage
│   ├── RallyFileUtils.kt          # Filename hardening + traversal-safe path resolution for every file written from Rally data
│   ├── RallyGitOps.kt             # Git4Idea wrapper for Start Working (optional dependency; gate on isAvailable())
│   └── RepositoryChoice.kt        # pickRepository: which repo Start Working branches (git4idea-free, testable)
```

## Key Design Decisions

- **Application-level settings** — Rally credentials are per-user, not per-project. Settings: Server URL, API Key, Workspace Ref, Username, Export Directory
- **Project switcher in tool window** — Projects are fetched from Rally API (`queryProjects()`) and shown in a dropdown in the toolbar. Selection is persisted in `selectedProject` setting and restored on next launch. "All Projects" queries across the entire workspace
- **Collapsible detail panel** — Main panel uses a horizontal split: ticket list (left) + detail panel (right). Detail panel starts collapsed (dividerSize=0), auto-expands when a ticket is selected, auto-collapses when selection clears. Thin dark divider (3px) instead of default Swing divider
- **Detail panel layout** — Vertical split: description (top, 40%) + JBTabbedPane (bottom, 60%) with three tabs: Test Cases, Tasks, Attachments. When a Test Case is selected, tabs switch to show Test Steps instead. Tab titles show counts (e.g., "Test Cases (5)")
- **Lazy description loading** — List queries use `LIST_FIELDS` (no Description) for smaller payloads. Description is fetched on demand via `fetchDescription()` when the detail panel opens
- **Parallel detail loading** — Description runs synchronously on the pooled thread (fetch + image resolution + `wrapHtml` all off-EDT), while test cases, tasks, and attachments load concurrently via `CompletableFuture` on apiExecutor. Generation-based cancellation (AtomicLong) prevents stale selections from continuing to update the UI
- **Theme-pinned description colors** — `wrapHtml` (→ `DescriptionHtml.wrap`, which every description passes through before `setDescriptionHtml`) runs descriptions through `RallyHtmlUtils.stripInlineColors`: inline `color`/`background-color`/`background` declarations, presentational `color`/`bgcolor`/`text` attributes (Swing maps `text=` to CSS color), and whole `<style>`/`<link>` elements are removed — Rally colors are authored for its light web UI and render as white blocks / dark-on-dark text on dark themes. Body/link/error colors are then pinned from the current theme (`UIUtil.getLabelForeground()`, `JBUI.CurrentTheme.Link.Foreground.ENABLED`, `Label.errorForeground` via the `.rally-error` class). Stripping is required because Swing's HTMLEditorKit gives author inline styles precedence over stylesheet rules (no `!important`). No live re-render on LaF switch — reselecting the ticket re-renders; a `LafManagerListener` is a known follow-up. Exporter paths intentionally keep author colors
- **Caching** — LRU query cache (access-ordered `LinkedHashMap`, max 200 entries) with 2-minute TTL. Downloaded images use a bounded in-memory cache (10 MB cap, 1 MB per-image cap). Bulk export mode extends TTL to 15 minutes via a **reentrant `bulkModeDepth` AtomicInteger** (`enterBulkMode`/`exitBulkMode` increment/decrement; floor at 0) so overlapping exports/browse don't stomp each other's extended-TTL window. `getUserByUsername` is cached; `clearArtifactCache` no longer evicts `currentIteration` (date-derived, mutation-independent)
- **Threading**: `executeOnPooledThread` for API calls, `invokeLater` for UI updates, `CompletableFuture.supplyAsync` for parallel operations. Dedicated `apiExecutor` thread pool in RallyApiClient (4 daemon threads)
- **Disposal safety** — `RallyToolWindowPanel` implements `Disposable` with a `disposed` flag. `dispose()` and `getClient()` are synchronized on `clientLock` so no client can be created after disposal begins. All `getClient()` call sites are guarded with try/catch to prevent late background tasks from crashing
- **HTTP/2** — Enabled for connection multiplexing on parallel requests. Respects IDE proxy settings (static, PAC, exceptions) by following the JVM-default `ProxySelector`, which the IDE installs as its own; proxy challenges are answered by the IDE's default `Authenticator` (server challenges never are). With an Authenticator attached the JDK throws IOException for a Basic challenge it can't answer (or a 401 without a challenge header) instead of returning the response — `authFailureOf` maps those to non-retried `RallyAuthenticationException`/proxy failures (a declined IDE proxy prompt reads as a proxy failure, not a bad API key). Non-Basic challenges (Negotiate/NTLM/Bearer) still come back as plain 401/407 responses handled by `handleResponse`
- **No external Rally SDK** — uses Java's built-in `HttpClient` with `zsessionid` header for API key auth
- **State field logic**: User Stories use `ScheduleState`; Defects carry both `ScheduleState` and `State`; Tasks use `State`
- **Client-side state filtering** — because ScheduleState vs State differs by artifact type, the state filter is applied **client-side at display time** (`applyStateFilter` inside `applySearchFilter`) over the scope-filtered `allArtifacts`. A state-combo change therefore re-filters in memory with **no network round trip** — except after a failed or never-run load, when it also retries the load (`stateChangeAction`, so an error / "Not configured" isn't replaced by a bogus "0 loaded"); scope/project/sprint changes always call `loadTickets()`. `applyScopeFilter`/`applyStateFilter`/`applyClientFilter` and the `Scope`/`StateFilter` enums (`RallyFilters.kt`) are the single source of truth
- **Effective-state & story-points helpers** — `RallyArtifact.effectiveState` (TestCase → LastVerdict; else ScheduleState ?: State ?: "Unknown"), `effectiveStateOrEmpty` (filter form, "" fallback), and `storyPoints` (PlanEstimate for stories/defects) in `RallyArtifactExt.kt` replace ~7 drifting copies. `_type` discriminators are constants in `RallyType`
- **Server-side errors surfaced, not swallowed** — Rally WSAPI returns HTTP 200 with a populated `Errors` array for field/permission/scoping errors. `QueryResultData.requireNoErrors(ctx)` is called after every `gson.fromJson` so those become a thrown `RallyApiException` instead of a silent empty list; `Warnings` are logged. `queryAllArtifactsParallel`/`searchArtifacts` return `ArtifactQueryResult` carrying partial-failure reasons so the list shows a warning icon + "(incomplete)" rather than looking complete
- **Create/upload split** — the shared `executeCreate` helper (used by both Create dialogs) creates the artifact in Phase 1 (optimistic insert + success balloon immediately) and uploads the attachment in a separate Phase 2 `try/catch`; a post-create upload failure reports "created, but attachment upload failed" instead of "Create failed" (which previously hid the created artifact and invited duplicates)
- **Server-side owner filtering** — `(Owner.UserName = "...")` is applied as a Rally query. "My Tickets" with a blank Username shows a "set your Username" empty state instead of silently listing everyone's tickets (`myTicketsWithoutUsername`); the server-search fallback is skipped too. That state skips ONLY the ticket query (`ticketLoadPlan`): the load still rebuilds the client and reloads the project/sprint lists, so a Settings change, Refresh or project switch there can't leave Create offering another workspace's or project's refs (audit F3)
- **Connection change drops the lists** — when `getClient()` replaces an existing client for new settings, `dropConnectionLists()` clears `cachedProjects`/`cachedIterations` and the sprint footer, resets both dropdowns to their disabled placeholders, and bumps `listsGeneration` (and the sprint-summary generation: every footer commit — `commitSprint` — checks it, so a slow current-sprint lookup from an older load or connection can't overwrite or erase the new footer). A Create dialog records that generation (plus its project fallback, `createProjectRef`, which re-reads the toolbar project only when the dialog opened with no lists and the generation is unchanged) when it opens; `executeCreate` refuses to POST refs from a dialog that outlived a connection change (`createBlockedByConnectionChange`). Create offers no sprints while a project switch or connection change reloads the sprint list (it still holds the previous project's); a Refresh re-querying the same project's list keeps offering it (`iterationsCurrent`, which only `invalidateIterations()` clears). Building the first client is not a change
- **Description rendering is fail-closed** — two layers. (1) `SafeHtmlEditorKit` on the description pane is the network boundary: images load only from base64 `data:image/*` (PNG/JPEG/GIF) through an in-memory `DataUrlHandler` (plain Swing has no `data:` handler, so inline images never rendered before it), and only after the size AWT's own decoder will allocate passes the limits (≤ 16,384 px/side, ≤ 16,777,216 px/image, ≤ 40M px per description, payload ≤ 20M chars) — a tiny PNG declaring 12000×12000 would otherwise allocate ~500 MB. The size is measured the way AWT allocates, not just as ImageIO reports it: PNGs are chunk-walked and admitted only with exactly one IHDR before the first IDAT with data, matching ImageIO, and with no chunk running past the end (AWT's `PNGImageDecoder` uses the LAST IHDR and pre-allocates declared chunk lengths); GIFs are charged exactly their logical screen, read from header bytes 6-9 without asking ImageIO (JDK 17's `GIFImageReader` — IntelliJ 2024.1 — copies extension blocks quadratically, JDK-8270915: seconds on the EDT for megabytes of comment/application extension; a zero side is refused); JPEG matches because both decoders use libjpeg's first SOF. `DataImageAwtDifferentialTest` checks admitted sizes against AWT's real `setDimensions` over randomized chunk layouts; `<input>`/`<select>`/`<textarea>`/`<isindex>`/`<object>`/`<applet>`/`<frame>`/`<frameset>` render as inert views; the style sheet ignores `<link>`/`@import` and hides background and list-bullet images from the painters (those load synchronously on the EDT). (2) `RallyHtmlUtils.neutralizeActiveContent` is defense in depth + display hygiene: a private `SwingTokenizer` mirrors JDK 17 `javax.swing.text.html.parser.Parser` (non-strict: malformed-tag recovery, quoted attribute names, in-tag `--` comments, a lone in-tag `-` discarding the next character, unterminated-comment re-parse; Parser tokenization is byte-identical in JDK 21/25) and rewrites only whole attributes — drops active tags (incl. `<isindex>`, `<link>`, the DTD's literal-content elements, the description's own `<html>`/`<head>`/`<body>` in every spelling Swing accepts, `<thead>`/`<tbody>`/`<tfoot>`, which outside a table send Swing's parser into unbounded recursion, and `<noscript>`, which does the same inside `<dir>`/`<menu>` — more chains reach it on JDK 21+; content is kept for all four), blanks non-`data:` `src`, drops `background=`, and drops style declarations that can load a resource or that Swing's CSS parser would throw on (unbalanced quotes, or `(`/`[` blocks that don't close in order). Output invariants: text never contains a raw `<` (no tag reassembly), text is copied verbatim, and only clean, unedited tags (no error recovery, no entity reference in an unquoted value) are copied byte-for-byte — everything else is re-emitted normalized, so Swing reads it exactly as tokenized. A seeded fuzz + a verbatim-tag invariant test check both against Swing's own parser
- **Description render guard** — every description render goes through `RallyDetailPanel.setDescriptionHtml`: a fresh document per render, and if `setText` throws (Swing's CSS parser on malformed author styles, or a `StackOverflowError` from ~900 nested tables) it logs at WARN and shows the description's plain text (`DescriptionHtml.plainText`, built from the tokenizer's text runs via `RallyHtmlUtils.visibleTextLines`, so it can't hit the parser failure that triggered it), then a short error. Exceptions during later layout/paint are outside the guard
- **Rally strings never render as HTML** — labels that show Rally-controlled text use `html.disable` (`rallyTextLabel()`, the detail header, the dropdown renderers); tooltips built from Rally text are `literalHtmlTooltip` (escaped inside `<html>`, since the IDE renders tooltips as HTML) with plain accessible text via `describeLiterally`; Messages dialogs go through `literalHtmlMessage` (the panel's `showInfo`/`showError`/`showWarning`/`confirm`), and balloons/notifications escape interpolated IDs and server error text
- **Load errors labeled by type** — `loadErrorLabel` classifies by exception type (auth / network / timeout / 429), and `queryAllArtifactsParallel` rethrows an auth/connection/host failure as-is when every type failed, so a bad API key reads "Auth error", not "Error"
- **Workspace/Project refs** — Rally WSAPI requires full API URLs for workspace/project params. The `normalizeRef()` method in RallyApiClient handles conversion from bare IDs, ref paths, or full URLs
- **Iteration filtering** — Iterations are scoped to the selected project via server-side project filtering in `queryIterations()`
- **Sandbox persistence** — Gradle sandbox moved to `.sandbox/` (outside `build/`) so settings survive `./gradlew clean`
- **Shared color constants** — `RallyColors` object eliminates color duplication across renderers
- **invokeLaterIfAlive helper** — private inline function replaces 19 disposed-guard boilerplate instances
- **Balloon notifications** — non-modal success feedback via JBPopupFactory
- **Generic plugin design** — No workflow-specific or company-specific custom fields hardcoded. Only standard Rally fields (Method, ScheduleState, etc.) are used
- **Log policy** — expected environmental failures (network, auth, declined transitions) log at WARN; `LOG.error` is reserved for programming errors because the platform turns it into an IDE fatal-error report
- **Selection preserved across refresh** — `updateListModel` re-selects surviving rows by ref and collapses the detail split when the selection is gone
- **Settings-apply refresh** — `RallySettingsListener.TOPIC` (application message bus) triggers `loadTickets()` when Settings are applied
- **No silent partial exports** — a failed attachment or test-case lookup fails that export (no fallback to an empty list / "not found"; attachments are looked up before any inline image is written, so nothing is left behind). Failed attachment/image downloads still write the file (marked inside it) but are counted per exporter (`failedDownloadCount`). `buildExportSummary` (`RallyExportSummary.kt`) turns any failure — incl. failed linked-test-case lookups and downloads — into a sticky WARNING balloon and a "— with errors" status; the detail panel's test-case export shows a warning dialog. Exception: the backend-only bulk export (`prefetchDescriptions`) still writes "" on a failed description fetch — make it strict before a Bulk Export UI ships
- **Slow credential store at startup** — `checkInitialConfiguration` keeps waiting (status "Waiting for API key...") past the 2s `isConfigured()` budget instead of showing "Not configured". A PasswordSafe read that throws sets `RallySettings.apiKeyLoadFailed` (the key stays "not loaded", so Settings Apply still can't persist a blank over it), which ends the wait with an "API key unavailable — re-enter it in Settings" warning. `getClient()` throws `RallyApiKeyUnavailableException` (loading vs. failed) rather than building a client with an empty key, never waiting on the EDT; `loadTickets` shows it as that status, not as "Error"
- **Export filenames** — attachments are `${objectId}_name` (OID-keyed, like inline images) so re-exports overwrite instead of accumulating copies

## Performance Optimizations

| Layer | Technique | Impact |
|-------|-----------|--------|
| **Caching** | LRU query cache (200 entries, 2-min TTL), bounded image cache (10 MB cap) | Eliminates redundant API calls without unbounded heap growth |
| **List queries** | `LIST_FIELDS` excludes Description field | Smaller payloads for 200+ items |
| **Lazy description** | `fetchDescription()` on demand when detail panel opens | Faster initial list load |
| **Parallel list** | `queryAllArtifactsParallel` runs user stories + defects concurrently (defects on `apiExecutor`, stories inline) — called from the **outer pooled thread** (not `apiExecutor`) so it only ever blocks on one sub-task slot, staying deadlock-free | ~½ the cold-load latency (`max` instead of `stories+defects` RTT) |
| **Client-side state filter** | State-combo changes re-filter `allArtifacts` in memory (`applyStateFilter` at display time) instead of re-querying Rally (a failed last load is retried instead — `stateChangeAction`) | Instant state switches, zero network |
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
| **StatusBadge cheap setter** | `update()` schedules no revalidate/repaint (rubber-stamp renderers don't need it) — it only `invalidate()`s on a text change so CellRendererPane re-lays out the row; real-container badges call `refresh()` | Removes per-cell repaint-queue churn on 200-row lists |
| **Off-EDT description pipeline** | Description fetch + image resolution + `wrapHtml` run on the unbounded pooled thread, not apiExecutor/EDT | apiExecutor never parks on image joins; EDT stays responsive on multi-MB descriptions |
| **Export download dedup** | Inline images + attachments deduplicated across JSON+Markdown passes (thread-safe `ConcurrentHashMap`); raw-bytes attachment endpoint with base64 fallback; exporter bypasses the UI image cache | Halves export downloads, ~1x peak heap, no UI-cache eviction |
| **Parallel export downloads** | Per-artifact inline images and attachments download on a **dedicated** daemon `Executors` pool (separate from `apiExecutor` and from the export-orchestrator pool to avoid self-deadlock), `allOf().join()`; output order preserved via index/key maps | Image/attachment-heavy exports no longer serialize each download |
| **Export skips redundant lookup** | `exportArtifact{Json,Markdown}(artifact, dir)` overloads reuse the in-memory artifact + `fetchDescriptionStrict` (ref GET, memoized per exporter so both passes share it; a failed fetch or deleted artifact fails the export instead of writing an empty description) instead of re-running a FormattedID search per artifact | One fewer search query per exported artifact |
| **Reentrant bulk mode** | `enterBulkMode`/`exitBulkMode` use an `AtomicInteger` depth counter (floor 0) | Overlapping exports/browse keep a consistent extended-TTL window |
| **Streamed JSON export** | `gson.toJson(output, bufferedWriter)` instead of full-string materialization | Halves peak memory of export write phase |
| **Selection debounce** | 200 ms single-shot Timer between list selection and detail loads (header/badge update instantly) | Arrow-scrolling costs zero API calls for skipped rows |
| **Dedicated export executor** | Per-export daemon pool for export orchestration; apiExecutor only serves short HTTP calls | Tool window stays responsive during multi-ticket exports |
| **Truncation-aware status** | TotalResultCount plumbed through PagedResult/ArtifactQueryResult → "200 of 934 loaded" | Removes silent page-size truncation |
| **Raw-first attachment fetch** | downloadAttachmentBytes: raw endpoint, base64 fallback (save + export) | ~25% less transfer, ~1x peak heap on large attachments |
| **Parallel project load** | Project list loads concurrently with artifacts when scoped to "All Projects" | One RTT off cold open |
| **Shared exporter download pool** | One lazy pool per RallyExporter (AutoCloseable) instead of a pool per call | No pool churn on step-heavy test cases |
| **Linear-time sanitizer** | `SwingTokenizer` scans with `indexOf`, memoizes comment-terminator searches and binary-searches end tags — no regex over whole tags | Multi-MB descriptions (resolved `data:` images) sanitize in tens of ms |
| **Header-only image admission** | `DataImageView` reads only headers before letting Toolkit decode asynchronously: ImageIO `getWidth`/`getHeight` for JPEG/PNG (plus a PNG chunk-header walk, ≤ ~20 ms at the payload cap), bytes 6-9 for GIF (never ImageIO), streamed base64 | Bounded EDT work and heap per description |

## Rally WSAPI Gotchas

Official references: [response/error envelopes](https://techdocs.broadcom.com/us/en/ca-enterprise-software/valueops/rally/rally-help/reference/rally-web-services-api/query-parameters/responses.html) and [query parameters](https://techdocs.broadcom.com/us/en/ca-enterprise-software/valueops/rally/rally-help/reference/rally-web-services-api/query-parameters.html).

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
- `TestCase` has no `Iteration` attribute — `(Iteration.Name = …)` on `/testcase` returns 200-with-Errors. The plugin filters test cases by sprint via `WorkProduct.Iteration.Name`, but that is **unverified against a live workspace**: Broadcom KB 57618 says `WorkProduct` points to the abstract Artifact type, which has no `Iteration`, so the traversal may also fail (alternatives: `TestSets.Iteration`, or resolve the sprint's stories/defects first)
- The `/user` endpoint ignores unknown fetch fields and silently returns a ref-only user — always pass user fields explicitly

## Data Model (Tier 1 Fields)

Core artifact models (`RallyUserStory`, `RallyDefect`, `RallyTaskItem`) include: FormattedID, Name, ScheduleState/State, Owner, Project, Iteration, PlanEstimate, Description, CreationDate, LastUpdateDate, plus extended fields: Blocked, BlockedReason, Release, Ready.

## Testing

489 tests across 43 classes: Rally API JSON parsing, query-value escaping, Retry-After parsing, host/scheme/port validation, field-update bodies, exporter formatting, file/HTML utils (incl. inline-color/embedded-stylesheet stripping), sprint summary, gzip body decoding, status color mapping, StatusBadge behavior, plus the audit additions: `RallyArtifactExt` (effectiveState/storyPoints), `Scope`/`StateFilter` enums, `requireNoErrors` + `ArtifactQueryResult` (the 200-with-Errors / partial-failure paths), the `RallyApiClient` instance helpers (`normalizeRef`, reentrant bulk-mode depth, `checkOperationResult`/`parseCreateResult`), and the query-per-scope builder (H1), selection-index restore, load-status text (M3), exporter attachment naming (M5), and entity decode order (L1); plus the review fixes: State-change reload decision (`stateChangeAction`), StatusBadge re-layout inside a renderer container, `RallySettings.awaitApiKey` (slow keychain reads never read as an empty key), plugin/configurable id pinning (`PluginDescriptorIdentityTest`), and HTTP-level tests against an in-process fake Rally server / authenticating proxy (`testutil/FakeRallyServer`): strict export description fetch, detail-panel description failures thrown (not read as empty, 401 kept as an auth failure), export failing on an attachment-lookup error for artifacts and test cases with nothing left on disk, failed-download counting, the export summary (`buildExportSummary`), the failed-keychain-read state, proxy auth + non-retried 401/407 mapping, attachments-cache eviction; plus the PR #12 review fixes: state-change confirmation/status text, Start Working button state, owner-step reporting and outcome text (`RallyWorkActionsTest`), and repository choice in multi-root projects (`RepositoryChoiceTest`); plus the publish-readiness fixes: type-based load-error labels (`loadErrorLabel`), auth failures kept typed through the parallel list query and 200-with-Errors user/workspace lookups surfaced (`RallyApiClientErrorSurfacingTest`), description active-content neutralizing (`neutralizeActiveContent`), Markdown attachment link targets, and the My-Tickets-without-Username check. Run via `./gradlew test`.

Regression coverage also checks exhausted HTTP 429 retries through the parallel list query and quoted angle brackets before HTML image/style attributes (including Swing parser verification and preservation of resolved data images). Server-search generations are invalidated at ticket-load entry so early-return states cannot be overwritten by an older search.

The PR #12 audit fixes (docs/audits/2026-09-23-pr-12-review.md) added, all written test-first and checked against targeted mutants:
- **Sanitizer vs Swing's parser** (`RallyHtmlSanitizerTest`): every assertion is made on what `ParserDelegator` sees (`swingView`) or what a `JTextPane` renders (`renderedText`). Covers malformed-tag and attribute-boundary bypasses, text preservation, tag reassembly, comments, CSS crash inputs, a seeded random fuzz, a structured attribute-list fuzz, and a verbatim-tag invariant (a byte-for-byte copied tag must read the same to Swing as its normalized form).
- **Editor kit** (`SafeHtmlEditorKitTest`, `StockKitFetchControlTest`, `DescriptionRenderingTest`, `DataUrlHandlerTest`): a loopback `testutil/FetchRecorder` server proves zero requests for every resource-loading construct, with no sanitizer in front and through the full pipeline, plus a stock-kit positive control per fetch family within the same quiet window.
- **Image limits** (`DataImageLimitsTest`, `DataImageAwtDifferentialTest`): caps, the per-description budget, PNG IHDR/chunk rules, and GIF logical screens, differentially against AWT's real decoder (`testutil/AwtDecode`, `testutil/TestImages`).
- **Render guard and fallback** (`DescriptionHtmlTest`): CSS-parser exceptions and small-stack overflows fall back to plain text, with a pairwise element sweep proving `wrap()` never hands Swing's parser an overflowing pair.
- **HTML sinks** (`RendererHtmlSafetyTest`, `RallyComboRenderersTest`, `ConnectionTestReportTest`, `RallyApiClientPartialFailureNotificationTest`): Rally strings never render as live HTML in labels, tooltips, dropdowns, dialogs or notifications.
- **Panel wiring** (`RallyToolWindowPanelTest`, `CreateArtifactDialogTest`, `BasePlatformTestCase`s driving the real panel and dialogs against a concurrent `FakeRallyServer`):
  - My Tickets without a Username reconciles lists without an owner-less query (Settings change, Refresh, project switch);
  - a connection change drops the old lists;
  - Create refuses stale refs and keeps fresh ones;
  - the sprint footer never shows another load's sprint;
  - Start Working re-enables when a reload drops the selection;
  - the Create dialogs' dropdowns use the HTML-safe renderer;
  - Create keeps offering the current project's sprints while a Refresh re-queries them.

The final PR #12 review added: a lone in-tag `-`, unbalanced `(`/`[` style blocks and `<noscript>` recursion in the sanitizer tests; GIF sizing without ImageIO (`DataImageLimitsTest`, incl. a cut-off extension chain and a multi-MB comment chain); and Test Connection's ref-vs-lookup failure classification (`ConnectionTestReportTest`).

## Build & Run

```bash
./gradlew clean build          # Build plugin
./gradlew verifyPlugin         # Verify plugin structure
./gradlew runIde               # Launch sandbox IDE with plugin
```

Warnings during `runIde` about GradleJvmSupportMatrix, Maven, or memory leaks on UI switch are IntelliJ 2024.1 internal issues — not from this plugin.

The build uses Gradle's **configuration cache** (enabled in `gradle.properties`, unblocked by the Kotlin 2.0 upgrade) — `compileKotlin`/`test`/`buildPlugin` all store/reuse a config-cache entry. If a future change reintroduces a config-cache incompatibility, the line in `gradle.properties` can be removed without losing the Kotlin upgrade. `verifyPlugin` downloads several full IDE distributions and needs multiple GB of free disk.

`buildSearchableOptions` is ON by default — every shipped ZIP is built locally (no CI), and the index is what lets Settings search find the Rally page by "API key", "workspace", etc. It boots a headless IDE per `buildPlugin`, so pass `-PskipSearchableOptions=true` on the command line to skip it while iterating — not in `~/.gradle/gradle.properties`, which would silently drop the index from release ZIPs too (L10).

`verifyPlugin` uses `recommended()` (one release per major across 241–262; Plugin Verifier 1.410 reads the 2025.3+ layout that broke 1.405) and a strict `failureLevel`: deprecated, scheduled-for-removal, internal, override-only and non-extendable API usages fail the task, not just incompatibilities. The one known failure is the deliberate 241-floor keep `FileSaverDescriptor(title, desc, vararg ext)` (its `(title, desc)` replacement exists from 251); the task stays red until the floor is raised to 251. `CredentialAttributes` is built in Java (`CredentialAttributesCompat`) so it binds the plain `(String)` constructor instead of Kotlin's deprecated default-args one; the export-directory browse button uses `addBrowseFolderListener(TextBrowseFolderListener(...))`; the Settings page is Kotlin UI DSL v2. Marketplace's verifier also flags `URL(URL, String, URLStreamHandler)` in `dataImageUrl` (JDK-deprecated since 20); its replacement `URL.of(URI, handler)` needs JBR 21 (242+). Proxy handling no longer uses `CommonProxy`/`HttpConfigurable`.

## Current Filter Options (in Tool Window)

All Tickets, My Tickets, User Stories, Defects, Test Cases, Recent Activity

## State Filter Options

Any State, Idea, Defined, In-Progress, Completed, Accepted, Active (excludes Accepted/Completed/Idea)

## What's Implemented

- **Refresh** — clears the query cache and re-queries projects and sprints too; both dropdowns are disabled until the reload commits, a failed re-query keeps the current lists, and Create keeps offering the current project's sprints meanwhile
- Tool window with ticket list, search (debounced client-side + server-side fallback), filters, and sprint summary
- **Detail panel** — selecting a ticket shows its HTML description (inline images resolved to base64 data URIs and rendered through `SafeHtmlEditorKit`'s data: handler, capped at 10 downloads and a per-description pixel budget, guarded against stale selections; Rally's light-UI author colors stripped and body/link/error colors pinned to the IDE theme) and three tabs:
  - **Test Cases** — linked test cases with Method (Automated/Manual) badges and LastVerdict (Pass/Fail)
  - **Tasks** — child tasks with State badge, Owner name, ToDo hours
  - **Attachments** — linked files with content-type icon and human-readable file size. Double-click to save to disk. Context menu: Save to Disk, Open in Browser
- **Test Case detail view** — selecting a Test Case in the main list shows its description + Test Steps tab (StepIndex, Input, Expected Result)
- **Collapsible detail panel** — starts hidden, auto-expands on ticket selection, auto-collapses on deselection, thin dark divider for manual resize
- **Create User Story dialog** — full dialog with Name, Project, Sprint, Assign to me, Description, and file attachment upload (any type, 50 MB cap)
- Project switcher dropdown — fetches projects from Rally, persists selection across sessions
- Iteration (sprint) switcher dropdown — shows date ranges in dropdown (e.g., "Sprint 42 (2026-02-10 → 2026-02-24)"), deduplicates iterations, defaults to "All Sprints"
- Settings page (Server URL, API Key, Workspace Ref, Username, Page Size, Export Directory) with Test Connection (shows DisplayName + UserName, checks the Workspace Ref via `getWorkspaceName` — blaming the ref only for Rally's answer about it (400-404, 200-with-Errors), while a failed lookup (connection, 429/5xx, non-JSON reply) reads as unverified and still checks the Username — and says plainly when the Username matches no Rally user) and folder chooser
- State change actions (In-Progress, Completed, Defined) via toolbar and context menu — supports multi-select with optimistic UI updates. Confirms multi-ticket changes and always confirms Completed (`stateChangeConfirmation`)
- Open in Browser, Copy FormattedID via context menu and detail panel header
- **Export** — toolbar Export button exports selected artifact(s) + their linked test cases to JSON and Markdown; selected test cases (Test Cases scope) export directly with their steps and are counted as test cases. Also available via right-click context menu
- Inline image downloading during export (replaces Rally image URLs with local paths)
- Attachment downloading during export (via base64 content API, deduplicated across JSON+Markdown)
- **Start Working** — dialog lets user choose branch prefix (feature, bugfix, hotfix, refactor, chore, test) with auto-selection based on ticket type. Creates/checks out branch, moves ticket to In-Progress, assigns owner. Verifies branch checkout before proceeding with Rally state changes. Single selection only (the button is disabled for multi-select, test cases, and while a run is in progress); branches the repo holding the project root in multi-root projects and refuses when that's ambiguous; the owner is assigned only after the state change lands, and every skipped assignment is reported (a blank Username, already announced in the dialog, only in the status bar)
- **Security** — Rally query value escaping, attachment filename sanitization with path traversal prevention, canonical path verification
- **Threading safety** — PasswordSafe access cached off-EDT, project/iteration selection read from cached data instead of Swing state, generation-based stale result prevention
- **Performance** — caching, parallel queries, lazy description loading, HTTP/2, generation-based cancellation, disposed-client guards (see Performance Optimizations table)
- **Create Defect dialog** — full dialog with Name, Project, Sprint, Severity, Priority, Assign to me, Description, and file attachment upload (any type, 50 MB cap)
- **Create Task** — create task from detail panel Tasks tab, linked to the selected work product
- **Edit Points** — edit PlanEstimate via context menu on user stories/defects
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
| `queryAllTestCases()` | Query all test cases in workspace/project |
| `queryTestCases(workProductRef)` | Test cases linked to a user story/defect (cached) |
| `queryTasksForWorkProduct(ref)` | Tasks linked to a user story/defect (cached) |
| `queryTestCaseByFormattedId(id)` | Single test case lookup |
| `queryTestSteps(formattedId)` | Test steps for a test case |
| `queryAttachments(formattedId)` | Attachments for any artifact (cached) |
| `searchArtifacts(text, scope)` | Server-side search by Name/FormattedID |
| `fetchDescription(artifactRef)` | On-demand description fetch for the detail panel (cached; every failure is thrown, uncached — auth shows as an auth error, anything else as "Couldn't load the description" with a Retry link, never as "No description") |
| `fetchDescriptionStrict(artifactRef)` | Export-path description fetch: throws on HTTP/connection errors and deleted objects |
| `getArtifactByFormattedId(id)` | Lookup by FormattedID (US/DE/TA) |
| `updateArtifactState(ref, type, state)` | Change ScheduleState/State |
| `updateArtifactOwner(ref, type, ownerRef)` | Change Owner |
| `getAttachmentContent(contentRef)` | Get base64 attachment content |
| `downloadAttachment(url, cache)` | Download attachment bytes via HTTP (bounded image cache unless cache=false, 10 MB cap) |
| `downloadAttachmentBytes(attachment)` | Attachment bytes, raw endpoint first, base64 fallback |
| `queryProjects()` / `queryIterations()` | Project and sprint lists |
| `queryCurrentIteration()` | Find active sprint by today's date |
| `createUserStory()` | Create a new user story |
| `createDefect()` | Create a new defect |
| `createTask()` | Create a new task linked to a work product |
| `updateArtifactField(ref, type, field, value)` | Update any field on an artifact |
| `uploadAttachment(ref, path)` | Two-step attachment upload (content + link) |
| `getUserByUsername(username)` | Lookup user by email |
| `clearCache()` / `clearArtifactCache()` | Cache invalidation |
| `clearAttachmentsCache(formattedId)` | Evict one artifact's attachment list after an upload (epoch-guarded against in-flight queries) |
| `enterBulkMode()` / `exitBulkMode()` | Extended cache TTL for exports |
| `getCurrentUser()` | Get authenticated user info |
| `buildWebUrl(artifact)` | Construct Rally web UI URL |

## Git Commit Rules

- **Do NOT include `Co-Authored-By: Claude`** or any AI attribution in commit messages
- Follow conventional commit format: `type(scope): description`

## Not Yet Implemented

- Create Test Case dialog
- Bulk Export UI (backend implemented in RallyExporter but no dedicated bulk-select UI)

## Known Follow-ups (from the final PR #12 review)

- **Huge inline images stall `setText`** — Swing's `Parser.addString` grows its buffer by 128 chars, so `setText` is quadratic in the longest `data:` attribute (~13 s at 10M chars, on every JDK). Inline attachments are downloaded uncapped by `resolveInlineImages`; cap their bytes there (and/or lower `MAX_DATA_IMAGE_PAYLOAD_CHARS`). Pre-existing, not introduced by PR #12
- **Zero-delay looping GIFs** — AWT replays a looping GIF whose frame delay is 0 (or that has no Graphic Control Extension) with only `Thread.yield()` between frames: one "Image Animator" thread per GIF at 100% CPU plus continuous repaints while the description is shown. Browsers clamp such delays; a fix must walk the GIF the way `sun.awt.image.GifImageDecoder` does (clamp delays in place, or drop the NETSCAPE2.0 loop)
- **Refresh that drops the saved project** — `iterationsCurrent` survives a Refresh, so if the project re-query drops or restores the saved project, Create offers the previous scope's sprints for about one sprint-list round trip. Record the project ref the list was loaded for and compare it in `createDialogInputs()`
