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
    private val serverUrl: String,
    private val apiKey: String
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
            "Iteration"
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
    private fun buildQuery(query: String?, pageSize: Int = DEFAULT_PAGE_SIZE, start: Int = 1): String {
        val params = mutableListOf<String>()

        if (query != null && query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
            params.add("query=$encodedQuery")
        }

        params.add("pagesize=${pageSize.coerceIn(1, MAX_PAGE_SIZE)}")
        params.add("start=$start")
        params.add("fetch=${COMMON_FIELDS.joinToString(",")}")

        return params.joinToString("&")
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
        val url = buildApiUrl("user") + "?fetch=UserName,DisplayName,EmailAddress&pagesize=1"
        val response = executeGet(url)
        handleResponse(response)

        val jsonElement = JsonParser.parseString(response.body())
        val queryResult = jsonElement.asJsonObject
            .getAsJsonObject("QueryResult")

        val results = queryResult.getAsJsonArray("Results")
        if (results.size() == 0) {
            throw RallyApiException("No user found for the provided API key")
        }

        return gson.fromJson(results.get(0), RallyUser::class.java)
    }

    /**
     * Query User Stories (HierarchicalRequirement)
     */
    fun queryUserStories(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyUserStory> {
        val url = buildApiUrl("hierarchicalrequirement") + "?" + buildQuery(query, pageSize)
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
        val url = buildApiUrl("defect") + "?" + buildQuery(query, pageSize)
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
        val url = buildApiUrl("task") + "?" + buildQuery(query, pageSize)
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

        // Query user stories
        try {
            results.addAll(queryUserStories(query, pageSize))
        } catch (e: RallyApiException) {
            // Log but continue
        }

        // Query defects
        try {
            results.addAll(queryDefects(query, pageSize))
        } catch (e: RallyApiException) {
            // Log but continue
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
}
