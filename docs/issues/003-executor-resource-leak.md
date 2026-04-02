## Title
bug: apiExecutor thread pool is never shut down, causing resource leaks on plugin reload

## Labels
bug, resource-leak, high-priority

## Description

### Summary
`RallyApiClient` creates a fixed-size `ExecutorService` (`apiExecutor`) with 8 daemon threads at construction time but never calls `shutdown()` or `shutdownNow()` on it. Each time a client is re-created (e.g., after settings changes or plugin reload), 8 new threads are spawned and the old pool is abandoned.

### Affected Files & Lines
`src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt` — lines 32–34

```kotlin
val apiExecutor: ExecutorService = Executors.newFixedThreadPool(8) { r ->
    Thread(r, "rally-api-worker").apply { isDaemon = true }
}
```

`src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt` — `dispose()` method:
The `apiExecutor` is not shut down during panel disposal.

### Impact
- Thread handle leak: each plugin reload adds 8 dormant threads to the JVM
- Pending network requests in the old pool continue to run after the client is logically disposed
- IDE shutdown may be delayed by inflight tasks on abandoned pools
- In long IDE sessions with frequent settings changes, thread count grows without bound

### Steps to Reproduce
1. Open Rally settings and change the server URL multiple times
2. Use a profiler or `jstack` to count threads named `rally-api-worker`
3. Observe the count increasing with each settings change

### Expected Behavior
`RallyApiClient` should implement `Closeable`/`AutoCloseable`, with `close()` calling `apiExecutor.shutdown()` followed by a timed `awaitTermination()`.

### Suggested Fix
```kotlin
class RallyApiClient(…) : Closeable {

    override fun close() {
        apiExecutor.shutdown()
        if (!apiExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            apiExecutor.shutdownNow()
        }
    }
}
```

In `RallyToolWindowPanel.dispose()`:
```kotlin
override fun dispose() {
    synchronized(clientLock) {
        disposed = true
        currentClient?.close()
        currentClient = null
    }
}
```
