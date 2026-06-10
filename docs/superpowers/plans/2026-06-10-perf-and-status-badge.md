# Performance + Status Badge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 14 verified performance improvements and the tinted-chip status badge from the approved spec (`docs/superpowers/specs/2026-06-10-perf-and-status-badge-design.md`).

**Architecture:** All changes are behavior-preserving edits inside four existing files plus one new UI component (`StatusBadge`) and an extended `RallyColors`. Network layer gains gzip; threading moves blocking work off the shared 4-thread `apiExecutor`; Swing updates become incremental; export deduplicates downloads. The badge replaces plain colored state text at all four render sites via a centralized `RallyColors.forState()` lookup.

**Tech Stack:** Kotlin 1.9.25, JVM 17, IntelliJ Platform 2024.1 (Swing, JBColor/JBUI), java.net.http.HttpClient, Gson, JUnit 4.

**Build/test commands** (run from repo root `/Users/halmurat-max/IdeaProjects/rally-plugin`):
- Compile + tests: `./gradlew test` → expect `BUILD SUCCESSFUL`, all tests pass
- Full check: `./gradlew clean build verifyPlugin`
- Manual: `./gradlew runIde`

**Commit rules (from CLAUDE.md):** conventional format `type(scope): description`, **no AI attribution lines**.

Line numbers reference the tree at commit `4f70eec`. If a snippet doesn't match exactly, locate by the quoted code, not the line number.

---

### Task 1: Drop unused `Description` from `queryTestCases` fetch (A9)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt:1181`

- [ ] **Step 1: Edit the fetch list**

In `queryTestCases()`, change:

```kotlin
        var url = buildApiUrl("testcase") +
                "?query=$encodedQuery&fetch=FormattedID,Name,Method,Type,LastVerdict,LastRun,Owner,WorkProduct,Description,Priority,ObjectID,_ref" +
                "&pagesize=$pageSize&order=${URLEncoder.encode("FormattedID ASC", StandardCharsets.UTF_8)}"
```

to:

```kotlin
        // No Description here: nothing reads it from this list path (the detail tab
        // renders ID/name/method/verdict; the exporter re-fetches by ID with its own
        // Description fetch). Saves a full HTML payload per linked test case.
        var url = buildApiUrl("testcase") +
                "?query=$encodedQuery&fetch=FormattedID,Name,Method,Type,LastVerdict,LastRun,Owner,WorkProduct,Priority,ObjectID,_ref" +
                "&pagesize=$pageSize&order=${URLEncoder.encode("FormattedID ASC", StandardCharsets.UTF_8)}"
```

Do NOT touch `queryTestCaseByFormattedId` (line ~1268) — the exporter needs its Description.

- [ ] **Step 2: Verify**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "perf(api): stop fetching unused Description in queryTestCases"
```

---

### Task 2: Cache `getUserByUsername` (A10)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt:497-511`

- [ ] **Step 1: Wrap in the existing query cache**

Replace the body of `getUserByUsername`:

```kotlin
    fun getUserByUsername(username: String): RallyUser {
        val ws = workspaceRef
        val safeUsername = escapeQueryValue(username)
        // The username -> user-ref mapping effectively never changes mid-session,
        // yet this sits on the blocking path of every create/Start Working action.
        // Standard TTL applies; clearCache() on manual Refresh evicts it.
        val cacheKey = "user:$safeUsername|$ws"
        getCached<RallyUser>(cacheKey)?.let { return it }

        val url = buildApiUrl("user") + "?" +
                buildQuery("(UserName = \"$safeUsername\")", pageSize = 1, workspace = ws)
        val response = executeGet(url)
        handleResponse(response)

        val root = JsonParser.parseString(response.body()).asJsonObject
        val results = root.getAsJsonObject("QueryResult")?.getAsJsonArray("Results")
        if (results != null && results.size() > 0) {
            val user = gson.fromJson(results.get(0), RallyUser::class.java)
            putCache(cacheKey, user)
            return user
        }
        throw RallyApiException("No user found with the configured username")
    }
```

Note the not-found path throws WITHOUT caching (per spec: never cache failures).

- [ ] **Step 2: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(api): cache getUserByUsername lookups"
```

---

### Task 3: Narrow `clearArtifactCache` eviction (A11)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt:238-241`

- [ ] **Step 1: Drop the two iteration-related prefixes**

In `clearArtifactCache()`, change the condition:

```kotlin
                if (key.startsWith("artifact:") || key.startsWith("artifacts:") ||
                    key.startsWith("stories:") || key.startsWith("defects:") ||
                    key.startsWith("alltestcases:") || key.startsWith("search:") ||
                    key.startsWith("sprint:") || key.startsWith("currentIteration:")) {
```

to:

```kotlin
                // currentIteration: is NOT evicted here — which sprint is current is a
                // function of today's date + the sprint calendar; no artifact mutation
                // can change it. clearCache() (manual Refresh) still evicts it.
                // ("sprint:" was dead code: no putCache ever writes that prefix.)
                if (key.startsWith("artifact:") || key.startsWith("artifacts:") ||
                    key.startsWith("stories:") || key.startsWith("defects:") ||
                    key.startsWith("alltestcases:") || key.startsWith("search:")) {
```

- [ ] **Step 2: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(api): keep currentIteration cache across artifact mutations"
```

---

### Task 4: Generation guard on Create Task completion (A14 — correctness)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyDetailPanel.kt:789-805` (`showCreateTaskDialog`)

- [ ] **Step 1: Capture and check the generation**

Replace the background block of `showCreateTaskDialog()`:

```kotlin
        val gen = generation.get()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val task = client.createTask(taskName, artifactRef, estimate = estimate)
                // Invalidate the cached task list for this work product so the next
                // detail-panel reopen re-fetches and includes the new task.
                client.clearTasksCache(artifactRef)
                ApplicationManager.getApplication().invokeLater {
                    // Generation check: if the user switched artifacts while createTask
                    // was in flight, this row belongs to the PREVIOUS artifact's Tasks
                    // tab — clearTasksCache above already guarantees it appears when
                    // that artifact is next opened.
                    if (disposed || generation.get() != gen) return@invokeLater
                    taskListModel.addElement(task)
                    val count = taskListModel.size()
                    for (i in 0 until tabbedPane.tabCount) {
                        if (tabbedPane.getTitleAt(i).startsWith("Tasks")) {
                            tabbedPane.setTitleAt(i, "Tasks ($count)")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to create task", e)
                ApplicationManager.getApplication().invokeLater {
                    if (disposed) return@invokeLater
                    Messages.showErrorDialog(project, "Failed to create task: ${e.message}", "Rally")
                }
            }
        }
```

