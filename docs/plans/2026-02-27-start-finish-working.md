# Start Working / Finish Working — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add "Start Working" and "Finish Working" toolbar buttons that orchestrate git branching, Rally state changes, owner assignment, and commit message prefixing in a single click.

**Architecture:** Two toolbar buttons in `RallyToolWindowPanel` trigger a multi-step workflow. A project-level `RallyWorkSession` service tracks the active ticket. A `CheckinHandlerFactory` prepends `[FormattedID]` to commit messages. Git branching uses the `git4idea` bundled plugin API.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Git4Idea API, Rally WSAPI 2.0

---

### Task 1: Add git4idea dependency

**Files:**
- Modify: `build.gradle.kts:23`
- Modify: `src/main/resources/META-INF/plugin.xml:32`

**Step 1: Add git4idea to intellij.plugins in build.gradle.kts**

In `build.gradle.kts`, change the empty plugins list to include git4idea:

```kotlin
intellij {
    version.set("2024.1")
    type.set("IC")
    plugins.set(listOf("git4idea"))
    sandboxDir.set(layout.projectDirectory.dir(".sandbox").toString())
}
```

**Step 2: Add git4idea dependency to plugin.xml**

In `plugin.xml`, after the existing `<depends>com.intellij.modules.platform</depends>` line, add:

```xml
<depends>Git4Idea</depends>
```

**Step 3: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (git4idea classes now available on classpath)

**Step 4: Commit**

```bash
git add build.gradle.kts src/main/resources/META-INF/plugin.xml
git commit -m "build: add git4idea plugin dependency for VCS integration"
```

---

### Task 2: Create RallyWorkSession project service

**Files:**
- Create: `src/main/kotlin/com/github/halmuratuyghur/rally/vcs/RallyWorkSession.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Step 1: Create the RallyWorkSession service**

Create `src/main/kotlin/com/github/halmuratuyghur/rally/vcs/RallyWorkSession.kt`:

```kotlin
package com.github.halmuratuyghur.rally.vcs

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class RallyWorkSession {
    var activeTicketId: String? = null
    var activeTicketRef: String? = null
    var activeTicketType: String? = null
    var activeBranchName: String? = null

    val isActive: Boolean get() = activeTicketId != null

    fun start(ticketId: String, ticketRef: String, ticketType: String, branchName: String) {
        activeTicketId = ticketId
        activeTicketRef = ticketRef
        activeTicketType = ticketType
        activeBranchName = branchName
    }

    fun finish() {
        activeTicketId = null
        activeTicketRef = null
        activeTicketType = null
        activeBranchName = null
    }

    companion object {
        fun getInstance(project: Project): RallyWorkSession =
            project.getService(RallyWorkSession::class.java)
    }
}
```

**Step 2: Register as project service in plugin.xml**

In `plugin.xml`, inside the `<extensions defaultExtensionNs="com.intellij">` block, add:

```xml
<projectService
    serviceImplementation="com.github.halmuratuyghur.rally.vcs.RallyWorkSession"/>
```

**Step 3: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/vcs/RallyWorkSession.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat: add RallyWorkSession project service for tracking active ticket"
```

---

### Task 3: Add updateArtifactOwner method to RallyApiClient

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt:604` (after `updateArtifactState`)

**Step 1: Add the updateArtifactOwner method**

In `RallyApiClient.kt`, after the `updateArtifactState()` method (line ~604), add:

```kotlin
/**
 * Update the Owner of a Rally artifact.
 * @param artifactRef Full API URL ref of the artifact
 * @param artifactType Rally type name (e.g., "HierarchicalRequirement", "Defect")
 * @param ownerRef Full API URL ref of the user (e.g., "https://rally1.rallydev.com/slm/webservice/v2.0/user/12345")
 */
fun updateArtifactOwner(artifactRef: String, artifactType: String, ownerRef: String) {
    val body = """{"$artifactType":{"Owner":"$ownerRef"}}"""
    val response = executePost(artifactRef, body)
    handleResponse(response)

    val json = JsonParser.parseString(response.body()).asJsonObject
    val result = json.getAsJsonObject("OperationResult")
    if (result != null) {
        val errors = result.getAsJsonArray("Errors")
        if (errors != null && errors.size() > 0) {
            throw RallyApiException("Failed to update owner: ${errors.joinToString()}")
        }
    }
}
```

**Step 2: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt
git commit -m "feat(api): add updateArtifactOwner method for assigning ticket ownership"
```

