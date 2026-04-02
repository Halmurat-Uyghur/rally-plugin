## Title
bug: Thread-unsafe image cache eviction causes memory limit violations

## Labels
bug, concurrency, high-priority

## Description

### Summary
The in-memory image cache in `RallyApiClient` uses a non-atomic check-then-act pattern that allows concurrent threads to bypass the size cap, leading to unbounded memory growth.

### Current Behavior
The cache eviction logic reads `imageCacheBytes.get()`, then potentially evicts entries, then writes new data — all without holding a lock across the entire sequence. Concurrent threads can simultaneously find the cache under-limit, both insert large entries, and overshoot the 10 MB ceiling by an unbounded amount.

### Affected File & Lines
`src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt` — lines 1039–1046

```kotlin
if (imageCacheBytes.get() + bytes.size > maxImageCacheBytes) {
    val toRemove = imageCache.keys.take(imageCache.size / 4)
    toRemove.forEach { key ->
        imageCache.remove(key)?.let { imageCacheBytes.addAndGet(-it.size.toLong()) }
    }
}
imageCache[url] = bytes
imageCacheBytes.addAndGet(bytes.size.toLong())
```

### Root Cause
`ConcurrentHashMap` and `AtomicLong` individually guarantee atomic single operations, but not atomic compound operations. The entire check-evict-insert sequence must be wrapped in a `synchronized` block.

### Impact
- Memory limit regularly exceeded under concurrent detail-panel loads
- OOM risk when many large inline images are loaded simultaneously
- `imageCacheBytes` counter diverges from actual cache byte size, breaking future eviction decisions

### Steps to Reproduce
1. Open 3–4 artifacts with rich inline images simultaneously
2. Watch memory usage spike well above the nominal 10 MB cache limit

### Expected Behavior
Cache size stays within the configured `maxImageCacheBytes` limit under concurrent access.

### Suggested Fix
```kotlin
synchronized(imageCache) {
    if (imageCacheBytes.get() + bytes.size > maxImageCacheBytes) {
        val toRemove = imageCache.keys.take(imageCache.size / 4)
        toRemove.forEach { key ->
            imageCache.remove(key)?.let { imageCacheBytes.addAndGet(-it.size.toLong()) }
        }
    }
    imageCache[url] = bytes
    imageCacheBytes.addAndGet(bytes.size.toLong())
}
```
