## Title
bug: CompletableFuture.join() blocks IDE pooled threads during export and state-change operations, risking thread starvation

## Labels
bug, concurrency, performance, high-priority

## Description

### Summary
`RallyToolWindowPanel` calls `CompletableFuture.allOf(…).join()` from within `executeOnPooledThread` lambdas during both artifact export and state-change operations. Because the child futures themselves run on `client.apiExecutor` (a fixed pool of 8 threads), the blocking `join()` can exhaust the IDE's pooled thread pool, starving unrelated IDE tasks.

### Affected Files & Lines
`src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyToolWindowPanel.kt`

**Export (line 1110):**
```kotlin
ApplicationManager.getApplication().executeOnPooledThread {
    // ...creates N futures on client.apiExecutor...
    CompletableFuture.allOf(*futures.toTypedArray()).join()  // BLOCKS IDE THREAD
    ApplicationManager.getApplication().invokeLater { /* update UI */ }
}
```

**State change (line 1182):**
```kotlin
ApplicationManager.getApplication().executeOnPooledThread {
    // ...creates N futures on client.apiExecutor...
    CompletableFuture.allOf(*futures.toTypedArray()).join()  // BLOCKS IDE THREAD
    ApplicationManager.getApplication().invokeLater { /* update UI */ }
}
```

Additionally, `resolveInlineImages()` in `RallyDetailPanel` calls `.join()` on image futures from within a `supplyAsync` callback on the same `apiExecutor`, compounding the risk (line 788).

### Impact
- IDE becomes unresponsive during large batch exports or multi-ticket state changes
- IntelliJ's shared pooled thread pool starves, delaying auto-save, indexing, and other IDE operations
- Under worst case (8 simultaneous concurrent operations + 8 image loads), all 16 pool slots are blocked

### Expected Behavior
Background operations should use non-blocking `CompletableFuture` composition via `.thenAccept()` or `.handle()` instead of blocking `.join()`.

### Suggested Fix
Replace blocking joins with callback-based completion:
```kotlin
ApplicationManager.getApplication().executeOnPooledThread {
    val client = try { getClient() } catch (e: Exception) { /* handle */ return@executeOnPooledThread }
    val futures = buildFutures(client)

    CompletableFuture.allOf(*futures.toTypedArray()).handle { _, throwable ->
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || disposed) return@invokeLater
            if (throwable != null) {
                statusLabel.text = "Operation failed: ${throwable.message}"
            } else {
                // update UI with results
            }
        }
    }
    // Method returns immediately — no blocking
}
```
