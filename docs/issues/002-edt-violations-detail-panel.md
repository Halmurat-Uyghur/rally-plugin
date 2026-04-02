## Title
bug: EDT violations in RallyDetailPanel — Swing components updated off event dispatch thread

## Labels
bug, thread-safety, critical

## Description

### Summary
`RallyDetailPanel.showArtifact()` directly updates Swing components (`descriptionPane.text`, `tabbedPane.removeAll()`, `tabbedPane.addTab()`) without dispatching to the EDT, violating Swing's single-thread rule and causing unpredictable rendering failures or crashes.

### Current Behavior
When `showArtifact()` is called from a background thread, lines such as:

```kotlin
descriptionPane.text = wrapHtml(desc)          // line 266 — off-EDT write
descriptionPane.caretPosition = 0               // line 269 — off-EDT write
tabbedPane.removeAll()                          // line 274 — off-EDT structural change
tabbedPane.addTab("Test Steps", JBScrollPane(stepList)) // line 275 — off-EDT
```
are executed on the pooled thread rather than the EDT.

### Affected Files & Lines
`src/main/kotlin/com/github/halmuratuyghur/rally/ui/RallyDetailPanel.kt`
- Line 266: `descriptionPane.text = wrapHtml(desc)`
- Line 269: `descriptionPane.text = wrapHtml("<i>Loading description...</i>")`
- Line 274: `tabbedPane.removeAll()`
- Line 275: `tabbedPane.addTab(…)`
- Lines 241–246: Tab restoration on the wrong thread

### Impact
- Random rendering glitches (partial paints, invisible text)
- Data races on Swing internal model state
- Intermittent crashes, especially on slower machines or high-load scenarios
- Hard-to-reproduce bugs that only surface under concurrent usage

### Expected Behavior
All Swing component mutations must occur on the EDT, either by calling `invokeLater { }` or `invokeAndWait { }`.

### Suggested Fix
Wrap all initial Swing updates inside `ApplicationManager.getApplication().invokeLater { }`:
```kotlin
ApplicationManager.getApplication().invokeLater {
    val desc = artifact.description
    if (!desc.isNullOrBlank()) {
        descriptionPane.text = wrapHtml(desc)
        descriptionPane.caretPosition = 0
    } else {
        descriptionPane.text = wrapHtml("<i>Loading description...</i>")
    }
    if (artifact is RallyTestCase) {
        tabbedPane.removeAll()
        tabbedPane.addTab("Test Steps", JBScrollPane(stepList))
    }
}
```