(Only the `val gen = generation.get()` line before `executeOnPooledThread` and the `|| generation.get() != gen` in the success path are new. The error dialog keeps disposed-only — the user should see their action failed regardless of selection.)

- [ ] **Step 2: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "fix(ui): guard Create Task completion against stale artifact selection"
```

---

### Task 5: Delete duplicate src-neutralization pass (A8)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyDetailPanel.kt:1048-1054` (end of `resolveInlineImages`)

- [ ] **Step 1: Return directly**

Replace:

```kotlin
        val resolved = sb.toString()
        // Neutralize any remaining external http(s) src attributes to prevent JTextPane
        // network fetches. The pattern already consumes the entire src="..." run via the
        // backreference, so we just emit a single empty src attribute. Kept as an
        // escaped string literal rather than a raw triple-quoted one — the adjacent
        // closing quotes in the raw form were legal but near-unreadable.
        return EXTERNAL_SRC_PATTERN.matcher(resolved).replaceAll("src=\"\"")
```

with:

```kotlin
        // External-src neutralization happens in wrapHtml — the documented single
        // chokepoint every descriptionPane.text assignment goes through — so a second
        // multi-MB regex pass here would be pure duplicate work.
        return sb.toString()
```

- [ ] **Step 2: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(ui): drop duplicate external-src neutralization pass"
```

---

### Task 6: Reuse detail-panel scroll panes (A12)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyDetailPanel.kt` (fields ~104-112, `setupUI` 193/199, `showArtifact` 302-303 + 337, `clear` 549-550, `loadTestSteps` 692)

- [ ] **Step 1: Hoist three scroll panes to fields**

After the `stepList` declaration (line ~112), add:

```kotlin
    // Scroll panes are fields (like tcPanel) so the tab-restore sites in
    // showArtifact()/clear() reuse them instead of allocating fresh
    // JBScrollPane + viewport + scrollbar UI on every selection toggle.
    private val taskScrollPane = JBScrollPane(taskList)
    private val attachmentScrollPane = JBScrollPane(attachmentList)
    private val stepScrollPane = JBScrollPane(stepList)
```

- [ ] **Step 2: Use the fields at all five sites**

In `setupUI()` delete the two local declarations:
```kotlin
        val taskScrollPane = JBScrollPane(taskList)
```
```kotlin
        val attachmentScrollPane = JBScrollPane(attachmentList)
```
(the `tabbedPane.addTab("Tasks", taskScrollPane)` / `addTab("Attachments", attachmentScrollPane)` lines now resolve to the fields).

In `showArtifact()` tab-restore block (lines 299-304) and in `clear()` (lines 546-551), replace:
```kotlin
            tabbedPane.addTab("Tasks", JBScrollPane(taskList))
            tabbedPane.addTab("Attachments", JBScrollPane(attachmentList))
```
with:
```kotlin
            tabbedPane.addTab("Tasks", taskScrollPane)
            tabbedPane.addTab("Attachments", attachmentScrollPane)
```

In the test-case branch of `showArtifact()` (line 337) and in `loadTestSteps()` (line 692), replace `JBScrollPane(stepList)` with `stepScrollPane`:
```kotlin
            tabbedPane.addTab("Test Steps", stepScrollPane)
```
```kotlin
            tabbedPane.addTab("Test Steps (...)", stepScrollPane)
```

- [ ] **Step 3: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(ui): reuse detail-panel scroll panes instead of reallocating per selection"
```

---

### Task 7: Fixed cell height on the ticket list (A5)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt:265`

- [ ] **Step 1: Measure one prototype row and pin the height**

After `artifactList.cellRenderer = ArtifactCellRenderer()` (line 265), add:

```kotlin
        // Pin the row height from one prototype render. Without fixedCellHeight,
        // BasicListUI calls the renderer + getPreferredSize() for EVERY element on
        // EVERY model event (each debounced keystroke, every refresh) to compute row
        // heights — O(n) nested-layout passes for rows that are all the same height.
        val prototype = RallyUserStory(
            formattedID = "US00000", name = "Prototype", scheduleState = "In-Progress",
            owner = RallyUser(displayName = "Prototype Owner")
        )
        artifactList.fixedCellHeight = artifactList.cellRenderer
            .getListCellRendererComponent(artifactList, prototype, 0, false, false)
            .preferredSize.height
```

(Both `RallyUserStory` and `RallyUser` are data classes with all-default constructor params — verified at `RallyApiModels.kt:56` and `:239` — so this named-argument construction compiles as written.)

- [ ] **Step 2: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(ui): fix ticket list cell height to avoid O(n) renderer measurement"
```

---

### Task 8: Patch single rows in place after optimistic updates (A6)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt` — new helper near `updateListModel` (~906), call sites in `changeState` (~1630), `editPoints` (~1529-1539), `startWorking` (~1785), `finishWorking` (~1845)

- [ ] **Step 1: Add the helper below `updateListModel`**

```kotlin
    /**
     * Patch already-updated artifacts (fresh copies live in [allArtifacts]) into the
     * visible list model in place. Unlike applySearchFilter()'s clear()+addAll()
     * rebuild, this fires one contentsChanged event per row and preserves the JList
     * selection — optimistic updates shouldn't collapse the detail panel, drop a
     * multi-select, or re-measure every row. Rows filtered out of the current view
     * are simply absent from the model and skipped, same as before.
     */
    private fun patchArtifactsInModel(refs: Collection<String>) {
        if (refs.isEmpty()) return
        val byRef = HashMap<String, RallyArtifact>()
        for (a in allArtifacts) {
            val r = a.ref ?: continue
            if (r in refs) byRef[r] = a
        }
        var patched = false
        for (i in 0 until listModel.size()) {
            val replacement = listModel.getElementAt(i).ref?.let(byRef::get) ?: continue
            listModel.setElementAt(replacement, i)
            patched = true
        }
        if (patched) updateStats(java.util.Collections.list(listModel.elements()))
    }
```

- [ ] **Step 2: Use it in `changeState`**

Replace (inside the `invokeLaterIfAlive` block, after the `allArtifacts = allArtifacts.map {...}` patch):

```kotlin
                client.clearArtifactCache()
                applySearchFilter()
                statusLabel.text = "Updated ${results.get()}"
```

with:

```kotlin
                client.clearArtifactCache()
                patchArtifactsInModel(successfulRefs)
                statusLabel.text = "Updated ${results.get()}"
```

- [ ] **Step 3: Use it in `editPoints` and delete the selection-restore workaround**

Replace:

