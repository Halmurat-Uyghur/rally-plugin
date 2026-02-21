package com.intellij.plugins.rally

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.plugins.rally.api.RallyApiClient
import com.intellij.plugins.rally.api.RallyApiException
import com.intellij.plugins.rally.api.RallyArtifact
import com.intellij.plugins.rally.api.RallyAuthenticationException
import com.intellij.plugins.rally.api.RallyConnectionException
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.TaskState
import com.intellij.tasks.impl.BaseRepository
import com.intellij.tasks.impl.BaseRepositoryImpl
import com.intellij.util.xmlb.annotations.Tag

/**
 * Rally task repository implementation
 * This class handles the connection to Rally and fetching of tasks
 */
@Tag("Rally")
class RallyRepository : BaseRepositoryImpl {

    companion object {
        private val LOG = Logger.getInstance(RallyRepository::class.java)
        private const val DEFAULT_SERVER_URL = "https://rally1.rallydev.com"
        private const val DEFAULT_PAGE_SIZE = 200
    }

    // Configuration properties
    @JvmField
    @Tag("serverUrl")
    var serverUrl: String = DEFAULT_SERVER_URL

    @JvmField
    @Tag("apiKey")
    var apiKey: String = ""

    @JvmField
    @Tag("workspace")
    var workspace: String = ""

    @JvmField
    @Tag("projectFilter")
    var projectFilter: String = ""

    @JvmField
    @Tag("pageSize")
    var pageSize: Int = DEFAULT_PAGE_SIZE

    // API client instance (transient - not persisted)
    @Transient
    private var apiClient: RallyApiClient? = null

    /**
     * Default constructor required for serialization
     */
    constructor() : super()

    /**
     * Constructor with repository type
     */
    constructor(type: TaskRepositoryType<*>) : super(type)

    /**
     * Copy constructor
     */
    constructor(other: RallyRepository) : super(other) {
        this.serverUrl = other.serverUrl
        this.apiKey = other.apiKey
        this.workspace = other.workspace
        this.projectFilter = other.projectFilter
        this.pageSize = other.pageSize
    }

    /**
     * Get or create API client
     */
    private fun getClient(): RallyApiClient {
        if (apiClient == null) {
            apiClient = RallyApiClient(serverUrl, apiKey)
        }
        return apiClient!!
    }

    /**
     * Get the API client for use by actions.
     */
    fun getApiClient(): RallyApiClient = getClient()

    /**
     * Create a clone of this repository
     */
    override fun clone(): BaseRepository {
        return RallyRepository(this)
    }

    /**
     * Check if repository is configured properly
     */
    override fun isConfigured(): Boolean {
        return serverUrl.isNotBlank() && apiKey.isNotBlank()
    }

    /**
     * Get repository presentation name
     */
    override fun getPresentableName(): String {
        return if (url.isNotBlank()) {
            "Rally ($url)"
        } else {
            "Rally"
        }
    }

    /**
     * Get repository comment for display
     */
    override fun getComment(): String {
        return presentableName
    }

    /**
     * Test connection to Rally server
     */
    @Throws(Exception::class)
    override fun testConnection() {
        if (!isConfigured()) {
            throw RallyApiException("Rally repository is not configured. Please provide server URL and API key.")
        }

        try {
            val client = getClient()
            if (client.testConnection()) {
                val user = client.getCurrentUser()
                LOG.info("Successfully connected to Rally as user: ${user.displayName}")
            }
        } catch (e: RallyAuthenticationException) {
            throw Exception("Authentication failed: ${e.message}")
        } catch (e: RallyConnectionException) {
            throw Exception("Connection failed: ${e.message}")
        } catch (e: RallyApiException) {
            throw Exception("Rally API error: ${e.message}")
        } catch (e: Exception) {
            throw Exception("Unexpected error: ${e.message}")
        }
    }

    /**
     * Main method to fetch issues from Rally
     * This is called by IntelliJ's task management system
     */
    @Throws(Exception::class)
    override fun getIssues(query: String?, maxCount: Int, since: Long): Array<Task> {
        if (!isConfigured()) {
            LOG.warn("Rally repository is not configured")
            return emptyArray()
        }

        return try {
            val client = getClient()

            // Build Rally query
            val rallyQuery = buildRallyQuery(query, projectFilter, true)

            // Fetch artifacts
            val artifacts = client.queryAllArtifacts(rallyQuery, maxCount.coerceAtMost(pageSize))

            // Convert to tasks
            val tasks = artifacts.mapNotNull { artifact ->
                try {
                    convertToTask(artifact, client)
                } catch (e: Exception) {
                    LOG.error("Failed to convert artifact ${artifact.formattedID} to task", e)
                    null
                }
            }

            LOG.info("Fetched ${tasks.size} tasks from Rally")
            tasks.toTypedArray()

        } catch (e: RallyAuthenticationException) {
            LOG.error("Rally authentication failed", e)
            throw Exception("Authentication failed. Please check your API key.")
        } catch (e: RallyConnectionException) {
            LOG.error("Rally connection failed", e)
            throw Exception("Connection failed. Please check your server URL and network connection.")
        } catch (e: RallyApiException) {
            LOG.error("Rally API error", e)
            throw Exception("Rally API error: ${e.message}")
        } catch (e: Exception) {
            LOG.error("Unexpected error while fetching Rally tasks", e)
            throw Exception("Unexpected error: ${e.message}")
        }
    }

