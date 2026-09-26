package com.github.halmurat.rally.testutil

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal in-process stand-in for a Rally WSAPI server, for tests that need
 * [com.github.halmurat.rally.api.RallyApiClient] to make real HTTP calls.
 *
 * Routes match on the request path (query string ignored). Unrouted paths answer
 * 200 with an empty QueryResult, which every list query parses as "no items".
 *
 * By default every request is answered on the server's single dispatcher thread, so a route
 * that blocks (a test holding a response back) stalls every other request too. Pass
 * [concurrent] = true to answer each request on its own thread, as a real Rally does — needed
 * when a test holds one answer back while other requests must still be served (e.g. an old
 * load's lookup still in flight while a newer load runs to completion).
 */
class FakeRallyServer(concurrent: Boolean = false) : AutoCloseable {

    class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool: ExecutorService? = if (concurrent) {
        Executors.newCachedThreadPool { r -> Thread(r, "FakeRallyServer").apply { isDaemon = true } }
    } else null
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Reply>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val requestLog = ConcurrentLinkedQueue<URI>()

    /** Every request URI received (path + raw query), in arrival order. */
    val requests: List<URI> get() = requestLog.toList()

    /** Forget the requests received so far, e.g. to assert only on those after a step. */
    fun clearRequests() = requestLog.clear()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Base of the WSAPI object URLs this server serves, e.g. "$apiBase/defect/1". */
    val apiBase: String get() = "$baseUrl/slm/webservice/v2.0"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            requestLog.add(exchange.requestURI)
            val reply = routes[path]?.invoke(exchange) ?: Reply(200, EMPTY_QUERY_RESULT)
            val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
            reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        if (pool != null) server.executor = pool
        server.start()
    }

    fun route(path: String, status: Int, body: String) {
        routes[path] = { Reply(status, body) }
    }

    fun route(path: String, responder: (HttpExchange) -> Reply) {
        routes[path] = responder
    }

    fun hitCount(path: String): Int = hits[path]?.get() ?: 0

    override fun close() {
        server.stop(0)
        pool?.shutdownNow()
    }

    companion object {
        const val EMPTY_QUERY_RESULT =
            """{"QueryResult":{"Errors":[],"Warnings":[],"TotalResultCount":0,"StartIndex":1,"PageSize":20,"Results":[]}}"""

        /** What Rally returns (HTTP 200) when a direct-ref GET names a deleted/unreadable object. */
        const val OBJECT_NOT_FOUND =
            """{"OperationResult":{"_rallyAPIMajor":"2","_rallyAPIMinor":"0","Errors":["Cannot find object to read"],"Warnings":[]}}"""

        /** Decoded value of query parameter [name] in [uri] (e.g. the `workspace` ref), or null. */
        fun queryParam(uri: URI, name: String): String? =
            uri.rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
                val key = pair.substringBefore('=')
                if (URLDecoder.decode(key, StandardCharsets.UTF_8) == name) {
                    URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8)
                } else null
            }

        /** A QueryResult envelope holding [items] (each a JSON object). */
        fun queryResult(items: List<String>): String =
            """{"QueryResult":{"Errors":[],"Warnings":[],"TotalResultCount":${items.size},""" +
                """"StartIndex":1,"PageSize":200,"Results":[${items.joinToString(",")}]}}"""
    }
}
