## Title
bug: Event listeners and mouse adapters never removed on panel disposal — memory leak

## Labels
bug, memory-leak, high-priority

## Description

### Summary
Both `RallyToolWindowPanel` and `RallyDetailPanel` register event listeners (action listeners, document listeners, list selection listeners, mouse adapters) during setup, but **none of these listeners are removed in the `dispose()` methods**. Each listener holds an implicit reference to the enclosing panel, preventing garbage collection and causing memory leaks.

### Affected Files & Lines

**RallyToolWindowPanel.kt — `setupListeners()` (lines 255–341) and `dispose()` (lines 1424–1434):**
- `scopeCombo.addActionListener { … }`
- `stateCombo.addActionListener { … }`
- `projectCombo.addActionListener { … }`
- `iterationCombo.addActionListener { … }`
- `searchField.document.addDocumentListener(…)`
- `artifactList.addListSelectionListener(…)`
- `artifactList.addMouseListener(…)`

None of these are stored as variables and none are removed in `dispose()`.

**RallyDetailPanel.kt — `setupListeners()` (lines 89–225):**
- `copyButton.addMouseListener(…)`
- `browserButton.addMouseListener(…)`
- `testCaseList.addMouseListener(…)`
- `taskList.addMouseListener(…)`
- `attachmentList.addMouseListener(…)`

None are removed when the panel is destroyed.

### Impact
- Each time IntelliJ recreates the tool window (e.g., project reopen, IDE restart within a session), the old listeners accumulate
- The entire panel graph (including `project` reference, all list models, API client references) is kept alive by the dangling listeners
- Under repeated open/close cycles, heap usage grows proportionally
- Old listeners may fire on already-disposed components, causing `IllegalStateException` or `NullPointerException`

### Expected Behavior
All registered listeners must be removed in `dispose()`.

### Suggested Fix
Store listeners as `private lateinit var` fields and explicitly remove them:
```kotlin
private var artifactListSelectionListener: ListSelectionListener? = null
private var searchDocumentListener: DocumentListener? = null

private fun setupListeners() {
    artifactListSelectionListener = ListSelectionListener { onArtifactSelected() }
    artifactList.addListSelectionListener(artifactListSelectionListener)

    searchDocumentListener = object : DocumentListener { … }
    searchField.document.addDocumentListener(searchDocumentListener)
}

override fun dispose() {
    artifactListSelectionListener?.let { artifactList.removeListSelectionListener(it) }
    searchDocumentListener?.let { searchField.document.removeDocumentListener(it) }
    // … similarly for all other listeners
}
```
