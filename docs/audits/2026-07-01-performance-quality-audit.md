# Rally Plugin — Performance & Quality Audit (Follow-up)

| | |
|---|---|
| **Date** | 2026-07-01 |
| **Scope** | Whole plugin — `src/main/kotlin` (~7,540 LOC, 21 files) + build scripts + plugin.xml |
| **Baseline commit** | `e1ecb6e` (branch `chore/perf-quality-audit`) |
| **Baseline health** | `./gradlew test` → **182/182 passing** (CLAUDE.md still says 176 — update when convenient) |
| **Goal** | Find remaining bugs and speed/efficiency improvements after the 2026-06-13 audit |
| **Method** | Full manual read of every source file, cross-checked against CLAUDE.md's documented invariants; dead-code greps; baseline test run |

> Follow-up to [2026-06-13-performance-quality-audit.md](2026-06-13-performance-quality-audit.md). Everything that audit fixed (requireNoErrors, parallel queries, bulk-mode depth, off-EDT description pipeline, fixed cell heights, export dedup, …) was re-verified and **holds up**. This report contains only *new* findings.

---

## 1. Executive summary

The plugin is in strong shape: the executor topology is deadlock-free by construction (verified — see §6), caching is coherent, disposal is airtight, and the security posture (host/scheme/port pinning, query escaping, filename sanitization, external-src neutralization) is consistent. The remaining opportunities are concentrated:

| Severity | Count | Theme |
|----------|-------|-------|
| 🔴 High | 2 | 1 functional bug with a trivial trigger, 1 error-reporting misuse that fires an "IDE Internal Error" on every network hiccup |
| 🟠 Medium | 7 | Interactive-pool starvation during export, missing selection debounce, silent list truncation, heavyweight attachment save, duplicate files on re-export, cold-start RTT, refresh UX |
| 🟡 Low | 11 | Entity decoding, wrong fetch fields, misc hygiene/dead code, build speed |

**Highest-ROI fixes, in order:** H2 (one-word change × 12 sites), H1 (one `if`), M2 (a Timer, mirroring the existing search debounce), M1 (a dedicated export executor), M3 (surface `TotalResultCount`).

---

## 2. 🔴 High

### H1 · "Test Cases" scope + a selected Sprint = guaranteed load failure

**`RallyToolWindowPanel.kt:814-817` + `:568-569`**

`buildQuery()` appends `(Iteration.Name = "…")` whenever a sprint is selected, *regardless of scope*. When scope is **Test Cases**, that query is sent to `/testcase` — but Rally's `TestCase` type **has no `Iteration` attribute**. Rally returns HTTP 200 with a populated `Errors` array ("Could not parse: attribute Iteration…"), which `requireNoErrors` correctly converts into a thrown `RallyApiException`.

**Trigger:** pick any sprint in the toolbar, then switch Scope to "Test Cases". The whole load fails — status shows "Error", the list is empty, and (via H2) an IDE fatal-error balloon fires.

Ironically, the 2026-06-13 fix made this *visible*: before `requireNoErrors`, the same combination silently showed an empty list. Either way it has never worked.

**Fix (S):** in `buildQuery`, skip the iteration condition when `Scope.fromDisplay(scope) == Scope.TEST_CASES`. Two options for keeping the sprint filter meaningful:
- **Minimal:** drop the condition and (optionally) note "(sprint filter not applicable)" in the status/empty text.
- **Better:** use Rally's dotted traversal instead — `(WorkProduct.Iteration.Name = "…")` is valid on TestCase and filters test cases whose linked story/defect is in the sprint (test cases with no WorkProduct won't match; that's arguably correct).

Add a unit test on the built query per scope — the query-builder logic is already the kind of thing pinned by tests elsewhere.

### H2 · 12 `LOG.error` sites turn routine failures into "IDE Internal Error" reports

**`RallyToolWindowPanel.kt:621, 1289, 1362, 1475, 1535, 1671, 1695, 1708, 1784` · `RallyDetailPanel.kt:718, 985` · `RallyGitOps.kt:92`**