```kotlin
                    client.clearArtifactCache()
                    // applySearchFilter() rebuilds the list model, which clears the JList
                    // selection. Grab the freshly-copied artifact first so we can restore
                    // the selection and refresh the detail panel with it — otherwise
                    // artifactList.selectedValue is null and the panel collapses.
                    val updated = allArtifacts.firstOrNull { it.ref == ref }
                    applySearchFilter()
                    if (updated != null) artifactList.setSelectedValue(updated, true)
                    statusLabel.text = "Updated ${selected.formattedID} points"
                    // Refresh detail panel metadata with the updated artifact
                    detailPanel.showArtifact(updated ?: artifactList.selectedValue, client)
```

with:

```kotlin
                    client.clearArtifactCache()
                    // In-place patch preserves the selection, so no restore dance needed.
                    patchArtifactsInModel(listOf(ref))
                    statusLabel.text = "Updated ${selected.formattedID} points"
                    // Refresh detail panel metadata with the updated artifact
                    allArtifacts.firstOrNull { it.ref == ref }?.let { detailPanel.showArtifact(it, client) }
```

- [ ] **Step 4: Use it in `startWorking` and `finishWorking`**

In `startWorking`'s `invokeLaterIfAlive` block, replace:

```kotlin
                client.clearArtifactCache()
                applySearchFilter()
```

with:

```kotlin
                client.clearArtifactCache()
                if (stateChangeSucceeded) patchArtifactsInModel(listOf(ticketRef))
```

In `finishWorking`'s `invokeLaterIfAlive` block, replace:

```kotlin
                    client.clearArtifactCache()
                    applySearchFilter()
                    statusLabel.text = "Finished $ticketId"
```

with:

```kotlin
                    client.clearArtifactCache()
                    patchArtifactsInModel(listOf(ticketRef))
                    statusLabel.text = "Finished $ticketId"
```

- [ ] **Step 5: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(ui): patch list rows in place after optimistic updates, preserving selection"
```

---

### Task 9: Description loading — unblock apiExecutor and move wrapHtml off the EDT (A3 + A7)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyDetailPanel.kt` — TestCase branch (~340-397) and story/defect branch (~413-474) of `showArtifact`

Both branches change the same way: the description work (fetch + `resolveInlineImages`, which blocks on `imageExecutor` futures) moves from `CompletableFuture.supplyAsync(..., client.apiExecutor)` onto the outer `executeOnPooledThread` thread — which currently only submits futures and exits — so none of the 4 shared `apiExecutor` workers parks on `join()` (A3). And `wrapHtml(text)` runs on that background thread, with only the final string assignment inside `invokeLater` (A7). Other futures (steps/test cases/tasks/attachments) stay on `apiExecutor` and are submitted FIRST so they run concurrently with the description work.

- [ ] **Step 1: Rewrite the TestCase branch's background block**

Replace lines ~340-397 (`ApplicationManager.getApplication().executeOnPooledThread { ... }` containing `descFuture` and `stepsFuture`) with:

```kotlin
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

                // Description work runs on THIS pooled thread (effectively unbounded
                // pool): fetchDescription + resolveInlineImages block on image
                // downloads, and parking one of apiExecutor's 4 shared workers on that
                // join starved fresh loads during rapid ticket switching. wrapHtml also
                // runs here so its full-string scans stay off the EDT.
                try {
                    val resolvedDesc: String? = run {
                        if (generation.get() != gen) return@run null
                        var resolved = desc
                        if (resolved.isNullOrBlank()) {
                            try {
                                resolved = client.fetchDescription(artifactRef)
                            } catch (e: RallyAuthenticationException) {
                                LOG.warn("Auth failure fetching description for $id", e)
                                return@run DESC_AUTH_FAILED
                            } catch (e: Exception) {
                                LOG.warn("Failed to fetch description for $id", e)
                            }
                        }
                        if (generation.get() != gen) return@run null
                        val resolvedNonNull = resolved
                        if (!resolvedNonNull.isNullOrBlank()) {
                            try { resolveInlineImages(resolvedNonNull, client, gen) } catch (e: Exception) {
                                LOG.warn("Failed to resolve inline images for $id", e)
                                resolvedNonNull
                            }
                        } else null
                    }
                    if (generation.get() == gen && !disposed) {
                        val text = when {
                            resolvedDesc == DESC_AUTH_FAILED -> AUTH_ERROR_HTML
                            !resolvedDesc.isNullOrBlank() -> resolvedDesc
                            else -> "<i>No description</i>"
                        }
                        val wrapped = wrapHtml(text)
                        ApplicationManager.getApplication().invokeLater {
                            if (generation.get() != gen || disposed) return@invokeLater
                            descriptionPane.text = wrapped
                            descriptionPane.caretPosition = 0
                        }
                    }
                } catch (t: Exception) {
                    LOG.warn("Detail panel description update failed", t)
                }
            }
            return  // Skip the normal story/defect detail loading
```

- [ ] **Step 2: Rewrite the story/defect branch the same way**

In the second `executeOnPooledThread` block (~413-524): DELETE the `descFuture` declaration (the `CompletableFuture.supplyAsync({...}, client.apiExecutor)` assigned to `val descFuture`) and its `descFuture.thenAccept {...}.exceptionally {...}` continuation. Keep `tcFuture`, `taskFuture`, `attachFuture` and their `thenAccept` continuations exactly as they are. Then, AFTER the `attachFuture.thenAccept{...}.exceptionally{...}` line (so all three futures are already submitted), append the same inline description block as Step 1:

```kotlin
            // Description work runs on THIS pooled thread — see the test-case branch
            // for why (apiExecutor starvation + EDT-side wrapHtml cost).
            try {
                val resolvedDesc: String? = run {
                    if (generation.get() != gen) return@run null
                    var resolved = desc
                    if (resolved.isNullOrBlank()) {
                        try {
                            resolved = client.fetchDescription(artifactRef)
                        } catch (e: RallyAuthenticationException) {
                            LOG.warn("Auth failure fetching description for $id", e)
                            return@run DESC_AUTH_FAILED
                        } catch (e: Exception) {
                            LOG.warn("Failed to fetch description for $id", e)
                        }
                    }
                    if (generation.get() != gen) return@run null
                    val resolvedNonNull = resolved
                    if (!resolvedNonNull.isNullOrBlank()) {
                        try { resolveInlineImages(resolvedNonNull, client, gen) } catch (e: Exception) {
                            LOG.warn("Failed to resolve inline images for $id", e)
                            resolvedNonNull
                        }
                    } else null
                }
                if (generation.get() == gen && !disposed) {
                    val text = when {
                        resolvedDesc == DESC_AUTH_FAILED -> AUTH_ERROR_HTML
                        !resolvedDesc.isNullOrBlank() -> resolvedDesc
                        else -> "<i>No description</i>"
                    }
                    val wrapped = wrapHtml(text)
                    ApplicationManager.getApplication().invokeLater {
                        if (generation.get() != gen || disposed) return@invokeLater
                        descriptionPane.text = wrapped
                        descriptionPane.caretPosition = 0
                    }
                }
            } catch (t: Exception) {
                LOG.warn("Detail panel description update failed", t)
            }
```

