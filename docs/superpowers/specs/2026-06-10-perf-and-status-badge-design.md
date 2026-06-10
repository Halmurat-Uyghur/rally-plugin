# Performance Improvements + Status Badge — Design

**Date:** 2026-06-10
**Scope approved:** all 14 performance items + tinted-chip status badge in all four state-rendering locations.
**Constraint:** every change is behavior-preserving (no new user-facing features other than the badge visual).

All findings below were produced by a multi-agent audit and individually verified against the code
(24 candidates, 15 confirmed, 9 refuted). File:line references are to the tree at commit `57c0ad1`.

---

## Part A — Performance

### Tier 1: user-visible latency

#### A1. gzip compression for JSON responses
- **Where:** `RallyApiClient.kt` — `executeGet` (365–377), `executePost` (873–885), decode path in `executeWithRetry`.
- **Problem:** JDK `HttpClient` neither sends `Accept-Encoding` nor decompresses. All Rally JSON arrives uncompressed; list pages are 100–500 KB and gzip 5–10x.
- **Change:** add `.header("Accept-Encoding", "gzip")` to both JSON request builders. Fetch with `BodyHandlers.ofByteArray()`; in a single shared helper, if the response carries `Content-Encoding: gzip`, wrap bytes in `GZIPInputStream` before UTF-8 decode, else decode directly. Status-code inspection (retry logic) stays before body decode and is unchanged.
- **Untouched:** `downloadAttachment` binary path.
- **Error handling:** decompress only when the header says gzip — a server or proxy that ignores or strips the header degrades gracefully to today's behavior. A corrupt gzip body surfaces as the same `RallyApiException` an unparseable body produces today.
- **Test:** unit test for the decode helper (plain bytes, gzipped bytes, gzip header but plain body → exception).

#### A2. Parallelize iterations load with the artifact fetch
- **Where:** `RallyToolWindowPanel.kt` `loadTickets()` (473–487).
- **Problem:** `loadIterations()` is a blocking round trip that runs before `artifactsFuture` is even submitted, but its result is only *required* when a saved sprint name must be validated against `cachedIterations` before `buildQuery()`. The common case (saved iteration blank or "All Sprints" — the default, and forced after every project switch by the reset at line 325) doesn't need it.
- **Change:** read `savedIter` first. If blank/"All Sprints", run `loadIterations(client)` as a `CompletableFuture` on `client.apiExecutor` concurrently with `artifactsFuture` (it already handles its own errors and posts combo updates via `invokeLaterIfAlive`). Keep today's sequential path when a saved sprint must be validated.
- **Risk note:** `resolveSelectedSprint` is only reached when a sprint filter is active — that path keeps the sequential ordering, so no consumer ever sees a not-yet-loaded iteration list.

#### A3. Stop parking an `apiExecutor` thread during inline-image resolution
- **Where:** `RallyDetailPanel.kt` — `descFuture` suppliers (340, 413), `resolveInlineImages` join (1037).
- **Problem:** the description future runs on `client.apiExecutor` (4 threads, shared with every other client call) and blocks on `futures.map { it.join() }` while up to 10 images download on the separate `imageExecutor` (60 s timeout each). Rapid ticket switching can park several of the 4 threads on stale joins and starve fresh loads.
- **Change:** run the description fetch + `resolveInlineImages` synchronously on the outer `ApplicationManager.executeOnPooledThread` thread (which today only submits futures and exits) instead of `supplyAsync(client.apiExecutor)`. Test-case/task/attachment futures stay on `apiExecutor`. Ordering, error handling, and generation checks are unchanged — only which thread blocks.

#### A4. Deduplicate inline-image downloads across the JSON + Markdown export passes
- **Where:** `RallyExporter.kt` — image loop (577–582), `downloadRallyImage` (603); prior art: attachment dedup map `downloadedPaths` (95, used at 505–535).
- **Problem:** `exportArtifactJson` then `exportArtifactMarkdown` both call `downloadInlineImages()` on the identical description with identical output paths — every image is downloaded and written twice. Images > 1 MB bypass the client image cache entirely, so the second download is a full network round trip.
- **Change:** add a session-scoped `downloadedImagePaths = ConcurrentHashMap<String, String>()` keyed by `"$objectId:$imgDir:$localFileName"`, consulted at the top of `downloadRallyImage` and written on success. Mirror the attachment pattern exactly, including *not* caching failures. The `RallyExporter` instance is created once per export operation (`RallyToolWindowPanel.kt:1407`), so the map's lifetime is correct.

