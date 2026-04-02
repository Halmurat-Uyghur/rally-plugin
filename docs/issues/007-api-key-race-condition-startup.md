## Title
bug: Race condition in settings startup — API key may return empty string when accessed on EDT before async PasswordSafe load completes

## Labels
bug, thread-safety, settings, high-priority

## Description

### Summary
`RallySettings.apiKey` uses a `CountDownLatch` to wait for an asynchronous PasswordSafe load, but the wait is **skipped when called from the Event Dispatch Thread**. During plugin initialization, the tool window factory calls `settings.apiKey` on the EDT before the background load completes, receiving an empty string and failing authentication silently.

### Affected Files & Lines
`src/main/kotlin/com/github/halmuratuyghur/rally/settings/RallySettings.kt` — lines 43–71

```kotlin
fun loadState(state: State) {
    // ...
    ApplicationManager.getApplication().executeOnPooledThread {
        // async PasswordSafe read...
        cachedApiKey = PasswordSafe.instance.getPassword(credentialAttr)
        apiKeyLatch.countDown()
    }
}

val apiKey: String get() {
    if (cachedApiKey == null && !ApplicationManager.getApplication().isDispatchThread) {
        apiKeyLatch.await(2, TimeUnit.SECONDS)  // skipped on EDT!
    }
    return cachedApiKey ?: ""  // returns "" if async load not yet done
}
```

### Impact
- Plugin initializes and immediately attempts API calls with an empty key
- `RallyAuthenticationException` (or silent failure) on the very first query after IDE start
- 2-second timeout is also insufficient on slow systems or when PasswordSafe vault unlocking takes time
- Users see blank ticket lists with no clear error message

### Steps to Reproduce
1. Start IntelliJ with the Rally plugin installed and a valid API key configured
2. Open the Rally tool window immediately after IDE launch
3. Observe "Authentication failed" or empty list on first load

### Expected Behavior
The API key should always be available by the time any API call is made, regardless of which thread requests it.

### Suggested Fix
1. **Never skip the latch wait on EDT** — use `invokeLater` to defer EDT-initiated calls until the key is loaded:
```kotlin
val apiKey: String get() {
    if (cachedApiKey == null) {
        apiKeyLatch.await(10, TimeUnit.SECONDS)  // always wait, increase timeout
    }
    return cachedApiKey ?: ""
}
```
2. **Increase the timeout** from 2 seconds to at least 10 seconds (or make it configurable).
3. **Log a warning** when the latch times out so the issue is discoverable.
