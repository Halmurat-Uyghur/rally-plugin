# Design: Start Working / Finish Working Workflow

> Approved 2026-02-27

## Problem

Developers must manually create branches, change Rally ticket states, assign themselves as owner, and type FormattedIDs in commit messages. This context-switching between IDE and Rally browser interrupts flow.

## Solution

Two toolbar buttons in the Rally tool window: **Start Working** and **Finish Working**. They orchestrate git branching, Rally state changes, owner assignment, and commit message prefixing in a single click.

## Approach

**Lightweight Toolbar Actions** (chosen over IntelliJ Tasks API integration and custom Work Session panel for simplicity).

## Architecture

### New Files

| File | Purpose |
|------|---------|
| `vcs/RallyCheckinHandler.kt` | `CheckinHandlerFactory` that prepends `[US12345]` to commit messages when a work session is active |
| `vcs/RallyWorkSession.kt` | Project-level service tracking the active ticket (FormattedID, ref, type, branch name) |

### Modified Files

| File | Change |
|------|--------|
| `RallyToolWindowPanel.kt` | Add "Start Working" / "Finish Working" toolbar buttons + methods |
| `RallyApiClient.kt` | Add `updateArtifactOwner(ref, type, ownerRef)` method |
| `plugin.xml` | Register `CheckinHandlerFactory`, `projectService`, add `git4idea` dependency |
| `build.gradle.kts` | Add `git4idea` to `intellij.plugins` |

## "Start Working" Flow

1. User selects a ticket and clicks "Start Working"
2. Confirmation dialog lists what will happen
3. On pooled thread (parallel where possible):
   - Create & checkout branch `feature/{FormattedID}` from current HEAD via Git4Idea `GitBrancher`
   - Update Rally state to In-Progress via `updateArtifactState()`
   - Assign owner via new `updateArtifactOwner()`
   - Save state to `RallyWorkSession`
4. On EDT:
   - Optimistic update of `allArtifacts` (state + owner)
   - Status label: "Working on US12345"
   - Toggle button states (disable Start, enable Finish)

### Edge Cases

- Branch exists: offer to check it out instead of creating
- Already In-Progress: skip state change, still create branch
- No git repo: show error
- Partial API failure: warn but keep branch; show which steps failed

## "Finish Working" Flow

1. User clicks "Finish Working" (only enabled when session active)
2. Confirmation dialog
3. On pooled thread:
   - Update Rally state to Completed
   - Clear `RallyWorkSession`
4. On EDT:
   - Optimistic update of `allArtifacts`
   - Toggle button states
   - Open IntelliJ's native "Create Pull Request" dialog

### Edge Cases

- No active session: button disabled
- State change fails: warn, but still clear session and open PR dialog
- Manual branch switch: session persists until explicitly finished

## Commit Message Integration

`RallyCheckinHandler` (registered as `<checkinHandlerFactory>`):

- On commit, checks `RallyWorkSession` for active ticket
- If active and message doesn't start with `[FormattedID]`, prepends it
- Idempotent: skips if prefix already present
- Non-blocking: user can always edit the message

## Work Session State

```kotlin
@Service(Service.Level.PROJECT)
class RallyWorkSession {
    var activeTicketId: String? = null
    var activeTicketRef: String? = null
    var activeTicketType: String? = null
    var activeBranchName: String? = null
    val isActive: Boolean get() = activeTicketId != null
}
```

- Project-level service (different projects can have different active tickets)
- No persistence across IDE restarts (by design)
- Reset on "Finish Working" or IDE close

## Branch Naming

Format: `feature/{FormattedID}` (e.g., `feature/US12345`)

- User stories: `feature/US12345`
- Defects: `feature/DE4567`

## Dependencies

- `git4idea` plugin (IntelliJ bundled) for `GitBrancher` and PR dialog
- No new external libraries