### Tier 2: EDT responsiveness, redundant work, memory

#### A5. Fixed cell height on the ticket list
- **Where:** `RallyToolWindowPanel.kt:265` (renderer install), `ArtifactCellRenderer` (1900–1970).
- **Problem:** with `fixedCellHeight == -1`, Swing calls the renderer + `getPreferredSize()` for **every** element on **every** model event to compute row heights — and the renderer is a nested-layout `JPanel`. Model rebuilds fire on every debounced keystroke and refresh.
- **Change:** after installing the renderer, render one prototype artifact and set `artifactList.fixedCellHeight = prototype.preferredSize.height`. All rows are already uniform height, so zero visual change. (Re-derive on `LafManagerListener` theme change is *not* needed: the panel is rebuilt per tool-window instance and font doesn't change within one; if scaling issues surface in `runIde`, fall back to `prototypeCellValue`.)

#### A6. Patch single rows in place after optimistic updates
- **Where:** `RallyToolWindowPanel.kt` — `changeState` (1619–1630), `editPoints` (1530–1539), `startWorking` (1786), `finishWorking` (~1815), `updateListModel` (906).
- **Problem:** after patching `allArtifacts`, these paths call `applySearchFilter()` → `updateListModel()` → `clear()+addAll()` — a full O(n) rebuild that fires full-range events (re-measuring every cell pre-A5) and silently clears the selection; `editPoints` carries extra code just to re-find and restore the selection it destroyed.
- **Change:** add a helper `patchArtifactsInModel(updated: List<RallyArtifact>)` that finds each updated ref in `listModel` and calls `setElementAt(patched, index)` (single `contentsChanged(i, i)` event, selection preserved), then refreshes stats from the displayed list. Use it at all four sites; delete `editPoints`' selection-restore workaround.
- **Invariant kept:** `allArtifacts` (the unfiltered source list) is still patched exactly as today; only the *model* update strategy changes. If a patched artifact no longer matches the active filter, it remains visible until the next reload — identical to today's behavior, since `applySearchFilter` re-filters from `allArtifacts` which was patched the same way.

#### A7. Compute `wrapHtml` off the EDT
- **Where:** `RallyDetailPanel.kt` — `wrapHtml` (959–982), call sites inside `invokeLater` (372–383, 463–474).
- **Problem:** three full passes/copies over a string that can exceed 10 MB after base64 image inlining, all on the EDT, immediately before the (unavoidable) HTML parse from setting `descriptionPane.text`.
- **Change:** in both `thenAccept` continuations, after a cheap generation pre-check, compute `val wrapped = wrapHtml(text)` on the completing background thread and pass only the final string into `invokeLater`. `wrapHtml` touches no Swing components (`JBUI.scaleFontSize` is a cached-scale lookup, safe off-EDT).

#### A8. Delete the duplicate src-neutralization pass
- **Where:** `RallyDetailPanel.kt:1054`.
- **Problem:** `resolveInlineImages` ends with `EXTERNAL_SRC_PATTERN...replaceAll(...)`, but every return value flows into `wrapHtml`, which runs the identical pattern at its documented "single chokepoint" (975–979). One redundant multi-MB regex scan + copy per description.
- **Change:** return `sb.toString()` directly; update the comment to point at the chokepoint. Security posture unchanged — the neutralization still runs on every `descriptionPane.text` assignment.

#### A9. Drop unused `Description` from `queryTestCases` fetch
- **Where:** `RallyApiClient.kt:1181`.
- **Problem:** full HTML descriptions are fetched for every linked test case on every ticket selection and export, and no consumer reads `tc.description` from this path (the detail tab renders ID/name/method/verdict; the exporter re-fetches via `queryTestCaseByFormattedId`, which keeps its own `Description` fetch at 1268). Verified by grep across all call sites.
- **Change:** remove `Description` from the fetch list. The codebase already tolerates null descriptions on list paths (`TC_LIST_FIELDS` pattern + `fetchDescription` fallback at `RallyDetailPanel.kt:326`).

#### A10. Cache `getUserByUsername`
- **Where:** `RallyApiClient.kt:497`; callers `RallyToolWindowPanel.kt:973, 1197, 1760`.
- **Problem:** the only uncached read in the client; resolves the same `settings.username` → user ref on the blocking path of every Create Story, Create Defect, and Start Working action.
- **Change:** wrap in the existing cache (`getCached`/`putCache`, key `"user:$safeUsername|$ws"`). Standard TTL applies; `clearCache()` on manual Refresh evicts it. Do **not** cache the not-found/exception path.

#### A11. Narrow `clearArtifactCache` eviction
- **Where:** `RallyApiClient.kt:238–241`.
- **Problem:** artifact mutations cannot change which iteration is current (pure function of date + sprint calendar), yet `clearArtifactCache()` evicts `currentIteration:`, forcing a redundant round trip after every state change. The `sprint:` prefix it also scans is never written by any `putCache` — dead code.
- **Change:** drop both prefixes from `clearArtifactCache`. `clearCache()` (manual Refresh) still evicts everything.

#### A12. Reuse detail-panel scroll panes
- **Where:** `RallyDetailPanel.kt` — 193/199 (originals), 299–304, 336–337, 546–551, 692 (re-creations).
- **Problem:** fresh `JBScrollPane(taskList/attachmentList/stepList)` instances are constructed on every selection toggle and `clear()` — viewport/scrollbar/UI-delegate allocations and EDT revalidation for components that already exist.
- **Change:** hoist `taskScrollPane`, `attachmentScrollPane`, `stepScrollPane` to fields initialized once (mirroring `tcPanel`) and reuse at every `addTab` site.

#### A13. Export memory
- **A13a — stream JSON writes** (`RallyExporter.kt:128, 229, 392`): replace `file.writeText(gson.toJson(output))` with `file.bufferedWriter(UTF_8).use { gson.toJson(output, it) }`. Identical output (same Gson config), roughly halves peak memory of the write phase.
- **A13b — raw-bytes attachment download** (`RallyExporter.kt:514`): the base64 JSON path (`getAttachmentContent`) holds ~3–4x the file size in heap (JSON string + Gson DOM + base64 string + decoded bytes) and transfers ~33% more. `RallyAttachment` already carries `ObjectID`, so download via `client.downloadAttachment("${webBaseUrl}/slm/attachment/$objectId/$name")` — exactly what `downloadRallyImage` does (608–610). Keep `getAttachmentContent` for the Attachments-tab "Save to Disk" path unless trivially unifiable; this change targets the export loop only.
- **Verification for A13b:** confirm a downloaded attachment is byte-identical via both endpoints before switching (one manual check in `runIde` against a real workspace, or skip A13b if no live workspace is available and fall back to A13a only).

#### A14. Generation guard on Create Task completion (correctness, found during audit)
- **Where:** `RallyDetailPanel.kt:789–814`.
- **Problem:** the only async UI-mutation path that skips the generation guard — switch tickets while `createTask` is in flight and the new task row is appended to the *wrong* artifact's Tasks tab.
- **Change:** capture `val gen = generation.get()` before `executeOnPooledThread`; bail in the `invokeLater` when `generation.get() != gen` (plus the existing `disposed` check). `clearTasksCache` at 794 already guarantees the task appears on next open of the correct artifact.

### Explicitly rejected (verified not worth doing)
Parallel pagination (no reachable path requests >1 page); in-flight request coalescing (scenarios unreachable/negligible); skipping reload on state-filter change (changes refresh semantics); `updateListModel` event coalescing (Swing already coalesces); caching resolved inline-image HTML (memory cost > win); StepCellRenderer regex (pattern already precompiled, lists tiny); parallel leaf downloads in export (deadlock-adjacent, marginal); bulk-export prefetch dedup (bulk UI not exposed); Test Connection parallelization (settings-dialog only, not hot).

---

## Part B — Status Badge (tinted chip)

### B1. Color model — `RallyColors`
Centralize the state→color mapping that is currently triplicated
(`RallyToolWindowPanel.kt:1953–1961`, `RallyDetailPanel.stateColor()` 951–957, `TaskCellRenderer` 1144–1152).

- Add a small value holder: `class StateColors(val foreground: JBColor, val background: JBColor)` —
  background = tinted fill (low-alpha variant of the foreground hue), all pre-allocated constants.
- Add `RallyColors.forState(state: String?): StateColors` backed by a pre-built immutable `Map<String, StateColors>` plus a `NEUTRAL` fallback. No allocation per call.
- Palette (all `JBColor(light, dark)`; existing foregrounds reused, new ones follow the documented
  grayscale/deuteranopia-safe convention; backgrounds ≈ 14–18% alpha of the foreground hue, tuned in `runIde`):

| State | Foreground | Notes |
|---|---|---|
| Idea | new purple `JBColor(Color(128,60,170), Color(190,140,225))` | currently colorless |
| Defined | existing `DEFINED` orange | |
| In-Progress | existing `IN_PROGRESS` green | |
| Completed | existing `COMPLETED` blue | |
| Accepted | new `ACCEPTED` gray (promotes inline `JBColor.GRAY`) | |
| Submitted | shares Defined orange | same lifecycle stage |
| Open | shares Fail red | active problem |
| Fixed | shares Completed blue | |
| Closed | shares Accepted gray | |
| Pass | existing `PASS` cyan | |
| Fail | existing `FAIL` red | |
| Unknown / No Verdict / other | `NEUTRAL` gray | fallback |

### B2. `StatusBadge` component
New `ui/StatusBadge.kt`: a minimal `JComponent` that paints a rounded-rect fill behind the state text.

- Mutable per-render state: `text: String`, `colors: StateColors` (set via one `update(text, colors)` method; empty text → `isVisible = false`).
- `paintComponent`: antialiased `fillRoundRect` (arc `JBUI.scale(8)`) in `colors.background`, then text in `colors.foreground`. **No allocations in paint** — no new `Color`/`Font`/`Insets`/`Rectangle` per call (project convention, CLAUDE.md "JBColor pre-allocation").
- `getPreferredSize`: `FontMetrics` width + `JBUI.scale(8)` horizontal padding each side, `JBUI.scale(2)` vertical.
- Selection behavior: the badge keeps its own fill and foreground on selected rows — it does **not** switch to `selectionForeground`. The surrounding row panel still paints `selectionBackground` around it (the `rightPanel` containers are `isOpaque = false`), which resolves today's colored-text-vs-selection-highlight contrast clash. Verify contrast in both themes in `runIde`.

### B3. Application sites (all four places state renders)
1. **Ticket list** — `ArtifactCellRenderer` (`RallyToolWindowPanel.kt:1905, 1910, 1947–1961`): replace `stateLabel: JLabel` with one shared `StatusBadge` instance in `rightPanel`. State-string derivation (1947–1951: TestCase → `lastVerdict ?: "No Verdict"`, else `scheduleState ?: state ?: "Unknown"`) is unchanged; the color switch is replaced by `RallyColors.forState`.
2. **Detail header** — `stateBadge` (`RallyDetailPanel.kt:78, 176, 313–319, 534`): the `JBLabel` becomes a `StatusBadge`; delete the private `stateColor()` helper (951–957). Side benefit: Pass/Fail test-case verdicts in the header get correct colors (today they fall through to dark gray). `clear()` hides the badge.
3. **Tasks tab** — `TaskCellRenderer` (`RallyDetailPanel.kt:1144–1152`): state label → badge.
4. **Test Cases tab** — `TestCaseCellRenderer` (`RallyDetailPanel.kt:1091–1106`): Method (Automated/Manual) and LastVerdict labels → badges. Method colors keep their current mapping (Automated → In-Progress green, Manual → Defined orange) via two dedicated `StateColors` entries rather than overloading state names.

Optional follow-ups (out of scope, noted for later): colored dots in the State filter combo; badge colors in the sprint-summary footer.

### B4. Testing
- Unit: `RallyColors.forState` mapping (every known state + null + unknown → expected constants).
- Unit: `StatusBadge` preferred-size grows with text; empty text hides.
- Existing 10 JSON tests must stay green (`./gradlew test`); `./gradlew verifyPlugin` and a manual `runIde` pass in light + dark themes for badge contrast and selected-row rendering.

---

## Build sequence
1. Part A Tier 2 small items first (A8, A9, A10, A11, A14, A5, A12) — independent one-liners-to-small, each compile+test.
2. A6 (model patching), A7 (wrapHtml off EDT), A2 (parallel iterations).
3. A1 (gzip, with new unit test), A3 (executor restructure), A4 + A13 (export).
4. Part B: B1 colors → B2 component → B3 sites → B4 tests.
5. `./gradlew clean build test verifyPlugin`, then manual `runIde` verification pass.
6. Update CLAUDE.md Performance Optimizations table and What's Implemented (badge).
