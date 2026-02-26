# Rally Plugin — Session Changes (2026-02-25)

## Bugs Fixed

### 1. "Assign to me" assigned to API key owner instead of configured user
**Files:** `RallyApiClient.kt`, `RallyToolWindowPanel.kt`

**Problem:** When creating a user story with "Assign to me" checked, the code called `getCurrentUser()` which hits Rally's `/user` endpoint — that always returns the API key owner (Varun Srinivas), not the user configured in Settings (Halmurat Tahir).

**Fix:**
- Added `getUserByUsername(username)` method to `RallyApiClient` that queries Rally's user endpoint with `(UserName = "...")` filter
- Changed the dialog handler from `client.getCurrentUser().ref` to `client.getUserByUsername(settings.username).ref`

---

### 2. "Open in Browser" not navigating to user stories
**Files:** `RallyApiClient.kt`, `RallyDetailPanel.kt`

**Problem:** Rally web UI URLs require a project OID in the path: `/#/<projectOID>d/detail/userstory/<objectID>`. The plugin was generating `/#/detail/userstory/<objectID>` (missing project context), so the browser opened but couldn't resolve the artifact.

**Fix:**
- Updated `buildWebUrl()` in `RallyApiClient` to extract project OID from the artifact's `project.ref` and include it in the URL
- Added `getParentProjectOid()` helper in `RallyDetailPanel`
- Updated `openTestCaseInBrowser()`, `openTaskInBrowser()`, `openAttachmentInBrowser()` to also include project OID

---

### 3. Iteration dropdown showing all workspace iterations instead of project-scoped
**Files:** `RallyApiClient.kt`, `RallyApiModels.kt`, `RallyToolWindowPanel.kt`

**Problem:** `queryIterations()` only passed `project` as a URL parameter, but Rally still returned iterations from every project in the workspace. The old `distinctBy { it.name }` dedup was a workaround that masked the real issue.

**Fix:**
- Added query filter `(Project = "/project/...")` when a project is selected (matching Rally web UI behavior)
- Added `projectScopeUp=true&projectScopeDown=true` URL params
- Added `State` and `Project` fields to `RallyIteration` model
- Removed the `distinctBy { it.name }` hack

---

### 4. "My Tickets" + iteration filter returning 0 results
**File:** `RallyToolWindowPanel.kt`

**Problem:** The query combiner `conditions.reduce { acc, cond -> "(($acc) AND ($cond))" }` double-wrapped each condition in parentheses, producing `(((Owner.UserName = "...")) AND ((Iteration.Name = "...")))`. Rally's query parser doesn't handle double-nested parens on atomic conditions — it silently returns 0 results.

**Fix:** Changed to `conditions.reduce { acc, cond -> "($acc AND $cond)" }` which produces the correct format: `((Owner.UserName = "...") AND (Iteration.Name = "..."))`

---

### 5. Test Connection showing misleading username info
**File:** `RallySettingsConfigurable.kt`

**Problem:** Test Connection called `getCurrentUser()` (API key owner) and told the user to enter that username. If the API key belongs to someone else, this leads to the wrong username being configured, causing "My Tickets" to fail.

**Fix:**
- Test Connection now shows API key owner separately
- If a username is configured, it validates it via `getUserByUsername()` and shows whether it resolved to a valid Rally user
- Updated tooltip to clarify the field expects the user's email address

---

### 6. EDT freeze — "Freeze in EDT for 25 seconds"
**File:** `RallyToolWindowPanel.kt`

**Problem:** Two issues causing the EDT (UI thread) to freeze:
1. `loadProjects()` and `loadIterations()` used `invokeAndWait` from pooled threads, blocking until the EDT processed the runnable. If the EDT had a backlog of events, the chain stalled.
2. `applySearchFilter()` added items to the list model one at a time (192+ events), each firing `ListSelectionListener` → `showArtifact()` — unnecessary work on every insertion.

**Fix:**
- Replaced `invokeAndWait` with `invokeLater` in both `loadProjects()` and `loadIterations()`
- Captured all UI state (scope, stateFilter, selectedIter, selectedProjectIndex) on EDT before dispatching to background thread
- Detached `ListSelectionListener` during bulk list model updates in `applySearchFilter()`

---

## Deep Analysis — Threading & Data Race Fixes

### 7. `@Volatile` on shared mutable fields

**`RallyToolWindowPanel.kt`** — 9 fields:
- `loading`, `pendingReload` — guard flags read/written from both EDT and pooled threads
- `projectsLoaded`, `iterationsLoaded` — cache state flags across threads
- `cachedProjects`, `cachedIterations` — list references swapped between threads
- `allArtifacts` — set from `invokeLater`, read in `applySearchFilter`
- `currentClient` — created on pooled thread, used from EDT
- `lastSettingsSnapshot` — compared/set on pooled thread

**`RallyDetailPanel.kt`** — 3 fields:
- `currentArtifactRef` — stale-response guard read in `CompletableFuture` callbacks
- `currentArtifact`, `currentClient` — set in `showArtifact()`, read in context menu handlers

**`RallyApiClient.kt`** — 2 fields:
- `workspaceRef`, `projectRef` — set from EDT, read from pooled threads during API queries

### 8. EDT violation in `updateClientProjectRef()`
**Problem:** Was reading `projectCombo.selectedIndex` from a background thread.
**Fix:** Index is now captured on the EDT in `loadTickets()` and passed as a parameter.

### 9. Silent exception swallowing
**Problem:** Sprint summary future's catch block discarded exceptions silently.
**Fix:** Now logs with `LOG.warn`.

### 10. `clearArtifactCache()` concurrent modification risk
**Problem:** Used `queryCache.keys.removeAll { }` on a `ConcurrentHashMap`.
**Fix:** Changed to iterator-based removal which is safe for concurrent maps.

---

## Known Issues (Not Fixed — Low Risk)

| Issue | Risk | Notes |
|-------|------|-------|
| Cache TOCTOU in `getCached()` | Low | Stale data just means an extra API call |
| Unbounded image cache | Low | Few images per session, small footprint |
| No timeout on CompletableFuture tasks | Medium | Hung API call = futures never complete. Needs `orTimeout()` (Java 9+) |
| Query injection in Rally WSAPI queries | Low | Values come from Rally's own API or controlled settings, not free-form input |
| `getClient()` check-then-act race | Low | Multiple threads could create duplicate clients; harmless since they're identical |

---

## Files Changed

| File | Changes |
|------|---------|
| `RallyApiClient.kt` | `getUserByUsername()`, `buildWebUrl()` with project OID, `queryIterations()` project filter, `@Volatile` fields, `clearArtifactCache()` fix |
| `RallyApiModels.kt` | `RallyIteration` — added `State` and `Project` fields |
| `RallyToolWindowPanel.kt` | Query combiner fix, EDT state capture, `invokeAndWait` → `invokeLater`, `@Volatile` fields, list update optimization, `updateClientProjectRef()` param |
| `RallyDetailPanel.kt` | `getParentProjectOid()` helper, project OID in browser URLs, `@Volatile` fields |
| `RallySettingsConfigurable.kt` | Test Connection validates configured username, updated tooltip |
