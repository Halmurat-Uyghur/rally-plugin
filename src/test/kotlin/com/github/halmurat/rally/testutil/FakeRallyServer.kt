package com.github.halmurat.rally.testutil

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal in-process stand-in for a Rally WSAPI server, for tests that need
 * [com.github.halmurat.rally.api.RallyApiClient] to make real HTTP calls.
 *
 * Routes match on the request path (query string ignored). Unrouted paths answer
 * 200 with an empty QueryResult, which every list query parses as "no items".
 */
class FakeRallyServer : AutoCloseable {

    class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Reply>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Base of the WSAPI object URLs this server serves, e.g. "$apiBase/defect/1". */
    val apiBase: String get() = "$baseUrl/slm/webservice/v2.0"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val reply = routes[path]?.invoke(exchange) ?: Reply(200, EMPTY_QUERY_RESULT)
            val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
            reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    fun route(path: String, status: Int, body: String) {
        routes[path] = { Reply(status, body) }
    }

    fun route(path: String, responder: (HttpExchange) -> Reply) {
        routes[path] = responder
    }

    fun hitCount(path: String): Int = hits[path]?.get() ?: 0

    override fun close() = server.stop(0)

    companion object {
        const val EMPTY_QUERY_RESULT =
            """{"QueryResult":{"Errors":[],"Warnings":[],"TotalResultCount":0,"StartIndex":1,"PageSize":20,"Results":[]}}"""

        /** What Rally returns (HTTP 200) when a direct-ref GET names a deleted/unreadable object. */
        const val OBJECT_NOT_FOUND =
            """{"OperationResult":{"_rallyAPIMajor":"2","_rallyAPIMinor":"0","Errors":["Cannot find object to read"],"Warnings":[]}}"""
    }
}