`wrapHtml` touches no Swing components (`JBUI.scaleFontSize` is a cached-scale lookup) — safe off-EDT. The synchronous `descriptionPane.text = wrapHtml(desc)` first-paint at line ~328 stays as is (raw description, no base64 yet).

- [ ] **Step 3: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(ui): keep apiExecutor free during image resolution and wrap HTML off-EDT"
```

---

### Task 10: Parallelize iterations load with the artifact fetch (A2)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt:472-481` (`loadTickets`) and the `sprintFuture` join block (~529-533)

- [ ] **Step 1: Branch on whether the saved sprint needs validation**

Replace:

```kotlin
                // Load iterations if not yet loaded (or reset after project change)
                if (!iterationsLoaded) {
                    loadIterations(client)
                }

                val savedIter = RallySettings.getInstance().selectedIteration
                val effectiveIter = if (savedIter.isNotBlank() && savedIter != "All Sprints" &&
                    cachedIterations.any { it.name == savedIter }) savedIter else ""
```

with:

```kotlin
                // Load iterations if not yet loaded (or reset after project change).
                // When no saved sprint must be validated against the list (the default,
                // and the state after every project switch), the iteration list only
                // feeds the dropdown — run it concurrently with the artifact fetch
                // instead of paying a serial round trip before it.
                val savedIter = RallySettings.getInstance().selectedIteration
                val needsIterationValidation = savedIter.isNotBlank() && savedIter != "All Sprints"
                var iterationsFuture: java.util.concurrent.CompletableFuture<Void>? = null
                if (!iterationsLoaded) {
                    if (needsIterationValidation) {
                        loadIterations(client)
                    } else {
                        iterationsFuture = java.util.concurrent.CompletableFuture.runAsync({
                            loadIterations(client)
                        }, client.apiExecutor)
                    }
                }

                val effectiveIter = if (needsIterationValidation &&
                    cachedIterations.any { it.name == savedIter }) savedIter else ""
```

- [ ] **Step 2: Join the future at the end, next to sprintFuture**

After the existing `if (sprintFuture != null) { try { sprintFuture.get() } ... }` block, add:

```kotlin
                // loadIterations handles its own errors and UI updates; join only so
                // this load cycle doesn't report done with the dropdown still pending.
                if (iterationsFuture != null) {
                    try { iterationsFuture.get() } catch (e: Exception) {
                        LOG.warn("Failed to load iterations", e)
                    }
                }
```

Thread-budget note: at most 3 concurrent apiExecutor tasks here (artifacts + sprint + iterations) on a 4-thread pool, and `queryAllArtifacts` is internally sequential — no deadlock.