In the IntelliJ Platform, `Logger.error(msg, throwable)` doesn't just log — it raises an **IdeaLoggingEvent**: the red exclamation icon starts blinking, the exception appears in the *IDE Fatal Errors* dialog attributed to this plugin, and users are prompted to submit a report. JetBrains' own guidance is that `error()` is for programming errors only; expected environmental failures should be `warn()`.

Every one of these 12 sites catches an *expected* failure — Rally unreachable, VPN down, 401 from a rotated key, a declined state transition:

- `loadTickets`'s catch (`:621`) is the worst: **every reload while offline fires a fatal-error report.** With `pendingReload` retries, several per minute.
- There is also a shutdown race: `dispose()` calls `apiExecutor.shutdownNow()` while a late `CompletableFuture.supplyAsync(...)` inside a running load can throw `RejectedExecutionException` — which lands in the same `LOG.error` catch and produces a spurious fatal report *during IDE shutdown*.

**Impact:** users on flaky networks see "IDE Internal Error … caused by Rally Integration" regularly. For a marketplace plugin this is a review-score problem, and it drowns real bugs in noise.

**Fix (S):** change the 12 sites to `LOG.warn`. The UI already surfaces each failure (status text, dialogs, balloons), so nothing is lost. Keep `LOG.error` only for genuinely impossible states.

---

## 3. 🟠 Medium

### M1 · Exports starve the interactive thread pool

**`RallyToolWindowPanel.kt:1351-1389` vs `RallyDetailPanel.kt:441-463` / `queryAllArtifactsParallel`**

`exportSelectedArtifact()` runs one task per selected artifact **on `client.apiExecutor` (4 threads)**. Each task is long-lived: description fetch, up to 50 inline-image downloads, all attachments, then every linked test case exported serially — the worker stays parked on the inner pools' `join()` the whole time.

Everything interactive also rides `apiExecutor`: the detail panel's test-cases/tasks/attachments queries, the defects half of `queryAllArtifactsParallel`, sprint summary, iterations. So exporting **4+ tickets saturates the pool**, and until the export finishes:
- selecting a ticket leaves all three detail tabs stuck at "(…)",
- Refresh's defect query queues behind export tasks (the outer pooled thread blocks on `defectsFuture.get()`),
- state changes queue too (`changeState` fans out on `apiExecutor`).

**Fix (M):** run the *per-artifact export orchestration* on its own small dedicated executor (2–4 daemon threads, created per export, `shutdown()` in the existing `finally`) — exactly the pattern the exporter already uses for its download pools. `apiExecutor` then only serves short HTTP calls, and the tool window stays responsive during long exports. (Same applies to `changeState`'s fan-out if you ever bulk-move 50+ tickets, but that's short-lived and lower priority.)

### M2 · No debounce on ticket selection → wasted API calls while scrolling

**`RallyToolWindowPanel.kt:403-426` → `RallyDetailPanel.showArtifact`**

Holding ↓ through the list fires the selection listener once per row (`valueIsAdjusting` is only true for mouse drags). Each row immediately launches **3 `apiExecutor` tasks + 1 pooled description task + a full detail-panel model clear/repopulate**. The generation counter makes *stale* work cheap (queued tasks bail on their first line), but:
- HTTP calls already in flight for skipped rows run to completion (up to 60 s each), occupying pool slots,
- every skipped row still costs an EDT round of clearing lists, resetting tab titles, and setting "Loading…" — visible flicker,
- the query cache fills with entries for tickets the user never looked at.

**Fix (S):** debounce selection → `showArtifact` with a ~200 ms single-shot `javax.swing.Timer`, exactly like the existing 300 ms search debounce (`searchDebounceTimer`). Update the header/badge immediately (cheap, local data) and defer only the network-backed loads. Arrow-scrolling then costs zero API calls until the user settles.

### M3 · List silently truncates at Page Size — `TotalResultCount` is parsed and thrown away

**`RallyApiClient.kt:651-681` · `RallyToolWindowPanel.kt:594`**

