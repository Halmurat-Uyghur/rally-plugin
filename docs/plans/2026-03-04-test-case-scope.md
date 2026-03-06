# Test Case Scope Filter — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add "Test Cases" as a first-class scope option so users can browse and search test cases the same way they browse user stories and defects.

**Architecture:** Make `RallyTestCase` implement `RallyArtifact`, add a `queryAllTestCases()` API method, wire the "Test Cases" scope into `loadTickets()`, and add TC-specific rendering/detail handling.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Rally WSAPI 2.0

---

### Task 1: Make RallyTestCase implement RallyArtifact

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiModels.kt:300-336`

**Step 1: Add RallyArtifact interface fields to RallyTestCase**

Change the `RallyTestCase` data class to implement `RallyArtifact`. Add the missing interface fields and convert existing fields to `override`. The `description` and `owner` fields already exist on `RallyTestCase` — just add `override`. Add `creationDate`, `lastUpdateDate`, `scheduleState` (null), rename `type` → `artifactType` to avoid clash with `_type`:

```kotlin
data class RallyTestCase(
    @SerializedName("_ref")
    override val ref: String? = null,

    @SerializedName("ObjectID")
    override val objectID: String? = null,

    @SerializedName("FormattedID")
    override val formattedID: String? = null,

    @SerializedName("Name")
    override val name: String? = null,

    @SerializedName("Description")
    override val description: String? = null,

    @SerializedName("CreationDate")
    override val creationDate: String? = null,

    @SerializedName("LastUpdateDate")
    override val lastUpdateDate: String? = null,

    @SerializedName("Owner")
    override val owner: RallyUser? = null,

    override val scheduleState: String? = null,

    @SerializedName("State")
    override val state: String? = null,

    @SerializedName("_type")
    override val type: String? = "TestCase",

    @SerializedName("Method")
    val method: String? = null,

    @SerializedName("LastVerdict")
    val lastVerdict: String? = null,

    @SerializedName("LastRun")
    val lastRun: String? = null,

    @SerializedName("WorkProduct")
    val workProduct: RallyRef? = null,

    @SerializedName("Priority")
    val priority: String? = null,

    @SerializedName("Project")
    val project: RallyRef? = null
) : RallyArtifact
```

Key changes from current:
- Added `override` to `ref`, `objectID`, `formattedID`, `name`, `description`, `owner`
- Added new fields: `creationDate`, `lastUpdateDate`, `scheduleState` (always null), `state`, `type` (renamed from TC-specific `type` to use `_type` with default "TestCase")
- Added `project` field (needed for building browser URLs)
- Removed the old non-override `type` field (was `@SerializedName("Type")` for Rally TC type like "Acceptance") — this is now `val tcType` or can be dropped since it collides with `_type`. Rename old `Type` field to `testType`:

```kotlin
    @SerializedName("Type")
    val testType: String? = null,
```

**Step 2: Build to verify compilation**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. Any compile errors will be from places that reference `RallyTestCase.type` (previously the Rally "Type" field) — these need updating to `testType`.

**Step 3: Fix any references to old `type` field**

Search for `\.type` usages on `RallyTestCase` instances and update to `testType` if they referred to the Rally Type field (Acceptance/Functional/etc.), not the `_type` field.

**Step 4: Commit**

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiModels.kt
git commit -m "refactor(model): make RallyTestCase implement RallyArtifact"
```

---

### Task 2: Add queryAllTestCases() to RallyApiClient

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt`

**Step 1: Add TC list fields constant**

After `DETAIL_FIELDS` (~line 164), add lightweight fields for test case list queries:

```kotlin
private val TC_LIST_FIELDS = listOf(
    "FormattedID",
    "Name",
    "ObjectID",
    "CreationDate",
    "LastUpdateDate",
    "Owner",
    "Method",
    "LastVerdict",
    "LastRun",
    "State",
    "WorkProduct",
    "Project",
    "Priority"
)
```

**Step 2: Add queryAllTestCases() method**

After `searchArtifacts()` (~line 541), add:

```kotlin
/**
 * Query all test cases in the current workspace/project.
 * Used when scope is "Test Cases".
 */
