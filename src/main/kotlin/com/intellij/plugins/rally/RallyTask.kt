package com.intellij.plugins.rally

import com.intellij.icons.AllIcons
import com.intellij.plugins.rally.api.RallyArtifact
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.TaskState
import com.intellij.tasks.TaskType
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.Icon

/**
 * Represents a Rally work item (User Story, Defect, or Task) as an IntelliJ Task
 */
class RallyTask(
    private val artifact: RallyArtifact,
    private val repository: TaskRepository,
    private val issueUrl: String
) : Task() {

    companion object {
        // Rally date format: "2024-01-15T12:30:45.123Z"
        private val RALLY_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /**
         * Parse Rally date string to Date object
         */
        private fun parseRallyDate(dateStr: String?): Date? {
            if (dateStr == null) return null
            return try {
                RALLY_DATE_FORMAT.parse(dateStr)
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun getId(): String {
        return artifact.formattedID ?: artifact.objectID ?: ""
    }

    override fun getSummary(): String {
        return "${artifact.formattedID ?: ""}: ${artifact.name ?: "Untitled"}"
    }

    override fun getDescription(): String? {
        return artifact.description?.let { stripHtml(it) }
    }

    override fun getCreated(): Date? {
        return parseRallyDate(artifact.creationDate)
    }

    override fun getUpdated(): Date? {
        return parseRallyDate(artifact.lastUpdateDate)
    }

    override fun isClosed(): Boolean {
        val state = artifact.scheduleState ?: artifact.state ?: ""
        return state.equals("Accepted", ignoreCase = true) ||
                state.equals("Completed", ignoreCase = true) ||
                state.equals("Closed", ignoreCase = true) ||
                state.equals("Fixed", ignoreCase = true)
    }

    override fun isIssue(): Boolean {
        // Defects are issues, user stories are features
        return artifact.type == "Defect"
    }

    override fun getIssueUrl(): String {
        return issueUrl
    }

    override fun getType(): TaskType {
        return when (artifact.type) {
            "Defect" -> TaskType.BUG
            "HierarchicalRequirement" -> TaskType.FEATURE
            "Task" -> TaskType.OTHER
            else -> TaskType.OTHER
        }
    }

    override fun getState(): TaskState {
        val state = artifact.scheduleState ?: artifact.state ?: ""
        return when {
            state.equals("Accepted", ignoreCase = true) ||
            state.equals("Completed", ignoreCase = true) ||
            state.equals("Closed", ignoreCase = true) ||
            state.equals("Fixed", ignoreCase = true) -> TaskState.RESOLVED

            state.equals("In-Progress", ignoreCase = true) ||
            state.equals("In Progress", ignoreCase = true) -> TaskState.IN_PROGRESS

            else -> TaskState.OPEN
        }
    }

    override fun getRepository(): TaskRepository {
        return repository
    }

    override fun getComments(): Array<Comment> {
        // Rally API doesn't easily provide comments in simple queries
        // Could be enhanced in future versions
        return emptyArray()
    }

    override fun getIcon(): Icon {
        // Return default icon from IntelliJ based on task type
        return when (type) {
            TaskType.BUG -> AllIcons.General.Error
            TaskType.FEATURE -> AllIcons.Nodes.PpLib
            else -> AllIcons.FileTypes.Any_type
        }
    }

    override fun getPresentableName(): String {
        return summary
    }

    override fun getPresentableId(): String {
        return artifact.formattedID ?: id
    }

    /**
     * Get the owner/assignee of the task
     */
    fun getOwner(): String? {
        return artifact.owner?.displayName ?: artifact.owner?.refObjectName
    }

    /**
     * Get the project name
     */
    override fun getProject(): String? {
        // Access project from specific artifact types if needed
        return when (artifact) {
            is com.intellij.plugins.rally.api.RallyUserStory -> artifact.project?.name
            is com.intellij.plugins.rally.api.RallyDefect -> artifact.project?.name
            else -> null
        }
    }

    /**
     * Get the schedule state (for display purposes)
     */
    fun getScheduleState(): String? {
        return artifact.scheduleState ?: artifact.state
    }

    /**
     * Strip HTML tags from description
     */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "") // Remove HTML tags
            .replace(Regex("&nbsp;"), " ") // Replace non-breaking spaces
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()
    }
}