`loadTickets` passes `maxResults = pageSize` (≤ 200) per artifact type, and `queryAllPages` stops there — but the status line says "**N loaded**" with no hint that the workspace has more. A team with 900 stories sees the 200 most-recently-updated and has no way to know the list is capped. The information is already on hand: `queryResult.totalResultCount` is deserialized on every page and then discarded.

**Fix (M):** plumb `totalResultCount` (summed per type) through `ArtifactQueryResult` and render "200 of 934 loaded" (and mirror it in `refreshLoadedStatus`). No extra network cost — it's in every response already. Optionally add a "Load more" affordance later; the honest count alone removes the silent-truncation trap.

### M4 · "Save to Disk" downloads attachments via the base64-JSON endpoint

**`RallyDetailPanel.kt:969-972` vs the better pattern at `RallyExporter.kt:641-656`**

The detail panel's save action uses `getAttachmentContent(contentRef)`: the entire attachment arrives base64-wrapped in a JSON document, is parsed into a Gson DOM, and then decoded. For a 50 MB attachment that is ~33% more transfer and a **~4-5× transient heap spike** (UTF-16 body string + DOM + base64 string + decoded bytes ≈ 250 MB) on the pooled thread.

The exporter already solved this: try the raw-bytes endpoint (`/slm/attachment/<OID>/<name>` via `downloadAttachment(url, cache=false)`), fall back to the base64 Content ref on failure.

**Fix (S):** port that raw-first/base64-fallback block into `saveAttachmentToDisk()` (the `RallyAttachment` already carries `objectID` and `name`). ~25% less transfer, ~1× peak heap, faster saves.

### M5 · Re-exporting the same artifact duplicates every attachment on disk

**`RallyExporter.kt:662-677`**

The name-collision loop (`name_1`, `name_2`, …) exists to keep two *different* same-named attachments apart within one run — but the dedup map (`downloadedPaths`) lives on the `RallyExporter` *instance*, while the files persist on disk. Every export click creates a fresh exporter, so a second export of the same ticket finds `screenshot.png` already present and writes `screenshot_1.png`, the third writes `screenshot_2.png`… The JSON/MD always reference the newest copy; the older ones just accumulate.

Inline images don't have this problem because their filenames are **deterministic (OID-keyed)** and simply overwritten.

**Fix (S):** apply the same scheme to attachments — include the attachment `ObjectID` in the filename (`${objectId}_${safeName}`) and overwrite instead of counter-suffixing. Collisions become impossible (OIDs are unique), re-exports are idempotent, and the write lock can go back to protecting nothing hotter than directory creation.

### M6 · Cold start pays a serial round trip for the project list

**`RallyToolWindowPanel.kt:507-543`**

First load (and every settings change): `loadProjects(client)` completes **before** the artifact query starts. The serialization is only genuinely needed when a saved project name must be resolved to a ref for query scoping. In the common "All Projects" case (the default), the project list feeds nothing but the dropdown — the same insight that already made iterations load concurrently when no saved sprint needs validation.

**Fix (M):** mirror the iterations pattern — when `selectedProject` is blank/"All Projects", run `loadProjects` via `CompletableFuture.runAsync(..., apiExecutor)` alongside the artifact fetch and join at the end. Saves one full RTT (often 300–800 ms against Rally) on cold open for most users.

### M7 · Refresh drops the selection and strands an open, empty detail panel

**`RallyToolWindowPanel.kt:1037-1043` (updateListModel) + `:589` (detailPanel.clear())**

`loadTickets` → `applySearchFilter` → `updateListModel` rebuilds the model with selection listeners detached. Consequences on every Refresh / scope / project / sprint change:
- the user's selection is silently dropped (annoying right after Refresh, where the same ticket is almost always still present), and
- because the listener never fires for the implicit deselection, the **auto-collapse never runs** — the split stays open showing a blank "Select a ticket to view details" pane.

**Fix (M):** after `updateListModel`, re-select the previously selected artifact by `ref`/`formattedID` if it's still in the new list (this also re-populates the detail panel via the reattached listener); if it isn't, collapse the divider explicitly (the same two lines the listener uses).