- [ ] **Step 3: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(ui): load iterations concurrently with artifacts when no sprint filter is saved"
```

---

### Task 11: gzip for all JSON responses (A1) — TDD

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt` — companion (~276), `executeGet` (365-377), `executePost` (873-885), new private class at file bottom
- Test: `src/test/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClientCompanionTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `RallyApiClientCompanionTest`:

```kotlin
    @Test
    fun `decodeBody gunzips when content-encoding is gzip`() {
        val original = """{"QueryResult":{"Results":[]}}"""
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(original.toByteArray(Charsets.UTF_8)) }
        assertEquals(original, RallyApiClient.decodeBody(baos.toByteArray(), "gzip"))
    }

    @Test
    fun `decodeBody is case-insensitive for the encoding token`() {
        val original = "plain"
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(original.toByteArray(Charsets.UTF_8)) }
        assertEquals(original, RallyApiClient.decodeBody(baos.toByteArray(), "GZIP"))
    }

    @Test
    fun `decodeBody passes plain utf8 through when no encoding`() {
        val original = """{"User":{"UserName":"a@b.c"}}"""
        assertEquals(original, RallyApiClient.decodeBody(original.toByteArray(Charsets.UTF_8), null))
    }

    @Test
    fun `decodeBody passes plain utf8 through for identity encoding`() {
        assertEquals("x", RallyApiClient.decodeBody("x".toByteArray(Charsets.UTF_8), "identity"))
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests "*RallyApiClientCompanionTest*"`
Expected: FAIL — `unresolved reference: decodeBody` (compile error counts as the failing state).

- [ ] **Step 3: Implement `decodeBody` in the companion**

Add to the `companion object` (next to `escapeQueryValue`):

```kotlin
        /**
         * Decode an HTTP response body, gunzipping when Content-Encoding says gzip.
         * JDK HttpClient neither requests nor decompresses gzip on its own, so the
         * request side sends Accept-Encoding: gzip and this is the matching decode.
         * Servers/proxies that ignore or strip the header fall through to plain UTF-8.
         * Public so unit tests can exercise it without constructing a client.
         */
        fun decodeBody(bytes: ByteArray, contentEncoding: String?): String {
            return if (contentEncoding != null && contentEncoding.equals("gzip", ignoreCase = true)) {
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
            } else {
                bytes.toString(Charsets.UTF_8)
            }
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*RallyApiClientCompanionTest*"` → PASS.

- [ ] **Step 5: Add the adapter class and switch executeGet/executePost**

At the very bottom of `RallyApiClient.kt` (file scope, after the class closing brace):

```kotlin
/**
 * Presents a byte-array HTTP response as HttpResponse<String> with the body already
 * decoded (and gunzipped when needed), so the many existing call sites that use
 * response.body()/statusCode()/headers() stay untouched.
 */
private class DecodedResponse(
    private val delegate: java.net.http.HttpResponse<ByteArray>,
    private val decoded: String
) : java.net.http.HttpResponse<String> {
    override fun statusCode(): Int = delegate.statusCode()
    override fun request(): java.net.http.HttpRequest = delegate.request()
    override fun previousResponse(): java.util.Optional<java.net.http.HttpResponse<String>> =
        java.util.Optional.empty()
    override fun headers(): java.net.http.HttpHeaders = delegate.headers()
    override fun body(): String = decoded
    override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = delegate.sslSession()
    override fun uri(): java.net.URI = delegate.uri()
    override fun version(): java.net.http.HttpClient.Version = delegate.version()
}
```

Rewrite `executeGet`:

```kotlin
    private fun executeGet(url: String): HttpResponse<String> {
        requireSameHost(url)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(ZSESSION_HEADER, apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()

        val response = executeWithRetry(request, HttpResponse.BodyHandlers.ofByteArray())
        return DecodedResponse(
            response,
            decodeBody(response.body(), response.headers().firstValue("Content-Encoding").orElse(null))
        )
    }
```

Rewrite `executePost` identically (keep `.POST(HttpRequest.BodyPublishers.ofString(jsonBody))`):

```kotlin
    private fun executePost(url: String, jsonBody: String): HttpResponse<String> {
        requireSameHost(url)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(ZSESSION_HEADER, apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val response = executeWithRetry(request, HttpResponse.BodyHandlers.ofByteArray())
        return DecodedResponse(
            response,
            decodeBody(response.body(), response.headers().firstValue("Content-Encoding").orElse(null))
        )
    }
```

Retry semantics unchanged: `executeWithRetry` inspects status codes only; decode happens after it returns. `downloadAttachment`'s binary path is NOT touched.

- [ ] **Step 6: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`, all tests (existing + 4 new) pass.

```bash
git add -A && git commit -m "perf(api): request and decode gzip for all JSON responses"
```

---

### Task 12: Deduplicate inline-image downloads in export (A4)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/export/RallyExporter.kt:94-95` (fields), `downloadRallyImage` (603-620)

- [ ] **Step 1: Add the dedup map next to `downloadedPaths`**

```kotlin
    /** Per-session cache for downloaded attachment paths (deduplicates across JSON+Markdown export). */
    private val downloadedPaths = ConcurrentHashMap<String, String>()

    /** Per-session cache for downloaded inline-image paths — same dedup role as
     *  [downloadedPaths]: the JSON and Markdown passes generate identical image jobs. */
    private val downloadedImagePaths = ConcurrentHashMap<String, String>()
```

- [ ] **Step 2: Consult it in `downloadRallyImage`**

```kotlin
    private fun downloadRallyImage(objectId: String, originalFileName: String, localFileName: String, imgDir: String): String? {
        val cacheKey = "$objectId:$imgDir:$localFileName"
        downloadedImagePaths[cacheKey]?.let { return it }
        try {
            val imgDirPath = Paths.get(imgDir)
            Files.createDirectories(imgDirPath)

            val imageUrl = "${client.webBaseUrl}/slm/attachment/$objectId/$originalFileName"

            val fileBytes = client.downloadAttachment(imageUrl)

            val outputPath = RallyFileUtils.safeResolve(imgDirPath, localFileName)
            Files.write(outputPath, fileBytes)
            LOG.info("Downloaded inline image: $outputPath (${fileBytes.size} bytes)")
            val path = outputPath.toAbsolutePath().toString()
            downloadedImagePaths[cacheKey] = path
            return path
        } catch (e: Exception) {
            // Don't cache failures — allow retry on transient errors
            LOG.warn("Failed to download image OID=$objectId", e)
            return null
        }
    }
```

(Note `var outputPath` becomes `val` — there was no reassignment in this method.)

- [ ] **Step 3: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "perf(export): deduplicate inline-image downloads across JSON and Markdown passes"
```

---

### Task 13: Stream JSON writes (A13a)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/export/RallyExporter.kt:128, 229, 392`

- [ ] **Step 1: Replace all three `writeText(gson.toJson(...))` sites**

Each of the three occurrences of:

```kotlin
        file.writeText(gson.toJson(output), StandardCharsets.UTF_8)
```

becomes:

```kotlin
        // Stream straight to the file: gson.toJson(JsonElement, Appendable) emits
        // identical output without first materializing the whole document as a String.
        file.bufferedWriter(StandardCharsets.UTF_8).use { gson.toJson(output, it) }
```

(Sites: `exportTestCaseJson` line 128, `bulkExportJson` line 229, `exportArtifactJson` line 392. Put the comment on the first site only; the other two just get the one-line change.)

- [ ] **Step 2: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL` (the format tests in `RallyExporterFormatTest` must stay green — they exercise companion helpers, not file writes).

```bash
git add -A && git commit -m "perf(export): stream JSON output instead of materializing full strings"
```

---

### Task 14: Raw-bytes attachment download with base64 fallback (A13b)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/export/RallyExporter.kt:498-541` (`downloadAttachmentContent`)

- [ ] **Step 1: Prefer the raw endpoint, fall back to base64**

Replace the method:

```kotlin
    private fun downloadAttachmentContent(
        attachment: RallyAttachment,
        attachDir: String,
        fileName: String
    ): String? {
        val objectId = attachment.objectID
        val contentRef = attachment.content?.ref
        if (objectId == null && contentRef == null) return null
        val safeFileName = RallyFileUtils.sanitizeFileName(fileName)
        val cacheKey = "${objectId ?: contentRef}:$attachDir:$safeFileName"

        // Check per-session dedup cache (avoids re-downloading for JSON+Markdown exports)
        downloadedPaths[cacheKey]?.let { return it }

        return try {
            val attachDirPath = Paths.get(attachDir)
            Files.createDirectories(attachDirPath)

            // Prefer the raw-bytes endpoint (the same one inline images use): no JSON
            // DOM, no base64 decode, ~25% less transfer, ~1x peak heap instead of 3-4x.
            // Fall back to the base64 Content ref if the raw download fails or there
            // is no ObjectID.
            val fileBytes = (if (objectId != null) {
                try {
                    val encodedName = java.net.URLEncoder
                        .encode(attachment.name ?: safeFileName, StandardCharsets.UTF_8)
                        .replace("+", "%20")
                    client.downloadAttachment("${client.webBaseUrl}/slm/attachment/$objectId/$encodedName")
                } catch (e: Exception) {
                    LOG.warn("Raw download failed for '$fileName'; falling back to base64 content", e)
                    null
                }
            } else null) ?: run {
                val ref = contentRef ?: return null
                val base64Content = client.getAttachmentContent(ref)
                // Rally returns MIME-encoded base64 with line breaks every 76 chars; the strict
                // decoder throws IllegalArgumentException on real attachments, so use MIME decoder.
                Base64.getMimeDecoder().decode(base64Content)
            }

            // safeResolve handles sanitization + containment. Dedup on existing names.
            var outputPath = RallyFileUtils.safeResolve(attachDirPath, safeFileName)
            if (Files.exists(outputPath)) {
                val baseName = safeFileName.substringBeforeLast(".", safeFileName)
                val ext = if (safeFileName.contains(".")) ".${safeFileName.substringAfterLast(".")}" else ""
                var counter = 1
                while (Files.exists(outputPath)) {
                    outputPath = RallyFileUtils.safeResolve(attachDirPath, "${baseName}_$counter$ext")
                    counter++
                }
            }

            Files.write(outputPath, fileBytes)
            LOG.info("Saved attachment: $outputPath (${fileBytes.size} bytes)")
            val path = outputPath.toAbsolutePath().toString()
            downloadedPaths[cacheKey] = path
            path
        } catch (e: Exception) {
            LOG.warn("Failed to download attachment '$fileName'", e)
            // Don't cache failures — allow retry on transient errors
            null
        }
    }
```

Key safety properties to preserve: filename URL-encoding (attachment names can contain spaces; `URI.create` rejects them), the in-method fallback chain (raw → base64 → null), no caching of failures.

- [ ] **Step 2: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.
Live verification happens in Task 18's runIde checklist (export an artifact with attachments; confirm files open correctly).

```bash
git add -A && git commit -m "perf(export): download attachments via raw-bytes endpoint with base64 fallback"
```

---

### Task 15: Centralized state colors — `StateColors` + `RallyColors.forState` (B1) — TDD

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyColors.kt` (full rewrite below)
- Create: `src/test/kotlin/com/github/halmuratuyghur/rally/ui/RallyColorsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.github.halmuratuyghur.rally.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class RallyColorsTest {

    @Test
    fun `schedule states map to their existing foreground constants`() {
        assertSame(RallyColors.IN_PROGRESS, RallyColors.forState("In-Progress").foreground)
        assertSame(RallyColors.COMPLETED, RallyColors.forState("Completed").foreground)
        assertSame(RallyColors.DEFINED, RallyColors.forState("Defined").foreground)
        assertSame(RallyColors.ACCEPTED, RallyColors.forState("Accepted").foreground)
        assertSame(RallyColors.IDEA, RallyColors.forState("Idea").foreground)
    }

    @Test
    fun `verdicts map to pass and fail`() {
        assertSame(RallyColors.PASS, RallyColors.forState("Pass").foreground)
        assertSame(RallyColors.FAIL, RallyColors.forState("Fail").foreground)
    }

    @Test
    fun `defect lifecycle states share the matching schedule-stage colors`() {
        assertSame(RallyColors.forState("Defined"), RallyColors.forState("Submitted"))
        assertSame(RallyColors.forState("Fail"), RallyColors.forState("Open"))
        assertSame(RallyColors.forState("Completed"), RallyColors.forState("Fixed"))
        assertSame(RallyColors.forState("Accepted"), RallyColors.forState("Closed"))
    }

    @Test
    fun `null and unknown states fall back to neutral`() {
        assertSame(RallyColors.NEUTRAL, RallyColors.forState(null))
        assertSame(RallyColors.NEUTRAL, RallyColors.forState("Unknown"))
        assertSame(RallyColors.NEUTRAL, RallyColors.forState("No Verdict"))
        assertSame(RallyColors.NEUTRAL, RallyColors.forState("SomethingElse"))
    }

    @Test
    fun `method colors keep their existing semantics`() {
        assertSame(RallyColors.IN_PROGRESS, RallyColors.forMethod("Automated").foreground)
        assertSame(RallyColors.DEFINED, RallyColors.forMethod("Manual").foreground)
        assertSame(RallyColors.DEFINED, RallyColors.forMethod(null).foreground)
    }

    @Test
    fun `chip backgrounds are translucent tints, not the foreground color`() {
        val chip = RallyColors.forState("In-Progress")
        assertFalse(chip.foreground === chip.background)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests "*RallyColorsTest*"`
Expected: FAIL — unresolved references (`forState`, `ACCEPTED`, `IDEA`, `NEUTRAL`, `forMethod`).

- [ ] **Step 3: Rewrite `RallyColors.kt`**

```kotlin
package com.github.halmuratuyghur.rally.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Foreground + chip-fill pair for one state value. The background is a translucent
 * tint of the same hue so the chip reads in both light and dark themes while the
 * full-strength foreground keeps text contrast.
 */
class StateColors(val foreground: JBColor, val background: JBColor)

/**
 * Shared color constants for Rally UI components.
 *
 * Hues are picked so each state is distinguishable on grayscale and for the most
 * common color-blindness types (red-green deuteranopia, protanopia). Color is
 * still supplementary — every renderer draws the state name as text — but the
 * earlier palette had IN_PROGRESS and PASS both at pure green, and PASS and
 * COMPLETED both at pure blue, which made the column unreadable at a glance.
 *
 * [forState] is the single state→color mapping; the per-renderer `when` switches
 * it replaced had already drifted apart (the detail header lacked Pass/Fail).
 */
object RallyColors {
    private val IN_PROGRESS_L = Color(0, 128, 0)
    private val IN_PROGRESS_D = Color(100, 200, 100)
    private val COMPLETED_L = Color(0, 0, 180)
    private val COMPLETED_D = Color(100, 150, 255)
    private val DEFINED_L = Color(200, 120, 0)
    private val DEFINED_D = Color(255, 180, 80)
    private val PASS_L = Color(0, 160, 200)
    private val PASS_D = Color(80, 200, 230)
    private val FAIL_L = Color(180, 0, 0)
    private val FAIL_D = Color(255, 100, 100)
    private val ACCEPTED_L = Color(110, 110, 110)
    private val ACCEPTED_D = Color(160, 160, 160)
    private val IDEA_L = Color(128, 60, 170)
    private val IDEA_D = Color(190, 140, 225)
    private val NEUTRAL_L = Color(90, 90, 90)
    private val NEUTRAL_D = Color(150, 150, 150)

    val IN_PROGRESS = JBColor(IN_PROGRESS_L, IN_PROGRESS_D)  // pure green
    val COMPLETED = JBColor(COMPLETED_L, COMPLETED_D)        // pure blue
    val DEFINED = JBColor(DEFINED_L, DEFINED_D)              // orange
    val PASS = JBColor(PASS_L, PASS_D)                       // cyan — distinct from both green and blue
    val FAIL = JBColor(FAIL_L, FAIL_D)                       // pure red
    val ACCEPTED = JBColor(ACCEPTED_L, ACCEPTED_D)           // gray (was inline JBColor.GRAY)
    val IDEA = JBColor(IDEA_L, IDEA_D)                       // purple (had no color before)
    val DIVIDER = JBColor(Color(80, 80, 80), Color(70, 70, 70))

    /** Translucent fill of the same hue: light theme tints lighter, dark theme a bit stronger. */
    private fun tint(light: Color, dark: Color) = JBColor(
        Color(light.red, light.green, light.blue, 34),
        Color(dark.red, dark.green, dark.blue, 46)
    )

    private val IN_PROGRESS_CHIP = StateColors(IN_PROGRESS, tint(IN_PROGRESS_L, IN_PROGRESS_D))
    private val COMPLETED_CHIP = StateColors(COMPLETED, tint(COMPLETED_L, COMPLETED_D))
    private val DEFINED_CHIP = StateColors(DEFINED, tint(DEFINED_L, DEFINED_D))
    private val PASS_CHIP = StateColors(PASS, tint(PASS_L, PASS_D))
    private val FAIL_CHIP = StateColors(FAIL, tint(FAIL_L, FAIL_D))
    private val ACCEPTED_CHIP = StateColors(ACCEPTED, tint(ACCEPTED_L, ACCEPTED_D))
    private val IDEA_CHIP = StateColors(IDEA, tint(IDEA_L, IDEA_D))

    /** Fallback chip for null/unknown states ("Unknown", "No Verdict", blank). */
    val NEUTRAL = StateColors(JBColor(NEUTRAL_L, NEUTRAL_D), tint(NEUTRAL_L, NEUTRAL_D))

    private val STATE_COLORS: Map<String, StateColors> = mapOf(
        // ScheduleState (user stories + defects)
        "Idea" to IDEA_CHIP,
        "Defined" to DEFINED_CHIP,
        "In-Progress" to IN_PROGRESS_CHIP,
        "Completed" to COMPLETED_CHIP,
        "Accepted" to ACCEPTED_CHIP,
        // Defect State — mapped to the matching lifecycle-stage hue
        "Submitted" to DEFINED_CHIP,
        "Open" to FAIL_CHIP,
        "Fixed" to COMPLETED_CHIP,
        "Closed" to ACCEPTED_CHIP,
        // Test case verdicts
        "Pass" to PASS_CHIP,
        "Fail" to FAIL_CHIP,
    )

    /** Badge colors for a state/verdict value; unknown or null falls back to [NEUTRAL]. */
    fun forState(state: String?): StateColors = state?.let { STATE_COLORS[it] } ?: NEUTRAL

    /** Test-case Method badge colors (existing semantics: Automated=green, Manual=orange). */
    fun forMethod(method: String?): StateColors =
        if (method == "Automated") IN_PROGRESS_CHIP else DEFINED_CHIP
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*RallyColorsTest*"` → PASS. Then `./gradlew test` → all green (public constants `IN_PROGRESS`/`COMPLETED`/`DEFINED`/`PASS`/`FAIL`/`DIVIDER` are unchanged in type and value, so existing usages compile).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(ui): add StateColors chips and centralized RallyColors.forState lookup"
```

---

### Task 16: `StatusBadge` component (B2)

**Files:**
- Create: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/StatusBadge.kt`

- [ ] **Step 1: Create the component**

```kotlin
package com.github.halmuratuyghur.rally.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * A small rounded chip that paints a tinted fill behind a state name.
 *
 * Designed for reuse inside shared cell-renderer instances (project convention:
 * no per-paint allocations), so all visual state flows through [update]. The chip
 * keeps its own colors on selected rows — the tinted fill provides local contrast
 * against the selection highlight, unlike the colored-text-on-selection-blue
 * clash the plain labels had.
 */
class StatusBadge : JComponent() {
    private var text: String = ""
    private var colors: StateColors = RallyColors.NEUTRAL

    init {
        font = JBUI.Fonts.label(11f)
        isOpaque = false
    }

    /** Set text + colors in one call; blank text hides the badge entirely. */
    fun update(text: String?, colors: StateColors) {
        this.text = text ?: ""
        this.colors = colors
        isVisible = this.text.isNotBlank()
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        if (text.isEmpty()) return Dimension(0, 0)
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(16), fm.height + JBUI.scale(4))
    }

    override fun paintComponent(g: Graphics) {
        if (text.isEmpty()) return
        val g2 = g as Graphics2D
        val oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(8)
        g2.color = colors.background
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        g2.color = colors.foreground
        g2.font = font
        val fm = g2.fontMetrics
        g2.drawString(
            text,
            (width - fm.stringWidth(text)) / 2,
            (height - fm.height) / 2 + fm.ascent
        )
        if (oldAA != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA)
    }
}
```

Notes: `revalidate()`/`repaint()` are no-ops inside a CellRendererPane (harmless) but required for the live detail-header instance. No `Color`/`Font`/`Insets` allocation in `paintComponent`.

- [ ] **Step 2: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL` (component is exercised visually in Task 18).

```bash
git add -A && git commit -m "feat(ui): add StatusBadge tinted-chip component"
```

---

### Task 17: Apply badges at all four render sites (B3)

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt` (`ArtifactCellRenderer`, ~1900-1970)
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyDetailPanel.kt` (header `stateBadge` 78/171/313-319/534, `stateColor()` 951-957, `TaskCellRenderer` 1114-1166, `TestCaseCellRenderer` 1061-1110)

- [ ] **Step 1: Ticket list renderer**

In `ArtifactCellRenderer`, replace the field:
```kotlin
        private val stateLabel = JLabel()
```
with:
```kotlin
        private val stateBadge = StatusBadge()
```
and in `init`, `rightPanel.add(stateLabel)` → `rightPanel.add(stateBadge)`.

Replace the state rendering block (lines ~1952-1961):

```kotlin
            stateLabel.text = state
            stateLabel.foreground = if (isSelected) list.selectionForeground else when (state) {
                "Pass" -> RallyColors.PASS
                "Fail" -> RallyColors.FAIL
                "In-Progress" -> RallyColors.IN_PROGRESS
                "Completed" -> RallyColors.COMPLETED
                "Accepted" -> JBColor.GRAY
                "Defined" -> RallyColors.DEFINED
                else -> JBColor.DARK_GRAY
            }
```

with:

```kotlin
            // The badge keeps its own colors on selected rows: the tinted fill is its
            // local background, so it stays readable on the selection highlight.
            stateBadge.update(state, RallyColors.forState(state))
```

(The `val state = ...` derivation above it is unchanged.)

- [ ] **Step 2: Detail panel header**

In `RallyDetailPanel`, change the field (line 78):
```kotlin
    private val stateBadge = JBLabel()
```
to:
```kotlin
    private val stateBadge = StatusBadge()
```

In `setupUI()`, DELETE the line (the chip has built-in padding; an outer empty border would be painted over by the fill):
```kotlin
        stateBadge.border = JBUI.Borders.empty(2, 8)
```

In `showArtifact()` (lines ~318-319), replace:
```kotlin
        stateBadge.text = state
        stateBadge.foreground = stateColor(state)
```
with:
```kotlin
        stateBadge.update(state, RallyColors.forState(state))
```
(Side effect: Pass/Fail test-case verdicts in the header get correct colors — the old `stateColor()` lacked those branches.)

In `clear()` (line ~534), replace:
```kotlin
        stateBadge.text = ""
```
with:
```kotlin
        stateBadge.update(null, RallyColors.NEUTRAL)
```

DELETE the now-unused `stateColor()` helper (lines ~951-957).

- [ ] **Step 3: Tasks tab renderer**

In `TaskCellRenderer`, replace the field `private val stateLabel = JLabel()` with `private val stateBadge = StatusBadge()`, fix `rightPanel.add(stateLabel)` → `rightPanel.add(stateBadge)`, and replace:

```kotlin
            val state = value.state ?: ""
            stateLabel.isVisible = state.isNotBlank()
            stateLabel.text = state
            stateLabel.foreground = if (isSelected) list.selectionForeground else when (state) {
                "In-Progress" -> RallyColors.IN_PROGRESS
                "Completed" -> RallyColors.COMPLETED
                "Defined" -> RallyColors.DEFINED
                else -> JBColor.DARK_GRAY
            }
```

with:

```kotlin
            // update() hides the badge when state is blank (same as the old isVisible).
            stateBadge.update(value.state, RallyColors.forState(value.state))
```

- [ ] **Step 4: Test Cases tab renderer**

In `TestCaseCellRenderer`, replace the fields:
```kotlin
        private val methodLabel = JLabel()
        private val verdictLabel = JLabel()
```
with:
```kotlin
        private val methodBadge = StatusBadge()
        private val verdictBadge = StatusBadge()
```
fix the `init` adds (`verdictLabel` → `verdictBadge`, `methodLabel` → `methodBadge`), and replace:

```kotlin
            val method = value.method ?: "Manual"
            methodLabel.text = method
            methodLabel.foreground = if (isSelected) list.selectionForeground else if (method == "Automated") {
                RallyColors.IN_PROGRESS
            } else {
                RallyColors.DEFINED
            }

            val verdict = value.lastVerdict ?: ""
            verdictLabel.isVisible = verdict.isNotBlank()
            verdictLabel.text = verdict
            verdictLabel.foreground = if (isSelected) list.selectionForeground else when (verdict) {
                "Pass" -> RallyColors.PASS
                "Fail" -> RallyColors.FAIL
                else -> JBColor.GRAY
            }
```

with:

```kotlin
            val method = value.method ?: "Manual"
            methodBadge.update(method, RallyColors.forMethod(method))

            // update() hides the badge for blank verdicts; non-Pass/Fail verdicts
            // (e.g. Blocked) get the neutral chip, matching the old gray fallback.
            verdictBadge.update(value.lastVerdict, RallyColors.forState(value.lastVerdict))
```

- [ ] **Step 5: Clean up imports**

In both files, remove now-unused imports if the compiler flags them (`JBColor` may still be used elsewhere in each file — only remove if actually unused). `RallyToolWindowPanel.kt` and `RallyDetailPanel.kt` are in the same package as `StatusBadge`/`RallyColors`/`StateColors` — no new imports needed.

- [ ] **Step 6: Verify + commit**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "feat(ui): render ticket status as tinted chips in list, header, and detail tabs"
```

---

### Task 18: Final verification + docs

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Full build**

Run: `./gradlew clean build verifyPlugin`
Expected: `BUILD SUCCESSFUL`; verifyPlugin reports no structure errors.

- [ ] **Step 2: Manual runIde checklist**

Run: `./gradlew runIde` and verify against a configured Rally workspace (skip gracefully if no live workspace is available, but note which items were verified):

1. Ticket list shows tinted chips; rows uniform height; chips readable on selected rows in BOTH light and dark theme (Settings → Appearance).
2. Detail header shows the chip; clears when deselecting; a Test Case shows Pass/Fail colored verdict in the header.
3. Tasks tab + Test Cases tab show chips (Method + Verdict).
4. State change (toolbar In-Progress/Completed/Defined) updates the row WITHOUT clearing selection or collapsing the detail panel; multi-select state change keeps all rows selected.
5. Edit Points updates without the list flickering or selection jumping.
6. Cold load: sprint dropdown and ticket list populate; switch projects; pick a specific sprint and refresh (validates the sequential-validation branch).
7. Rapid ticket switching with image-heavy descriptions stays responsive; descriptions render with images.
8. Export an artifact with attachments + inline images to JSON+Markdown: images appear ONCE in `<ID>_images/`, attachments open correctly (validates Task 14's raw download against the live API — if attachments come out corrupted, revert Task 14's commit and keep the base64 path).
9. Create Task, then immediately click another ticket: the new task must NOT appear in the other ticket's Tasks tab.

- [ ] **Step 3: Update CLAUDE.md**

Add rows to the Performance Optimizations table:

```markdown
| **gzip transport** | `Accept-Encoding: gzip` + transparent decode for all JSON responses | 5-10x smaller payloads on cold loads |
| **Parallel iterations** | Sprint list loads concurrently with artifacts when no saved sprint needs validation | Removes a serial RTT from cold start/project switch |
| **In-place row patching** | `patchArtifactsInModel` replaces full list rebuilds after optimistic updates | Selection preserved, single-cell repaint |
| **Fixed cell height** | Prototype-measured `fixedCellHeight` on the ticket list | O(1) instead of O(n) layout per model event |
| **Off-EDT HTML wrap** | `wrapHtml` + description work run on pooled thread, not apiExecutor/EDT | EDT stays responsive on multi-MB descriptions |
| **Export download dedup** | Inline images + attachments deduplicated across JSON+Markdown passes; raw-bytes attachment endpoint | Halves export downloads, ~1x peak heap |
```

In "What's Implemented", extend the relevant bullets: status shown as tinted color chips (`StatusBadge` + `RallyColors.forState()`) in ticket list, detail header, Tasks tab, and Test Cases tab. In the Architecture tree, add `│   ├── StatusBadge.kt             # Tinted-chip status badge component` under `ui/`. Update the `RallyColors.kt` line to mention `forState()`/`StateColors`.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "docs: record performance and status-badge changes in CLAUDE.md"
```

---

## Verification (end-to-end)

- `./gradlew clean build verifyPlugin` — full compile, all unit tests (10 existing + ~10 new), plugin structure.
- `./gradlew runIde` — the 9-point manual checklist in Task 18.
- Spec traceability: A9→T1, A10→T2, A11→T3, A14→T4, A8→T5, A12→T6, A5→T7, A6→T8, A3+A7→T9, A2→T10, A1→T11, A4→T12, A13a→T13, A13b→T14, B1→T15, B2→T16, B3→T17, B4+docs→T18.
