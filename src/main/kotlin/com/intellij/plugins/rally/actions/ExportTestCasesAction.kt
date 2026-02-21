package com.intellij.plugins.rally.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as IdeaTask
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.plugins.rally.RallyRepository
import com.intellij.plugins.rally.RallyTask
import com.intellij.plugins.rally.export.TestCaseExporter
import com.intellij.tasks.TaskManager

/**
 * Action to export Rally test cases for the currently active task.
 * Available from Tools menu and right-click context menu.
 */
class ExportTestCasesAction : AnAction(
    "Export Rally Test Cases",
    "Export test cases for the active Rally user story to Markdown or JSON",
    null
) {
    companion object {
        private val LOG = Logger.getInstance(ExportTestCasesAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Get active task from IntelliJ's task manager
        val taskManager = TaskManager.getManager(project) ?: run {
            Messages.showErrorDialog(project, "Task manager not available.", "Export Failed")
            return
        }
        val activeTask = taskManager.activeTask

        // Check if it's a Rally task (User Story)
        val rallyTask = activeTask as? RallyTask ?: run {
            Messages.showInfoMessage(
                project,
                "Please activate a Rally user story first (Tools > Tasks > Open Task).",
                "No Rally Task Active"
            )
            return
        }

        val artifact = rallyTask.getArtifact()
        if (artifact.type != "HierarchicalRequirement") {
            Messages.showInfoMessage(
                project,
                "Test case export is only available for User Stories, not ${artifact.type}.",
                "Not a User Story"
            )
            return
        }

        val objectID = artifact.objectID ?: return
        val formattedID = artifact.formattedID ?: "Unknown"
        val storyName = artifact.name ?: "Untitled"

        // Ask user: Markdown or JSON?
        val choice = Messages.showYesNoCancelDialog(
            project,
            "Export test cases for $formattedID as:",
            "Export Format",
            "Markdown",
            "JSON",
            "Cancel",
            Messages.getQuestionIcon()
        )
        if (choice == Messages.CANCEL) return
        val useMarkdown = (choice == Messages.YES)
        val extension = if (useMarkdown) "md" else "json"

        // Show file save dialog
        val descriptor = FileSaverDescriptor(
            "Export Test Cases",
            "Choose where to save the test cases file",
            extension
        )
        val defaultFileName = "${formattedID}-test-cases.$extension"
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }, defaultFileName)
            ?: return

        // Get the RallyApiClient from the repository
        val repository = rallyTask.repository as? RallyRepository ?: return
        val client = repository.getApiClient()

        // Run in background with progress
        ProgressManager.getInstance().run(object : IdeaTask.Backgroundable(project, "Exporting Test Cases...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Fetching test cases from Rally..."
                    indicator.isIndeterminate = true

                    val content = if (useMarkdown) {
                        TestCaseExporter.exportToMarkdown(client, objectID, formattedID, storyName)
                    } else {
                        TestCaseExporter.exportToJson(client, objectID, formattedID, storyName)
                    }

                    wrapper.file.writeText(content)

                    // Refresh VFS so IntelliJ sees the new file
                    LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(wrapper.file)

                    Messages.showInfoMessage(
                        project,
                        "Test cases exported to:\n${wrapper.file.absolutePath}",
                        "Export Complete"
                    )
                } catch (ex: Exception) {
                    LOG.error("Failed to export test cases", ex)
                    Messages.showErrorDialog(
                        project,
                        "Failed to export test cases: ${ex.message}",
                        "Export Failed"
                    )
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        // Only enable when a project is open
        e.presentation.isEnabled = e.project != null
    }
}
