package com.intellij.plugins.rally.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Client for interacting with Rally WSAPI 2.0
 */
class RallyApiClient(
    val serverUrl: String,
    val apiKey: String
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val gson = Gson()

    companion object {
        private const val API_VERSION = "v2.0"
        private const val DEFAULT_PAGE_SIZE = 200
        private const val MAX_PAGE_SIZE = 2000
        private const val ZSESSION_HEADER = "zsessionid"

        // Common fields to fetch for all artifact types
        private val COMMON_FIELDS = listOf(
            "FormattedID",
            "Name",
            "Description",
            "CreationDate",
            "LastUpdateDate",
            "Owner",
            "ScheduleState",
            "State",
            "Project",
            "Iteration",
            "PlanEstimate"
        )
    }

    /**
     * Normalize server URL to ensure proper format
     */
    private fun normalizeServerUrl(): String {
        var url = serverUrl.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    /**
     * Build the base API URL
     */
    private fun buildApiUrl(endpoint: String): String {
        val baseUrl = normalizeServerUrl()
        return "$baseUrl/slm/webservice/$API_VERSION/$endpoint"
    }

    /**
     * Execute HTTP GET request
     */
    private fun executeGet(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(ZSESSION_HEADER, apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()

        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw RallyConnectionException("Failed to connect to Rally server: ${e.message}", e)
        }
    }

    /**
     * Handle HTTP response and check for errors
     */
    private fun handleResponse(response: HttpResponse<String>) {
        when (response.statusCode()) {
            200 -> return // Success
            401 -> throw RallyAuthenticationException(
                "Authentication failed. Please check your API key.",
                401,
                response.body()
            )
            403 -> throw RallyAuthenticationException(
                "Access forbidden. Check your permissions.",
                403,
                response.body()
            )
            404 -> throw RallyApiException(
                "Rally API endpoint not found. Check your server URL.",
                404,
                response.body()
            )
            else -> throw RallyApiException(
                "Rally API request failed with status ${response.statusCode()}",
                response.statusCode(),
                response.body()
            )
        }
    }

    /**
     * Build query string for Rally API
     */
    private fun buildQuery(
        query: String?,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        start: Int = 1,
        workspace: String? = null,
        project: String? = null,
        order: String? = null
    ): String {
        val params = mutableListOf<String>()

        if (query != null && query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
            params.add("query=$encodedQuery")
        }

        params.add("pagesize=${pageSize.coerceIn(1, MAX_PAGE_SIZE)}")
        params.add("start=$start")
        params.add("fetch=${COMMON_FIELDS.joinToString(",")}")

        if (!workspace.isNullOrBlank()) {
            params.add("workspace=${URLEncoder.encode(normalizeRef("workspace", workspace), StandardCharsets.UTF_8)}")
        }
        if (!project.isNullOrBlank()) {
            params.add("project=${URLEncoder.encode(normalizeRef("project", project), StandardCharsets.UTF_8)}")
        }
        if (!order.isNullOrBlank()) {
            params.add("order=${URLEncoder.encode(order, StandardCharsets.UTF_8)}")
        }

        return params.joinToString("&")
    }

    // Stored workspace/project refs, set by the caller
    var workspaceRef: String? = null
    var projectRef: String? = null

    /**
     * Normalize a ref to full Rally API URL.
     * Accepts: "12345", "/workspace/12345", or full URL.
     */
    private fun normalizeRef(type: String, ref: String): String {
        val trimmed = ref.trim()
        // Already a full URL
        if (trimmed.startsWith("http")) return trimmed
        // Already a ref path like /workspace/12345
        if (trimmed.startsWith("/")) {
            return "${normalizeServerUrl()}/slm/webservice/$API_VERSION$trimmed"
        }
        // Just a number
        return "${normalizeServerUrl()}/slm/webservice/$API_VERSION/$type/$trimmed"
    }

    /**
     * Test connection to Rally server
     */
    fun testConnection(): Boolean {
        return try {
            val url = buildApiUrl("user") + "?query=(UserName = \"${getCurrentUser().userName}\")"
            val response = executeGet(url)
            handleResponse(response)
            true
        } catch (e: RallyApiException) {
            throw e
        } catch (e: Exception) {
            throw RallyConnectionException("Connection test failed: ${e.message}", e)
        }
    }

    /**
     * Get current authenticated user
     */
    fun getCurrentUser(): RallyUser {
        // Try the direct /user endpoint first (returns User object for API key auth)
        val directUrl = buildApiUrl("user")
        val response = executeGet(directUrl)
        handleResponse(response)

        val root = JsonParser.parseString(response.body()).asJsonObject

        // Rally may return {"User": {...}} for direct access
        val userObj = root.getAsJsonObject("User")
        if (userObj != null) {
            return gson.fromJson(userObj, RallyUser::class.java)
        }

        // Or it may return {"QueryResult": {"Results": [...]}} for query access
        val queryResult = root.getAsJsonObject("QueryResult")
        if (queryResult != null) {
            val results = queryResult.getAsJsonArray("Results")
            if (results != null && results.size() > 0) {
                return gson.fromJson(results.get(0), RallyUser::class.java)
            }
        }

        throw RallyApiException("No user found for the provided API key")
    }

    /**
     * Query User Stories (HierarchicalRequirement)
     */
    fun queryUserStories(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyUserStory> {
        val url = buildApiUrl("hierarchicalrequirement") + "?" +
                buildQuery(query, pageSize, workspace = workspaceRef, project = projectRef, order = "LastUpdateDate DESC")
        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyUserStory>>() {}.type
        val result: RallyQueryResult<RallyUserStory> = gson.fromJson(response.body(), type)
        return result.queryResult.results
    }

    /**
     * Query Defects
     */
    fun queryDefects(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyDefect> {
        val url = buildApiUrl("defect") + "?" +
                buildQuery(query, pageSize, workspace = workspaceRef, project = projectRef, order = "LastUpdateDate DESC")
        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyDefect>>() {}.type
        val result: RallyQueryResult<RallyDefect> = gson.fromJson(response.body(), type)
        return result.queryResult.results
    }

    /**
     * Query Tasks
     */
    fun queryTasks(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyTaskItem> {
        val url = buildApiUrl("task") + "?" +
                buildQuery(query, pageSize, workspace = workspaceRef, project = projectRef, order = "LastUpdateDate DESC")
        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyTaskItem>>() {}.type
        val result: RallyQueryResult<RallyTaskItem> = gson.fromJson(response.body(), type)
        return result.queryResult.results
    }

    /**
     * Get artifact by FormattedID (e.g., "S-1234", "DE5678", "TA9012")
     */
    fun getArtifactByFormattedId(formattedId: String): RallyArtifact? {
        // Determine artifact type from FormattedID prefix
        val (endpoint, type) = when {
            formattedId.startsWith("S-", ignoreCase = true) ||
            formattedId.startsWith("US", ignoreCase = true) ->
                "hierarchicalrequirement" to object : TypeToken<RallyQueryResult<RallyUserStory>>() {}.type

            formattedId.startsWith("DE", ignoreCase = true) ->
                "defect" to object : TypeToken<RallyQueryResult<RallyDefect>>() {}.type

            formattedId.startsWith("TA", ignoreCase = true) ->
                "task" to object : TypeToken<RallyQueryResult<RallyTaskItem>>() {}.type

            else -> return null
        }

        val query = "(FormattedID = \"$formattedId\")"
        val url = buildApiUrl(endpoint) + "?" + buildQuery(query, 1)

        return try {
            val response = executeGet(url)
            handleResponse(response)

            val result: RallyQueryResult<out RallyArtifact> = gson.fromJson(response.body(), type)
            result.queryResult.results.firstOrNull()
        } catch (e: RallyApiException) {
            null
        }
    }

    /**
     * Query all artifacts (User Stories and Defects combined)
     * This is useful for the main task browser
     */
    fun queryAllArtifacts(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyArtifact> {
        val results = mutableListOf<RallyArtifact>()
        val errors = mutableListOf<String>()

        // Query user stories
        try {
            results.addAll(queryUserStories(query, pageSize))
        } catch (e: Exception) {
            errors.add("UserStories: ${e.message}")
        }

        // Query defects
        try {
            results.addAll(queryDefects(query, pageSize))
        } catch (e: Exception) {
            errors.add("Defects: ${e.message}")
        }

        // If both queries failed, throw so the UI can show the error
        if (results.isEmpty() && errors.isNotEmpty()) {
            throw RallyApiException("Query failed - ${errors.joinToString("; ")}")
        }

        // Sort by last update date (most recent first)
        return results.sortedByDescending { it.lastUpdateDate }
    }

    /**
     * Build web URL for viewing an artifact in Rally
     */
    fun buildWebUrl(artifact: RallyArtifact): String {
        val baseUrl = normalizeServerUrl()
        val objectId = artifact.objectID ?: return baseUrl

        val detailPage = when (artifact.type) {
            "HierarchicalRequirement" -> "userstory"
            "Defect" -> "defect"
            "Task" -> "task"
            else -> "detail"
        }

        return "$baseUrl/#/detail/$detailPage/$objectId"
    }

    /**
     * Execute HTTP POST request
     */
    private fun executePost(url: String, jsonBody: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(ZSESSION_HEADER, apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw RallyConnectionException("Failed to connect to Rally server: ${e.message}", e)
        }
    }

    /**
     * Update the state of an artifact.
     * User Stories/Defects use ScheduleState, Tasks use State.
     */
    fun updateArtifactState(artifactRef: String, artifactType: String, newState: String) {
        val stateField = if (artifactType == "Task") "State" else "ScheduleState"
        val body = """{"$artifactType":{"$stateField":"$newState"}}"""
        val response = executePost(artifactRef, body)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val result = json.getAsJsonObject("OperationResult")
        if (result != null) {
            val errors = result.getAsJsonArray("Errors")
            if (errors != null && errors.size() > 0) {
                throw RallyApiException("Failed to update state: ${errors.joinToString()}")
            }
        }
    }

    /**
     * Get the current iteration (sprint) by today's date.
     */
    fun queryCurrentIteration(workspaceRef: String? = null, projectRef: String? = null): RallyIteration? {
        val today = java.time.LocalDate.now().toString()
        val query = "((StartDate <= \"$today\") AND (EndDate >= \"$today\"))"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("iteration") +
                "?query=$encodedQuery&fetch=Name,StartDate,EndDate,PlannedVelocity,ObjectID,_ref&pagesize=1"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef), StandardCharsets.UTF_8)}"
        }
        if (!projectRef.isNullOrBlank()) {
            url += "&project=${URLEncoder.encode(normalizeRef("project", projectRef), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyIteration>>() {}.type
        val result: RallyQueryResult<RallyIteration> = gson.fromJson(response.body(), type)
        return result.queryResult.results.firstOrNull()
    }

    /**
     * Get all artifacts in a specific iteration by name.
     */
    fun queryIterationArtifacts(iterationName: String, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyArtifact> {
        val query = "(Iteration.Name = \"$iterationName\")"
        return queryAllArtifacts(query, pageSize)
    }

    /**
     * Query projects in the configured workspace.
     * Returns only Open (active) projects, sorted alphabetically by name.
     */
    fun queryProjects(pageSize: Int = MAX_PAGE_SIZE): List<RallyProject> {
        val query = "(State = \"Open\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("project") +
                "?query=$encodedQuery&fetch=Name,ObjectID,_ref,State&pagesize=$pageSize&order=Name"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyProject>>() {}.type
        val result: RallyQueryResult<RallyProject> = gson.fromJson(response.body(), type)
        return result.queryResult.results.sortedBy { it.name?.lowercase() }
    }

    /**
     * Query iterations (sprints) in the configured workspace/project.
     * Returns iterations sorted by StartDate descending (most recent first).
     */
    fun queryIterations(pageSize: Int = MAX_PAGE_SIZE): List<RallyIteration> {
        var url = buildApiUrl("iteration") +
                "?fetch=Name,ObjectID,_ref,StartDate,EndDate&pagesize=$pageSize" +
                "&order=${URLEncoder.encode("StartDate desc", StandardCharsets.UTF_8)}"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }
        if (!projectRef.isNullOrBlank()) {
            url += "&project=${URLEncoder.encode(normalizeRef("project", projectRef!!), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyIteration>>() {}.type
        val result: RallyQueryResult<RallyIteration> = gson.fromJson(response.body(), type)
        return result.queryResult.results
    }

    /**
     * Create a new User Story.
     */
    fun createUserStory(name: String, projectRef: String?, ownerRef: String? = null): RallyUserStory {
        val url = buildApiUrl("hierarchicalrequirement/create")
        val fields = mutableMapOf<String, Any>("Name" to name, "ScheduleState" to "Defined")
        if (!projectRef.isNullOrBlank()) fields["Project"] = projectRef
        if (!ownerRef.isNullOrBlank()) fields["Owner"] = ownerRef

        val body = """{"HierarchicalRequirement":${gson.toJson(fields)}}"""
        val response = executePost(url, body)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val createResult = json.getAsJsonObject("CreateResult")
        val errors = createResult?.getAsJsonArray("Errors")
        if (errors != null && errors.size() > 0) {
            throw RallyApiException("Failed to create user story: ${errors.joinToString()}")
        }
        val obj = createResult.getAsJsonObject("Object")
        return gson.fromJson(obj, RallyUserStory::class.java)
    }
}