fun queryAllTestCases(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE, maxResults: Int = MAX_PAGE_SIZE): List<RallyTestCase> {
    val cacheKey = "alltestcases:${query}|${pageSize}|${maxResults}|${workspaceRef}|${projectRef}"
    getCached<List<RallyTestCase>>(cacheKey)?.let { return it }

    val type = object : TypeToken<RallyQueryResult<RallyTestCase>>() {}.type
    val results: List<RallyTestCase> = queryAllPages(
        "testcase", type, query, pageSize, maxResults,
        order = "LastUpdateDate DESC",
        fields = TC_LIST_FIELDS
    )
    putCache(cacheKey, results)
    return results
}
```

**Step 3: Update searchArtifacts() to support "Test Cases" scope**

In `searchArtifacts()`, add a branch for test case scope. When scope is "Test Cases", query testcase endpoint instead of stories/defects:

```kotlin
fun searchArtifacts(searchText: String, scope: String? = null): List<RallyArtifact> {
    val cacheKey = "search:${searchText}|${scope}|${workspaceRef}|${projectRef}"
    getCached<List<RallyArtifact>>(cacheKey)?.let { return it }

    val query = "((Name contains \"$searchText\") OR (FormattedID contains \"$searchText\"))"

    val results = mutableListOf<RallyArtifact>()

    if (scope == "Test Cases") {
        // Search test cases only
        try { results.addAll(queryAllTestCases(query)) } catch (_: Exception) {}
    } else {
        val fetchStories = scope != "Defects"
        val fetchDefects = scope != "User Stories"

        val storiesFuture = if (fetchStories) {
            java.util.concurrent.CompletableFuture.supplyAsync({
                queryUserStories(query)
            }, apiExecutor)
        } else null
        val defectsFuture = if (fetchDefects) {
            java.util.concurrent.CompletableFuture.supplyAsync({
                queryDefects(query)
            }, apiExecutor)
        } else null

        storiesFuture?.let { try { results.addAll(it.get()) } catch (_: Exception) {} }
        defectsFuture?.let { try { results.addAll(it.get()) } catch (_: Exception) {} }
    }

    val sorted = results.sortedByDescending { it.lastUpdateDate }
    putCache(cacheKey, sorted)
    return sorted
}
```

**Step 4: Update clearArtifactCache() to include TC cache key prefix**

In `clearArtifactCache()`, add `"alltestcases:"` to the prefixes that get cleared:

```kotlin
if (key.startsWith("artifacts:") || key.startsWith("stories:") ||
    key.startsWith("defects:") || key.startsWith("tasks:") ||
    key.startsWith("alltestcases:") ||
    key.startsWith("sprint:") || key.startsWith("currentIteration:")) {
```

**Step 5: Build and commit**

Run: `./gradlew clean build`

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt
git commit -m "feat(api): add queryAllTestCases and TC search support"
```

---

### Task 3: Wire "Test Cases" scope into RallyToolWindowPanel

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt`

**Step 1: Add "Test Cases" to SCOPE_OPTIONS**

In the companion object (~line 47), add "Test Cases" between "Defects" and "Recent Activity":

```kotlin
private val SCOPE_OPTIONS = arrayOf(
    "All Tickets",
    "My Tickets",
    "User Stories",
    "Defects",
    "Test Cases",
    "Recent Activity"
)
```

**Step 2: Handle "Test Cases" scope in loadTickets()**

In `loadTickets()` (~line 410), the current code calls `client.queryAllArtifacts()`. Add a branch for "Test Cases" scope that calls `queryAllTestCases()` instead:

Replace the artifact loading block:
```kotlin
val artifactsFuture = java.util.concurrent.CompletableFuture.supplyAsync({
    client.queryAllArtifacts(query, pageSize, scope = scope, maxResults = pageSize)
}, client.apiExecutor)
```

With:
```kotlin
val artifactsFuture = java.util.concurrent.CompletableFuture.supplyAsync({
    if (scope == "Test Cases") {
        client.queryAllTestCases(query, pageSize, maxResults = pageSize)
    } else {
        client.queryAllArtifacts(query, pageSize, scope = scope, maxResults = pageSize)
    }
}, client.apiExecutor)
```

**Step 3: Handle "Test Cases" in applyClientFilter()**

In `applyClientFilter()` (~line 608), add a branch for "Test Cases":

```kotlin
val scopeFiltered = when (scope) {
    "User Stories" -> artifacts.filter { it.type == "HierarchicalRequirement" }
    "Defects" -> artifacts.filter { it.type == "Defect" }
    "Test Cases" -> artifacts.filter { it.type == "TestCase" }
    else -> artifacts
}
```

**Step 4: Disable Start/Finish Working buttons for TCs**

In the selection listener (~line 298), after `detailPanel.showArtifact(selected, currentClient)`, add logic to disable start/finish buttons when a TC is selected:

```kotlin
artifactList.addListSelectionListener {
    if (!it.valueIsAdjusting) {
        val selected = artifactList.selectedValue
        detailPanel.showArtifact(selected, currentClient)

        // Disable Start/Finish Working for test cases
        val isTc = selected is RallyTestCase
        val session = RallyWorkSession.getInstance(project)
        startWorkingButton.isEnabled = !isTc && !session.isActive
        finishWorkingButton.isEnabled = !isTc && session.isActive

        val sp = mainSplitPane ?: return@addListSelectionListener
        // ... existing auto-expand/collapse logic unchanged
    }
}
```

Note: add `import com.github.halmuratuyghur.rally.api.RallyTestCase` at the top of the file.

**Step 5: Adjust context menu for TCs**

In `showContextMenu()` (~line 339), skip state-change and export actions for test cases:

```kotlin
private fun showContextMenu(e: MouseEvent) {
    val index = artifactList.locationToIndex(e.point)
    if (index < 0) return
    if (!artifactList.isSelectedIndex(index)) {
        artifactList.selectedIndex = index
    }
    val selected = artifactList.selectedValue

    val menu = JPopupMenu()
    menu.add(JMenuItem("Open in Browser").apply { addActionListener { openInBrowser() } })
    menu.add(JMenuItem("Copy FormattedID").apply { addActionListener { copyFormattedId() } })

    if (selected !is RallyTestCase) {
        menu.addSeparator()
        menu.add(JMenuItem("Set In-Progress").apply { addActionListener { changeState("In-Progress") } })
        menu.add(JMenuItem("Set Completed").apply { addActionListener { changeState("Completed") } })
        menu.add(JMenuItem("Set Defined").apply { addActionListener { changeState("Defined") } })
        menu.addSeparator()
        menu.add(JMenuItem("Export to JSON/Markdown").apply { addActionListener { exportSelectedArtifact() } })
    }

    menu.show(artifactList, e.x, e.y)
}
```

**Step 6: Build and commit**

Run: `./gradlew clean build`

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt
git commit -m "feat(ui): wire Test Cases scope into tool window"
```

---

### Task 4: Update cell renderer for test cases

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt:1419-1468`

**Step 1: Add TC-specific icon and state badge in ArtifactCellRenderer**

In the `getListCellRendererComponent` method, update the icon and state sections:

```kotlin
iconLabel.icon = when (value.type) {
    "HierarchicalRequirement" -> AllIcons.Nodes.PpLib
    "Defect" -> AllIcons.General.Error
    "TestCase" -> AllIcons.RunConfigurations.TestState.Run
    else -> AllIcons.FileTypes.Any_type
}

textLabel.text = "${value.formattedID ?: "?"}: ${value.name ?: "Untitled"}"
textLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

val state = if (value is RallyTestCase) {
    value.lastVerdict ?: "No Verdict"
} else {
    value.scheduleState ?: value.state ?: "Unknown"
}
stateLabel.text = state
stateLabel.foreground = if (isSelected) list.selectionForeground else when (state) {
    "Pass" -> JBColor(Color(0, 128, 0), Color(100, 200, 100))
    "Fail" -> JBColor(Color(180, 0, 0), Color(255, 100, 100))
    "In-Progress" -> JBColor(Color(0, 128, 0), Color(100, 200, 100))
    "Completed" -> JBColor(Color(0, 0, 180), Color(100, 150, 255))
    "Accepted" -> JBColor.GRAY
    "Defined" -> JBColor(Color(200, 120, 0), Color(255, 180, 80))
    else -> JBColor.DARK_GRAY
}
```

Note: add `import com.github.halmuratuyghur.rally.api.RallyTestCase` if not already added in Task 3.

**Step 2: Build and commit**

Run: `./gradlew clean build`

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt
git commit -m "feat(ui): add test case icon and verdict badge to cell renderer"
```

---

### Task 5: Update detail panel for test case display

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyDetailPanel.kt:220-350`

**Step 1: Add TC-specific detail handling in showArtifact()**

When the selected artifact is a `RallyTestCase`, the detail panel should:
- Show description (top) — same as stories/defects
- Show test steps in the tabbed pane (bottom) — reuse existing `loadTestSteps()` logic
- Hide the Test Cases / Tasks / Attachments tabs (replace with a single "Test Steps" view)

In `showArtifact()`, after the description loading setup (~line 250), add a branch:

```kotlin
if (artifact is RallyTestCase) {
    // For test cases: show description + test steps only
    tabbedPane.removeAll()
    tabbedPane.addTab("Test Steps", JBScrollPane(stepList))
    stepListModel.clear()

    // Load description and test steps in parallel
    ApplicationManager.getApplication().executeOnPooledThread {
        val descFuture = CompletableFuture.supplyAsync({
            var resolved = desc
            if (resolved.isNullOrBlank()) {
                resolved = try { client.fetchDescription(artifactRef) } catch (e: Exception) { null }
            }
            if (!resolved.isNullOrBlank()) {
                try { resolveInlineImages(resolved, client) } catch (_: Exception) { resolved }
            } else null
        }, client.apiExecutor)

        val stepsFuture = CompletableFuture.supplyAsync({
            try { client.queryTestSteps(id) } catch (e: Exception) {
                LOG.warn("Failed to load test steps for $id", e)
                null
            }
        }, client.apiExecutor)

        descFuture.thenAccept { resolvedDesc ->
            ApplicationManager.getApplication().invokeLater {
                if (currentArtifactRef != artifactRef) return@invokeLater
                descriptionPane.text = wrapHtml(resolvedDesc ?: "<i>No description</i>")
                descriptionPane.caretPosition = 0
            }
        }

        stepsFuture.thenAccept { steps ->
            ApplicationManager.getApplication().invokeLater {
                if (currentArtifactRef != artifactRef) return@invokeLater
                stepListModel.clear()
                if (steps != null) {
                    steps.forEach { stepListModel.addElement(it) }
                    tabbedPane.setTitleAt(0, "Test Steps (${steps.size})")
                } else {
                    tabbedPane.setTitleAt(0, "Test Steps (0)")
                }
            }
        }
    }
    return  // Skip the normal story/defect detail loading
}

// ... existing story/defect detail loading code continues below
```

Also update the state badge to show `lastVerdict` for TCs:

```kotlin
val state = if (artifact is RallyTestCase) {
    artifact.lastVerdict ?: "No Verdict"
} else {
    artifact.scheduleState ?: artifact.state ?: "Unknown"
}
stateBadge.text = state
stateBadge.foreground = stateColor(state)
```

Add `import com.github.halmuratuyghur.rally.api.RallyTestCase` at the top.

**Step 2: Ensure tabs are restored for non-TC artifacts**

After the `if (artifact is RallyTestCase)` block returns early, the normal code path already sets up the 3-tab layout. But we need to ensure the tabs are restored if a TC was shown before a story is selected. At the start of `showArtifact()`, before the TC branch, restore the standard tabs if they were replaced:

```kotlin
// Restore standard tabs if previously showing a test case
if (tabbedPane.tabCount != 3 || tabbedPane.getTitleAt(0) == "Test Steps") {
    tabbedPane.removeAll()
    tabbedPane.addTab("Test Cases", JBScrollPane(testCaseList))
    tabbedPane.addTab("Tasks", JBScrollPane(taskList))
    tabbedPane.addTab("Attachments", JBScrollPane(attachmentList))
}
```

**Step 3: Update buildWebUrl for TestCase type**

In `RallyApiClient.buildWebUrl()` (~line 550), add a branch for TestCase:

```kotlin
val detailPage = when (artifact.type) {
    "HierarchicalRequirement" -> "userstory"
    "Defect" -> "defect"
    "Task" -> "task"
    "TestCase" -> "testcase"
    else -> "detail"
}
```

And update the project OID extraction to handle `RallyTestCase`:

```kotlin
val projectOid = when (artifact) {
    is RallyUserStory -> artifact.project?.ref
    is RallyDefect -> artifact.project?.ref
    is RallyTestCase -> artifact.project?.ref
    else -> null
}?.trimEnd('/')?.substringAfterLast('/')
```

**Step 4: Build and commit**

Run: `./gradlew clean build`

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyDetailPanel.kt \
    src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt
git commit -m "feat(ui): show test steps in detail panel when TC is selected"
```

---

### Task 6: Final build verification

**Step 1: Full clean build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL

**Step 2: Commit any remaining changes and push**

```bash
git push
```
