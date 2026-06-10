package com.github.halmuratuyghur.rally.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.net.InetSocketAddress
import java.net.ProxySelector
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
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Client for interacting with Rally WSAPI 2.0
 */
class RallyApiClient(
    val serverUrl: String,
    private val apiKey: String
) {
    /**
     * Compare credentials/scope against a settings snapshot without exposing the raw key.
     * Used by the tool window panel to decide whether to recycle or rebuild the client
     * when the user rotates their API key or changes server/workspace.
     */
    fun matchesSettings(serverUrl: String, apiKey: String, workspaceRef: String?): Boolean {
        return this.serverUrl == serverUrl &&
                this.apiKey == apiKey &&
                this.workspaceRef == workspaceRef
    }

    /** Bounded thread pool for API operations (daemon threads so IDE shutdown isn't blocked). */
    val apiExecutor: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "rally-api-worker").apply { isDaemon = true }
    }

    /**
     * True if this client's executor is still accepting work. Use this from UI
     * code that wants to bail out before submitting tasks to a client whose
     * thread pool has already been shut down by a settings change or panel
     * disposal — otherwise the task throws RejectedExecutionException.
     */
    val isAlive: Boolean get() = !apiExecutor.isShutdown

    // HttpConfigurable is deprecated in 2025.x+ in favor of JdkProxyProvider, but that
    // class does not exist in 2024.1/2024.2. Since the plugin still supports sinceBuild=241,
    // we keep HttpConfigurable (present and functional through 261) and suppress the warning.
    // Migrate to JdkProxyProvider.getInstance().proxySelector once the floor is raised to 243+.
    @Suppress("DEPRECATION")
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .version(HttpClient.Version.HTTP_2)
        .apply {
            // Respect IDE proxy settings (Settings → Appearance & Behavior → System Settings → HTTP Proxy)
            try {
                val httpConfigurable = com.intellij.util.net.HttpConfigurable.getInstance()
                if (httpConfigurable.USE_HTTP_PROXY && !httpConfigurable.PROXY_HOST.isNullOrBlank()) {
                    proxy(ProxySelector.of(InetSocketAddress(httpConfigurable.PROXY_HOST, httpConfigurable.PROXY_PORT)))
                }
            } catch (_: Exception) {
                // IDE proxy API not available — use direct connection
            }
        }
        .build()

    private val gson = Gson()

    /** Pre-computed normalized server URL (computed once at construction time). */
    private val normalizedServerUrl: String = run {
        var url = serverUrl.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        url
    }

    /**
     * Public accessor for the normalized base URL — already trimmed of trailing
     * slashes, with an https:// scheme prepended if the user typed a bare host.
     * Use this in place of `client.serverUrl.trimEnd('/')` so every call site
     * agrees on what the canonical URL looks like.
     */
    val webBaseUrl: String get() = normalizedServerUrl

    /** Pre-computed allowed host and scheme for security validation. */
    private val allowedHost: String
    private val allowedScheme: String
    init {
        val uri = try { URI(normalizedServerUrl) } catch (_: Exception) { null }
        allowedHost = uri?.host?.lowercase()
            ?: throw RallySecurityException("Cannot determine host from server URL: $normalizedServerUrl")
        allowedScheme = uri.scheme?.lowercase()
            ?: throw RallySecurityException("Cannot determine scheme from server URL: $normalizedServerUrl")
        if (allowedScheme == "http") {
            // The API key travels in the zsessionid header on every request. Over plain HTTP
            // it is exposed to anyone on the network path. We don't hard-fail (some on-prem
            // Rally setups front it with an internal HTTP gateway) but surface the risk.
            LOG.warn("Rally server URL uses plaintext http:// — the API key is transmitted unencrypted. Use https:// if possible.")
        }
    }

    /**
     * Validate that a URL targets the configured Rally server on the configured scheme.
     * Relative URLs (null host) pass through safely — they resolve against normalizedServerUrl.
     * Absolute URLs must match both host and scheme so a poisoned `_ref` can't leak the
     * API key to another host or downgrade https → http.
     *
     * `internal` (not private) so the security contract is unit-testable from the same module.
     */
    internal fun requireSameHost(url: String) {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            throw RallySecurityException("Malformed URL rejected: $url")
        }
        val targetHost = uri.host?.lowercase() ?: return  // relative — safe
        if (targetHost != allowedHost) {
            throw RallySecurityException(
                "Security: refusing request to external host '$targetHost' (expected '$allowedHost')"
            )
        }
        val targetScheme = uri.scheme?.lowercase()
        if (targetScheme != null && targetScheme != allowedScheme) {
            throw RallySecurityException(
                "Security: refusing request with scheme '$targetScheme' (expected '$allowedScheme')"
            )
        }
    }

    // ── Cache ────────────────────────────────────────────────────

    private data class CacheEntry<T>(val data: T, val timestamp: Long)

    /** LRU query cache: access-ordered LinkedHashMap with automatic eldest-entry eviction. */
    private val maxQueryCacheSize = 200
    private val queryCache: MutableMap<String, CacheEntry<Any>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry<Any>>(maxQueryCacheSize * 4 / 3 + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<Any>>?): Boolean {
                return size > maxQueryCacheSize
            }
        }
    )
    /**
     * Access-ordered LinkedHashMap for true LRU semantics: get() reorders the entry
     * to the back of the iteration, so eviction removes whichever entry was used
     * least recently. The previous ConcurrentHashMap had no defined iteration order
     * and the old "take first 25% of keys" eviction was effectively random.
     */
    private val imageCache: MutableMap<String, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {}
    )
    private val imageCacheBytes = java.util.concurrent.atomic.AtomicLong(0)

    /** Default TTL for query caches (2 minutes). */
    private val queryTtlMs = 2 * 60 * 1000L

    /** Maximum image cache size in bytes (10 MB). */
    private val maxImageCacheBytes = 10L * 1024 * 1024

    /** Do not keep very large attachment images in memory between selections. */
    private val maxCacheableImageBytes = 1L * 1024 * 1024

    /** Extended TTL for bulk mode (null = use default). */
    @Volatile private var bulkModeTtlMs: Long? = null

    /** Enter bulk mode: extends cache TTL to prevent expiry during long exports. */
    fun enterBulkMode(ttlMinutes: Int = 15) {
        bulkModeTtlMs = ttlMinutes * 60 * 1000L
    }

    /** Exit bulk mode: restores default cache TTL. */
    fun exitBulkMode() {
        bulkModeTtlMs = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getCached(key: String): T? {
        synchronized(queryCache) {
            val entry = queryCache[key] ?: return null
            val ttl = bulkModeTtlMs ?: queryTtlMs
            if (System.currentTimeMillis() - entry.timestamp > ttl) {
                queryCache.remove(key)
                return null
            }
            return entry.data as? T
        }
    }

    private fun <T : Any> putCache(key: String, data: T) {
        synchronized(queryCache) {
            queryCache[key] = CacheEntry(data, System.currentTimeMillis())
        }
    }



    /**
     * Clear all caches including images.
     * Call on manual Refresh to get fully fresh data from Rally.
     */
    fun clearCache() {
        synchronized(queryCache) { queryCache.clear() }
        synchronized(imageCache) {
            imageCache.clear()
            imageCacheBytes.set(0)
        }
    }

    /**
     * Clear only artifact list caches (keeps detail/description/image caches).
     * Call after local state changes (create, update) where only the list is stale
     * but detail data we just wrote is still correct.
     */
    fun clearArtifactCache() {
        // Must synchronize on the map for iteration — Collections.synchronizedMap
        // only guards individual operations, and with accessOrder=true even get() mutates.
        // Note: detail-level caches (tasksForWp:, testcases:, attachments:, desc:, ...)
        // are intentionally left alone — they're keyed per-workproduct and unrelated
        // to list-level mutations.
        synchronized(queryCache) {
            val iter = queryCache.keys.iterator()
            while (iter.hasNext()) {
                val key = iter.next()
                // "artifact:" (singular) is the per-FormattedID lookup cache used by
                // getArtifactByFormattedId; it must be evicted on list-level mutations
                // too or an export-after-edit re-fetch returns stale field values.
                // Note "artifacts:".startsWith("artifact:") is false, so the plural
                // list cache is matched only by its own prefix below.
                // currentIteration: is NOT evicted here — which sprint is current is a
                // function of today's date + the sprint calendar; no artifact mutation
                // can change it. clearCache() (manual Refresh) still evicts it.
                // Only prefixes actually written by putCache are listed here.
                if (key.startsWith("artifact:") || key.startsWith("artifacts:") ||
                    key.startsWith("stories:") || key.startsWith("defects:") ||
                    key.startsWith("alltestcases:") || key.startsWith("search:")) {
                    iter.remove()
                }
            }
        }
    }

    /**
     * Invalidate the cached task list for a specific work product. Call after
     * createTask so the next queryTasksForWorkProduct re-fetches from Rally
     * instead of returning a stale list missing the new task.
     */
    fun clearTasksCache(workProductRef: String) {
        synchronized(queryCache) {
            queryCache.remove("tasksForWp:$workProductRef")
        }
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(RallyApiClient::class.java)

        // Pre-allocated TypeToken objects to avoid repeated anonymous class creation
        private val TYPE_USER_STORIES = object : TypeToken<RallyQueryResult<RallyUserStory>>() {}.type
        private val TYPE_DEFECTS = object : TypeToken<RallyQueryResult<RallyDefect>>() {}.type
        private val TYPE_TEST_CASES = object : TypeToken<RallyQueryResult<RallyTestCase>>() {}.type
        private val TYPE_TASKS = object : TypeToken<RallyQueryResult<RallyTaskItem>>() {}.type
        private val TYPE_ITERATIONS = object : TypeToken<RallyQueryResult<RallyIteration>>() {}.type
        private val TYPE_PROJECTS = object : TypeToken<RallyQueryResult<RallyProject>>() {}.type
        private val TYPE_ATTACHMENTS = object : TypeToken<RallyQueryResult<RallyAttachment>>() {}.type
        private val TYPE_TEST_STEPS = object : TypeToken<RallyQueryResult<RallyTestCaseStep>>() {}.type

        /**
         * Escape a value for use inside Rally WSAPI query strings.
         * Strips quotes and backslashes that could break query syntax.
         */
        fun escapeQueryValue(value: String): String =
            value.replace("\\", "").replace("\"", "")

        /**
         * Decode an HTTP response body, gunzipping when Content-Encoding says gzip.
         * JDK HttpClient neither requests nor decompresses gzip on its own, so the
         * request side sends Accept-Encoding: gzip and this is the matching decode.
         * Servers/proxies that ignore or strip the header fall through to plain UTF-8.
         * Public so unit tests can exercise it without constructing a client.
         * If the encoding is declared as gzip but decompression fails (e.g. a proxy/LB
         * mislabelled a plain error page), falls back to raw UTF-8 so the status-code
         * error path remains meaningful rather than surfacing a raw ZipException.
         */
        fun decodeBody(bytes: ByteArray, contentEncoding: String?): String {
            if (!"gzip".equals(contentEncoding, ignoreCase = true)) return bytes.toString(Charsets.UTF_8)
            return try {
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
            } catch (e: java.io.IOException) {
                // A proxy/LB can mislabel a plain (often error) body as gzip. Fall back
                // to the raw bytes so the status-code error path stays meaningful
                // instead of surfacing a raw ZipException.
                LOG.warn("Response declared Content-Encoding: gzip but failed to decompress; using raw body", e)
                bytes.toString(Charsets.UTF_8)
            }
        }

        private const val API_VERSION = "v2.0"
        private const val DEFAULT_PAGE_SIZE = 200
        private const val MAX_PAGE_SIZE = 2000
        private const val ZSESSION_HEADER = "zsessionid"
        private const val MAX_RETRIES = 3
        /**
         * Hard cap on a single backoff sleep. The API executor has only 4 worker threads,
         * so a 30-second Retry-After on one call would otherwise stall a quarter of the
         * pool for the full interval. If the server asks for longer, we still honor it
         * across retries but in smaller chunks, so other work can interleave.
         */
        private const val MAX_RETRY_SLEEP_MS = 2_000L

        /**
         * Hard ceiling on the TOTAL delay honored for a single retry attempt. Even if
         * the server sends a large Retry-After, we never park a worker for more than
         * this — the API executor has only 4 threads, so a handful of long Retry-After
         * responses (e.g. during a bulk export) could otherwise starve the whole pool
         * and freeze the tool window. MAX_RETRY_SLEEP_MS bounds each individual sleep
         * for interrupt/dispose responsiveness; this bounds the cumulative wait.
         */
        private const val MAX_RETRY_DELAY_MS = 30_000L

        /**
         * Hard cap on attachment upload size. Each upload base64-encodes the file
         * (~4N/3 bytes) and then wraps it in a JSON body, so peak heap is roughly
         * 2-3x the file size. 25 MB caps the worst case at ~75 MB, well within a
         * typical IDE heap, and still covers anything users would reasonably
         * attach to a Rally artifact.
         */
        private const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
        private val RETRYABLE_STATUS_CODES = setOf(429, 502, 503, 504)

        // Fields for list queries (lightweight — no Description)
        private val LIST_FIELDS = listOf(
            "FormattedID",
            "Name",
            "ObjectID",
            "CreationDate",
            "LastUpdateDate",
            "Owner",
            "ScheduleState",
            "State",
            "Project",
            "Iteration",
            "PlanEstimate",
            "Severity",
            "Priority",
            "Environment",
            "Blocked",
            "BlockedReason",
            "Release",
            "Ready"
        )

        // Fields for detail queries (includes Description)
        private val DETAIL_FIELDS = LIST_FIELDS + "Description"

        // Fields for test case list queries
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
    }

    /**
     * Build the base API URL
     */
    private fun buildApiUrl(endpoint: String): String {
        return "$normalizedServerUrl/slm/webservice/$API_VERSION/$endpoint"
    }

    /**
     * Execute HTTP GET request with retry for transient errors (429, 502, 503, 504).
     */
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
            return "$normalizedServerUrl/slm/webservice/$API_VERSION$trimmed"
        }
        // Just a number
        return "$normalizedServerUrl/slm/webservice/$API_VERSION/$type/$trimmed"
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

    /**
     * Fetch all pages for a query, up to maxResults total items.
     * Rally paginates via start/pageSize params; this loops until all results are fetched.
     */
    private fun <T> queryAllPages(
        endpoint: String,
        typeToken: java.lang.reflect.Type,
        query: String?,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxResults: Int = MAX_PAGE_SIZE,
        order: String? = "LastUpdateDate DESC",
        fields: List<String> = LIST_FIELDS,
        workspace: String?,
        project: String?
    ): List<T> {
        val allResults = mutableListOf<T>()
        var start = 1

        do {
            val url = buildApiUrl(endpoint) + "?" +
                    buildQuery(query, pageSize, start, workspace, project, order, fields)
            val response = executeGet(url)
            handleResponse(response)
            val result: RallyQueryResult<T> = gson.fromJson(response.body(), typeToken)
            if (!result.queryResult.errors.isNullOrEmpty()) {
                throw RallyApiException("Rally query error: ${result.queryResult.errors.joinToString("; ")}")
            }
            allResults.addAll(result.queryResult.safeResults)
            val effectivePageSize = result.queryResult.pageSize.takeIf { it > 0 } ?: pageSize
            start += effectivePageSize
        } while (allResults.size < result.queryResult.totalResultCount
            && result.queryResult.safeResults.isNotEmpty()
            && allResults.size < maxResults)

        return allResults
    }

    /**
     * Query User Stories (HierarchicalRequirement)
     */
    fun queryUserStories(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE, maxResults: Int = MAX_PAGE_SIZE): List<RallyUserStory> {
        // Snapshot scope once so a concurrent project-dropdown change can't split the
        // cache key and the actual query URL across different workspace/project values.
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "stories:${query}|${pageSize}|${maxResults}|${ws}|${pr}"
        getCached<List<RallyUserStory>>(cacheKey)?.let { return it }

        val results: List<RallyUserStory> = queryAllPages(
            "hierarchicalrequirement", TYPE_USER_STORIES, query, pageSize, maxResults,
            workspace = ws, project = pr
        )
        putCache(cacheKey, results)
        return results
    }

    /**
     * Query Defects
     */
    fun queryDefects(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE, maxResults: Int = MAX_PAGE_SIZE): List<RallyDefect> {
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "defects:${query}|${pageSize}|${maxResults}|${ws}|${pr}"
        getCached<List<RallyDefect>>(cacheKey)?.let { return it }

        val results: List<RallyDefect> = queryAllPages(
            "defect", TYPE_DEFECTS, query, pageSize, maxResults,
            workspace = ws, project = pr
        )
        putCache(cacheKey, results)
        return results
    }

    /**
     * Fetch the Description field for a single artifact by ref URL.
     * Returns the HTML description or null.
     */
    fun fetchDescription(artifactRef: String): String? {
        val cacheKey = "desc:$artifactRef"
        // An empty cached value means "known to have no description" — avoids re-fetching
        // on every detail-panel open for description-less tickets.
        val cached = getCached<String>(cacheKey)
        if (cached != null) return cached.ifEmpty { null }

        val url = "$artifactRef?fetch=Description"
        return try {
            val response = executeGet(url)
            handleResponse(response)
            val root = JsonParser.parseString(response.body()).asJsonObject
            // Rally wraps in the type name (HierarchicalRequirement, Defect, Task, etc.)
            val obj = root.entrySet().firstOrNull()?.value?.asJsonObject
            val descElement = obj?.get("Description")
            val desc = if (descElement != null && !descElement.isJsonNull) descElement.asString else null
            putCache(cacheKey, desc ?: "")
            desc
        } catch (e: RallyAuthenticationException) {
            // Surface auth failures so the UI can show an actionable message instead
            // of a generic "No description" — every other detail-panel call hits the
            // same credentials, so this almost always means the API key is bad/expired.
            LOG.warn("Auth failure fetching description for $artifactRef", e)
            throw e
        } catch (e: RallyApiException) {
            LOG.warn("API error fetching description for $artifactRef: ${e.message}")
            null
        } catch (e: Exception) {
            LOG.warn("Unexpected error fetching description for $artifactRef", e)
            null
        }
    }

    /**
     * Get artifact by FormattedID (e.g., "S-1234", "DE5678", "TA9012")
     */
    fun getArtifactByFormattedId(formattedId: String): RallyArtifact? {
        // Scope the cache key by workspace/project: a project switch mutates projectRef
        // on the same client, and FormattedID lookups are scoped server-side, so an
        // unscoped key could return an out-of-scope artifact after a switch.
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "artifact:$formattedId|$ws|$pr"
        getCached<RallyArtifact>(cacheKey)?.let { return it }

        // Determine artifact type from FormattedID prefix
        val (endpoint, type) = when {
            formattedId.startsWith("S-", ignoreCase = true) ||
            formattedId.startsWith("US", ignoreCase = true) ->
                "hierarchicalrequirement" to TYPE_USER_STORIES

            formattedId.startsWith("DE", ignoreCase = true) ->
                "defect" to TYPE_DEFECTS

            formattedId.startsWith("TA", ignoreCase = true) ->
                "task" to TYPE_TASKS

            formattedId.startsWith("TC", ignoreCase = true) ->
                "testcase" to TYPE_TEST_CASES

            else -> return null
        }

        val safeId = escapeQueryValue(formattedId)
        val query = "(FormattedID = \"$safeId\")"
        // Pass workspace/project so multi-workspace Rally deployments don't return a
        // same-ID artifact from a different workspace.
        val url = buildApiUrl(endpoint) + "?" + buildQuery(
            query,
            pageSize = 1,
            workspace = ws,
            project = pr,
            fields = DETAIL_FIELDS
        )

        return try {
            val response = executeGet(url)
            handleResponse(response)

            val result: RallyQueryResult<out RallyArtifact> = gson.fromJson(response.body(), type)
            val artifact = result.queryResult.safeResults.firstOrNull()
            if (artifact != null) putCache(cacheKey, artifact)
            artifact
        } catch (e: RallyAuthenticationException) {
            // Don't mask a bad/expired key as "not found" — let callers surface the
            // real cause (e.g. export shows an auth error instead of a misleading
            // "artifact not found").
            throw e
        } catch (e: RallySecurityException) {
            // A poisoned host/scheme is a hard security failure, not a miss.
            throw e
        } catch (e: RallyApiException) {
            LOG.warn("API error fetching artifact $formattedId: ${e.message}")
            null
        } catch (e: Exception) {
            LOG.warn("Unexpected error fetching artifact $formattedId", e)
            null
        }
    }

    /**
     * Query all artifacts (User Stories and Defects combined)
     * This is useful for the main task browser
     */
    /**
     * Query all artifacts (User Stories and Defects combined).
     * @param scope Optional scope hint: "User Stories" fetches only stories, "Defects" fetches only defects,
     *              anything else (null, "All Tickets", "My Tickets", etc.) fetches both.
     * @param maxResults Maximum total items to return per type. Defaults to MAX_PAGE_SIZE (2000).
     */
    fun queryAllArtifacts(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE, scope: String? = null, maxResults: Int = MAX_PAGE_SIZE): List<RallyArtifact> {
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "artifacts:${query}|${pageSize}|${maxResults}|${scope}|${ws}|${pr}"
        getCached<List<RallyArtifact>>(cacheKey)?.let { return it }

        val results = mutableListOf<RallyArtifact>()
        val errors = mutableListOf<String>()

        val fetchStories = scope != "Defects"
        val fetchDefects = scope != "User Stories"

        // Query user stories and defects sequentially to avoid apiExecutor self-deadlock
        // (this method is often called from an apiExecutor thread; submitting inner tasks
        // to the same pool and blocking on .get() can exhaust the fixed 4-thread pool)
        if (fetchStories) {
            try {
                results.addAll(queryUserStories(query, pageSize, maxResults))
            } catch (e: Exception) {
                errors.add("UserStories: ${e.message}")
            }
        }

        if (fetchDefects) {
            try {
                results.addAll(queryDefects(query, pageSize, maxResults))
            } catch (e: Exception) {
                errors.add("Defects: ${e.message}")
            }
        }

        // If all queries failed, throw so the UI can show the error
        if (results.isEmpty() && errors.isNotEmpty()) {
            throw RallyApiException("Query failed - ${errors.joinToString("; ")}")
        }

        // Partial failure: at least one type returned but at least one failed.
        // Surface via IDE notification so the user knows the list is incomplete,
        // and DO NOT cache the partial result — otherwise the truncated list would
        // be served silently (with no further warning) for the full TTL, even after
        // Rally recovers. The next call retries the failed type from scratch.
        val sorted = results.sortedByDescending { it.lastUpdateDate }
        if (errors.isNotEmpty()) {
            LOG.warn("queryAllArtifacts partial failure: ${errors.joinToString("; ")}")
            notifyPartialFailure(errors)
            return sorted
        }

        putCache(cacheKey, sorted)
        return sorted
    }

    private fun notifyPartialFailure(errors: List<String>) {
        try {
            val group = com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Rally")
            group.createNotification(
                "Rally — partial query failure",
                "Some results couldn't be loaded:\n${errors.joinToString("\n")}",
                com.intellij.notification.NotificationType.WARNING
            ).notify(null)
        } catch (e: Exception) {
            // Notification group not registered or notification API unavailable —
            // the WARN log above is the fallback.
            LOG.warn("Failed to dispatch partial-failure notification", e)
        }
    }

    /**
     * Search artifacts server-side using Rally "contains" query.
     * Searches both Name and FormattedID fields across user stories and defects.
     * @param searchText The text to search for
     * @param scope Optional scope hint: "User Stories" or "Defects" to limit search
     */
    fun searchArtifacts(
        searchText: String,
        scope: String? = null,
        pageSize: Int = 50,
        maxResults: Int = 100
    ): List<RallyArtifact> {
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "search:${searchText}|${scope}|${pageSize}|${maxResults}|${ws}|${pr}"
        getCached<List<RallyArtifact>>(cacheKey)?.let { return it }

        val safeText = escapeQueryValue(searchText)
        val query = "((Name contains \"$safeText\") OR (FormattedID = \"$safeText\"))"

        if (scope == "Test Cases") {
            val tcResults: List<RallyArtifact> = queryAllTestCases(query, pageSize, maxResults)
            val sorted = tcResults.sortedByDescending { it.lastUpdateDate }
            putCache(cacheKey, sorted)
            return sorted
        }

        val results = mutableListOf<RallyArtifact>()
        val errors = mutableListOf<String>()
        val fetchStories = scope != "Defects"
        val fetchDefects = scope != "User Stories"

        if (fetchStories) {
            try { results.addAll(queryUserStories(query, pageSize, maxResults)) }
            catch (e: Exception) { errors.add("UserStories: ${e.message}") }
        }
        if (fetchDefects) {
            try { results.addAll(queryDefects(query, pageSize, maxResults)) }
            catch (e: Exception) { errors.add("Defects: ${e.message}") }
        }

        if (results.isEmpty() && errors.isNotEmpty()) {
            throw RallyApiException("Search failed - ${errors.joinToString("; ")}")
        }

        val sorted = results.sortedByDescending { it.lastUpdateDate }
        putCache(cacheKey, sorted)
        return sorted
    }

    /**
     * Query all test cases in the current workspace/project.
     * Used when scope is "Test Cases".
     */
    fun queryAllTestCases(query: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE, maxResults: Int = MAX_PAGE_SIZE): List<RallyTestCase> {
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "alltestcases:${query}|${pageSize}|${maxResults}|${ws}|${pr}"
        getCached<List<RallyTestCase>>(cacheKey)?.let { return it }

        val results: List<RallyTestCase> = queryAllPages(
            "testcase", TYPE_TEST_CASES, query, pageSize, maxResults,
            order = "LastUpdateDate DESC",
            fields = TC_LIST_FIELDS,
            workspace = ws,
            project = pr
        )
        putCache(cacheKey, results)
        return results
    }

    /**
     * Build web URL for viewing an artifact in Rally.
     * Rally web UI URLs require the project OID: /#/<projectOID>d/detail/<type>/<objectID>
     */
    fun buildWebUrl(artifact: RallyArtifact): String {
        val baseUrl = normalizedServerUrl
        val objectId = artifact.objectID ?: return baseUrl

        val detailPage = when (artifact.type) {
            "HierarchicalRequirement" -> "userstory"
            "Defect" -> "defect"
            "Task" -> "task"
            "TestCase" -> "testcase"
            else -> "detail"
        }

        // Extract project OID from the artifact's project ref
        val projectOid = when (artifact) {
            is RallyUserStory -> artifact.project?.ref
            is RallyDefect -> artifact.project?.ref
            is RallyTestCase -> artifact.project?.ref
            else -> null
        }?.trimEnd('/')?.substringAfterLast('/')

        return if (projectOid != null) {
            "$baseUrl/#/${projectOid}d/detail/$detailPage/$objectId"
        } else {
            "$baseUrl/#/detail/$detailPage/$objectId"
        }
    }

    /**
     * Execute HTTP POST request with retry for transient errors.
     */
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

    /**
     * Send an HTTP request with automatic retry + exponential backoff for transient errors.
     * Retries up to MAX_RETRIES times for status codes 429, 502, 503, 504.
     * Honors the Retry-After header when present (capped at 30s).
     */
    private fun <T> executeWithRetry(
        request: HttpRequest,
        bodyHandler: HttpResponse.BodyHandler<T>
    ): HttpResponse<T> {
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            val response = try {
                httpClient.send(request, bodyHandler)
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    sleepForRetry(backoffMs(attempt))
                    continue
                }
                throw RallyConnectionException("Failed to connect to Rally server: ${e.message}", e)
            }

            if (response.statusCode() !in RETRYABLE_STATUS_CODES || attempt == MAX_RETRIES) {
                return response
            }

            // Respect Retry-After header if present, otherwise use exponential backoff.
            // The total honored delay is capped at MAX_RETRY_DELAY_MS so a single
            // worker thread can't be parked indefinitely on a shared 4-thread pool,
            // and each individual sleep is capped at MAX_RETRY_SLEEP_MS for interrupt
            // responsiveness (see sleepForRetry).
            val retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(-1)
            val delayMs = (if (retryAfter > 0) (retryAfter * 1000) else backoffMs(attempt))
                .coerceAtMost(MAX_RETRY_DELAY_MS)
            sleepForRetry(delayMs)
        }
        // Should not reach here, but satisfy the compiler. Don't synthesize a fake
        // cause when we never captured a real one — leave cause null instead.
        throw lastException?.let { RallyConnectionException("Failed after $MAX_RETRIES retries", it) }
            ?: RallyConnectionException("Failed after $MAX_RETRIES retries")
    }

    private fun backoffMs(attempt: Int): Long {
        val base = (1000L shl attempt).coerceAtMost(8000)
        val jitter = (Math.random() * base * 0.3).toLong()  // ±30% jitter
        return base + jitter
    }

    /**
     * Sleep for the (already total-capped) retry delay in MAX_RETRY_SLEEP_MS chunks.
     * The caller clamps the total at MAX_RETRY_DELAY_MS; here we just keep each
     * individual Thread.sleep short — that bound matters for the 4-thread API
     * executor: if anything wants to kick the worker (interrupts, dispose), it
     * never waits more than a chunk for that to be observed.
     */
    private fun sleepForRetry(delayMs: Long) {
        if (delayMs <= 0) return
        var remaining = delayMs
        try {
            while (remaining > 0) {
                val chunk = remaining.coerceAtMost(MAX_RETRY_SLEEP_MS)
                Thread.sleep(chunk)
                remaining -= chunk
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RallyConnectionException("Interrupted during retry backoff", ie)
        }
    }

    /**
     * Allowlist of Rally artifact types we accept on the update/create paths.
     * The type name is used as a JSON key in the request body, so anything outside
     * this list could rewrite the structure if it contained a quote or brace.
     * Keep this in sync with Rally's WSAPI type names.
     */
    private val ALLOWED_ARTIFACT_TYPES = setOf(
        "HierarchicalRequirement", "Defect", "Task", "TestCase", "TestCaseStep",
        "Attachment", "AttachmentContent"
    )

    private fun requireValidArtifactType(artifactType: String) {
        require(artifactType in ALLOWED_ARTIFACT_TYPES) {
            "Unknown Rally artifact type: $artifactType"
        }
    }

    /**
     * Update the state of an artifact.
     * User Stories/Defects use ScheduleState, Tasks use State.
     */
    fun updateArtifactState(artifactRef: String, artifactType: String, newState: String) {
        requireValidArtifactType(artifactType)
        val stateField = if (artifactType == "Task") "State" else "ScheduleState"
        val body = gson.toJson(mapOf(artifactType to mapOf(stateField to newState)))
        val response = executePost(artifactRef, body)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val result = json.getAsJsonObject("OperationResult")
            ?: throw RallyApiException("Unexpected response: missing OperationResult")
        val errors = result.getAsJsonArray("Errors")
        if (errors != null && errors.size() > 0) {
            throw RallyApiException("Failed to update state: ${errors.joinToString()}")
        }
    }

    /**
     * Update the Owner of a Rally artifact.
     * @param artifactRef Full API URL ref of the artifact
     * @param artifactType Rally type name (e.g., "HierarchicalRequirement", "Defect")
     * @param ownerRef Full API URL ref of the user
     */
    fun updateArtifactOwner(artifactRef: String, artifactType: String, ownerRef: String) {
        requireValidArtifactType(artifactType)
        val body = gson.toJson(mapOf(artifactType to mapOf("Owner" to ownerRef)))
        val response = executePost(artifactRef, body)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val result = json.getAsJsonObject("OperationResult")
            ?: throw RallyApiException("Unexpected response: missing OperationResult")
        val errors = result.getAsJsonArray("Errors")
        if (errors != null && errors.size() > 0) {
            throw RallyApiException("Failed to update owner: ${errors.joinToString()}")
        }
    }

    /**
     * Update a single field on a Rally artifact.
     * @param artifactRef Full API URL ref of the artifact
     * @param artifactType Rally type name (e.g., "HierarchicalRequirement", "Defect", "Task")
     * @param field The field name to update (e.g., "PlanEstimate", "Name", "Description")
     * @param value The new value (String, Number, or null to clear)
     */
    fun updateArtifactField(artifactRef: String, artifactType: String, field: String, value: Any?) {
        requireValidArtifactType(artifactType)
        val fieldMap = if (value != null) mapOf(field to value) else mapOf(field to com.google.gson.JsonNull.INSTANCE)
        val body = gson.toJson(mapOf(artifactType to fieldMap))
        val response = executePost(artifactRef, body)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val result = json.getAsJsonObject("OperationResult")
            ?: throw RallyApiException("Unexpected response: missing OperationResult")
        val errors = result.getAsJsonArray("Errors")
        if (errors != null && errors.size() > 0) {
            throw RallyApiException("Failed to update $field: ${errors.joinToString()}")
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

        val result: RallyQueryResult<RallyIteration> = gson.fromJson(response.body(), TYPE_ITERATIONS)
        val iteration = result.queryResult.safeResults.firstOrNull()
        if (iteration != null) putCache(cacheKey, iteration)
        return iteration
    }

    /**
     * Get all artifacts in a specific iteration by name.
     */
    fun queryIterationArtifacts(iterationName: String, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyArtifact> {
        val query = "(Iteration.Name = \"${escapeQueryValue(iterationName)}\")"
        return queryAllArtifacts(query, pageSize)
    }

    /**
     * Query projects in the configured workspace.
     * Returns only Open (active) projects, sorted alphabetically by name.
     */
    fun queryProjects(pageSize: Int = MAX_PAGE_SIZE): List<RallyProject> {
        val ws = workspaceRef
        val cacheKey = "projects:${ws}|${pageSize}"
        getCached<List<RallyProject>>(cacheKey)?.let { return it }

        val query = "(State = \"Open\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("project") +
                "?query=$encodedQuery&fetch=Name,ObjectID,_ref,State&pagesize=$pageSize&order=Name"

        if (!ws.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val result: RallyQueryResult<RallyProject> = gson.fromJson(response.body(), TYPE_PROJECTS)
        val sorted = result.queryResult.safeResults.sortedBy { it.name?.lowercase() }
        putCache(cacheKey, sorted)
        return sorted
    }

    /**
     * Query iterations (sprints) in the configured workspace/project.
     * When a project is selected, filters iterations to that project only.
     * Returns iterations sorted by StartDate descending (most recent first).
     */
    fun queryIterations(pageSize: Int = MAX_PAGE_SIZE): List<RallyIteration> {
        val ws = workspaceRef
        val pr = projectRef
        val cacheKey = "iterations:${ws}|${pr}|${pageSize}"
        getCached<List<RallyIteration>>(cacheKey)?.let { return it }

        var url = buildApiUrl("iteration") +
                "?fetch=Name,ObjectID,_ref,StartDate,EndDate,PlannedVelocity,Project,State&pagesize=$pageSize" +
                "&order=${URLEncoder.encode("StartDate DESC,EndDate DESC,ObjectID", StandardCharsets.UTF_8)}"

        if (!ws.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}"
        }
        // Scope via URL params only. An earlier (Project=...) query filter contradicted
        // projectScopeUp/Down and silently pinned results to one project. Rely on the
        // canonical scope params so the chosen project plus its descendants are covered.
        if (!pr.isNullOrBlank()) {
            url += "&project=${URLEncoder.encode(normalizeRef("project", pr), StandardCharsets.UTF_8)}"
            url += "&projectScopeUp=true&projectScopeDown=true"
        }

        val response = executeGet(url)
        handleResponse(response)

        val result: RallyQueryResult<RallyIteration> = gson.fromJson(response.body(), TYPE_ITERATIONS)
        val iterations = result.queryResult.safeResults
        putCache(cacheKey, iterations)
        return iterations
    }

    /**
     * Query tasks linked to a work product (user story/defect).
     */
    fun queryTasksForWorkProduct(workProductRef: String, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyTaskItem> {
        val ws = workspaceRef
        // Use a distinctive prefix so clearArtifactCache can't accidentally evict
        // unrelated per-workproduct task lists when an unrelated artifact mutation
        // happens.
        val cacheKey = "tasksForWp:$workProductRef"
        getCached<List<RallyTaskItem>>(cacheKey)?.let { return it }

        val query = "(WorkProduct = \"${escapeQueryValue(workProductRef)}\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("task") +
                "?query=$encodedQuery&fetch=FormattedID,Name,State,Owner,Estimate,Actuals,ToDo,ObjectID,_ref" +
                "&pagesize=$pageSize&order=${URLEncoder.encode("FormattedID ASC", StandardCharsets.UTF_8)}"

        if (!ws.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val result: RallyQueryResult<RallyTaskItem> = gson.fromJson(response.body(), TYPE_TASKS)
        putCache(cacheKey, result.queryResult.safeResults)
        return result.queryResult.safeResults
    }

    /**
     * Query test cases linked to a work product (user story/defect).
     */
    fun queryTestCases(workProductRef: String, pageSize: Int = DEFAULT_PAGE_SIZE): List<RallyTestCase> {
        val ws = workspaceRef
        val cacheKey = "testcases:$workProductRef"
        getCached<List<RallyTestCase>>(cacheKey)?.let { return it }

        val query = "(WorkProduct = \"${escapeQueryValue(workProductRef)}\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        // Description is intentionally not fetched on this list path (saves a full
        // HTML payload per linked test case); consumers needing it must re-fetch
        // by ID or via fetchDescription().
        var url = buildApiUrl("testcase") +
                "?query=$encodedQuery&fetch=FormattedID,Name,Method,Type,LastVerdict,LastRun,Owner,WorkProduct,Priority,ObjectID,_ref" +
                "&pagesize=$pageSize&order=${URLEncoder.encode("FormattedID ASC", StandardCharsets.UTF_8)}"

        if (!ws.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val result: RallyQueryResult<RallyTestCase> = gson.fromJson(response.body(), TYPE_TEST_CASES)
        putCache(cacheKey, result.queryResult.safeResults)
        return result.queryResult.safeResults
    }

    /**
     * Query test case steps for a given test case FormattedID.
     */
    fun queryTestSteps(testCaseFormattedId: String): List<RallyTestCaseStep> {
        val ws = workspaceRef
        val cacheKey = "teststeps:$testCaseFormattedId"
        getCached<List<RallyTestCaseStep>>(cacheKey)?.let { return it }

        val safeId = escapeQueryValue(testCaseFormattedId)
        val query = "(TestCase.FormattedID = \"$safeId\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("testcasestep") +
                "?query=$encodedQuery&fetch=StepIndex,Input,ExpectedResult,_ref" +
                "&pagesize=$DEFAULT_PAGE_SIZE&order=${URLEncoder.encode("StepIndex ASC", StandardCharsets.UTF_8)}"

        if (!ws.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val result: RallyQueryResult<RallyTestCaseStep> = gson.fromJson(response.body(), TYPE_TEST_STEPS)
        putCache(cacheKey, result.queryResult.safeResults)
        return result.queryResult.safeResults
    }

    /**
     * Query attachments for a given artifact FormattedID.
     */
    fun queryAttachments(artifactFormattedId: String): List<RallyAttachment> {
        val ws = workspaceRef
        val cacheKey = "attachments:$artifactFormattedId"
        getCached<List<RallyAttachment>>(cacheKey)?.let { return it }

        val safeId = escapeQueryValue(artifactFormattedId)
        val query = "(Artifact.FormattedID = \"$safeId\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("attachment") +
                "?query=$encodedQuery&fetch=Name,ContentType,Size,Description,Content,ObjectID,_ref" +
                "&pagesize=$DEFAULT_PAGE_SIZE"

        if (!ws.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val result: RallyQueryResult<RallyAttachment> = gson.fromJson(response.body(), TYPE_ATTACHMENTS)
        putCache(cacheKey, result.queryResult.safeResults)
        return result.queryResult.safeResults
    }

    /**
     * Query a single test case by FormattedID.
     */
    fun queryTestCaseByFormattedId(formattedId: String): RallyTestCase? {
        val ws = workspaceRef
        val pr = projectRef
        // Scope by workspace/project so a project switch on the same client can't
        // return an out-of-scope test case from the cache.
        val cacheKey = "testcase:id:$formattedId|$ws|$pr"
        getCached<RallyTestCase>(cacheKey)?.let { return it }

        val safeId = escapeQueryValue(formattedId)
        val query = "(FormattedID = \"$safeId\")"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)

        var url = buildApiUrl("testcase") +
                "?query=$encodedQuery&fetch=FormattedID,Name,Method,Type,LastVerdict,LastRun,Owner,WorkProduct,Description,Priority,ObjectID,_ref" +
                "&pagesize=1"

        if (!ws.isNullOrBlank()) {
            url += "&workspace=${URLEncoder.encode(normalizeRef("workspace", ws), StandardCharsets.UTF_8)}"
        }

        val response = executeGet(url)
        handleResponse(response)

        val result: RallyQueryResult<RallyTestCase> = gson.fromJson(response.body(), TYPE_TEST_CASES)
        val tc = result.queryResult.safeResults.firstOrNull()
        if (tc != null) putCache(cacheKey, tc)
        return tc
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
    fun downloadAttachment(url: String, cache: Boolean = true): ByteArray {
        requireSameHost(url)
        // Small images rarely change, so keep a bounded in-memory cache for repeat
        // views. Callers with their own dedup (the exporter) pass cache=false so a
        // big export can't evict the detail panel's inline images.
        if (cache) {
            imageCache[url]?.let { return it }
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(ZSESSION_HEADER, apiKey)
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()

        // Go through executeWithRetry so transient 502/503/504 + network faults use
        // the same capped exponential backoff as the JSON paths. Without this,
        // flaky Rally downloads broke exports on the first blip.
        val response = executeWithRetry(request, HttpResponse.BodyHandlers.ofByteArray())

        if (response.statusCode() != 200) {
            throw RallyApiException("Download failed with status ${response.statusCode()}", response.statusCode(), null)
        }

        val bytes = response.body()
        if (!cache || bytes.size.toLong() > maxCacheableImageBytes) {
            return bytes
        }
        synchronized(imageCache) {
            // Double-check inside the lock — another thread may have downloaded
            // the same URL while we were on the network.
            imageCache[url]?.let { return it }

            // Evict oldest entries until there's room for the new one. The map is
            // access-ordered, so entries.iterator() yields least-recently-used first.
            val incoming = bytes.size.toLong()
            while (imageCacheBytes.get() + incoming > maxImageCacheBytes && imageCache.isNotEmpty()) {
                val iter = imageCache.entries.iterator()
                if (!iter.hasNext()) break
                val eldest = iter.next()
                iter.remove()
                imageCacheBytes.addAndGet(-eldest.value.size.toLong())
            }

            imageCache[url] = bytes
            imageCacheBytes.addAndGet(incoming)
        }
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

        val body = gson.toJson(mapOf("HierarchicalRequirement" to fields))
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
     * Create a new Defect.
     */
    fun createDefect(
        name: String,
        projectRef: String?,
        ownerRef: String? = null,
        description: String? = null,
        iterationRef: String? = null,
        severity: String? = null,
        priority: String? = null
    ): RallyDefect {
        val url = buildApiUrl("defect/create")
        val fields = mutableMapOf<String, Any>("Name" to name)
        fields["State"] = "Submitted"
        if (!projectRef.isNullOrBlank()) fields["Project"] = projectRef
        if (!ownerRef.isNullOrBlank()) fields["Owner"] = ownerRef
        if (!description.isNullOrBlank()) fields["Description"] = description
        if (!iterationRef.isNullOrBlank()) fields["Iteration"] = iterationRef
        if (!severity.isNullOrBlank()) fields["Severity"] = severity
        if (!priority.isNullOrBlank()) fields["Priority"] = priority

        val body = gson.toJson(mapOf("Defect" to fields))
        val response = executePost(url, body)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val createResult = json.getAsJsonObject("CreateResult")
            ?: throw RallyApiException("Unexpected response: missing CreateResult")
        val errors = createResult.getAsJsonArray("Errors")
        if (errors != null && errors.size() > 0) {
            throw RallyApiException("Failed to create defect: ${errors.joinToString()}")
        }
        val obj = createResult.getAsJsonObject("Object")
            ?: throw RallyApiException("Unexpected response: missing Object in CreateResult")
        return gson.fromJson(obj, RallyDefect::class.java)
    }

    /**
     * Create a new Task linked to a work product.
     */
    fun createTask(
        name: String,
        workProductRef: String,
        ownerRef: String? = null,
        description: String? = null,
        estimate: Double? = null
    ): RallyTaskItem {
        val url = buildApiUrl("task/create")
        val fields = mutableMapOf<String, Any>(
            "Name" to name,
            "WorkProduct" to workProductRef,
            "State" to "Defined"
        )
        if (!ownerRef.isNullOrBlank()) fields["Owner"] = ownerRef
        if (!description.isNullOrBlank()) fields["Description"] = description
        if (estimate != null) fields["Estimate"] = estimate

        val body = gson.toJson(mapOf("Task" to fields))
        val response = executePost(url, body)
        handleResponse(response)

        val json = JsonParser.parseString(response.body()).asJsonObject
        val createResult = json.getAsJsonObject("CreateResult")
            ?: throw RallyApiException("Unexpected response: missing CreateResult")
        val errors = createResult.getAsJsonArray("Errors")
        if (errors != null && errors.size() > 0) {
            throw RallyApiException("Failed to create task: ${errors.joinToString()}")
        }
        val obj = createResult.getAsJsonObject("Object")
            ?: throw RallyApiException("Unexpected response: missing Object in CreateResult")
        return gson.fromJson(obj, RallyTaskItem::class.java)
    }

    /**
     * Upload a file as an attachment to a Rally artifact.
     * Rally requires a two-step process:
     * 1. Create AttachmentContent with base64-encoded file data
     * 2. Create Attachment linking the content to the artifact
     */
    fun uploadAttachment(artifactRef: String, filePath: Path): RallyAttachment {
        val fileName = filePath.fileName.toString()
        val fileSize = Files.size(filePath)
        require(fileSize <= MAX_UPLOAD_BYTES) {
            "Attachment too large: ${fileSize / 1024} KB exceeds the ${MAX_UPLOAD_BYTES / 1024 / 1024} MB upload limit"
        }
        val fileBytes = Files.readAllBytes(filePath)
        val contentType = Files.probeContentType(filePath) ?: "application/octet-stream"
        // Stream base64 directly into a buffer to skip the intermediate String
        // allocation that Base64.getEncoder().encodeToString() produces.
        val base64Buffer = java.io.ByteArrayOutputStream(((fileBytes.size * 4 + 2) / 3) + 16)
        Base64.getEncoder().wrap(base64Buffer).use { it.write(fileBytes) }
        val base64Content = base64Buffer.toString(StandardCharsets.US_ASCII)

        // Step 1: Create AttachmentContent
        val contentUrl = buildApiUrl("attachmentcontent/create")
        val contentBody = gson.toJson(mapOf("AttachmentContent" to mapOf("Content" to base64Content)))
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
        val attachBody = gson.toJson(mapOf("Attachment" to attachFields))
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

/**
 * Presents a byte-array HTTP response as HttpResponse<String> with the body already
 * decoded (and gunzipped when needed), so the many existing call sites that use
 * response.body()/statusCode()/headers() stay untouched.
 */
private class DecodedResponse(
    private val delegate: HttpResponse<ByteArray>,
    private val decoded: String
) : HttpResponse<String> {
    override fun statusCode(): Int = delegate.statusCode()
    override fun request(): HttpRequest = delegate.request()
    override fun previousResponse(): java.util.Optional<HttpResponse<String>> =
        java.util.Optional.empty()
    override fun headers(): java.net.http.HttpHeaders = delegate.headers()
    override fun body(): String = decoded
    override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = delegate.sslSession()
    override fun uri(): URI = delegate.uri()
    override fun version(): HttpClient.Version = delegate.version()
}
