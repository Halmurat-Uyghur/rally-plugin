# Performance & Quality Audit Fixes (2026-07-01 audit) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement every scored finding from `docs/audits/2026-07-01-performance-quality-audit.md` — 2 High (H1, H2), 7 Medium (M1–M7), 11 Low (L1–L11) — plus the CLAUDE.md refresh it calls for.

**Architecture:** Targeted fixes inside the existing structure: `RallyApiClient` (HTTP/cache layer), `RallyToolWindowPanel` (main UI), `RallyDetailPanel` (detail UI), `RallyExporter` (export), plus two new small files (`RallyLoadStatus.kt`, `RallySettingsListener.kt`). Pure logic is extracted into package-level functions so it is unit-testable without Swing (the pattern already used by `RallyFilters.kt` / `RallySprintSummary.kt`).

**Tech Stack:** Kotlin 2.0.21 (JVM 17), IntelliJ Platform 2024.1 (sinceBuild 241), Gson 2.10.1, JUnit 4.13.2, Gradle with `org.jetbrains.intellij.platform` 2.16.0.

## Global Constraints

- **sinceBuild = 241**: only use IntelliJ Platform APIs that exist in 2024.1. Deprecated-but-present APIs are used with `@Suppress("DEPRECATION")` and a comment naming the migration path (existing convention).
- **Commits**: conventional format `type(scope): description`. **Do NOT include `Co-Authored-By: Claude` or any AI attribution** (CLAUDE.md rule — overrides any default).
- **Baseline**: `./gradlew test` → 182/182 passing at commit `e1ecb6e`. Every task ends with the full suite green.
- **Never run `./gradlew verifyPlugin`** (downloads multiple GB of IDEs). `./gradlew test` per task; `./gradlew clean build` only in the final task.
- **Threading invariants** (documented in CLAUDE.md, verified sound by the audit — do not break): `client.apiExecutor` has 4 threads; never block an apiExecutor worker on tasks submitted to apiExecutor. `queryAllArtifactsParallel` must only be called from non-apiExecutor threads. Exporter download pools must stay separate from apiExecutor.
- Filter display strings must byte-match the `Scope` / `StateFilter` enums in `RallyFilters.kt`.
- Test files live under `src/test/kotlin/com/github/halmurat/rally/...` mirroring the main package, JUnit 4 style with backtick test names (see `RallyFiltersTest.kt`).

---

### Task 1: H2 — 12 `LOG.error` sites → `LOG.warn`

