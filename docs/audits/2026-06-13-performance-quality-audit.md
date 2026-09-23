# Rally Plugin — Performance & Quality Audit

| | |
|---|---|
| **Date** | 2026-06-13 |
| **Scope** | Whole plugin — `src/main/kotlin` (~7,344 LOC, 15 files) + tests (~1,551 LOC) |
| **Baseline commit** | `86b6339` (branch `main`) |
| **Goal** | Improve performance, speed, and overall quality |
| **Method** | 11-dimension parallel agent audit + adversarial per-finding verification, cross-checked against a full manual read |

> This document is the durable record of the audit. Section 1 is the executive summary; sections 2–8 are the prioritized analysis; the **Appendix** is the complete catalog of all 84 verified findings (auto-generated from the audit data, with file/line references and the verifier's notes).

---

## 1. Executive summary

**This is a genuinely well-engineered plugin — better than most.** The threading discipline (off-EDT API calls, generation counters, disposal guards), the caching layer (true access-ordered LRU, bounded image cache with byte accounting, HTTP/2 + gzip), and the security posture (PasswordSafe, SSRF host/scheme/port pinning, query escaping, HTML neutralization) are all deliberate and well-reasoned, with the *why* documented inline and in `CLAUDE.md`.

This is **not a rescue job.** The improvement opportunities cluster in three places:

1. **A handful of real correctness gaps** that silently hide Rally errors from the user.
2. **Performance is concentrated, not pervasive** — most of the available speed is in 4–5 specific spots, not spread everywhere.
3. **The dominant quality drag is structural** — two "god files" (2,143 + 1,297 lines) and ~5 copy-pasted patterns. Nothing is *broken*; it is where future bugs will hide and what slows every change.

### Severity profile (after adversarial verification)

| Severity | Count | Notes |
|----------|-------|-------|
| 🔴 High | 3 | 2 silent-correctness, 1 structural (god object) |
| 🟠 Medium | 14 | Performance/concurrency/quality/architecture |
| 🟡 Low | 67 | Refinements, micro-optimizations, hygiene |
| **Total verified** | **84** | from 85 raw findings; **1 refuted** |

The verifiers were deliberately harsh: **39 of 84 findings were downgraded** ("the code already handles this" / "severity overstated"). The numbers above are the *post-verification* severities, which is why "only 3 High" is an honest reflection of a mature codebase rather than a thin review.

### The one refuted finding

A reviewer claimed the description "fast path" (`descriptionPane.text = wrapHtml(desc)` in `showArtifact`) could be overwritten by a slower async render of a *previously* selected ticket. **Verified false** — the `generation` `AtomicLong` is incremented on every `showArtifact`/`clear` and checked inside every `invokeLater`, so stale renders are correctly discarded.

---

## 2. Methodology

- **11 specialist reviewers** ran in parallel, one per dimension: threading/EDT, API performance & caching, memory & leaks, UI rendering, concurrency & races, code quality, error handling & API correctness, build/platform/deprecations, security, architecture, and test coverage.
- **Every finding was then adversarially verified** by an independent skeptic agent that opened the cited code, quoted the real lines, and returned `confirmed` / `needs-nuance` / `refuted` with a corrected severity. This is why several originally-"High" findings appear here as Medium (e.g. the export-executor "deadlock" claim was corrected to a throughput issue; the `StatusBadge` repaint claim was downgraded because `RepaintManager` coalesces).
- The `testing` dimension reviewer hit an API rate-limit mid-run, so **section 8 (testing) is authored from a direct read** of the test suite rather than the agent output.
- All findings were cross-checked against a full manual read of every source file, so the framings below reflect the *corrected* understanding, not the raw reviewer claims.

---

## 3. Correctness — fix these first (highest value, lowest effort)

The two findings that survived as **High** with ~0.97 confidence. Both are *silent* failures.

### 🔴 H1 · Server-side errors silently become "empty results"
**`RallyApiClient.kt`** — `handleResponse()` only checks the HTTP status code. But Rally WSAPI 2.0 returns **HTTP 200 with a populated `Errors` array** for many failure modes (an unknown field in `fetch`, a malformed/over-nested query, a field the user lacks permission to read, a scoping error). Only `queryAllPages` (line 643) checks `queryResult.errors`. **Every other query method** — `queryTasksForWorkProduct`, `queryTestCases`, `queryTestSteps`, `queryAttachments`, `queryProjects`, `queryIterations`, `queryCurrentIteration`, `getArtifactByFormattedId` — reads `safeResults` directly and returns an empty list.

**Impact:** A scoping error makes the Tasks / Test Cases / Attachments tabs show `(0)` — indistinguishable from a ticket that genuinely has none, with no log and no balloon. A bad workspace ref silently disables the project/sprint dropdowns. Export reports "not found." This is precisely the silent-failure class the audit targets.

**Fix (M):** extract one shared check and call it after every `gson.fromJson`:
```kotlin
private fun QueryResultData<*>.requireNoErrors(ctx: String) {
    if (!errors.isNullOrEmpty())
        throw RallyApiException("Rally query error ($ctx): ${errors.joinToString("; ")}")
}
```
Also surface the never-read `warnings` field at `LOG.warn`.

### 🔴 H2 · "Create failed" shown when only the *attachment* upload failed
**`RallyToolWindowPanel.kt`** — in both `showCreateDefectDialog` (~1119) and `showCreateUserStoryDialog` (~1373), the create call and `uploadAttachment` are in the **same `try` block**. If the artifact is created but the upload throws (network blip, a 502 — uploads use `retry = false`), the exception unwinds to the outer `catch` → `statusLabel.text = "Create failed"`, and the success balloon plus the optimistic list-insert are skipped.

**Impact:** The artifact **is** in Rally, but the user is told creation failed and the new ticket never appears. The natural reaction is to retry → a **duplicate** defect/story. `CLAUDE.md`'s pre-create size re-check guards only the *oversize* case; any other post-create upload failure still mis-reports.

**Fix (S):** split into two phases — show success and do the optimistic insert *immediately* after the create succeeds, then attempt the upload in its own inner `try/catch` with a distinct message: "Created DE123, but attachment upload failed — re-attach from the detail panel."

---

## 4. Performance & speed (the primary goal)

Honest magnitudes after verification.

| # | Finding | Location | Real impact | Effort |
|---|---------|----------|-------------|--------|
| **P1** | List loads run **two serial round trips** (stories *then* defects) | `queryAllArtifacts` 818–835; called from panel 541 | **+150–300 ms on every cold load** and every project/scope/sprint switch (`stories_RTT + defects_RTT` instead of `max`). Masked by the 2-min cache for repeat loads. | M |
| **P2** | Export **serializes the slow path** — downloads run inline on the 4-thread `apiExecutor`, 4-wide per artifact, serial *within* each artifact | panel 1593–1630; `RallyExporter` 600–614, 497–513 | A 10-artifact × 5-image export = ~50 fully-serialized GETs across ≤4 lanes. **Not a deadlock** — verifier corrected the original claim; calls are synchronous, not resubmitted. | M |
| **P3** | Every ticket-open costs a **dedicated description round trip** | `RallyDetailPanel` 347–353, 491 | `LIST_FIELDS` omits Description, so `artifact.description` is always null → a 4th RTT on top of the 3 tab queries, on every first view. | M |
| **P4** | Export **re-queries each artifact by FormattedID** though the panel holds the full object | `RallyExporter` 369/417; panel 1594 | 1 redundant **search** query per exported artifact (heavier than a ref GET). Verifier corrected "2×" → "1×" (the markdown pass hits cache). | M |
| **P5** | `StatusBadge.update()` calls `revalidate()+repaint()` on **every cell stamp** | `StatusBadge.kt` 33–40 | Queue churn on 200-row lists (×2 badges on the TC list). Verifier downgraded High→Medium: `RepaintManager` *coalesces* these, so real jank is unlikely — but it is wasted work and a poor renderer contract. | S |
| **P6** | Every filter change = **full network re-query**, even though state filtering is already client-side | combo listeners 344–380; `applyClientFilter` 806 | `stateCombo`/`scopeCombo` changes call full `loadTickets()` and re-hit Rally, even though `allArtifacts` is already in memory. Filter in memory instead → instant, zero network. | M |

**Smaller perf wins (Low):** image-cache eviction re-creates its iterator per evicted entry (hoist it); `wrapHtml` runs multi-pass regex on the EDT in the rarely-hit fast path; the detail-panel lists lack `fixedCellHeight` (FontMetrics recomputed per row); `DecodedResponse` retains both the `byte[]` and the decoded `String`.

> **Practical cap worth knowing:** `loadTickets` passes `maxResults = pageSize` (≤200), so `queryAllPages` fetches exactly one page. The ticket list is effectively capped at ~200 items per type. Likely intentional, but users with larger backlogs will silently not see everything.

---

## 5. Code quality & maintainability (the structural drag)

Where most of the 84 findings live, and where future velocity is bottlenecked. None are bugs *today*; all are mechanically extractable with no behavior change.

- **God files** — `RallyToolWindowPanel.kt` (2,143 lines, ~33 fields, 13 `@Volatile`) and `RallyDetailPanel.kt` (1,297) fold UI layout + threading orchestration + business logic + dialogs + renderers + client lifecycle into single classes. Nothing is testable in isolation.
- **The two Create dialogs are ~85–90% duplicated** (~250 lines) — `CreateDefectDialog` / `CreateUserStoryDialog` plus their handler methods share a byte-identical `doValidate()` and a near-identical create flow. **This is the live drift risk** (the documented reason `withState()` exists — but for the create path).
- **`CreateResult` / `OperationResult` + `Errors` unwrapping copy-pasted 8×** in `RallyApiClient` — extract `parseOperationResult()` / `parseCreateResult<T>()`.
- **"Effective state" rule (`scheduleState ?: state`, TestCase → `lastVerdict`) reimplemented 7×** across 4 files with *inconsistent* fallbacks (`?: ""` vs `?: "Unknown"`); **plan-estimate type-switch 4×**. Extract `RallyArtifact.effectiveState` / `.storyPoints` extensions.
- **Scope/state/type as untyped magic strings** (`scope == "Test Cases"`, `_type == "HierarchicalRequirement"`) duplicated across combo arrays, query builder, client, and exporter — a typo compiles and silently disables a filter. Introduce enums/constants.
- Smaller duplication: **6 query methods bypass the existing `buildQuery()` helper**; **4× "find tab by title prefix" scan**; **3× identical `openInBrowser`**; **4× optimistic-update scaffold**.

**Recommended first extractions (zero behavioral risk, follows the existing `RallySprintSummary.kt` precedent):** move cell renderers to their own files → extract an `AbstractCreateArtifactDialog` base → add the JSON-unwrap helpers and `effectiveState` extension → then the larger `RallyDataController` / `RallyActions` split.

---

## 6. Architecture (the ceiling on how good this can get)

Not urgent, but these define the long-term ceiling:

- **No service/repository layer.** `RallyApiClient` is `new`'d *inside* the tool window, which doubles as the app's state store (`currentClient`, `cachedProjects`, `cachedIterations`, the loaded/generation flags). Closing the tool window discards the entire warm cache + thread pool. A project-level `@Service` owning the client/caches would enable reuse, keep-warm, and unit-testing. *Worth it mainly if a second consumer (gutter, status-bar widget) ever appears.*
- **Hand-rolled concurrency: 3 uncoordinated thread pools** (`apiExecutor`=4, `imageExecutor`=4, the app pool) + `AtomicLong` generation counters + manual `.get()`/`.join()`. The "avoid `apiExecutor` self-deadlock" gymnastics exist *because* a bounded pool blocks on its own subtasks. **Key finding from verification: IntelliJ has bundled `kotlinx-coroutines` since platform 233 — below the 241 floor — so migrating to a `Disposable`-scoped `CoroutineScope` needs no `sinceBuild` bump.** Coroutines would collapse the three pools, replace generation polling with real structured cancellation (`job.cancel()`), and make the deadlock constraint disappear (suspension, not thread-parking).
- **No `Task.Backgroundable` / `ProgressManager`** for export and bulk operations — no progress UI, no cancellation, just a status label.
- **Minimal observability** — no per-request timing or cache hit/miss counters to diagnose a slow Rally instance.

---

## 7. Build & forward-compatibility

All build-tooling, not runtime — but two directly affect iteration speed:

- **Kotlin Gradle Plugin 1.9.25 blocks Gradle's configuration cache** (`Unsupported provider registered as a task completion listener`) — every build is slower than necessary. Upgrading to **Kotlin 2.0.x** unblocks it and clears a Gradle-10 deprecation. *Highest-value build change.*
- **`instrumentCode = true`** runs the form/`@NotNull` instrumentation pass for a module with **zero `.java` and zero `.form` files** — set it `false`.
- **`untilBuild = "261.*"`** will lock users out of 2026.2+ (build 262); EAPs are imminent as of this audit. Consider raising it or dropping the upper bound.
- The four `@Suppress("DEPRECATION")` 241-floor keeps are **legitimate and correctly documented** — no action needed.

---

## 8. Testing gaps (authored from a direct read — the agent was rate-limited)

The **145 tests are good but narrow**: they cover the *pure / companion* functions (query escaping, Retry-After parsing, host/port validation, exporter formatting, file/HTML utils, gzip decode, sprint summary, colors, badge). The **high-risk logic is untested**, and the reason is the god-files — it is welded to a live `HttpClient` and Swing:

- **The entire cache layer** — `getCached`/`putCache` TTL expiry, LRU eviction, bulk-mode TTL. A core *performance* feature with **zero tests**.
- **`queryAllPages` pagination** — multi-page accumulation, `totalResultCount`/`pageSize` math, the `maxResults` cap.
- **`normalizeRef`** — bare id / ref path / full URL. Pure, trivially testable, currently `private`.
- **`buildQuery` AND-nesting** and **`applyClientFilter`** (the "Active" set, scope filtering) — pure logic trapped inside the Swing panel.
- **The Errors-array handling** from H1 — once centralized, a one-line test target.

**The unlock:** introduce a thin HTTP seam (an interface for "send request → response body") that `RallyApiClient` depends on. That single extraction makes cache + pagination + error-handling testable *without a network* — and it is the same seam the architecture refactor wants anyway.

---

## 9. Recommended sequence (impact vs. effort)

| Phase | Items | Why first |
|-------|-------|-----------|
| **1 — Correctness** (≈½ day) | H1 (Errors-check helper), H2 (split create/upload) | Silent failures; tiny, high-value diffs |
| **2 — Speed quick wins** (≈1 day) | P1 (parallelize list load), P6 (client-side state/scope filter), P5 (`StatusBadge` setter) | Directly hits the speed goal; small diffs |
| **3 — Quality quick wins** (≈1 day) | `parseCreateResult`/`parseOperationResult`, `effectiveState` extension, move renderers to own files | Mechanical, kills drift risk, shrinks god-files |
| **4 — Build** (≈½ day) | Kotlin → 2.0.x (+ config cache), `instrumentCode = false`, bump `untilBuild` | Faster builds + forward reach |
| **5 — Bigger refactors** (multi-day, staged) | `AbstractCreateArtifactDialog`; HTTP seam + cache/pagination tests; `RallyDataController`/`RallyService`; coroutine migration | The structural ceiling; incremental, optional |

**Bottom line:** a mature, careful codebase. The needle-movers are few and small — two silent-error fixes, ~4 targeted speed wins, and a handful of de-duplication extractions — and the deeper architecture/coroutine work is genuinely optional and can be staged.

---

## Appendix A — Dimension scorecard

Each dimension was reviewed independently; the count is confirmed/nuanced findings (refuted excluded).

### Threading & EDT correctness — 7 confirmed
Threading discipline in this plugin is genuinely above average: API calls and the PasswordSafe-backed apiKey getter are consistently moved off the EDT, generation counters and a disposed flag guard stale async UI writes, and the invokeLaterIfAlive helper centralizes disposal checks. The real exposure is in modality correctness (every invokeLater uses the implicit NON_MODAL state, so background results that finish while a modal create/edit dialog is open are deferred or, worse, post Messages dialogs that can stack), a few EDT-bound regex/HTML passes on potentially large descriptions, and the imageExecutor join pattern that parks unbounded app-pool threads during rapid ticket switching. None are crashes; they are jank/latency and edge-case ordering bugs rather than data corruption.

### API client performance & caching — 8 confirmed
The API layer is unusually well-engineered for caching: a true access-ordered LRU query cache, bounded image cache with proper byte accounting, HTTP/2, gzip, capped/jittered backoff, streamed JSON export, and pre-allocated TypeTokens. The dominant remaining wins are concurrency-shaped rather than caching-shaped: list loads run two serial round trips (stories then defects), and the multi-artifact export path nests work onto the same 4-thread apiExecutor it is already saturating, creating a genuine deadlock/starvation hazard and serializing the slowest part of export. There are also a few redundant round trips (export re-fetches artifacts the UI already holds; detail selection always costs a separate description fetch) that are correct but add latency the user feels on every ticket open.

### Memory & resource leaks — 8 confirmed
Disposal is handled with genuine care: RallyToolWindowPanel is registered as the content disposer, both panels carry a `disposed` flag plus generation counters that make stale async work into no-ops, and apiExecutor is shut down on both client rebuild and dispose. The two real leaks are unbounded growth of per-artifact detail caches (no LRU on the query cache prefixes that matter) and JDK HttpClient instances never being closed when the client is rebuilt or disposed — both bite on long IDE sessions with frequent ticket browsing or settings/project changes. A few smaller retention and stream-handling issues round out the list.

### UI rendering & responsiveness — 7 confirmed
The UI layer is well-engineered and shows clear performance awareness: shared cell-renderer instances (no per-paint component allocation), pre-allocated JBColor/StateColors constants, fixedCellHeight from a prototype render, off-EDT description/image pipeline with generation-based cancellation, debounced search, and in-place row patching for optimistic updates. The genuine remaining problems are concentrated in two places: (1) StatusBadge.update() calls revalidate()+repaint() on every cell render, which fires a validate/repaint request per badge per cell per paint pass — a real repaint/layout-storm source on the ticket list and tabs; and (2) several smaller paint-path costs (per-render string concatenation for text+tooltip, FontMetrics lookups) and a fixedCellHeight prototype that omits the StatusBadge, risking a too-short row height that clips chips. None are catastrophic, but #1 in particular undercuts the otherwise-careful renderer design.

### Concurrency correctness & races — 8 confirmed
Concurrency handling here is unusually disciplined for a Swing plugin: generation counters guard stale async results, caches are wrapped in Collections.synchronizedMap with explicit synchronized blocks for compound operations, EDT/pooled-thread boundaries are mostly respected, and disposal is guarded by clientLock plus volatile flags. The dominant residual risks are (1) bulk-mode TTL being global mutable state on a shared client that two concurrent export/browse paths can stomp on, (2) a window where descriptionPane fast-path text on the EDT can be overwritten out of order by a slower in-flight async render of a previously-selected ticket, and (3) the imageExecutor shutdown-then-submit window throwing RejectedExecutionException. None are crash-prone in normal single-user flow, but a few can produce wrong UI under rapid selection/refresh or overlapping exports.

### Code quality, maintainability & complexity — 13 confirmed
The codebase is unusually well-commented and the author has clearly thought hard about threading, caching, and security trade-offs — most of the "why" is documented inline and in CLAUDE.md. The core quality problem is structural concentration: RallyToolWindowPanel (2143 lines) and RallyApiClient (1698 lines) are god objects that fold UI assembly, dialogs, threading orchestration, business logic, and HTTP/JSON plumbing into single files, and several cross-cutting concepts (state extraction, the two create dialogs, JSON result-unwrapping, the parallel-update action flow) are copy-pasted rather than abstracted. None of this is broken, but it directly slows future change and is where bugs will hide; almost all of it is mechanically extractable without behavioral risk.

### Error handling, silent failures & API correctness — 11 confirmed
Error handling is unusually mature for a plugin this size: retry/backoff replay-safety, the partial-failure-don't-cache policy, host/scheme/port pinning, and Rally Errors-array checks on creates/updates are all deliberate and well-reasoned (and documented). The two real systemic gaps are (1) the QueryResult.Errors array is only checked in queryAllPages — every direct-query method (tasks, test cases, steps, attachments, projects, iterations, single-artifact lookups) silently returns an empty list when Rally returns a 200-with-Errors (bad fetch field, permission scope, malformed query), so the user sees "0 results" indistinguishable from a legitimate empty set; and (2) the create-then-upload-attachment flow reports "Create failed" when only the attachment upload failed, hiding the fact that the artifact was actually created and inviting duplicate creates. Beyond those, most catch(Exception) blocks log+continue, which is generally appropriate here but masks a few user-facing failures (sprint summary, server search degradation under partial failure).

### Build, plugin config, platform compat & deprecations — 7 confirmed
The plugin config is in good shape and clearly reflects deliberate decisions: modern IntelliJ Platform Gradle Plugin 2.16.0, correct application-level service/configurable/notificationGroup declarations, a DumbAware tool-window factory, a properly isolated optional Git4Idea dependency, and well-documented sinceBuild=241 deprecation keeps that compile cleanly against the 2024.1 SDK. The real, evidence-backed gaps are all build-tooling/forward-compat rather than runtime correctness: Kotlin Gradle Plugin 1.9.25 blocks both the Gradle configuration cache and Gradle 10 (slower builds, a pinned-Gradle trap), instrumentCode runs needlessly for an all-Kotlin/no-form module, the untilBuild=261.* cap will lock users out of 2026.2+, and the codebase relies on prose comments instead of @RequiresEdt/@RequiresBackgroundThread annotations. None of these are blockers; the 241-floor trade-offs documented in CLAUDE.md hold up, but a few are worth revisiting given speed is the top priority.

### Security & sensitive-data handling — 5 confirmed
The security posture is unusually strong and clearly deliberate for a developer-tool plugin. Credentials live in PasswordSafe (with a clean cleartext-migration path), are never logged or placed in URLs, and the credential-change detector uses a truncated SHA-256 fingerprint rather than the raw key. Rally query values are correctly escaped (\" and \\), HTML balloons XML-escape interpolated server strings, the JTextPane description renderer neutralizes external/protocol-relative img src and strips whole &lt;style&gt;/&lt;link&gt; elements to block SSRF/tracking-pixel fetches, and SSRF on every _ref/attachment/inline-image URL is gated by a well-tested host+scheme+port pin (requireSameHost). File writes derived from Rally data go through sanitizeFileName + safeResolve containment. The only genuine gaps are minor: an unsanitized Rally FormattedID flows into a git branch name, plaintext http is permitted with only a warning, and Swing HTMLEditorKit still renders untrusted Rally rich-text (no script execution, but residual parser/resource surface).

### Architecture & strategic improvement opportunities — 10 confirmed
The plugin is functionally rich and the threading/caching has clearly been battle-hardened (generation counters, capped backoff, LRU eviction, disposal guards), but that hardening is spread across two 1300-2100 line "god" UI files that mix Swing layout, raw HTTP-result business logic, dialogs, renderers, and hand-rolled CompletableFuture orchestration. The biggest leverage is structural: there is no service/repository layer (the HTTP client is `new`'d directly inside the tool window and is the de-facto state store), concurrency is hand-managed with three uncoordinated thread pools instead of IntelliJ coroutines, and the data layer re-loads full pages on every filter change with no prefetch. These are not correctness bugs today — they are the ceiling on how fast and how maintainable this can get. The recommendations below are ranked by impact-vs-effort with concrete target designs.

> The `testing` dimension is absent here — its reviewer hit an API rate-limit. See section 8 for a hand-authored testing analysis.


---

## Appendix B — Full findings catalog (84 verified)

Generated from the audit data. Each entry shows the **post-verification** severity; `orig=` marks where the verifier changed it. `conf` is the verifier's confidence. Effort: **S** < 2h · **M** ~half-day · **L** ~multi-day.

### 🔴 High (3)

#### HIGH-1 · QueryResult.Errors array unchecked in all direct-query methods — server-side errors silently become empty results
`Error handling, silent failures & API correctness` · category: correctness · effort: M · conf: 0.97 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** queryTasksForWorkProduct (1320-1322), queryTestCases (1350-1352), queryTestSteps (1378-1380), queryAttachments (1406-1408), queryTestCaseByFormattedId (1437-1438), queryProjects (1252-1253), queryIterations (1289-1290), queryCurrentIteration (1216-1217), getArtifactByFormattedId (775-776). Contrast with queryAllPages line 643.  

**Problem.** Only queryAllPages checks `result.queryResult.errors` (line 643: `if (!result.queryResult.errors.isNullOrEmpty()) throw ...`). Every other query method does `gson.fromJson(...); ... result.queryResult.safeResults` and returns directly. Rally WSAPI 2.0 returns HTTP 200 with a populated `Errors` array (and an empty `Results`) for many failure modes: an unknown field in `fetch`, a malformed/over-nested query, a field the user lacks permission to read, or a scoping error. `handleResponse` only inspects the HTTP status (200 → return), so these all pass through, `safeResults` yields `emptyList()`, and the method returns an empty list as if the query legitimately matched nothing.

**Impact.** Correctness: a user opening a ticket whose Tasks/Test Cases/Attachments query hit a server-side error sees empty tabs ('Tasks (0)') indistinguishable from a ticket that genuinely has none — with no log, no balloon, nothing. queryProjects/queryIterations returning empty silently disables the dropdowns. getArtifactByFormattedId returning a swallowed-empty result surfaces as 'Artifact not found' in export. These are wrong/empty results with zero user feedback — exactly the failure class the task targets.

**Recommendation.** Extract the errors check into a private extension on `QueryResultData<T>`: `private fun QueryResultData<*>.requireNoErrors(context: String) { if (!errors.isNullOrEmpty()) throw RallyApiException("Rally query error ($context): ${errors.joinToString("; ")}") }`. Call it in every direct-query method immediately after `gson.fromJson(...)` and before reading `safeResults`. For `queryProjects` and `queryIterations`, where an empty result would silently disable UI dropdowns, consider also logging at WARN level so misconfigured workspace refs produce visible diagnostics. For `getArtifactByFormattedId`, the existing catch blocks would then naturally log the `RallyApiException` thrown by errors, so no extra handling is needed there. Also surface `warnings` at LOG.warn level in all paths — they are currently never read (field exists in the model at line 29-30) and can carry useful diagnostic information from the Rally API about deprecated query syntax or missing fields.

**Verifier.** The code exactly matches the finding. `handleResponse` (lines 480-503) only inspects the HTTP status code — `200` returns immediately without examining the body. `queryAllPages` (lines 643-645) is the sole location that checks `result.queryResult.errors.isNullOrEmpty()` and throws a `RallyApiException` when errors are present. Every other direct-query method — `queryTasksForWorkProduct` (1320-1322), `queryTestCases` (1350-1352), `queryTestSteps` (1378-1380), `queryAttachments` (1406-1408), `queryTestCaseByFormattedId` (1437-1438), `queryProjects` (1252-1253), `queryIterations` (1289-1290), `queryCurrentIteration` (1216-1217), and `getArtifactByFormattedId` (775-776) — all call `gson.fromJson(...)` then read `result.queryResult.safeResults` with no errors check, then return. `QueryResultData.errors` is a `List<String>?` at line 26-27 and is populated by Gson correctly, but these call sites never read it. Rally WSAPI 2.0 returns HTTP 200 with a populated `Errors` array for field-not-found, malformed queries, permission errors, and scoping errors, so every one of these failure modes becomes a silent empty list. The severity is genuinely high: the three most user-visible paths — Tasks tab, Test Cases tab, and Attachments tab — each go through one of these unchecked methods. A scoping error or a bad field in the fetch list would show empty tabs with no log or balloon, indistinguishable from a ticket with no linked items. `queryProjects` and `queryIterations` silently returning empty would disable those dropdowns entirely with no indication of why. The `getArtifactByFormattedId` path has extra catch blocks (lines 787-793) that log a warning if a `RallyApiException` is thrown, but since errors are never converted to an exception on this path, the log never fires either.

**Evidence.** Line 643: `if (!result.queryResult.errors.isNullOrEmpty()) { throw RallyApiException("Rally query error: ...") }` exists ONLY in queryAllPages; e.g. queryTasksForWorkProduct line 1320-1322: `val result: RallyQueryResult<RallyTaskItem> = gson.fromJson(...); putCache(cacheKey, result.queryResult.safeResults); return result.queryResult.safeResults` — no errors check.

---

#### HIGH-2 · Create-then-upload-attachment: upload failure reports 'Create failed' though the artifact was created, inviting duplicate creates
`Error handling, silent failures & API correctness` · category: correctness · effort: S · conf: 0.97 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** createDefect flow 1119-1153 (and the parallel createUserStory flow around 1330-1402)  

**Problem.** The create call and the attachment upload are inside the SAME try block. `created = client.createDefect(...)` succeeds, then `client.uploadAttachment(created.ref, ...)` (line 1127) can throw (network blip, 50MB server reject, content-type issue). That exception unwinds to the outer `catch (e: Exception)` at 1148, which sets `statusLabel.text = "Create failed"` and shows `"Failed to create defect: ${e.message}"`. The success balloon AND the optimistic list-insert (lines 1130-1147) are skipped entirely.

**Impact.** The artifact IS in Rally but the user is told creation failed and the new ticket never appears in the list. The natural reaction is to retry → a duplicate defect/story is created. This is the exact orphan/duplicate risk CLAUDE.md says the pre-create size re-check guards against, but it only guards the *oversize* case; any other post-create upload failure still mis-reports. uploadAttachment is also called with retry=false on each step, so a transient 502 on the attach step produces this.

**Recommendation.** Split the two phases in both `createDefect` and `createUserStory` flows. Immediately after the create call succeeds, do the optimistic insert and show the success balloon. Then attempt the upload in its own inner try/catch. On upload failure, show a targeted message like "Created DE123, but attachment upload failed: &lt;msg&gt; — you can re-attach from the detail panel." Never show "Create failed" for a post-create upload error. The exact split point is after `val createdId = created.formattedID ?: "?"`: move the `invokeLaterIfAlive { ... allArtifacts = ... }` block to run unconditionally before the upload block. Wrap `client.uploadAttachment(...)` in its own try/catch that updates the status label with the distinct "created but upload failed" message. Example structure:

```kotlin
val created = client.createDefect(...)
val createdId = created.formattedID ?: "?"

// Phase 1: artifact created — notify immediately
invokeLaterIfAlive {
    allArtifacts = listOf(created as RallyArtifact) + allArtifacts
    client.clearArtifactCache()
    applySearchFilter()
    val index = listModel.indexOf(created); if (index >= 0) artifactList.selectedIndex = index
    statusLabel.text = "Created $createdId"
    // show balloon...
}

// Phase 2: optional attachment — failure is non-fatal
if (attachment != null && created.ref != null) {
    try {
        invokeLaterIfAlive { statusLabel.text = "Uploading attachment..." }
        client.uploadAttachment(created.ref, attachment.toPath())
        invokeLaterIfAlive { statusLabel.text = "Created $createdId with attachment" }
    } catch (uploadEx: Exception) {
        LOG.warn("Attachment upload failed for $createdId", uploadEx)
        invokeLaterIfAlive {
            statusLabel.text = "Created $createdId (attachment failed)"
            Messages.showWarningDialog(project,
                "Created $createdId, but attachment upload failed: ${uploadEx.message}\nYou can re-attach from the detail panel.",
                "Rally - Attachment Upload Failed")
        }
    }
}
```

Apply the same refactoring to the `createUserStory` flow (around lines 1373-1408).

**Verifier.** The code exactly matches the described problem. In the createDefect flow (lines 1119-1154) and createUserStory flow (lines 1373-1408), the artifact is created on line 1119/1373, and then `client.uploadAttachment(created.ref, attachment.toPath())` is called on line 1127/1381 — both inside the outer try block. The success notification, balloon, optimistic list insert (`allArtifacts = listOf(created as RallyArtifact) + allArtifacts`), cache clear, and selection logic are all placed AFTER the upload call (lines 1130-1147 / 1384-1401). If `uploadAttachment` throws for any reason — network blip, size check (`require(fileSize <= MAX_UPLOAD_BYTES)`), base64 failure, JSON parse error, or Rally API error on either of its two steps — the exception propagates to the outer catch at line 1148/1402. That catch sets `statusLabel.text = "Create failed"` and shows `Messages.showErrorDialog(... "Failed to create defect: ${e.message}" ...)` with no awareness that `created` already exists in Rally. The optimistic insert is skipped, so the artifact is invisible until a manual refresh. `uploadAttachment` uses `retry = false` on both its internal POST calls, so a transient 502 on attach also triggers this path. The CLAUDE.md notes the pre-create size check as an orphan guard, but that guard only covers the oversize-file path before the create call; it does not protect against post-create upload failures.

**Evidence.** Lines 1122-1128 upload inside the try; line 1148-1153 outer catch: `statusLabel.text = "Create failed"; Messages.showErrorDialog(project, "Failed to create defect: ${e.message}", ...)` — no awareness that `created` already exists.

---

#### HIGH-3 · RallyToolWindowPanel is a 2143-line god object with 6+ unrelated responsibilities
`Code quality, maintainability & complexity` · category: architecture · effort: L · conf: 0.95 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** whole file (class 48-2129), plus ArtifactCellRenderer 2065-2128 and ThinDividerSplitPaneUI 2134-2143  

**Problem.** One class owns: Swing layout/toolbar/filter assembly (setupUI/setupListeners), data-loading orchestration with the iteration-generation concurrency machinery, project/iteration cache management, all artifact actions (changeState/startWorking/finishWorking/editPoints/export/openInBrowser/copyId), two full create dialogs, search filtering, sprint-summary wiring, the client lifecycle (getClient/dispose), the cell renderer, and a custom SplitPaneUI. It holds ~30 fields including 14 @Volatile flags mixing UI state, cache state, and concurrency tokens.

**Impact.** High cognitive load to make any change; the breadth of mutable state means a change in one concern (e.g. iteration loading) can subtly affect another (e.g. create-dialog index mapping, which already shares cachedIterations). The class cannot be unit-tested at all — every responsibility is welded to Swing + Project + the live client.

**Recommendation.** The finding is well-grounded and the recommended refactoring is the right direction. Concrete priority order:

1. **Move `ArtifactCellRenderer` to its own file first** (lowest risk): it has no back-references to the outer class beyond `RallyColors` (a static object). This is a one-file, zero-behavior change and immediately reduces the class by ~65 lines.

2. **Move `ThinDividerSplitPaneUI` to its own file** (trivially small): already a package-private class outside `RallyToolWindowPanel`, just move it.

3. **Move `CreateDefectDialog` and `CreateUserStoryDialog` to their own files**, converting them from `inner class` (which gives access to `cachedProjects`, `cachedIterations`, and outer combo state) to classes that receive those values as constructor parameters. This removes the index-mapping coupling: instead of `cachedIterations[comboIndex - 1]` in the outer class, the dialog should return a `selectedIterationRef: String?` value directly, eliminating the fragile integer-index mapping that is the documented correctness risk.

4. **Extract `RallyDataController`**: Move `loadTickets`, `loadProjects`, `loadIterations`, `buildQuery`, `applyClientFilter`, `getClient`, `invalidateIterations`, all @Volatile cache fields, and the `iterationLoadGeneration`/`iterationCommitLock` concurrency machinery into a non-Swing class that emits data via callbacks or a simple listener interface. This makes the loading/concurrency logic independently testable. The panel becomes a listener.

5. **Extract `RallyActions`**: Move `changeState`, `startWorking`, `finishWorking`, `editPoints`, `exportSelectedArtifact`, `openInBrowser`, `copyFormattedId` into a class that receives a `RallyApiClient` supplier, a selection supplier, and a status callback. Again, independently testable.

The already-extracted `RallySprintSummary.kt` (108 lines) is the right model for item size. Note that `RallyDetailPanel.kt` is 1297 lines and may itself warrant a similar review — avoid migrating complexity into it.

**Verifier.** The file is exactly 2143 lines. The main class `RallyToolWindowPanel` spans lines 48–2129 (2081 lines). The finding accurately describes what is in the file:

1. **Size and section banners are real**: The code has verbatim section banners at lines 197 (`// ── UI Setup`), 442 (`// ── Context Menu`), 467 (`// ── Data Loading`), 900 (`// ── Search Filter`), 1050 (`// ── Actions`), 2036 (`// ── Client`), 2063 (`// ── Cell Renderer`).

2. **Multiple unrelated responsibilities are real**: The class owns all of: full Swing layout assembly (`setupUI`, `setupListeners`), data-loading orchestration with generation-based concurrency (`loadTickets`, `loadProjects`, `loadIterations`), project/iteration cache management (7 @Volatile cache flags), all artifact actions (`changeState`, `startWorking`, `finishWorking`, `editPoints`, `exportSelectedArtifact`, `openInBrowser`, `copyFormattedId`), two full create dialogs as inner classes (`CreateDefectDialog` at line 1158, `CreateUserStoryDialog` at line 1412), search filtering (`applySearchFilter`), sprint-summary wiring, client lifecycle (`getClient`, `dispose`), `ArtifactCellRenderer` (private inner class), and `ThinDividerSplitPaneUI` (top-level private class at line 2134).

3. **Field count**: 13 @Volatile fields (not 14 as claimed — minor overcount), plus ~20 more non-volatile fields, totaling ~33 fields mixing UI widgets, cache state, and concurrency primitives. The "~30 fields including 14 @Volatile" in the finding is close (13 @Volatile is the actual count).

4. **The shared `cachedIterations` coupling is real**: `CreateDefectDialog.init` reads `cachedIterations` from the outer class at lines 1183–1189 and `CreateUserStoryDialog.init` reads it at 1434–1440. Both dialogs use index arithmetic against that same list in the create action (`iterationsSnapshot[selectedIterationIndex - 1]` at lines 1101, 1355). This is the coupling hazard the review identified.

5. **Already-extracted `RallySprintSummary.kt` (108 lines) and `RallyDetailPanel.kt` (1297 lines) do exist**, confirming that extraction is the established project pattern, and that `RallyDetailPanel` itself is not small — suggesting the panel was extracted but the main class was not yet cleaned up afterward.

The severity claim of "high" is appropriate for a plugin codebase: the class is not unit-testable at all (every function has Swing widget or live-client calls), the `cachedIterations` shared state between outer panel and inner dialogs is a real correctness coupling (a project-switch in-flight can stomp the list the dialog indexed), and the 2081-line size substantially increases the risk of unintended cross-concern side effects in normal maintenance. This is not merely cosmetic.

The recommendation to extract a `RallyDataController` and `RallyActions` collaborator and move the dialogs to their own files is sound and matches the project's own pattern (`RallySprintSummary.kt`). One refinement: `RallyDetailPanel.kt` at 1297 lines is itself large, so the extracted `RallyDataController` should be mindful not to become another god object. The `ArtifactCellRenderer` can be its own file immediately at trivial cost (it has no back-references to the outer class other than `RallyColors`).

**Evidence.** Class spans lines 48-2129 (2081 lines in one class) with section banners '── UI Setup ──', '── Data Loading ──', '── Actions ──', '── Cell Renderer ──' delimiting concerns that should be separate types.

---

### 🟠 Medium (14)

#### MED-1 · Multi-artifact export saturates the same 4-thread apiExecutor it runs on, serializing the slow path and risking starvation
`API client performance & caching` · category: performance · effort: M · conf: 0.75 · verdict: needs-nuance · orig=high  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** 1593-1630 (export loop); RallyExporter.kt downloadInlineImages 600-614, attachmentsToJsonArray 497-513  

**Problem.** The export submits one CompletableFuture.runAsync per selected artifact to client.apiExecutor (a fixed 4-thread pool). Each of those tasks then does ALL of its own network work inline on that worker: getArtifactByFormattedId (x2 via JSON+MD), queryTestCases, queryAttachments, and the deliberately-sequential downloadInlineImages()/downloadAttachmentContent() loops (the exporter comments explicitly refuse to resubmit those to apiExecutor to avoid self-deadlock). So with 4 workers, only 4 artifacts' worth of image/attachment downloads ever proceed at once, and every download within an artifact is serial. The heaviest, most parallelizable part of export (downloading many attachments/inline images) is throttled to 4-wide at the artifact granularity with no per-download concurrency.

**Impact.** Exporting a handful of image-heavy artifacts is far slower than the network allows: attachments and inline images download strictly serially within each artifact, and only 4 artifacts run concurrently. Combined with the sequential downloads, a 10-artifact export with 5 images each issues ~50 fully serialized GETs across at most 4 lanes. It also means if a user selects >=4 artifacts whose tasks each block on a queryTestCases/queryAttachments call that internally needs another apiExecutor slot, the pool can stall.

**Recommendation.** The throughput bottleneck is real but limited to the export cold path. The self-deadlock guard in the exporter comments is accurate and correctly prevents the worst failure mode. To improve export speed without risking deadlock: introduce a dedicated export download executor (e.g. `Executors.newFixedThreadPool(8)`, created and shut down per export invocation, or as a companion object) that is separate from `apiExecutor`. Pass it into `downloadInlineImages()` and `attachmentsToJsonArray()` so each method can submit per-image/attachment download tasks to the dedicated pool and collect them with `CompletableFuture.allOf(...).join()`. This mirrors the `prefetchDescriptions` Semaphore(10) pattern but uses a separate pool instead of a semaphore so there is no contention with the apiExecutor at all. Keep the outer per-artifact fan-out on `apiExecutor`. The MAX_INLINE_IMAGES_PER_DESCRIPTION=50 cap already bounds worst-case download count. Do not use `ForkJoinPool.commonPool()` as the download executor — that pool is shared across the JVM and can interact badly with other IntelliJ operations. The dedup caches (`downloadedPaths`, `downloadedImagePaths`) already ensure the JSON and Markdown passes do not re-download the same content, so the optimization primarily benefits the first pass per artifact. Given the export path is user-triggered and infrequent, prioritize correctness and shutdown safety for the dedicated executor (use try/finally to call `shutdownNow()` after `allOf().join()`).

**Verifier.** The core throughput claim is confirmed by the code. `apiExecutor` is a fixed 4-thread pool (RallyApiClient.kt:42). The export loop (panel:1594-1626) submits one `CompletableFuture.runAsync` per selected artifact to `client.apiExecutor`. Inside each task, `exportArtifactJson` and `exportArtifactMarkdown` run synchronously — including `downloadInlineImages()` (exporter:609 `for (job in jobs) { val localPath = downloadRallyImage(...) }`) and `attachmentsToJsonArray()` (exporter:500 `for (att in attachments) { val savedPath = downloadAttachmentContent(...) }`), both explicitly sequential. The comments at exporter:600-606 confirm this is intentional to avoid submitting back to `client.apiExecutor` from within an apiExecutor task. So at most 4 artifacts export concurrently, and within each artifact all image/attachment downloads are fully serial. The "4-wide at artifact granularity with no per-download concurrency" description is accurate.

However, the finding overstates the "stall / starvation" risk. The current code does NOT submit inner tasks to apiExecutor and block on them — it calls all network operations inline/synchronously on the worker thread. This means there is no true deadlock or task-starvation in the Java thread-pool sense; the threads stay busy doing sequential HTTP GETs rather than parking waiting for pool slots that never free. The CLAUDE.md performance table documents "Reduced thread pool: API executor reduced from 8 to 4 threads — prevents thread saturation while maintaining parallelism." The concern about >=4 artifacts whose tasks "need another apiExecutor slot" causing a stall is not accurate for the current code path — `queryTestCases` is a direct HTTP call, not submitted as a new apiExecutor task (that was the old parallel path; the comment at api:818-819 explains user stories+defects were changed to sequential for exactly this reason). So the stall/deadlock framing is wrong for this code.

The real issue is purely a throughput one: on a multi-MB artifact with many images, 1 artifact occupies 1 thread for the entire serial download sequence (up to MAX_INLINE_IMAGES_PER_DESCRIPTION=50 images), and at most 4 such sequences run in parallel. For a small number of selected artifacts with few images this is not meaningfully slow; for 10+ artifacts each with many images it is suboptimal. Severity is medium, not high — the export path is infrequent (user-triggered, not in the hot path), the dedup cache (`downloadedPaths`, `downloadedImagePaths`) already eliminates redundant downloads for JSON+MD passes, and the HttpClient does its own connection multiplexing (HTTP/2, per the CLAUDE.md table). The recommendation to use a separate executor for downloads is valid but the claimed impact ("far slower than the network allows", comparing to a deadlock/starvation risk) is overstated for the typical case.

**Evidence.** RallyExporter.kt:600 "This MUST NOT submit to client.apiExecutor ... self-deadlocks once the selection count reaches the pool size"; loop at RallyExporter.kt:609 `for (job in jobs) { val localPath = downloadRallyImage(...) }`; panel 1626 `}, client.apiExecutor)`

---

#### MED-2 · queryAllArtifacts fetches user stories and defects in two serial round trips on every list load
`API client performance & caching` · category: performance · effort: M · conf: 0.90 · verdict: needs-nuance · orig=high  
**File:** `…/api/RallyApiClient.kt`  
**Location:** 806-856 (queryAllArtifacts), called from RallyToolWindowPanel.kt:541-547  

**Problem.** queryAllArtifacts runs queryUserStories() then queryDefects() sequentially (lines 821-835). CLAUDE.md documents this as deliberate to avoid apiExecutor self-deadlock, but the deadlock only exists because the caller submits queryAllArtifacts itself to apiExecutor (panel line 541-547) and then BLOCKS an apiExecutor thread via artifactsFuture.get() (line 555). The two queries each pay a full Rally round trip (often 200-2000 items, gzip-decoded, full Gson reflection), so the list load latency is stories_RTT + defects_RTT instead of max(stories_RTT, defects_RTT).

**Impact.** Every cold list load and every project/scope/sprint switch pays roughly 2x the network latency it needs to. On a typical 150-300ms Rally RTT this is a visible 150-300ms added to the most frequent user action (loading/refreshing the ticket list).

**Recommendation.** The fix should happen at the call site in RallyToolWindowPanel, not inside `queryAllArtifacts`. The outer lambda (line 490) already runs on IntelliJ's unbounded `executeOnPooledThread` pool. That outer thread can safely run one query inline while submitting the other to `apiExecutor`:

```kotlin
val artifactsFuture = java.util.concurrent.CompletableFuture.supplyAsync({
    // Run defects on apiExecutor (1 slot consumed), stories inline on this unbounded thread
    val defectsFuture = CompletableFuture.supplyAsync({ client.queryDefects(...) }, client.apiExecutor)
    val stories = client.queryUserStories(...)
    stories + defectsFuture.get()   // outer unbounded thread blocks, not apiExecutor thread
}, /* no executor — runs on current unbounded pool thread */)
```

Or more cleanly, move the two queries directly onto the outer `executeOnPooledThread` body without the wrapping `supplyAsync`, running one inline and one on `apiExecutor`. Either way, `queryAllArtifacts` should retain its sequential path as a safe default for other callers. The LRU cache with 2-minute TTL limits the impact to cold loads and post-TTL refreshes only, so this is medium priority — worth a targeted fix but not urgent. CLAUDE.md already flags it explicitly ("Parallel list ... changed from parallel to prevent thread-pool deadlock") so this is acknowledged debt, not a discovery.

**Verifier.** The finding is accurate in its description of what the code does. `queryAllArtifacts` (lines 821-835) does run `queryUserStories` then `queryDefects` sequentially, and the comment at line 818 explicitly says this is to avoid apiExecutor self-deadlock. The call site in RallyToolWindowPanel (line 541-547) submits `queryAllArtifacts` to `client.apiExecutor` via `supplyAsync`, which means the sequential execution happens on one of the 4 apiExecutor threads. The deadlock concern is genuine: if `queryAllArtifacts` were to submit sub-tasks to the same fixed-4-thread apiExecutor and block on `.get()`, with all 4 threads occupied by outer callers doing the same, no worker would be free to run the sub-tasks.

However, the finding's framing needs nuance:

1. The deadlock risk is real and correctly motivated. The code comment, the CLAUDE.md Performance table ("changed from parallel to prevent thread-pool deadlock"), and the architecture all agree this is a deliberate safety choice, not an oversight.

2. Line 555 `artifactsFuture.get()` blocks the OUTER executeOnPooledThread thread (IntelliJ's unbounded application pool), NOT an apiExecutor thread. The finding claims the caller "BLOCKS an apiExecutor thread via artifactsFuture.get()" — that is technically wrong. The `.get()` is called from the outer `executeOnPooledThread` lambda, not from inside apiExecutor.

3. The performance cost is real: two serial Rally round trips instead of parallel. On 150-300ms Rally RTT the gap is 150-300ms per list load. However, the LRU cache with 2-minute TTL means the second and subsequent loads within that window pay zero RTTs for both queries. The latency hit is bounded to cold loads and post-TTL refreshes only.

4. The recommended fix (run both queries from the outer unbounded pool thread, bypassing apiExecutor for the fan-out) is architecturally valid. The outer `executeOnPooledThread` already occupies an unbounded-pool thread; one query could run inline on that thread and the other via a fresh `supplyAsync(apiExecutor)`, joining both at the end. This would recover parallelism without deadlock risk, since only one apiExecutor slot would be consumed (for the second query) rather than having the outer apiExecutor slot blocking on inner apiExecutor slots.

The severity should be medium rather than high: the LRU cache masks this for repeated loads, the path is only cold on first load and project/sprint switches (not "every list load"), and CLAUDE.md already flags it as a known regression to revisit — making it a documented quality debt rather than an undetected bug.

**Evidence.** RallyApiClient.kt:818 "Query user stories and defects sequentially to avoid apiExecutor self-deadlock"; panel:555 `val artifacts = artifactsFuture.get()` blocks an apiExecutor thread

---

#### MED-3 · Export re-queries each artifact by FormattedID twice even though the UI already passes the full artifact object
`API client performance & caching` · category: performance · effort: M · conf: 0.95 · verdict: needs-nuance  
**File:** `…/export/RallyExporter.kt`  
**Location:** exportArtifactJson 369, exportArtifactMarkdown 417; panel passes `selected` artifacts at RallyToolWindowPanel.kt:1594-1600  

**Problem.** The panel iterates over `selected` (fully-populated RallyArtifact objects already in memory) but calls exporter.exportArtifactJson(id, ...) and exporter.exportArtifactMarkdown(id, ...) by FormattedID only. Both methods then call client.getArtifactByFormattedId(artifactId) (lines 369 and 417) — issuing a Rally `(FormattedID = "...")` search query — to recover the artifact the panel already had, purely to obtain the Description field (which LIST_FIELDS omits). The first call populates the artifact: cache so the second is a cache hit, but the first is still a full extra round trip per artifact that could be a direct `_ref?fetch=Description` (or avoided entirely by passing the artifact object + a single fetchDescription).

**Impact.** One unnecessary search round trip per exported artifact on the export critical path. For a 20-artifact export that is 20 avoidable FormattedID lookups (a search query is heavier than a direct ref GET). Adds latency to every export and consumes apiExecutor slots that compound finding export-nested-apiexecutor-saturation.

**Recommendation.** The fix has two parts:

1. Add overloads `exportArtifactJson(artifact: RallyArtifact, outputDir: String)` and `exportArtifactMarkdown(artifact: RallyArtifact, outputDir: String)` that accept the already-in-memory artifact object. Inside those overloads, replace the `getArtifactByFormattedId` call with `client.fetchDescription(artifact.ref ?: "")` to obtain Description via a cheaper direct ref GET (`$ref?fetch=Description`) rather than a search query.

2. Update the panel call sites (lines 1599-1600) to pass the artifact object directly instead of extracting only the ID.

Keep the existing `exportArtifactJson(artifactId: String, ...)` / `exportArtifactMarkdown(artifactId: String, ...)` string-ID overloads for the test-case export entry points (`exportTestCaseJson`/`exportTestCaseMarkdown`) that genuinely start from an ID with no artifact object in hand.

This eliminates one `(FormattedID = "...")` search query per exported artifact. The Markdown call's `getArtifactByFormattedId` is already a cache hit (JSON call warms it), so only the JSON call's network round trip is eliminated in the current code. The saving is one search query per artifact on the export critical path — 20 queries for a 20-artifact export — replaced with at most one direct ref GET per artifact (and that too can be skipped if `fetchDescription` is already cached from the detail panel view).

**Verifier.** The core finding is real but the claim of "two round trips" is overstated — it is one round trip per artifact, not two.

Confirmed facts:
1. The panel iterates `selected` (full RallyArtifact objects from the list query) and extracts only `formattedID` and `ref`. It passes only `id: String` to `exporter.exportArtifactJson(id, outputDir)` and `exporter.exportArtifactMarkdown(id, outputDir)`.
2. `exportArtifactJson` (line 369) calls `client.getArtifactByFormattedId(artifactId)`, which issues a `(FormattedID = "...") ` search query with `DETAIL_FIELDS` (including Description) and caches the result under `artifact:$formattedId|$ws|$pr`.
3. `exportArtifactMarkdown` (line 417) calls the same method, but since JSON export already populated the cache, the Markdown call is a cache hit — no second network round trip.
4. The list query uses a different cache key prefix (`artifacts:` vs `artifact:`), so the artifact objects already in memory do NOT pre-warm the per-FormattedID cache. The first call per artifact always hits the network.

What the reviewer got wrong: they claimed "the first call populates the artifact: cache so the second is a cache hit" — this is correct — but they still described it as "20 avoidable FormattedID lookups" implying 2x duplication. The actual overhead is 1 search query per artifact (not 2): JSON triggers one network call, Markdown reuses the cache.

The underlying issue is still real: the panel already has the full artifact object in memory (fetched by the list query). Passing only the ID discards that object, forcing a redundant `(FormattedID = "...")` search query (heavier than a direct ref GET) to recover data the caller already holds. For a 20-artifact export this is 20 avoidable search queries on the critical path.

The `fetchDescription(ref)` alternative (`$ref?fetch=Description`) is a cheaper direct ref GET versus a search query and would be sufficient since Description is the only field missing from the list result. All other fields (name, type, state, etc.) are already present on the artifact object the panel holds.

**Evidence.** RallyExporter.kt:369 `val artifact = client.getArtifactByFormattedId(artifactId)`; RallyExporter.kt:417 same; panel:1594 `selected.mapNotNull { artifact -> val id = artifact.formattedID`

---

#### MED-4 · No service/repository layer — HTTP client constructed inside the tool window, which doubles as the app's state store
`Architecture & strategic improvement opportunities` · category: architecture · effort: L · conf: 0.95 · verdict: needs-nuance · orig=high  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** getClient() lines 2038-2061; currentClient/cachedProjects/cachedIterations/allArtifacts fields lines 102-115  

**Problem.** RallyApiClient is instantiated directly inside the tool window panel (getClient() at 2038), and the panel owns all of the cross-cutting state: currentClient, cachedProjects, cachedIterations, allArtifacts, projectsLoaded/iterationsLoaded flags, the iterationLoadGeneration counter, and lastSettingsSnapshot. The client itself owns the LRU cache, image cache and the apiExecutor pool. There is no application/project-level @Service that owns 'the connection to Rally'. plugin.xml registers only RallySettings as an applicationService — the client and all derived state are bound to the lifetime of one Swing panel. The data class state in RallySettings (selectedProject/selectedIteration) is being used as the canonical selection store, read from background threads (e.g. getSelectedProjectRef() line 833, loadTickets line 506).

**Impact.** Everything that wants Rally data must go through a Swing component, so the client, its caches and its 4-thread pool cannot be shared, unit-tested in isolation, reused by a future second view (e.g. an editor gutter or a status-bar widget), or kept warm across tool-window close/reopen. Closing the tool window discards the entire warm cache and tears down the pool (dispose() line 2024), so the next open pays a full cold-load. The tight coupling is also why the panel needs ~10 @Volatile coordination fields and a clientLock — that complexity is a symptom of business state living in the UI.

**Recommendation.** The structural observation is correct and worth tracking as a future improvement, but it is not an urgent refactor. If a second Rally data consumer is ever added (gutter marker, status-bar widget, editor inlay), introduce a project-level RallyService registered as a &lt;projectService&gt; in plugin.xml that owns the RallyApiClient lifecycle, caches, and the projectsLoaded/iterationsLoaded/generation state. The tool window panel would then call RallyService.getInstance(project).getClient() instead of constructing its own. Until a second consumer exists, the marginal benefit (avoiding a cold reload on tool-window reopen) does not justify the refactor cost. If pursued: (1) move getClient()/matchesSettings rebuild logic into the service, (2) move cachedProjects/cachedIterations/projectsLoaded/iterationsLoaded/iterationLoadGeneration/lastSettingsSnapshot into the service, (3) keep the Swing-layer fields (allArtifacts, displayedArtifacts, sprintIteration) in the panel where they belong. The clientLock and disposed guard remain necessary but can move to the service, substantially simplifying the panel.

**Verifier.** The factual description is accurate: RallyApiClient is instantiated inside getClient() (line 2051) within RallyToolWindowPanel, plugin.xml registers only RallySettings as an applicationService (lines 48-49), and all the cited @Volatile fields (allArtifacts, currentClient, cachedProjects, cachedIterations, projectsLoaded, iterationsLoaded, lastSettingsSnapshot, disposed) exist at lines 102-151. dispose() at line 2024 shuts down apiExecutor and nulls the client, so the warm cache and 4-thread pool are torn down on tool-window close. The structural coupling described is real.

However, the severity rating of "high" overstates the actual impact for this specific plugin. CLAUDE.md explicitly documents every aspect of this design under "Disposal safety", "Threading", and "Performance Optimizations" — the design is intentional, not accidental. Critically: this is a single-view, single-user developer tool. There is no second consumer of the client today (gutter widget, status-bar, editor marker) and none is on the roadmap. The "cannot be shared or reused by a future second view" impact is hypothetical. The cold-reload-on-reopen cost is real but minor: the LRU cache holds at most 200 entries, and developers rarely close tool windows during a session. The complexity (clientLock, 10 @Volatile fields, generation counter) is genuine but is a maintainability concern rather than a correctness or performance defect users will hit in normal use. No crash, no data loss, no observable freeze is caused by this pattern. This is correctly categorized as an architecture/maintainability issue at medium severity.

**Evidence.** RallyToolWindowPanel.kt:2051 `currentClient = RallyApiClient(serverUrl, apiKey)`; plugin.xml registers only `<applicationService serviceImplementation="...RallySettings"/>` — no service wraps RallyApiClient.

---

#### MED-5 · Two god-files (2143 + 1297 lines) mix layout, threading, business logic, dialogs and renderers
`Architecture & strategic improvement opportunities` · category: architecture · effort: L · conf: 0.97 · verdict: confirmed · orig=high  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** Whole file (2143 lines): inner classes CreateDefectDialog ~1158-1306, CreateUserStoryDialog ~1412-1546, ArtifactCellRenderer ~2065-2128; RallyDetailPanel.kt whole file (1297 lines) with 4 inner renderers + CreateTaskDialog  

**Problem.** RallyToolWindowPanel holds UI construction (setupUI), event wiring (setupListeners), filter/query construction (buildQuery, applyClientFilter), the client factory (getClient), optimistic-update business logic (withState, patchArtifactsInModel, changeState, startWorking, finishWorking, editPoints), export orchestration (exportSelectedArtifact), two large DialogWrapper inner classes that duplicate ~140 lines of GridBag form code each, and a cell renderer — all in one class. RallyDetailPanel similarly carries description rendering, image resolution, four cell renderers, attachment download, and a create-task dialog. The two Create*Dialog inner classes are nearly identical (compare lines 1158-1306 vs 1412-1546).

**Impact.** High cost to change anything: a reviewer must hold 2000+ lines in context, the duplicated dialog form code drifts (the doValidate attachment-size block is copy-pasted verbatim in both dialogs), and the renderers/dialogs cannot be unit-tested without the whole panel. This is the #1 drag on quality/velocity even though no single line is buggy.

**Recommendation.** The finding is correct. Severity should be medium rather than high: this is a maintainability drag, not a correctness or runtime-quality issue — no crashes, no data loss, no performance problem.

Concrete steps in priority order:

1. Extract an AbstractCreateArtifactDialog(project, title) base class with the shared fields (nameField, projectCombo, iterationCombo, assignToMeCheckbox, descriptionArea, attachmentPathField, attachmentFile), the shared init block (populate combos, pre-select from toolbar), the shared createCenterPanel skeleton (GridBag rows 0–attachment), and the shared doValidate. CreateDefectDialog subclass adds only severityCombo and priorityCombo rows. This eliminates ~130 duplicated lines and means the attachment-size validation lives in exactly one place.

2. Move the four cell renderers out of RallyDetailPanel (TestCaseCellRenderer, TaskCellRenderer, StepCellRenderer, AttachmentCellRenderer) and ArtifactCellRenderer out of RallyToolWindowPanel into their own top-level files or a renderers/ package. They have no need for outer-class access and become independently unit-testable.

3. Extract a RallyPanelActions class (or similar) that takes the client factory and model reference, and holds changeState, startWorking, finishWorking, editPoints, exportSelectedArtifact. This decouples action logic from Swing hierarchy and lets it be tested without constructing the panel.

Step 1 is the highest-value change because the duplicated doValidate is the live drift risk: if the attachment-size logic ever changes (e.g., a new MAX_UPLOAD_BYTES tier), it must be updated in two places. Steps 2 and 3 are pure quality wins with no urgency.

**Verifier.** All factual claims in the finding check out against the actual code:

1. Line counts: RallyToolWindowPanel.kt is exactly 2143 lines; RallyDetailPanel.kt is exactly 1297 lines.

2. Class locations: CreateDefectDialog starts at line 1158, CreateUserStoryDialog at line 1412, ArtifactCellRenderer at line 2065 — all matching the cited ranges.

3. RallyDetailPanel inner classes: CreateTaskDialog at line 844, plus four cell renderers at lines 1112 (TestCaseCellRenderer), 1155 (TaskCellRenderer), 1204 (StepCellRenderer), 1260 (AttachmentCellRenderer).

4. Byte-for-byte identical doValidate methods: A diff of lines 1284–1303 (CreateDefectDialog.doValidate) vs lines 1524–1543 (CreateUserStoryDialog.doValidate) produces zero output — they are literally identical down to the comment text.

5. Structural breadth in RallyToolWindowPanel: setupUI (line 199), setupListeners (line 342), buildQuery (line 782), applyClientFilter (line 806), patchArtifactsInModel (line 1003), withState (line 1027), editPoints (line 1666), changeState (line 1729), startWorking (line 1815), finishWorking (line 1977), getClient (line 2038) — business logic, query building, threading, optimistic updates, client factory, two large dialog inner classes, and a cell renderer are all in one class.

6. createCenterPanel duplication: The diff shows the two dialogs share ~65 of their ~80 panel-building lines verbatim; the only differences are the two Defect-specific rows (Severity, Priority), the row/gridy numbering that follows from them, and the preferredSize height (440 vs 380).

One minor overstatement in the finding: the duplicated block is described as "~140 lines" of GridBag form code across the two dialogs. Measured from the diff, the shared form-building code is closer to ~65 lines per dialog (130 shared total), and the doValidate is an additional 20 identical lines each — so the characterization is roughly correct in magnitude but not precise.

**Evidence.** RallyToolWindowPanel.kt is 2143 lines; CreateDefectDialog.doValidate (1284-1303) and CreateUserStoryDialog.doValidate (1524-1543) are byte-for-byte identical attachment-size checks.

---

#### MED-6 · Hand-rolled CompletableFuture + 3 uncoordinated thread pools + generation counters instead of IntelliJ coroutines/structured concurrency
`Architecture & strategic improvement opportunities` · category: performance · effort: L · conf: 0.90 · verdict: needs-nuance · orig=high  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** showArtifact lines 298-481, resolveInlineImages 1040-1106; RallyToolWindowPanel loadTickets 490-621; thread pools: RallyApiClient.apiExecutor (42-44), RallyDetailPanel.imageExecutor (45-47), plus app executeOnPooledThread  

**Problem.** Concurrency is built from executeOnPooledThread + CompletableFuture.supplyAsync chains, manual .get()/.join() blocking, three independent fixed pools (apiExecutor=4, imageExecutor=4, the app pool), and AtomicLong 'generation' counters in both the detail panel and the tool window to discard stale results. Cancellation is cooperative-by-polling (every supplyAsync starts with `if (generation.get() != gen) return`). The code itself documents the fragility: queryAllArtifacts was changed from parallel to sequential 'to avoid apiExecutor self-deadlock' (line 818), MAX_RETRY_SLEEP_MS exists because 'the API executor has only 4 worker threads' (line 374), and the description pipeline was moved off apiExecutor onto the unbounded app pool to avoid parking workers on image joins (loadAndRenderDescription KDoc 483-490).

**Impact.** These are all symptoms of not having structured concurrency: a bounded pool that blocks on its own sub-tasks deadlocks, so the code must hand-tune which pool runs what and add caps to avoid starvation. Real cancellation (user switches ticket / closes tool window) only takes effect at the next generation poll — in-flight HTTP calls run to completion, wasting the very 4 worker threads that are the bottleneck. The generation bookkeeping is error-prone (it already needed the iterationCommitLock fix described at lines 136-138).

**Recommendation.** The code is correct as-is — the self-deadlock is already avoided (sequential fetch), and generation counters prevent stale UI updates. The issue is maintainability: three pools with manually tuned sizes require ongoing prose documentation explaining why each constraint exists, and TOCTOU discipline around generation checks has already bitten once (iterationCommitLock was added as a fix).

Coroutines are actually available without raising sinceBuild — the IntelliJ platform bundles kotlinx-coroutines since 233 (2023.3), well below the current 241 floor. Migrating to a Disposable-scoped CoroutineScope on `Dispatchers.IO` would collapse all three pools to one, replace generation counters with structured cancellation (job.cancel() on the previous selection's Job), and make the deadlock constraint disappear naturally (coroutines suspend rather than park threads, so a coroutine calling another coroutine on the same dispatcher cannot deadlock).

Concrete migration path: (1) add `coroutineScope` via `com.intellij.openapi.components.Service` or `Disposer`-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, (2) replace each `executeOnPooledThread { CompletableFuture.supplyAsync(..., apiExecutor) }` block with `scope.launch(Dispatchers.IO) { ... }`, (3) use `withContext(Dispatchers.EDT)` instead of `invokeLater`, (4) store the Job returned by each `showArtifact` launch and call `currentJob?.cancel()` at the top of showArtifact — eliminating all AtomicLong generation counters. The imageExecutor and apiExecutor become unnecessary; Dispatchers.IO's shared pool handles both. No sinceBuild change required.

**Verifier.** The finding is factually accurate in describing what the code does — three independent thread pools (apiExecutor=4, imageExecutor=4, app pool), AtomicLong generation counters in both RallyDetailPanel (line 140) and RallyToolWindowPanel (line 134), CompletableFuture.supplyAsync chains, and generation-check-by-polling cancellation. The self-deadlock comment at RallyApiClient.kt:818-820 and the MAX_RETRY_SLEEP_MS concern at lines 373-388 are genuine, correctly identifying that a bounded pool blocking on its own sub-tasks is fragile. The loadAndRenderDescription KDoc at lines 483-490 of RallyDetailPanel is real and explains why the description pipeline was moved off apiExecutor.

However, the severity claim is too strong, and the recommendation's key prerequisite (IntelliJ coroutine APIs) has a significant obstacle that the finding papers over.

Why "high" severity is overstated:

1. The design is a deliberately engineered workaround: queryAllArtifacts was converted FROM parallel to sequential specifically to eliminate the self-deadlock (line 818), so the deadlock class of bug is already not present — the code just has an awkward shape that reveals the constraint. The in-flight HTTP calls continuing to completion is annoying (wastes the 4 threads briefly after a ticket switch) but does NOT block the UI and does NOT produce incorrect results thanks to the generation checks. This is a maintainability/architecture concern, not a crash or data-loss problem users will regularly hit.

2. The `iterationCommitLock` fix cited as "already needed" proves the generation approach is susceptible to TOCTOU races, but the fix is already in place in the code (line 138), so this is a past bug that was addressed, not an open one.

3. Real user-visible symptoms are subtle: rapid ticket switching wastes some HTTP bandwidth (in-flight requests for the old ticket finish before being discarded). With HTTP/2 multiplexing and a local LRU cache (200 entries, 2-min TTL), repeated visits cost nothing. Four threads stalling on a rare 429 Retry-After backoff is a realistic but low-frequency event.

Why the coroutine recommendation needs nuance:

The build.gradle.kts shows sinceBuild=241 (IntelliJ IDEA 2024.1) and no `kotlinx-coroutines-core` dependency. The IntelliJ platform has bundled coroutines since 2023.3 (233), so coroutines ARE available on the 241 floor without adding a separate dependency — the reviewer's caveat "requires raising sinceBuild" is incorrect. However, migrating requires switching to `kotlinx.coroutines.intellij` / `com.intellij.openapi.application.coroutineScope`, which ARE present in 241+. This actually makes coroutines MORE accessible than the finding states.

The actual problem is architectural complexity and maintainability: three pools with manually tuned sizes, generation counters that require careful TOCTOU discipline (as the iterationCommitLock demonstrates), and prose documentation explaining which pool runs what and why. This is a real medium-severity maintainability concern, not a high-severity correctness or performance problem.

The actualCode below is the core evidence: the self-deadlock comment at line 818-820 and the MAX_RETRY_SLEEP_MS KDoc at lines 373-388 confirm the constraint is real; the generation counter declarations confirm the manual cancellation model; and the dispose() comment (lines 146-151) reveals an additional subtlety — even imageExecutor.shutdownNow() was unsafe because it would strand CompletableFuture joins.

**Evidence.** RallyApiClient.kt:818 comment `Query user stories and defects sequentially to avoid apiExecutor self-deadlock`; RallyDetailPanel.kt:483-490 KDoc explaining description work was moved off apiExecutor to avoid parking shared workers.

---

#### MED-7 · enterBulkMode/exitBulkMode mutate global client state, not export-scoped
`Concurrency correctness & races` · category: concurrency · effort: S · conf: 0.92 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** lines 182-192 (bulkModeTtlMs, enterBulkMode, exitBulkMode); consumed in getCached line 198; callers RallyToolWindowPanel.exportSelectedArtifact lines 1585/1652  

**Problem.** `bulkModeTtlMs` is a single @Volatile field on the shared RallyApiClient that getCached reads to pick the TTL. enterBulkMode()/exitBulkMode() set/clear it globally. The same client instance is shared by the tool window list loads, detail-panel loads, AND exports. If an export runs while the user keeps browsing (or two export actions overlap — Export toolbar button plus the detail panel's exportSelectedTestCases both build their own RallyExporter on the same client), the first exitBulkMode()'s `bulkModeTtlMs = null` flips the extended TTL off for the still-running second operation, and any concurrent list/detail caching silently gets the 15-minute TTL during the window.

**Impact.** Overlapping exports lose the extended-TTL guarantee mid-run (re-fetching artifacts the design intended to keep cached, slowing the export), and normal browsing during an export caches data with a 15-min TTL that then serves stale tickets long after the export finishes. It is a check-then-act on shared mutable state with no nesting/refcount.

**Recommendation.** Replace `bulkModeTtlMs: Long?` with an `AtomicInteger bulkModeDepth` and make `enterBulkMode` increment it and `exitBulkMode` decrement it (guarded to floor zero). In `getCached`, use the extended TTL when `bulkModeDepth.get() > 0`. This makes the flag reentrant and safe under concurrent exports. For example: `private val bulkModeDepth = AtomicInteger(0)` / `fun enterBulkMode() { bulkModeDepth.incrementAndGet() }` / `fun exitBulkMode() { bulkModeDepth.decrementAndGet().coerceAtLeast(0) /* or use getAndUpdate */ }` / `val ttl = if (bulkModeDepth.get() > 0) bulkTtlMs else queryTtlMs`. This eliminates the race without requiring callers to pass TTL as a parameter. The bulk TTL value can be a companion-object constant. Also note that `RallyDetailPanel.exportSelectedTestCases` never calls `enterBulkMode` at all, so small test-case exports always run under the 2-minute TTL — that may be intentional, but worth making explicit.

**Verifier.** The code exactly matches the claim. `bulkModeTtlMs` is a `@Volatile private var` on the shared `RallyApiClient` singleton (`currentClient`). `enterBulkMode()` sets it and `exitBulkMode()` nulls it, with no depth counter or synchronization. `getCached()` reads it with `val ttl = bulkModeTtlMs ?: queryTtlMs`. Two callers can race: (a) two clicks of the toolbar Export button / context-menu Export both call `exportSelectedArtifact()`, each on a pooled thread, each calling `client.enterBulkMode()` then eventually `client.exitBulkMode()`; when the first one finishes and calls `exitBulkMode()`, the second is still running but now has its extended TTL silently revoked. Additionally, `RallyExporter.bulkExportJson` and `bulkExportMarkdown` each independently call `client.enterBulkMode()`/`client.exitBulkMode()` on the same client instance (lines 194/251 and 261/320 of RallyExporter.kt), creating additional overlap vectors. The "normal browsing gets 15-min TTL" concern is also real: any `getCached()` call that happens to execute while `bulkModeTtlMs != null` — including background list refreshes and detail-panel loads — will use the extended TTL and return stale data long after the export completes. One nuance from the review: `RallyDetailPanel.exportSelectedTestCases` does NOT call `enterBulkMode`, so it doesn't add to the overlap problem, but it also gets no extended-TTL protection. The core finding (no reentrance guard, global mutable flag, shared instance) is accurate.

**Evidence.** @Volatile private var bulkModeTtlMs: Long? = null ... fun enterBulkMode(...) { bulkModeTtlMs = ... } fun exitBulkMode() { bulkModeTtlMs = null }  // getCached: val ttl = bulkModeTtlMs ?: queryTtlMs

---

#### MED-8 · Partial query failure in queryAllArtifacts/searchArtifacts is throttled to one balloon/minute, so a degraded endpoint silently truncates results
`Error handling, silent failures & API correctness` · category: correctness · effort: M · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** queryAllArtifacts 842-852, notifyPartialFailure 858-882, searchArtifacts 924-938  

**Problem.** When stories succeed but defects fail (or vice versa), the method returns the partial list and calls notifyPartialFailure, which is throttled to once per 60s (line 867). The deliberate design (don't cache the partial result) is sound, but the only signal the user gets is a single balloon that may already have fired for an unrelated query in the last minute, plus a WARN log. The returned list is then rendered with a normal '${filtered.size} loaded' status (RallyToolWindowPanel:570) — no persistent in-UI indication that the list is incomplete.

**Impact.** Correctness/UX: a user with a flaky defect endpoint sees a stories-only list that looks complete (count shown, no error icon) and may make decisions on a truncated dataset. The throttle means a second project switch within the minute produces a partial list with NO balloon at all.

**Recommendation.** The cleanest targeted fix is to have `queryAllArtifacts` and `searchArtifacts` return a thin wrapper instead of a bare list:

```kotlin
data class ArtifactQueryResult(
    val artifacts: List<RallyArtifact>,
    val partialFailureReasons: List<String> = emptyList()
) {
    val isPartial get() = partialFailureReasons.isNotEmpty()
}
```

The panel then checks `result.isPartial` after `.get()` and:
- Sets `statusLabel.text = "${filtered.size} loaded (incomplete)"` with `statusLabel.icon = AllIcons.General.Warning`
- Clears the warning icon on the next successful full load

This preserves the balloon (still useful for the Event Log) but removes the 60-second throttle dependency as the primary UX signal, since the status label is persistent and reset on recovery. Keep the throttle only for the balloon itself to avoid spam.

If a wrapper type is too invasive, a lower-effort alternative is to add a `@Volatile var lastQueryWasPartial: Boolean` flag on the client that the panel reads after `artifactsFuture.get()`, and use it to append a warning suffix and icon to the status label for that render cycle only.

**Verifier.** The actual code confirms all three parts of the finding:

1. `queryAllArtifacts` (line 848–851) catches partial failure, logs a WARN, calls `notifyPartialFailure`, and returns the partial sorted list — not throwing. This means the `CompletableFuture.get()` at panel line 555 succeeds normally, and execution falls straight through to line 570 `statusLabel.text = "${filtered.size} loaded"` with no warning icon or textual indication of incompleteness.

2. `notifyPartialFailure` (line 867) throttles to one balloon per 60 seconds across ALL callers — both `queryAllArtifacts` and `searchArtifacts` share the same `@Volatile lastPartialFailureNotifyMs` field. A user who searches rapidly (debounced per-keystroke) while defects are down will suppress the project-load balloon for the following minute.

3. The panel's catch block (line 598) only fires on exceptions. A partial return does not trigger the `statusLabel.icon = AllIcons.General.Error` path at line 606. There is no code path that sets a warning icon or modifies the status label text when partial failure occurs and the method returns normally.

The finding is accurate on all three cited locations. The comment in `queryAllArtifacts` (lines 842–846) documents the cache-skip decision (correct trade-off) but is silent on the UX gap. The throttle rationale (per-keystroke search spam) is sound for the search path but has the side effect the reviewer identified: a project-switch partial failure within the throttle window is completely silent in the UI.

**Evidence.** Line 867: `if (now - lastPartialFailureNotifyMs < 60_000) return`; RallyToolWindowPanel:570 `statusLabel.text = "${filtered.size} loaded"` runs regardless of whether errors were non-empty.

---

#### MED-9 · Bulk state change clears artifact cache after partial failure but never re-fetches failed rows, leaving stale optimistic state
`Error handling, silent failures & API correctness` · category: correctness · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** changeState 1786-1805  

**Problem.** On a bulk state change, failed refs are NOT in successfulRefs, so they correctly keep their old state in the optimistic patch — good. But the only feedback for failures is a single warning dialog 'Updated: X, Failed: Y' (1788-1792) that does not say WHICH tickets failed or WHY. The failures AtomicInteger counts them but the per-ticket exception (logged at LOG.error 1777) is discarded from the user's view. Combined with clearArtifactCache (1796), the next reload will fetch fresh truth — but until then the user has no way to know which of the N selected tickets didn't move.

**Impact.** Correctness/UX on bulk operations: in a 20-ticket move where 3 fail (e.g. a state-transition validation rejected by Rally because the ticket is Blocked), the user sees 'Updated: 17, Failed: 3' with no IDs. They cannot tell which 3 to revisit. This is a silent-ish failure of partial-success reporting.

**Recommendation.** Add a `ConcurrentLinkedQueue<String>` (e.g. `failedIds`) alongside the existing `failures` AtomicInteger. In the catch block, append `artifact.formattedID ?: ref` to it. In the warning dialog, include the IDs: "Failed to move: ${failedIds.joinToString(", ")}" with a fallback to the count-only string when the list is very long (e.g., truncate after 10 IDs with "… and N more"). The first failure's exception message can optionally be captured for a "Reason: …" suffix. This is a 3-line change with no structural impact.

**Verifier.** The actual code at lines 1776-1792 is exactly as the reviewer described. In the catch block (line 1776-1779), `artifact.formattedID` is logged via LOG.error but not added to any collection — only `failures.incrementAndGet()` is called. The subsequent warning dialog (lines 1788-1792) shows only the count string "Updated: ${results.get()}, Failed: ${failures.get()}" with no per-ticket identifiers. The `artifact` lambda parameter is fully in scope in the catch block (it is captured from the outer `mapNotNull` lambda), so collecting failed IDs would require only adding a `ConcurrentLinkedQueue<String>` and appending `artifact.formattedID` at the catch site. The optimistic-update logic is correct (failed refs are not in `successfulRefs` and are not moved), and `clearArtifactCache()` at line 1796 ensures the next reload gets truth — so there is no data corruption. The UX deficiency is the sole issue: on a partial-failure bulk move, the user cannot identify which tickets need attention without manually diffing their list.

**Evidence.** Line 1776-1779 catch only does `LOG.error(...); failures.incrementAndGet()` — formattedID discarded; line 1788-1792 dialog shows only counts: `"Updated: ${results.get()}, Failed: ${failures.get()}"`.

---

#### MED-10 · CreateUserStoryDialog and CreateDefectDialog are ~95% duplicated (~500 lines)
`Code quality, maintainability & complexity` · category: quality · effort: M · conf: 0.97 · verdict: confirmed · orig=high  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** showCreateDefectDialog 1052-1156, CreateDefectDialog 1158-1306, showCreateUserStoryDialog 1308-1410, CreateUserStoryDialog 1412-1546  

**Problem.** showCreateDefectDialog/CreateDefectDialog and showCreateUserStoryDialog/CreateUserStoryDialog are near-identical. Both dialogs build the same GridBagLayout form (Name, Project, Sprint, Assign-to-me, Attachment + browse, Description), populate the project/iteration combos identically from cachedProjects/cachedIterations, and share an identical doValidate() attachment-size block. Both handler methods repeat the same ~100-line off-EDT flow: re-check attachment size, snapshot caches, resolve project/iteration ref by combo index, resolve owner, create, upload attachment, optimistic prepend + select. The only real differences are the Defect's Severity/Priority combos and which create method is called.

**Impact.** Any change to the create flow (new field, validation tweak, the attachment re-check, the optimistic-update logic) must be made twice and kept in sync by hand — exactly the drift class CLAUDE.md says withState() was introduced to prevent. Roughly 250 of the file's 2143 lines are this duplication, inflating the god object and slowing every create-related change.

**Recommendation.** The duplication is real but the severity should be medium, not high. This is a maintainability issue, not a correctness or performance problem — the duplicated paths currently behave identically so there is no active drift or bug. Refactoring steps in priority order: (1) Extract a `CreateArtifactDialog(project, title, extraFields: List&lt;Pair&lt;String,JComponent&gt;&gt;)` base class or a `buildArtifactForm(extraRows)` factory function that owns the shared Name/Project/Sprint/Assign/Attach/Description layout, the `init` block (combo population + pre-selection), and `doValidate()`. `CreateDefectDialog` passes `[("Severity", severityCombo), ("Priority", priorityCombo)]` as extra rows. (2) Extract the post-OK pooled-thread flow into a single `executeCreate(spec: CreateSpec, createFn: (RallyApiClient, CreateSpec) -&gt; RallyArtifact)` helper — the only thing that varies is the `createFn` lambda and the artifact-type name in error messages. (3) Move both dialog classes out of `RallyToolWindowPanel` into `ui/dialogs/` once they no longer need inner-class access to panel state (pass `cachedProjects`, `cachedIterations`, and `projectCombo.selectedIndex` as constructor args instead). This reduces `RallyToolWindowPanel.kt` by roughly 230 lines and makes future create-flow changes (new field, new validation, new notification style) a single-point edit.

**Verifier.** Reading lines 1052–1546 confirms the duplication is exactly as described. The two `show*` handler methods (lines 1052–1156 and 1308–1410) are structurally identical: same attachment re-check block (lines 1080–1091 vs 1334–1345), same cache-snapshot + projectRef/iterationRef resolution (1094–1105 vs 1348–1359), same assign-to-me / getUserByUsername flow with identical warning string (1107–1117 vs 1361–1371), same upload block, same balloon notification, same optimistic prepend + select — differing only in the `createDefect(...)` vs `createUserStory(...)` call and the extra `severity`/`priority` args. The two inner dialog classes (lines 1158–1306 and 1412–1546) share byte-identical `init` blocks (project combo, iteration combo, checkbox pre-selection), identical `createCenterPanel` scaffolding for the Name/Project/Sprint/Attach/Description rows, identical `doValidate()` (including the identical comment block), and identical `getPreferredFocusedComponent()`. The only real differences are: `CreateDefectDialog` declares two extra combos (`severityCombo`, `priorityCombo`), adds Rows 3–4 for those combos (shifting Assign-to-me and Attachment down two rows), and sets `preferredSize` to 440 vs 380 height. The claimed line count (~500 duplicated lines across ~250 unique + ~250 duplicate) is accurate. The 95% duplication figure is a slight overstatement — the true shared fraction is closer to 85–90% — but the substance is correct and the impact claim (any change to the create flow, validation, or optimistic-update logic must be made twice) is directly supported by the code.

**Evidence.** Lines 1158 `private inner class CreateDefectDialog : DialogWrapper(project)` and 1412 `private inner class CreateUserStoryDialog : DialogWrapper(project)` have byte-identical createCenterPanel form scaffolding and identical doValidate() attachment blocks (1284-1303 vs 1524-1543).

---

#### MED-11 · CreateResult/OperationResult + Errors unwrapping duplicated 7 times in RallyApiClient
`Code quality, maintainability & complexity` · category: quality · effort: S · conf: 0.98 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** updateArtifactState 1140-1146, updateArtifactOwner 1161-1167, updateArtifactField 1183-1189, createUserStory 1532-1541, createDefect 1570-1579, createTask 1606-1615, uploadAttachment 1644-1676  

**Problem.** Three update methods repeat the exact 'parse body -> getAsJsonObject("OperationResult") ?: throw -> getAsJsonArray("Errors") -> if size>0 throw' block; three create methods plus the two-step uploadAttachment repeat the analogous 'getAsJsonObject("CreateResult") ?: throw -> Errors check -> getAsJsonObject("Object") -> gson.fromJson' block. Only the error-message prefix and target class differ.

**Impact.** Eight copies of fragile hand-rolled JSON navigation. A change to Rally's error envelope, or a fix to error reporting, must be applied in all copies; an inconsistency here would silently swallow a Rally error on one path but not another. Pure noise inflating the client.

**Recommendation.** Add two private helpers inside `RallyApiClient`:

```kotlin
private fun parseOperationResult(response: HttpResponse<String>, action: String) {
    val json = JsonParser.parseString(response.body()).asJsonObject
    val result = json.getAsJsonObject("OperationResult")
        ?: throw RallyApiException("Unexpected response: missing OperationResult")
    val errors = result.getAsJsonArray("Errors")
    if (errors != null && errors.size() > 0) throw RallyApiException("Failed to $action: ${errors.joinToString()}")
}

private inline fun <reified T> parseCreateResult(response: HttpResponse<String>, action: String): T {
    val json = JsonParser.parseString(response.body()).asJsonObject
    val result = json.getAsJsonObject("CreateResult")
        ?: throw RallyApiException("Unexpected response: missing CreateResult")
    val errors = result.getAsJsonArray("Errors")
    if (errors != null && errors.size() > 0) throw RallyApiException("Failed to $action: ${errors.joinToString()}")
    val obj = result.getAsJsonObject("Object")
        ?: throw RallyApiException("Unexpected response: missing Object in CreateResult")
    return gson.fromJson(obj, T::class.java)
}
```

The `uploadAttachment` two-step case works naturally with the generic helper — just pass different `action` strings ("create attachment content" vs "create attachment"). Each public method then becomes 1-2 lines. The `Errors` null-check logic becomes a single unit-test target. Severity stays medium: no correctness divergence exists today between copies, but the duplication is non-trivial (8 sites) and the error-reporting inconsistency risk is real.

**Verifier.** The actual code confirms the finding precisely. `handleResponse` only checks HTTP status codes and does not touch the response body JSON. The JSON unwrapping + Errors check is hand-rolled at every call site:

- OperationResult pattern appears 3 times (lines 1141-1146, 1162-1167, 1184-1189): each parses the body, calls `getAsJsonObject("OperationResult") ?: throw`, fetches the Errors array, and throws if non-empty.
- CreateResult pattern appears 5 times (lines 1533-1541, 1571-1579, 1607-1615, 1645-1652, 1668-1676): same structure plus `getAsJsonObject("Object") ?: throw` and `gson.fromJson`. The two instances inside `uploadAttachment` are differentiated only by the label in the exception message ("AttachmentContent" vs "Attachment").

No shared helper (`parseOperationResult`, `parseCreateResult`, etc.) exists anywhere in the file. The `grep` output shows every occurrence. The duplication is real and not a documented trade-off — CLAUDE.md is silent on this pattern. The risk is exactly as described: a fix or change to Rally's error envelope (e.g., Errors becoming a JsonNull instead of an absent key in some API versions) would need to be patched in all 8 places.

**Evidence.** grep shows getAsJsonObject("OperationResult")/getAsJsonObject("CreateResult") + getAsJsonArray("Errors") repeated at lines 1141/1143, 1162/1164, 1184/1186, 1533/1535, 1571/1573, 1607/1609, 1645/1647, 1668/1670.

---

#### MED-12 · scheduleState/state and planEstimate extraction logic scattered across 4 files
`Code quality, maintainability & complexity` · category: quality · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiModels.kt`  
**Location:** RallyArtifact interface 39-51; consumers in RallyToolWindowPanel 818/823/1037-1041/1672-1676/2112-2116, RallyDetailPanel 335-339, RallySprintSummary 38-42/58, RallyExporter 210/283  

**Problem.** The 'effective state' rule (scheduleState ?: state, with TestCase using lastVerdict) is re-implemented at 7+ sites, and the 'plan estimate for stories/defects' type-switch is re-implemented at 4 sites. These are domain concepts of the artifact, not of any one renderer. The duplication is error-prone: the detail panel and list use `?: "Unknown"`, the client filter uses `?: ""`, and TestCase verdict handling is bolted on separately each time.

**Impact.** Adding a new artifact type or changing how state/points are derived requires hunting every call site; the TestCase-verdict special-case has already had to be added per-site (list renderer 2112, detail 335). This is the same maintenance hazard withState() was created to kill, but for read paths.

**Recommendation.** Add two extension properties in RallyApiModels.kt (or a companion file `RallyArtifactExt.kt`): `val RallyArtifact.effectiveState: String get() = if (this is RallyTestCase) lastVerdict ?: "No Verdict" else scheduleState ?: state ?: "Unknown"` and `val RallyArtifact.planEstimate: Double? get() = when (this) { is RallyUserStory -> planEstimate; is RallyDefect -> planEstimate; else -> null }` (rename the member field to avoid collision, e.g. `storyPoints`). Replace all 6 effectiveState call sites and 3 planEstimate type-switches with these. The filter sites that need `""` (not `"Unknown"`) can either use `effectiveState.takeIf { it != "Unknown" } ?: ""` or a separate `effectiveStateRaw` variant returning `""` — whichever reads more clearly. Keep the `withState()` write-path helper as-is. The `planEstimateOf()` private function in RallySprintSummary can then be deleted. This change is purely mechanical and safe to unit-test against the existing 145-test suite.

**Verifier.** The code exactly matches the finding. The `scheduleState ?: state` elvis chain is duplicated at 6 call sites across 4 files with inconsistent fallbacks: `?: ""` at RallyToolWindowPanel:818/823 and RallyExporter:210/283, `?: "Unknown"` at RallyToolWindowPanel:2115, RallyDetailPanel:338, and RallySprintSummary:58. The TestCase verdict special-case (`if (artifact is RallyTestCase) lastVerdict ?: "No Verdict" else scheduleState ?: state ?: "Unknown"`) is duplicated verbatim at RallyToolWindowPanel:2112-2115 and RallyDetailPanel:335-338. The `planEstimate` type-switch `when(artifact) { is RallyUserStory -> planEstimate; is RallyDefect -> planEstimate; else -> null }` is re-implemented three times: as a private `planEstimateOf()` in RallySprintSummary (lines 38-41, not usable externally) and inline at RallyToolWindowPanel:1037-40 and 1672-74. The CLAUDE.md confirms `withState()` was created specifically to kill write-path duplication, yet no equivalent exists for the read path. One nuance: the `""` vs `"Unknown"` discrepancy is a real inconsistency but not a correctness bug — the filter uses `""` precisely so it never accidentally matches a literal state name when the field is absent, while the UI uses `"Unknown"` for display.

**Evidence.** grep: 'scheduleState ?: ...state ?:' appears at RallySprintSummary:58, RallyDetailPanel:338, RallyToolWindowPanel:818,823,2115, RallyExporter:210,283; planEstimate type-switch at RallyToolWindowPanel:1038-39,1673-74,1702-03 and RallySprintSummary:39-40.

---

#### MED-13 · Scope/state/type literals used as untyped magic strings across the control flow
`Code quality, maintainability & complexity` · category: quality · effort: M · conf: 0.95 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** SCOPE_OPTIONS 52-59, STATE_OPTIONS 61-69, buildQuery 782-804, applyClientFilter 806-827, loadTickets 486-545; type strings in RallyApiModels defaults  

**Problem.** Filtering decisions branch on raw string equality: `scope == "Test Cases"`, `scope == "My Tickets"`, `scope != "Defects"`, `stateFilter == "Active"`, `state !in setOf("Accepted","Completed","Idea")`, and artifact type matched as `"HierarchicalRequirement"`/`"Defect"`/`"TestCase"`. These same literals appear in the combo arrays, the query builder, the client (queryAllArtifacts scope param), and the search filter. There is no single source of truth; a typo compiles fine and silently disables a filter.

**Impact.** Refactors and new filters are fragile: the relationship between the combo display string and the branch logic is implicit. The 'Active' state set and the type discriminators are duplicated between UI and exporter/API. A renamed scope string would break loadTickets silently.

**Recommendation.** Two separate constant domains need addressing:

1. Scope/StateFilter display strings: introduce a sealed class or enum (e.g., Scope, StateFilter) with a displayName property. Populate SCOPE_OPTIONS and STATE_OPTIONS by mapping the enum values to their displayName. Read the combo selection as an enum (Scope.fromDisplay(selectedItem)) and branch on the enum. This eliminates the implicit coupling between display text and branch logic, and makes adding a new scope/state atomic — one place, not four.

2. Rally _type discriminators ("HierarchicalRequirement", "Defect", "TestCase"): introduce a companion object or top-level object (e.g., RallyType) with const val USER_STORY = "HierarchicalRequirement", DEFECT = "Defect", TEST_CASE = "TestCase". Reference these constants in applyClientFilter, RallyApiClient.buildWebUrl, and anywhere else the strings appear. The model class defaults can reference the same constants. This does not change protocol behavior but ensures a rename/correction propagates everywhere via the compiler.

Note: Kotlin's when on String is not compiler-exhaustive even with these changes unless you switch to branching on an enum/sealed class — so the exhaustiveness benefit only applies to the Scope/StateFilter enums, not to the type-string constants. Both changes are straightforward and can be done incrementally without touching tests (the string values themselves do not change).

**Verifier.** The code genuinely has the described issue. The SCOPE_OPTIONS array (lines 52-59) and STATE_OPTIONS array (lines 61-69) define raw display strings. Those same strings are then matched literally in: loadTickets (line 488 "Recent Activity", line 542 "Test Cases", line 573 "Any State"); buildQuery (line 787 "My Tickets"); applyClientFilter (lines 809-811 "User Stories"/"Defects"/"Test Cases", lines 817-821 "Active"/"Any State"); the server-search branch (line 932 "My Tickets"). The Rally _type discriminators "HierarchicalRequirement", "Defect", "TestCase" are hardcoded as defaults in RallyApiModels.kt (lines 88/153/365) and then independently repeated in applyClientFilter (lines 809-811) and in RallyApiClient.kt (line 970-974 in buildWebUrl). There are no shared constants or enums for any of these. A typo anywhere compiles without warning and silently disables the affected filter. One nuance: the type strings are the literal WSAPI _type values sent by the server, so they are not arbitrary — they must match what Rally returns on the wire. This does not prevent defining constants for them; it just means the constants would be protocol-anchored rather than UI-only. The exhaustiveness point in the original recommendation is slightly overstated: Kotlin's when on String is not compiler-exhaustive regardless of whether constants are used — you would need a sealed class or enum for true exhaustive checking. But the core claim about no single source of truth, duplication across layers, and silent-failure risk of a typo is fully accurate.

**Evidence.** applyClientFilter (806-827) branches on `"User Stories"`/`"Defects"`/`"Test Cases"` and `"Active"` with an inline `setOf("Accepted","Completed","Idea")`; loadTickets (542) does `if (scope == "Test Cases")`; same literals re-appear in searchArtifacts call at 937.

---

#### MED-14 · StatusBadge.update() calls revalidate()+repaint() on every cell render, causing per-cell validate/repaint storms
`UI rendering & responsiveness` · category: performance · effort: S · conf: 0.82 · verdict: needs-nuance · orig=high  
**File:** `…/ui/StatusBadge.kt`  
**Location:** update(), lines 33-40  

**Problem.** update() unconditionally calls revalidate() and repaint() at the end of every invocation. It is called from inside every cell renderer's getListCellRendererComponent (ArtifactCellRenderer line 2119, TaskCellRenderer line 1186, TestCaseCellRenderer lines 1143+1147 — two badges per cell). getListCellRendererComponent runs once per visible row on every paint, every model event, every selection change, and during the fixedCellHeight prototype measurement. Each update() therefore queues a RepaintManager invalidate (revalidate) plus a dirty-region (repaint) for the rubber-stamp badge on every single cell stamp. revalidate() also walks up to find a validate-root, and the value never actually changed in the common repaint case (same state string + same StateColors), so the work is pure overhead.

**Impact.** On a 200-row ticket list (the documented page size) every full repaint or model event triggers up to 200 (ticket list) or 400 (test-case list, 2 badges/row) superfluous revalidate+repaint requests against the renderer stamp — amplifying the cost the fixedCellHeight optimization was added to remove. Manifests as scroll jank and sluggish list refreshes, exactly the responsiveness goal this dimension targets.

**Recommendation.** The fix is correct but the framing should shift from "jank removal" to "cleaner renderer contract." Remove revalidate() and repaint() from update() entirely. The three cell renderers return the panel component to the CellRendererPane which paints it immediately — no repaint scheduling is needed, and revalidate() on a rubber-stamp component adds unnecessary RepaintManager queue churn. The two real-container callers in RallyDetailPanel (showArtifact() line 340 and clear() line 541) should call stateBadge.revalidate(); stateBadge.repaint() explicitly after update(), or alternatively add a separate refresh() convenience method that wraps these calls for use outside renderers. This is a clean API contract issue: update() should be a pure data-setter; repaint scheduling belongs to the caller when the component lives in a real hierarchy.

**Verifier.** The code is exactly as described: StatusBadge.update() unconditionally calls revalidate() and repaint() at lines 38-39, and it is called from getListCellRendererComponent() in all three renderers (ArtifactCellRenderer line 2119, TestCaseCellRenderer lines 1143 and 1147, TaskCellRenderer line 1186). The detail-panel header badge (RallyDetailPanel lines 340 and 541) also calls update() on a component that genuinely lives in a real container hierarchy — that usage is correct and beneficial.

However, the claimed severity ("high", scroll jank, 200-400 superfluous revalidate+repaint storms) is overstated due to how Swing's rubber-stamp model and RepaintManager actually work:

1. revalidate(): During getListCellRendererComponent(), the renderer panel IS temporarily parented to a CellRendererPane while painting. But revalidate() walks up to find an isValidateRoot() ancestor and posts a validateRoot request to RepaintManager's deferred queue. CellRendererPane overrides isValidateRoot() to return false in Oracle JDK, so the walk continues up to the next real validate root (e.g., JRootPane). The posted request is then batched with other pending validates and processed at the next event-dispatch pass. It is not zero-cost, but it is deferred and coalesced — it does not cause n synchronous layout passes.

2. repaint(): Posts a dirty-region rectangle to RepaintManager. RepaintManager.paintDirtyRegions() coalesces all overlapping dirty rectangles before painting — if the whole list is already dirty (e.g., on a model refresh), n individual repaint() calls produce one merged paint call. In the scroll-jank scenario, the entire visible area is already dirty from the scroll event, so the extra repaint() calls are absorbed.

3. The fixedCellHeight optimization (which the finding cites as being undermined) is a one-time measurement at initialization, not per-render. update() is called once during that prototype render, not repeatedly.

The real underlying issue is correct: update() does unnecessary work (revalidate + repaint) when invoked as a renderer stamp, because the renderer's badge is already being painted by the CellRendererPane and does not need to schedule additional repaints. But the practical impact is medium, not high — the work is batched and coalesced by RepaintManager, not executed per-cell synchronously. Observable jank on a 200-row list from this specific cause is unlikely given modern RepaintManager coalescing, though it does create unnecessary queue churn and is poor practice.

**Evidence.** StatusBadge.update(): "isVisible = this.text.isNotBlank(); getAccessibleContext().accessibleName = this.text; revalidate(); repaint()" — called from ArtifactCellRenderer line 2119 "stateBadge.update(state, RallyColors.forState(state))"

---

### 🟡 Low (67)

#### › API client performance & caching

#### LOW-1 · Image cache has no TTL and is keyed by URL only, so it can serve stale/updated attachment bytes within a session
`API client performance & caching` · category: correctness · effort: S · conf: 0.85 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** downloadAttachment 1461-1509, imageCache 167-169  

**Problem.** Unlike the query cache (2-min TTL via CacheEntry.timestamp), imageCache stores raw ByteArray keyed by URL with no timestamp and no expiry. Rally inline-image/attachment URLs are /slm/attachment/<OID>/<filename>; if an attachment's bytes are replaced server-side while keeping the same OID+filename, the in-memory cache will serve the old bytes for the life of the session (until 10MB eviction or clearCache on manual Refresh). The doc note 'small images rarely change' acknowledges this but it is a silent-staleness path the query cache deliberately bounds with TTL.

**Impact.** Low: in practice attachment content rarely changes under a stable OID. Worst case the detail panel shows a stale image until manual Refresh. No correctness impact on artifact data.

**Recommendation.** The trade-off is already rationalized in the source code comment at line 1463, but CLAUDE.md's Caching bullet (line 47) should be extended with one sentence: "Image cache has no TTL — attachment bytes at a given URL rarely change within a session; a manual Refresh (clearCache) evicts it." This makes the deliberate asymmetry visible at the architecture level without requiring a code change. Adding a TTL to imageCache is unnecessary given the cost/benefit: Rally /slm/attachment/&lt;OID&gt;/&lt;filename&gt; URLs effectively identify a specific immutable content upload, making in-session staleness negligible.

**Verifier.** The code is exactly as the reviewer described. imageCache is declared at line 167-169 as a `MutableMap<String, ByteArray>` (access-ordered LinkedHashMap wrapped in Collections.synchronizedMap) with no timestamp or TTL. At line 1467, `imageCache[url]?.let { return it }` returns cached bytes with no expiry check, in contrast to `getCached()` at line 195-204 which enforces a 2-minute TTL on query results. The asymmetry is real and confirmed.

The `clearCache()` method at line 219-225 does clear imageCache (so manual Refresh wipes it), but `clearArtifactCache()` (called after state/field mutations) intentionally skips imageCache per its comment. So images are only cleared by a full manual Refresh.

There IS a one-line rationale comment in the implementation at line 1463 ("Small images rarely change, so keep a bounded in-memory cache for repeat views"), which the reviewer did not mention - the trade-off is partially documented in code but NOT surfaced in CLAUDE.md's architecture section (line 47 only mentions the 10 MB/1 MB caps, not the no-TTL policy). The finding's severity (low) and confidence (0.55) are appropriate: Rally attachment URLs embed the OID in the path (/slm/attachment/&lt;OID&gt;/&lt;filename&gt;), and while the OID is stable per attachment object, Rally does allow attachment content to be replaced under the same reference. In practice this is extremely rare within a single IDE session.

**Evidence.** imageCache declared as `MutableMap<String, ByteArray>` (167) with no timestamp; downloadAttachment:1467 `imageCache[url]?.let { return it }` with no expiry check, vs getCached TTL at 199

---

#### LOW-2 · resolveInlineImages base64-encodes full image bytes into a String per image, doubling peak heap for inline images
`API client performance & caching` · category: memory · effort: S · conf: 0.85 · verdict: needs-nuance  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** resolveInlineImages 1075-1084  

**Problem.** For each inline image, the cached/downloaded ByteArray is converted via Base64.getEncoder().encodeToString(bytes) into a ~4N/3 String, then concatenated into a data: URI String, then spliced into the description StringBuilder. With maxInlineImages images (cap 10 in the detail panel), this materializes up to 10 separate base64 Strings (each up to ~1.3MB given the 1MB per-image cacheable cap) plus the growing StringBuilder, on top of the cached bytes. The uploadAttachment path already uses the streaming Base64 wrap trick (RallyApiClient.kt:1634-1636) to avoid exactly this intermediate-String allocation, but the detail path does not.

**Impact.** Transient heap spike of several MB when opening a description with multiple large inline images, plus extra GC pressure. Minor versus the network costs above, but it is on the EDT-adjacent description render path and contradicts the streaming-base64 optimization applied elsewhere.

**Recommendation.** The extra allocation is real but bounded and on a background thread, making it a genuine low-priority item. If you want to reduce it, the simplest approach is not the streaming ByteArrayOutputStream trick (which only saves one copy and still ends up with a String), but rather pre-sizing a single StringBuilder per-image and writing the data-URI prefix + base64 chars directly: `val sb = StringBuilder("data:$contentType;base64,"); Base64.getEncoder().encodeToString(bytes).also { sb.append(it) }` — this saves the intermediate standalone `base64` local variable but not the underlying String allocation from `encodeToString`. For a truly allocation-free path, write directly into a CharArrayWriter or use `java.util.Base64.Encoder.encodeToString` with a pre-allocated buffer. Given the 10-image cap and 1 MB-per-image bound (~26 MB worst-case transient), and the fact all of this is on a dedicated background pool (not the EDT), this is not worth the complexity. Leave it as-is or add a brief comment noting the bounded allocation; do not conflate with the uploadAttachment pattern which also produces a final String.

**Verifier.** The code at line 1075 does exactly what the reviewer says: `Base64.getEncoder().encodeToString(bytes)` produces an intermediate base64 String (~1.33 MB for a 1 MB image), then line 1084 constructs another String `"data:$contentType;base64,$base64"` of similar size before `base64` becomes eligible for GC. With the 10-image cap and 1 MB-per-image cache cap, worst-case transient allocation is ~26 MB above the cached bytes — a real but bounded and modest heap spike.

Two nuances make the finding less severe than claimed:

1. The "EDT-adjacent" framing is inaccurate. The entire resolveInlineImages path runs on a dedicated imageExecutor (4-thread pool, line 1089), not on the EDT. The CLAUDE.md explicitly documents "Off-EDT description pipeline" as a performance feature. The GC pressure is entirely on background threads, which is the most benign possible location for it.

2. The uploadAttachment comparison (lines 1634-1636) is not as clean a contrast as the reviewer implies. The streaming trick there (`ByteArrayOutputStream.wrap`) still produces a final `base64Content` String on line 1636 via `toString()`; it merely saves one intermediate allocation. In resolveInlineImages, the same number of extra String allocations occur (one intermediate base64 String, then the data-URI String). The difference is one ByteArray-to-String copy, not "streaming vs non-streaming."

The actual code confirmed at the cited location:
- Line 1075: `val base64 = Base64.getEncoder().encodeToString(bytes)`
- Line 1084: `"data:$contentType;base64,$base64"`

The problem is real but the severity is genuinely low given the caps and off-EDT execution. Calling it "low" is correct.

**Evidence.** RallyDetailPanel.kt:1075 `val base64 = Base64.getEncoder().encodeToString(bytes)` then 1084 `"data:$contentType;base64,$base64"`; contrast RallyApiClient.kt:1635 `Base64.getEncoder().wrap(base64Buffer).use { it.write(fileBytes) }`

---

#### LOW-3 · Opening any ticket always costs a dedicated description round trip that could be coalesced with the detail fan-out
`API client performance & caching` · category: performance · effort: M · conf: 0.85 · verdict: needs-nuance · orig=medium  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** showArtifact 347-353, loadAndRenderDescription 491-516; RallyApiClient.fetchDescription 696-727  

**Problem.** Because the list query uses LIST_FIELDS (no Description), artifact.description is always null when a ticket is selected, so loadAndRenderDescription always calls client.fetchDescription(artifactRef) (line 499) — a separate `<ref>?fetch=Description` GET. This is correct and the fetch is cached, but it is a 4th concurrent request on every selection (alongside test cases, tasks, attachments), each of which is its own round trip. There is no batching: Rally WSAPI can return Description in the same query the list already runs, or the detail fan-out could request Description together with the artifact's other on-demand fields in one GET.

**Impact.** Every ticket open issues 4 parallel round trips (description, test cases, tasks, attachments). The description fetch in particular is on the path the user stares at first. While parallel, each is a full RTT and they share apiExecutor/imageExecutor; coalescing description into an existing request would remove one round trip per selection without hurting list-load payload size.

**Recommendation.** The finding identifies a real but narrow issue: every first-view of a ticket incurs one additional RTT for the description, on top of the 3 tab queries. This is a deliberate, documented trade-off (CLAUDE.md: "Lazy description loading", "Off-EDT description pipeline") that keeps list payloads lean. The impact is lower than claimed because: (a) description runs on the unbounded thread pool, not apiExecutor, so it does not compete with tab queries; (b) the LRU cache makes re-views free.

If the extra first-view RTT is worth addressing, the most targeted option is: when a row receives a mouseEntered or isFocused event (but before click), speculatively warm the description cache by submitting a background `fetchDescription` call. This keeps LIST_FIELDS intact, adds no new API shape, and makes the common "click row, read description" path likely cache-warm. A lower-priority follow-up is to add a comment in `showArtifact` cross-referencing the CLAUDE.md rationale, since the KDoc on `loadAndRenderDescription` already covers the threading decision but not the LIST_FIELDS trade-off.

**Verifier.** The core observation is factually correct: list queries use LIST_FIELDS (no Description), so artifact.description is always null on selection, triggering a `fetchDescription` call that issues a dedicated `<ref>?fetch=Description` GET on first view. DETAIL_FIELDS (LIST_FIELDS + "Description") exists in the codebase (line 428) but is only used by `getArtifactByFormattedId()`, not by the detail fan-out. So one extra RTT per ticket on first open is real.

However, the finding's severity assessment and impact characterization are materially wrong in two ways:

1. Threading model misread: The description fetch does NOT compete on apiExecutor with the 3 tab queries. Lines 402-479 show: test cases, tasks, and attachments are submitted as CompletableFuture on `client.apiExecutor`; then `loadAndRenderDescription` (line 479) is called directly on the `executeOnPooledThread` thread — IntelliJ's unbounded application thread pool. The KDoc at line 484-489 explicitly explains this separation: "parking one of apiExecutor's 4 shared workers on that join starved fresh loads." So there is no contention with the 3 tab queries, and the description pipeline runs on a thread that doesn't throttle API requests.

2. The design is explicitly documented as intentional: CLAUDE.md documents "Lazy description loading" and "Off-EDT description pipeline" as deliberate architectural decisions with stated rationale. The extra RTT is the accepted cost of keeping list payloads lean across 200+ items.

The caching also significantly limits real-world impact: `fetchDescription` stores results (including empty descriptions as "") in the LRU cache, so re-selecting any previously viewed ticket costs zero network RTTs.

The genuine residual issue is narrower: on first view of a ticket, one additional sequential RTT occurs before the description renders, even though it runs in parallel with the tab loads. On a slow connection or with a large description body containing inline images, this can delay what the user sees first. The suggestion to coalesce Description into the list query (defeating the LIST_FIELDS optimization) or into a combined detail fetch is architecturally valid but would need a new API call shape (the current fan-out uses separate endpoints). The reviewer's prefetch-on-hover alternative is the most practical improvement.

**Evidence.** RallyDetailPanel.kt:497 `if (resolved.isNullOrBlank()) { resolved = client.fetchDescription(artifactRef)`; LIST_FIELDS excludes Description (RallyApiClient.kt:406-425); DETAIL_FIELDS = LIST_FIELDS + "Description" (428)

---

#### LOW-4 · Search and query cache keys embed raw, unbounded user text and page params, weakening hit rate and growing the key space
`API client performance & caching` · category: performance · effort: S · conf: 0.75 · verdict: needs-nuance  
**File:** `…/api/RallyApiClient.kt`  
**Location:** searchArtifacts 898, queryUserStories 664, queryDefects 681, queryAllArtifacts 809  

**Problem.** Cache keys are string concatenations that include the full query text and pageSize/maxResults (e.g. "search:${searchText}|${scope}|${pageSize}|${maxResults}|${ws}|${pr}" and "stories:${query}|${pageSize}|${maxResults}|${ws}|${pr}"). Because the server-search path fires once per progressively-longer query string during typing (panel debounce, line 924), each distinct prefix produces a distinct cache entry, and the 200-entry LRU can churn through these single-use search entries, evicting genuinely reusable list entries. pageSize/maxResults are also part of the key even though they are effectively constant per session, so an unintended page-size change fragments the cache.

**Impact.** Lower effective hit rate for the list cache during active searching (search entries crowd out list entries within the 200-entry budget), and a larger key string allocated per call. Modest, but the search path is per-keystroke.

**Recommendation.** The structural issue is real but mild: a shared 200-entry LRU holds both stable list-query entries and one-shot search entries. A targeted fix is to cap how many search entries can live in the cache simultaneously — e.g., evict old `search:` entries before inserting a new one (keep at most 10). Alternatively, assign search entries a shorter TTL (e.g., 30s) so they expire quickly without displacing list entries at eviction time. The `pageSize/maxResults` component of all cache keys is session-constant and can safely be dropped from the key (reduce to `"search:${searchText}|${scope}|${ws}|${pr}"`), saving a few bytes and making keys cleaner. Do NOT separate into two caches — that adds complexity disproportionate to the actual eviction pressure, given the debounce-plus-fallback guard already limits search cache production to a handful of entries per session.

**Verifier.** The cache key structure is exactly as described: line 898 reads `val cacheKey = "search:${searchText}|${scope}|${pageSize}|${maxResults}|${ws}|${pr}"` and lines 664/681/809 show the same pattern for queryUserStories/queryDefects/queryAllArtifacts. So the key construction claim is accurate.

However, the reviewer critically mischaracterizes when server search fires. Panel line 924 shows the condition is: `if (filtered.isEmpty() && query.length >= 3)` — server search is a fallback that only fires when the local `allArtifacts` list yields ZERO client-side matches. It is not "per-keystroke" in normal operation. Moreover, the 300ms non-repeating debounce timer (line 80: `javax.swing.Timer(300) { applySearchFilter() }.apply { isRepeats = false }`) means a burst of typing generates exactly one call to `applySearchFilter`, not one per character. A user typing "bug 123" quickly fires one server search, not six.

For list queries (queryUserStories/queryDefects/queryAllArtifacts), the query parameter is a structured Rally WSAPI filter string (not raw user text), so it is stable across repeated calls with the same filters — these cache well.

The pageSize/maxResults inclusion in keys is mildly wasteful since `serverResultLimit = RallySettings.getInstance().pageSize.coerceIn(25, 100)` is session-constant, but it does not fragment the cache across sessions — just adds ~10 bytes per key string. 

The LRU eviction concern is structurally valid: a shared 200-entry LRU means many distinct search strings (one per unique completed search) can displace structured list-query entries. But given the fallback-only trigger and debounce, the actual rate of search cache entries is low — realistically a few entries per session rather than tens. The concern is real but the claimed impact ("per-keystroke" churn, "crowding out" list entries) is overstated.

**Evidence.** RallyApiClient.kt:898 `val cacheKey = "search:${searchText}|${scope}|${pageSize}|${maxResults}|${ws}|${pr}"`; panel:924 server search fires on every query of length >= 3

---

#### LOW-5 · getArtifactByFormattedId always issues a FormattedID search query even when callers already hold the artifact's _ref
`API client performance & caching` · category: performance · effort: S · conf: 0.85 · verdict: needs-nuance  
**File:** `…/api/RallyApiClient.kt`  
**Location:** getArtifactByFormattedId 732-794  

**Problem.** getArtifactByFormattedId builds a `(FormattedID = "...")` query and runs it through queryAllPages-style fetch (lines 760-769) — a server-side search returning a QueryResult. A direct GET on the artifact's _ref with fetch=DETAIL_FIELDS is cheaper (no query parsing/index lookup on Rally's side, single object). Several callers (notably the export path, see export-redundant-artifact-refetch) already possess artifact.ref but discard it in favor of the FormattedID lookup.

**Impact.** Each lookup is a heavier search query rather than a direct object GET. On Rally, indexed FormattedID searches are usually fast, so impact is modest, but it compounds with the export refetch and any per-row lookups.

**Recommendation.** The structural issue is real but low priority given the caching. If you want to optimize: add an overload `getArtifactByRef(ref: String, fields: String = DETAIL_FIELDS): RallyArtifact?` that issues a direct GET (`$ref?fetch=$fields`) as `fetchDescription` does. Then add an overload `exportArtifactJson(artifact: RallyArtifact, outputDir: String)` that accepts the already-fetched object directly, bypassing the lookup entirely — the UI already has the full artifact in memory at export time (line 1594: `selected.mapNotNull { artifact -> ... }`). Reserve the FormattedID search path for the user-typed id-only export case. The JSON+Markdown double-call for the same artifact is already handled correctly by the cache, so fixing it matters only as a code-clarity improvement rather than a measurable performance gain.

**Verifier.** The code at lines 759-769 does exactly what the review claims: it builds `(FormattedID = "$safeId")` and issues a server-side search query rather than a direct ref GET like `fetchDescription` does at line 703. The export path at RallyExporter.kt:369 and :417 accepts only a `String artifactId` and calls `getArtifactByFormattedId`, and the calling site in RallyToolWindowPanel.kt:1595-1600 holds `artifact.ref` but passes only `id` to the exporter, discarding the ref. So the structural issue is real.

However, the claimed impact is overstated for these reasons:
1. `getArtifactByFormattedId` uses `pageSize = 1` (line 765), so it is a bounded, single-result query — not a full index scan.
2. Results are cached (line 777: `putCache(cacheKey, artifact)`), and the export wraps in `enterBulkMode()` (15-min TTL). Both JSON and Markdown calls for the same artifact hit the cache on the second call.
3. FormattedID is an indexed, unique field in Rally — Rally's own query planner resolves this as efficiently as a ref GET in practice.
4. The function is only called from the export path (2 call sites) and not from any per-row or frequent-list-render path.

So this is a real code-quality issue (the API boundary prevents passing a ref through), but the severity is low-to-cosmetic. There is no meaningful double-fetch for normal usage — the caching makes the second export pass free, and the query itself is a single-item bounded lookup on an indexed unique key.

**Evidence.** RallyApiClient.kt:760 `val query = "(FormattedID = \"$safeId\")"` then 763 buildQuery(...) for a search rather than a direct `$ref?fetch=...` GET (contrast fetchDescription:703 `val url = "$artifactRef?fetch=Description"`)

---

#### › Architecture & strategic improvement opportunities

#### LOW-6 · sinceBuild=241 floor blocks platform coroutines and forces a cluster of deprecated-API suppressions
`Architecture & strategic improvement opportunities` · category: architecture · effort: S · conf: 0.90 · verdict: needs-nuance · orig=medium  
**File:** `build.gradle.kts`  
**Location:** sinceBuild = "241" line 39; @Suppress("DEPRECATION") sites: RallyApiClient.kt 54-58, RallySettings.kt 152, RallySettingsConfigurable.kt 33-35, RallyDetailPanel.kt 918-919  

**Problem.** The 241 floor is a deliberate, documented trade-off (CLAUDE.md, MEMORY.md), but it has compounding architectural cost: it forces HttpConfigurable instead of JdkProxyProvider (RallyApiClient 54-58), the deprecated FileSaverDescriptor vararg constructor (RallyDetailPanel 918-919), the deprecated addBrowseFolderListener 4-arg form (Configurable 33-35), and — most importantly — it predates clean access to the IntelliJ Kotlin coroutine APIs and Service.Async coroutine scopes that would let the concurrency rewrite (see coroutines finding) be idiomatic. The verifyPlugin task is also already pinned around an unreadable 2025.3 layout.

**Impact.** The single biggest modernization (coroutines/structured concurrency) is gated behind this floor. Keeping 241 indefinitely means the plugin permanently carries hand-rolled CompletableFuture orchestration and a growing list of deprecation suppressions that each need re-verification on every new IDE release.

**Recommendation.** The real, concrete benefit of raising sinceBuild to 243 is clearing 2 external deprecation suppressions (HttpConfigurable → JdkProxyProvider, and 4-arg addBrowseFolderListener → 2-arg form) plus the FileSaverDescriptor one. These are documented, low-risk, and have known migration paths. Do not frame this change as "enabling coroutines" — the codebase has no coroutine dependencies and sinceBuild=241 already exceeds the 233 threshold where platform coroutine scopes became stable; a coroutine migration is a separate decision orthogonal to the floor. The CredentialAttributes suppression at RallySettings.kt:152 has an independent PasswordSafe key-stability concern (changing the constructor would orphan stored API keys for existing users) that should be addressed separately regardless of the floor. Priority: defer floor raise until 2024.1/2024.2 install-base data is available from the JetBrains Marketplace stats; the current suppressions are properly guarded and cause zero user-visible issues.

**Verifier.** The code confirms four real @Suppress("DEPRECATION") sites caused by the 241 floor, but the finding overstates the architectural cost in two important ways:

1. The coroutines claim is speculative, not evidenced. There are zero coroutine usages anywhere in the codebase (no kotlinx-coroutines dependency, no suspend fun, no CoroutineScope). The review frames this as the "most importantly" cost of the floor, but no one is currently blocked from using coroutines by the floor — the codebase simply was not written with coroutines, and raising the floor would not automatically enable them. Platform coroutine scopes (e.g., cs()) became stable at 233, not 243. The floor would need to be ≥233 for platform-provided scopes, which sinceBuild=241 already satisfies. The finding's framing that "the single biggest modernization is gated behind this floor" is not supported by what the code actually contains.

2. Two of the four suppressions are correctly floor-related (HttpConfigurable at RallyApiClient.kt:58, addBrowseFolderListener at RallySettingsConfigurable.kt:35), one is partially floor-related with an additional PasswordSafe key-stability concern independent of the floor (RallySettings.kt:152 — changing CredentialAttributes constructor would orphan stored keys), and the suppression at RallySettings.kt:42 is entirely unrelated to the floor — it suppresses access to `state.apiKey` which is marked `@Deprecated` by the codebase itself as an internal migration annotation ("Use PasswordSafe via apiKey property instead"). The finding incorrectly groups all four sites as floor-driven.

3. The actual suppressions at line 58 of RallyApiClient.kt and line 35 of RallySettingsConfigurable.kt are genuinely floor-caused and each has a documented migration path (JdkProxyProvider and 2-arg addBrowseFolderListener respectively). FileSaverDescriptor at RallyDetailPanel.kt:919 is also real but minor.

4. The documented trade-off in CLAUDE.md is accurately reflected in the code comments — each suppression site carries a "migrate once floor is raised to 243+" note, which is responsible practice.

The real finding here is narrow and genuine: there are 2-3 floor-caused deprecation suppressions that are cleanly fixable by raising sinceBuild to 243, and the verifyPlugin pinning work-around confirms ongoing maintenance overhead. The severity should be low, not medium — these are suppressed-warning maintainability issues with no functional impact, no correctness concern, and no user-visible cost. The HttpConfigurable still works correctly through 261.

**Evidence.** build.gradle.kts:39 `sinceBuild = "241"`; four distinct `@Suppress("DEPRECATION")` blocks each documenting 'migrate once the floor is raised to 243+'.

---

#### LOW-7 · Persistent application settings double as live selection state, read cross-thread — incoherent state ownership
`Architecture & strategic improvement opportunities` · category: architecture · effort: M · conf: 0.85 · verdict: needs-nuance · orig=medium  
**File:** `…/settings/RallySettings.kt`  
**Location:** selectedProject/selectedIteration data-class fields (State 25-26, accessors 118-123); read off-EDT in RallyToolWindowPanel getSelectedProjectRef 833-837, loadTickets 506/518, write in combo listeners 364/377  

**Problem.** selectedProject and selectedIteration are stored in the @State persistent settings object (mutable var on a non-volatile data class State, line 18-28) and used as the live, authoritative selection that background threads read (getSelectedProjectRef line 834, loadTickets line 506). The same object also holds credentials behind a careful PasswordSafe latch. So one component conflates 'persist my last choice across restarts' with 'this is the current selection right now', and the latter is read from pooled threads via a plain (non-@Volatile) data-class field.

**Impact.** Selection state and persistence are entangled: every transient UI selection change writes through the persistence layer, and background reads of myState.selectedProject (a plain var) have no memory-visibility guarantee. It also forces the panel to keep parallel shadow state (lastProject/lastIteration, cachedProjects) and to reconcile combo-index ↔ settings ↔ cache in three places (e.g. loadTickets 506-511, create dialogs 1096-1105). This is a coherence smell that makes the threading code in the panel more complex than it needs to be.

**Recommendation.** The JMM gap is real but narrow and the worst outcome is a benign stale read. Two targeted fixes are sufficient without a full service-layer refactor: (1) Add `@Volatile` backing fields for selectedProject and selectedIteration directly on RallySettings (bypassing the data-class State fields for cross-thread access), or make the getters/setters `synchronized`. (2) Optionally document in RallySettings that selectedProject/selectedIteration are "last-used hints for UI restore" rather than the authoritative live selection — clarifying the dual-purpose design for future maintainers. The shadow `lastProject`/`lastIteration` fields in the panel are necessary for change detection in combo listeners and should not be removed. A full RallyService rewrite is not warranted by this issue alone.

**Verifier.** The finding is directionally correct but overstates the threading risk and misidentifies where the volatility gap actually is.

WHAT THE CODE ACTUALLY DOES:
- `myState` itself is declared `@Volatile` (line 30): `@Volatile private var myState = State()`. The reviewer's characterization of "non-@Volatile" is therefore partially wrong — the *reference* is volatile. However, the individual `var` fields on the `State` data class (selectedProject, selectedIteration) are plain Kotlin properties with no JMM ordering guarantee. Writes via `myState.selectedProject = value` (selectedProject setter, line 120) happen on the EDT (combo listener, lines 364/377); reads happen on pooled threads via `RallySettings.getInstance().selectedProject` (lines 506, 518, 834). Because only the reference to `myState` is volatile, not the field within it, a JMM-strict analysis says there is no happens-before between these EDT writes and the pooled-thread reads.

PRACTICAL IMPACT IS NARROW:
- The pooled thread is always launched *after* the EDT write completes (the combo listener writes settings then calls `loadTickets()`, which calls `executeOnPooledThread`). In practice the write is visible before the read. But this is a JMM coincidence, not a guarantee.
- Even if a stale value is read, `cachedProjects.firstOrNull { it.name == saved }?.ref` (line 836) just returns null, falling back to "All Projects" — the worst outcome is one stale ticket fetch, not data loss or a crash.

THE ARCHITECTURE SMELL IS REAL:
- `selectedProject`/`selectedIteration` serve dual purposes: cross-restart persistence hint AND authoritative live selection. The panel maintains shadow `lastProject`/`lastIteration` fields specifically to distinguish user-initiated changes from programmatic combo population, which is a direct consequence of this entanglement.
- `cachedProjects` (itself `@Volatile`) is the real lookup table; the settings field is only a string name used as a key into that cache.

THE RECOMMENDATION IS OVERSTATED:
- Introducing a full "RallyService typed state model" is a significant refactor for what amounts to a narrow memory-model gap with benign worst-case behavior and a minor architecture smell. A targeted fix suffices: make the accessors synchronized (or use `@Volatile`-annotated wrapper fields in RallySettings, or simply declare `selectedProject`/`selectedIteration` as `@Volatile` fields directly in the State class — though data class fields can't be annotated that way, so a pair of `@Volatile` fields on the settings class itself, bypassing the State data class, would be the minimal fix).

**Evidence.** RallySettings.State (18-28) declares `var selectedProject` on a non-volatile data class; RallyToolWindowPanel.kt:834 reads it off-EDT in getSelectedProjectRef (called from loadSprintSummary on a pooled thread).

---

#### LOW-8 · Minimal observability — Logger.warn/error only, no metrics or structured request timing for diagnosing slow Rally instances
`Architecture & strategic improvement opportunities` · category: architecture · effort: M · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** executeWithRetry 1038-1082; executeGet 458-475; LOG usage throughout  

**Problem.** The client logs warnings/errors and a single LOG.info for the built query (RallyToolWindowPanel buildQuery line 802), but there is no timing/latency capture per request, no count of cache hits vs misses, no retry-count surfacing, and no way for a user on a slow on-prem Rally to see WHERE time is going (network vs gzip decode vs JSON parse vs render). The cache hit/miss is invisible.

**Impact.** When a user reports 'the plugin is slow', there is no data to distinguish a slow Rally server, a cache that is not being hit (e.g. because the cache key includes a frequently-changing param), retries silently eating time, or a heavy description render. Given speed is the top goal, the inability to measure it is a meaningful gap.

**Recommendation.** The finding is accurate. The pragmatic fix requires minimal code: (1) In executeGet, wrap the executeWithRetry call with System.nanoTime() before/after and emit one LOG.debug("GET {url} -> {status} in {ms}ms, attempt={attempt}") — the attempt count can be surfaced by making executeWithRetry return a small data class pairing the response with attempt count. (2) In getCached/putCache, maintain two AtomicLong counters (cacheHits, cacheMisses) on the client; add a getDiagnostics(): String method that dumps hit rate, total entries, and TTL. (3) Expose that method from the Settings "Test Connection" button or a hidden diagnostic action (Help > Rally Diagnostics). This is ~30 lines of change and gives enough signal to answer "is it the server or the cache?" without requiring a ring buffer or structured logging framework. Retries warrant a LOG.warn("Retrying Rally request, attempt=$attempt, status=${response.statusCode()}, delay=${delayMs}ms, url=$url") so slow on-prem users can spot silent retry storms in their IDE log.

**Verifier.** The code exactly matches the finding's description. In executeWithRetry (lines 1038-1082), all three retry-path branches (InterruptedException, connect exception, retryable status code) contain zero LOG calls — retries are entirely silent. In executeGet (lines 458-475), there is no timing capture before or after the executeWithRetry call. getCached (lines 195-205) and putCache (lines 207-211) contain no hit/miss counters. All 10 LOG statements in RallyApiClient.kt are LOG.warn — there are zero LOG.info/debug/trace calls in the file. The only LOG.info in the whole API/UI layer is in RallyToolWindowPanel.kt line 802 ("Rally query built ..."), which captures the query structure but not the response time. The reviewer's specific evidence is accurate point-for-point.

**Evidence.** RallyApiClient.kt executeWithRetry (1038) and executeGet (458) contain no timing or counter instrumentation; the only timing-adjacent log is RallyToolWindowPanel.kt:802 `LOG.info("Rally query built ...")`.

---

#### LOW-9 · Every scope/state/project/sprint change triggers a full network re-query; no prefetch or incremental loading
`Architecture & strategic improvement opportunities` · category: performance · effort: M · conf: 0.90 · verdict: needs-nuance · orig=medium  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** scopeCombo/stateCombo/projectCombo/iterationCombo listeners 344-380 all call loadTickets(); applyClientFilter 806-827; loadTickets 469-621  

**Problem.** State filtering is already done client-side (applyClientFilter, line 806) because ScheduleState vs State differs by type — yet the stateCombo listener still calls full loadTickets() (line 354), re-hitting the network for data already in allArtifacts. The scope filter is also partly client-side (line 808-813) but still reloads. Loading fetches a full page (default 200) per type on every change. There is no prefetch of the likely-next selection (e.g. the detail of the top item, or the adjacent project's list), and no incremental/windowed loading — the list is built in one shot via queryAllPages looping to maxResults.

**Impact.** Toggling a state filter (a pure client-side operation) pays a full round-trip plus cache lookup and a full list rebuild, when the result is a subset of data already in memory. On a slow/proxied Rally connection this is the most visible latency the user feels after the initial load. This directly contradicts the user's #1 goal (speed).

**Recommendation.** The real fix is narrower than the reviewer suggests: for stateCombo and scopeCombo changes (not projectCombo or iterationCombo), skip loadTickets() entirely and call applyClientFilter() + applySearchFilter() directly on the existing allArtifacts. This eliminates the unnecessary background thread dispatch, the "Loading..." flicker, and the risk of a cold-cache network call. The pattern would be:

stateCombo.addActionListener {
    val newState = stateCombo.selectedItem as? String ?: return@addActionListener
    if (newState == lastState) return@addActionListener
    lastState = newState
    // State is client-side only — no network needed
    val scope = scopeCombo.selectedItem as? String ?: "My Tickets"
    allArtifacts = applyClientFilter(scope, newState, cachedRawArtifacts)
    applySearchFilter()
}

This requires keeping a separate cachedRawArtifacts field (the unfiltered server response) alongside allArtifacts (the filtered view). projectCombo and iterationCombo correctly need the full loadTickets() path because they change server-side query parameters. Prefetching and incremental loading (the rest of the original recommendation) are out of scope for this specific bug and should be treated as separate enhancement work.

**Verifier.** The reviewer is correct that stateCombo and scopeCombo both call loadTickets() (lines 350–355), and that applyClientFilter() (line 806) does all state/type filtering in memory. The comment at line 784 explicitly acknowledges this: "State/type filtering is done client-side since ScheduleState vs State differs by type." So the architectural observation is accurate.

However, the claimed impact — "pays a full round-trip plus cache lookup" — is wrong. RallyApiClient.queryAllArtifacts() checks an LRU cache before any network call (line 810: `getCached<List<RallyArtifact>>(cacheKey)?.let { return it }`). The cache key (line 809) includes query, pageSize, maxResults, scope, workspaceRef, and projectRef — but NOT state, because state is not in the server query at all. This means a state filter toggle within the 2-minute TTL hits the cache immediately (O(1) map lookup) and returns the already-fetched list. The actual cost is: background thread dispatch + cache lookup + applyClientFilter (O(n)) + applySearchFilter + UI list model rebuild — no network round-trip.

The finding is architecturally valid but the severity is overstated. The redundant loadTickets() path is genuinely unnecessary code complexity: when stateCombo or scopeCombo changes, the correct action is to call applyClientFilter() + applySearchFilter() directly on the already-loaded allArtifacts, skipping the network path entirely. The real cost (when cache is warm, which is the common case) is the background thread dispatch overhead and the unnecessary "Loading..." status flash that confuses users into thinking a network call happened. The claim of "most visible latency the user feels" is false for warm-cache cases. It would only be correct for the very first state toggle on a cold start (no cached data yet), or after 2+ minutes of inactivity.

For project and iteration changes, the full loadTickets() call is correctly required because those change the server-side query scope (owner filter, iteration filter, projectRef). That part of the original analysis is also not adequately distinguished.

**Evidence.** RallyToolWindowPanel.kt:350-355 stateCombo listener `lastState = newState; loadTickets()`; applyClientFilter (806) already filters state in memory, proving the reload is redundant for state changes.

---

#### LOW-10 · Inline-image resolution blocks a pooled thread on futures.map { it.join() }, separate from structured cancellation
`Architecture & strategic improvement opportunities` · category: performance · effort: S · conf: 0.85 · verdict: needs-nuance  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** resolveInlineImages 1040-1106, esp. `val results = futures.map { it.join() }` line 1091; dispose() shutdown rationale 143-152  

**Problem.** resolveInlineImages submits up to 10 downloads to imageExecutor and then blocks the calling pooled thread with futures.map { it.join() } (line 1091). The dispose() method (143-152) had to use shutdown() not shutdownNow() specifically because shutdownNow would strand queued tasks and leave the join() parking an app-pool thread forever. This is a self-inflicted constraint born of the manual-future model — the join holds a thread for the slowest of N parallel image downloads.

**Impact.** While a multi-image description resolves, one app-pool thread is parked on the join for the duration of the slowest image, and disposal semantics are delicate enough to require a documented shutdown()-vs-shutdownNow() choice. Under coroutines this would be a suspending awaitAll() that holds no thread and cancels cleanly with the scope.

**Recommendation.** The thread-parking behavior is intentional and correctly scoped to the unbounded IntelliJ app pool — not apiExecutor — so there is no starvation risk. The only actionable improvement at this severity level is adding a per-future timeout on the join (e.g., `it.get(30, TimeUnit.SECONDS)` with a fallback to null on TimeoutException) to guard against a stuck HTTP download holding an app-pool thread indefinitely. A full coroutines migration is disproportionate to the actual impact here and should be deferred until the codebase has a broader need for structured concurrency.

**Verifier.** The finding is factually correct about the mechanics: `resolveInlineImages` at line 1091 does call `futures.map { it.join() }`, which parks the calling thread while waiting for all image downloads. The `dispose()` comment at lines 146-150 does explicitly explain why `shutdownNow()` cannot be used. Both pieces of evidence are real.

However, the finding misrepresents the severity of the impact. The code's own documentation (lines 485-489 comment and CLAUDE.md "Off-EDT description pipeline" entry) shows this is an intentional, documented architectural decision: the function is deliberately moved to IntelliJ's unbounded `executeOnPooledThread` pool *specifically because* blocking that thread is acceptable while blocking apiExecutor's 4-worker pool is not. The comment reads: "parking one of apiExecutor's 4 shared workers on that join starved fresh loads during rapid ticket switching." The migration to the unbounded pool already solved the thread-starvation problem that the reviewer is attributing as still present.

The actual impact of parking one unbounded-pool thread is negligible — IntelliJ's shared pool expands on demand and the cap of 10 inline images limits duration. The disposal complexity is real but trivial (a one-line comment explains the choice). There is no scenario in normal usage where this parks a thread "forever" — the imageExecutor has 4 threads that will complete downloads (or bail on generation mismatch) regardless.

The reviewer's recommended interim fix (a join timeout) has merit as a defensive safety net against a hung HTTP download, but the coroutines migration recommendation goes far beyond what the low-severity finding warrants. The finding is accurate in describing the pattern but overstates the practical impact and the "self-inflicted constraint" framing ignores that the constraint was already resolved by the unbounded-pool design.

**Evidence.** RallyDetailPanel.kt:1091 `val results = futures.map { it.join() }`; dispose() comment 146-150 explains shutdownNow() would leave the resolveInlineImages join 'park a shared app-pool thread forever.'

---

#### LOW-11 · Hand-written Gson JsonObject drilling duplicated across ~12 client methods; consider centralizing or kotlinx.serialization
`Architecture & strategic improvement opportunities` · category: quality · effort: S · conf: 0.97 · verdict: confirmed · orig=medium  
**File:** `…/api/RallyApiClient.kt`  
**Location:** Repeated CreateResult/OperationResult unwrap: updateArtifactState 1140-1146, updateArtifactOwner 1161-1167, updateArtifactField 1183-1189, createUserStory 1532-1541, createDefect 1570-1579, createTask 1606-1615, uploadAttachment 1644-1676  

**Problem.** Every write method repeats the same pattern: JsonParser.parseString(response.body()).asJsonObject, getAsJsonObject("CreateResult"|"OperationResult"), null-check, getAsJsonArray("Errors"), size>0 throw, getAsJsonObject("Object"). This is ~10 lines duplicated 7+ times with only the wrapper key and error message differing. Reads similarly hand-drill root.entrySet().firstOrNull()?.value?.asJsonObject (fetchDescription 709) and getAsJsonObject("QueryResult") (getCurrentUser 580, getUserByUsername 610).

**Impact.** Each new write endpoint copies this boilerplate, and a fix to the error-extraction logic must be applied in 7 places (the createResult/operationResult divergence is exactly the kind of thing that drifts). It also makes the client harder to read than the actual HTTP/caching logic warrants.

**Recommendation.** Add two private helpers in RallyApiClient: (1) `private fun parseOperationResult(body: String, opDescription: String)` — parses body, extracts OperationResult, throws on missing wrapper or non-empty Errors; (2) `private fun <T> parseCreateResult(body: String, type: Class<T>, opDescription: String): T` — extracts CreateResult, throws on missing wrapper or Errors, deserializes and returns the Object. All 8 write-path blocks collapse to one-liners. The reviewer's recommendation is sound. Severity is better classed as low (not medium): this is pure maintainability boilerplate in a ~1700-line file with a stable write-path that rarely adds new endpoints; there is no runtime correctness risk and the drift scenario (fixing Errors extraction in one place but not others) is low probability given the current endpoint count. Migrating to kotlinx.serialization is a separate, larger refactor and not needed to capture this win.

**Verifier.** All 8 cited duplicated blocks exist exactly as described. There are 3 identical OperationResult unwrap patterns (updateArtifactState 1140-1146, updateArtifactOwner 1161-1167, updateArtifactField 1183-1189) and 5 CreateResult unwrap patterns (createUserStory 1532-1541, createDefect 1570-1579, createTask 1606-1615, uploadAttachment step-1 1644-1652, uploadAttachment step-2 1667-1676). No private helper exists for either pattern. The reviewer actually undercounts: uploadAttachment contains two CreateResult blocks (one per upload step), giving 8 total, not 7. The only difference between instances is the error message string (e.g., "Failed to create user story" vs "Failed to create defect"). The fetchDescription and getCurrentUser/getUserByUsername uses of JsonParser are structurally different (they don't share the Errors-array check pattern), so those are not part of the same problem. The core claim — that the 5-7 line OperationResult/CreateResult unwrap is duplicated with only strings differing and no shared helper — is accurate.

**Evidence.** RallyApiClient.kt:1532-1541 and 1570-1579 are the same CreateResult/Errors/Object unwrap with only the strings 'user story' vs 'defect' differing.

---

#### LOW-12 · No use of Task.Backgroundable / ProgressManager — long operations (export, bulk state change) lack progress UI and cancellation
`Architecture & strategic improvement opportunities` · category: ux · effort: M · conf: 0.95 · verdict: confirmed · orig=medium  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** exportSelectedArtifact 1568-1661 (parallel export of N artifacts + their test cases, joined with allOf().join()); changeState 1729-1813 (parallel multi-select updates)  

**Problem.** Export and bulk state-change fan out CompletableFutures across apiExecutor and block on allOf().join() inside a raw executeOnPooledThread, with feedback limited to a single statusLabel.text = 'Exporting...'. There is no ProgressIndicator, no determinate progress bar, and no way for the user to cancel a large export mid-flight. A multi-artifact export that also pulls every linked test case (1607-1625) can run for a long time with no visible progress and no cancel.

**Impact.** On a large selection the IDE gives no indication of how far along the export is or that it can be stopped; the only signal is the tool window status text. Users cannot abort a runaway export, which on a slow connection wastes the bounded worker pool and the user's time. Progress + cancel is exactly the kind of responsiveness improvement the user is after.

**Recommendation.** The finding is accurate but the severity should be low rather than medium. This is a tool-window plugin, not an IDE-wide blocking operation — the pooled thread blocks but the EDT stays free and the IDE remains interactive. The `changeState` path is a concern only for large multi-selects (the confirmation dialog already fires for >1 ticket, and each update is a single PATCH — fast on good connections). Export is the more realistic pain point since it chains JSON queries + image downloads per artifact. A pragmatic improvement short of `Task.Backgroundable`: (1) add a per-artifact counter update to `statusLabel.text` on the EDT (e.g. "Exporting 3/10...") — this requires only atomics already in place; (2) add a cancel flag (volatile Boolean) checked between items in the loop. Wrapping in `Task.Backgroundable` with a determinate `ProgressIndicator` would be the complete solution and is the correct IntelliJ idiom, but given the scope of this plugin (Rally tool window) and the fact that the EDT is never blocked, it is a quality-of-life improvement rather than a correctness or stability issue.

**Verifier.** The cited code is exactly as described. `exportSelectedArtifact` (lines 1568–1661) fans out N `CompletableFuture.runAsync` tasks onto a 4-thread `apiExecutor`, each of which also queries and exports all linked test cases, then blocks the pooled thread with `CompletableFuture.allOf(*futures.toTypedArray()).join()` at line 1630. The only user feedback is `statusLabel.text = "Exporting..."` set at line 1580. `changeState` (lines 1729–1813) does the same pattern: parallel futures on `apiExecutor` joined at line 1784, with `statusLabel.text = "Updating..."` at line 1758 as the sole feedback. A grep for `Task.`, `ProgressManager`, `ProgressIndicator`, `withBackgroundProgress`, and `isCanceled` returns zero hits in the entire file. There is no cancel path whatsoever. The 4-thread pool means that exporting even 5 artifacts can saturate the pool while all threads are blocked on HTTP round-trips for test case queries and image downloads, during which the IDE appears completely frozen from a progress-feedback standpoint. The exporter itself calls `exportArtifactJson`, `exportArtifactMarkdown`, `exportTestCaseJson`, and `exportTestCaseMarkdown` per artifact, each of which may make multiple network calls. On a slow Rally connection with a large multi-select this can run for tens of seconds with no indication of progress or ability to cancel.

**Evidence.** RallyToolWindowPanel.kt:1630 `CompletableFuture.allOf(*futures.toTypedArray()).join()` inside executeOnPooledThread with only `statusLabel.text = "Exporting..."` (1580) for feedback — no ProgressIndicator anywhere in the file.

---

#### › Build, plugin config, platform compat & deprecations

#### LOW-13 · Kotlin Gradle Plugin 1.9.25 blocks Gradle configuration cache, slowing every build
`Build, plugin config, platform compat & deprecations` · category: build · effort: M · conf: 0.95 · verdict: confirmed · orig=medium  
**File:** `build.gradle.kts`  
**Location:** line 5 (kotlin.jvm 1.9.25); gradle.properties has no org.gradle.configuration-cache flag  

**Problem.** Enabling Gradle's configuration cache fails with a hard error: 'Plugin org.jetbrains.kotlin.jvm: Unsupported provider is registered as a task completion listener'. I verified this by running `./gradlew help --configuration-cache`, which reported 'Configuration cache problems found in this build. 1 problem was found storing the configuration cache.' originating in KGP 1.9.25's BuildFlowService. Because of this the project ships with configuration cache off, so Gradle re-runs the full configuration phase on every invocation (compile, test, runIde, verifyPlugin).

**Impact.** Every build pays the configuration-phase cost again (noticeable on the IntelliJ Platform plugin, which configures a large dependency graph). The configuration cache typically cuts warm incremental build/test turnaround substantially — directly relevant to the user's #1 goal of speed.

**Recommendation.** Upgrade the Kotlin Gradle plugin from 1.9.25 to 2.0.x (the BuildEventsListenerRegistry issue is not patched in any 1.9.x release; 2.0.0 is the first compatible version). After upgrading, add `org.gradle.configuration-cache=true` to gradle.properties and run `./gradlew test --configuration-cache` twice to confirm the second invocation reports "Configuration cache entry reused". Verify that IntelliJ Platform Gradle Plugin 2.16.0 still works with the chosen Kotlin version — IGFP 2.x officially supports KGP 2.0+ so this upgrade should be compatible. Note: `org.gradle.caching=true` (task output cache) is already enabled in gradle.properties and is separate from configuration cache; both can coexist. Severity is downgraded to low because the configuration cache is opt-in, the project never declared it as a goal, and the existing build already benefits from Gradle's task output cache and parallel execution. The configuration-cache improvement is real but incremental on a project of this size.

**Verifier.** The actual code at build.gradle.kts line 5 reads `id("org.jetbrains.kotlin.jvm") version "1.9.25"`, and gradle.properties contains `org.gradle.caching=true` (build cache) but no `org.gradle.configuration-cache` flag. A configuration cache report exists at build/reports/configuration-cache/.../configuration-cache-report.html, generated by running `./gradlew help --configuration-cache`. The report shows exactly 1 problem with `cacheAction: storing`, sourced from `plugin 'org.jetbrains.kotlin.jvm'`: "Unsupported provider is registered as a task completion listener in org.gradle.build.event.BuildEventsListenerRegistry. Configuration Cache only supports providers returned from org.gradle.api.services.BuildServiceRegistry as task completion listeners." The cache directory contains only `.tmp` files (uncommitted entry), confirming the cache was not stored. The finding is accurate on all factual points. The only minor imprecision in the recommendation is that KGP 1.9.25 is actually the latest 1.9.x release — there is no later 1.9.2x patch that fixes this; the fix requires upgrading to KGP 2.0.0+.

**Evidence.** `./gradlew help --configuration-cache` -> 'Plugin 'org.jetbrains.kotlin.jvm': Unsupported provider is registered as a task completion listener in BuildEventsListenerRegistry. Configuration Cache only supports providers returned from BuildServiceRegistry'

---

#### LOW-14 · Kotlin Gradle Plugin 1.9.25 emits a Gradle-10-incompatible deprecation against pinned Gradle 9.0.0
`Build, plugin config, platform compat & deprecations` · category: build · effort: S · conf: 0.98 · verdict: confirmed  
**File:** `gradle/wrapper/gradle-wrapper.properties`  
**Location:** line 3 (gradle-9.0.0-bin.zip) + build.gradle.kts line 5  

**Problem.** Every build prints 'Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.' The stacktrace (`--warning-mode all --stacktrace`) shows the call originates in KGP 1.9.25: `org.jetbrains.kotlin.gradle.plugin.statistics.BuildFlowService` -> `DefaultConfigurationCacheStartParameterAccessor.isConfigurationCacheRequested` calling the removed `StartParameter.isConfigurationCacheRequested`. It is NOT from this project's build scripts. With Gradle pinned at 9.0.0 this is only a warning, but it is a tripwire: bumping the wrapper to Gradle 10 will break the build until Kotlin is upgraded.

**Impact.** No current breakage, but couples the project to Gradle <10 until Kotlin is upgraded; future Gradle wrapper bumps will hard-fail. Shares a root cause with the configuration-cache blocker, so one Kotlin upgrade fixes both.

**Recommendation.** The finding is accurate and the severity assessment (low, no current breakage) is correct. The deprecation fires on every build configure phase but does not affect correctness or performance today. The fix is straightforward: upgrade KGP from 1.9.25 to 2.0.x or later (e.g., 2.0.21 is the stable 2.0 series release that ships with IntelliJ Platform Gradle Plugin 2.x). KGP 2.x uses the replacement `BuildFeatures.configurationCache.requested` API and will eliminate this warning. Because IntelliJ Platform Gradle Plugin 2.16.0 already targets Kotlin 2.x internally, upgrading KGP is low-risk. After upgrading, run `./gradlew build --warning-mode all` and confirm the Gradle-10 incompatibility warning is gone. Do not bump the Gradle wrapper to 10 before the KGP upgrade is in place and the warning is gone.

**Verifier.** Both the file contents and a live build run confirm the finding exactly. gradle/wrapper/gradle-wrapper.properties pins Gradle 9.0.0 and build.gradle.kts line 5 declares KGP 1.9.25. Running `./gradlew compileKotlin --warning-mode all --stacktrace` reproduces the full stacktrace cited in the finding: `DefaultConfigurationCacheStartParameterAccessor.isConfigurationCacheRequested` -> `StartParameterInternal.isConfigurationCacheRequested` -> Gradle's `StartParameterDeprecations.nagOnIsConfigurationCacheRequested`, originating at `BuildFlowService$Companion.fusStatisticsAvailable(BuildFlowService.kt:54)` and `CompilerSystemPropertiesService$Companion.registerIfAbsent`. The warning fires during the configure phase on every build invocation. The Gradle daemon is running KGP 1.9.25 (verified jar in cache: `kotlin-gradle-plugin-1.9.25-gradle82.jar`), confirming this is not from project build scripts but from KGP internals. With the wrapper at Gradle 9.0.0 this is a warning only; bumping to Gradle 10 would hard-fail because the `StartParameter.isConfigurationCacheRequested` API is listed for removal in Gradle 10.

**Evidence.** stacktrace: 'StartParameter.isConfigurationCacheRequested ... scheduled to be removed in Gradle 10' at 'org.jetbrains.kotlin.gradle.plugin.statistics.BuildFlowService$Companion.fusStatisticsAvailable(BuildFlowService.kt:54)'

---

#### LOW-15 · untilBuild=261.* will lock the plugin out of IDE 2026.2 and later
`Build, plugin config, platform compat & deprecations` · category: build · effort: S · conf: 0.85 · verdict: needs-nuance · orig=medium  
**File:** `build.gradle.kts`  
**Location:** lines 38-41, pluginConfiguration.ideaVersion.untilBuild = "261.*"  

**Problem.** The plugin pins `untilBuild = "261.*"`, so 2026.1.x (build 261) is the last release that can install it. With today's date 2026-06-13, 2026.2 EAPs (build 262) are imminent and users on 2026.2+ will see 'incompatible with this version' and cannot install. The plugin uses no API that is known to break across minor releases (plain Swing UI, java.net.http client, stable services), so the cap mostly limits reach rather than protecting against real breakage. JetBrains' own guidance for plugins without a hard upper-bound need is to omit untilBuild (or set it generously).

**Impact.** Forward reach is capped just one release ahead of the current date; every new IDE release silently drops support and forces a plugin re-publish. For a browsing/management tool this is pure reach loss.

**Recommendation.** The cap is a real forward-reach issue but lower severity than claimed — no 2026.2 release exists yet, so no users are blocked today. The correct fix is a two-step process: (1) Check whether `HttpConfigurable` and the 4-arg `addBrowseFolderListener` are still present in IC/IU-2026.2 EAP builds (grep the platform JARs or check the API compatibility report). If they still exist, raise `untilBuild` to "262.*" (or omit it) and add an IC/IU-2026.2.x entry to `pluginVerification.ides`. (2) If those APIs are removed in 262+, migrate first: replace `HttpConfigurable` with `JdkProxyProvider.getInstance().proxySelector` (available since 243) and replace the 4-arg `addBrowseFolderListener` with the 2-arg form, then raise `sinceBuild` to "243" and drop the cap. Doing either step allows the plugin to reach 2026.2+ users. Do not simply remove `untilBuild` without first confirming deprecated APIs are still present — that risks shipping a broken plugin on IDEs where those APIs are already removed.

**Verifier.** The code at build.gradle.kts lines 38-41 exactly confirms `sinceBuild = "241"` and `untilBuild = "261.*"`. The finding is factually correct that this blocks installation on any build 262+ (2026.2 and later). However, the reviewer's claim that "the plugin uses no API that is known to break across minor releases" is materially wrong. The codebase intentionally uses two deprecated APIs behind @Suppress("DEPRECATION"): `HttpConfigurable` (deprecated in 2025.x+ in favor of `JdkProxyProvider`, absent before 243+) and the 4-arg `addBrowseFolderListener` overload (deprecated in newer platforms, absent before 243+). CLAUDE.md explicitly documents "7 deprecated/scheduled-for-removal API usages on newer IDEs are the deliberate 241-floor keeps." The `untilBuild = "261.*"` cap is therefore partly a safety cap — the plugin author is aware these APIs may be removed in future IDE builds — not purely a forgotten limit. That said, the cap is still overly conservative: the deprecated APIs are confirmed to still exist through 261 (2026.1), the verifyPlugin list includes IU-2026.1.3, and none of the @Suppress comments suggest the APIs are removed in 261. The cap does not protect against a real, imminent 261-era breakage; it is just the "current last major" at authoring time. The real risk is 262+, which is a genuine future reach issue. The severity is low-to-medium (not urgent today but will silently block new installs as 2026.2 releases), and the recommendation to drop `untilBuild` or raise the cap is sound — but the reviewer should have noted that doing so should be paired with either (a) verifying the deprecated APIs still exist in 262+, or (b) migrating them first.

**Evidence.** build.gradle.kts: `sinceBuild = "241"` / `untilBuild = "261.*"`; current date 2026-06-13 means 261 (2026.1) is the latest stable line.

---

#### LOW-16 · instrumentCode=true runs the form/NotNull instrumentation pass for an all-Kotlin, zero-form module
`Build, plugin config, platform compat & deprecations` · category: build · effort: S · conf: 0.90 · verdict: confirmed  
**File:** `build.gradle.kts`  
**Location:** line 34, intellijPlatform { instrumentCode = true }  

**Problem.** instrumentCode triggers IntelliJ's GUI-form (.form) binding and @NotNull bytecode instrumentation. I confirmed the module has zero .java and zero .form files (`find src -name '*.java' -o -name '*.form'` returns none) and uses only Kotlin. The instrumentation task therefore has nothing to instrument but still pulls in the instrumentation tooling and adds tasks (instrumentCode/instrumentTestCode) to the build graph on every build.

**Impact.** Wasted build-graph work and tooling resolution on each build with no behavioral benefit. Minor, but ties to the speed goal and removes a moving part.

**Recommendation.** Set `instrumentCode = false` in the `intellijPlatform` block. Since the project has zero `.java` files, zero `.form` files, and no `@NotNull`/`@Nonnull` annotations, neither of the two instrumentation passes (form binding and null-assertion injection) performs any work. The tasks still enter the build graph and the instrumentation tooling is still configured. Disabling it removes `instrumentCode` and `instrumentTestCode` from the task graph with no behavioral change. Run `./gradlew build` and `./gradlew verifyPlugin` to confirm. Only re-enable if Java sources or Swing form files are introduced in the future.

**Verifier.** The file at /Users/halmurat-max/IdeaProjects/rally-plugin/build.gradle.kts line 34 has `instrumentCode = true`. `find src -name '*.java' -o -name '*.form'` returns zero results — the entire source tree is 15 Kotlin source files plus XML resources. No `@NotNull`/`@Nonnull` annotations exist anywhere in the codebase. In IntelliJ Platform Gradle Plugin 2.x, `instrumentCode` enables two passes: Java Swing form (.form) binding compilation and `@NotNull`/`@Nullable` bytecode instrumentation for Java classes. Neither pass applies to Kotlin sources — Kotlin null-safety is enforced at compile time by the Kotlin compiler, not by bytecode instrumentation. The tasks `instrumentCode` and `instrumentTestCode` still appear in the build graph and the instrumentation tooling is still resolved, but they are no-ops on this codebase. The CLAUDE.md does not document this as an intentional trade-off. The finding is accurate: the setting delivers no benefit and adds a small amount of unnecessary build machinery.

**Evidence.** build.gradle.kts: `instrumentCode = true`; `find src -name '*.java'` -> 0 files, `find src -name '*.form'` -> 0 files (pure-Kotlin module).

---

#### LOW-17 · Four deliberate 241-floor deprecation keeps remain; raising the floor to 243 would clear most
`Build, plugin config, platform compat & deprecations` · category: build · effort: M · conf: 0.92 · verdict: needs-nuance  
**File:** `…/api/RallyApiClient.kt`  
**Location:** RallyApiClient.kt:58 (HttpConfigurable); RallySettingsConfigurable.kt:35 (addBrowseFolderListener 4-arg); RallyDetailPanel.kt:919 (FileSaverDescriptor vararg ctor); RallySettings.kt:152 (CredentialAttributes single-arg)  

**Problem.** The code carries four @Suppress("DEPRECATION") sites for APIs the plugin verifier still reports as deprecated/scheduled-for-removal on newer IDEs (CLAUDE.md notes 7 such usages total across IDE versions). The suppressions are correct and well-commented, and I verified the 2024.1 SDK has no replacement for HttpConfigurable→JdkProxyProvider (243+), the 2-arg addBrowseFolderListener (243+), or the FileSaverDescriptor builder (251+). CredentialAttributes(serviceName) must stay to avoid orphaning stored keys. The compile is clean (no kotlinc warnings) because suppressions hide them — but the verifier reads bytecode and will keep listing them. Note NotificationGroupManager...createNotification(title, content, type) at RallyApiClient.kt:872 is the deprecated 3-arg overload and is NOT suppressed, likely a contributor to the verifier's count.

**Impact.** These are scheduled-for-removal APIs; whichever future IDE actually removes one will break that code path (proxy resolution, folder/file choosers, attachment save). The 241 floor defers the work but keeps the liability. Pure compatibility/maintenance debt, not a current defect.

**Recommendation.** The four documented suppression sites are genuine, well-commented, and correctly justified — no action needed until the sinceBuild floor is raised. When raising to 243, migrate HttpConfigurable to JdkProxyProvider and the 4-arg addBrowseFolderListener to its 2-arg replacement in one pass. FileSaverDescriptor builder migration requires 251. The CredentialAttributes single-arg keep is intentional forever (changing the key would orphan stored credentials). Do NOT touch the createNotification(title, content, type) call at line 872 — bytecode analysis confirms this 3-arg overload is NOT deprecated in IntelliJ 2025.2 (only the subtitle-bearing overloads carry @Deprecated + @ScheduledForRemoval). Note also that there is a fifth @Suppress("DEPRECATION") at RallySettings.kt:42 (state.apiKey field access during migration) that was not counted in the review.

**Verifier.** The four documented @Suppress("DEPRECATION") sites are real and exactly as described (RallyApiClient.kt:58 for HttpConfigurable, RallySettingsConfigurable.kt:35 for 4-arg addBrowseFolderListener, RallyDetailPanel.kt:919 for FileSaverDescriptor vararg ctor, RallySettings.kt:152 for CredentialAttributes single-arg). All are properly commented with rationale and migration paths. This part is confirmed.

However, the reviewer's key secondary claim is wrong: the call at RallyApiClient.kt:872 uses createNotification(String title, String content, NotificationType) — the 3-arg overload where the second argument is content, not subtitle. Bytecode inspection of the IntelliJ 2025.2 NotificationGroup class shows this overload has no Deprecated or @ApiStatus$ScheduledForRemoval annotation. The deprecated 3-arg overloads are those involving a subtitle parameter: createNotification(String title, String subtitle, String content) and createNotification(String title, String subtitle, String content, NotificationType). The code at line 872 is already using the recommended non-deprecated form, so the reviewer's fix recommendation for that line is unnecessary and the claim that it "likely contributes to the verifier's count" is incorrect.

There is also a fifth @Suppress("DEPRECATION") at RallySettings.kt:42 (for state.apiKey field access during migration) that the reviewer did not enumerate, so the count of suppression sites is 5, not 4.

The severity and overall characterization (documented, accepted trade-off, maintenance debt, no current defect) are correct.

**Evidence.** RallyApiClient.kt:58 '// Migrate to JdkProxyProvider.getInstance().proxySelector once the floor is raised to 243+.'; RallyApiClient.kt:872 createNotification("Rally — partial query failure", ...) 3-arg form with no @Suppress.

---

#### LOW-18 · No @RequiresEdt/@RequiresBackgroundThread annotations; threading invariants enforced only by prose comments
`Build, plugin config, platform compat & deprecations` · category: concurrency · effort: M · conf: 0.95 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** whole codebase: grep for RequiresEdt/RequiresBackgroundThread returns 0 hits; threading rules live only in // comments (e.g. RallyToolWindowPanel.kt:890 'EDT-only', :172 'off-EDT')  

**Problem.** The plugin has an elaborate, correct-looking EDT/background split (invokeLaterIfAlive, executeOnPooledThread, @Volatile cross-thread fields), but the invariants are documented purely in comments. None of the methods carry com.intellij.util.concurrency.annotations.@RequiresEdt / @RequiresBackgroundThread. These annotations are checkable in the IDE (and by the platform's ThreadingAssertions in tests/internal mode), so without them a future refactor that calls an EDT-only method (e.g. renderSprintSummary) from a pooled thread, or reads Swing state off-EDT, gets no compile/inspection signal.

**Impact.** Quality/correctness risk over time rather than a current bug: the carefully-maintained threading contract is invisible to tooling, making regressions easy to introduce and hard to catch. These annotations are available in the 2024.1 floor, so adding them costs nothing in compatibility.

**Recommendation.** The finding is valid but the cost/benefit ratio is modest for a single-team plugin. If you do add annotations, prioritise the highest-traffic EDT-confined methods: `renderSprintSummary`, `updateListModel`, `updateStats`, `updateClientProjectRef`, and `showNotConfigured` should get `@RequiresEdt`; the pooled-thread body inside `loadTickets` and `loadSprintSummary` could get `@RequiresBackgroundThread` if extracted to named helpers. Do not annotate lambdas passed to `invokeLaterIfAlive` or `executeOnPooledThread` — the call site already enforces the contract. Adding annotations to 5–8 leaf methods without annotating their callers gives limited IDE signal because IntelliJ's inspection chain needs annotated callers to propagate the mismatch warning upward. Highest-value first step: annotate `renderSprintSummary` (most referenced EDT-only leaf) and see whether the IDE inspection immediately flags any suspicious call site — if not, the practical gain is documentation only.

**Verifier.** The grep for `RequiresEdt|RequiresBackgroundThread` returns zero results across the entire `src/` tree — confirmed. The threading contract is enforced only through prose comments. Line 890 reads `// EDT-only.` on `renderSprintSummary()`, lines 105–106 explain "`sprintIteration` is written on a pooled thread and read on the EDT (hence @Volatile); `displayedArtifacts` is EDT-confined", and line 172 reads `// Check configuration off-EDT`. The actual threading discipline is genuinely careful (`@Volatile` fields, `invokeLaterIfAlive` for every UI mutation, UI state captured on EDT before dispatching to the pooled thread at line 485–486), so there is no current threading bug. The concern is entirely about future-proofing: without `@RequiresEdt` on methods like `renderSprintSummary`, `updateListModel`, `updateStats`, and `@RequiresBackgroundThread` on query helpers, IntelliJ's threading inspections have nothing to cross-check against, and a refactor that misplaces a call gets no compile-time or inspection signal. The reviewer's description of the problem is accurate. The stated severity of low is correct — this is a maintainability gap, not a current defect, and the annotation benefit in a single-team private plugin is modest compared to a shared library.

**Evidence.** grep for 'RequiresEdt|RequiresBackgroundThread' across src/main/kotlin returns nothing; threading is described only in comments such as RallyToolWindowPanel.kt:890 '// EDT-only.'

---

#### LOW-19 · Tool-window icon ships only a light variant with hardcoded colors and no _dark.svg
`Build, plugin config, platform compat & deprecations` · category: ux · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `src/main/resources/icons/rally.svg`  
**Location:** icons/rally.svg (referenced by plugin.xml line 39 toolWindow icon)  

**Problem.** The tool-window icon is a single rally.svg with hardcoded fill colors (`fill="#00A4E0"` background, white 'R' text) and no rally_dark.svg companion. IntelliJ's icon theming convention picks up a `_dark` suffixed variant for dark IDE themes. There is no New-UI 20x20 / Retina handling either. The icon does render acceptably because the blue chip is theme-independent, but it ignores the platform icon-theming mechanism.

**Impact.** Cosmetic only; the icon is legible on both themes due to the opaque blue chip. Flagging for completeness since the dimension covers plugin.xml icon config.

**Recommendation.** The icon is confirmed as a single light-only SVG with no `_dark` companion. No action is required for correctness — the opaque blue chip renders fine on dark themes. If polish is desired: (1) add `rally_dark.svg` with the same design (or a slightly lighter blue) for completeness with IntelliJ's icon-theming convention, or (2) use IntelliJ's SVG palette-variable mechanism (`currentColor` / `$IDE_THEME_COLOR`) so a single file adapts to the LaF. Also note the icon is sized 16x16 with no `@2x` (32x32) Retina variant — add `rally@2x.svg` (and `rally_dark@2x.svg`) if HiDPI sharpness matters. All of this is optional given the chip-style design is inherently theme-agnostic.

**Verifier.** The SVG file at /Users/halmurat-max/IdeaProjects/rally-plugin/src/main/resources/icons/rally.svg contains exactly what the reviewer cited: an opaque blue rounded-rectangle chip (`fill="#00A4E0"`) with a white "R" text label. The icons/ directory contains only this single file — no `rally_dark.svg` companion exists. plugin.xml line 39 references `/icons/rally.svg` directly. IntelliJ's IconLoader does pick up a `_dark`-suffixed SVG when the IDE is in a dark LaF (Darcula/New UI dark), so the absence of `rally_dark.svg` means dark-theme customization is not provided. That said, the finding's own impact assessment is correct: the opaque blue chip is theme-neutral and renders legibly on both light and dark backgrounds without any dark-variant file, so this is purely cosmetic.

**Evidence.** icons/rally.svg: single file, `<rect ... fill="#00A4E0"/>` + `<text ... fill="white">R</text>`; no rally_dark.svg present in icons/ (ls shows only rally.svg).

---

#### › Concurrency correctness & races

#### LOW-20 · resolveInlineImages can submit to a shut-down imageExecutor after dispose()
`Concurrency correctness & races` · category: concurrency · effort: S · conf: 0.95 · verdict: needs-nuance  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** dispose() lines 143-152 (imageExecutor.shutdown()); resolveInlineImages lines 1060-1090 (CompletableFuture.supplyAsync(..., imageExecutor))  

**Problem.** dispose() sets disposed=true, bumps generation, then calls imageExecutor.shutdown(). A description render already running on a pooled thread (loadAndRenderDescription) can reach resolveInlineImages and call CompletableFuture.supplyAsync(block, imageExecutor) AFTER shutdown(). shutdown() rejects new submissions, so supplyAsync throws RejectedExecutionException synchronously. The generation check at resolveInlineImages line 1062 is INSIDE the submitted block — it never runs because submission itself fails before the block executes.

**Impact.** No crash: the RejectedExecutionException propagates out of resolveInlineImages and is caught by loadAndRenderDescription's outer `catch (t: Exception)` (line 529), logged as 'Detail panel description update failed'. So the only effect is a spurious WARN in idea.log at panel close while a description was loading. Harmless but noisy, and relies on the broad catch.

**Recommendation.** The finding is confirmed but the impact is slightly better than claimed: the RejectedExecutionException is caught at the inner try/catch at line 510 (not the outer catch at line 529), so the description is still displayed — just without inline images — and the WARN says "Failed to resolve inline images" rather than "Detail panel description update failed".

The simplest fix is to add a guard at the top of resolveInlineImages before any supplyAsync call:

```kotlin
private fun resolveInlineImages(html: String, client: RallyApiClient, gen: Long): String {
    if (disposed || generation.get() != gen) return html
    // ... existing Phase 1 match collection ...
    if (matches.isEmpty()) return html
    if (disposed || generation.get() != gen) return html  // re-check after collection
    val futures = matches.map { match ->
        CompletableFuture.supplyAsync({ ... }, imageExecutor)
    }
    ...
}
```

Alternatively, wrap the supplyAsync call itself in a try/catch for RejectedExecutionException that returns a completed future with null, matching the existing null-means-skip convention. Either approach eliminates the spurious WARN without altering the documented shutdown() vs shutdownNow() trade-off.

**Verifier.** The race condition is real and present in the code exactly as described: dispose() sets disposed=true, bumps the generation counter, and calls imageExecutor.shutdown() (lines 143-151). A loadAndRenderDescription task already running on a pooled thread can reach resolveInlineImages and call CompletableFuture.supplyAsync({...generation check inside block...}, imageExecutor) at line 1089 AFTER shutdown(). shutdown() rejects new submissions; the generation check at line 1062 is inside the lambda and never runs because task submission itself throws RejectedExecutionException.

However, the reviewer's claimed impact location is wrong. The exception is NOT caught by the outer catch at line 529. Instead, the call site at line 510 wraps resolveInlineImages in its own try/catch: `try { resolveInlineImages(resolvedNonNull, client, gen) } catch (e: Exception) { LOG.warn("Failed to resolve inline images for $id", e); resolvedNonNull }`. So the RejectedExecutionException is caught there, logged as a WARN for "Failed to resolve inline images", and the description is displayed without inline images (not propagated to "Detail panel description update failed"). The actual behavior under the race: description loads but images are missing, plus a WARN in the log. This is slightly more graceful than claimed (description still shown, just without images), but the spurious warning and image loss are real.

The comment in dispose() itself (lines 146-150) documents why shutdown() not shutdownNow() was chosen: shutdownNow() would drain queued tasks so their CompletableFutures never complete, parking the pooled thread on join() forever. This is a deliberate trade-off. But there is no guard preventing NEW supplyAsync submissions after shutdown, which the comment does not address.

**Evidence.** imageExecutor.shutdown()  // ... CompletableFuture.supplyAsync({ if (generation.get() != gen) return@supplyAsync null ... }, imageExecutor)

---

#### LOW-21 · getCached returns the live cached collection reference to callers
`Concurrency correctness & races` · category: concurrency · effort: S · conf: 0.80 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** getCached lines 194-205; cache hits in queryTasksForWorkProduct line 1304, queryTestCases line 1331, queryAttachments line 1389, searchArtifacts line 899  

**Problem.** getCached returns `entry.data as? T` — the exact List instance stored by putCache. Several callers return that list directly to UI code (e.g. queryTasksForWorkProduct returns the cached list, RallyDetailPanel.showArtifact does taskListModel.addAll(tasks)). The cached lists are produced by sortedByDescending/safeResults (effectively immutable today), so no caller currently mutates them. However the contract is unenforced: a single future caller doing an in-place sort/removeAll on a returned list would corrupt every subsequent cache hit across all threads with no synchronization.

**Impact.** Latent shared-mutable-state hazard. Today benign because all consumers treat results as read-only and rebuild via copies (listModel.addAll iterates, allArtifacts uses listOf(...) + ...). The risk is a maintenance trap: the cache hands out aliases under no lock.

**Recommendation.** The most defensive fix is to wrap at `putCache` time rather than at each call site. Change `putCache` to wrap list values: if `data is List<*>`, store `Collections.unmodifiableList(data)` instead. This makes accidental mutation fail fast with `UnsupportedOperationException` at the call site of the offending code rather than silently corrupting all cache readers. Alternatively, since all produced lists are either Gson ArrayLists or `sortedByDescending` results, callers can wrap at the point of storage: `putCache(cacheKey, result.queryResult.safeResults.toList())` — `toList()` returns a new read-only Kotlin list backed by an array. The `toList()` approach is lower friction (no import, idiomatic Kotlin) and adds negligible overhead for the typical list sizes here (tens to low hundreds of items). A KDoc comment on `getCached` noting "returned list is read-only; do not mutate" is insufficient on its own because it is not enforced by the type system.

**Verifier.** The code confirms the finding. `getCached` at line 203 returns `entry.data as? T` — the exact reference stored by `putCache`. Callers like `queryTasksForWorkProduct` (line 1304), `queryTestCases` (line 1331), `queryAttachments` (line 1389), and `searchArtifacts` (line 899) all hit the cache and return that reference directly. The stored lists are either Gson-deserialized `ArrayList` instances (from `safeResults`, which is `results ?: emptyList()` where Gson populates `results` as a mutable `ArrayList`) or lists produced by `sortedByDescending` (which in Kotlin returns a new `ArrayList` — also mutable). The `synchronized(queryCache)` block guards only the map lookup; it releases the lock before the caller receives the list, so any post-return mutation would be unguarded. All current call sites are read-only: `taskListModel.addAll(tasks)`, `testCaseListModel.addAll(testCases)`, `attachmentListModel.addAll(attachments)` iterate without mutating; `allArtifacts = allArtifacts.map { ... }` creates a new list rather than sorting in place. The contract is de facto but unenforced — a future caller calling `.sortWith()` or `.removeAll()` on a returned list would corrupt all subsequent cache reads across threads with no compile-time or runtime guard. The severity is correctly assessed as low (latent, no current exploit path).

**Evidence.** return entry.data as? T   // ... putCache(cacheKey, result.queryResult.safeResults); return result.queryResult.safeResults

---

#### LOW-22 · lastPartialFailureNotifyMs throttle is a non-atomic check-then-set across apiExecutor threads
`Concurrency correctness & races` · category: concurrency · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** notifyPartialFailure lines 859-868  

**Problem.** `lastPartialFailureNotifyMs` is @Volatile, but the throttle does read-compare-write (`if (now - lastPartialFailureNotifyMs < 60_000) return; lastPartialFailureNotifyMs = now`) without atomicity. queryAllArtifacts and searchArtifacts both call this, and searchArtifacts can run on multiple apiExecutor threads concurrently (parallel stories+defects, debounced searches). Two threads can both pass the 60s check before either writes, raising two balloons.

**Impact.** Cosmetic: at most a couple of duplicate WARNING balloons within the throttle window during a degraded-endpoint burst. No data/state corruption.

**Recommendation.** Replace `@Volatile private var lastPartialFailureNotifyMs = 0L` with `private val lastPartialFailureNotifyMs = java.util.concurrent.atomic.AtomicLong(0L)` and change the throttle check to `val prev = lastPartialFailureNotifyMs.get(); if (now - prev < 60_000 || !lastPartialFailureNotifyMs.compareAndSet(prev, now)) return`. The `compareAndSet` ensures only one thread wins the race window; others see the updated timestamp and return early. This is a one-line logic change with no behavioral impact under normal (non-degraded) conditions.

**Verifier.** The code at lines 859-868 of RallyApiClient.kt exactly matches the claim. `lastPartialFailureNotifyMs` is declared `@Volatile private var lastPartialFailureNotifyMs = 0L`, and `notifyPartialFailure` performs a non-atomic read-compare-write: `val now = System.currentTimeMillis(); if (now - lastPartialFailureNotifyMs < 60_000) return; lastPartialFailureNotifyMs = now`. There is no `synchronized` block, `AtomicLong`, or any other guard protecting the throttle. `@Volatile` only provides visibility, not atomicity of the compound check-then-set.

Two execution paths can reach this function concurrently: (1) `queryAllArtifacts` called via `CompletableFuture.supplyAsync` (line 541 in RallyToolWindowPanel), and (2) `searchArtifacts` called via `ApplicationManager.getApplication().executeOnPooledThread` (line 934). Both can pass the 60-second guard before either writes the new timestamp, resulting in duplicate balloons.

The reviewer's attribution of the concurrency to "multiple apiExecutor threads" is slightly imprecise — `searchArtifacts` runs on IntelliJ's unbounded pooled thread, not directly on `apiExecutor`, and within `searchArtifacts` the user-story/defect queries are sequential. But the race itself is real: debounced keystrokes can produce overlapping `executeOnPooledThread` invocations, and `queryAllArtifacts` can run simultaneously. The impact is purely cosmetic (duplicate WARNING balloons); no data or state is corrupted. Severity low is appropriate.

**Evidence.** val now = System.currentTimeMillis(); if (now - lastPartialFailureNotifyMs < 60_000) return; lastPartialFailureNotifyMs = now

---

#### LOW-23 · Optimistic create prepend races with an in-flight loadTickets that overwrites allArtifacts
`Concurrency correctness & races` · category: concurrency · effort: M · conf: 0.82 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** showCreateDefectDialog lines 1141-1146 and showCreateUserStoryDialog lines 1395-1400 (allArtifacts = listOf(created) + allArtifacts); loadTickets commit lines 565-566 (allArtifacts = filtered)  

**Problem.** Both the create-success invokeLater and loadTickets's commit invokeLater run on the EDT and write `allArtifacts`. They are mutually exclusive on the EDT, so there is no data race. The ordering hazard: if a loadTickets background fetch was already in flight when the create completes, the create's optimistic prepend runs first on the EDT, then loadTickets's later invokeLater commits `filtered` (which does not include the just-created item because the server query predates it, and clearArtifactCache was called but the in-flight fetch already had its results), overwriting the optimistic row. The new item silently vanishes from the list until the next manual refresh.

**Impact.** User creates a story/defect, sees it appear and selected, then a concurrent/just-finishing refresh drops it from the visible list. Low frequency (requires an overlapping reload) and self-heals on next load, but it is a confusing 'my new ticket disappeared' UX bug.

**Recommendation.** Add an `artifactListGeneration` AtomicLong (mirroring the existing `iterationLoadGeneration`) to RallyToolWindowPanel. In `loadTickets`, snapshot the generation before launching the background thread and check it inside the `invokeLaterIfAlive` commit block — if it no longer matches, discard the stale result. Increment `artifactListGeneration` in the create-success handler before calling `applySearchFilter()`, after the optimistic prepend, so any concurrently-completing `loadTickets` sees the increment and skips its write. This is the same pattern already used for iterations. A simpler but less elegant alternative: after the optimistic prepend in the create handler, set `pendingReload = true` (if `loading` is still true) so that when `loadTickets` commits it immediately schedules another fresh fetch that will include the new item.

**Verifier.** The race is real and present exactly as described. The `loadTickets` background thread captures its `filtered` list locally and posts `invokeLaterIfAlive { allArtifacts = filtered; loading = false }` to the EDT. The create-success handler also posts `invokeLaterIfAlive { allArtifacts = listOf(created) + allArtifacts; client.clearArtifactCache() }`. Both posts run exclusively on the EDT, but EDT event ordering is not guaranteed relative to each other when posted from two independent background threads. If the create's invokeLater fires first (create API call completes before the in-flight loadTickets fetch), the optimistic prepend happens, then `clearArtifactCache()` is called. Then the loadTickets invokeLater fires and executes `allArtifacts = filtered` — where `filtered` was computed before the create existed on the server (and before the cache was cleared), so the new item is not in it. This clobbers the optimistic row. No generation counter guards the `allArtifacts` write in `loadTickets`; only an `iterationLoadGeneration` AtomicLong exists for the iterations dropdown. The `loading`/`pendingReload` flag only prevents a new `loadTickets` from starting concurrently — it does not protect against a previously-started in-flight load's EDT commit overwriting a subsequent optimistic update. After the clobber, no automatic reload is triggered (there is no `loadTickets()` call after the optimistic prepend), so the user must manually refresh. The scenario is most likely at plugin startup or after a project switch, when `loadTickets` is slowest and a user could open a Create dialog while the initial fetch is still in flight.

**Evidence.** allArtifacts = listOf(created as RallyArtifact) + allArtifacts; client.clearArtifactCache(); applySearchFilter()  // vs loadTickets: allArtifacts = filtered

---

#### LOW-24 · lastSettingsSnapshot check-then-set runs only on apiExecutor but is shared mutable state
`Concurrency correctness & races` · category: concurrency · effort: S · conf: 0.85 · verdict: needs-nuance  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** loadTickets lines 497-502; field decl line 145  

**Problem.** `lastSettingsSnapshot` (@Volatile) is read-compared-then-written inside loadTickets's executeOnPooledThread block (`if (!projectsLoaded || snapshot != lastSettingsSnapshot) { lastSettingsSnapshot = snapshot; loadProjects(...) }`). loadTickets cycles are serialized by the EDT-confined loading/pendingReload flags, so only one loadTickets background task runs at a time — making this effectively single-threaded. It is safe ONLY because of that external serialization invariant, which is not local to this code.

**Impact.** No bug today. Flagged as a fragile invariant: if loadTickets's serialization (loading flag) is ever relaxed or another path writes lastSettingsSnapshot, this becomes a lost-update race that could skip a needed loadProjects after a credential change.

**Recommendation.** The check-then-set on `lastSettingsSnapshot` is safe as-is because the `loading` flag structurally serializes all `loadTickets` background tasks: `loading` is set to `true` on the EDT before the async dispatch and can only be cleared back to `false` on the EDT via `invokeLaterIfAlive`, and every call site of `loadTickets()` is EDT-confined (making concurrent invocations architecturally impossible, not just accidentally avoided). Do NOT move this under `clientLock` — that lock guards the `currentClient` reference and would not address the stated concern; it would just add unnecessary contention. The correct action is a one-line comment at the `lastSettingsSnapshot` field declaration: `// Mutation is single-threaded in practice: loadTickets() is EDT-confined and the loading flag ensures only one background task runs at a time.` This documents the invariant for future maintainers without any runtime change.

**Verifier.** The cited code is real and accurately described: `lastSettingsSnapshot` is `@Volatile` and the check-then-set pattern at lines 498-499 runs inside `executeOnPooledThread` without an explicit lock. The reviewer is correct that safety depends on the invariant that only one `loadTickets` background task runs at a time, enforced by the `@Volatile loading` flag set to `true` on the EDT before dispatch and reset to `false` only via `invokeLaterIfAlive` (back on EDT). All `loadTickets()` call sites confirmed as EDT-only (lines 177, 202, 348, 354, 367, 378, 578, 604). The comment at line 591 even explicitly names this invariant: "loadTickets cycles are serialized by the loading/pendingReload flags."

However, the severity and framing need correction. This is not a "fragile" invariant in the sense of being easy to accidentally violate. The `loading` guard is structural: `loadTickets()` is only called from the EDT, `loading=true` is set synchronously on EDT before the async dispatch, and `loading=false` can only be set on the EDT via `invokeLaterIfAlive`. Any future caller of `loadTickets()` from off-EDT would have different problems (Swing state reads, EDT-only combo-box reads, etc.) that would surface immediately. The recommendation to move the snapshot compare under `clientLock` is incorrect — `clientLock` guards the `currentClient` reference, not the loading-cycle serialization, so that would not address the concern. The correct fix is documentation only: a comment at the `lastSettingsSnapshot` field declaration noting that mutation is safe without an additional lock because `loadTickets()` background tasks are serialized by the `loading` flag and all `loadTickets()` entry points are EDT-confined.

**Evidence.** if (!projectsLoaded || snapshot != lastSettingsSnapshot) { lastSettingsSnapshot = snapshot; loadProjects(client); invalidateIterations() }

---

#### LOW-25 · RallyDetailPanel.disposed correctly @Volatile; clear()/showArtifact mutate Swing models off the guarantee
`Concurrency correctness & races` · category: concurrency · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** dispose lines 143-152; showArtifact line 299; clear line 535; loadAndRenderDescription lines 516/524  

**Problem.** disposed is @Volatile and checked at the top of showArtifact/clear and inside every invokeLater, which is correct. One subtlety: dispose() runs on the EDT (called from RallyToolWindowPanel.dispose -> detailPanel.dispose), and showArtifact/clear are EDT-only, so the disposed checks and the generation bump are serialized on the EDT. The background blocks only ever touch Swing via invokeLater, all of which re-check disposed + generation. This path is sound; verified no off-EDT Swing model mutation exists.

**Impact.** No defect found here — included as a positive confirmation that the disposal/generation design in the detail panel is internally consistent (volatile flag + generation re-check on every EDT hop, shutdown() chosen over shutdownNow() to avoid orphaned joins).

**Recommendation.** No correctness change is required. The disposal/generation pattern is sound. The optional improvement the reviewer mentioned — adding an EDT assertion (`ApplicationManager.getApplication().assertIsDispatchThread()`) at the top of `showArtifact` and `clear` — would make the EDT-only contract explicit and catch any future caller regressions at development time. This is purely a defensive measure and low priority. If the team wants extra safety, add the assertion; otherwise leave as-is.

**Verifier.** The actual code confirms the reviewer's positive assessment. All cited lines behave exactly as described:

- `dispose()` (lines 143-152): sets `disposed = true`, bumps `generation`, calls `imageExecutor.shutdown()`. No `shutdownNow()` — correctly preserving queued task completion so no `CompletableFuture` parks a shared thread indefinitely.
- `showArtifact` (line 299): opens with `if (disposed) return` — pure EDT call.
- `clear()` (line 535): opens with `if (disposed) return`, then mutates Swing models directly (lines 540-558) on the EDT.
- `loadAndRenderDescription` (lines 516/524): checks `generation.get() == gen && !disposed` before the expensive `wrapHtml` call, then wraps the actual Swing mutation (`descriptionPane.text = wrapped`) inside `invokeLater` with a re-check of `generation.get() != gen || disposed`.

Since `dispose()`, `showArtifact()`, and `clear()` are all called on the EDT, and background threads touch Swing only via `invokeLater` (which re-checks both `generation` and `disposed`), there is no window where a background task can observe a stale `disposed=false` and still write to the UI after disposal. The design is internally consistent. The finding is "confirmed" in the sense that it is an accurate positive (no-bug) characterization of the code: the reviewer correctly concluded no defect exists and the disposal/generation pattern is sound.

**Evidence.** @Volatile private var disposed = false ... override fun dispose() { disposed = true; generation.incrementAndGet(); imageExecutor.shutdown() }

---

#### LOW-26 · Image-cache eviction allocates a fresh iterator per evicted entry
`Concurrency correctness & races` · category: performance · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** downloadAttachment, eviction loop lines 1497-1503  

**Problem.** The eviction while-loop calls `imageCache.entries.iterator()` and reads `iter.next()`/`iter.remove()` on a brand-new iterator each loop pass, evicting exactly one entry per iteration. It is correct (the block holds the imageCache lock), but reconstructing the entry set view + iterator on every eviction is wasteful when several large images must be dropped to make room.

**Impact.** Minor: under cache pressure (a multi-MB image arriving with a near-full 10 MB cache) it does O(k) iterator allocations to evict k entries instead of reusing one iterator. Not a correctness issue; trivial GC churn on a cold path.

**Recommendation.** Hoist the iterator outside the while-loop and use `iter.hasNext()` as the loop's termination guard instead of `imageCache.isNotEmpty()`:

```kotlin
val iter = imageCache.entries.iterator()
while (imageCacheBytes.get() + incoming > maxImageCacheBytes && iter.hasNext()) {
    val eldest = iter.next()
    iter.remove()
    imageCacheBytes.addAndGet(-eldest.value.size.toLong())
}
```

This removes the redundant per-pass iterator allocation and also eliminates the now-dead `if (!iter.hasNext()) break` guard (the while-condition subsumes it). Since the map is access-ordered, the iterator still yields LRU entries first. The change is safe: the entire block is inside `synchronized(imageCache)` so no other thread can mutate the map while the iterator is live.

**Verifier.** The actual code at lines 1497-1503 matches the finding exactly. Inside the `synchronized(imageCache)` block, the while-loop condition checks `imageCache.isNotEmpty()` but then immediately creates a brand-new `imageCache.entries.iterator()` on every pass, advances it exactly once with `iter.next()`, calls `iter.remove()`, and discards the iterator. A single eviction loop that needed to drop k entries would allocate k separate iterator objects. The code is correct (the redundant `if (!iter.hasNext()) break` guard is actually dead code given the while-condition already checks `isNotEmpty()`), but the iterator is reconstructed unnecessarily on every pass.

Mitigating factors: (1) this path only executes when the 10 MB image cache is near capacity, which is rare; (2) the per-image cap is 1 MB, so at most ~10 entries need evicting in the absolute worst case; (3) the whole block is inside a lock so there's no concurrency concern. The GC churn is genuinely trivial in practice, making "low" the correct severity. The reviewer's characterization is accurate and their recommended fix (hoist the iterator outside the while-loop and use `iter.hasNext()` as part of the loop condition) is the standard approach for this pattern.

**Evidence.** while (imageCacheBytes.get() + incoming > maxImageCacheBytes && imageCache.isNotEmpty()) { val iter = imageCache.entries.iterator(); if (!iter.hasNext()) break; val eldest = iter.next(); iter.remove(); ... }

---

#### › Error handling, silent failures & API correctness

#### LOW-27 · RallyGitOps writes error/result from a pooled thread read on the calling thread without happens-before guarantee beyond the latch
`Error handling, silent failures & API correctness` · category: concurrency · effort: S · conf: 0.85 · verdict: confirmed  
**File:** `…/util/RallyGitOps.kt`  
**Location:** createOrCheckoutBranch 59-100  

**Problem.** `var error: String?` is assigned inside the verifyCheckout Runnable that runs on executeOnPooledThread (line 67/70), and read by the caller after `latch.await()` (line 100). The CountDownLatch DOES establish happens-before from countDown to await, so the read of `error` is correctly visible — this part is fine. The subtler issue: if the brancher's completion callback (createBranch/checkout) is never invoked (e.g. the user cancels a VCS background task, or checkout silently no-ops), `verifyCheckout` never runs, the latch never counts down, and the method blocks the full 30s before returning 'Branch operation timed out' — during which the caller's pooled thread is parked. Start Working then reports a timeout for what may have been a user cancel.

**Impact.** Low: a 30s freeze of the Start Working flow on an uncommon path (VCS task cancel / no-op checkout), reported as a timeout. The state-change to In-Progress is correctly gated on branchSucceeded so no Rally mutation happens — good. Mostly a responsiveness/diagnostics concern.

**Recommendation.** The 30s stall is real but the fix is straightforward. Two concrete improvements: (1) Shorten the timeout — 10s is ample for a local Git checkout; 30s is unusually long and makes the UI feel frozen. (2) Improve the message: distinguish "VCS task completed but wrong branch" (the existing mismatch check) from "callback never fired" (the timeout case) — e.g., return "Branch operation did not complete (VCS task may have been cancelled)" on timeout. Optionally, before starting the brancher operation, snapshot `repo.currentBranchName`; if the latch times out and the branch now matches (race where callback was skipped but checkout succeeded), return success rather than an error. No architectural change is needed — the CountDownLatch pattern is correct and the Rally mutation gate is sound.

**Verifier.** The code at lines 49-101 matches the reviewer's description exactly. `verifyCheckout` (lines 61-75) wraps `latch.countDown()` inside a `finally` block, but that `finally` only executes if the inner `executeOnPooledThread` lambda is actually invoked — which only happens if `GitBrancher` fires the completion callback Runnable. The catch block at lines 91-95 guards against synchronous throws from the brancher API itself and correctly calls `latch.countDown()` there, but it cannot catch a silent non-invocation of the callback by a background task (e.g., user cancels the VCS progress task, or Git4Idea swallows the callback on internal failure). In that case `latch.countDown()` is never called, and `latch.await(30, TimeUnit.SECONDS)` (line 97) parks the calling pooled thread for the full 30 seconds before returning the generic "Branch operation timed out" message. The reviewer's happens-before note is correct: CountDownLatch does guarantee visibility of `error` after `await` returns, so that part is not a bug. The real issue is purely the 30s stall and the diagnostic opacity of the timeout message. Severity is correctly assessed as low: the path requires the user to cancel a VCS background task mid-flight, no Rally state mutation occurs (branchSucceeded check gates that), and the consequence is only a 30s delay with a misleading message.

**Evidence.** Line 97-99: `if (!latch.await(30, TimeUnit.SECONDS)) { return "Branch operation timed out" }`; latch counts down only inside verifyCheckout (line 72), which only runs if a brancher callback fires.

---

#### LOW-28 · getAttachmentContent / create-result paths don't distinguish a 200-with-Errors content fetch from missing content
`Error handling, silent failures & API correctness` · category: correctness · effort: S · conf: 0.95 · verdict: confirmed · orig=medium  
**File:** `…/api/RallyApiClient.kt`  
**Location:** getAttachmentContent 1446-1455  

**Problem.** getAttachmentContent calls handleResponse (HTTP-status only) then reads `json.getAsJsonObject("AttachmentContent")`. If Rally returns a 200 with an Errors array instead of an AttachmentContent object (e.g. permission or a stale Content ref), the method throws the generic 'No AttachmentContent in response' rather than surfacing the real Rally error text, which is in the unparsed Errors array. The same is true of the read side of the attachment download in the exporter, which falls back to base64 and then can fail opaquely.

**Impact.** Quality/diagnosability: when an attachment download fails for a permission/scoping reason, the user gets 'No AttachmentContent in response' with the actual cause discarded, making it hard to know whether it's a bug or an access issue. Lower severity than the query case because it does at least throw rather than silently empty.

**Recommendation.** After `handleResponse(response)` and before the `AttachmentContent` lookup, add a top-level Errors check consistent with the mutation paths:

```kotlin
val json = JsonParser.parseString(response.body()).asJsonObject
val topErrors = json.getAsJsonArray("Errors")
if (topErrors != null && topErrors.size() > 0) {
    throw RallyApiException("Rally returned errors for attachment content fetch: ${topErrors.joinToString()}")
}
val contentObj = json.getAsJsonObject("AttachmentContent")
    ?: throw RallyApiException("No AttachmentContent in response")
```

This brings `getAttachmentContent` in line with every mutation path in the same file. No changes to `handleResponse` are needed — the pattern of checking body-level Errors after a 200 is already the established idiom here. The fix is a one-time 3-line addition.

**Verifier.** The actual code at lines 1446–1455 confirms the finding. `handleResponse` (lines 480–504) is a pure HTTP-status checker: it returns immediately on status 200 without inspecting the JSON body at all. After `handleResponse`, `getAttachmentContent` parses the body and looks only for `AttachmentContent`. If Rally returns a 200 with an `Errors` array (which it does for permission denials, stale refs, and similar application-level failures on its object-fetch endpoints), the code jumps straight to `json.getAsJsonObject("AttachmentContent")` which returns null, and throws the generic "No AttachmentContent in response" message — discarding the actual Rally error text.

By contrast, all mutation paths in the same file (updateArtifactState at 1143-1145, updateArtifactOwner at 1164-1166, updateArtifactField at 1186-1188, uploadAttachment at 1647-1672) DO check `result.getAsJsonArray("Errors")` after `handleResponse`. The inconsistency is real and deliberate elsewhere in the file — but missing here.

The severity is low rather than medium: this is a diagnosability/quality issue only. The method does throw (not silently swallow), the stack trace plus the "No AttachmentContent in response" message will surface in a balloon/log and tell a developer exactly which code path failed, and the Errors text in Rally's response for a stale-ref failure is typically something like "Cannot find object to read" — useful but not critical. The user's experience is the same either way: attachment fails to load. No data is lost or silently corrupted, and the path is cold (only triggered on permission or stale-ref conditions, not normal use).

**Evidence.** Line 1451-1452: `val contentObj = json.getAsJsonObject("AttachmentContent") ?: throw RallyApiException("No AttachmentContent in response")` — no Errors-array inspection.

---

#### LOW-29 · Retry-After HTTP-date form is silently ignored, falling back to backoff that may undershoot the server's requested wait
`Error handling, silent failures & API correctness` · category: correctness · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** retryAfterMillis 310-314, used at 1074-1076  

**Problem.** retryAfterMillis only parses the delta-seconds form (`toLongOrNull()`); the RFC 7231 HTTP-date form returns null and the loop falls back to exponential backoff (max ~8s + jitter, capped at MAX_RETRY_DELAY_MS=30s). This is documented as deliberate to avoid NumberFormatException. The trade-off worth revisiting: if a real Rally/CDN sends an HTTP-date Retry-After asking for, say, 45 seconds, the client retries far sooner and burns all 3 retries inside the requested window — effectively ignoring a legitimate 503/429 cooldown and hammering a server that explicitly asked to wait.

**Impact.** Low in practice (Rally's own WSAPI sends delta-seconds), but behind a reverse proxy / WAF that emits HTTP-date 429s, retries are wasted and the request fails faster than necessary. Performance-adjacent: needless retries against a rate-limited server.

**Recommendation.** The behavior is intentional, tested, and documented, so no fix is required for Rally WSAPI usage. If proxy/WAF resilience is desired, add HTTP-date parsing after the delta-seconds fast path: attempt `DateTimeFormatter.RFC_1123_DATE_TIME.parse(headerValue.trim())`, compute `ChronoUnit.SECONDS.between(ZonedDateTime.now(), parsed)`, clamp to [1, 86400], multiply by 1000, and return that — with the existing null fall-through on any parse failure. The unit-test at RallyApiClientCompanionTest line 125-128 already asserts null for this case, so flipping it to assert a positive value would complete the coverage without any structural change to the retry loop.

**Verifier.** The code at lines 310-314 does exactly what the finding claims: `retryAfterMillis` only handles delta-seconds via `toLongOrNull()`. Any HTTP-date string (e.g., "Wed, 10 Jun 2026 12:00:00 GMT") yields `null`, and the caller at line 1075 then falls back to `backoffMs(attempt)` (exponential: ~1s/2s/4s+jitter, capped at 8s per attempt, total capped at MAX_RETRY_DELAY_MS=30s). The behavior is explicitly unit-tested (RallyApiClientCompanionTest line 125-128 asserts `assertNull` for an HTTP-date value) and documented in CLAUDE.md ("delta-seconds; HTTP-date falls back to backoff"). With MAX_RETRIES=3 and backoff of roughly 1+2+4s, all 3 retries could be exhausted in under 10s against a WAF that requested 45s. The finding's framing is accurate. The severity assessment of "low" is appropriate — Rally WSAPI itself uses delta-seconds and this is a proxy/WAF edge case — but the trade-off is real and the code has a clear unit-test seam for adding HTTP-date support.

**Evidence.** Line 311: `headerValue?.trim()?.toLongOrNull()` — any non-numeric (HTTP-date) value yields null; KDoc explicitly notes the date form 'falls back to exponential backoff'.

---

#### LOW-30 · Server search iteration/owner client-side re-filter drops artifacts when iteration name resolves via neither name nor refObjectName
`Error handling, silent failures & API correctness` · category: correctness · effort: M · conf: 0.20 · verdict: needs-nuance  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** applySearchFilter server-side branch 945-954  

**Problem.** The server-search iteration filter resolves `iterName` from `artifact.iteration?.name ?: artifact.iteration?.refObjectName`. List queries fetch `Iteration` (LIST_FIELDS includes 'Iteration') but Rally returns iteration as a ref object whose human name is typically in `_refObjectName`, not `Name` (the RallyRef.name field is only populated when Name is explicitly fetched on the nested object). If both are null/blank for a matching ticket, the equality check fails and the ticket is silently filtered OUT of server-search results even though it belongs to the selected sprint.

**Impact.** Server-side search (the fallback that finds tickets outside the loaded list) can under-return when a sprint filter is active and the iteration ref lacks a populated name — the user searches, gets fewer/zero hits, and never learns the filter discarded valid matches. Low severity because it only affects the search-with-active-sprint-filter path and depends on Rally's ref population.

**Recommendation.** The current code works correctly in practice: Rally always populates `_refObjectName` on ref objects returned in list queries, so `artifact.iteration?.refObjectName` reliably carries the sprint name. No urgent fix is needed. As a robustness improvement, consider adding a server-side `(Iteration.Name = "...")` clause to `searchArtifacts` (or its caller) when an iteration filter is active — mirroring what `buildQuery()` already does at line 794. This would eliminate the client-side re-filter entirely for the sprint dimension and make the search path consistent with the list path. It also avoids the theoretical risk of a future API response where `_refObjectName` is absent on an iteration ref.

**Verifier.** The cited code at lines 947-952 is real and the structure matches the claim:

```kotlin
val iterName = when (artifact) {
    is RallyUserStory -> artifact.iteration?.name ?: artifact.iteration?.refObjectName
    is RallyDefect -> artifact.iteration?.name ?: artifact.iteration?.refObjectName
    else -> null
}
iterName?.equals(iterFilter, ignoreCase = true) == true
```

However, the reviewer's framing is inverted. `RallyRef.name` is `@SerializedName("Name")` and `RallyRef.refObjectName` is `@SerializedName("_refObjectName")`. In Rally WSAPI, when you add `"Iteration"` to the fetch list, the nested Iteration object in each result carries `_refObjectName` (populated by Rally automatically on all ref objects) but NOT `Name` (that would require the nested object to be fully hydrated). So `artifact.iteration?.name` is almost always null, and `artifact.iteration?.refObjectName` carries the actual sprint name. The `?:` fallback to `refObjectName` is the path that actually works, and it works reliably.

The real observation is valid but understated differently: the asymmetry between the list/filter path (server-side `Iteration.Name = "..."` at line 794) and the search path (client-side re-filter on `refObjectName`) is a legitimate design inconsistency. The search path works correctly in practice because `_refObjectName` is always populated by Rally on ref objects, but it could silently drop results if Rally ever returns an artifact with an iteration ref that has a null `_refObjectName` (e.g., a corrupt ref or a future API change). The claimed scenario — "both name and refObjectName are null" — is unrealistic for any valid artifact with an assigned iteration under the current WSAPI contract. The severity is low (not medium or higher) and the confidence should be even lower than 0.55 — this is a theoretical risk, not an observable bug.

**Evidence.** Lines 947-952: `val iterName = when (artifact) { is RallyUserStory -> artifact.iteration?.name ?: artifact.iteration?.refObjectName; ... }; iterName?.equals(iterFilter, ignoreCase = true) == true` — null iterName → filtered out.

---

#### LOW-31 · fetchDescription maps API/network errors to null, rendered as 'No description' with no distinction from a genuinely empty field
`Error handling, silent failures & API correctness` · category: correctness · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** fetchDescription 720-726; consumed in RallyDetailPanel.loadAndRenderDescription 503-505, 520  

**Problem.** fetchDescription deliberately re-throws RallyAuthenticationException (good) but catches RallyApiException (404/429/500) and generic Exception, logs WARN, and returns null. In the detail panel, a null result renders as '<i>No description</i>' (RallyDetailPanel:520). So a 429 rate-limit or a 500 while fetching a description is indistinguishable from a ticket that truly has no description.

**Impact.** Minor correctness/UX: a user hitting a transient error while opening a ticket sees 'No description' and may believe the ticket is under-documented. The auth case is handled specially, which is the most important one; this is the long tail. The empty-string-cached-as-known-empty optimization (line 712) is fine and unaffected.

**Recommendation.** The sentinel pattern is already in place (DESC_AUTH_FAILED / AUTH_ERROR_HTML). Extend it with a second sentinel for transient errors, e.g. a DESC_FETCH_FAILED constant. In fetchDescription, catch non-404 RallyApiException and generic Exception, do not cache anything (already the case — this is correct), and return DESC_FETCH_FAILED. In the detail panel when branch, render a short inline error like "&lt;i style='color:gray'&gt;Description temporarily unavailable — reselect to retry.&lt;/i&gt;". Leave 404 returning null (or a separate DESC_NOT_FOUND sentinel) so a genuinely missing artifact ref still shows "No description". Do NOT add a new sentinel for the empty-string case; the current putCache(cacheKey, "") / cached.ifEmpty { null } optimization for truly empty descriptions is correct and should not change.

**Verifier.** The code at the cited locations does exactly what the review describes. In fetchDescription (lines 720-726), RallyApiException (covering 404, 429, 500, etc.) and generic Exception are caught, logged at WARN, and return null. This null propagates through loadAndRenderDescription (line 514: resolvedDesc becomes null) and is rendered at line 520 as "&lt;i&gt;No description&lt;/i&gt;", which is indistinguishable from a genuinely empty description field. The auth-failure sentinel pattern (DESC_AUTH_FAILED constant, lines 58 and 502) already exists in the codebase, confirming the infrastructure to distinguish errors from true-empty is present but has not been extended to cover non-auth API errors. One important mitigating detail the review got slightly wrong: the putCache call (line 712) is inside the try block and is only reached on success — exceptions do NOT cache the empty string, so re-selecting the ticket will retry the fetch rather than permanently showing "No description". This reduces severity slightly but does not eliminate the confusion for the user during the current selection.

**Evidence.** Lines 720-722: `catch (e: RallyApiException) { LOG.warn(...); null }`; RallyDetailPanel:520 `else -> "<i>No description</i>"` for the null case.

---

#### LOW-32 · uploadAttachment relies on Files.probeContentType which is platform-dependent and often returns null/wrong type
`Error handling, silent failures & API correctness` · category: correctness · effort: S · conf: 0.90 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** uploadAttachment 1631  

**Problem.** `Files.probeContentType(filePath) ?: "application/octet-stream"` uses the JDK's installed FileTypeDetector, which on macOS/Linux frequently returns null (falling back to octet-stream) and varies by OS/JDK. The CLAUDE.md / create dialogs are described as ZIP-upload oriented; a .zip may probe as application/zip on one machine and octet-stream on another. This is not a hard failure (Rally accepts the upload) but the ContentType stored on the attachment is unreliable, which then drives the exporter's `contentType.startsWith("image/")` image-vs-link rendering (RallyExporter:699).

**Impact.** Low: cross-machine inconsistency in stored ContentType; image attachments uploaded from a machine where probeContentType returns null get octet-stream and later export as a plain link instead of an inline image. No data loss.

**Recommendation.** Replace `Files.probeContentType` with an extension-based lookup first, falling back to the JDK probe, then to `application/octet-stream`. A small explicit map (e.g., png/jpg/jpeg/gif/webp → image/*, zip → application/zip, pdf → application/pdf) covers all cases the export cares about and is deterministic. Since the exporter's image-vs-link branch only requires `contentType.startsWith("image/")`, the map only needs to be correct for common image extensions to fix the rendering issue. Example: `MIME_MAP[ext] ?: Files.probeContentType(filePath) ?: "application/octet-stream"` where `ext = filePath.extension.lowercase()`.

**Verifier.** The cited code is exactly as described. Line 1631 of RallyApiClient.kt reads: `val contentType = Files.probeContentType(filePath) ?: "application/octet-stream"`. This value is then passed to Rally's API as the `ContentType` field of the attachment (line 1658). When the attachment is later fetched via Rally's API, the stored `ContentType` comes back in `RallyAttachment.contentType` (RallyApiModels.kt:420). The exporter at RallyExporter.kt:693-699 reads this stored value and uses `contentType.startsWith("image/")` to decide between inline-image markdown (`![]()`) and plain-link markdown. If `Files.probeContentType` returned `null` at upload time (which is common on macOS/Linux JDKs), the stored value is `"application/octet-stream"`, and image attachments are later rendered as plain links in exports. The mechanism described is real and the code path is confirmed. The severity is correctly assessed as low: no data loss, the attachment content is preserved correctly, and this only affects Markdown export rendering for image attachments uploaded via the create dialogs (which are described as primarily ZIP-oriented in CLAUDE.md).

**Evidence.** Line 1631: `val contentType = Files.probeContentType(filePath) ?: "application/octet-stream"`; consumer RallyExporter.kt:699 `if (contentType.startsWith("image/"))`.

---

#### LOW-33 · decodeBody gzip-failure fallback to raw bytes can yield garbled JSON parsed downstream without a clear error
`Error handling, silent failures & API correctness` · category: correctness · effort: S · conf: 0.65 · verdict: needs-nuance  
**File:** `…/api/RallyApiClient.kt`  
**Location:** decodeBody 355-365  

**Problem.** When Content-Encoding says gzip but GZIPInputStream throws IOException, decodeBody logs WARN and returns the RAW (still-compressed) bytes as a UTF-8 string. The KDoc justifies this for the case of a mislabeled plain error page. But if the body is genuinely gzip-compressed and decompression failed for another reason (truncated stream, partial read), the caller (e.g. queryAllPages) then runs `gson.fromJson` on binary gzip data, which throws a JsonSyntaxException that surfaces as a generic parse error rather than 'truncated/corrupt response'.

**Impact.** Low and rare: only on a genuinely-gzip body that fails to decompress for a non-mislabel reason. The user gets a confusing JSON parse error instead of a transport error. The fallback is a reasonable trade-off for the common mislabel case.

**Recommendation.** The trade-off is already documented and the scenario (genuine gzip that fails to decompress) is very rare since `ofByteArray()` collects the full body before `decodeBody` runs — network truncation surfaces earlier. If you want to improve diagnostic clarity without changing the behavior for the mislabel case, add a magic-byte check: if `bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()` is true when the IOException occurs, throw `RallyApiException("Failed to decompress gzip response", cause = e)` instead of returning the raw bytes. When the magic bytes are absent, keep the current fallback — the body is genuinely a plain-text error page. This is a low-priority diagnostic improvement, not a correctness fix.

**Verifier.** The code at lines 355-365 is exactly as described. The `decodeBody` function catches `IOException` from `GZIPInputStream` and returns `bytes.toString(Charsets.UTF_8)` — the raw compressed bytes interpreted as a UTF-8 string. This string is then returned via `DecodedResponse.body()` and fed to `gson.fromJson` at call sites like `queryAllPages` line 642, which would throw a `JsonSyntaxException` on binary gzip data.

However, three mitigating factors reduce the real-world impact below the reviewer's framing:

1. The trade-off is explicitly documented in both CLAUDE.md and the function's KDoc: it targets the well-known case of a proxy/LB mislabeling a plain error page as gzip. In that case the fallback is correct and beneficial — you get a meaningful HTTP status-code error rather than a raw ZipException.

2. The scenario the reviewer worries about — a genuinely-compressed body that fails mid-decompression — is extremely rare in practice. `HttpResponse.BodyHandlers.ofByteArray()` reads the full response body bytes before `decodeBody` is ever called. A network truncation would surface as an `IOException` on the HTTP response body handler, not inside `GZIPInputStream`. The only realistic path to a "genuine gzip body that still throws in GZIPInputStream" is a corrupt (not merely truncated) payload from the server, which is exceedingly rare with Rally's API.

3. The `JsonSyntaxException` that results IS catchable and distinguishable — it would propagate up from `queryAllPages` and the plugin would show an error, just not the most helpful "corrupt gzip" message. This is a diagnostic quality issue, not a silent data corruption or crash.

The reviewer's magic-byte check recommendation is sound and would be a modest improvement. But the severity is lower than "low" implies — this is effectively a diagnostic-clarity improvement in an already-documented trade-off for a very rare path. The finding is real but overstated.

**Evidence.** Lines 360-364: `catch (e: java.io.IOException) { LOG.warn("...failed to decompress; using raw body", e); bytes.toString(Charsets.UTF_8) }` — returns compressed bytes as a String.

---

#### › Memory & resource leaks

#### LOW-34 · JDK HttpClient is never closed on client rebuild or dispose — selector/connection threads leak per recreation
`Memory & resource leaks` · category: memory · effort: M · conf: 0.85 · verdict: needs-nuance · orig=high  
**File:** `…/api/RallyApiClient.kt`  
**Location:** httpClient field (lines 58-73); shutdown only handles apiExecutor — see RallyToolWindowPanel.dispose() L2024-2034 and getClient() L2050  

**Problem.** Each RallyApiClient builds its own `HttpClient` (HTTP/2). On JDK 17 a built HttpClient owns a daemon SelectorManager thread plus a connection pool, and it is NOT garbage-collected promptly because the SelectorManager thread holds a strong reference back to the client (the JDK only releases it via reference-reachability after the selector idles out, which can take a long time). When getClient() detects a settings/credential change it does `currentClient?.apiExecutor?.shutdown()` and replaces currentClient, and dispose() does `apiExecutor?.shutdownNow()` — but neither ever releases the HttpClient. HttpClient is not AutoCloseable on JDK 17 (close() arrives in JDK 21), so there is no clean shutdown call available, and the old instance lingers with its selector thread and any pooled HTTP/2 connections.

**Impact.** Every API-key rotation, server-URL change, or workspace change (each triggers a new RallyApiClient in getClient) leaks one HttpClient with a live selector thread and idle keep-alive sockets. Over a long IDE session with occasional settings edits this accumulates daemon threads and retained socket buffers; the apiExecutor threads are reclaimed but the HTTP machinery is not. Tool-window close/reopen also creates a fresh panel + client without releasing the prior HttpClient.

**Recommendation.** The mechanism is real but the risk is low. No action is strictly required for correctness. For hygiene: (1) Add a `fun shutdown()` to `RallyApiClient` that calls `apiExecutor.shutdownNow()` (and documents that `httpClient` becomes GC-eligible once executor tasks drain). Call this instead of accessing `apiExecutor` directly from `dispose()` and `getClient()`. (2) When the project build floor moves to JDK 21, add `httpClient.close()` to that `shutdown()` method. (3) Optionally log a debug message in `getClient()` rebuild path so settings-churn is observable during development. There is no urgent fix needed: the SelectorManager is a daemon thread, the client becomes GC-eligible after executor drain, and settings changes are a rare user action.

**Verifier.** The finding is structurally correct: the `httpClient` field is a `private val` built at construction time and is never released — neither `dispose()` (line 2028: only calls `currentClient?.apiExecutor?.shutdownNow()` then nulls the reference) nor `getClient()` (line 2050: only calls `currentClient?.apiExecutor?.shutdown()`) touches the HttpClient. On JDK 17 there is indeed no `close()` method, so the old instance and its internal state become unreachable only when the GC collects the `RallyApiClient` object.

However, the severity is overstated. The JDK's `HttpClient.SelectorManager` thread is a daemon thread (verified in JDK source). This means: (1) it does NOT prevent IDE/JVM shutdown, (2) it does not cause the IDE to hang, and (3) it will eventually be reclaimed when GC collects the old client (once `currentClient` is nulled and no other references exist). The practical trigger rate is also very low: `getClient()` only rebuilds when `matchesSettings()` returns false, which requires a serverUrl, apiKey, OR workspaceRef change in Settings — a rare user action, not a per-request event. Tool-window reopen reuses the existing panel via IntelliJ's ToolWindowFactory lifecycle rather than creating a new one every time.

The real issue is that the old `RallyApiClient` instance may survive longer than necessary because the `apiExecutor` threads hold a reference to tasks that may close over the client. After `apiExecutor.shutdown()`, once the pool drains, those threads terminate and the client becomes GC-eligible. So the HttpClient is not truly "leaked forever" — it is released when the GC runs after the executor drains. The daemon SelectorManager thread will stop once the HttpClient becomes phantom-reachable. The window of "leakage" is bounded by GC cadence plus the executor drain time.

The finding is accurate about the mechanism and the absence of an explicit `httpClient.close()` call, but "high" severity implies normal-use impact. In practice this is low severity: daemon threads, rare trigger (settings changes), and GC-eventual collection mean this is a code-quality/hygiene issue rather than a genuine resource leak that accumulates meaningfully.

**Evidence.** private val httpClient: HttpClient = HttpClient.newBuilder()...build()  // never closed; dispose() only does currentClient?.apiExecutor?.shutdownNow()

---

#### LOW-35 · Per-artifact detail caches (desc:, testcases:, tasksForWp:, attachments:, teststeps:) grow with browsing and are never LRU-evicted within TTL
`Memory & resource leaks` · category: memory · effort: M · conf: 0.85 · verdict: needs-nuance · orig=medium  
**File:** `…/api/RallyApiClient.kt`  
**Location:** queryCache LRU (L152-160, max 200) vs clearArtifactCache() L232-258 which only evicts list prefixes  

**Problem.** The query cache IS bounded to 200 entries by removeEldestEntry, so it cannot grow without limit — that part is fine. The subtler issue is retention of large payloads: `desc:<ref>` entries cache full HTML descriptions (potentially multi-MB each, per CLAUDE.md), and there can be up to ~200 of them mixed with list results. clearArtifactCache() deliberately leaves all detail prefixes (desc:, testcases:, tasksForWp:, attachments:, teststeps:) untouched, so after edits the only thing that evicts a heavy description is LRU pressure from 200 newer entries or a manual Refresh. Browsing 50 tickets with large descriptions can hold ~50 multi-MB HTML strings resident for the 2-minute TTL (15 min in bulk mode) regardless of whether the detail panel is still open.

**Impact.** On a session where the user opens many tickets with image-heavy/large HTML descriptions, the query cache can retain tens of MB of description strings that are no longer displayed, on top of the 10 MB image cache. The 200-entry cap bounds count but not byte-weight, so a handful of giant descriptions dominate heap until evicted by churn or TTL.

**Recommendation.** The count-only bound on `queryCache` is a minor quality gap, but the actual risk is low because `desc:` entries hold raw HTML strings (before `resolveInlineImages` runs), not base64-expanded HTML. A few hundred KB of raw HTML across 200 entries is rarely a meaningful heap concern. If you want defense-in-depth, the most targeted fix is to skip caching descriptions above a size threshold (e.g., `if (desc != null && desc.length <= 512_000) putCache(cacheKey, desc)`), mirroring the `maxCacheableImageBytes` guard on the image cache. This prevents pathological cases (malformed/enormous Rally HTML) without touching the 200-entry count cap or requiring a full byte-budget eviction scheme. Do not include `desc:` in `clearArtifactCache()` evictions — the comment explaining why detail caches are left alone is correct and deliberate.

**Verifier.** The structural observation is correct: `queryCache` is count-bounded (max 200 entries) but not byte-bounded, `clearArtifactCache()` intentionally skips `desc:` / `testcases:` / `tasksForWp:` / `attachments:` / `teststeps:` prefixes (documented at L232–258), and the only eviction for heavy descriptions is LRU pressure from 200 newer entries or a manual Refresh.

However, the claimed impact is materially overstated. The finding says "desc: entries cache full HTML descriptions (potentially multi-MB each)" and implies that image-heavy browsing can hold "tens of MB" in the description string cache. This is wrong.

What `fetchDescription` actually stores is raw HTML — the `<img>` tags still contain Rally server URLs, not base64 data URIs. The base64 image resolution (`resolveInlineImages`) happens entirely inside `RallyDetailPanel.loadAndRenderDescription` and is never written back into `queryCache`. The expanded (image-embedded) HTML string lives only in the JEditorPane component. So a `desc:` cache entry holds a few KB of markup, not MB of base64 data.

The image cache has its own 10 MB byte budget with a 1 MB per-image cap (`maxCacheableImageBytes`). The description string cache, covering a separate concern (raw HTML), has only a 200-entry cap. This is a mild asymmetry, but with typical Rally descriptions being a few KB of HTML, 200 entries cap the string cache at roughly 2–5 MB total in realistic worst cases — not the "tens of MB" suggested. The real data (base64 images) is byte-capped separately.

The core observation — count bound without byte bound — is valid but the practical impact is low rather than medium. The recommendation to mirror a byte budget on `desc:` entries could reduce memory slightly in edge cases but is not a meaningful concern given the actual payload sizes stored.

**Evidence.** queryCache[key] = CacheEntry(data, ...) for desc:$artifactRef stores full HTML; clearArtifactCache comment: "detail-level caches (tasksForWp:, testcases:, attachments:, desc:, ...) are intentionally left alone"

---

#### LOW-36 · imageCache LinkedHashMap relies solely on manual byte-budget eviction; an entry exactly at the 1 MB cap edge or a logic slip could let it grow
`Memory & resource leaks` · category: memory · effort: S · conf: 0.30 · verdict: needs-nuance  
**File:** `…/api/RallyApiClient.kt`  
**Location:** imageCache (L167-169) + downloadAttachment eviction loop (L1489-1507)  

**Problem.** Unlike queryCache, imageCache's anonymous LinkedHashMap does NOT override removeEldestEntry — it has an empty body `{}`. The only bound is the manual while-loop in downloadAttachment that evicts until imageCacheBytes + incoming <= maxImageCacheBytes. This is correct as written, but the safety net is entirely procedural: any future caller that inserts into imageCache outside downloadAttachment (or any accounting drift between imageCacheBytes and actual contents) would grow the map unbounded with no structural backstop. The byte counter and the map can also drift if an entry is replaced rather than inserted (the double-check inside the lock returns early, so that path is safe today, but it is fragile).

**Impact.** Today bounded at 10 MB, so impact is low. The risk is latent: the structure provides count/byte safety only by convention, not by the map itself, so a regression would silently reintroduce unbounded image retention.

**Recommendation.** The current implementation is correct. The imageCache is private and has exactly one write path (inside downloadAttachment's synchronized block), so there is no actual drift today. The latent risk is real but modest. If you want a low-effort structural backstop, override removeEldestEntry to cap entry count (e.g., 256 entries) so that even if a future developer adds a second write path, the map cannot grow without bound. Alternatively, extract a private putImage(url, bytes) helper that is the single documented write point, with a comment noting it must maintain imageCacheBytes — this self-documents the invariant better than the map itself and costs zero runtime overhead. Either change is a defensive cleanup, not a bug fix.

**Verifier.** The actual code confirms the structural observation: imageCache is an anonymous LinkedHashMap with an empty body (no removeEldestEntry override), and all size enforcement is procedural via the eviction while-loop in downloadAttachment. However, several reviewer concerns are overstated for the current code:

1. The map is declared `private val`, so no caller outside RallyApiClient can insert into it. The only insertion point is the synchronized block at L1489-1507 inside downloadAttachment, which always runs the eviction loop before inserting.

2. The double-check early-return at L1492 (`imageCache[url]?.let { return it }`) exits before writing, so entry replacement never occurs — only inserts of new URLs. There is no counter-drift path from replacements.

3. The `clearCache()` at L219-225 resets both `imageCache` and `imageCacheBytes` inside the same synchronized block — no drift there either.

4. The `imageCacheBytes` AtomicLong read at L1467 (outside the lock) is a read-only early exit with no write, so no accounting skew can result from it.

The finding is technically correct that there is no structural backstop (removeEldestEntry or similar), and that a future developer adding a second write path could silently bypass the byte budget. But the current implementation is correct, the private visibility limits the blast radius, and "future regression" is a latent/hypothetical concern rather than a present bug. Severity low is appropriate; the claimed confidence of 0.55 is slightly generous given how well-guarded the single write path actually is.

**Evidence.** object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {}  // empty body, no removeEldestEntry

---

#### LOW-37 · exportSelectedArtifact captures client.apiExecutor and joins on the EDT-detached pool; enterBulkMode extends TTL globally and survives if export task is abandoned
`Memory & resource leaks` · category: memory · effort: S · conf: 0.75 · verdict: needs-nuance  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** exportSelectedArtifact L1582-1661; enterBulkMode/exitBulkMode in finally  

**Problem.** Export wraps work in enterBulkMode()/exitBulkMode() with a try/finally, which is good. However enterBulkMode sets bulkModeTtlMs globally on the client, extending ALL query-cache entries' effective TTL to 15 minutes for the duration. The finally restores it, so the only leak window is if the pooled task is interrupted in a way that bypasses finally (shutdownNow during dispose interrupts the worker — finally still runs on InterruptedException, so this is mostly safe). The larger concern: the export submits N futures to client.apiExecutor and `allOf(...).join()` blocks a general application-pool thread; if the panel is disposed mid-export, apiExecutor is shutdownNow() and the inner futures complete exceptionally, but the outer join() thread and the captured `exporter`/`selected` list (which can hold large artifact objects + downloaded byte arrays in downloadedPaths/downloadedImagePaths maps) stay referenced until join returns. The RallyExporter's two ConcurrentHashMaps accumulate every downloaded path for the whole export and are only freed when the exporter is GC'd.

**Impact.** During a large multi-artifact export, the exporter's dedup maps and the in-flight byte[] buffers are retained for the export duration; if the user disposes the tool window mid-export, that retention persists until the abandoned join completes. Bounded and transient, but a long bulk export holds peak heap longer than necessary and the extended TTL keeps the whole query cache from expiring.

**Recommendation.** The finding has two factual errors that reduce its severity: (1) the exporter maps hold String paths, not byte[] arrays, so there is no large buffer retention; (2) the bulkMode TTL cannot leak because exitBulkMode() runs in finally, and after dispose() nulls currentClient no other code touches that client. The only genuine (low severity) issue is that after dispose(), in-flight export futures continue to completion rather than bailing early — wasting CPU/network on orphaned work. To address this: add a disposed-flag check inside each per-artifact lambda before calling exportArtifactJson/Markdown, and propagate the check between test case iterations. No change to bulkMode scoping or exporter map handling is needed.

**Verifier.** The code at lines 1582-1661 is largely correct and the main concern about TTL leak and byte[] accumulation is overstated or wrong in key specifics:

1. enterBulkMode/exitBulkMode: The finally block at line 1651 correctly calls exitBulkMode(), and Java's finally runs even on InterruptedException. If dispose() is called mid-export, the panel sets currentClient = null — but the closure already captured a strong reference to `client`. exitBulkMode() will still be called on that captured reference, resetting bulkModeTtlMs, but since the panel nulled currentClient, no other code uses that orphaned client. There is no TTL leak in practice.

2. The exporter's downloadedPaths / downloadedImagePaths ConcurrentHashMaps store String file-path values, NOT byte[] arrays. The byte arrays are local variables written to disk (line 567) and immediately eligible for GC. The reviewer's claim that "in-flight byte[] buffers" are retained in the maps is factually wrong.

3. On dispose with shutdownNow(): tasks queued-but-not-started on apiExecutor are cancelled, but the individual futures (lines 1597-1626) catch all exceptions internally and complete normally (not exceptionally). So allOf().join() does NOT throw early — but the interrupted tasks complete quickly (InterruptedException is caught), so the outer thread unblocks soon after shutdownNow(). The outer join blocks on the general application pool thread (not apiExecutor), so shutdownNow() does not interrupt it directly, but the futures finish promptly.

4. The genuine (minor) concern: the selected artifact list and exporter are retained for the full export duration, which for a large multi-artifact export could be minutes. There is no early-exit check on the disposed flag between artifacts. However, the artifacts themselves are lightweight metadata objects (no pre-loaded byte arrays), so the retention is bounded and expected for export tasks.

5. The lack of a disposed-check between artifact futures is a mild quality concern (wasted work after disposal) but not a memory hazard because the exporter maps only accumulate String paths, not large blobs.

**Evidence.** client.enterBulkMode() ... allOf(*futures.toTypedArray()).join() ... finally { client.exitBulkMode() }; exporter holds downloadedPaths/downloadedImagePaths ConcurrentHashMap for export lifetime

---

#### LOW-38 · Debounced server-search pooled task captures the panel and runs after dispose with no generation guard on the client call
`Memory & resource leaks` · category: memory · effort: S · conf: 0.90 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** applySearchFilter server-search block L934-977  

**Problem.** The server-search executeOnPooledThread closure captures `this` (the panel) and calls getClient() then client.searchArtifacts(...). getClient() is guarded by `check(!disposed)` so it throws after dispose (caught by the surrounding catch), which is the correct backstop. But the task can still be in flight at dispose: searchArtifacts may be blocked in HttpClient.send for up to 60s, holding a strong reference to the disposed panel (and via it the listModel, allArtifacts, cachedProjects/cachedIterations) until the request returns or times out. The apiExecutor shutdownNow() interrupts apiExecutor workers, but this search runs on the shared application pool (executeOnPooledThread), which is NOT interrupted by panel dispose — so the panel is retained for the full network timeout.

**Impact.** After closing the tool window while a server search is outstanding, the entire panel object graph (artifact list, cached projects/iterations, detail panel) is kept alive until the in-flight HTTP request finishes (up to ~60s + retries). One-shot, self-healing, but delays GC of a sizeable graph and is a classic 'disposed component held by a pooled task' pattern.

**Recommendation.** The cleanest fix is to dispatch the search onto `client.apiExecutor` (acquired before the lambda, with a null-check) rather than the shared application pool. `dispose()` already calls `apiExecutor.shutdownNow()`, which will interrupt the blocking HttpClient.send call on the worker thread, immediately releasing the panel reference. Concretely: call `getClient()` synchronously before the lambda, store `client` in a local, then submit `client.apiExecutor.submit { ... }` (wrapping in a try/catch for RejectedExecutionException, which means dispose already fired). This eliminates the retention window entirely with minimal code change. An alternative — adding a `disposed` check immediately after `searchArtifacts` returns, before touching any panel field — reduces the UI-update window but does not shorten the HTTP blocking time or release the reference earlier; it is not sufficient on its own.

**Verifier.** The code at lines 934-977 confirms the finding. The server-search block submits work via `ApplicationManager.getApplication().executeOnPooledThread`, capturing `this` (the panel). Inside, `getClient()` throws if `disposed` is already true — but if `disposed` becomes true AFTER `getClient()` returns successfully, `client.searchArtifacts(...)` executes on the shared app-pool thread. Each HTTP request carries a 60-second per-request timeout (confirmed at RallyApiClient lines 466/1011), and `searchArtifacts` makes up to two sequential calls (user stories + defects), so the panel object graph (listModel, allArtifacts, cachedProjects, cachedIterations, detailPanel) can be retained for up to ~120 seconds after dispose.

`dispose()` sets `disposed = true` and calls `currentClient?.apiExecutor?.shutdownNow()` (line 2028), but the search runs on the IntelliJ shared application pool — `shutdownNow()` on `apiExecutor` does not interrupt it. The `invokeLaterIfAlive` guard at line 955 and 970 correctly prevents post-dispose UI updates, so there is no UI corruption. The retention is bounded and self-healing: once the HTTP request(s) finish or time out, the task completes and the strong reference is released. The `disposed` volatile flag ensures the `invokeLaterIfAlive` callback is a no-op after dispose.

The reviewer's description of the mechanism is accurate: `getClient()` is the correct backstop for the case where dispose fires before the call, but offers no protection for the in-flight HTTP case. The panel object graph is held for up to the full network timeout after the tool window is closed.

**Evidence.** ApplicationManager.getApplication().executeOnPooledThread { val client = getClient(); val serverResults = client.searchArtifacts(...) ... } — runs on app pool, not apiExecutor; dispose() only shutsdown apiExecutor

---

#### LOW-39 · loadTickets blocks a shared application-pool thread on artifactsFuture.get() while holding references; outlives dispose for the network timeout
`Memory & resource leaks` · category: memory · effort: S · conf: 0.70 · verdict: needs-nuance  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** loadTickets L490-620, especially artifactsFuture.get() L555 and sprintFuture.get()/iterationsFuture.get() L584/593  

**Problem.** loadTickets runs its orchestration on executeOnPooledThread (shared app pool) and then blocks on artifactsFuture.get() / sprintFuture.get() / iterationsFuture.get(), where those inner futures run on client.apiExecutor. The orchestration thread (app pool) is not interrupted by dispose() — only apiExecutor is. So if the panel is disposed while a load is in flight, the app-pool orchestration thread stays parked on .get() (the inner apiExecutor tasks are interrupted by shutdownNow and complete exceptionally, which unblocks get() reasonably fast — so this is better than the search case) but the closure still strong-references the panel until then.

**Impact.** Minor: dispose interrupts apiExecutor so the inner futures fail fast and get() returns, releasing the panel quickly. Retention window is short. Listed for completeness as another app-pool-task-holds-panel pattern; combined with the search task it means dispose does not deterministically release the panel graph.

**Recommendation.** The pattern is real but the retention window is already bounded and fast due to `shutdownNow()`. No action is needed for memory retention. The only concrete improvement worth considering is adding a timeout to each `.get()` call (e.g., `artifactsFuture.get(30, TimeUnit.SECONDS)`) to make it defensive against any future executor change that omits `shutdownNow()`. Do not add extra `disposed` checks between `.get()` and the UI update — `invokeLaterIfAlive` already provides that guard. The "same remedy as the search finding" recommendation is inapplicable here: the search case's problem (if it exists) is a task with no interrupt path; this case already has a working interrupt path via `shutdownNow()`.

**Verifier.** The code is exactly as described: `loadTickets` dispatches orchestration to `executeOnPooledThread` (lines 490–620) and then blocks at `artifactsFuture.get()` (L555), `sprintFuture.get()` (L584), and `iterationsFuture.get()` (L593), while the inner futures run on `client.apiExecutor`. `dispose()` (L2024–2034) sets `disposed = true` and calls `currentClient?.apiExecutor?.shutdownNow()`. `shutdownNow()` sends interrupts to running apiExecutor tasks, which causes the inner `CompletableFuture` tasks to complete exceptionally almost immediately, unblocking all three `.get()` calls within milliseconds. The catch block at L598 then runs `invokeLaterIfAlive` which is a no-op due to `disposed = true`. So the app-pool thread does hold strong references to the panel (via the lambda closure capturing `this` implicitly through `invokeLaterIfAlive`, `allArtifacts`, `detailPanel`, etc.) from the time `executeOnPooledThread` is called until the catch block completes. The reviewer correctly identifies this, but the "retention window" framing overstates concern: `shutdownNow()` makes the unblocking deterministic and very fast — this is not a leak or a prolonged hold. The real nuance the reviewer misses is that the finding is not materially different from any ordinary pooled-thread task that holds a closure over UI objects. Every `executeOnPooledThread` in the file has the same characteristic. The recommended remedy ("gate post-get() work on disposed/generation") is already done — all UI updates inside the lambda use `invokeLaterIfAlive`. Adding a `disposed` check between `.get()` and the UI update adds nothing because `invokeLaterIfAlive` already guards that. The only genuine improvement would be passing a timeout to `.get()` (e.g., `get(30, SECONDS)`) so the orchestration thread cannot park indefinitely if `shutdownNow()` somehow fails to interrupt the inner task — but that is a defensive-coding concern, not a memory retention one. The "search case" comparison the reviewer mentions would need to be evaluated separately; this specific pattern does not share the same characteristics as a long-running search with no cancellation path.

**Evidence.** val artifacts = artifactsFuture.get() ... if (iterationsFuture != null) { try { iterationsFuture.get() } } — orchestration on app pool, inner work on apiExecutor

---

#### LOW-40 · getAttachmentContent + decode materializes the full base64 string and decoded bytes simultaneously for large attachments
`Memory & resource leaks` · category: memory · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** getAttachmentContent L1446-1455 (returns full base64 String); callers RallyDetailPanel.saveAttachmentToDisk L930-934 and RallyExporter.downloadAttachmentContent L548-552  

**Problem.** getAttachmentContent parses the entire response body as a JSON object (JsonParser.parseString on the already-fully-buffered decoded String) and returns the Content field as a String. For a near-50 MB attachment fetched via the base64 path, peak heap holds: the raw response byte[] (in DecodedResponse), the decoded UTF-8 String of the whole JSON, the parsed JsonObject DOM, the extracted base64 String (~67 MB for 50 MB binary), and then Base64.getMimeDecoder().decode produces the ~50 MB byte[] — all live at once. The exporter mitigates this by preferring the raw-bytes endpoint (cache=false) and only falling back to base64; saveAttachmentToDisk always uses the base64 path.

**Impact.** Saving a large attachment from the detail panel (which always uses getAttachmentContent) can spike heap to several multiples of the file size (documented ~3-4x for the base64 path). For a 50 MB file that is a >150 MB transient allocation, risking GC pauses or OOM on constrained IDE heaps.

**Recommendation.** In `saveAttachmentToDisk`, mirror the exporter's raw-bytes-first pattern. After obtaining `contentRef`, also read `val objectId = selected.objectID`. Then attempt `client.downloadAttachment("${client.webBaseUrl}/slm/attachment/$objectId/${URLEncoder.encode(fileName, UTF_8).replace("+", "%20")}", cache = false)` inside a try/catch. Only fall back to `client.getAttachmentContent(contentRef)` + `Base64.getMimeDecoder().decode(...)` when `objectId` is null or the raw download throws. This exactly matches the already-working pattern in `RallyExporter.downloadAttachmentContent` (L538-552) and cuts peak heap from ~3-4x file size to ~1x on the common path. The exporter's comment documents the magnitude. No new API surface is needed; `webBaseUrl` is already public.

**Verifier.** The cited code is exactly as described. `getAttachmentContent` at L1446-1455 calls `JsonParser.parseString(response.body()).asJsonObject` (full JSON DOM materialization) and returns `contentObj.get("Content")?.asString` — a full base64 String. `saveAttachmentToDisk` at L930-933 always uses this path: `val base64Content = client.getAttachmentContent(contentRef)` followed by `Base64.getMimeDecoder().decode(base64Content)`, meaning the raw response body, the decoded UTF-8 JSON String, the JsonObject DOM, the base64 String, and the decoded byte[] are all transiently live at once.

The contrast with the exporter is real and code-documented: `RallyExporter.downloadAttachmentContent` at L538-543 explicitly prefers `client.downloadAttachment("${client.webBaseUrl}/slm/attachment/$objectId/$encodedName", cache = false)` and its inline comment reads "~1x peak heap instead of 3-4x." The exporter only falls back to `getAttachmentContent` when `objectID` is null (L548-552).

`saveAttachmentToDisk` already has everything needed to mirror the exporter: `selected` is a `RallyAttachment` which carries `objectID` and `name`, and `client.webBaseUrl` is a public property. The fix is a direct copy of the exporter's try/catch raw-bytes-first pattern.

**Evidence.** fun getAttachmentContent(contentRef): String { ... return contentObj.get("Content")?.asString } ; saveAttachmentToDisk: val base64Content = client.getAttachmentContent(contentRef); val bytes = Base64.getMimeDecoder().decode(base64Content)

---

#### LOW-41 · DecodedResponse holds both the original ByteArray response and the decoded String, doubling retained body size for the response's lifetime
`Memory & resource leaks` · category: memory · effort: S · conf: 0.85 · verdict: needs-nuance  
**File:** `…/api/RallyApiClient.kt`  
**Location:** DecodedResponse class L1685-1698; constructed in executeGet L471-474 and executePost L1021-1024  

**Problem.** executeGet/executePost call BodyHandlers.ofByteArray(), decode to a String, then wrap both in DecodedResponse, which keeps a strong reference to the original HttpResponse<ByteArray> (`delegate`) AND the decoded String. So while a caller holds the response, both the raw bytes and the decoded String of the same payload are resident. For large query results (200 artifacts with descriptions, or a base64 attachment body) this doubles transient body memory until the response object is dropped.

**Impact.** Transient ~2x body retention per request. For the largest responses (bulk artifact queries, base64 attachment content) this adds to the peak alongside the gson-parsed model objects. Bounded by request lifetime, so low severity, but it is pure avoidable doubling.

**Recommendation.** The structural fix is correct but the priority is low. If you want to apply it: in `DecodedResponse`, capture `delegate.statusCode()`, `delegate.headers()`, `delegate.uri()`, `delegate.version()`, `delegate.request()`, and `delegate.sslSession()` as `val` fields at construction time, then remove the `private val delegate` field entirely. This lets the `ByteArray` held by the original `HttpResponse<ByteArray>` become unreachable as soon as `DecodedResponse(response, decoded)` returns, rather than when the local `response` variable exits scope (which is only a few lines later anyway). The win is real but small — it tightens the window from "a few lines" to "immediately after decode" and is worth doing only if you observe GC pressure from large responses in profiling. Do not prioritize this over functional work.

**Verifier.** The claim is structurally correct — `DecodedResponse` holds `private val delegate: HttpResponse<ByteArray>` alongside `private val decoded: String`, so the raw byte array and the decoded String coexist while any `DecodedResponse` instance is alive. The cited code at L1685-1698 and construction sites at L471-474 and L1021-1024 are real. However, the claimed impact is substantially overstated in two ways:

1. **Lifetime is a few lines, not "the response's lifetime"**: every call site follows the pattern `val response = executeGet(url); handleResponse(response); val result = gson.fromJson(response.body(), ...)` — `response` is a local variable that exits scope at the end of the enclosing block, typically 3-5 lines after creation. `DecodedResponse` is never stored in the LRU cache, in any field, or in any long-lived structure. The LRU cache stores parsed model objects, not response wrappers. So "while a caller holds the response" translates to roughly 1-2 ms while JSON is being parsed, not the "request lifetime" the finding implies.

2. **The gzip case is the only case with meaningful transient excess**: for non-gzip (plain UTF-8) responses, `bytes.toString(Charsets.UTF_8)` is called, and both the original `ByteArray` (held by `delegate`) and the resulting `String` are live simultaneously — this is the described doubling, but again only for the parsing duration. For gzip responses, `decodeBody` creates an additional intermediate decompressed `ByteArray` (`it.readBytes()`) before the String decode — so there are briefly three live copies (compressed bytes + decompressed bytes + String), but `decodeBody` immediately returns so the intermediate decompressed array is also collected quickly.

The fix is valid and correct — capturing `statusCode`, `headers`, `uri`, `version`, `request()`, and `sslSession()` at construction time and nulling out the delegate reference would allow the JVM to collect the byte array as soon as `DecodedResponse` is constructed rather than when the local `response` variable exits scope. But given these are local variables with sub-millisecond lifetimes, the practical heap impact at any given GC pause is essentially one extra request body in old-gen pressure on slow GC cycles — not the "doubles retained body size for the response's lifetime" framing, which implies a persistent or request-scoped leak.

**Evidence.** private class DecodedResponse(private val delegate: HttpResponse<ByteArray>, private val decoded: String) — retains delegate (and its byte[] body) plus decoded

---

#### › Code quality, maintainability & complexity

#### LOW-42 · loadTickets() is a ~150-line method braiding 4 concerns with deep nesting
`Code quality, maintainability & complexity` · category: architecture · effort: M · conf: 0.90 · verdict: needs-nuance · orig=medium  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** loadTickets 469-621  

**Problem.** A single method handles: the loading/pendingReload re-entrancy gate, EDT-state capture, the projects-load-if-snapshot-changed branch, the effective-project-index computation, the conditional concurrent-vs-serial iteration load with the generation token, query building, the parallel artifacts+sprint futures, client-side filtering, the success UI update with empty-state messaging, and a multi-branch error classifier (401/403/429/network/timeout). The try body alone nests futures inside invokeLaterIfAlive inside executeOnPooledThread inside try.

**Impact.** This is the most concurrency-sensitive method in the app and it is hard to read end-to-end, which raises the risk of a regression when touching any one concern (the comments themselves note several subtle ordering requirements). Untestable as written.

**Recommendation.** The method is genuinely long (152 lines) and has multiple responsibilities, but the reviewer's claim that the heavy logic is all inline is incorrect — loadProjects, loadIterations, buildQuery, applyClientFilter, loadSprintSummary, and resolveSelectedSprint are already extracted private methods. The two concrete improvements worth making are: (1) Extract the error classifier `when` block (lines 608-614) into a private `classifyLoadError(cause: Throwable): String` pure function — this is the one truly unit-testable piece still embedded in the method. (2) Extract lines 497-511 (the "refresh projects if stale" block including effectiveProjectIndex computation) into a private `refreshProjectsIfStale(client, settings)` helper. These two changes would bring the method to ~100 lines with no loss of readability. The larger RallyDataController extraction proposed in the review is a valid future direction but is out of scope for this finding — it would require restructuring the entire panel, not just refactoring loadTickets.

**Verifier.** The method is 152 lines (469-621) and does orchestrate multiple concerns, so the factual claim about size and multi-concern structure is correct. However, the reviewer's framing overstates both the decomposition gap and the nesting depth:

1. Already decomposed: The review says the method handles "the projects-load-if-snapshot-changed branch", "effective-project-index computation", "conditional concurrent-vs-serial iteration load", "query building", "parallel artifacts+sprint futures", "client-side filtering", and "error classifier" all inline. In reality, the heavy logic for most of these is already delegated: loadProjects(), loadIterations(), buildQuery(), applyClientFilter(), loadSprintSummary(), resolveSelectedSprint() are all existing private methods in the same file (confirmed at lines 644, 689, 782, 806, 843, 871).

2. Nesting depth: The claim of "futures inside invokeLaterIfAlive inside executeOnPooledThread inside try" is accurate but describes the standard IntelliJ async pattern — not pathological deep nesting. The happy path is 3 levels deep (executeOnPooledThread > try > invokeLaterIfAlive), not a callback pyramid.

3. What is genuinely true: The method is still the orchestration hub for ~6-7 sequential logical steps (re-entrancy gate, EDT capture, project freshness check, project index resolution, iteration fork, parallel launch + join, error handling). The error classifier (lines 608-614) is a pure `when` expression embedded in a catch handler that is extractable and unit-testable. The two concurrent branches (iteration concurrent-vs-serial at 521-530) add genuine cognitive complexity that a short comment does not fully resolve.

4. "Untestable as written" is an overstatement — the pure logic (buildQuery, applyClientFilter, error classification) is already in separate functions. What is untestable is the orchestration sequencing itself, which is inherent to any async tool-window controller of this complexity.

Severity should be low-to-medium: this is a real maintainability concern (152-line orchestration method with non-trivial concurrent branching) but not a correctness, performance, or crash risk. The existing helper extraction already addresses the reviewer's primary concern. The remaining actionable improvement is narrow: extract the error classifier `when` block into a private `classifyLoadError(cause: Throwable): String` function, and possibly extract the "load projects if stale" block (lines 497-511) into a `refreshProjectsIfStale(client, settings)` helper. The recommendation for a full RallyDataController is a larger architectural change beyond what this finding justifies on its own.

**Evidence.** loadTickets spans 469-621 (152 lines) with the error classifier at 608-614 and nested CompletableFuture/invokeLater/try blocks; CLAUDE.md documents multiple non-obvious ordering invariants that all live in this one method.

---

#### LOW-43 · Six query methods hand-build URLs with the same workspace/project/order boilerplate
`Code quality, maintainability & complexity` · category: quality · effort: M · conf: 0.95 · verdict: confirmed · orig=medium  
**File:** `…/api/RallyApiClient.kt`  
**Location:** queryCurrentIteration 1195-1220, queryProjects 1234-1256, queryIterations 1263-1293, queryTasksForWorkProduct 1298-1323, queryTestCases 1328-1353, queryTestSteps 1358-1381, queryAttachments 1386-1409, queryTestCaseByFormattedId 1414-1441  

**Problem.** A clean buildQuery() helper exists and is used by queryAllPages, but ~8 single-page query methods bypass it and instead manually string-concatenate `buildApiUrl(x) + "?query=$encodedQuery&fetch=...&pagesize=..."`, then each appends `if (!ws.isNullOrBlank()) url += "&workspace=...normalizeRef..."` (and sometimes project) by hand. The cache-key construction (`"prefix:$a|$b|..."`), getCached/putCache wrapping, and executeGet+handleResponse+gson.fromJson+safeResults sequence are also copy-pasted in each.

**Impact.** Eight chances to forget the workspace param, mis-encode a fetch list, or build an inconsistent cache key (a real class of bug the author already had to guard against with the scoped cache keys). Every new query method re-pastes ~15 lines. URL encoding correctness is security-adjacent.

**Recommendation.** The finding is real but the severity should be low rather than medium. These are internal helper methods with no user-facing or security-facing behavior: workspace scoping is already correct in all eight methods (the duplication hasn't caused a bug), and URL encoding is not a security boundary here (Rally API key auth is in the header, not the URL). The refactoring value is maintainability only.

Concretely: extend `buildQuery` to accept a `vararg extraParams: Pair<String, String>` or a `scopeParams: Map<String, String>` for the projectScopeUp/Down case, then replace each of the eight methods with `buildApiUrl(endpoint) + "?" + buildQuery(query, pageSize, start=1, ws, pr, order, fields)`. The cache-key construction and getCached/putCache boilerplate is harder to centralize cleanly because each method uses a different singleton type token — a generic `queryOnce<T>(cacheKey, endpoint, typeToken, ...)` private helper could absorb that pattern too, collapsing each public method to ~5–8 lines. Only `queryIterations` needs the extra scope params forwarded.

**Verifier.** The `buildQuery` helper at line 509 handles query encoding, fetch fields, pageSize, workspace normalization, project normalization, and order — but is only used by `queryAllPages` (line 639) and `getUserByUsername` (line 604-605). All eight cited single-page methods (`queryCurrentIteration`, `queryProjects`, `queryIterations`, `queryTasksForWorkProduct`, `queryTestCases`, `queryTestSteps`, `queryAttachments`, `queryTestCaseByFormattedId`) manually string-concatenate the URL and duplicate the `if (!ws.isNullOrBlank()) { url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}" }` pattern verbatim. The boilerplate pattern appears exactly five times at lines 1207, 1246, 1273-1274, 1313-1314, 1343-1344, 1371-1372, 1399-1400, 1430-1431. There is one genuine complication: `queryIterations` also appends `&projectScopeUp=false&projectScopeDown=false` (line 1283), which `buildQuery` currently does not support, so that one method cannot be trivially routed through the existing helper without extending it.

**Evidence.** Each of queryIterations/queryTasksForWorkProduct/queryTestCases/queryTestSteps/queryAttachments repeats `if (!ws.isNullOrBlank()) { url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}" }` verbatim (e.g. lines 1313-1315, 1343-1345, 1371-1373, 1399-1401).

---

#### LOW-44 · changeState/finishWorking/startWorking/editPoints repeat the same optimistic-update async scaffold
`Code quality, maintainability & complexity` · category: quality · effort: M · conf: 0.95 · verdict: confirmed · orig=medium  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** editPoints 1666-1727, changeState 1729-1813, startWorking 1815-1975, finishWorking 1977-2022  

**Problem.** All four mutation actions follow the same template: validate selection, executeOnPooledThread { getClient(); call update; invokeLaterIfAlive { allArtifacts = allArtifacts.map { transform }; client.clearArtifactCache(); patchArtifactsInModel(refs, transform); refresh detail panel if selected.ref matches; set status } catch { status = failed }}. The 'patch model + refresh detail panel header' tail (the `artifactList.selectedValue?.let { sel -> if (sel.ref ... ) detailPanel.showArtifact(sel, client) }` block) is copy-pasted four times with a near-identical comment 'Keep the open detail panel's header in sync (see changeState)'.

**Impact.** The shared optimistic-update epilogue is duplicated 4x; the comments even cross-reference each other, which is a code smell signaling the missing abstraction. A fix to the optimistic-update/detail-refresh behavior must touch all four. startWorking at 160 lines is also doing too much (git + state + owner) inline.

**Recommendation.** Extract a private `applyOptimisticUpdate(refs: Collection<String>, transform: (RallyArtifact) -> RallyArtifact, client: RallyApiClient)` helper that performs the three-step triad plus the detail-panel sync. Call sites reduce to a single line. The helper signature already matches `patchArtifactsInModel`'s existing `Collection<String>` overload so there is no new type complexity. Note that `startWorking` guards the patch inside `if (stateChangeSucceeded)` — the helper should be called only inside that guard, which is fine. The `changeState` multi-select path uses a `ConcurrentHashMap.KeySetView<String>` (which implements `Collection<String>`) as the ref set, so it fits the same signature without widening. The `startWorking` function's overall length (160 lines) is legitimately a separate concern: the git + state + owner sequencing and the complex error-accumulation logic are intentional and hard to shrink without introducing a dedicated action class. Focus the refactor on the epilogue helper only; do not restructure `startWorking`'s business logic in the same change. Severity is low rather than medium because all four copies are correct and consistent; the only real risk is a future maintainer changing one copy and missing the others, which the cross-reference comments partially mitigate.

**Verifier.** All four functions were read and verified. Each contains the same three-step optimistic-update triad on the EDT side: `allArtifacts = allArtifacts.map { ... }`, `client.clearArtifactCache()`, `patchArtifactsInModel(refs, transform)`, followed by `artifactList.selectedValue?.let { sel -> if (sel.ref ...) detailPanel.showArtifact(sel, client) }`. Two instances (startWorking line 1952, finishWorking line 2008) carry explicit `// Keep the open detail panel's header in sync (see changeState)` comments. The editPoints instance (line 1715) carries a structurally similar comment but without the cross-reference. changeState (line 1801) carries a differently worded but semantically identical comment. The duplication is genuine: the grep confirms `patchArtifactsInModel` + `clearArtifactCache` + `allArtifacts.map` appear together four times in this same pattern (lines 1707-1710, 1795-1797, 1947-1951, 2005-2007). One minor inaccuracy: the reviewer's claim that the comment 'Keep the open detail panel's header in sync (see changeState)' appears four times is slightly wrong — it appears in exactly two places (startWorking and finishWorking); editPoints and changeState use different comment wording. This does not affect the validity of the duplication finding itself.

**Evidence.** The block `artifactList.selectedValue?.let { sel -> if (sel.ref ... ) detailPanel.showArtifact(sel, client) }` recurs at lines 1715-1717, 1801-1803, 1953-1955, 2009-2011, each preceded by a 'Keep the open detail panel's header in sync (see changeState)' comment.

---

#### LOW-45 · Three openInBrowser methods in detail panel share an identical URL-building block
`Code quality, maintainability & complexity` · category: quality · effort: S · conf: 0.97 · verdict: confirmed  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** openTestCaseInBrowser 756-763, openTaskInBrowser 788-795, openAttachmentInBrowser 959-966  

**Problem.** The three methods are identical except for the detail type segment ('testcase'/'task'/'attachment'): each does `selectedValue ?: return; currentClient ?: return; objectID ?: return; getParentProjectOid(); projectSegment = if (oid!=null) "${oid}d/" else ""; BrowserUtil.browse("${client.webBaseUrl}/#/${projectSegment}detail/<type>/$objectId")`. This also duplicates the buildWebUrl logic that already exists in RallyApiClient (lines 966-991), so the web-URL convention now lives in two places with two slightly different shapes.

**Impact.** Four total copies of Rally's web-URL convention (3 here + RallyApiClient.buildWebUrl). If the Rally URL scheme changes, or the project-OID handling needs a fix, it must be changed in all of them. Low severity (small methods) but a clear DRY/consistency gap.

**Recommendation.** Add a private helper in RallyDetailPanel:

```kotlin
private fun openInBrowser(objectId: String, detailType: String) {
    val client = currentClient ?: return
    val projectOid = getParentProjectOid()
    val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
    BrowserUtil.browse("${client.webBaseUrl}/#/${projectSegment}detail/$detailType/$objectId")
}
```

Then each method becomes a two-liner (guard + call). The three `selected.*` sources differ, so keep the per-method guards for `selectedValue` and `objectID` null checks before delegating to the helper.

Separately, extend `RallyApiClient.buildWebUrl` to handle "attachment" in its `when` block (currently falls through to the "detail" fallback), and consider whether the panel should call `buildWebUrl` instead — but that requires passing the specific list item as a `RallyArtifact` subtype, which it may not always be (e.g., `RallyAttachment`). The in-panel private helper is simpler and avoids the type-fitting problem.

**Verifier.** All three methods are present exactly as described. Reading the actual code confirms the duplication:

openTestCaseInBrowser (756-763):
```kotlin
val objectId = selected.objectID ?: return
val projectOid = getParentProjectOid()
val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
BrowserUtil.browse("${client.webBaseUrl}/#/${projectSegment}detail/testcase/$objectId")
```

openTaskInBrowser (788-795):
```kotlin
val objectId = selected.objectID ?: return
val projectOid = getParentProjectOid()
val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
BrowserUtil.browse("${client.webBaseUrl}/#/${projectSegment}detail/task/$objectId")
```

openAttachmentInBrowser (959-966):
```kotlin
val objectId = selected.objectID ?: return
val projectOid = getParentProjectOid()
val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
BrowserUtil.browse("${client.webBaseUrl}/#/${projectSegment}detail/attachment/$objectId")
```

And RallyApiClient.buildWebUrl (966-991) independently builds the same URL pattern using `normalizedServerUrl` (aliased as `webBaseUrl`), per-type detail page names, and the same project-OID extraction logic. The URL shapes are functionally identical, confirming two divergent implementations of the same convention. There is one additional nuance: `buildWebUrl` does not handle the "attachment" type (its `when` block maps to "detail" as the fallback), so the detail panel methods are not fully redundant with it — they independently handle attachment and task URLs that `buildWebUrl` doesn't cover. This makes the duplication slightly worse than described: `buildWebUrl` cannot currently be used as-is as the consolidation point for attachments without extending it.

**Evidence.** Lines 760-762, 792-794, 963-965 are identical save for the literal 'testcase'/'task'/'attachment' in the browse() URL.

---

#### LOW-46 · Linear 'find the tab whose title starts with X' scan reimplemented 4 times
`Code quality, maintainability & complexity` · category: quality · effort: S · conf: 0.97 · verdict: confirmed  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** loadTestSteps 704-716 and 735-741, showCreateTaskDialog 827-832, plus tab-rebuild guards in showArtifact 321 and clear 553  

**Problem.** The pattern `for (i in 0 until tabbedPane.tabCount) { if (tabbedPane.getTitleAt(i).startsWith("Test Steps"/"Tasks")) { ...; break } }` is written out four times, and the 'restore standard tabs if previously showing a test case' block (`if (tabbedPane.tabCount != 3 || getTitleAt(0).startsWith("Test Steps")) { removeAll(); addTab x3 }`) is duplicated verbatim between showArtifact (321-326) and clear (553-558). Tab identity is encoded in the human-readable title string, which is brittle (title also carries the count, hence startsWith).

**Impact.** Tab bookkeeping logic is smeared across the class and keyed on display titles; renaming a tab label, or changing the count format, can break the index lookups. Repetition makes the dynamic Test-Steps-tab behavior hard to follow and modify.

**Recommendation.** Add three private fields tracking the stable tab indices or component references, e.g. `private var stepsTabIndex = -1`, and a `private fun restoreStandardTabs()` that sets them back to the canonical [0=TestCases, 1=Tasks, 2=Attachments] state. Store the base label separately (`private val STEPS_LABEL = "Test Steps"`) so that count-bearing titles (`"Test Steps (5)"`) are composed from the constant, not matched against it. Replace all four scan loops with field reads; the defensive re-scan inside the async callback (lines 736-741) becomes unnecessary once `stepsTabIndex` is a field that `restoreStandardTabs()` resets to -1. Severity remains low: this is a maintainability issue, not a correctness or performance problem.

**Verifier.** All four title-scan patterns exist exactly as claimed:

1. Lines 705-710: `for (i in 0 until tabbedPane.tabCount) { if (tabbedPane.getTitleAt(i).startsWith("Test Steps")) { stepsTabIndex = i; break } }` — initial tab search in loadTestSteps()
2. Lines 736-741: An identical loop (`var currentStepsTab = -1; for (i in ...) { if (...startsWith("Test Steps")) { currentStepsTab = i; break } }`) — re-find in the async callback within the same loadTestSteps()
3. Lines 827-832: `for (i in 0 until tabbedPane.tabCount) { if (tabbedPane.getTitleAt(i).startsWith("Tasks")) { tabbedPane.setTitleAt(i, "Tasks ($count)"); break } }` — in showCreateTaskDialog()
4. Lines 321-326 and 553-558: The tab-restore block `if (tabbedPane.tabCount != 3 || (tabbedPane.tabCount > 0 && tabbedPane.getTitleAt(0).startsWith("Test Steps"))) { tabbedPane.removeAll(); addTab x3 }` is byte-identical in showArtifact() and clear().

No helpers (`findTabByPrefix`, `restoreStandardTabs`, constants like `STEPS_TAB_INDEX`) exist — only a bare `private val tabbedPane = JBTabbedPane()`. Tab identity is entirely encoded in human-readable strings with startsWith(), meaning tab counts embedded in titles (e.g., "Test Steps (5)") require startsWith rather than equality, which is the brittleness the reviewer identified. The second loop inside the async callback in loadTestSteps() is there precisely because tabs can change between the initial call and the callback — which is a real-world concern but also evidence that the design is fragile enough to require a defensive re-scan.

**Evidence.** Identical title-scan loops at lines 705-710, 736-741, 827-832; the tab-restore block at 321-326 is byte-identical to 553-558.

---

#### LOW-47 · Pervasive fully-qualified names and inline java.* references hurt readability
`Code quality, maintainability & complexity` · category: quality · effort: S · conf: 0.97 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** throughout, e.g. 134, 490-552, 1597-1630, 1763-1784; also RallyApiClient uses inline com.intellij.notification.* at 870-875  

**Problem.** Many call sites use fully-qualified names inline instead of imports: `java.util.concurrent.atomic.AtomicLong`, `java.util.concurrent.CompletableFuture.supplyAsync(...)` (repeated ~10x in loadTickets/export/changeState), `java.util.concurrent.atomic.AtomicInteger`, `com.intellij.openapi.util.text.StringUtil.escapeXmlEntities`, `java.awt.Dimension`, `javax.swing.JPanel`/`JLabel` inside startWorking's anonymous dialog. RallyApiClient inlines `com.intellij.notification.NotificationGroupManager`/`NotificationType`.

**Impact.** Lines are long and visually noisy, making the already-large methods harder to scan and review. It is a consistency smell — some of these types are imported elsewhere in the same file — and it slightly raises the bar for spotting what a block actually does.

**Recommendation.** Add imports for the repeatedly-used types: `java.util.concurrent.CompletableFuture`, `java.util.concurrent.atomic.AtomicInteger`, `java.util.concurrent.atomic.AtomicLong`, `java.util.concurrent.ConcurrentHashMap`, `java.util.concurrent.ExecutionException`, `java.awt.Dimension`, `java.awt.GridBagConstraints`, `java.awt.GridBagLayout`, `java.awt.Insets`, `com.intellij.openapi.util.text.StringUtil`, and `com.intellij.notification.NotificationGroupManager`/`NotificationType` (in RallyApiClient). An IDE "Optimize Imports" pass handles all of these automatically. Special note: `javax.swing.JPanel` and `javax.swing.JLabel` at lines 1855/1862 are already covered by the existing `javax.swing.*` wildcard import at line 46 — their FQN usage is not just noisy but actively incorrect style. Priority target for cleanup is the `CompletableFuture` + `AtomicInteger` cluster in loadTickets/export/changeState since those are the hot code paths reviewers read most often.

**Verifier.** The actual code contains pervasive inline fully-qualified names exactly as described. In RallyToolWindowPanel.kt: `java.util.concurrent.CompletableFuture` appears 8+ times with no import (lines 520, 526, 541, 550, 1597, 1630, 1771, 1784); `java.util.concurrent.atomic.AtomicInteger` 5 times (1588-1591, 1763-1765); `java.util.concurrent.atomic.AtomicLong` once (134); `java.awt.Dimension` 7 times (211, 215, 217, 236, 253, 285, 318, 1280, 1520) while other `java.awt.*` types like `BorderLayout`, `FlowLayout`, `Component` are imported; `java.awt.GridBagLayout`, `java.awt.GridBagConstraints`, `java.awt.Insets` inside the createCenterPanel dialog; `javax.swing.JPanel`/`javax.swing.JLabel` at lines 1855/1862 even though `javax.swing.*` wildcard is already imported at line 46; `com.intellij.openapi.util.text.StringUtil.escapeXmlEntities` twice (755, 757) and once more (1637). In RallyApiClient.kt: `java.util.concurrent.atomic.AtomicLong` at line 170, and `com.intellij.notification.NotificationGroupManager`/`NotificationType` at lines 870/875 with no imports despite `java.util.concurrent.ExecutorService`/`Executors` being explicitly imported at top. No documented trade-off in CLAUDE.md justifies this pattern. The `javax.swing.JPanel`/`JLabel` cases are the most egregious because `javax.swing.*` is already a wildcard import — the FQNs are redundant on top of being noisy.

**Evidence.** loadTickets uses `java.util.concurrent.CompletableFuture.supplyAsync({ ... }, client.apiExecutor)` and `java.util.concurrent.CompletableFuture.runAsync(...)` repeatedly (lines 526, 541, 550); export/changeState repeat `java.util.concurrent.atomic.AtomicInteger(0)` (1588-1591, 1763-1765).

---

#### LOW-48 · loadState migration path sets apiKeyLoaded=true before the keychain write, masking a stale read
`Code quality, maintainability & complexity` · category: quality · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/settings/RallySettings.kt`  
**Location:** loadState 39-57 (migration branch 43-53)  

**Problem.** On migration the code sets cachedApiKey = keyToMigrate and apiKeyLoaded = true synchronously, then writes to PasswordSafe on a pooled thread. That is correct for the cached value, but the two readiness paths (migration vs loadApiKeyFromPasswordSafe) duplicate the synchronized notify dance with subtly different ordering, and noStateLoaded/loadState/setter each re-implement the same 'assign cachedApiKey + apiKeyLoaded=true + notifyAll' block. The latch protocol is spread across four methods with no single helper.

**Impact.** The credential-readiness latch is security- and correctness-sensitive (a wrong read shows 'Not configured' or builds a client with an empty key). Having the assign+notify logic copy-pasted in loadState, noStateLoaded, loadApiKeyFromPasswordSafe, and the setter makes it easy for one path to drift (e.g. forget notifyAll), and the 2s blocking wait in the getter is a hidden cost duplicated in reasoning.

**Recommendation.** The title's claim about "masking a stale read" is a slight misread — the migration path correctly caches the key immediately (it is already known from the XML) before the async PasswordSafe write, so no stale read occurs. The genuine problem is the copy-paste latch pattern.

Extract a private inline helper:

```kotlin
private fun publishApiKey(value: String) {
    synchronized(apiKeyReady) {
        cachedApiKey = value
        apiKeyLoaded = true
        apiKeyReady.notifyAll()
    }
}
```

Then replace all three call sites:
- `loadState` migration branch: `publishApiKey(keyToMigrate)`
- `loadApiKeyFromPasswordSafe` pooled thread: `publishApiKey(key)`
- `apiKey` setter: `publishApiKey(value)`

The `loadApiKeyFromPasswordSafe` reset at line 73 (`cachedApiKey = null; apiKeyLoaded = false`) is intentionally NOT part of `publishApiKey` since it is a pre-load reset, not a publish. Consider naming it `resetApiKeyLatch()` for symmetry if desired.

This is a low-severity, genuine maintainability issue. The current code is functionally correct — all three copies are identical — but the latch protocol is correctness-sensitive enough that a single auditable implementation is worth the small refactor.

**Verifier.** The code contains exactly what the reviewer described. The synchronized latch pattern `synchronized(apiKeyReady) { cachedApiKey = ...; apiKeyLoaded = true; apiKeyReady.notifyAll() }` is hand-written three times:

1. Lines 46-50: migration branch in `loadState`
2. Lines 76-80: inside the pooled thread in `loadApiKeyFromPasswordSafe`
3. Lines 108-112: in the `apiKey` setter

The first half of the finding's title — "sets apiKeyLoaded=true before the keychain write, masking a stale read" — is correct in the sense that the migration branch (lines 46-50) sets the cache to `keyToMigrate` and signals readiness synchronously, BEFORE the PasswordSafe write happens on the pooled thread (lines 51-53). However, this is actually intentional and correct behavior: the key is already known from the cleartext XML, so there is nothing stale about caching it immediately. The PasswordSafe write is just persisting it for next time. So the first part of the title is slightly misleading — there is no actual stale-read problem in the migration path.

The real, confirmed finding is the second part: the `synchronized { cachedApiKey = ...; apiKeyLoaded = true; apiKeyReady.notifyAll() }` block is copy-pasted three times with no shared helper. This is a genuine maintainability/correctness-risk issue. For example, if someone added a fourth path and forgot `notifyAll()`, or forgot to set `apiKeyLoaded = true`, an off-EDT `apiKey` getter would block for the full 2-second deadline. The latch protocol is correctness-sensitive (a broken write path makes the plugin appear unconfigured or blocks UI threads), so having it scattered without a single `publishApiKey()` helper is a real code-quality concern. The evidence is exactly as cited.

**Evidence.** The block `synchronized(apiKeyReady) { cachedApiKey = ...; apiKeyLoaded = true; apiKeyReady.notifyAll() }` appears at lines 46-50 (migration), 76-80 (PasswordSafe load), and 108-112 (setter), each hand-written.

---

#### LOW-49 · Deprecated cleartext apiKey field still lives in the persisted State data class
`Code quality, maintainability & complexity` · category: quality · effort: S · conf: 0.85 · verdict: confirmed  
**File:** `…/settings/RallySettings.kt`  
**Location:** State 18-28 (field 20-21), loadState migration 43-53  

**Problem.** State carries `@Deprecated("Use PasswordSafe via apiKey property instead") var apiKey: String = ""`. This is intentional migration scaffolding (read once, blanked, written to PasswordSafe), but it is unlabeled as transitional in CLAUDE.md and a fresh reader can't tell whether the field is still a live persistence sink. The @Suppress("DEPRECATION") at the read site is the only signal.

**Impact.** Low — it works and is needed for one-time migration. But indefinitely retaining a deprecated cleartext field in the serialized model is a maintainability/clarity hazard: future contributors may re-use it, and it leaves a cleartext key in any settings XML written before migration completes.

**Recommendation.** Add a KDoc comment directly on the deprecated `apiKey` field in `State` explaining it is one-time migration scaffolding — read once when a pre-PasswordSafe settings XML is loaded, immediately blanked, and written to the system keychain. Include a note about when it is safe to remove (e.g., "// TODO: remove once no installs older than [version] are expected in the wild, or after the next major release cycle"). Example:

```kotlin
/** Migration-only: populated only from settings XML written before PasswordSafe migration.
 *  Blanked immediately in [loadState] after being migrated to PasswordSafe.
 *  TODO: Remove after [next major release] once all pre-migration installs have been upgraded. */
@Deprecated("Use PasswordSafe via apiKey property instead")
var apiKey: String = "",
```

No behavioral change is needed. The migration logic itself is correct and safe.

**Verifier.** The actual code at lines 20-21 shows `@Deprecated("Use PasswordSafe via apiKey property instead") var apiKey: String = ""` inside the serialized `State` data class. The migration logic at lines 41-56 is correct: it reads the field, immediately blanks it (`state.apiKey = ""`), migrates the value to PasswordSafe, then signals readiness via the latch. The migration comment at line 41 (`// Migrate cleartext API key to PasswordSafe off-EDT`) explains what the migration code does, but there is no comment on the field declaration itself explaining that it exists solely as backward-compatibility scaffolding and can be removed once no installs older than the PasswordSafe migration floor remain in the wild. A future contributor reading only the `State` data class sees a deprecated field with no lifecycle note — no "safe to delete after version X" marker, no explicit statement that this is a one-time-read-and-clear pattern. The `@Deprecated` annotation alone does signal something is wrong with using it, and `@Suppress("DEPRECATION")` at the read site adds a clue, but neither explains when the field can be dropped. The finding is accurate in its diagnosis and impact assessment.

**Evidence.** Line 20-21: `@Deprecated("Use PasswordSafe via apiKey property instead") var apiKey: String = ""` inside the serialized State data class, consumed only by the migration branch at 43-53.

---

#### › Security & sensitive-data handling

#### LOW-50 · Rally-controlled FormattedID flows unsanitized into git branch name
`Security & sensitive-data handling` · category: security · effort: S · conf: 0.85 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** lines 1822, 1880; startWorking() — branchName = "${dialog.prefixCombo.selectedItem}/$ticketId"  

**Problem.** ticketId is taken directly from selected.formattedID (Rally API data) at line 1822 and concatenated into branchName at line 1880 with no validation. It is then passed to RallyGitOps.createOrCheckoutBranch -> GitBrancher.createBranch/checkout. Every other place that consumes Rally-controlled strings in this codebase is hardened (query escaping, safeResolve, XML-escaping for balloons), but the branch-name path is not. A FormattedID is normally well-formed (e.g. US1234), but it is server-controlled untrusted input; a value containing git ref metacharacters or path segments (e.g. '../', spaces, '..', leading '-') would be handed to the Git plugin's ref-creation API unchecked.

**Impact.** A malicious or corrupted Rally instance could cause creation of an unexpected/invalid git ref, a ref leading-dash that some git tooling treats as an option, or a confusingly-named branch. Real exploitability is bounded because GitBrancher validates refs and FormattedIDs are constrained server-side, so this is integrity/robustness hardening rather than a clear RCE — but it is the one untrusted-data sink in the codebase that lacks the sanitization applied everywhere else.

**Recommendation.** Add a single validation step in `startWorking()` between line 1822 and 1825, after the null check on `ticketId`:

```kotlin
val formattedIdPattern = Regex("^(US|DE|TA|TC|S-|F|PI)\\d+$", RegexOption.IGNORE_CASE)
if (!formattedIdPattern.matches(ticketId)) {
    Messages.showErrorDialog(project, "Ticket ID '$ticketId' is not a valid FormattedID and cannot be used as a branch name component.", "Rally")
    return
}
```

This is a pure correctness guard — it documents the expected invariant, surfaces any unexpected server response immediately with a clear message, and requires zero ongoing maintenance because FormattedID shape is stable in Rally WSAPI. Do not attempt to sanitize/mangle an invalid FormattedID into a valid ref; reject it and show a dialog instead. No change to `RallyGitOps` is needed because GitBrancher's own ref validation will catch the downstream case anyway — this guard just makes the error message user-friendly rather than a cryptic git failure.

**Verifier.** The code at lines 1822 and 1880 of RallyToolWindowPanel.kt does exactly what the finding claims. `ticketId` is read directly from `selected.formattedID` (a Gson-deserialized Rally API field) with no validation, then concatenated into `branchName = "${dialog.prefixCombo.selectedItem}/$ticketId"` and passed without any sanitization to `RallyGitOps.createOrCheckoutBranch`, which calls `brancher.createBranch(branchName, ...)` or `brancher.checkout(branchName, ...)` directly. There is no regex check, no allowlist, and no sanitizer anywhere in the path. The contrast with the rest of the codebase is real: query values use `escapeQueryValue`, balloon messages use `escapeXmlEntities`, file paths use `safeResolve`, but the git branch name path is unsanitized.

However, real-world exploitability is further bounded than the reviewer states: (a) Rally's own server enforces the FormattedID format (e.g. US1234, DE5678) as a server-generated identifier — it is not user-supplied text that round-trips; (b) IntelliJ's GitBrancher delegates to native git or JGit which validates ref names via `git check-ref-format` semantics, so an invalid ref would produce an error string that `verifyCheckout` catches and surfaces to the user rather than silently succeeding; (c) if an attacker already controls the Rally WSAPI server, they have much broader attack surface than git ref naming. The finding's own characterization of this as "integrity/robustness hardening" rather than RCE is accurate. Low severity is the right call.

**Evidence.** val ticketId = selected.formattedID  ...  val branchName = "${dialog.prefixCombo.selectedItem}/$ticketId"

---

#### LOW-51 · API key sent over plaintext HTTP with only a log warning
`Security & sensitive-data handling` · category: security · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/api/RallyApiClient.kt`  
**Location:** lines 105-110 (init), 462/1007/1471 (zsessionid header on every request)  

**Problem.** When the configured Server URL uses http://, the constructor only emits LOG.warn (line 109) and proceeds. The zsessionid API key is then attached to every GET/POST/download request (lines 462, 1007, 1471). Over plain HTTP the key is exposed in cleartext to anyone on the network path. requireSameHost even allows http when the base is http, so there is no downgrade protection in that mode.

**Impact.** On an http:// Rally endpoint (the code explicitly anticipates on-prem HTTP gateways), the long-lived API key travels unencrypted on every request and can be sniffed, granting full Rally access as the key owner. The risk surfaces only when the user configures http, but the warning is buried in the IDE log where users will not see it.

**Recommendation.** The finding and its recommendation are sound. The severity is correctly low because http:// must be deliberately configured (the default is https://rally1.rallydev.com), so this only bites users who explicitly choose an insecure endpoint. The concrete fix: in `RallySettingsConfigurable.apply()`, after saving the server URL, check if it starts with "http://" and, if so, call `Messages.showWarningDialog(...)` (or a non-blocking `BalloonBuilder` balloon) alerting the user their API key will travel unencrypted. Alternatively — or additionally — add the same check in `testConnection()` so it appears inline in the success message: "Connected (WARNING: server URL uses plain HTTP — API key is transmitted unencrypted)". Either approach puts the warning in front of the user at the moment they configure or test the setting, without blocking the http-by-choice on-prem use case. The LOG.warn can remain as a secondary trace.

**Verifier.** The code at lines 105-110 of RallyApiClient.kt is exactly as described. When `allowedScheme == "http"`, the constructor emits a single `LOG.warn(...)` to the IDE diagnostic log and proceeds without any further guard. The `zsessionid` API key is then attached as an HTTP header on every GET (line 462), POST (line 1007), and download (line 1471) request. There is no check for the HTTP scheme in `RallySettingsConfigurable.apply()`, `testConnection()`, or anywhere in the tool window panel — the warning is completely invisible to a typical user who does not monitor the IDE log. The `requireSameHost` method enforces host/scheme/port consistency between the base URL and any downstream `_ref` URL but explicitly allows http when the configured base is http, providing no downgrade protection. The finding's description of the code, the locations, and the impact are all accurate. The claim that the warning is "buried in the IDE log where users will not see it" is correct — there is no balloon, dialog, or in-panel indicator surfacing this to the user in the settings configurable or at tool-window init time.

**Evidence.** if (allowedScheme == "http") { ... LOG.warn("Rally server URL uses plaintext http:// — the API key is transmitted unencrypted. Use https:// if possible.") }

---

#### LOW-52 · Untrusted Rally rich-text HTML rendered in JTextPane (residual parser/resource surface)
`Security & sensitive-data handling` · category: security · effort: M · conf: 0.75 · verdict: needs-nuance  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** lines 95-99 (descriptionPane), 979-1031 (wrapHtml), 70-73 (EXTERNAL_SRC_PATTERN)  

**Problem.** The description pane is a JTextPane with contentType=text/html that renders Rally-authored rich-text essentially as-is (only </body>/</html> are escaped, inline colors stripped, and external/protocol-relative src neutralized; whole <style>/<link> are stripped in RallyHtmlUtils). There is no allow-list sanitization of tags/attributes. Swing's HTMLEditorKit does not execute JavaScript, so this is not classic XSS, and the external-fetch SSRF vector is closed by EXTERNAL_SRC_PATTERN (line 1005) plus EMBEDDED_STYLESHEET stripping — both correct and tested. The residual surface is: (a) reliance on the regex-based src neutralizer keeping pace with every HTMLEditorKit resource-loading construct (the pattern matches any src= attribute, which is good, but is regex-on-HTML and assumes '>' not inside attribute values), and (b) feeding arbitrary attacker-influenced markup to Swing's HTML parser.

**Impact.** No code execution is possible via Swing HTML. The practical worst case is a malformed/hostile description that renders oddly or, if the src neutralizer were ever bypassed by an exotic construct, an off-host network fetch leaking the viewer's IP/timing (the zsessionid is NOT attached to HTMLEditorKit's own fetches, so the key itself does not leak via this path). Low risk, but it is the one place untrusted HTML is parsed without a positive allow-list.

**Recommendation.** The existing defense-in-depth (EXTERNAL_SRC_PATTERN + EMBEDDED_STYLESHEET stripping) is correctly implemented and tested. The real gaps are: (1) the regex does not handle `>` inside attribute values (a known documented limitation), which could allow a crafted `src="http://evil.com/t>" width="100"` to survive neutralization; and (2) `<base href="...">` is not stripped, which would redirect all relative URL resolutions in the pane. To close these: strip `<base>` tags in stripInlineColors alongside `<style>/<link>`; optionally add a concrete HTMLEditorKit subclass override of `getStyleSheet().importStyleSheet()` or override the factory's `createImageView()` to return a no-op view for any src that isn't a `data:` URI, providing a hard backstop that doesn't rely on regex correctness. Do not add a full allow-list sanitizer - Rally descriptions contain legitimate tables, lists, and code blocks that would need to survive - but the two targeted additions above close the remaining surface without regression.

**Verifier.** The code exactly matches what the reviewer describes. The JTextPane is configured with contentType="text/html" and isEditable=false (lines 95-99). The EXTERNAL_SRC_PATTERN (lines 70-73) zeroes out http(s):// and protocol-relative src attributes in wrapHtml (line 1005). The stripInlineColors call in wrapHtml removes style/link elements via EMBEDDED_STYLESHEET before the src neutralizer runs.

The reviewer's characterization of the defenses is accurate and fair: the neutralization pipeline is well-designed (choke-pointed through wrapHtml, documented, tested), and Swing's HTMLEditorKit genuinely cannot execute JavaScript. The `<link>` vector is already closed by EMBEDDED_STYLESHEET stripping in stripInlineColors, not only by EXTERNAL_SRC_PATTERN - so the reviewer's claim that EXTERNAL_SRC_PATTERN must "keep pace with every HTMLEditorKit resource-loading construct" is partially overstated; the two complementary patterns together cover both vectors.

The residual risk is real but narrower than presented: (a) EXTERNAL_SRC_PATTERN is regex-on-HTML and doesn't handle `>` inside attribute values (documented in the code and CLAUDE.md), meaning a crafted attribute like `src="http://evil.com/t>" width="100"` would not match. (b) Other HTML resource-fetching constructs beyond `src=` and `<link>` are not neutralized - for example a `<base href="...">` tag would redirect all relative URL resolutions, and `<form action="...">` exists (though no form submission is triggered in a static pane). (c) No explicit `putClientProperty` or HTMLEditorKit subclass is used to globally disable async image loading, so the defense is entirely regex-dependent.

However, the threat model is an IDE plugin for corporate Rally users where an "attacker" must have write access to Rally descriptions to target a specific developer's plugin render. This is a low-probability insider threat. The worst case is an off-host timing/IP leak via a regex edge-case bypass - not credential leak (zsessionid not attached to HTMLEditorKit fetches as noted) and not code execution. The severity is correctly assessed as low. The recommendation to add a hard backstop by disabling HTMLEditorKit image loading is sound additional defense-in-depth, but the finding overstates the fragility of the current approach and understates how much coverage the EMBEDDED_STYLESHEET strip adds.

**Evidence.** private val descriptionPane = JTextPane().apply { contentType = "text/html"; isEditable = false ... }   and   val neutralized = EXTERNAL_SRC_PATTERN.matcher(decolored).replaceAll("src=\"\"")

---

#### LOW-53 · safeResolve does not resolve symlinks in the export parent directory
`Security & sensitive-data handling` · category: security · effort: S · conf: 0.85 · verdict: needs-nuance  
**File:** `…/util/RallyFileUtils.kt`  
**Location:** lines 54-62, safeResolve(); plus all RallyExporter Files.write call sites (RallyExporter.kt 182, 462, 567, 650)  

**Problem.** safeResolve sanitizes the child filename and verifies normalize().startsWith(parent) to block '../' traversal, which is correct and tested. However, as its own KDoc acknowledges, normalize() does not resolve symlinks: if the export parent directory (or an intermediate dir the export creates) already contains a symlink pointing outside, a write lands outside the intended tree. The exporter creates subdirectories like '${artifactId}_attachments' and '${artifactId}_images' (RallyExporter.kt 495, 607) whose names derive from the Rally FormattedID (sanitized) — the containment check covers the name but not pre-existing symlinks at the destination.

**Impact.** Within the stated threat model (user picks the export root via a folder chooser; Rally cannot create symlinks there) this is acceptable and the documented trade-off holds. It becomes a real write-outside-tree primitive only if the export root is attacker-influenced or already contains hostile symlinks — not the normal case for this plugin. Flagged for completeness because the containment guard is the sole protection and its limitation is load-bearing.

**Recommendation.** No change is needed for the current threat model. The limitation is already correctly documented in the KDoc. The confidence should be higher (0.85) than originally claimed because the behavior is unambiguous — `normalize()` provably does not resolve symlinks, and the KDoc documents this explicitly. The subdirectory names at lines 495 and 607 pass through `sanitizeFileName` (restricted to `[a-zA-Z0-9._\-()[] ]`), so a hostile FormattedID cannot produce a symlink-looking name. If a future feature adds bulk export to less-trusted directories, call `parent.toRealPath()` before constructing `normalizedParent` — but only after `Files.createDirectories(parent)` since `toRealPath()` requires the path to exist. This is a documented, intentional trade-off, not an oversight.

**Verifier.** The code is exactly as described: `safeResolve` at lines 54-62 uses `normalize().startsWith(normalizedParent)` to guard against path traversal, and the KDoc at lines 47-52 explicitly documents the symlink limitation. The `Files.write` call sites at lines 182, 462, 567, and 650 all go through `safeResolve`. The subdirectory creation calls at lines 495 and 607 also use `safeResolve`.

The finding is factually accurate. The nuance that makes this "needs-nuance" rather than "confirmed" or "refuted" is about severity and completeness of the threat model assessment:

1. The finding correctly identifies the symlink limitation and correctly states it is documented and acceptable for the plugin's threat model (user-chosen export root, Rally API cannot create symlinks).

2. However, the finding slightly overstates the attack surface by calling this a "real write-outside-tree primitive" if the export root contains hostile symlinks — in practice, if an attacker can already place symlinks inside the user's chosen export root folder, they have filesystem write access to that directory, which is a far more fundamental security problem than path traversal guards.

3. The finding also notes subdirectory names derive from Rally FormattedIDs (sanitized via `sanitizeFileName`). These are `US12345_attachments` / `US12345_images` format — extremely constrained character sets from `RE_UNSAFE = Regex("[^a-zA-Z0-9._\\-()\\[\\] ]")` — so an attacker-controlled FormattedID cannot produce a directory name that itself looks like a symlink.

4. The severity "low" is appropriate but the confidence of 0.6 is a bit low — the code clearly has the stated behavior (symlink non-resolution), and the threat model assessment is clearly documented in the KDoc. This is a well-understood, documented trade-off, not an overlooked bug.

5. The recommendation to "keep as-is" is correct. If `toRealPath()` were called on the parent, it would require the directory to exist first (since `toRealPath()` on a non-existent path throws IOException), complicating the flow for new export directories. The current approach is appropriate.

**Evidence.** // NOTE: normalize() does not resolve symlinks. If the caller-supplied `parent` already contains a symlink to an outside location, a write through that symlink will land outside the intended directory.

---

#### LOW-54 · Full Rally response bodies retained in exceptions and surfaced via e.message
`Security & sensitive-data handling` · category: security · effort: S · conf: 0.80 · verdict: needs-nuance  
**File:** `…/api/RallyApiClient.kt`  
**Location:** handleResponse() lines 480-504 (responseBody passed into RallyApiException); messages surfaced at RallyToolWindowPanel.kt 616/972/1152/1406/1723 and RallySettingsConfigurable.kt 219  

**Problem.** On non-200 responses, handleResponse stores response.body() into RallyApiException.responseBody (lines 483-502). The exception messages built elsewhere (e.message) are then shown in dialogs/balloons/empty-text. The body never contains the API key (the key is request-side only), but on a 200-but-error or verbose 4xx Rally can echo back artifact field content or query fragments. The messages are XML-escaped before HTML balloons (good), and plain dialogs are non-HTML, so this is not an injection issue — it is a minor information-exposure/log-hygiene note: artifact content can appear in user-visible error text and in LOG.warn output.

**Impact.** No credential leakage. Low-severity data-exposure: sensitive artifact text could appear in IDE error UI or the idea.log on failures. Acceptable for a single-user dev tool; noted because responseBody is captured but, helpfully, RallyApiException.toString() (lines 30-37) deliberately omits it — the message path is the only exposure.

**Recommendation.** The reviewer's mechanism is wrong: responseBody does not leak via e.message. The real (milder) issue is that Rally-provided error arrays are interpolated directly into the exception message at query/mutation call sites (lines 644, 839, 925, 1145, 1166, 1188, 1537, 1575, 1611, 1649), and these do reach UI dialogs and LOG.warn. For a single-user dev tool this is acceptable — the error text helps developers diagnose query problems. No action required. If tightening is ever desired, the LOG.warn call sites could log the full error detail while the user-visible message shows only a short summary like "Rally returned N error(s) — see idea.log for details." The HTML-balloon path already correctly XML-escapes the message, so injection is not a concern at any call site.

**Verifier.** The finding's central claim — that `response.body()` stored in `responseBody` is surfaced via `e.message` — is structurally wrong. `RallyApiException` calls `super(message)` with the curated developer-written string, and `e.message` returns only that curated string. The `responseBody` field is completely separate and is correctly excluded from `toString()`. So the "full response body" does NOT leak through `e.message` at the cited `handleResponse()` throw sites.

However, there is a real but differently-located information-exposure issue: multiple other throw sites interpolate server-returned error arrays directly into the exception message parameter, so they DO reach `e.message` and therefore the UI:
- Line 644: `"Rally query error: ${result.queryResult.errors.joinToString("; ")}"` — Rally's errors array content goes into message
- Line 839: `"Query failed - ${errors.joinToString("; ")}"` — composed of earlier e.message from nested exceptions, which can include Rally error text
- Line 925: `"Search failed - ${errors.joinToString("; ")}"`
- Lines 1145, 1166, 1188, 1537, 1575, 1611, 1649: `${errors.joinToString()}` from OperationResult error arrays

These server-provided error strings reach the UI via the e.message call sites (lines 616, 972, 1152, 1406, 1723 in RallyToolWindowPanel, and 219 in RallySettingsConfigurable). The balloon path (notifyLoadFailure) correctly XML-escapes the message before HTML rendering, preventing injection. The Messages.showErrorDialog() paths use plain text, so injection is not a concern there either.

The actual exposure is Rally API error messages (which can contain artifact names or query fragments echoed back) appearing in IDE error dialogs and in LOG.warn output — not the full raw HTTP response body. This is a milder form of the same information-exposure concern the reviewer noted, just at the wrong code layer.

**Evidence.** 404 -> throw RallyApiException("Rally API endpoint not found. Check your server URL.", 404, response.body())  ... and  override fun toString() { append("RallyApiException: $message"); if (statusCode != null) append(" (HTTP $statusCode)") }  (body intentionally not appended)

---

#### › Threading & EDT correctness

#### LOW-55 · All background invokeLater calls use implicit NON_MODAL modality, so UI updates are deferred while a modal dialog is open
`Threading & EDT correctness` · category: concurrency · effort: M · conf: 0.85 · verdict: needs-nuance · orig=medium  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** invokeLaterIfAlive at lines 164-169; every callsite (e.g. 565, 1130, 1384, 1786, 1944, 2004) and RallyDetailPanel.kt invokeLater at 373, 431, 449, 464, 523, 819, 936; RallySettingsConfigurable.kt 79, 149, 187, 209, 217  

**Problem.** Every invokeLater in the codebase uses the no-arg overload. When invoked from a pooled thread, the platform schedules these runnables at ModalityState.NON_MODAL (the default for non-EDT callers). Any runnable scheduled while a modal DialogWrapper (CreateUserStoryDialog, CreateDefectDialog, CreateTaskDialog, Start Working dialog, Edit Points input, the Finish/State confirm dialogs) is on screen will NOT run until that dialog closes. Conversely, runnables that themselves open Messages dialogs from a background path can be queued behind/around an already-open modal in surprising order.

**Impact.** A status-label update, balloon, or list patch produced by a background task that completes while the user has a create/confirm dialog open is silently postponed until the dialog dismisses, making the UI look frozen or stale (e.g. 'Creating...' never updating, or a refresh that finished mid-dialog not reflecting until close). This is a latency/jank defect directly against the performance goal.

**Recommendation.** The only real fix needed is in `RallySettingsConfigurable.testConnection()` (lines 187, 209, 217 of RallySettingsConfigurable.kt). Replace the three bare `invokeLater` calls there with `invokeLater(runnable, ModalityState.any())` so feedback appears while the Settings dialog is still open:

```kotlin
// Line 187 — auto-fill username:
ApplicationManager.getApplication().invokeLater({
    if (usernameField?.text.isNullOrBlank()) usernameField?.text = apiKeyUserName
}, ModalityState.any())

// Lines 209, 217 — success/error dialogs:
ApplicationManager.getApplication().invokeLater({
    Messages.showInfoMessage(...)
}, ModalityState.any())
```

No changes are needed to `invokeLaterIfAlive` in `RallyToolWindowPanel.kt` or to the `RallyDetailPanel.kt` paths — those are called only after any relevant modal dialog is already closed, so the current NON_MODAL default is harmless there. Adding a `ModalityState` parameter to `invokeLaterIfAlive` (as the reviewer suggests) is optional cleanup but carries no practical impact given the current call sites.

**Verifier.** The reviewer correctly identifies that every `invokeLater` / `invokeLaterIfAlive` in the codebase uses the no-argument overload, which defaults to `ModalityState.NON_MODAL` when scheduled from a pooled (non-EDT) thread. That part of the finding is factually accurate.

However, the claimed impact is largely wrong for `RallyToolWindowPanel.kt`. Every create/confirm/start-working/finish-working/edit-points dialog in that file uses a *synchronous, blocking* call — `dialog.showAndGet()`, `Messages.showYesNoDialog()`, `Messages.showInputDialog()` — which returns only after the dialog is dismissed. The `executeOnPooledThread` dispatch (and the subsequent `invokeLaterIfAlive` calls) happen *after* the dialog is already closed. At that point there is no modal dialog on screen, so the NON_MODAL runnable executes immediately on the next EDT pump — no deferral occurs.

The one genuine case is `RallySettingsConfigurable.testConnection()` (lines 187, 209, 217). This method is called while the Settings dialog is still open, and the three `invokeLater` calls (auto-fill username at line 187; success message at line 209; error message at line 217) are scheduled from a pooled thread while the Settings modal dialog is active. With NON_MODAL scheduling, all three are queued and held until the user manually closes the Settings dialog — so "Test Connection" appears to hang: the "Connected successfully!" popup never appears as long as Settings is open. This is a real UX defect, though infrequent.

The `RallyDetailPanel.kt` paths (tab title/count updates) are triggered by ticket list selection, not by any dialog action, so no modal context is present during typical usage — no deferral issue there either.

Actual code at the cited location:
```kotlin
private inline fun invokeLaterIfAlive(crossinline action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed || disposed) return@invokeLater
        action()
    }
}
```
And the Settings case (line 209):
```kotlin
ApplicationManager.getApplication().invokeLater {
    Messages.showInfoMessage(
        "Connected successfully!\n\nAPI Key Owner: $apiKeyName ($apiKeyUserName)$usernameInfo",
        "Rally Connection"
    )
}
```

The real severity is low: it is a narrow UX glitch (test-connection feedback invisible while Settings is open), not a systemic "frozen UI" or "creating never updating" defect across the whole plugin as implied.

**Evidence.** private inline fun invokeLaterIfAlive(crossinline action: () -> Unit) { ApplicationManager.getApplication().invokeLater { if (project.isDisposed || disposed) return@invokeLater; action() } }

---

#### LOW-56 · resolveInlineImages joins on imageExecutor futures from an unbounded app-pool thread, parking a thread per concurrent description load
`Threading & EDT correctness` · category: concurrency · effort: M · conf: 0.85 · verdict: needs-nuance  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** resolveInlineImages lines 1040-1106 (val results = futures.map { it.join() } at 1091); called from loadAndRenderDescription on the app pooled thread (385, 479)  

**Problem.** loadAndRenderDescription runs on ApplicationManager.executeOnPooledThread (the shared, effectively-unbounded app pool). It calls resolveInlineImages, which submits up to 10 downloads to the 4-thread imageExecutor and then blocks the app-pool thread on futures.map { it.join() }. During rapid ticket switching, each selection spawns a fresh app-pool task that parks on this join until its (now superseded) images finish or the generation check short-circuits each download. The generation check inside each supplyAsync bails fast, so this is mostly self-limiting, but the join still holds an app-pool thread for the duration of any in-flight HTTP download started before the switch.

**Impact.** Under fast scrolling through tickets with image-heavy descriptions, several app-pool threads can be parked simultaneously on image joins. The app pool is unbounded so this won't deadlock, but it adds thread churn and delays unrelated IDE background work that shares that pool. Modest performance cost, not a freeze.

**Recommendation.** The finding's recommendation (use `CompletableFuture.allOf(...).thenApply { }`) is valid if you want to eliminate the parking entirely, but given this is a documented intentional trade-off (CLAUDE.md "Off-EDT description pipeline") the cost/benefit is low. If pursued: replace `val results = futures.map { it.join() }` with `CompletableFuture.allOf(*futures.toTypedArray()).thenApply { futures.map { f -> f.getNow(null) } }`, and restructure `loadAndRenderDescription` so the wrap/render chain continues in the `thenApply`/`thenAccept` callback rather than sequentially. Also correct the executor reference: the images run on `imageExecutor` (a dedicated 4-thread pool in `RallyDetailPanel`), not `client.apiExecutor`, so starvation of API queries is not a risk. If the goal is simply reducing parked threads, cancelling `imageExecutor` futures on generation advance (via a `CancellableFuture` wrapper or by tracking and calling `cancel(true)` on generation increment) would be more targeted, though mid-HTTP-transfer interruption is limited by Java's `HttpClient` cancellation semantics.

**Verifier.** The mechanics are correct: `resolveInlineImages` (lines 1060-1091) submits up to 10 image downloads to `imageExecutor` (a dedicated 4-thread fixed pool in `RallyDetailPanel`, NOT `client.apiExecutor` as the finding implies) and then blocks the calling app-pool thread via `futures.map { it.join() }` at line 1091. The calling thread is indeed from the unbounded IDE app pool (`ApplicationManager.executeOnPooledThread`). So one app-pool thread per concurrent description load is parked while image downloads run. The generation check at line 1062 and 1074 bails fast inside each `supplyAsync` when the selection changes, but any HTTP transfer already started by `imageExecutor` runs to completion before its future resolves and the join returns.

However, two important nuances: First, the finding misidentifies the executor — it claims images submit to `apiExecutor` (the shared 4-thread client pool), but the code actually uses `imageExecutor`, a **separate** dedicated 4-thread pool private to `RallyDetailPanel`. This distinction matters because starving `apiExecutor` would block all API queries, whereas starving `imageExecutor` only delays other image loads. Second, and more importantly, CLAUDE.md explicitly documents this as an intentional design decision (Performance Optimizations table, row "Off-EDT description pipeline"): "Description fetch + image resolution + wrapHtml run on the unbounded pooled thread, not apiExecutor/EDT — apiExecutor never parks on image joins; EDT stays responsive on multi-MB descriptions." The current design was deliberately chosen over running on apiExecutor precisely to prevent the 4-thread pool starvation that a prior design caused.

The underlying thread-parking reality is real — under rapid switching with image-heavy tickets, multiple app-pool threads can be parked simultaneously. But the impact is modest: the app pool is unbounded, IDE background work is not blocked from acquiring new threads (just competes with slightly more), and the generation guard means most downloads short-circuit quickly after the HTTP call returns. This is a genuine low-severity efficiency concern, not a hidden bug or undocumented trade-off.

**Evidence.** val results = futures.map { it.join() }  // on the app pooled thread, after submitting to the 4-thread imageExecutor

---

#### LOW-57 · getClient() rebuild path mutates shared @Volatile UI-loading flags and calls invalidateIterations() from arbitrary background threads
`Threading & EDT correctness` · category: concurrency · effort: M · conf: 0.80 · verdict: needs-nuance  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** getClient lines 2038-2061 (projectsLoaded = false; invalidateIterations() inside synchronized(clientLock))  

**Problem.** getClient() can be called from many different pooled-thread entry points (loadTickets, applySearchFilter server search, create/state/export/startWorking/finishWorking). When it detects a settings change it rebuilds the client and, still on that arbitrary background thread, sets projectsLoaded=false and calls invalidateIterations() (which mutates iterationsLoaded and bumps iterationLoadGeneration). These flags are read both on the EDT (the projectCombo/iterationCombo listeners gate on projectsLoaded/iterationsLoaded) and on other pooled threads. They are @Volatile so there is no torn read, but the combination of two independent flags plus a generation counter being flipped mid-flight from a thread that is not the one orchestrating loadTickets() can interleave with an in-progress loadTickets() that already read projectsLoaded as true, leading to a window where projects are considered loaded-then-not within one load cycle.

**Impact.** Edge-case only: an API-key/server change discovered via getClient() during, say, a server-search or export task can reset projectsLoaded/iterationsLoaded out from under a concurrent loadTickets(), causing a redundant project/iteration refetch or a transiently empty dropdown. Low severity because it is rare and self-heals on the next loadTickets(), but it is genuine cross-thread shared-mutable-state coupling that the rest of the code is careful to avoid.

**Recommendation.** The finding is real but already partially mitigated by the `iterationLoadGeneration` counter (which guards iteration correctness on concurrent loads). The actionable gap is limited to `projectsLoaded`: if a settings-change is detected by a concurrent export/search/state-change `getClient()` call mid-`loadTickets()`, it resets `projectsLoaded=false` after `loadTickets()` already skipped the project fetch, causing the next `loadTickets()` to do a redundant but harmless re-fetch.

The cleanest fix is to have `getClient()` return a boolean `clientRebuilt` flag instead of mutating `projectsLoaded`/`iterationsLoaded` directly, and have `loadTickets()` (the only function that reads and acts on these flags) perform the reset itself when it sees `clientRebuilt=true`. This keeps all load-flag lifecycle decisions inside the single orchestrating function. Alternatively, since the current defect is only a redundant API call (not a correctness failure), this is a low-priority cleanup item rather than a bug fix.

**Verifier.** The cited code exists exactly as described at lines 2038-2061: inside `synchronized(clientLock)`, on whatever pooled thread called `getClient()`, the code sets `projectsLoaded = false` and calls `invalidateIterations()` (which sets `iterationsLoaded = false` and bumps `iterationLoadGeneration`). This is a real cross-thread mutation of shared flags from an arbitrary background thread.

However, the claimed impact is overstated in one key dimension. The `iterationLoadGeneration` AtomicLong counter is precisely the protection against the worst-case scenario. Every in-flight `loadIterations()` call captures the generation at start (`val generation = iterationLoadGeneration.incrementAndGet()` at line 522) and checks `generation != iterationLoadGeneration.get()` before committing results to the dropdown (lines 699, 705, 735). So if `getClient()` bumps the generation mid-flight, the in-flight `loadIterations()` will detect staleness and abort — it will NOT commit wrong-project iteration results. This is documented in the CLAUDE.md and in code comments around line 128-134.

For `projectsLoaded`, the window described by the reviewer is: `loadTickets()` reads `projectsLoaded=true` (skipping `loadProjects()`), then a concurrent `getClient()` call from export/search sets `projectsLoaded=false`. The consequence is NOT that the current `loadTickets()` breaks — it already passed the guard. The consequence is that the *next* `loadTickets()` will unnecessarily re-fetch projects. `loadProjects()` is idempotent (overwrites `cachedProjects` with the same data, calls `invokeLater` to refresh the combo), so this causes a redundant API round-trip and a combo repopulation, not a correctness failure.

So the finding is real (the flags are mutated cross-thread from a non-orchestrating thread) but the severity is genuinely low — the generation counter already guards iteration correctness, and the project-reload case is just a redundant fetch. The recommendation to have `getClient()` only return the client and delegate flag resets to the EDT or the `loadTickets()` orchestrator is sound but low-priority.

**Evidence.** return synchronized(clientLock) { check(!disposed) {...}; if (currentClient?.matchesSettings(...) != true) { currentClient?.apiExecutor?.shutdown(); currentClient = RallyApiClient(...); projectsLoaded = false; invalidateIterations() } ... }

---

#### LOW-58 · RallySettingsConfigurable apiKey async load can overwrite a value the user is mid-typing on the reset() path
`Threading & EDT correctness` · category: concurrency · effort: S · conf: 0.85 · verdict: confirmed  
**File:** `…/settings/RallySettingsConfigurable.kt`  
**Location:** reset() lines 137-154 (invokeLater { apiKeyField?.text = key }) versus createComponent() lines 76-87 which guard with if (field.password.isEmpty())  

**Problem.** createComponent()'s async key load correctly guards the EDT write with `if (field.password.isEmpty())` so it won't clobber user typing. reset() does the same off-EDT load but unconditionally writes apiKeyField?.text = key on the EDT with no isEmpty() guard. Settings reset() can be invoked by the platform (Reset button / closing without apply) and the async PasswordSafe read may land after the user has begun editing, replacing their in-progress text.

**Impact.** Rare data-entry surprise: the API key field can be repopulated from storage while the user is typing a new key, after a Reset. Low severity (reset semantics arguably justify overwrite) but it is an inconsistency with the deliberately-guarded createComponent path and a cross-thread Swing write whose ordering versus user input is unspecified.

**Recommendation.** Mirror the isEmpty() guard from createComponent() in reset(): `if (apiKeyField?.password?.isEmpty() == true) { apiKeyField?.text = key }`. This makes the two async write paths consistent and prevents the narrow-window overwrite. If the intent of Reset is always to force-restore the saved value regardless of user input, document that explicitly and remove the guard from createComponent() instead — but the current inconsistency is confusing. The isEmpty() guard in reset() is the safer and more consistent fix.

**Verifier.** The code exactly matches the description. createComponent() (lines 79-86) guards its async EDT write with `if (field.password.isEmpty()) { field.text = key }`, but reset() (lines 149-152) does an unconditional `apiKeyField?.text = key` on the EDT after the pooled thread reads the stored key. The asymmetry is real and the reviewer's characterisation is accurate. That said, severity should remain low for two reasons: (1) Reset semantics intentionally restore saved values, so overwriting is at least arguably correct behaviour; (2) the race window is very narrow — PasswordSafe is a fast local/keychain read and the invokeLater callback typically fires within tens of milliseconds, making it very unlikely a user has begun typing in that window. The `createComponent()` guard was needed because the panel opens with a blank field and the initial render time gives users meaningful opportunity to start typing before the async result arrives; the Reset path starts with the field already populated so the race is even harder to hit in practice.

**Evidence.** ApplicationManager...executeOnPooledThread { val key = settings.apiKey; loadedApiKey = key; ApplicationManager...invokeLater { apiKeyField?.text = key; apiKeyLoaded = true } }  // reset(), no isEmpty guard

---

#### LOW-59 · wrapHtml runs multi-pass regex (stripInlineColors + external-src) on the EDT for the already-loaded description fast path
`Threading & EDT correctness` · category: performance · effort: S · conf: 0.85 · verdict: needs-nuance  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** showArtifact lines 347-353 (descriptionPane.text = wrapHtml(desc) on EDT); wrapHtml 979-1031; stripInlineColors does EMBEDDED_STYLESHEET + TAG_PATTERN + per-tag STYLE/PRESENTATIONAL passes  

**Problem.** When an artifact already carries a non-blank Description, showArtifact (which runs on the EDT as a selection listener) calls wrapHtml(desc) directly on the EDT. wrapHtml runs stripInlineColors (one EMBEDDED_STYLESHEET regex pass over the whole string, then a TAG_PATTERN tokenization with three nested regex replacements per tag) plus the EXTERNAL_SRC_PATTERN pass and two string replaces. For list-loaded rows this is a non-issue because LIST_FIELDS excludes Description, but the create* flows prepend a freshly created artifact that DOES carry the create-response Description, and after-state-change re-selection (changeState/editPoints/startWorking/finishWorking each call detailPanel.showArtifact(sel, client)) re-renders whatever description that row carries — all on the EDT.

**Impact.** For a large pasted-from-Word Rally description (tens of KB to low MB with many tags), the synchronous regex tokenization on the EDT can produce a perceptible hitch on selection/state-change. Bounded and rare, but it is exactly the kind of EDT-bound string scan the off-EDT description pipeline was built to avoid, and the fast path bypasses it.

**Recommendation.** The EDT `wrapHtml(desc)` call (line 349) is intentional and self-documented, but it produces wasted work because `loadAndRenderDescription` always overwrites it off-EDT anyway. The concrete improvement: replace the EDT fast path with the same "Loading description..." placeholder regardless of whether `desc` is already present, and let `loadAndRenderDescription` be the sole renderer. This eliminates one redundant multi-pass regex scan on the EDT on every pre-loaded-description selection, and is consistent with the documented off-EDT description pipeline principle. The change is safe because `loadAndRenderDescription` skips `fetchDescription` when `desc` is non-blank, so no extra network round-trip is added — the off-EDT `wrapHtml(desc)` result arrives quickly. If zero-latency display of cached text is desirable (before image resolution), add a size guard: only do the EDT `wrapHtml(desc)` when `desc.length` is below a small threshold (e.g., 4096 chars) where regex cost is provably sub-millisecond.

**Verifier.** The EDT `wrapHtml(desc)` call at line 349 is real and confirmed in the code. However, the finding misstates both the design intent and the actual impact.

**What the code actually does:**

At lines 347-353, if `artifact.description` is non-blank, `descriptionPane.text = wrapHtml(desc)` runs on the EDT as an immediate placeholder render. This is followed immediately (lines 402-480) by `executeOnPooledThread { ... loadAndRenderDescription(desc, ...) }`. Inside `loadAndRenderDescription` (lines 491-532), when `desc` is non-blank, it skips `fetchDescription`, runs `resolveInlineImages` off-EDT, calls `wrapHtml` off-EDT (line 522), then posts the result via `invokeLater`. The off-EDT result overwrites the EDT placeholder.

The codebase's own comment in `wrapHtml` (lines 1011-1014) explicitly acknowledges this dual-path behavior: "wrapHtml runs on a pooled thread for freshly fetched descriptions and on the EDT for the already-loaded fast path (showArtifact) — both are fine."

**What the finding gets right:**
- `wrapHtml` (with `stripInlineColors` + `EXTERNAL_SRC_PATTERN`) does run on the EDT in this path.
- The paths that trigger it are: (a) create-response artifacts that carry Description in the API response, and (b) re-selection after state-change operations (changeState/editPoints/startWorking/finishWorking call `showArtifact`).
- Normal list selections are NOT affected because LIST_FIELDS excludes Description, so `desc` is null/blank and the EDT calls `wrapHtml("<i>Loading description...</i>")` — a trivially cheap fixed string.

**What the finding gets wrong or overstates:**
- The EDT `wrapHtml(desc)` is the intentional design: show cached content immediately, then let the off-EDT pipeline overwrite with inline-image-resolved HTML. It is not a gap — it is explicit and documented in the code comment.
- The finding claims this bypasses the off-EDT pipeline, but the off-EDT `loadAndRenderDescription` runs in ALL cases (it is always called at line 479 for user stories/defects, at line 385 for test cases). The EDT path produces a quick placeholder; the pipeline's result overwrites it. Both `wrapHtml` calls run on every non-blank-description selection.
- This means `wrapHtml` actually runs TWICE for pre-loaded descriptions (once on EDT as placeholder, once off-EDT via `loadAndRenderDescription`) — the EDT call is wasted work since the off-EDT call overwrites it. This is a minor inefficiency the finding does not name explicitly.
- The performance risk is real but bounded: multi-pass regex over multi-KB HTML on the EDT could produce a sub-millisecond to low-millisecond hitch for typical Rally descriptions. A large Word-pasted description with hundreds of tags would be worse. But these paths (create-response + state-change re-selections) are uncommon compared to normal selection of list-loaded tickets.

**Severity reassessment:** Low. The affected paths are narrow (only artifacts carrying a pre-loaded description field), the wrapHtml cost is proportional to description size and tag count (typical: fast; pathological: potentially a few ms), and the code already documents this as intentional. The only concrete improvement would be skipping the EDT `wrapHtml(desc)` call altogether (let the off-EDT pipeline be the sole renderer, using a "Loading description..." placeholder even when desc is available) to eliminate the redundant regex pass and keep all multi-pass scans off the EDT.

**Evidence.** val desc = artifact.description; if (!desc.isNullOrBlank()) { descriptionPane.text = wrapHtml(desc); descriptionPane.caretPosition = 0 }  // EDT

---

#### LOW-60 · Detail-load test-case/task/attachment futures share the same 4-thread apiExecutor as the artifact list query, risking head-of-line stalls
`Threading & EDT correctness` · category: performance · effort: S · conf: 0.80 · verdict: needs-nuance  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** showArtifact lines 405-427 (tcFuture/taskFuture/attachFuture all submitted to client.apiExecutor); RallyApiClient.kt apiExecutor = newFixedThreadPool(4) at line 42  

**Problem.** The detail panel submits three concurrent queries (test cases, tasks, attachments) to client.apiExecutor, which is a fixed 4-thread pool also used by loadTickets() for the list query and sprint summary, by changeState/export bulk fan-out, and by server search. A single detail-panel open consumes up to 3 of the 4 threads. If a list reload or a multi-select bulk state change is in flight at the same time, the pool can be momentarily fully subscribed, serializing what looks like parallel work. The description path was deliberately moved OFF apiExecutor onto the app pool (good, documented), but tc/task/attach were left on it.

**Impact.** On a selection during an active refresh or bulk operation, detail-tab population can stall behind list/export work because they contend for the same 4 threads. Adds latency to the detail panel exactly when the user is also driving the list. The mitigation (description off apiExecutor) only covers one of four detail queries.

**Recommendation.** The issue is real but the practical impact is low. The worst-case overlap (list reload + detail open simultaneously) queues at most 2 tasks behind 4 running ones, adding roughly one network RTT of latency to the 5th and 6th detail or list tasks. No starvation or deadlock risk exists because the coordinator threads run on IntelliJ's unbounded pool, not apiExecutor.

If you want to eliminate the contention without adding a new executor (which would increase total thread count): note that `queryTestCases`, `queryTasksForWorkProduct`, and `queryAttachments` all have LRU-cached results. On re-selection of a previously-loaded ticket all three return immediately from cache, so the contention window is only on first selection. For a genuine fix, the most targeted change is to give the detail panel its own 2-3 thread executor (named `rally-detail-worker`) for tc/task/attach — mirroring the already-separate description path — so detail loads never queue behind list/export work. Alternatively, keep the status quo given that CLAUDE.md explicitly documents the 4-thread cap as a deliberate trade-off between saturation and parallelism, and this is a low-frequency overlap in normal use.

**Verifier.** The code exactly matches what the reviewer describes: `tcFuture`, `taskFuture`, and `attachFuture` in `RallyDetailPanel.showArtifact` (lines 405-427) all use `CompletableFuture.supplyAsync(..., client.apiExecutor)`, and `client.apiExecutor` is indeed `Executors.newFixedThreadPool(4)` (RallyApiClient.kt line 42). Concurrently, `loadTickets()` submits `artifactsFuture`, `sprintFuture`, and `iterationsFuture` all to `client.apiExecutor` as well (lines 526-552). So the maximum simultaneous submission is 6 tasks to a 4-thread pool: 2 tasks queue behind.

However, the severity and framing need correction on several points:

1. The coordinator threads (`executeOnPooledThread` callers for both `loadTickets` and `showArtifact`) run on IntelliJ's unbounded pool, not on `apiExecutor`. The `loadTickets` outer thread parks on `artifactsFuture.get()` on the IntelliJ pool — it does not consume an apiExecutor slot while waiting, so there is no deadlock risk (unlike `queryAllArtifacts` which is already guarded with a sequential comment at line 818-820 for exactly this reason).

2. Each apiExecutor thread blocks the full network round-trip via synchronous `httpClient.send()` (line 1047). With HTTP/2 multiplexing configured, the JDK HttpClient can pipeline requests over fewer TCP connections, but the *threads* are still parked per call. So 6 concurrent tasks with 4 threads means 2 tasks wait in the queue — not a stall but a brief ordering delay (one extra network round-trip's worth of latency for the 2 queued tasks).

3. CLAUDE.md documents the 4→4 thread reduction as a deliberate trade-off ("Reduced thread pool: API executor reduced from 8 to 4 threads — prevents thread saturation while maintaining parallelism"). The finding's claimed worst-case scenario — detail open *during* active list reload AND bulk state change — requires three simultaneous operations, which is uncommon in normal use. Bulk state change adds N futures (one per selected artifact) but is triggered manually while the list is shown, not typically during a reload.

4. The description pipeline was correctly moved off apiExecutor (as noted in CLAUDE.md and the comment at line 402-403), reducing the detail panel's footprint from 4 to 3 apiExecutor tasks. The finding correctly notes this.

5. The real concern is the occasional 1-2 task queueing delay under concurrent list-reload + detail-open, not a systematic head-of-line stall. The impact is a half-second additional latency at most in normal interactive use, not a freeze.

**Evidence.** val tcFuture = CompletableFuture.supplyAsync({ ... client.queryTestCases(artifactRef) ... }, client.apiExecutor)  // plus taskFuture, attachFuture, all on the same 4-thread pool

---

#### LOW-61 · Export completion balloon/status posted via invokeLater can be deferred indefinitely if a modal opens during long bulk export
`Threading & EDT correctness` · category: ux · effort: S · conf: 0.80 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** exportSelectedArtifact lines 1582-1660, completion invokeLaterIfAlive at 1632-1650  

**Problem.** Export runs entirely off-EDT (good) and joins all per-artifact futures, then posts the summary balloon and final status text via invokeLaterIfAlive (NON_MODAL). Bulk exports of many artifacts plus their test cases (each downloading attachments and inline images) can run for many seconds. If during that window the user opens any modal (e.g. a create dialog, Settings), the export-complete balloon and the 'Exported N artifact(s)' status update will not appear until the modal closes, so a finished export looks unfinished.

**Impact.** User-perceived stall: a successfully completed export gives no feedback while a modal is open, and the status label keeps showing 'Exporting...'. Cosmetic but undermines confidence in long operations, which is exactly where feedback matters most.

**Recommendation.** Add an overload of `invokeLaterIfAlive` that accepts an explicit `ModalityState`, and use `ModalityState.any()` specifically for the export-completion and other long-background-operation completions (create artifact, state change). Example:

```kotlin
private inline fun invokeLaterIfAlive(
    modalityState: ModalityState = ModalityState.NON_MODAL,
    crossinline action: () -> Unit
) {
    ApplicationManager.getApplication().invokeLater({
        if (project.isDisposed || disposed) return@invokeLater
        action()
    }, modalityState)
}
```

Then at the export-completion site (line 1632) and the error site (line 1656), call:
```kotlin
invokeLaterIfAlive(ModalityState.any()) { ... }
```

This ensures the "Exported N artifact(s)" status text and balloon surface immediately when the export finishes, even if a Create dialog or Settings panel is open. The `NON_MODAL` default should be kept for list-refresh and selection-driven updates where running inside a modal context could cause re-entrancy issues.

**Verifier.** The code is exactly as described. `invokeLaterIfAlive` is defined at line 164-169 as `ApplicationManager.getApplication().invokeLater { ... }` with no explicit `ModalityState`. In IntelliJ Platform, `invokeLater` without a modality argument uses `ModalityState.defaultModalityState()` which, when called from a background thread, resolves to `NON_MODAL`. Tasks queued this way are held in the EDT queue and will not be dispatched while any modal dialog is showing — they execute only after the modal closes. The export itself runs entirely off-EDT on `executeOnPooledThread`, calls `CompletableFuture.allOf(*futures.toTypedArray()).join()` to wait for all artifact/TC/attachment downloads, and then posts the completion balloon and status label update via `invokeLaterIfAlive`. If the user opens a modal dialog (Create User Story, Create Defect, Settings) while the export is running and the export finishes while the modal is still open, the "Exported N artifact(s)" status text and the completion balloon will not appear until the modal is dismissed. The export has fully completed; only the UI acknowledgment is deferred. The severity is correctly rated low — it is cosmetic/UX only, no data loss or corruption occurs. The confidence can reasonably be raised to 0.8 since the code unambiguously matches the described pattern; the only uncertainty is whether users actually hit this in practice (they would need to open a modal during a long bulk export).

**Evidence.** invokeLaterIfAlive { statusLabel.text = "Exported ${artifactSuccess.get()} artifact(s), ..."; ... balloon.show(...) }  // after allOf(...).join(), NON_MODAL

---

#### › UI rendering & responsiveness

#### LOW-62 · ArtifactCellRenderer builds text twice and a tooltip string on every paint
`UI rendering & responsiveness` · category: performance · effort: S · conf: 0.95 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** ArtifactCellRenderer.getListCellRendererComponent, lines 2098-2124  

**Problem.** Each cell stamp builds textLabel.text via string interpolation, then for blocked rows builds a SECOND concatenated string ('⛔ ' + textLabel.text), and separately builds panel.toolTipText = "${value.formattedID}: ${value.name}" — a third string concatenation that duplicates the text already computed two lines above. These run once per visible row per paint/model-event. The tooltip string is recreated on every stamp even though it only changes when the underlying value changes.

**Impact.** Minor per-paint garbage on the EDT proportional to visible rows; contributes to GC pressure during scroll/refresh on large lists. Small relative to the StatusBadge finding but in the same hot path.

**Recommendation.** Build the base string once, capture it in a val, then conditionally prefix it for the blocked case — all in one assignment. Reuse that same val for the tooltip to eliminate the third interpolation and fix the subtle null-safety divergence (the tooltip currently omits the ?: "?" / ?: "Untitled" guards, so it can render "null: null" when fields are absent). Suggested replacement:

val baseText = "${value.formattedID ?: "?"}: ${value.name ?: "Untitled"}"
textLabel.text = if (isBlocked) "⛔ $baseText" else baseText
panel.toolTipText = baseText

Compute isBlocked before building baseText so the single-assignment form works. Bundle this with the StatusBadge finding as it is in the same stamp method.

**Verifier.** The actual code at lines 2098–2124 matches the finding exactly. Three string-building operations occur on every renderer stamp: (1) line 2098 builds the base display string with null coalescing; (2) line 2107 reads textLabel.text and prepends the blocked emoji, creating a second string; (3) line 2124 builds the tooltip by re-interpolating value.formattedID and value.name — duplicating nearly the same content as string #1 but without the null-coalescing guards (so tooltip can emit "null: null" if those fields are absent, which is also a minor correctness divergence not mentioned in the original finding). This is a real issue in the paint hot path of a ListCellRenderer. The fix is straightforward and the reviewer's characterization is accurate.

**Evidence.** textLabel.text = "${value.formattedID ?: "?"}: ${value.name ?: "Untitled"}" ... if (isBlocked) { textLabel.text = "⛔ ${textLabel.text}" } ... panel.toolTipText = "${value.formattedID}: ${value.name}"

---

#### LOW-63 · descriptionPane re-parses full HTML document on every selection via setText; no document reuse, large descriptions block the EDT only at assignment
`UI rendering & responsiveness` · category: performance · effort: M · conf: 0.75 · verdict: needs-nuance  
**File:** `…/ui/RallyDetailPanel.kt`  
**Location:** loadAndRenderDescription line 525 / showArtifact line 349 / wrapHtml lines 979-1031  

**Problem.** wrapHtml runs off-EDT (good — documented), but the actual descriptionPane.text = wrapped assignment runs on the EDT and forces HTMLEditorKit to discard the prior HTMLDocument and re-parse the full wrapped HTML string synchronously. For multi-MB descriptions with up to 10 inlined base64 data-URI images, the data URIs are embedded directly in the HTML string, so the EDT parse/layout of a multi-megabyte string (base64 inflates image bytes ~33%) happens on the EDT. The fast-path in showArtifact (line 349) also assigns wrapHtml(desc) on the EDT synchronously when a description is already present. There is no incremental/streaming render and no guard on total document size before assignment.

**Impact.** A single very large Rally description with several large inline images produces a multi-MB HTML string whose JEditorPane setText (parse + layout) runs on the EDT, causing a perceptible freeze when opening that ticket's detail panel. Bounded by the 10-image cap and 1MB/image image-cache cap, so worst case is roughly a handful of MB — noticeable but not unbounded.

**Recommendation.** The real risk is the async path's `descriptionPane.text = wrapped` at line 525, where `wrapped` is a potentially multi-MB HTML string embedding up to 10 base64 data-URIs. The fast path at line 349 is much less risky — it only wraps the raw HTML without images.

Concrete mitigations, in priority order:

1. **Add a cumulative base64 size guard in `resolveInlineImages`**: after computing each `base64` string, accumulate the byte count and skip further image inlining if the total would exceed ~3 MB. This caps the worst-case `setText` string size regardless of how many 1 MB images exist. The existing `maxInlineImages = 10` cap helps with count but not size.

2. **For large strings, pre-build the Document off-EDT**: instead of assigning `setText`, construct an `HTMLDocument` by calling `editorKit.read(StringReader(wrapped), doc, 0)` on the pooled thread, then swap it in on the EDT via `descriptionPane.document = doc`. This moves the parse work entirely off the EDT. Caveat: `HTMLEditorKit` is not thread-safe so the kit instance used for off-thread parsing must be separate from the one installed on the pane.

3. **At minimum**, log the string length before the `setText` call at line 525 so production freeze reports can be correlated with description size. This costs nothing and confirms (or refutes) the claim in real usage.

The fast path at line 349 does not need the same treatment since it assigns raw (imageless) HTML that is typically small.

**Verifier.** The core claim — that `descriptionPane.text = wrapped` assigns a potentially large HTML string (with embedded base64 data-URI images) synchronously on the EDT, triggering a full `HTMLEditorKit` re-parse — is correct for the async path. However, several details in the finding are inaccurate or overstated:

1. **`wrapHtml` does NOT run on the EDT for the async path.** Line 522 computes `val wrapped = wrapHtml(text)` on the pooled thread *before* the `invokeLater` block. Only the final string assignment `descriptionPane.text = wrapped` (line 525) executes on the EDT. The finding's claim that `wrapHtml` "runs off-EDT (good — documented)" is correct for the async path; the reviewer's parenthetical is consistent with CLAUDE.md.

2. **The fast-path at line 349** (`descriptionPane.text = wrapHtml(desc)`) does run `wrapHtml` on the EDT synchronously, but at that point `desc` is the *raw* Rally HTML without any resolved inline images (images are resolved later in the async path). So this path produces a much smaller string — typically a few KB of text/HTML markup. The claim that the fast-path causes a "multi-MB" EDT assignment is inaccurate.

3. **The actual large EDT assignment** is at line 525 in `loadAndRenderDescription`, after `resolveInlineImages` embeds base64 data-URIs into the HTML. With up to 10 images at up to 1 MB each (per `maxCacheableImageBytes = 1L * 1024 * 1024`), the worst-case string is roughly 10 MB raw → ~13.3 MB base64 → plus HTML wrapper. This is assigned to `JEditorPane` via `setText`, which discards the prior `HTMLDocument` and re-parses synchronously on the EDT.

4. **The 1 MB per-image cap** applies only to caching (`maxCacheableImageBytes`); images larger than 1 MB are still downloaded and embedded as base64 (they just bypass the cache). So the actual worst-case string can exceed what the CLAUDE.md documents as "1 MB per-image cap."

5. **The real concern is real but bounded.** The 10-image cap (`maxInlineImages = 10`) does provide a hard upper bound on inlined image count. Typical Rally descriptions with screenshots are well under 1 MB each, making the practical worst case closer to a few MB than 13 MB. But the EDT `setText` parse of a multi-MB HTML string with embedded binary data is genuinely capable of causing perceptible jank (a few hundred milliseconds) on slow machines or with many large images.

Actual code at the cited locations:
- Line 349: `descriptionPane.text = wrapHtml(desc)` (fast path — raw HTML, no images, EDT)
- Lines 522-527: `val wrapped = wrapHtml(text)` off-EDT, then `invokeLater { descriptionPane.text = wrapped }` on EDT with full base64-embedded string
- Lines 979-1031: `wrapHtml` builds the full HTML document string including `<style>`, `<body>`, color pins, and the neutralized/decolored description body

**Evidence.** ApplicationManager.getApplication().invokeLater { ... descriptionPane.text = wrapped; descriptionPane.caretPosition = 0 } — full wrapped HTML (incl. base64 data URIs) assigned synchronously on EDT

---

#### LOW-64 · StatusBadge.getPreferredSize and paintComponent each fetch FontMetrics, called per cell when row height is not pinned
`UI rendering & responsiveness` · category: performance · effort: S · conf: 0.85 · verdict: needs-nuance  
**File:** `…/ui/StatusBadge.kt`  
**Location:** getPreferredSize lines 42-46, paintComponent lines 48-64  

**Problem.** getPreferredSize calls getFontMetrics(font) and fm.stringWidth(text) on each invocation; paintComponent fetches g2.fontMetrics and recomputes stringWidth(text) again for centering. In the detail-panel test-case/task/attachment lists (which do NOT set fixedCellHeight — only the main ticket list pins it), BasicListUI calls the renderer + getPreferredSize for every row on every model event, so each badge does a FontMetrics + stringWidth per row per event, then again at paint. FontMetrics lookups are comparatively cheap but stringWidth scans the text each time.

**Impact.** Minor. Only matters on the unpinned detail-panel lists (test cases can be dozens of rows). Adds FontMetrics/stringWidth work to those lists' layout passes. Negligible for typical small detail lists, real only for test-case lists with many rows.

**Recommendation.** Apply fixedCellHeight to the four detail-panel lists (testCaseList, taskList, attachmentList, stepList) using the same prototype-render pattern already used for artifactList in RallyToolWindowPanel.kt. This is primarily a code-consistency fix — the developer already knows about and documents this pattern, so the omission from sub-lists is likely an oversight rather than a deliberate trade-off. Because the rows in each sub-list are uniform height, setting fixedCellHeight eliminates the O(n) getPreferredSize sweep on every model event. The StatusBadge caching suggestion (keying on (text, font)) is not worthwhile given that getFontMetrics returns a cached object and stringWidth on short strings is negligible; fixing fixedCellHeight is the more impactful and simpler change. The two redundant stringWidth calls in paintComponent (once for centering) are not a real problem and do not need caching.

**Verifier.** The code is exactly as the reviewer describes: StatusBadge.getPreferredSize (lines 42-46) calls getFontMetrics(font) and fm.stringWidth(text) on every invocation, and paintComponent (lines 48-64) calls g2.fontMetrics and fm.stringWidth(text) again for centering. None of the four detail-panel lists (testCaseList, taskList, attachmentList, stepList) set fixedCellHeight — confirmed by grep returning no results for fixedCellHeight in RallyDetailPanel.kt. The main artifactList in RallyToolWindowPanel.kt does pin fixedCellHeight with an explicit comment documenting exactly this O(n) layout concern (lines 296-306), so the developer is clearly aware of the pattern and intentionally applied it to the main list. The gap is real: the sub-lists are inconsistent with the established project convention. However, the severity claim ("minor, real only for test-case lists with many rows") is accurate and if anything the reviewer slightly overstates it. getFontMetrics(font) in Swing returns a cached FontMetrics object from the Graphics pipeline and does not re-measure; stringWidth on a 5-15 character state string like "In-Progress" or "Automated" is a fast fixed-complexity scan. Rally detail panels typically show tens of test cases at most. The extra work per model event is measurable in a profiler but imperceptible to users. The finding is real as a code-consistency / latent-quality issue, but framing it as a performance finding overstates it.

**Evidence.** getPreferredSize: "val fm = getFontMetrics(font); return Dimension(fm.stringWidth(text) + JBUI.scale(16), fm.height + JBUI.scale(4))"; detail lists set cellRenderer but never fixedCellHeight (lines 216-234)

---

#### LOW-65 · fixedCellHeight prototype omits StatusBadge/owner, so the pinned row height can clip the chip
`UI rendering & responsiveness` · category: ux · effort: S · conf: 0.80 · verdict: needs-nuance · orig=medium  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** setupUI(), lines 300-306  

**Problem.** The prototype used to derive fixedCellHeight is a RallyUserStory with formattedID/name/scheduleState/owner set, rendered once. The StatusBadge's preferredSize is fm.height + JBUI.scale(4) (StatusBadge.getPreferredSize line 45). BorderLayout row height = max of WEST/CENTER/EAST preferred heights. The badge sits in rightPanel (EAST). If the badge's preferred height (label font 11f + 4px scaled) ever exceeds the JLabel/icon height for a given theme/HiDPI scale, the prototype captures it; but the prototype's scheduleState is "In-Progress" which yields a visible badge, so height is captured — however the prototype is rendered BEFORE the list has a real width/LaF fully applied, and the height is frozen for the panel's life. After a LaF or font-size switch (StatusBadge.updateUI re-derives a larger font) fixedCellHeight is never recomputed, so a larger badge/font is clipped to the stale row height.

**Impact.** On a font-size or theme change at runtime, ticket rows keep the old (smaller) fixedCellHeight and the StatusBadge chip / text gets vertically clipped until the tool window is reopened. Cosmetic but visible jank on theme switches.

**Recommendation.** The gap is real but low-priority. When implementing the already-planned `LafManagerListener` (noted in CLAUDE.md for description color re-rendering), add fixedCellHeight recomputation to the same listener:

```kotlin
LafManager.getInstance().addLafManagerListener({
    artifactList.fixedCellHeight = artifactList.cellRenderer
        .getListCellRendererComponent(artifactList, prototype, 0, false, false)
        .preferredSize.height
    artifactList.revalidate()
}, this) // 'this' as Disposable for automatic cleanup
```

Do NOT set `fixedCellHeight = -1` as a workaround — that reverts to O(n) per-render measurement defeating the original optimization. Tying the recompute to the same listener that handles description color re-rendering keeps the fix in one place. There is no urgent need to add a standalone fix; this is a cosmetic edge case only triggered by mid-session font-size changes (rare) not by ordinary dark/light theme switches, since `JBUI.Fonts.label(11f)` is scale-factor-bound rather than LaF-bound.

**Verifier.** The structural claim is correct: `artifactList.fixedCellHeight` is computed exactly once at lines 304-306 in `setupUI()`, using a prototype render of a `RallyUserStory` with `scheduleState = "In-Progress"`, and there is no `LafManagerListener`, no `updateUI` override, and no other hook anywhere in `RallyToolWindowPanel.kt` or `RallyDetailPanel.kt` that recomputes it. After a runtime LaF or font-size change, the value is permanently stale for the panel's lifetime.

However, the claimed medium severity overstates the practical impact. `StatusBadge` uses `JBUI.Fonts.label(11f)` — a fixed logical size resolved through JBUI's HiDPI scaling. The JBUI scale factor is determined at JVM startup based on the display DPI; it does not change when the user switches between dark/light themes in a running IDE session. A dark→light (or vice-versa) theme switch therefore does NOT change `fm.height` for the badge, so the prototype's captured height remains correct after a plain theme switch. The only scenarios that would actually cause clipping are: (a) a custom IDE font size setting that takes effect without restart, or (b) a HiDPI resolution change mid-session — both rare in practice. The CLAUDE.md already acknowledges a missing `LafManagerListener` as a known follow-up specifically for description color re-rendering; fixedCellHeight recompute is a parallel gap in the same missing listener.

Actual code at the cited location:
```kotlin
val prototype = RallyUserStory(
    formattedID = "US00000", name = "Prototype", scheduleState = "In-Progress",
    owner = RallyUser(displayName = "Prototype Owner")
)
artifactList.fixedCellHeight = artifactList.cellRenderer
    .getListCellRendererComponent(artifactList, prototype, 0, false, false)
    .preferredSize.height
```
No recompute hook exists anywhere in the file.

**Evidence.** artifactList.fixedCellHeight = artifactList.cellRenderer.getListCellRendererComponent(artifactList, prototype, 0, false, false).preferredSize.height — computed once in setupUI with no recompute hook

---

#### LOW-66 · Server-side fallback search has no in-flight cancellation; stale slow responses race the active query
`UI rendering & responsiveness` · category: ux · effort: S · conf: 0.90 · verdict: confirmed  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** applySearchFilter(), lines 924-977  

**Problem.** The client-side debounce (searchDebounceTimer, 300ms, isRepeats=false, restart per keystroke) is correct. But the server-side fallback fires when client-side returns 0 results and query >= 3 chars; it guards results with activeServerSearch == query && searchField.text.trim() == query. When the user keeps typing (e.g. 'abc' -> 'abcd'), a new server search overwrites activeServerSearch but the previous HTTP request is never cancelled — it just runs to completion on the apiExecutor and is then discarded by the guard. Rapid typing that keeps producing 0 client-side matches can launch several concurrent server searches on the 4-thread apiExecutor.

**Impact.** Wasted API round-trips and apiExecutor thread occupancy during fast typing of a no-local-match query; the guard prevents UI corruption, so this is throughput/resource waste rather than a correctness bug. Can briefly saturate the shared 4-thread pool that detail loads also use.

**Recommendation.** Store the server-search task as a `@Volatile private var serverSearchFuture: java.util.concurrent.Future<*>? = null`. Before launching a new search, call `serverSearchFuture?.cancel(true)` and then reassign. This only attempts interruption — `searchArtifacts` uses Java's `HttpClient` which does support `cancel()` on its futures — so it is cheap and safe. The dual `activeServerSearch == query` guard can remain as a secondary safety net. The debounce already makes this low-frequency; the fix is a one-liner that eliminates the thread-slot lease on stale requests without any behavioral change for normal use.

**Verifier.** The actual code at lines 924-977 confirms the described behavior exactly. When `filtered.isEmpty() && query.length >= 3`, the code sets `activeServerSearch = query` and fires `executeOnPooledThread` with a bare `client.searchArtifacts(...)` call. There is no `Future` reference stored, no `cancel()` call, and no generation counter for the search path (the `AtomicLong` generation pattern used elsewhere in the file for iteration loading is absent here). The only race protection is the dual guard `activeServerSearch == query && searchField.text.trim() == query` applied at result ingestion time on the EDT. This means each successive no-local-match keystroke during the 300ms debounce window can launch a new HTTP request that occupies an `apiExecutor` thread slot until the network response arrives; the old request is not cancelled. The reviewer's characterization of the problem, the guard-correctness note (UI is safe; waste only), and the thread-pool saturation concern are all accurate. The debounce does reduce how often this fires — rapid typing within 300ms produces only one server search per 300ms quiet period — but a user who types slowly (e.g., one char every ~350ms, each producing 0 local matches) can legitimately stack multiple concurrent searches. Severity is correctly assessed as low: the UI guard prevents corruption, HTTP/2 multiplexing reduces the cost of concurrent requests, and the 4-thread pool bound limits absolute saturation.

**Evidence.** activeServerSearch = query; ... ApplicationManager.getApplication().executeOnPooledThread { ... client.searchArtifacts(query, ...) ... if (activeServerSearch == query && searchField.text.trim() == query) { ... } } — no cancellation of the prior request

---

#### LOW-67 · Selection listener recomputes and sets dividerLocation on every selection change, forcing split relayout
`UI rendering & responsiveness` · category: ux · effort: S · conf: 0.30 · verdict: needs-nuance  
**File:** `…/ui/RallyToolWindowPanel.kt`  
**Location:** setupListeners() selection listener, lines 390-413  

**Problem.** On every non-adjusting selection change the listener calls detailPanel.showArtifact(...), then reads sp.width/sp.dividerLocation/sp.dividerSize and, when collapsed, sets sp.dividerSize and sp.dividerLocation = (sp.width * 0.55). Setting dividerLocation triggers a JSplitPane relayout (revalidate of both children incl. the JEditorPane scroll pane). When already expanded the detailWidth check (< 100) skips the resize, so the storm is avoided for subsequent selections — but each selection still triggers a full showArtifact pipeline plus button enable/disable. The interplay with the SwingUtilities.invokeLater dividerLocation = width at startup (line 321) and resizeWeight=1.0 means an initial selection can fight the deferred layout.

**Impact.** First selection after collapse causes a split relayout (acceptable, intended), but the divider math runs even when no resize is needed; combined with showArtifact's tab rebuilds it adds to per-selection EDT work. Mostly fine — flagged because it is the per-selection hot path and the resize/relayout can flicker if the deferred startup invokeLater races a quick user selection.

**Recommendation.** The only genuine defect here is the potential race at line 321: `SwingUtilities.invokeLater { splitPane.dividerLocation = splitPane.width }` fires after layout completes. If the user clicks a list item before that deferred call executes, the selection handler expands the panel (lines 399-400), then the deferred call immediately re-collapses it (`dividerLocation = width`). Fix by cancelling or no-oping the deferred call if the panel has already been expanded: e.g., track an `isDetailExpanded` boolean and guard the invokeLater body with `if (!isDetailExpanded)`. The per-selection divider math and button-state updates are not performance issues — the `detailWidth < 100` guard already prevents unnecessary relayouts on subsequent selections, and `gitAvailable` is a pre-computed field. The "caching gitAvailable" recommendation adds no value. No changes needed to the selection listener structure itself.

**Verifier.** The actual code at lines 389-413 confirms the structure described by the reviewer, but the severity and framing need correction.

What the code actually does:

1. Every non-adjusting selection event calls `detailPanel.showArtifact(selected, currentClient)` unconditionally. This is correct and necessary.

2. When `selected != null`, it reads `sp.width - sp.dividerLocation - sp.dividerSize` to compute `detailWidth`. The dividerLocation set (lines 399-400) IS already guarded behind `if (detailWidth < 100)` — so it only fires when the panel is actually collapsed (width < 100px). The reviewer acknowledges this guard exists.

3. When `selected == null`, it always collapses: `sp.dividerSize = 0` and `sp.dividerLocation = sp.width`. This is the correct collapse path.

4. The button enable/disable (`startWorkingButton.isEnabled`, `finishWorkingButton.isEnabled`) runs unconditionally on every selection (not guarded). The `gitAvailable` value is a pre-computed boolean field — it is not re-evaluated per selection. So there is no cache opportunity being missed; the reviewer's recommendation to "cache gitAvailable-based button states" is a non-issue because `gitAvailable` is already a field (not a runtime check).

5. The `detailWidth < 100` guard correctly prevents re-running the split relayout on subsequent selections when already expanded. So the "storm" for subsequent selections is already avoided.

Where the reviewer is right: on the very first selection when the panel is collapsed, two property sets on `JSplitPane` (`dividerSize` and `dividerLocation`) do trigger a relayout. This is intentional behavior — it is the expand-on-first-select mechanism. The potential race with the `SwingUtilities.invokeLater { splitPane.dividerLocation = splitPane.width }` at line 321 is real: if the user selects something before that deferred call fires, then the deferred call will immediately re-collapse the panel after the expand, creating a visible flicker. This is a genuine (if rare and timing-dependent) bug.

Where the reviewer is wrong or overclaims:
- The divider math is NOT re-run "even when no resize is needed" on subsequent selections — the `detailWidth < 100` guard prevents that. The reviewer says "already done" but then still flags it as if it were a problem.
- The `gitAvailable` button state is not a per-selection recomputation — it's a field read, not an expensive check.
- The overall per-selection EDT work is minimal: two boolean property sets and three reads from `JSplitPane`. This is not a performance issue.

The actual real issue is narrower: the `invokeLater` at line 321 that pushes the divider to `sp.width` (collapsed position) races with an immediate user selection. If selection fires before that deferred call, the deferred call will override the expand, collapsing the panel immediately after expansion. This is the only genuine defect, and it is severity low (timing-dependent, cosmetic, affects only the very first selection after panel creation before layout completes).

**Evidence.** val detailWidth = sp.width - sp.dividerLocation - sp.dividerSize; if (detailWidth < 100) { sp.dividerSize = DIVIDER_THICKNESS; sp.dividerLocation = (sp.width * 0.55).toInt() }

---

## Appendix C — Refuted finding (checked, dismissed)

#### Description fast-path text can be overwritten by a stale async render of the previous ticket
**File:** `…/ui/RallyDetailPanel.kt` — showArtifact lines 347-353 (synchronous fast-path text set) vs loadAndRenderDescription lines 516-528 (async invokeLater) of a prior generation  
**Claimed severity:** medium  

**Why refuted.** The actual code has two generation checks in loadAndRenderDescription. The outer check at line 516 (`if (generation.get() == gen && !disposed)`) is an optimization that avoids calling wrapHtml on stale data. The inner check at line 524 (`if (generation.get() != gen || disposed) return@invokeLater`) is the authoritative EDT-side guard that prevents descriptionPane.text from ever being set with stale content. Because both checks exist and the inner one runs on the EDT (single-threaded), there is no race window where wrong-ticket description text can be committed to the UI. The finding itself concedes this ("the inner invokeLater guard (line 524) closes this window") and rates the issue as low-confidence (0.4) with no functional defect. The "TOCTOU" framing is technically correct as an observation about the outer check, but because the outer check is explicitly an optimization and not the safety gate, there is no real bug here. The recommendation (add a comment) addresses a documentation/clarity concern, not a correctness defect. Since the finding's own conclusion is that the code is "defended" and no functional change is required, the finding does not confirm a genuine code defect — it confirms the code is correct.

---