---

## 4. 🟡 Low

**L1 · `stripHtml` decodes `&amp;` before `&lt;`/`&gt;`/`&quot;` → double-decode** — `RallyExporter.kt:98-102`. A description containing the literal text `&amp;lt;b&amp;gt;` exports as `<b>`. Standard fix: decode `&amp;` **last**. (S)

**L2 · `getUserByUsername` requests artifact fields from the `/user` endpoint** — `RallyApiClient.kt:632-633` uses `buildQuery(...)` with the default `fields = LIST_FIELDS` (`FormattedID`, `ScheduleState`, …). Rally ignores unknown fetch fields, so the returned user has only `_ref`/`_refObjectName`; `displayName`/`userName`/`emailAddress` are always null. All current call sites happen to survive on `ref`/`refObjectName` fallbacks, but the first caller to read `user.userName` gets a silent null. Fix: `fetch=UserName,DisplayName,EmailAddress,ObjectID,_ref`. (S)

**L3 · Sprint footer can show an arbitrary team's sprint under "All Projects"** — `RallyApiClient.kt:1335-1361`: `queryCurrentIteration` uses `pagesize=1` with **no order** and (from `loadSprintSummary`) a null project ref, so any workspace with multiple concurrent sprints returns whichever Rally feels like. Also `LocalDate.now()` is IDE-local while Rally compares in workspace time — off-by-one near midnight. Low stakes (footer metadata only); fix by ordering (`EndDate ASC`) and/or noting the limitation. (S)

**L4 · "Re-attach from the detail panel" points at a feature that doesn't exist** — `RallyToolWindowPanel.kt:1278-1279`. The attachment-upload-failed balloon tells users to re-attach from the detail panel, but the Attachments tab only offers *Save to Disk* / *Open in Browser*. Reword ("re-attach in the Rally web UI") or actually add an Upload action to the tab. (S)

**L5 · Per-artifact Markdown headings aren't escaped** — `RallyExporter.kt:199, 489` interpolate raw `artifact.name` into `# …` while the bulk paths carefully `escapeMarkdown()` everything. A name containing `#`/`*`/`[` corrupts the heading. One-line consistency fix. (S)

**L6 · Exporter spins up a fresh 8-thread pool per call** — `RallyExporter.kt:589, 725`. `appendStepsTable` calls `downloadInlineImages` **twice per test step** (`:799-800`), so an image-heavy 20-step test case creates and destroys ~40 pools. Cheap per pool, but pure churn — hoist one lazily-created pool per `RallyExporter` (or per export operation) and shut it down where bulk mode exits. (S)

**L7 · Dead code** — confirmed by grep, zero callers in `src/main`:
- `queryIterationArtifacts` (`RallyApiClient.kt:1367-1370`) — nothing calls it.
- Sequential `queryAllArtifacts` (`:835`) — its only caller is the dead method above. CLAUDE.md documents it as "the safe path for apiExecutor callers," but no such caller exists anymore.
- `RallyWorkspace` (`RallyApiModels.kt:294-306`) — never referenced.
- Unused imports `JBTextArea`, `JBTextField` in `RallyToolWindowPanel.kt:40-41`.
- (`bulkExportJson`/`bulkExportMarkdown`/`prefetchDescriptions` are also caller-less but documented as awaiting the Bulk Export UI — keep or delete deliberately.)
Delete or annotate; dead public methods on the client are where behavior drift hides. (S)

**L8 · Proxy support is static-HTTP-only** — `RallyApiClient.kt:61-75` honors host/port but not PAC (`USE_PROXY_PAC`), not proxy authentication (no `Authenticator` wired to `HttpClient`), and not the proxy-exceptions list. On PAC-based corporate networks the plugin silently goes direct. Consider `IdeaWideProxySelector` (available at the 241 floor) + an authenticator sourced from `HttpConfigurable`. (M)

**L9 · Settings Apply doesn't nudge the tool window** — after changing server/key in Settings, the panel keeps showing the old data/status until the user clicks Refresh (the client *is* rebuilt lazily via the settings snapshot, so no staleness bug — just a dead moment). A message-bus topic or a direct `loadTickets()` on apply would close the loop. (S)