---

### Task 4: Create RallyCheckinHandler for commit message prefixing

**Files:**
- Create: `src/main/kotlin/com/github/halmuratuyghur/rally/vcs/RallyCheckinHandler.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Step 1: Create the CheckinHandlerFactory**

Create `src/main/kotlin/com/github/halmuratuyghur/rally/vcs/RallyCheckinHandler.kt`:

```kotlin
package com.github.halmuratuyghur.rally.vcs

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory

class RallyCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return RallyCheckinHandler(panel)
    }
}

private class RallyCheckinHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    override fun getBeforeCheckinConfigurationPanel(): com.intellij.openapi.vcs.ui.RefreshableOnComponent? {
        val project = panel.project ?: return null
        val session = RallyWorkSession.getInstance(project)
        if (!session.isActive) return null

        val ticketId = session.activeTicketId ?: return null
        val prefix = "[$ticketId]"
        val currentMessage = panel.commitMessage

        // Prepend prefix if not already present
        if (!currentMessage.startsWith(prefix)) {
            panel.commitMessage = "$prefix $currentMessage"
        }

        return null // No additional UI panel needed
    }
}
```

**Step 2: Register the CheckinHandlerFactory in plugin.xml**

In `plugin.xml`, inside the `<extensions defaultExtensionNs="com.intellij">` block, add:

```xml
<checkinHandlerFactory
    implementation="com.github.halmuratuyghur.rally.vcs.RallyCheckinHandlerFactory"/>
```

**Step 3: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/vcs/RallyCheckinHandler.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat(vcs): add CheckinHandler to auto-prefix commit messages with ticket ID"
```

---

