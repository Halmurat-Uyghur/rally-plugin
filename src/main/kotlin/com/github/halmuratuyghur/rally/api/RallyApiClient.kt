package com.github.halmuratuyghur.rally.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Client for interacting with Rally WSAPI 2.0
 */
class RallyApiClient(
    val serverUrl: String,
    val apiKey: String
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val gson = Gson()

    // ── Cache ────────────────────────────────────────────────────

    private data class CacheEntry<T>(val data: T, val timestamp: Long)

    private val queryCache = ConcurrentHashMap<String, CacheEntry<Any>>()
    private val imageCache = ConcurrentHashMap<String, ByteArray>()

    /** Default TTL for query caches (2 minutes). */
    private val queryTtlMs = 2 * 60 * 1000L

    /** Image cache has no TTL — images rarely change. */

    @Suppress("UNCHECKED_CAST")
    private fun <T> getCached(key: String): T? {
        val entry = queryCache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > queryTtlMs) {
            queryCache.remove(key)
            return null
        }
        return entry.data as? T
    }

    private fun <T : Any> putCache(key: String, data: T) {
        queryCache[key] = CacheEntry(data, System.currentTimeMillis())
    }

    /**
     * Clear all caches including images.
     * Call on manual Refresh to get fully fresh data from Rally.
     */
    fun clearCache() {
        queryCache.clear()
        imageCache.clear()
    }

    /**
     * Clear only artifact list caches (keeps detail/description/image caches).
     * Call after local state changes (create, update) where only the list is stale
     * but detail data we just wrote is still correct.
     */
    fun clearArtifactCache() {
        val iter = queryCache.keys.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key.startsWith("artifacts:") || key.startsWith("sprint:") || key.startsWith("currentIteration:")) {
                iter.remove()
            }
        }
    }

    companion object {
        private const val API_VERSION = "v2.0"
        private const val DEFAULT_PAGE_SIZE = 200
        private const val MAX_PAGE_SIZE = 2000
        private const val ZSESSION_HEADER = "zsessionid"

        // Fields for list queries (lightweight — no Description)
        private val LIST_FIELDS = listOf(
            "FormattedID",
            "Name",
            "CreationDate",
            "LastUpdateDate",
            "Owner",
            "ScheduleState",
            "State",
            "Project",
            "Iteration",
            "PlanEstimate"
        )

        // Fields for detail queries (includes Description)
        private val DETAIL_FIELDS = LIST_FIELDS + "Description"
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
        order: String? = null,
        fields: List<String> = LIST_FIELDS
    ): String {
        val params = mutableListOf<String>()

        if (query != null && query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
            params.add("query=$encodedQuery")
        }

        params.add("pagesize=${pageSize.coerceIn(1, MAX_PAGE_SIZE)}")
        params.add("start=$start")
        params.add("fetch=${fields.joinToString(",")}")

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
    @Volatile var workspaceRef: String? = null
    @Volatile var projectRef: String? = null

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
     * Get a user by their UserName (email).
     * Uses the Rally user query endpoint instead of /user (which always returns the API key owner).
     */
    fun getUserByUsername(username: String): RallyUser {
        val url = buildApiUrl("user") + "?" +
                buildQuery("(UserName = \"$username\")", pageSize = 1, workspace = workspaceRef)
        val response = executeGet(url)
        handleResponse(response)

        val root = JsonParser.parseString(response.body()).asJsonObject
        val results = root.getAsJsonObject("QueryResult")?.getAsJsonArray("Results")
        if (results != null && results.size() > 0) {
            return gson.fromJson(results.get(0), RallyUser::class.java)
        }
        throw RallyApiException("No user found with UserName: $username")
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
     * Fetch the Description field for a single artifact by ref URL.
     * Returns the HTML description or null.
     */
    fun fetchDescription(artifactRef: String): String? {
        val cacheKey = "desc:$artifactRef"
        getCached<String>(cacheKey)?.let { return it }

        val url = "$artifactRef?fetch=Description"
        return try {
            val response = executeGet(url)
            handleResponse(response)
            val root = JsonParser.parseString(response.body()).asJsonObject
            // Rally wraps in the type name (HierarchicalRequirement, Defect, Task, etc.)
            val obj = root.entrySet().firstOrNull()?.value?.asJsonObject
            val desc = obj?.get("Description")?.asString
            if (desc != null) putCache(cacheKey, desc)
            desc
        } catch (e: Exception) {
            null
        }
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
        val url = buildApiUrl(endpoint) + "?" + buildQuery(query, 1, fields = DETAIL_FIELDS)

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
        val cacheKey = "artifacts:${query}|${pageSize}|${workspaceRef}|${projectRef}"
        getCached<List<RallyArtifact>>(cacheKey)?.let { return it }

        val results = mutableListOf<RallyArtifact>()
        val errors = mutableListOf<String>()

        // Query user stories and defects in parallel
        val storiesFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            queryUserStories(query, pageSize)
        }
        val defectsFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            queryDefects(query, pageSize)
        }

        try {
            results.addAll(storiesFuture.get())
        } catch (e: Exception) {
            errors.add("UserStories: ${e.cause?.message ?: e.message}")
        }

        try {
            results.addAll(defectsFuture.get())
        } catch (e: Exception) {
            errors.add("Defects: ${e.cause?.message ?: e.message}")
        }

        // If both queries failed, throw so the UI can show the error
        if (results.isEmpty() && errors.isNotEmpty()) {
            throw RallyApiException("Query failed - ${errors.joinToString("; ")}")
        }

        // Sort by last update date (most recent first)
        val sorted = results.sortedByDescending { it.lastUpdateDate }
        putCache(cacheKey, sorted)
        return sorted
    }

    /**
     * Build web URL for viewing an artifact in Rally.
     * Rally web UI URLs require the project OID: /#/<projectOID>d/detail/<type>/<objectID>
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

        // Extract project OID from the artifact's project ref
        val projectOid = when (artifact) {
            is RallyUserStory -> artifact.project?.ref
            is RallyDefect -> artifact.project?.ref
            else -> null
        }?.trimEnd('/')?.substringAfterLast('/')

        return if (projectOid != null) {
            "$baseUrl/#/${projectOid}d/detail/$detailPage/$objectId"
        } else {
            "$baseUrl/#/detail/$detailPage/$objectId"
        }
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
        val cacheKey = "currentIteration:${workspaceRef}|${projectRef}"
        getCached<RallyIteration>(cacheKey)?.let { return it }

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
        val iteration = result.queryResult.results.firstOrNull()
        if (iteration != null) putCache(cacheKey, iteration)
        return iteration
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
     * When a project is selected, filters iterations to that project only.
     * Returns iterations sorted by StartDate descending (most recent first).
     */
    fun queryIterations(pageSize: Int = MAX_PAGE_SIZE): List<RallyIteration> {
        var url = buildApiUrl("iteration") +
                "?fetch=Name,ObjectID,_ref,StartDate,EndDate,PlannedVelocity,Project,State&pagesize=$pageSize" +
                "&order=${URLEncoder.encode("StartDate DESC,EndDate DESC,ObjectID", StandardCharsets.UTF_8)}"

        // Filter by project in the query — without this, Rally returns iterations from all projects
        if (!projectRef.isNullOrBlank()) {
            val normalizedProjectRef = normalizeRef("project", projectRef!!)
            val query = "(Project = \"$normalizedProjectRef\")"
            url += "&query=${URLEncoder.encode(query, StandardCharsets.UTF_8)}"
        }

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }
        if (!projectRef.isNullOrBlank()) {
            url += "&project=${URLEncoder.encode(normalizeRef("project", projectRef!!), StandardCharsets.UTF_8)}"
            url += "&projectScopeUp=true&projectScopeDown=true"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyIteration>>() {}.type
        val result: RallyQueryResult<RallyIteration> = gson.fromJson(response.body(), type)
        return result.queryResult.results
    }

    /**
     * Query tasks linked to a work product (user story/defect).
     */
    fun queryTasksForWorkProduct(workProductRef: String, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyTaskItem> {
        val cacheKey = "tasks:$workProductRef"
        getCached<List<RallyTaskItem>>(cacheKey)?.let { return it }

        val query = "(WorkProduct = \"$workProductRef\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("task") +
                "?query=$encodedQuery&fetch=FormattedID,Name,State,Owner,Estimate,Actuals,ToDo,ObjectID,_ref" +
                "&pagesize=$pageSize&order=${URLEncoder.encode("FormattedID ASC", StandardCharsets.UTF_8)}"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyTaskItem>>() {}.type
        val result: RallyQueryResult<RallyTaskItem> = gson.fromJson(response.body(), type)
        putCache(cacheKey, result.queryResult.results)
        return result.queryResult.results
    }

    /**
     * Query test cases linked to a work product (user story/defect).
     */
    fun queryTestCases(workProductRef: String, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyTestCase> {
        val cacheKey = "testcases:$workProductRef"
        getCached<List<RallyTestCase>>(cacheKey)?.let { return it }

        val query = "(WorkProduct = \"$workProductRef\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("testcase") +
                "?query=$encodedQuery&fetch=FormattedID,Name,Method,Type,LastVerdict,LastRun,Owner,WorkProduct,Description,Priority,ObjectID,_ref" +
                "&pagesize=$pageSize&order=${URLEncoder.encode("FormattedID ASC", StandardCharsets.UTF_8)}"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyTestCase>>() {}.type
        val result: RallyQueryResult<RallyTestCase> = gson.fromJson(response.body(), type)
        putCache(cacheKey, result.queryResult.results)
        return result.queryResult.results
    }

    /**
     * Query test case steps for a given test case FormattedID.
     */
    fun queryTestSteps(testCaseFormattedId: String): List<RallyTestCaseStep> {
        val query = "(TestCase.FormattedID = \"$testCaseFormattedId\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("testcasestep") +
                "?query=$encodedQuery&fetch=StepIndex,Input,ExpectedResult,_ref" +
                "&pagesize=$DEFAULT_PAGE_SIZE&order=${URLEncoder.encode("StepIndex ASC", StandardCharsets.UTF_8)}"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyTestCaseStep>>() {}.type
        val result: RallyQueryResult<RallyTestCaseStep> = gson.fromJson(response.body(), type)
        return result.queryResult.results
    }

    /**
     * Query attachments for a given artifact FormattedID.
     */
    fun queryAttachments(artifactFormattedId: String): List<RallyAttachment> {
        val cacheKey = "attachments:$artifactFormattedId"
        getCached<List<RallyAttachment>>(cacheKey)?.let { return it }

        val query = "(Artifact.FormattedID = \"$artifactFormattedId\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("attachment") +
                "?query=$encodedQuery&fetch=Name,ContentType,Size,Description,Content,ObjectID,_ref" +
                "&pagesize=$DEFAULT_PAGE_SIZE"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyAttachment>>() {}.type
        val result: RallyQueryResult<RallyAttachment> = gson.fromJson(response.body(), type)
        putCache(cacheKey, result.queryResult.results)
        return result.queryResult.results
    }

    /**
     * Query a single test case by FormattedID.
     */
    fun queryTestCaseByFormattedId(formattedId: String): RallyTestCase? {
        val query = "(FormattedID = \"$formattedId\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("testcase") +
                "?query=$encodedQuery&fetch=FormattedID,Name,Method,Type,LastVerdict,LastRun,Owner,WorkProduct,Description,Priority,ObjectID,_ref" +
                "&pagesize=1"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyTestCase>>() {}.type
        val result: RallyQueryResult<RallyTestCase> = gson.fromJson(response.body(), type)
        return result.queryResult.results.firstOrNull()
    }

    /**
     * Query all non-automated test cases.
     */
    fun queryUnautomatedTestCases(pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyTestCase> {
        val query = "(Method != \"Automated\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("testcase") +
                "?query=$encodedQuery&fetch=FormattedID,Name,Method,Type,LastVerdict,Owner,WorkProduct,ObjectID,_ref" +
                "&pagesize=$pageSize&order=${URLEncoder.encode("FormattedID ASC", StandardCharsets.UTF_8)}"

        if (!workspaceRef.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", workspaceRef!!), StandardCharsets.UTF_8)}"
        }
        if (!projectRef.isNullOrBlank()) {
            url += "&project=${URLEncoder.encode(normalizeRef("project", projectRef!!), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val type = object : TypeToken<RallyQueryResult<RallyTestCase>>() {}.type
        val result: RallyQueryResult<RallyTestCase> = gson.fromJson(response.body(), type)
        return result.queryResult.results
    }

    /**
     * Get attachment content (base64) by fetching the Content ref.
     */
    fun getAttachmentContent(contentRef: String): String {
        val response = executeGet(contentRef)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val contentObj = json.getAsJsonObject("AttachmentContent")
            ?: throw RallyApiException("No AttachmentContent in response")
        return contentObj.get("Content")?.asString
            ?: throw RallyApiException("No Content field in AttachmentContent")
    }

    /**
     * Download a Rally attachment by URL using zsessionid auth.
     * Returns raw bytes.
     */
    fun downloadAttachment(url: String): ByteArray {
        // Images rarely change — use permanent cache
        imageCache[url]?.let { return it }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(ZSESSION_HEADER, apiKey)
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            throw RallyConnectionException("Failed to download attachment: ${e.message}", e)
        }

        if (response.statusCode() != 200) {
            throw RallyApiException("Download failed with status ${response.statusCode()}", response.statusCode(), null)
        }

        val bytes = response.body()
        imageCache[url] = bytes
        return bytes
    }

    /**
     * Create a new User Story.
     */
    fun createUserStory(
        name: String,
        projectRef: String?,
        ownerRef: String? = null,
        description: String? = null,
        iterationRef: String? = null
    ): RallyUserStory {
        val url = buildApiUrl("hierarchicalrequirement/create")
        val fields = mutableMapOf<String, Any>("Name" to name, "ScheduleState" to "Defined")
        if (!projectRef.isNullOrBlank()) fields["Project"] = projectRef
        if (!ownerRef.isNullOrBlank()) fields["Owner"] = ownerRef
        if (!description.isNullOrBlank()) fields["Description"] = description
        if (!iterationRef.isNullOrBlank()) fields["Iteration"] = iterationRef

        val body = """{"HierarchicalRequirement":${gson.toJson(fields)}}"""
        val response = executePost(url, body)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val createResult = json.getAsJsonObject("CreateResult")
            ?: throw RallyApiException("Unexpected response: missing CreateResult")
        val errors = createResult.getAsJsonArray("Errors")
        if (errors != null && errors.size() > 0) {
            throw RallyApiException("Failed to create user story: ${errors.joinToString()}")
        }
        val obj = createResult.getAsJsonObject("Object")
            ?: throw RallyApiException("Unexpected response: missing Object in CreateResult")
        return gson.fromJson(obj, RallyUserStory::class.java)
    }

    /**
     * Upload a file as an attachment to a Rally artifact.
     * Rally requires a two-step process:
     * 1. Create AttachmentContent with base64-encoded file data
     * 2. Create Attachment linking the content to the artifact
     */
    fun uploadAttachment(artifactRef: String, filePath: Path): RallyAttachment {
        val fileName = filePath.fileName.toString()
        val fileBytes = Files.readAllBytes(filePath)
        val fileSize = fileBytes.size.toLong()
        val contentType = Files.probeContentType(filePath) ?: "application/octet-stream"
        val base64Content = Base64.getEncoder().encodeToString(fileBytes)

        // Step 1: Create AttachmentContent
        val contentUrl = buildApiUrl("attachmentcontent/create")
        val contentBody = """{"AttachmentContent":{"Content":"$base64Content"}}"""
        val contentResponse = executePost(contentUrl, contentBody)
        handleResponse(contentResponse)

        val contentJson = JsonParser.parseString(contentResponse.body()).asJsonObject
        val contentResult = contentJson.getAsJsonObject("CreateResult")
            ?: throw RallyApiException("Unexpected response: missing CreateResult for AttachmentContent")
        val contentErrors = contentResult.getAsJsonArray("Errors")
        if (contentErrors != null && contentErrors.size() > 0) {
            throw RallyApiException("Failed to create attachment content: ${contentErrors.joinToString()}")
        }
        val contentRef = contentResult.getAsJsonObject("Object")?.get("_ref")?.asString
            ?: throw RallyApiException("No _ref in AttachmentContent create response")

        // Step 2: Create Attachment linking content to artifact
        val attachFields = mutableMapOf<String, Any>(
            "Content" to contentRef,
            "Name" to fileName,
            "ContentType" to contentType,
            "Size" to fileSize,
            "Artifact" to artifactRef
        )
        val attachUrl = buildApiUrl("attachment/create")
        val attachBody = """{"Attachment":${gson.toJson(attachFields)}}"""
        val attachResponse = executePost(attachUrl, attachBody)
        handleResponse(attachResponse)

        val attachJson = JsonParser.parseString(attachResponse.body()).asJsonObject
        val attachResult = attachJson.getAsJsonObject("CreateResult")
            ?: throw RallyApiException("Unexpected response: missing CreateResult for Attachment")
        val attachErrors = attachResult.getAsJsonArray("Errors")
        if (attachErrors != null && attachErrors.size() > 0) {
            throw RallyApiException("Failed to create attachment: ${attachErrors.joinToString()}")
        }
        val attachObj = attachResult.getAsJsonObject("Object")
            ?: throw RallyApiException("Unexpected response: missing Object in Attachment CreateResult")
        return gson.fromJson(attachObj, RallyAttachment::class.java)
    }
}