    /**
     * Find a single task by ID (FormattedID)
     */
    override fun findTask(id: String): Task? {
        if (!isConfigured()) {
            return null
        }

        return try {
            val client = getClient()
            val artifact = client.getArtifactByFormattedId(id)

            if (artifact != null) {
                convertToTask(artifact, client)
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.error("Failed to find task $id", e)
            null
        }
    }

    /**
     * Update the state of a Rally task.
     * Called by IntelliJ when the user selects a new state from the task dropdown.
     */
    override fun setTaskState(task: Task, state: TaskState) {
        val rallyTask = task as? RallyTask ?: return
        val artifact = rallyTask.getArtifact()
        val objectID = artifact.objectID ?: return
        val artifactType = artifact.type ?: return

        val newRallyState = mapTaskStateToRallyState(state, artifactType)

        try {
            val client = getClient()
            client.updateArtifactState(objectID, artifactType, newRallyState)
            LOG.info("Updated ${artifact.formattedID} state to $newRallyState")
        } catch (e: Exception) {
            LOG.error("Failed to update state for ${artifact.formattedID}", e)
            throw Exception("Failed to update Rally state: ${e.message}")
        }
    }

    /**
     * Map IntelliJ TaskState to Rally-specific state string
     */
    private fun mapTaskStateToRallyState(state: TaskState, artifactType: String): String {
        return when (artifactType) {
            "HierarchicalRequirement" -> when (state) {
                TaskState.OPEN -> "Defined"
                TaskState.IN_PROGRESS -> "In-Progress"
                TaskState.RESOLVED -> "Completed"
                else -> "Defined"
            }
            "Defect" -> when (state) {
                TaskState.OPEN -> "Open"
                TaskState.IN_PROGRESS -> "Open"  // Defects don't have an "In-Progress"; Open is active work
                TaskState.RESOLVED -> "Fixed"
                else -> "Submitted"
            }
            else -> throw RallyApiException("Unsupported artifact type: $artifactType")
        }
    }

    /**
     * Escape a string for safe use in Rally query language
     * Rally uses double quotes for string literals, so we need to escape:
     * - Backslashes (must be first to avoid double-escaping)
     * - Double quotes
     */
    private fun escapeRallyQueryString(input: String): String {
        return input
            .replace("\\", "\\\\")  // Escape backslashes first
            .replace("\"", "\\\"")  // Escape double quotes
    }

    /**
     * Build Rally query string from user input and filters
     */
    private fun buildRallyQuery(userQuery: String?, projectFilter: String?, withClosed: Boolean): String? {
        val conditions = mutableListOf<String>()

        // Add user query if provided
        if (!userQuery.isNullOrBlank()) {
            // Escape user input to prevent query injection
            val escapedQuery = escapeRallyQueryString(userQuery)

            // If user query looks like a FormattedID, search by that
            if (userQuery.matches(Regex("^(S-|US|DE|TA)\\d+", RegexOption.IGNORE_CASE))) {
                conditions.add("(FormattedID = \"$escapedQuery\")")
            } else {
                // Otherwise search in Name
                conditions.add("(Name contains \"$escapedQuery\")")
            }
        }

        // Add project filter if provided
        if (!projectFilter.isNullOrBlank()) {
            // Escape project filter to prevent query injection
            val escapedProjectFilter = escapeRallyQueryString(projectFilter)
            conditions.add("(Project.Name contains \"$escapedProjectFilter\")")
        }

        // Add state filter if closed items should be excluded
        if (!withClosed) {
            conditions.add("((ScheduleState != \"Accepted\") AND (State != \"Closed\") AND (State != \"Fixed\"))")
        }

        return if (conditions.isNotEmpty()) {
            conditions.joinToString(" AND ")
        } else {
            null
        }
    }

    /**
     * Convert Rally artifact to IntelliJ Task
     */
    private fun convertToTask(artifact: RallyArtifact, client: RallyApiClient): Task {
        val issueUrl = client.buildWebUrl(artifact)
        return RallyTask(artifact, this, issueUrl)
    }

    /**
     * Get the URL for this repository (used for display)
     */
    override fun getUrl(): String {
        return serverUrl
    }

    /**
     * Set the URL for this repository
     */
    override fun setUrl(url: String) {
        this.serverUrl = url
        // Invalidate client when URL changes
        apiClient = null
    }


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RallyRepository) return false
        if (!super.equals(other)) return false

        if (serverUrl != other.serverUrl) return false
        if (apiKey != other.apiKey) return false
        if (workspace != other.workspace) return false
        if (projectFilter != other.projectFilter) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + serverUrl.hashCode()
        result = 31 * result + apiKey.hashCode()
        result = 31 * result + workspace.hashCode()
        result = 31 * result + projectFilter.hashCode()
        return result
    }

    override fun toString(): String {
        return "RallyRepository(url=$serverUrl, workspace=$workspace)"
    }
}
