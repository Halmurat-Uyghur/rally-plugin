# Test Case Scope Filter

## Problem

Search only covers user stories and defects. Test cases can only be found indirectly when a parent story's name contains the TC ID. Orphan TCs (no WorkProduct link) and deprecated TCs are invisible to search.

## Solution

Add "Test Cases" as a scope option in the existing filter dropdown. Same behavior as "User Stories" or "Defects" — load TCs into the main list, search works on them, same performance patterns.

## Design

### 1. Model: RallyTestCase implements RallyArtifact

- Add missing interface fields: `description`, `creationDate`, `lastUpdateDate`, `scheduleState` (always null), map `_type` to `type`
- Existing TC-specific fields (`method`, `lastVerdict`, `workProduct`) unchanged

### 2. Scope & Loading

- Add `"Test Cases"` to `SCOPE_OPTIONS` (between "Defects" and "Recent Activity")
- New `queryAllTestCases()` in RallyApiClient — lightweight list fields, cached, respects project/workspace/iteration filters
- `searchArtifacts()` queries testcase endpoint when scope is "Test Cases"
- Same performance patterns: lightweight list fields (no Description), 2-min cache TTL, lazy description on selection, server search only on empty client results + 3+ chars

### 3. Cell Renderer

- `is RallyTestCase` branch in `ArtifactCellRenderer`
- Show FormattedID + Name (same layout as stories/defects)
- State badge: `LastVerdict` (Pass/Fail) instead of ScheduleState
- Icon: test-specific icon (e.g., `AllIcons.RunConfigurations.TestState.Run`)

### 4. Detail Panel & Context Menu

- TC selected: detail panel shows description (top) + test steps (bottom), reusing existing test steps logic
- No Test Cases/Tasks/Attachments tabs (those are for stories/defects)
- Context menu: "Open in Browser", "Copy FormattedID" only — no state change actions
- Start/Finish Working buttons disabled for TCs