**L10 · `buildSearchableOptions = true` slows every `buildPlugin`** — build.gradle.kts. It boots a headless IDE per build to index one small settings page. Gate it (`= System.getenv("CI") != null`) or disable during development. (S)

**L11 · Jitter comment says "±30%" but code adds 0–+30%** — `RallyApiClient.kt:1191`. Behavior is fine (positive-only jitter still de-synchronizes retries); fix the comment. (S)

---

## 5. Further ideas (not scored)

- **Single-flight request coalescing:** two concurrent identical queries (e.g. double-Refresh, two project windows) both hit the network; a small in-flight-future map keyed by cache key would collapse them. Real but rare for a single-user tool window.
- **Conditional requests (ETag/If-Modified-Since):** considered and rejected — Rally WSAPI doesn't serve usable validators.
- **Detail-panel refresh after state change** re-fires all three tab queries; they're served from the (deliberately preserved) detail caches so there's no network cost, but the tab titles still flicker through "(…)". A header-only update path would remove the flicker.
- **Bigger `apiExecutor`?** No — the observed starvation is structural (long-lived tasks parking on joins), and M1/M2 fix it at the source. 4 threads + HTTP/2 multiplexing is otherwise sufficient.

---

## 6. Verified sound (no action needed)

Checked deliberately, found correct:

- **Executor topology is deadlock-free**: the three documented rules hold at every call site — `queryAllArtifactsParallel` and `prefetchDescriptions` are only ever called from unbounded pooled threads; export tasks on `apiExecutor` only make direct HTTP calls or block on *separate* dedicated pools; the detail panel's image joins happen on pooled threads against `imageExecutor`.
- **Cache coherence**: workspace/project snapshotting before key construction, partial results never cached, `clearArtifactCache` prefix list matches actual `putCache` prefixes, `currentIteration` exemption is sound, bulk-mode depth counter is correct under overlap.
- **Disposal chain**: `clientLock` serializes `dispose()` vs `getClient()`; `imageExecutor.shutdown()` (not `shutdownNow`) is the right call for the join-completion reason documented; generation guards cover every async UI write I traced.
- **Retry policy**: creates/uploads retry only on 429; connection errors don't replay non-idempotent bodies; Retry-After parsing rejects HTTP-dates; sleeps are chunked and interrupt-safe.
- **Security posture**: `requireSameHost` on every URL fetch including image/attachment downloads; query-value escaping applied at every interpolation site I checked; `safeResolve` used for every export write; balloons escape interpolated text; external `src` neutralization is a true chokepoint (`wrapHtml`).
- **Settings persistence keys** frozen correctly across the package rename; PasswordSafe access never blocks the EDT.
- **The 2026-06-13 fast-path refutation still holds** (generation counter discards stale description renders).

---

## 7. Suggested fix order

| # | Finding | Effort | Payoff |
|---|---------|--------|--------|
| 1 | H2 — `LOG.error` → `LOG.warn` (12 sites) | S | Stops fatal-error spam for every offline user |
| 2 | H1 — skip/translate sprint filter for Test Cases scope | S | Un-breaks a whole filter combination |
| 3 | M2 — selection debounce | S | Snappier detail panel, far fewer wasted API calls |
| 4 | M4 — raw-endpoint attachment save | S | Big memory/transfer win on large attachments |
| 5 | M5 — OID-keyed attachment names | S | Idempotent re-exports |
| 6 | L1, L4, L5, L11 — one-liners | S | Cheap correctness/polish |
| 7 | M1 — dedicated export executor | M | Tool window stays alive during big exports |
| 8 | M3 — surface `TotalResultCount` | M | Removes silent truncation |
| 9 | M7 — preserve selection across refresh | M | Daily-use UX |
| 10 | M6 — parallel project load on cold start | M | ~1 RTT off cold open |
| 11 | L2, L3, L6–L10 — as convenient | S–M | Hygiene, robustness |
