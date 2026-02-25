# Code Review Findings — 2026-02-24

Deep review of the entire Rally plugin codebase. Findings ranked by severity.

---

## Bugs

### BUG 1: NPE in `createUserStory` response handling (Crash)

**File:** `RallyApiClient.kt:760-766`

**Problem:** If Rally returns an unexpected response where `"CreateResult"` is missing, `createResult` is null. Line 761 uses null-safe `?.` for the error check, but line 765 calls `.getAsJsonObject("Object")` directly on `createResult` — causing a `NullPointerException` instead of a meaningful error message.

```kotlin
val createResult = json.getAsJsonObject("CreateResult")  // can be null
val errors = createResult?.getAsJsonArray("Errors")       // safe
val obj = createResult.getAsJsonObject("Object")          // CRASH if null
```

**Fix:** Add null check on `createResult` before accessing `Object`, or use `?.` with an appropriate error throw.

---

### BUG 2: Filter changes silently dropped during loading (UX)

**File:** `RallyToolWindowPanel.kt:326-327`

**Problem:** The `loading` flag guard causes filter changes to be silently ignored while a previous load is in progress.

**Reproduction steps:**
1. Change scope combo — triggers `loadTickets()`, sets `loading = true`, starts background API call
2. While still loading, change state combo — `loadTickets()` sees `loading == true`, returns immediately
3. Background thread finishes — updates UI with results for the OLD filter combination
4. User sees stale results; their state filter change was completely lost

**Impact:** Affects all filter combos (scope, state, project, iteration). Also affects post-state-change refresh at line 937-938 — if the previous load hasn't finished, the refresh after a state change is skipped.

**Fix:** Track a `pendingReload` flag. When `loadTickets()` is called during an active load, set `pendingReload = true`. At load completion, check the flag and re-trigger `loadTickets()`.

---

### BUG 3: Wrong filename in image download URL during export

**File:** `RallyExporter.kt:461, 480`

**Problem:** `downloadInlineImages()` extracts the original filename from the HTML `src` attribute (regex group 3) but passes the locally-renamed `uniqueFileName` to `downloadRallyImage()`. The download URL is then constructed with the wrong filename.

```
Original src:  /slm/attachment/67890/screenshot.png
Constructed:   /slm/attachment/67890/US12345.png   (WRONG)
```

**Current behavior:** Likely works in practice because Rally routes by attachment OID and ignores the filename suffix. But technically incorrect and could break on stricter configurations.

**Fix:** Pass the original filename (regex group 3) to `downloadRallyImage()` as a separate parameter for URL construction, while keeping `uniqueFileName` for the local file save.

---

## Red Flags

### RED FLAG 1: Unbounded `imageCache` — never evicted

**File:** `RallyApiClient.kt:34, 58-60`

`imageCache` is a `ConcurrentHashMap<String, ByteArray>` with no TTL, no size limit, and no eviction. Each inline image can be hundreds of KB to several MB. Over a long IDE session browsing many artifacts, memory grows without bound.

Even `clearCache()` (called by the Refresh button) only clears `queryCache` — it does **not** clear `imageCache`.

**Recommendation:** Either add `imageCache.clear()` to `clearCache()`, or introduce a size-based eviction (e.g., cap at 50MB total).

---

### RED FLAG 2: `testConnection()` is dead code

**File:** `RallyApiClient.kt:220-231`

Never called anywhere. The settings page uses `getCurrentUser()` directly. Additionally, `getCurrentUser().userName` is `String?`, so if null, the query becomes `(UserName = "null")` which silently "succeeds" with 0 results.

**Recommendation:** Remove the method or wire it into the settings configurable properly.

---

### RED FLAG 3: Missing `workspace` parameter in `queryTestSteps`

**File:** `RallyApiClient.kt:602-616`

Every other query method (`queryTestCases`, `queryAttachments`, `queryTasksForWorkProduct`, etc.) appends the workspace URL parameter. `queryTestSteps` does not.

Probably works in most setups since FormattedID is subscription-unique, but inconsistent with all other methods and could cause unexpected results in multi-workspace environments.

**Recommendation:** Add workspace parameter for consistency.

---

## Improvement Applied (2026-02-24)

- **Cached `queryCurrentIteration()`** — Added 2-min TTL cache to eliminate one redundant API call per filter/scope/state change when "All Sprints" is selected. Cache key includes `workspaceRef|projectRef` so project switches get fresh results. `clearCache()` on Refresh still wipes it.

---

## Priority Order for Fixes

1. **BUG 1** — NPE crash, simple one-line fix
2. **BUG 2** — UX regression, moderate complexity (pending reload flag)
3. **RED FLAG 1** — Memory concern, simple fix (clear imageCache in clearCache)
4. **BUG 3** — Export correctness, moderate complexity (pass original filename)
5. **RED FLAG 3** — Consistency, one-line fix
6. **RED FLAG 2** — Dead code cleanup