In the IntelliJ Platform, `Logger.error(msg, throwable)` raises an IdeaLoggingEvent: blinking red icon, "IDE Fatal Errors" dialog attributed to this plugin, submit-report prompt. All 12 sites below catch *expected environmental failures* (Rally unreachable, 401, declined transition), each already surfaced in the UI. JetBrains guidance: `error()` is for programming errors only.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (9 sites: lines 621, 1289, 1362, 1475, 1535, 1671, 1695, 1708, 1784)
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyDetailPanel.kt` (2 sites: lines 718, 985)
- Modify: `src/main/kotlin/com/github/halmurat/rally/util/RallyGitOps.kt` (1 site: line 92)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing (logging-level change only). No unit test — logging severity isn't observable from JUnit here; the gate is compile + full suite.

- [ ] **Step 0: Commit the audit + this plan** (they are untracked)

```bash
git add docs/audits/2026-07-01-performance-quality-audit.md docs/superpowers/plans/2026-07-06-perf-quality-audit-fixes.md
git commit -m "docs: add 2026-07-01 performance/quality audit and implementation plan"
```

- [ ] **Step 1: Change each of the 12 sites from `LOG.error(` to `LOG.warn(`**

Exact sites (match on the message string, not the line number, in case earlier context shifted):

| File | Message string |
|---|---|
| RallyToolWindowPanel.kt | `"Failed to load Rally tickets"` |
| RallyToolWindowPanel.kt | `"Failed to create $typeLabel"` |
| RallyToolWindowPanel.kt | `"Failed to export $id"` |
| RallyToolWindowPanel.kt | `"Failed to update points"` |
| RallyToolWindowPanel.kt | `"Failed to update ${artifact.formattedID}"` |
| RallyToolWindowPanel.kt | `"Failed to create branch $branchName"` |
| RallyToolWindowPanel.kt | `"Failed to move $ticketId to In-Progress"` |
| RallyToolWindowPanel.kt | `"Failed to assign owner for $ticketId"` |
| RallyToolWindowPanel.kt | `"Failed to finish working on $ticketId"` |
| RallyDetailPanel.kt | `"Failed to export $tcId"` |
| RallyDetailPanel.kt | `"Failed to download attachment ${selected.name}"` |
| RallyGitOps.kt | `"Branch operation failed for $branchName"` |

Each is a one-word change: `LOG.error(...)` → `LOG.warn(...)`. Arguments unchanged.

- [ ] **Step 2: Verify no `LOG.error` remains in main sources**

Run: `grep -rn "LOG.error" src/main/`
Expected: no output.

- [ ] **Step 3: Run tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 182 tests passing.

- [ ] **Step 4: Commit**

```bash
git add -A src/main
git commit -m "fix(logging): downgrade 12 expected-failure LOG.error sites to LOG.warn

Logger.error raises an IDE fatal-error report attributed to the plugin;
every one of these sites catches an expected environmental failure (Rally
unreachable, auth rejection, declined transition) already surfaced in the
UI. Offline users were getting fatal-error balloons on every reload."
```

---

### Task 2: H1 — "Test Cases" scope + selected sprint no longer fails the load

`buildQuery()` appends `(Iteration.Name = "…")` regardless of scope, but Rally's `TestCase` type has no `Iteration` attribute — Rally returns HTTP 200 + `Errors`, `requireNoErrors` throws, the whole load fails. Fix: for the Test Cases scope, filter via the linked work product (`WorkProduct.Iteration.Name`), which is valid on TestCase. Extract the query builder into a pure function so it's unit-testable.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyFilters.kt` (add `buildTicketQuery`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (delegate `buildQuery` to it)
- Create: `src/test/kotlin/com/github/halmurat/rally/ui/RallyTicketQueryTest.kt`

**Interfaces:**
- Consumes: `Scope.fromDisplay(String?): Scope?` (RallyFilters.kt), `RallyApiClient.escapeQueryValue(String): String` (companion).
- Produces: `internal fun buildTicketQuery(scope: String?, selectedIter: String, username: String): String?` — package `com.github.halmurat.rally.ui`, top level in RallyFilters.kt.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/github/halmurat/rally/ui/RallyTicketQueryTest.kt`:

```kotlin
package com.github.halmurat.rally.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the server-side ticket query per scope (H1). TestCase has no Iteration
 * attribute, so the sprint filter must traverse the linked work product there —
 * `(Iteration.Name = …)` on /testcase is a guaranteed 200-with-Errors failure.
 */
class RallyTicketQueryTest {

    @Test
    fun `no filters yields null query`() {
        assertNull(buildTicketQuery("All Tickets", "", ""))
    }

    @Test
    fun `All Sprints adds no iteration condition`() {
        assertNull(buildTicketQuery("Test Cases", "All Sprints", ""))
    }

    @Test
    fun `sprint filter on regular scopes uses Iteration Name`() {
        assertEquals(
            "(Iteration.Name = \"Sprint 42\")",
            buildTicketQuery("All Tickets", "Sprint 42", "")
        )
    }

    @Test
    fun `sprint filter on Test Cases scope traverses the work product`() {
        assertEquals(
            "(WorkProduct.Iteration.Name = \"Sprint 42\")",
            buildTicketQuery("Test Cases", "Sprint 42", "")
        )
    }

    @Test
    fun `my tickets owner filter combines with sprint via binary AND nesting`() {
        assertEquals(
            "((Owner.UserName = \"me@x.com\") AND (Iteration.Name = \"Sprint 42\"))",
            buildTicketQuery("My Tickets", "Sprint 42", "me@x.com")
        )
    }

    @Test
    fun `owner filter only applies to My Tickets scope`() {
        assertNull(buildTicketQuery("All Tickets", "", "me@x.com"))
    }

    @Test
    fun `iteration values are escaped`() {
        assertEquals(
            "(Iteration.Name = \"Sprint \\\"42\\\"\")",
            buildTicketQuery("All Tickets", "Sprint \"42\"", "")
        )
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew test --tests "com.github.halmurat.rally.ui.RallyTicketQueryTest"`
Expected: FAIL — compilation error, `buildTicketQuery` unresolved.

- [ ] **Step 3: Implement `buildTicketQuery` in RallyFilters.kt**

Append to `src/main/kotlin/com/github/halmurat/rally/ui/RallyFilters.kt` (add `import com.github.halmurat.rally.api.RallyApiClient` at the top):

```kotlin
/**
 * Server-side query for the ticket list. Owner filter applies to "My Tickets" only;
 * the sprint filter is expressed per scope (H1): Rally's TestCase type has NO
 * Iteration attribute, so `(Iteration.Name = …)` sent to /testcase comes back as
 * HTTP 200 with a populated Errors array — which requireNoErrors correctly turns
 * into a failed load. Test cases are filtered through their linked work product
 * instead (`WorkProduct.Iteration.Name` is a valid dotted traversal on TestCase);
 * test cases with no WorkProduct won't match, which is the correct reading of
 * "test cases in this sprint". Pure and top-level so it is unit-testable.
 */
internal fun buildTicketQuery(scope: String?, selectedIter: String, username: String): String? {
    val conditions = mutableListOf<String>()

    if (Scope.fromDisplay(scope) == Scope.MY_TICKETS && username.isNotBlank()) {
        val safeUsername = RallyApiClient.escapeQueryValue(username)
        conditions.add("(Owner.UserName = \"$safeUsername\")")
    }

    if (selectedIter.isNotBlank() && selectedIter != "All Sprints") {
        val safeIter = RallyApiClient.escapeQueryValue(selectedIter)
        val iterationField =
            if (Scope.fromDisplay(scope) == Scope.TEST_CASES) "WorkProduct.Iteration.Name"
            else "Iteration.Name"
        conditions.add("($iterationField = \"$safeIter\")")
    }

    // Rally requires binary nesting for AND: ((a) AND (b))
    return when (conditions.size) {
        0 -> null
        1 -> conditions[0]
        else -> conditions.reduce { acc, cond -> "($acc AND $cond)" }
    }
}
```

- [ ] **Step 4: Delegate the panel's private `buildQuery` to it**

In `RallyToolWindowPanel.kt`, replace the whole `buildQuery` method body (currently builds `conditions` inline) with:

```kotlin
    private fun buildQuery(scope: String, selectedIter: String, settings: RallySettings): String? {
        // workspace/project are passed as URL params by the API client, not as query conditions.
        // State/type filtering is done client-side since ScheduleState vs State differs by type.
        // Query construction lives in buildTicketQuery (RallyFilters.kt) so the per-scope
        // iteration-field choice (H1) is unit-tested.
        val query = buildTicketQuery(scope, selectedIter, settings.username)
        LOG.info("Rally query built (scope=$scope, iteration='$selectedIter', hasQuery=${query != null})")
        return query
    }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test`
Expected: PASS, 189 tests (182 + 7 new).

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "fix(ui): sprint filter no longer breaks the Test Cases scope

TestCase has no Iteration attribute, so (Iteration.Name = ...) on /testcase
always failed the load with a Rally 200-with-Errors response. Filter test
cases via WorkProduct.Iteration.Name instead; query builder extracted to
RallyFilters.kt and unit-tested per scope."
```

---

### Task 3: M2 — Debounce ticket selection before detail loads

Arrow-scrolling fires the selection listener once per row; each row launches 3 apiExecutor tasks + a pooled description task + a full detail-panel clear/repopulate. Split `RallyDetailPanel.showArtifact` into a cheap synchronous header pass and a deferred network pass; the panel debounces the network pass with a 200 ms single-shot `javax.swing.Timer` (mirroring the existing 300 ms `searchDebounceTimer`).

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyDetailPanel.kt`
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt`

**Interfaces:**
- Consumes: existing `RallyDetailPanel` internals (`generation`, `loadAndRenderDescription`, tab fields).
- Produces (new public API on `RallyDetailPanel`, used by Task 4 too):
  - `fun showArtifactHeader(artifact: RallyArtifact?, client: RallyApiClient?)` — synchronous, no network; bumps generation (cancels stale loads).
  - `fun loadDetails()` — starts the network-backed loads for whatever `showArtifactHeader` last set.
  - `fun showArtifact(artifact, client)` — unchanged signature, now `showArtifactHeader(...)` + `loadDetails()`; all existing direct callers (changeState, editPoints, startWorking, finishWorking) keep exact behavior.
  - `fun isShowing(artifact: RallyArtifact): Boolean` — identity check (`===`) against the currently shown artifact.

No new unit test — this is Swing/Timer wiring; the gate is compile + full suite (all existing behavior preserved via `showArtifact` composition). Optional manual check with `./gradlew runIde`.

- [ ] **Step 1: Restructure `RallyDetailPanel.showArtifact`**

Replace the existing `showArtifact` method with three methods. `showArtifactHeader` is the current method's synchronous prefix (guards, generation bump, header/badge/metadata, description fast-path, tab reset); `loadDetails` is the current method's two `executeOnPooledThread` blocks, re-reading state from `currentArtifact`/`currentClient`/`generation.get()`:

```kotlin
    /**
     * Cheap, synchronous part of showing an artifact (M2): header, badge, metadata,
     * tab reset, and the already-loaded-description fast path. Bumps the generation so
     * in-flight loads for the previous selection cancel. Network-backed loads are NOT
     * started — call [loadDetails] for that; the tool window debounces it behind a
     * short Timer so arrow-key scrolling costs zero API calls for skipped rows.
     */
    fun showArtifactHeader(artifact: RallyArtifact?, client: RallyApiClient?) {
        if (disposed) return
        if (artifact == null || client == null) {
            clear()
            return
        }
        // Bail if the caller passed a client whose thread pool has already been
        // shut down by a settings change or parent disposal — loadDetails() would
        // otherwise submit tasks to a dead executor.
        if (!client.isAlive) {
            LOG.warn("showArtifactHeader called with a disposed client; skipping")
            clear()
            return
        }

        val artifactRef = artifact.ref ?: return
        generation.incrementAndGet()
        currentArtifactRef = artifactRef
        currentArtifact = artifact
        currentClient = client

        // Update header
        val id = artifact.formattedID ?: "?"
        val name = artifact.name ?: "Untitled"
        headerLabel.text = "$id: $name"
        copyButton.isVisible = true
        browserButton.isVisible = true

        // effectiveState (api package) is the single source of truth: TestCase → lastVerdict
        // (fallback "No Verdict"), everything else → ScheduleState ?: State ?: "Unknown".
        val state = artifact.effectiveState
        stateBadge.update(state, RallyColors.forState(state))
        // Header badge lives in a real container, so update() (a pure setter) must be
        // followed by refresh() to schedule the layout + repaint.
        stateBadge.refresh()

        // Metadata strip
        metadataLabel.text = buildMetadataText(artifact)
        metadataLabel.isVisible = true

        // Show description if already available, otherwise show loading state
        val desc = artifact.description
        if (!desc.isNullOrBlank()) {
            descriptionPane.text = wrapHtml(desc)
            descriptionPane.caretPosition = 0
        } else {
            descriptionPane.text = wrapHtml("<i>Loading description...</i>")
        }

        if (artifact is RallyTestCase) {
            // For test cases: description + test steps only
            tabbedPane.removeAll()
            tabbedPane.addTab("Test Steps (...)", stepScrollPane)
            stepListModel.clear()
            return
        }

        // Restore standard tabs if previously showing a test case
        if (tabbedPane.tabCount != 3 || (tabbedPane.tabCount > 0 && tabbedPane.getTitleAt(0).startsWith("Test Steps"))) {
            tabbedPane.removeAll()
            tabbedPane.addTab("Test Cases", tcPanel)
            tabbedPane.addTab("Tasks", taskScrollPane)
            tabbedPane.addTab("Attachments", attachmentScrollPane)
        }
        // Reset to first tab and clear all lists
        tabbedPane.selectedIndex = TAB_TEST_CASES
        testCaseListModel.clear()
        testCaseSummaryLabel.text = "Loading..."
        taskListModel.clear()
        attachmentListModel.clear()
        stepListModel.clear()
        tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases (...)")
        tabbedPane.setTitleAt(TAB_TASKS, "Tasks (...)")
        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (...)")
    }

    /**
     * Start the network-backed detail loads (test cases / tasks / attachments /
     * description) for the artifact last passed to [showArtifactHeader]. Debounced
     * by the tool window's selection Timer (M2); also called synchronously via
     * [showArtifact] by the state-change/edit paths.
     */
    fun loadDetails() {
        if (disposed) return
        val artifact = currentArtifact ?: return
        val client = currentClient ?: return
        val artifactRef = currentArtifactRef ?: return
        if (!client.isAlive) return
        val gen = generation.get()
        val id = artifact.formattedID ?: "?"
        val desc = artifact.description

        if (artifact is RallyTestCase) {
            ApplicationManager.getApplication().executeOnPooledThread {
                // Submit the steps query to apiExecutor FIRST so it runs in parallel
                // with the description work below.
                val stepsFuture = CompletableFuture.supplyAsync({
                    if (generation.get() != gen) return@supplyAsync null
                    try { client.queryTestSteps(id) } catch (e: Exception) {
                        LOG.warn("Failed to load test steps for $id", e)
                        null
                    }
                }, client.apiExecutor)

                stepsFuture.thenAccept { steps ->
                    ApplicationManager.getApplication().invokeLater {
                        if (generation.get() != gen || disposed) return@invokeLater
                        stepListModel.clear()
                        if (steps != null) {
                            steps.forEach { stepListModel.addElement(it) }
                            tabbedPane.setTitleAt(0, "Test Steps (${steps.size})")
                        } else {
                            tabbedPane.setTitleAt(0, "Test Steps (0)")
                        }
                    }
                }.exceptionally { t -> LOG.warn("Detail panel steps update failed", t); null }

                loadAndRenderDescription(desc, id, artifactRef, client, gen)
            }
            return
        }

        // Load description, test cases, tasks, and attachments in parallel
        ApplicationManager.getApplication().executeOnPooledThread {
            // ... EXACT existing body: tcFuture / taskFuture / attachFuture supplyAsync
            //     blocks, their three thenAccept UI updates, then
            //     loadAndRenderDescription(desc, id, artifactRef, client, gen) ...
        }
    }

    fun showArtifact(artifact: RallyArtifact?, client: RallyApiClient?) {
        showArtifactHeader(artifact, client)
        loadDetails()
    }

    /** True when [artifact] is the exact object the panel is already showing (identity, not ref). */
    fun isShowing(artifact: RallyArtifact): Boolean = artifact === currentArtifact
```

The `// ... EXACT existing body ...` comment means: move the current `tcFuture`/`taskFuture`/`attachFuture` block (from `val tcFuture = CompletableFuture.supplyAsync({` through `loadAndRenderDescription(desc, id, artifactRef, client, gen)`) into `loadDetails` unchanged.

- [ ] **Step 2: Add the debounce timer to `RallyToolWindowPanel`**

Next to `searchDebounceTimer` (field declarations):

```kotlin
    /**
     * Debounces selection → network-backed detail loads (M2). Header/badge update
     * instantly on selection (cheap, local data); the 3 API queries + description
     * fetch fire only after the selection has settled for 200 ms, so holding ↓
     * through the list costs zero API calls for skipped rows. Mirrors the search
     * field's 300 ms debounce.
     */
    private val selectionDebounceTimer = javax.swing.Timer(200) {
        if (!disposed) detailPanel.loadDetails()
    }.apply { isRepeats = false }
```

- [ ] **Step 3: Rewire the selection listener**

In `setupListeners()`, replace the line `detailPanel.showArtifact(selected, currentClient)` with:

```kotlin
                if (selected != null && detailPanel.isShowing(selected)) {
                    // Same object re-selected (e.g. selection restored after a model
                    // rebuild) — the panel is already rendering it; skip the reload
                    // to avoid tab flicker and duplicate loads.
                } else {
                    selectionDebounceTimer.stop()
                    detailPanel.showArtifactHeader(selected, currentClient)
                    if (selected != null) selectionDebounceTimer.restart()
                }
```

(The rest of the listener — split expand/collapse, button enablement — is unchanged.)

- [ ] **Step 4: Stop the timer on dispose**

In `RallyToolWindowPanel.dispose()`, next to `searchDebounceTimer.stop()`:

```kotlin
        selectionDebounceTimer.stop()
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test`
Expected: PASS (189).

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "perf(ui): debounce ticket selection before detail-panel API loads

Arrow-scrolling fired 3 apiExecutor tasks + a pooled description fetch per
row. Header/badge/metadata now update instantly; the network-backed loads
fire via a 200ms single-shot Timer once the selection settles, mirroring
the existing search debounce."
```

---

### Task 4: M7 — Preserve selection across refresh; collapse detail split when it's gone

`updateListModel` rebuilds the model with selection listeners detached, so every Refresh / scope / project / sprint change silently drops the selection AND leaves the detail split open on a blank pane (the listener never fires for the implicit deselection, so the auto-collapse never runs).

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyFilters.kt` (add `selectionIndicesByRef`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (`updateListModel`)
- Modify: `src/test/kotlin/com/github/halmurat/rally/ui/RallyFiltersTest.kt` (tests for the helper)

**Interfaces:**
- Consumes: `detailPanel.isShowing(artifact)` and the listener wiring from Task 3; `RallyArtifact.ref`.
- Produces: `internal fun selectionIndicesByRef(artifacts: List<RallyArtifact>, refs: Set<String>): IntArray` — top level in RallyFilters.kt.

- [ ] **Step 1: Write the failing tests**

Append to `RallyFiltersTest.kt` (add imports `org.junit.Assert.assertArrayEquals`, `com.github.halmurat.rally.api.RallyUserStory`):

```kotlin
    @Test
    fun `selectionIndicesByRef finds surviving refs by index`() {
        val a = RallyUserStory(ref = "r/1", formattedID = "US1")
        val b = RallyUserStory(ref = "r/2", formattedID = "US2")
        val c = RallyUserStory(ref = "r/3", formattedID = "US3")
        assertArrayEquals(intArrayOf(0, 2), selectionIndicesByRef(listOf(a, b, c), setOf("r/1", "r/3")))
    }

    @Test
    fun `selectionIndicesByRef is empty when refs are gone or empty`() {
        val a = RallyUserStory(ref = "r/1")
        assertArrayEquals(intArrayOf(), selectionIndicesByRef(listOf(a), setOf("r/999")))
        assertArrayEquals(intArrayOf(), selectionIndicesByRef(listOf(a), emptySet()))
        assertArrayEquals(intArrayOf(), selectionIndicesByRef(emptyList(), setOf("r/1")))
    }

    @Test
    fun `selectionIndicesByRef skips artifacts with null refs`() {
        val a = RallyUserStory(ref = null)
        val b = RallyUserStory(ref = "r/2")
        assertArrayEquals(intArrayOf(1), selectionIndicesByRef(listOf(a, b), setOf("r/2")))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.github.halmurat.rally.ui.RallyFiltersTest"`
Expected: FAIL — `selectionIndicesByRef` unresolved.

- [ ] **Step 3: Implement the helper in RallyFilters.kt**

(Add `import com.github.halmurat.rally.api.RallyArtifact`.)

```kotlin
/**
 * Indices of [artifacts] whose ref is in [refs] — used to restore the JList
 * selection after a model rebuild (M7). Pure and top-level so it is testable
 * without Swing.
 */
internal fun selectionIndicesByRef(artifacts: List<RallyArtifact>, refs: Set<String>): IntArray {
    if (refs.isEmpty()) return IntArray(0)
    return artifacts.withIndex()
        .filter { (_, artifact) -> artifact.ref != null && artifact.ref in refs }
        .map { it.index }
        .toIntArray()
}
```

- [ ] **Step 4: Rewrite `updateListModel`**

Replace the method in `RallyToolWindowPanel.kt`:

```kotlin
    /**
     * Rebuild the visible list model. The previous selection is re-selected by ref
     * when still present (M7) — re-selecting fires the (reattached) listener, which
     * re-populates the detail panel or, for identical objects, hits the isShowing
     * short-circuit. When the selection is gone, the detail split is collapsed
     * explicitly: the listener never fires for the implicit deselection of a model
     * clear, so the auto-collapse otherwise never runs and a blank pane stays open.
     */
    private fun updateListModel(artifacts: List<RallyArtifact>) {
        val selectedRefs = artifactList.selectedValuesList.mapNotNull { it.ref }.toSet()
        val selectionListeners = artifactList.listSelectionListeners
        selectionListeners.forEach { artifactList.removeListSelectionListener(it) }
        listModel.clear()
        listModel.addAll(artifacts)
        selectionListeners.forEach { artifactList.addListSelectionListener(it) }

        val indices = selectionIndicesByRef(artifacts, selectedRefs)
        if (indices.isNotEmpty()) {
            artifactList.selectedIndices = indices
        } else if (selectedRefs.isNotEmpty()) {
            // Previous selection no longer in the list — mirror the listener's auto-collapse.
            detailPanel.clear()
            mainSplitPane?.let { sp ->
                sp.dividerSize = 0
                sp.dividerLocation = sp.width
            }
        }
    }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test`
Expected: PASS (192 = 189 + 3).

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "fix(ui): preserve list selection across refresh and collapse detail pane when it is gone

updateListModel rebuilt the model with listeners detached, silently dropping
the selection and stranding an open, blank detail split (the auto-collapse
listener never fired). Re-select surviving rows by ref; collapse explicitly
otherwise."
```

---

### Task 5: M4 — "Save to Disk" uses the raw-bytes endpoint (base64 fallback)

The detail panel's save action pulls the whole attachment base64-wrapped in a JSON document (≈33% more transfer, ~4-5× transient heap for a 50 MB file). The exporter already does raw-first/base64-fallback; hoist that into a shared client method and use it in both places.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/api/RallyApiClient.kt` (new `downloadAttachmentBytes`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyDetailPanel.kt` (`saveAttachmentToDisk`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/export/RallyExporter.kt` (`downloadAttachmentContent` reuses the helper)

**Interfaces:**
- Consumes: `downloadAttachment(url, cache=false)`, `getAttachmentContent(contentRef)`, `RallyHtmlUtils.inlineImageUrl(base, oid, name)`, `RallyAttachment(objectID, name, content.ref)`.
- Produces: `fun RallyApiClient.downloadAttachmentBytes(attachment: RallyAttachment): ByteArray?` — returns null only when the attachment has neither ObjectID nor Content ref.

No new unit test (network path; both consumers are already covered by compile + existing suite). Manual: save a large attachment via runIde if available.

- [ ] **Step 1: Add the client method**

In `RallyApiClient.kt`, immediately after `downloadAttachment(...)`:

```kotlin
    /**
     * Fetch an attachment's bytes, preferring the raw-bytes endpoint over the base64
     * Content ref (M4). The raw endpoint (`/slm/attachment/<OID>/<name>` — the same
     * one inline images use) avoids the JSON DOM + base64 decode of
     * [getAttachmentContent]: ~25% less transfer and ~1x peak heap instead of 4-5x
     * (a 50 MB attachment spikes ~250 MB through the base64-JSON path). Falls back
     * to the Content ref when the raw download fails or there is no ObjectID.
     * Returns null when the attachment carries neither an ObjectID nor a Content ref.
     * Uncached — callers are one-shot saves/exports with their own dedup.
     */
    fun downloadAttachmentBytes(attachment: RallyAttachment): ByteArray? {
        val objectId = attachment.objectID
        val contentRef = attachment.content?.ref
        if (objectId != null) {
            try {
                // inlineImageUrl percent-encodes URI-illegal filenames (spaces, quotes)
                // without double-encoding names that are already encoded.
                val url = com.github.halmurat.rally.util.RallyHtmlUtils
                    .inlineImageUrl(webBaseUrl, objectId, attachment.name ?: "attachment")
                return downloadAttachment(url, cache = false)
            } catch (e: Exception) {
                LOG.warn("Raw attachment download failed for '${attachment.name}'; falling back to base64 content", e)
            }
        }
        val ref = contentRef ?: return null
        // Rally returns MIME base64 with line breaks every 76 chars; the strict
        // decoder throws IllegalArgumentException on real attachments.
        return Base64.getMimeDecoder().decode(getAttachmentContent(ref))
    }
```

- [ ] **Step 2: Use it in `RallyDetailPanel.saveAttachmentToDisk`**

Replace the top guard (which currently requires `content?.ref`) and the download block:

```kotlin
    private fun saveAttachmentToDisk() {
        val selected = attachmentList.selectedValue ?: return
        val client = currentClient ?: return
        if (selected.objectID == null && selected.content?.ref == null) {
            Messages.showErrorDialog(project, "No content reference for this attachment.", "Rally - Download Error")
            return
        }
        val fileName = RallyFileUtils.sanitizeFileName(selected.name ?: "attachment")
        // ... FileSaverDescriptor / wrapper / fileWrapper / targetFile: UNCHANGED ...

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Raw-bytes endpoint first, base64 Content ref fallback (M4) —
                // see RallyApiClient.downloadAttachmentBytes.
                val bytes = client.downloadAttachmentBytes(selected)
                    ?: throw RallyApiException("No content available for this attachment")
                targetFile.writeBytes(bytes)
                // ... success dialog: UNCHANGED ...
            } catch (e: Exception) {
                // ... catch block: UNCHANGED (LOG.warn after Task 1) ...
            }
        }
    }
```

Delete the now-unused `val contentRef = selected.content?.ref ?: run { ... }` guard and the `getAttachmentContent`/`Base64.getMimeDecoder` lines it fed. If `java.util.Base64` becomes unused in RallyDetailPanel.kt, keep it — `resolveInlineImages` still uses it.

- [ ] **Step 3: Reuse it in the exporter**

In `RallyExporter.downloadAttachmentContent`, replace the whole `val fileBytes = (if (objectId != null) { ... } else null) ?: contentRef?.let { ... } ?: return null` expression with:

```kotlin
            // Raw-first with base64 fallback, shared with the detail panel's
            // Save to Disk (M4) — see RallyApiClient.downloadAttachmentBytes.
            val fileBytes = client.downloadAttachmentBytes(attachment) ?: return null
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test`
Expected: PASS (192).

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "perf(api): raw-bytes attachment download with base64 fallback, shared by save and export

Save to Disk pulled attachments through the base64-JSON Content endpoint
(~33% more transfer, ~4-5x transient heap on large files). Hoist the
exporter's raw-first/base64-fallback into RallyApiClient.downloadAttachmentBytes
and use it from both paths."
```

---

### Task 6: M5 — OID-keyed attachment filenames: idempotent re-exports

The exporter's `name_1`, `name_2` collision counter dedupes within one exporter instance, but every export click creates a fresh exporter while files persist — re-exporting the same ticket accumulates duplicate attachment copies. Inline images already solved this with OID-keyed deterministic names; do the same for attachments.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/export/RallyExporter.kt`
- Modify: `src/test/kotlin/com/github/halmurat/rally/export/RallyExporterFormatTest.kt`

**Interfaces:**
- Consumes: `RallyFileUtils.sanitizeFileName`, `downloadAttachmentContent` internals from Task 5.
- Produces: `internal fun attachmentLocalName(objectId: String?, contentRef: String?, originalFileName: String): String` on the `RallyExporter` companion (mirrors the tested `inlineImageLocalName`).

- [ ] **Step 1: Write the failing tests**

Append to `RallyExporterFormatTest.kt`:

```kotlin
    @Test
    fun `attachmentLocalName keys on the attachment ObjectID`() {
        assertEquals("123_shot.png", RallyExporter.attachmentLocalName("123", null, "shot.png"))
    }

    @Test
    fun `attachmentLocalName falls back to the content ref trailing OID`() {
        assertEquals(
            "456_shot.png",
            RallyExporter.attachmentLocalName(
                null,
                "https://rally1.rallydev.com/slm/webservice/v2.0/attachmentcontent/456",
                "shot.png"
            )
        )
    }

    @Test
    fun `attachmentLocalName sanitizes hostile names`() {
        val name = RallyExporter.attachmentLocalName("123", null, "../../evil.png")
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
    }
```

(Match the file's existing import style; add `org.junit.Assert.assertFalse` if missing.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.github.halmurat.rally.export.RallyExporterFormatTest"`
Expected: FAIL — `attachmentLocalName` unresolved.

- [ ] **Step 3: Implement**

Add to the `RallyExporter` companion (next to `inlineImageLocalName`):

```kotlin
        /**
         * Local file name for an exported attachment (M5). Keyed on the attachment
         * ObjectID (unique in Rally) so re-exports overwrite deterministically instead
         * of accumulating name_1, name_2, … copies: the old collision counter deduped
         * within one exporter instance, but every export click creates a fresh exporter
         * while the files persist on disk. Falls back to the Content ref's trailing OID
         * when ObjectID is absent. Mirrors inlineImageLocalName.
         */
        internal fun attachmentLocalName(objectId: String?, contentRef: String?, originalFileName: String): String {
            val key = objectId ?: contentRef?.trimEnd('/')?.substringAfterLast('/') ?: "0"
            return RallyFileUtils.sanitizeFileName("${key}_$originalFileName")
        }
```

In `downloadAttachmentContent`, change the name computation and remove the collision-counter loop:

```kotlin
        val safeFileName = attachmentLocalName(objectId, contentRef, fileName)
        val cacheKey = "${objectId ?: contentRef}:$attachDir:$safeFileName"
```

and replace the `val path = synchronized(attachmentWriteLock) { ... }` block with:

```kotlin
            // OID-keyed names are unique per attachment (M5), so same-name collisions
            // are impossible and re-exports idempotently overwrite. The lock now only
            // serializes two threads racing the SAME attachment past a dedup-cache
            // miss — a concurrent truncate-mid-write would corrupt the file.
            val path = synchronized(attachmentWriteLock) {
                val outputPath = RallyFileUtils.safeResolve(attachDirPath, safeFileName)
                Files.write(outputPath, fileBytes)
                LOG.info("Saved attachment: $outputPath (${fileBytes.size} bytes)")
                outputPath.toAbsolutePath().toString()
            }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test`
Expected: PASS (195 = 192 + 3).

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "fix(export): OID-keyed attachment filenames make re-exports idempotent

The per-instance collision counter wrote screenshot_1.png, screenshot_2.png,
... on every re-export because the dedup map dies with the exporter while
the files persist. ObjectID-prefixed names (the inline-image scheme) make
collisions impossible and re-exports overwrite in place."
```

---

### Task 7: Polish batch — L1 (entity decode order), L4 (balloon copy), L5 (heading escape), L11 (jitter comment)

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/export/RallyExporter.kt` (L1, L5)
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (L4)
- Modify: `src/main/kotlin/com/github/halmurat/rally/api/RallyApiClient.kt` (L11)
- Modify: `src/test/kotlin/com/github/halmurat/rally/export/RallyExporterFormatTest.kt` (L1 test)

**Interfaces:** none new.

- [ ] **Step 1: Write the failing L1 test**

Append to `RallyExporterFormatTest.kt`:

```kotlin
    @Test
    fun `stripHtml decodes amp last so double-encoded entities do not double-decode`() {
        // A description whose literal text is "&lt;b&gt;" arrives as &amp;lt;b&amp;gt;
        // and must export as the text "&lt;b&gt;", not the tag "<b>".
        assertEquals("&lt;b&gt;", RallyExporter.stripHtml("&amp;lt;b&amp;gt;"))
    }

    @Test
    fun `stripHtml still decodes simple entities`() {
        assertEquals("a & b < c", RallyExporter.stripHtml("a &amp; b &lt; c"))
    }
```

- [ ] **Step 2: Run to verify the first new test fails**

Run: `./gradlew test --tests "com.github.halmurat.rally.export.RallyExporterFormatTest"`
Expected: FAIL — `expected:<&lt;b&gt;> but was:<<b>>`.

- [ ] **Step 3: Fix L1 — decode `&amp;` last in `stripHtml`**

In the companion `stripHtml`, reorder the entity replacements to:

```kotlin
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                // &amp; must decode LAST: decoding it first turns &amp;lt; into &lt;
                // which the later passes then double-decode into a real '<' (L1).
                .replace("&amp;", "&")
```

- [ ] **Step 4: Fix L5 — escape per-artifact Markdown headings**

`RallyExporter.kt` line ~199 (`exportTestCaseMarkdown`):

```kotlin
        md.appendLine("# $testCaseId - ${escapeMarkdown(tc.name ?: "")}")
```

`RallyExporter.kt` line ~489 (`exportArtifactMarkdown`):

```kotlin
        md.appendLine("# $artifactId - ${escapeMarkdown(artifact.name ?: "")}")
```

- [ ] **Step 5: Fix L4 — balloon copy points at a feature that exists**

`RallyToolWindowPanel.kt` (~line 1278, upload-failed balloon): replace `"Re-attach from the detail panel."` with `"You can re-attach the file in the Rally web UI."`

- [ ] **Step 6: Fix L11 — jitter comment matches the code**

`RallyApiClient.kt` `backoffMs`: change `// ±30% jitter` to `// 0..+30% positive jitter — still de-synchronizes concurrent retries`

- [ ] **Step 7: Run tests**

Run: `./gradlew test`
Expected: PASS (197 = 195 + 2).

- [ ] **Step 8: Commit**

```bash
git add -A src
git commit -m "fix(export,ui): polish batch — entity decode order, heading escapes, balloon copy, jitter comment

- stripHtml decodes &amp; last so double-encoded entities export as text (L1)
- per-artifact Markdown headings escape the name like the bulk paths (L5)
- attachment-upload-failed balloon no longer points at a nonexistent re-attach action (L4)
- backoff jitter comment matches the 0..+30% behavior (L11)"
```

---

### Task 8: M1 — Exports run on a dedicated executor, not `apiExecutor`

`exportSelectedArtifact()` runs one long-lived task per selected artifact on the 4-thread `apiExecutor`; exporting 4+ tickets starves the detail panel, refresh, and state changes until it finishes. Give the export orchestration its own small daemon pool, created per export, shut down in the existing `finally`.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (`exportSelectedArtifact`)

**Interfaces:**
- Consumes: existing export body.
- Produces: no API change. Task 9 assumes the export orchestration threads are NOT exporter-download-pool threads (deadlock-freedom argument).

No new unit test (thread-pool wiring); gate is compile + suite.

- [ ] **Step 1: Introduce the per-export pool**

In `exportSelectedArtifact()`, restructure the pooled-thread body:

```kotlin
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
            val client = getClient()
            client.enterBulkMode()
            // Per-export orchestration pool (M1). Each per-artifact task is long-lived
            // (description + inline images + attachments + linked test cases, parked on
            // the exporter's download-pool joins throughout), so running them on the
            // shared 4-thread apiExecutor starved everything interactive — detail tabs,
            // Refresh, state changes — for the duration of a 4+ ticket export. With a
            // dedicated pool, apiExecutor only ever serves short HTTP calls.
            val exportExecutor = java.util.concurrent.Executors.newFixedThreadPool(
                minOf(4, selected.size)
            ) { r -> Thread(r, "rally-export-orchestrator").apply { isDaemon = true } }
            try {
            val exporter = RallyExporter(client)
            // ... existing counters and futures body UNCHANGED, except the
            //     CompletableFuture.runAsync({ ... }, client.apiExecutor) calls
            //     become CompletableFuture.runAsync({ ... }, exportExecutor) ...
            // ... existing allOf(...).join() + result balloon UNCHANGED ...
            } finally {
                exportExecutor.shutdown()
                client.exitBulkMode()
            }
            } catch (e: Exception) {
                LOG.warn("Export aborted", e)
                invokeLaterIfAlive {
                    statusLabel.text = "Export failed"
                }
            }
        }
```

Concretely: add the `exportExecutor` creation line after `client.enterBulkMode()`, change `}, client.apiExecutor)` to `}, exportExecutor)` inside the `selected.mapNotNull { ... }` block, and add `exportExecutor.shutdown()` as the first line of the existing `finally`.

- [ ] **Step 2: Run tests**

Run: `./gradlew test`
Expected: PASS (197).

- [ ] **Step 3: Commit**

```bash
git add -A src
git commit -m "perf(export): run export orchestration on a dedicated pool instead of apiExecutor

Exporting 4+ tickets parked every apiExecutor worker on long-lived export
tasks, freezing detail-tab loads, Refresh, and state changes until the
export finished. A per-export daemon pool keeps apiExecutor serving only
short HTTP calls."
```

---

### Task 9: L6 — One shared download pool per exporter instead of a pool per call

`downloadInlineImages` / `attachmentsToJsonArray` each create and destroy an 8-thread pool per call; `appendStepsTable` calls `downloadInlineImages` twice per test step (~40 pools for an image-heavy 20-step test case). Hoist one lazily-created pool per `RallyExporter`; call sites close it.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/export/RallyExporter.kt`
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (close in export finally)
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyDetailPanel.kt` (`exportSelectedTestCases` uses `use {}`)

**Interfaces:**
- Consumes: Task 8's guarantee that joins on the download pool only ever happen from orchestration threads, never from download-pool threads (no self-deadlock).
- Produces: `RallyExporter : AutoCloseable`; `close()` shuts the pool down (idempotent, safe if pool never created).

- [ ] **Step 1: Make RallyExporter own one lazy pool**

```kotlin
class RallyExporter(private val client: RallyApiClient) : AutoCloseable {
```

Add fields (near `downloadedPaths`):

```kotlin
    /**
     * Shared download pool for inline images + attachments (L6). Previously each
     * downloadInlineImages / attachmentsToJsonArray call created and destroyed its
     * own pool — appendStepsTable calls downloadInlineImages twice per test step, so
     * an image-heavy 20-step test case churned ~40 pools. Lazily created on first
     * use; released via [close] (call sites use `use {}` / a finally). Threads are
     * daemon, so a leaked exporter can never stall IDE shutdown. INVARIANT: tasks on
     * this pool never submit-and-join back into it — joins happen only on the export
     * orchestration threads (see the M1 pool in RallyToolWindowPanel).
     */
    private val downloadPoolLazy = lazy { newDownloadPool(DOWNLOAD_POOL_SIZE) }
    private val downloadPool by downloadPoolLazy

    override fun close() {
        if (downloadPoolLazy.isInitialized()) downloadPool.shutdownNow()
    }
```

- [ ] **Step 2: Use the shared pool in both download methods**

In `attachmentsToJsonArray`, delete `val pool = newDownloadPool(minOf(DOWNLOAD_POOL_SIZE, attachments.size))` and the `try { ... } finally { pool.shutdownNow() }` wrapper; submit with `CompletableFuture.runAsync({ ... }, downloadPool)` and keep the `allOf(...).join()`.

In `downloadInlineImages`, same change: delete `val pool = newDownloadPool(minOf(DOWNLOAD_POOL_SIZE, uniqueJobs.size))` and its try/finally; submit to `downloadPool`.

- [ ] **Step 3: Close at the call sites**

`RallyToolWindowPanel.exportSelectedArtifact` (structure from Task 8): move `val exporter = RallyExporter(client)` to just above the inner `try` and add `exporter.close()` to the `finally`:

```kotlin
            val exporter = RallyExporter(client)
            try {
            // ... futures + join + balloon ...
            } finally {
                exporter.close()
                exportExecutor.shutdown()
                client.exitBulkMode()
            }
```

`RallyDetailPanel.exportSelectedTestCases`: wrap the loop:

```kotlin
        ApplicationManager.getApplication().executeOnPooledThread {
            var success = 0
            RallyExporter(client).use { exporter ->
                for (tc in selected) {
                    val tcId = tc.formattedID ?: continue
                    try {
                        if (json) exporter.exportTestCaseJson(tcId, outputDir)
                        if (markdown) exporter.exportTestCaseMarkdown(tcId, outputDir)
                        success++
                    } catch (e: Exception) {
                        LOG.warn("Failed to export $tcId", e)
                    }
                }
            }
            // ... existing result dialog UNCHANGED ...
        }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test`
Expected: PASS (197).

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "perf(export): one shared download pool per exporter instead of a pool per call

appendStepsTable spun up ~2 pools per test step via downloadInlineImages.
RallyExporter now owns a single lazily-created daemon pool released via
AutoCloseable.close() at the export call sites."
```

---

### Task 10: L7 — Delete dead code

Confirmed zero callers in `src/`: `queryIterationArtifacts`, sequential `queryAllArtifacts` (its only caller is the former), `RallyWorkspace`, and two unused imports. `bulkExportJson` / `bulkExportMarkdown` / `prefetchDescriptions` are also caller-less but deliberately kept for the planned Bulk Export UI — annotate them instead.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/api/RallyApiClient.kt` (delete `queryAllArtifacts`, `queryIterationArtifacts`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/api/RallyApiModels.kt` (delete `RallyWorkspace`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (delete `import com.intellij.ui.components.JBTextArea` and `import com.intellij.ui.components.JBTextField`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/export/RallyExporter.kt` (annotate bulk-export trio)
- Modify: `CLAUDE.md` (remove the two deleted methods from the API table)

**Interfaces:**
- Consumes: nothing.
- Produces: `queryAllArtifactsParallel` becomes the sole owner of the `"artifacts:"` cache prefix (Task 11 relies on this).

- [ ] **Step 1: Delete `queryAllArtifacts` and `queryIterationArtifacts` from RallyApiClient.kt** — including the stacked duplicate KDoc above `queryAllArtifacts`. In `queryAllArtifactsParallel`'s KDoc, drop the sentences referring to the sequential sibling ("Parallel sibling of [queryAllArtifacts] (MED-2):" → "Fetches user stories and defects concurrently and returns an [ArtifactQueryResult] …", and delete "The sequential [queryAllArtifacts] stays the safe default for callers already running on apiExecutor." and "Same caching/partial-failure policy as [queryAllArtifacts]:" → "Caching/partial-failure policy:").

- [ ] **Step 2: Delete `RallyWorkspace` from RallyApiModels.kt** (lines 294-306, the data class and its KDoc).

- [ ] **Step 3: Delete the two unused imports from RallyToolWindowPanel.kt** (`JBTextArea`, `JBTextField`).

- [ ] **Step 4: Annotate the deliberate keeps in RallyExporter.kt** — add to the KDoc of `bulkExportJson`, `bulkExportMarkdown`, and `prefetchDescriptions`:

```kotlin
     * NOTE: no UI entry point yet — deliberately kept for the planned Bulk Export UI
     * (see CLAUDE.md "Not Yet Implemented"). Do not delete as dead code.
```

- [ ] **Step 5: Update CLAUDE.md API table** — remove the `queryAllArtifacts()` and `queryIterationArtifacts(name)` rows. In the Performance Optimizations table's "Parallel list" row, delete the trailing sentence "`queryAllArtifacts` keeps its sequential path for `apiExecutor` callers".

- [ ] **Step 6: Verify + test**

Run: `grep -rn "queryAllArtifacts\b\|queryIterationArtifacts\|RallyWorkspace" src/ | grep -v Parallel`
Expected: no output.
Run: `./gradlew test`
Expected: PASS (197).

- [ ] **Step 7: Commit**

```bash
git add -A src CLAUDE.md
git commit -m "refactor(api): delete dead code — queryAllArtifacts, queryIterationArtifacts, RallyWorkspace

Zero callers in src/ (verified by grep); the sequential queryAllArtifacts
path's only caller was the equally dead queryIterationArtifacts. The bulk
export trio stays, annotated as awaiting the Bulk Export UI."
```

---

### Task 11: M3 — Surface `TotalResultCount`: "200 of 934 loaded"

`queryAllPages` deserializes `TotalResultCount` on every page and throws it away; the status line says "N loaded" with no hint the workspace holds more. Plumb the total through a new `PagedResult<T>` and `ArtifactQueryResult.totalAvailable`, and render it via a pure, tested status-text helper.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/api/RallyApiModels.kt` (`PagedResult`, `ArtifactQueryResult.totalAvailable`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/api/RallyApiClient.kt` (`queryAllPages`, `queryUserStories`, `queryDefects`, `queryAllTestCases`, `queryAllArtifactsParallel`, `searchArtifacts`)
- Create: `src/main/kotlin/com/github/halmurat/rally/ui/RallyLoadStatus.kt`
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (`loadTickets`, `refreshLoadedStatus`, new fields)
- Create: `src/test/kotlin/com/github/halmurat/rally/ui/RallyLoadStatusTest.kt`

**Interfaces:**
- Consumes: `QueryResultData.totalResultCount` (already deserialized); Task 10 (sequential `queryAllArtifacts` gone).
- Produces:
  - `data class PagedResult<T>(val items: List<T>, val totalResultCount: Int)` (api package).
  - `queryUserStories` / `queryDefects` now return `PagedResult<RallyUserStory>` / `PagedResult<RallyDefect>`; `queryAllTestCases` returns `PagedResult<RallyTestCase>` (breaking signature change — all callers are updated in this task).
  - `ArtifactQueryResult` gains `val totalAvailable: Int = -1` (third constructor param, after `partialFailureReasons`).
  - `internal fun buildLoadedStatusText(shownCount: Int, fetchedCount: Int, totalAvailable: Int, incomplete: Boolean): String` (ui package, top level).

- [ ] **Step 1: Write the failing status-text tests**

Create `src/test/kotlin/com/github/halmurat/rally/ui/RallyLoadStatusTest.kt`:

```kotlin
package com.github.halmurat.rally.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the "N loaded" status line (M3): the server-side total appears whenever the
 * fetch was truncated at page size, so a capped list can't masquerade as complete.
 */
class RallyLoadStatusTest {

    @Test
    fun `complete load shows plain count`() {
        assertEquals("200 loaded", buildLoadedStatusText(200, 200, 200, incomplete = false))
    }

    @Test
    fun `truncated load shows shown of total`() {
        assertEquals("200 of 934 loaded", buildLoadedStatusText(200, 200, 934, incomplete = false))
    }

    @Test
    fun `truncated load with client-side filter keeps the total`() {
        assertEquals("37 of 934 loaded", buildLoadedStatusText(37, 200, 934, incomplete = false))
    }

    @Test
    fun `unknown total (-1) shows plain count`() {
        assertEquals("50 loaded", buildLoadedStatusText(50, 50, -1, incomplete = false))
    }

    @Test
    fun `partial failure appends incomplete marker`() {
        assertEquals("10 loaded (incomplete)", buildLoadedStatusText(10, 10, -1, incomplete = true))
        assertEquals("10 of 30 loaded (incomplete)", buildLoadedStatusText(10, 10, 30, incomplete = true))
    }

    @Test
    fun `empty result shows zero loaded`() {
        assertEquals("0 loaded", buildLoadedStatusText(0, 0, 0, incomplete = false))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.github.halmurat.rally.ui.RallyLoadStatusTest"`
Expected: FAIL — `buildLoadedStatusText` unresolved.

- [ ] **Step 3: Implement the helper**

Create `src/main/kotlin/com/github/halmurat/rally/ui/RallyLoadStatus.kt`:

```kotlin
package com.github.halmurat.rally.ui

/**
 * Status line for the ticket list (M3). [shownCount] is the currently displayed
 * (scope/state/search-filtered) count; [fetchedCount] is how many rows the last
 * load actually fetched; [totalAvailable] is Rally's TotalResultCount summed across
 * the queried types (-1 when unknown, e.g. server-search results). When the
 * workspace holds more than the fetch cap the line reads "200 of 934 loaded"
 * instead of silently implying completeness; a partial failure appends the
 * existing "(incomplete)" marker. Pure and top-level so it is unit-testable.
 */
internal fun buildLoadedStatusText(
    shownCount: Int,
    fetchedCount: Int,
    totalAvailable: Int,
    incomplete: Boolean
): String {
    val truncated = totalAvailable > fetchedCount
    val base = if (truncated) "$shownCount of $totalAvailable loaded" else "$shownCount loaded"
    return if (incomplete) "$base (incomplete)" else base
}
```

Run: `./gradlew test --tests "com.github.halmurat.rally.ui.RallyLoadStatusTest"` — Expected: PASS.

- [ ] **Step 4: Add `PagedResult` and extend `ArtifactQueryResult`**

In `RallyApiModels.kt`, below `ArtifactQueryResult`:

```kotlin
/** Items plus Rally's server-reported TotalResultCount for one paged query (M3). */
data class PagedResult<T>(val items: List<T>, val totalResultCount: Int)
```

Change `ArtifactQueryResult` to:

```kotlin
data class ArtifactQueryResult(
    val artifacts: List<RallyArtifact>,
    val partialFailureReasons: List<String> = emptyList(),
    /** Server-side TotalResultCount summed across the queried types; -1 when unknown (M3). */
    val totalAvailable: Int = -1
) {
    val isPartial: Boolean get() = partialFailureReasons.isNotEmpty()
}
```

- [ ] **Step 5: Plumb totals through the client**

`queryAllPages`: change the return type to `PagedResult<T>`, track the last page's total:

```kotlin
    private fun <T> queryAllPages(
        /* params unchanged */
    ): PagedResult<T> {
        val allResults = mutableListOf<T>()
        var start = 1
        var total = 0

        do {
            val url = buildApiUrl(endpoint) + "?" +
                    buildQuery(query, pageSize, start, workspace, project, order, fields)
            val response = executeGet(url)
            handleResponse(response)
            val result: RallyQueryResult<T> = gson.fromJson(response.body(), typeToken)
            result.queryResult.requireNoErrors("paged query ($endpoint)")
            logWarnings(result.queryResult.warnings, "paged query ($endpoint)")
            allResults.addAll(result.queryResult.safeResults)
            total = result.queryResult.totalResultCount
            val effectivePageSize = result.queryResult.pageSize.takeIf { it > 0 } ?: pageSize
            start += effectivePageSize
        } while (allResults.size < result.queryResult.totalResultCount
            && result.queryResult.safeResults.isNotEmpty()
            && allResults.size < maxResults)

        return PagedResult(allResults, total)
    }
```

`queryUserStories`, `queryDefects`, `queryAllTestCases`: return type becomes `PagedResult<...>`; the cache stores the `PagedResult` under the same key:

```kotlin
    fun queryUserStories(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE, maxResults: Int = MAX_PAGE_SIZE): PagedResult<RallyUserStory> {
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "stories:${query}|${pageSize}|${maxResults}|${ws}|${pr}"
        getCached<PagedResult<RallyUserStory>>(cacheKey)?.let { return it }

        val results: PagedResult<RallyUserStory> = queryAllPages(
            "hierarchicalrequirement", TYPE_USER_STORIES, query, pageSize, maxResults,
            workspace = ws, project = pr
        )
        putCache(cacheKey, results)
        return results
    }
```

(`queryDefects` and `queryAllTestCases` follow identically, preserving their existing extra args.)

`getArtifactByFormattedId` does not use `queryAllPages` — unchanged.

`queryAllArtifactsParallel`: cache the full result object (it is now sole owner of the `"artifacts:"` prefix after Task 10) and sum totals:

```kotlin
    fun queryAllArtifactsParallel(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE, scope: String? = null, maxResults: Int = MAX_PAGE_SIZE): ArtifactQueryResult {
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "artifacts:${query}|${pageSize}|${maxResults}|${scope}|${ws}|${pr}"
        getCached<ArtifactQueryResult>(cacheKey)?.let { return it }

        val results = mutableListOf<RallyArtifact>()
        val reasons = mutableListOf<String>()
        var totalAvailable = 0

        val fetchStories = scope != "Defects"
        val fetchDefects = scope != "User Stories"

        // Kick the defect query onto apiExecutor first so it runs concurrently with the
        // inline user-story query below. Both sides are wrapped so a failure on one type
        // records a reason and continues with the other type's results (partial success).
        val defectsFuture: CompletableFuture<PagedResult<RallyDefect>>? =
            if (fetchDefects) {
                CompletableFuture.supplyAsync({ queryDefects(query, pageSize, maxResults) }, apiExecutor)
            } else null

        if (fetchStories) {
            try {
                val page = queryUserStories(query, pageSize, maxResults)
                results.addAll(page.items)
                totalAvailable += page.totalResultCount
            } catch (e: Exception) {
                reasons.add("User stories query failed: ${e.message}")
            }
        }

        if (defectsFuture != null) {
            try {
                val page = defectsFuture.get()
                results.addAll(page.items)
                totalAvailable += page.totalResultCount
            } catch (e: Exception) {
                reasons.add("Defects query failed: ${(e.cause ?: e).message}")
            }
        }

        if (results.isEmpty() && reasons.isNotEmpty()) {
            throw RallyApiException("Query failed - ${reasons.joinToString("; ")}")
        }

        val sorted = results.sortedByDescending { it.lastUpdateDate }
        if (reasons.isNotEmpty()) {
            LOG.warn("queryAllArtifactsParallel partial failure: ${reasons.joinToString("; ")}")
            notifyPartialFailure(reasons)
            return ArtifactQueryResult(sorted, reasons, totalAvailable)
        }

        val complete = ArtifactQueryResult(sorted, totalAvailable = totalAvailable)
        putCache(cacheKey, complete)
        return complete
    }
```

`searchArtifacts`: adapt to `.items` (totals stay unknown on the search path — the status line there is "N found via server search"):

- TC branch: `val tcResults: List<RallyArtifact> = queryAllTestCases(query, pageSize, maxResults).items`
- stories: `try { results.addAll(queryUserStories(query, pageSize, maxResults).items) }`
- defects: `try { results.addAll(queryDefects(query, pageSize, maxResults).items) }`

- [ ] **Step 6: Panel plumbing**

`RallyToolWindowPanel` fields, next to `lastLoadIncomplete`:

```kotlin
    /** How many rows the last loadTickets() actually fetched, and Rally's server-side
     *  total (-1 unknown) — kept so refreshLoadedStatus can re-render "X of Y loaded"
     *  after a client-side state-filter change (M3). */
    private var lastLoadFetched = 0
    private var lastLoadTotal = -1
```

`loadTickets` Test Cases branch:

```kotlin
                val result: ArtifactQueryResult = if (Scope.fromDisplay(scope) == Scope.TEST_CASES) {
                    val page = client.queryAllTestCases(query, pageSize, maxResults = pageSize)
                    ArtifactQueryResult(page.items, totalAvailable = page.totalResultCount)
                } else {
                    client.queryAllArtifactsParallel(query, pageSize, scope = scope, maxResults = pageSize)
                }
```

`loadTickets` completion block — replace the two status lines:

```kotlin
                    lastLoadIncomplete = result.isPartial
                    lastLoadFetched = artifacts.size
                    lastLoadTotal = result.totalAvailable
                    ...
                    statusLabel.text = buildLoadedStatusText(shownCount, artifacts.size, result.totalAvailable, result.isPartial)
                    statusLabel.icon = if (result.isPartial) AllIcons.General.Warning else null
```

`refreshLoadedStatus`:

```kotlin
    private fun refreshLoadedStatus() {
        statusLabel.text = buildLoadedStatusText(
            displayedArtifacts.size, lastLoadFetched, lastLoadTotal, lastLoadIncomplete
        )
        statusLabel.icon = if (lastLoadIncomplete) AllIcons.General.Warning else null
    }
```

- [ ] **Step 7: Run tests**

Run: `./gradlew test`
Expected: PASS (203 = 197 + 6).

- [ ] **Step 8: Commit**

```bash
git add -A src
git commit -m "feat(ui): surface Rally TotalResultCount — status shows '200 of 934 loaded'

queryAllPages deserialized TotalResultCount on every page and discarded it,
so a page-size-capped list looked complete. Plumbed through PagedResult /
ArtifactQueryResult.totalAvailable at zero extra network cost; status text
extracted to a tested pure helper."
```

---

### Task 12: M6 — Cold start loads the project list concurrently with artifacts

`loadProjects(client)` completes before the artifact query starts, but the serialization is only needed when a saved project *name* must be resolved to a ref for query scoping. In the default "All Projects" case, run it concurrently (the exact pattern iterations already use).

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (`loadTickets`)

**Interfaces:** none new. No new unit test (threading wiring inside `loadTickets`); gate is compile + suite.

- [ ] **Step 1: Restructure the project-load block in `loadTickets`**

Replace this region (from `val snapshot = ...` through `updateClientProjectRef(effectiveProjectIndex)`):

```kotlin
                // Load projects if not yet loaded or settings changed.
                // Use a non-sensitive SHA-256 fingerprint of the API key instead of String.hashCode()
                // so two distinct keys can't collide and mask a credential change.
                val snapshot = "${settings.serverUrl}|${apiKeyFingerprint(settings.apiKey)}|${settings.workspaceRef}"
                val savedProject = RallySettings.getInstance().selectedProject
                val projectScoped = savedProject.isNotBlank() && savedProject != "All Projects"
                var projectsFuture: java.util.concurrent.CompletableFuture<Void>? = null
                if (!projectsLoaded || snapshot != lastSettingsSnapshot) {
                    lastSettingsSnapshot = snapshot
                    invalidateIterations()
                    // The serial project load is only needed when a saved project NAME must
                    // be resolved to a ref before the artifact query can be scoped to it. In
                    // the default "All Projects" case the list feeds nothing but the dropdown,
                    // so it loads concurrently with the artifact fetch (M6) — the same pattern
                    // the iterations list uses. Saves a full RTT on cold open.
                    if (projectScoped) {
                        loadProjects(client)
                    } else {
                        projectsFuture = java.util.concurrent.CompletableFuture.runAsync({
                            loadProjects(client)
                        }, client.apiExecutor)
                    }
                }

                // Determine effective project selection from saved settings + cached data
                // (avoids reading Swing state off-EDT)
                val effectiveProjectIndex = if (projectScoped) {
                    val idx = cachedProjects.indexOfFirst { it.name == savedProject }
                    if (idx >= 0) idx + 1 else 0  // +1 for "All Projects" offset
                } else 0
                updateClientProjectRef(effectiveProjectIndex)
```

(Note: `invalidateIterations()` moves before the load — it only flips a flag + bumps a generation, order relative to `loadProjects` is immaterial. The later `val savedIter = ...` line stays; the previously duplicated `val savedProject = RallySettings.getInstance().selectedProject` read further down is replaced by the hoisted one.)

- [ ] **Step 2: Join the future at the end of the pooled block**

After the existing `iterationsFuture` join:

```kotlin
                // loadProjects handles its own errors and UI updates; join so this
                // background task's lifetime covers all the work it spawned.
                if (projectsFuture != null) {
                    try { projectsFuture.get() } catch (e: Exception) {
                        LOG.warn("Failed to load projects", e)
                    }
                }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test`
Expected: PASS (203).

- [ ] **Step 4: Commit**

```bash
git add -A src
git commit -m "perf(ui): load project list concurrently with artifacts on cold start

The serial loadProjects RTT is only needed when a saved project name must
resolve to a ref for query scoping; under the default All Projects it now
runs on apiExecutor alongside the artifact fetch, mirroring the iterations
pattern. ~300-800ms off cold open."
```

---

### Task 13: L2 + L3 — Correct fetch fields for `/user`; deterministic current-sprint pick

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/api/RallyApiClient.kt` (`getUserByUsername`, `queryCurrentIteration`)

**Interfaces:** none new. No new unit test (URL construction is private; behavior needs a live server); gate is compile + suite.

- [ ] **Step 1: L2 — fetch user fields, not artifact fields**

In `getUserByUsername`, replace the URL construction:

```kotlin
        val url = buildApiUrl("user") + "?" +
                buildQuery(
                    "(UserName = \"$safeUsername\")", pageSize = 1, workspace = ws,
                    // /user ignores unknown fetch fields, so the artifact LIST_FIELDS
                    // default returned a user with only _ref/_refObjectName —
                    // displayName/userName/emailAddress were always null (L2).
                    fields = listOf("UserName", "DisplayName", "EmailAddress", "ObjectID", "_ref")
                )
```

- [ ] **Step 2: L3 — order the current-iteration query**

In `queryCurrentIteration`, replace the URL construction:

```kotlin
        // Order by EndDate so, when several teams' sprints are concurrently active
        // under "All Projects", the pagesize=1 pick is deterministic (the sprint
        // ending soonest) rather than server-arbitrary (L3). Known limitation:
        // `today` is IDE-local while Rally compares in workspace time — near
        // midnight the resolved sprint can be off by one day.
        var url = buildApiUrl("iteration") +
                "?query=$encodedQuery&fetch=Name,StartDate,EndDate,PlannedVelocity,ObjectID,_ref&pagesize=1" +
                "&order=${URLEncoder.encode("EndDate ASC", StandardCharsets.UTF_8)}"
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test`
Expected: PASS (203).

- [ ] **Step 4: Commit**

```bash
git add -A src
git commit -m "fix(api): fetch real user fields from /user; order current-iteration pick by EndDate

getUserByUsername requested artifact LIST_FIELDS which Rally ignores,
leaving displayName/userName/emailAddress null for every caller. The
current-sprint lookup now orders by EndDate ASC so multi-team workspaces
get a deterministic pick instead of server-arbitrary."
```

---

### Task 14: L8 — IDE-wide proxy support (PAC + auth), not static-host-only

The client honors only static host/port proxy settings; PAC-based corporate networks silently go direct. `com.intellij.util.proxy.CommonProxy` (present well before 241) is the IDE-wide `java.net.ProxySelector` covering static, PAC, and the exceptions list; proxy credentials come from `HttpConfigurable`.

**Files:**
- Modify: `src/main/kotlin/com/github/halmurat/rally/api/RallyApiClient.kt` (httpClient builder)

**Interfaces:** none new. No new unit test (needs a real proxy); the existing `RallyApiClientHostTest` suite must stay green — client construction in unit tests hits the `catch` and falls back to direct, as today.

- [ ] **Step 1: Replace the proxy block in the `httpClient` builder**

```kotlin
    // HttpConfigurable is deprecated in 2025.x+ in favor of JdkProxyProvider, but that
    // class does not exist in 2024.1/2024.2. Since the plugin still supports sinceBuild=241,
    // we keep HttpConfigurable (present and functional through 261) and suppress the warning.
    // Migrate to JdkProxyProvider.getInstance().proxySelector once the floor is raised to 243+.
    @Suppress("DEPRECATION")
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .version(HttpClient.Version.HTTP_2)
        .apply {
            // Respect IDE proxy settings (Settings → Appearance & Behavior → System
            // Settings → HTTP Proxy). CommonProxy is the IDE-wide ProxySelector and
            // covers all three modes — static host/port, PAC scripts, and the
            // exceptions list — where the previous static-only ProxySelector.of(...)
            // silently went direct on PAC-based corporate networks (L8). Proxy
            // credentials are wired via java.net.Authenticator from HttpConfigurable.
            // Note: the JDK disables Basic auth for tunneled HTTPS by default
            // (jdk.http.auth.tunneling.disabledSchemes) — best-effort, like the IDE's
            // own HTTP stack.
            try {
                proxy(com.intellij.util.proxy.CommonProxy.getInstance())
                val httpConfigurable = com.intellij.util.net.HttpConfigurable.getInstance()
                if (httpConfigurable.PROXY_AUTHENTICATION) {
                    val login = httpConfigurable.proxyLogin
                    val password = httpConfigurable.plainProxyPassword
                    if (!login.isNullOrBlank() && !password.isNullOrEmpty()) {
                        authenticator(object : java.net.Authenticator() {
                            override fun getPasswordAuthentication(): java.net.PasswordAuthentication? =
                                if (requestorType == RequestorType.PROXY)
                                    java.net.PasswordAuthentication(login, password.toCharArray())
                                else null
                        })
                    }
                }
            } catch (_: Throwable) {
                // IDE proxy API not available (unit tests / non-IDE environment) — direct connection
            }
        }
        .build()
```

(Catch `Throwable`, not `Exception`: a missing platform class in unit tests raises `NoClassDefFoundError`.)

- [ ] **Step 2: Run tests**

Run: `./gradlew test`
Expected: PASS (203) — in particular `RallyApiClientHostTest` and `RallyApiClientCompanionTest`.

- [ ] **Step 3: Commit**

```bash
git add -A src
git commit -m "fix(api): honor IDE-wide proxy settings including PAC and proxy auth

The static-only ProxySelector.of(host, port) silently went direct on
PAC-configured networks and never authenticated. CommonProxy.getInstance()
covers static/PAC/exceptions; credentials come from HttpConfigurable via a
java.net.Authenticator."
```

---

### Task 15: L9 + L10 — Settings Apply refreshes the tool window; searchable options gated to CI

**Files:**
- Create: `src/main/kotlin/com/github/halmurat/rally/settings/RallySettingsListener.kt`
- Modify: `src/main/kotlin/com/github/halmurat/rally/settings/RallySettingsConfigurable.kt` (`apply()`)
- Modify: `src/main/kotlin/com/github/halmurat/rally/ui/RallyToolWindowPanel.kt` (subscribe in `init`)
- Modify: `build.gradle.kts` (`buildSearchableOptions`)

**Interfaces:**
- Produces: `interface RallySettingsListener { fun settingsApplied() }` with `RallySettingsListener.TOPIC` (application-level message bus; no plugin.xml registration needed for code-created topics).

No new unit test (message-bus + Gradle config); gate is compile + suite.

- [ ] **Step 1: Create the topic**

`src/main/kotlin/com/github/halmurat/rally/settings/RallySettingsListener.kt`:

```kotlin
package com.github.halmurat.rally.settings

import com.intellij.util.messages.Topic

/**
 * Application-level notification that Rally settings were applied (L9). The tool
 * window subscribes and reloads, so a changed server/key/username takes effect
 * immediately instead of leaving stale data on screen until a manual Refresh
 * (the client itself was already rebuilt lazily off the settings snapshot —
 * this closes the UI loop, not a staleness bug).
 */
interface RallySettingsListener {
    fun settingsApplied()

    companion object {
        @JvmField
        val TOPIC: Topic<RallySettingsListener> =
            Topic.create("Rally settings applied", RallySettingsListener::class.java)
    }
}
```

- [ ] **Step 2: Publish from `apply()`**

At the end of `RallySettingsConfigurable.apply()`:

```kotlin
        // Nudge open tool windows to reload with the new settings (L9).
        ApplicationManager.getApplication().messageBus
            .syncPublisher(RallySettingsListener.TOPIC)
            .settingsApplied()
```

- [ ] **Step 3: Subscribe in the panel**

In `RallyToolWindowPanel.init` (before `checkInitialConfiguration()`; add `import com.github.halmurat.rally.settings.RallySettingsListener`):

```kotlin
        // Reload when Settings → Tools → Rally is applied (L9). connect(this) ties the
        // subscription to this panel's Disposable, so it detaches on dispose.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(RallySettingsListener.TOPIC, object : RallySettingsListener {
                override fun settingsApplied() {
                    invokeLaterIfAlive { loadTickets() }
                }
            })
```

- [ ] **Step 4: Gate `buildSearchableOptions` (L10)**

In `build.gradle.kts`:

```kotlin
    // Building searchable options boots a headless IDE per buildPlugin run to index one
    // small settings page (L10) — needed for release artifacts, pure overhead during
    // development. CI keeps the index; local builds skip it.
    buildSearchableOptions = providers.environmentVariable("CI").isPresent
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test`
Expected: PASS (203).

- [ ] **Step 6: Commit**

```bash
git add -A src build.gradle.kts
git commit -m "feat(settings,build): apply-time tool-window refresh; searchable options only on CI

Settings Apply now publishes an application-level topic the tool window
subscribes to, so new credentials load immediately instead of after a
manual Refresh (L9). buildSearchableOptions is gated to CI — it boots a
headless IDE per build to index one settings page (L10)."
```

---

### Task 16: CLAUDE.md refresh + final verification

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:** none.

- [ ] **Step 1: Run the full suite and capture the real test count**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. Then get the count: `find build/test-results/test -name "*.xml" | xargs grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' | paste -sd+ - | bc`
Expected: 203 (verify — use the actual number below).

- [ ] **Step 2: Update CLAUDE.md**

- **Testing section**: replace "176 unit tests across 14 classes" with the actual count/classes, and append to the audit-additions list: "query-per-scope builder (H1), selection-index restore, load-status text (M3), exporter attachment naming (M5), entity decode order (L1)".
- **Performance Optimizations table** — add rows:
  - `| **Selection debounce** | 200 ms single-shot Timer between list selection and detail loads (header/badge update instantly) | Arrow-scrolling costs zero API calls for skipped rows |`
  - `| **Dedicated export executor** | Per-export daemon pool for export orchestration; apiExecutor only serves short HTTP calls | Tool window stays responsive during multi-ticket exports |`
  - `| **Truncation-aware status** | TotalResultCount plumbed through PagedResult/ArtifactQueryResult → "200 of 934 loaded" | Removes silent page-size truncation |`
  - `| **Raw-first attachment fetch** | downloadAttachmentBytes: raw endpoint, base64 fallback (save + export) | ~25% less transfer, ~1x peak heap on large attachments |`
  - `| **Parallel project load** | Project list loads concurrently with artifacts when scoped to "All Projects" | One RTT off cold open |`
  - `| **Shared exporter download pool** | One lazy pool per RallyExporter (AutoCloseable) instead of a pool per call | No pool churn on step-heavy test cases |`
- **Key Design Decisions** — add:
  - "**Log policy** — expected environmental failures (network, auth, declined transitions) log at WARN; `LOG.error` is reserved for programming errors because the platform turns it into an IDE fatal-error report."
  - "**Selection preserved across refresh** — `updateListModel` re-selects surviving rows by ref and collapses the detail split when the selection is gone."
  - "**Settings-apply refresh** — `RallySettingsListener.TOPIC` (application message bus) triggers `loadTickets()` when Settings are applied."
  - "**Export filenames** — attachments are `${objectId}_name` (OID-keyed, like inline images) so re-exports overwrite instead of accumulating copies."
- **Rally WSAPI Gotchas** — add: "`TestCase` has no `Iteration` attribute — sprint-filter test cases via `WorkProduct.Iteration.Name` (dotted traversal); `(Iteration.Name = …)` on `/testcase` returns 200-with-Errors." and "The `/user` endpoint ignores unknown fetch fields and silently returns a ref-only user — always pass user fields explicitly."
- **API Methods table** — add `downloadAttachmentBytes(attachment)` — "Attachment bytes, raw endpoint first, base64 fallback". (The dead rows were removed in Task 10.)
- **Build & Run** — note `buildSearchableOptions` is CI-only.
- **Proxy** — in Key Design Decisions, update the HTTP/2 bullet: "Respects IDE proxy settings including PAC and proxy authentication via `CommonProxy`".

- [ ] **Step 3: Final check + commit**

Run: `./gradlew test`
Expected: PASS.

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for the 2026-07-01 audit fixes"
```

---

## Execution notes for the orchestrator

- Tasks 1→16 are ordered by the audit's suggested fix order, adjusted for dependencies: **Task 4 needs Task 3** (listener/`isShowing`), **Task 6 needs Task 5** (same exporter function), **Task 9 needs Task 8** (deadlock-freedom argument), **Task 11 needs Task 10** (`"artifacts:"` cache-prefix ownership). Tasks 7, 12, 13, 14, 15 are independent of their neighbors but keep the order to avoid merge conflicts in the shared files.
- Each task = one subagent dispatch + review. Line numbers in this plan describe the state at commit `e1ecb6e` and drift as tasks land — subagents must locate edit sites by the quoted code/message strings, not line numbers.
- Expected final test count: 203 (182 baseline + 7 H1 + 3 M7 + 3 M5 + 2 L1 + 6 M3). Verify with the actual run in Task 16.
