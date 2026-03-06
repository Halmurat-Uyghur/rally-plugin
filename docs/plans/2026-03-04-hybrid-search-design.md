# Hybrid Search: Server-Side Fallback

## Problem

Plugin loads at most 200 artifacts (pageSize setting). Client-side search can only find items within that loaded set. Artifacts beyond the limit are invisible to search.

## Solution

Keep instant client-side filtering as default. When it returns 0 results, automatically trigger a server-side Rally WSAPI search using `contains` queries on Name and FormattedID. Results are temporary — clearing search restores the original list.

## Flow

1. User types in search box → 300ms debounce → client-side filter on `allArtifacts`
2. Client-side returns 0 results → trigger server-side search
3. Server query: `((Name contains "{text}") OR (FormattedID contains "{text}"))` on user stories + defects (parallel)
4. Status label shows "Searching Rally..." during API call
5. Results shown temporarily in list; clearing search restores original loaded list

## Implementation

### RallyApiClient.kt
- Add `searchArtifacts(searchText: String, scope: String?): List<RallyArtifact>` method
- Builds Rally `contains` query on Name and FormattedID
- Reuses existing `queryUserStories` / `queryDefects` with parallel execution
- Results cached naturally by existing cache mechanism

### RallyToolWindowPanel.kt
- Modify `applySearchFilter()`: when client-side filter yields 0 results and query is non-blank, fire server-side search on pooled thread
- Store server results in separate variable (not merged into `allArtifacts`)
- Show "Searching Rally..." in status label during server call
- On search clear, restore `allArtifacts` to list model

## Design Decisions
- Temporary results only (not merged into allArtifacts)
- Same 300ms debounce for both client and server paths
- Minimum query length of 3 characters before triggering server search (avoid overly broad queries)