### Task 5: Add "Start Working" toolbar button and logic

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt`

This is the largest task. It adds the toolbar button, the `startWorking()` method, and the necessary imports.

**Step 1: Add imports**

At the top of `RallyToolWindowPanel.kt`, add these imports (after the existing import block):

```kotlin
import com.github.halmuratuyghur.rally.vcs.RallyWorkSession
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager
```

**Step 2: Add instance variables for the new buttons**

In `RallyToolWindowPanel`, after the `statusLabel` declaration (line ~72), add:

```kotlin
private val startWorkingButton = JButton("Start Working", AllIcons.Actions.Execute).apply { isFocusable = false }
private val finishWorkingButton = JButton("Finish Working", AllIcons.Actions.Suspend).apply {
    isFocusable = false
    isEnabled = false
}
```

**Step 3: Add buttons to the toolbar**

In `setupUI()`, after the Export button line (line ~129), before `toolbar.add(Box.createHorizontalGlue())`, add:

```kotlin
toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
toolbar.add(startWorkingButton)
toolbar.add(finishWorkingButton)
```

**Step 4: Wire button action listeners**

In `setupListeners()` (or at the end of `setupUI()` near the other listeners), add:

```kotlin
startWorkingButton.addActionListener { startWorking() }
finishWorkingButton.addActionListener { finishWorking() }
```

**Step 5: Add the startWorking() method**

After the `changeState()` method (line ~1085), add:

```kotlin
private fun startWorking() {
    val selected = artifactList.selectedValue
    if (selected == null) {
        Messages.showInfoMessage(project, "Select a ticket first.", "Rally")
        return
    }

    val ticketId = selected.formattedID
    val ticketRef = selected.ref
    val ticketType = selected.type
    if (ticketId == null || ticketRef == null || ticketType == null) {
        Messages.showErrorDialog(project, "Selected ticket is missing required data.", "Rally")
        return
    }

    val branchName = "feature/$ticketId"
    val settings = RallySettings.getInstance()
    val username = settings.username

    val confirm = Messages.showYesNoDialog(
        project,
        "Start working on $ticketId?\n\n" +
                "This will:\n" +
                "  \u2022 Create & checkout branch: $branchName\n" +
                "  \u2022 Move ticket to In-Progress\n" +
                "  \u2022 Assign you as owner\n" +
                "  \u2022 Prefix commit messages with [$ticketId]",
        "Rally - Start Working",
        Messages.getQuestionIcon()
    )
    if (confirm != Messages.YES) return

    statusLabel.text = "Starting work on $ticketId..."

    ApplicationManager.getApplication().executeOnPooledThread {
        val client = getClient()
        val errors = mutableListOf<String>()

        // 1. Create & checkout git branch
        try {
            val repoManager = GitRepositoryManager.getInstance(project)
            val repos = repoManager.repositories
            if (repos.isEmpty()) {
                errors.add("No Git repository found in this project")
            } else {
                val brancher = GitBrancher.getInstance(project)
                // Check if branch already exists
                val repo = repos.first()
                val existingBranches = repo.branches.localBranches.map { it.name }
                ApplicationManager.getApplication().invokeLater {
                    if (branchName in existingBranches) {
                        brancher.checkout(branchName, false, repos, null)
                    } else {
                        brancher.createBranch(branchName, mapOf(repo to "HEAD"))
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to create branch $branchName", e)
            errors.add("Branch creation failed: ${e.message}")
        }

        // 2. Move ticket to In-Progress
        try {
            client.updateArtifactState(ticketRef, ticketType, "In-Progress")
        } catch (e: Exception) {
            LOG.error("Failed to move $ticketId to In-Progress", e)
            errors.add("State change failed: ${e.message}")
        }

        // 3. Assign owner
        if (username.isNotBlank()) {
            try {
                val user = client.getUserByUsername(username)
                val userRef = user.ref
                if (userRef != null) {
                    client.updateArtifactOwner(ticketRef, ticketType, userRef)
                }
            } catch (e: Exception) {
                LOG.error("Failed to assign owner for $ticketId", e)
                errors.add("Owner assignment failed: ${e.message}")
            }
        }

        // 4. Activate work session
        val session = RallyWorkSession.getInstance(project)
        session.start(ticketId, ticketRef, ticketType, branchName)

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Optimistic update
            allArtifacts = allArtifacts.map { artifact ->
                if (artifact.ref == ticketRef) {
                    when (artifact) {
                        is RallyUserStory -> artifact.copy(scheduleState = "In-Progress")
                        is RallyDefect -> artifact.copy(scheduleState = "In-Progress")
                        else -> artifact
                    }
                } else artifact
            }
            client.clearArtifactCache()
            applySearchFilter()

            startWorkingButton.isEnabled = false
            finishWorkingButton.isEnabled = true

            if (errors.isNotEmpty()) {
                Messages.showWarningDialog(
                    project,
                    "Started working on $ticketId with issues:\n\n${errors.joinToString("\n")}",
                    "Rally - Start Working"
                )
            }
            statusLabel.text = "Working on $ticketId"
        }
    }
}
```

**Step 6: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt
git commit -m "feat(ui): add Start Working button with branch creation, state change, and owner assignment"
```

---

### Task 6: Add "Finish Working" logic and PR dialog

**Files:**
- Modify: `src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt`

**Step 1: Add the finishWorking() method**

After the `startWorking()` method, add:

```kotlin
private fun finishWorking() {
    val session = RallyWorkSession.getInstance(project)
    if (!session.isActive) {
        Messages.showInfoMessage(project, "No active work session.", "Rally")
        return
    }

    val ticketId = session.activeTicketId!!
    val ticketRef = session.activeTicketRef!!
    val ticketType = session.activeTicketType!!

    val confirm = Messages.showYesNoDialog(
        project,
        "Finish working on $ticketId?\n\n" +
                "This will:\n" +
                "  \u2022 Move ticket to Completed\n" +
                "  \u2022 Stop prefixing commit messages\n" +
                "  \u2022 Open the Create Pull Request dialog",
        "Rally - Finish Working",
        Messages.getQuestionIcon()
    )
    if (confirm != Messages.YES) return

    statusLabel.text = "Finishing $ticketId..."

    ApplicationManager.getApplication().executeOnPooledThread {
        val client = getClient()

        // 1. Move ticket to Completed
        try {
            client.updateArtifactState(ticketRef, ticketType, "Completed")
        } catch (e: Exception) {
            LOG.error("Failed to move $ticketId to Completed", e)
        }

        // 2. Clear work session
        session.finish()

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Optimistic update
            allArtifacts = allArtifacts.map { artifact ->
                if (artifact.ref == ticketRef) {
                    when (artifact) {
                        is RallyUserStory -> artifact.copy(scheduleState = "Completed")
                        is RallyDefect -> artifact.copy(scheduleState = "Completed")
                        else -> artifact
                    }
                } else artifact
            }
            client.clearArtifactCache()
            applySearchFilter()

            startWorkingButton.isEnabled = true
            finishWorkingButton.isEnabled = false
            statusLabel.text = "Finished $ticketId"

            // 3. Open IntelliJ's native Create Pull Request dialog
            try {
                val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                val createPrAction = actionManager.getAction("Git.CreatePullRequest")
                    ?: actionManager.getAction("Github.Create.Pull.Request")
                if (createPrAction != null) {
                    val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project)
                    val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                        "RallyFinishWorking", null, dataContext
                    )
                    createPrAction.actionPerformed(event)
                } else {
                    Messages.showInfoMessage(
                        project,
                        "Ticket moved to Completed.\n\nCould not open PR dialog — install the GitHub or GitLab plugin for PR integration.",
                        "Rally - Finish Working"
                    )
                }
            } catch (e: Exception) {
                LOG.warn("Could not open PR dialog", e)
                Messages.showInfoMessage(
                    project,
                    "Ticket $ticketId moved to Completed.\nOpen a pull request manually when ready.",
                    "Rally - Finish Working"
                )
            }
        }
    }
}
```

**Step 2: Sync button states on init**

In `checkInitialConfiguration()` (line ~97), after `loadTickets()`, add a call to sync button state with any existing session:

```kotlin
private fun checkInitialConfiguration() {
    if (RallySettings.getInstance().isConfigured()) {
        loadTickets()
        syncWorkSessionButtons()
    } else {
        showNotConfigured()
    }
}

private fun syncWorkSessionButtons() {
    val session = RallyWorkSession.getInstance(project)
    startWorkingButton.isEnabled = !session.isActive
    finishWorkingButton.isEnabled = session.isActive
    if (session.isActive) {
        statusLabel.text = "Working on ${session.activeTicketId}"
    }
}
```

**Step 3: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt
git commit -m "feat(ui): add Finish Working button with state change and PR dialog"
```

---

### Task 7: Manual integration test

**Step 1: Launch the sandbox IDE**

Run: `./gradlew runIde`

**Step 2: Test "Start Working"**

1. Open Settings → Tools → Rally, verify credentials are configured
2. Open the Rally tool window, wait for tickets to load
3. Select a ticket (e.g., US12345)
4. Click "Start Working"
5. Verify the confirmation dialog shows all 4 actions
6. Click Yes
7. Verify:
   - Git branch `feature/US12345` was created and checked out (check terminal: `git branch`)
   - Status label shows "Working on US12345"
   - "Start Working" button is disabled, "Finish Working" is enabled
   - Ticket state changed to In-Progress in the list

**Step 3: Test commit message prefix**

1. Make a small change to any file
2. Open the Commit dialog (Ctrl+K / Cmd+K)
3. Verify the commit message starts with `[US12345]`

**Step 4: Test "Finish Working"**

1. Click "Finish Working"
2. Verify the confirmation dialog
3. Click Yes
4. Verify:
   - Ticket state changed to Completed in the list
   - "Start Working" button is re-enabled, "Finish Working" is disabled
   - PR dialog opens (if GitHub plugin is installed) or info message appears

**Step 5: Test edge cases**

- Click "Start Working" with no ticket selected → info message
- Click "Start Working" when branch already exists → checks out existing branch
- Click "Finish Working" with no active session → info message

**Step 6: Commit any fixes from testing**

```bash
git add -A
git commit -m "fix: address issues found during manual testing of Start/Finish Working"
```

---

## Summary

| Task | Description | Files | Est. |
|------|-------------|-------|------|
| 1 | Add git4idea dependency | build.gradle.kts, plugin.xml | 2 min |
| 2 | Create RallyWorkSession service | New file + plugin.xml | 3 min |
| 3 | Add updateArtifactOwner API method | RallyApiClient.kt | 3 min |
| 4 | Create RallyCheckinHandler | New file + plugin.xml | 5 min |
| 5 | Add "Start Working" button + logic | RallyToolWindowPanel.kt | 5 min |
| 6 | Add "Finish Working" button + logic | RallyToolWindowPanel.kt | 5 min |
| 7 | Manual integration test | — | 10 min |
