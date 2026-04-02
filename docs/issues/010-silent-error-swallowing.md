## Title
bug: Errors silently swallowed across the plugin — users receive no feedback on API failures, network errors, or load failures

## Labels
bug, ux, high-priority

## Description

### Summary
Throughout the plugin, exceptions are caught and either logged silently or replaced with a generic fallback value (e.g., `null`, empty list, placeholder text). Users are left seeing blank panels, empty lists, or "(loading…)" states with no indication that an error occurred, making troubleshooting impossible.

### Affected Files & Key Locations

**RallyApiClient.kt — `fetchDescription()` (lines 435–446):**
```kotlin
} catch (e: Exception) {
    null  // All errors silently return null; caller cannot distinguish "no description" from API failure
}
```

**RallyExporter.kt — attachment queries (lines 58, 89, 344, 390):**
```kotlin
val attachments = try { client.queryAttachments(id) } catch (e: Exception) { emptyList() }
// Exported files silently omit attachments on API failure with no indication
```

**RallyApiClient.kt — proxy configuration (line 42–49):**
```kotlin
} catch (_: Exception) {
    // IDE proxy API not available — use direct connection (no log, no user notification)
}
```

**RallyToolWindowPanel.kt — project load failure (line 536):**
```kotlin
} catch (e: Exception) {
    // Combo box is disabled with no dialog or notification
}
```

**RallyDetailPanel.kt — tab error indicators (lines 416, 429, 582):**
```kotlin
tabbedPane.setTitleAt(TAB_TASKS, "Tasks (!)")  // cryptic "(!)" suffix; no explanation
```

**RallyToolWindowPanel.kt — sprint summary (line 688):**
```kotlin
} catch (e: Exception) {
    // "Sprint: unable to load" shown briefly then overwritten
}
```

### Impact
- Users cannot distinguish "no data" from "failed to load" — no way to know if data is missing or an error occurred
- Authentication failures (401/403) look identical to empty results
- Network timeouts and Rally downtime are completely invisible
- Debugging requires manually checking IntelliJ's internal log (`Help > Show Log in Finder`)
- Particularly severe during attachment export: exports appear complete but are silently incomplete

### Expected Behavior
Transient errors (network timeout, 5xx) should be shown in the status bar with a retry hint. Authentication errors should prompt the user to check settings. Partial load failures (e.g., tasks failed but test cases succeeded) should be clearly marked.

### Suggested Fix

**Immediate improvement — show errors in status bar:**
```kotlin
} catch (e: Exception) {
    LOG.warn("Failed to load tasks for $id", e)
    ApplicationManager.getApplication().invokeLater {
        if (generation.get() != gen) return@invokeLater
        tabbedPane.setTitleAt(TAB_TASKS, "Tasks (load failed)")
        statusLabel.text = "Error loading tasks: ${e.message?.take(80)}"
    }
}
```

**For critical failures — use IntelliJ Notifications API:**
```kotlin
Notification("Rally", "Rally API Error",
    "Failed to authenticate. Please check your API key in Settings > Tools > Rally.",
    NotificationType.ERROR).notify(project)
```

**For export — make partial failures explicit:**
```kotlin
// Track which attachment queries failed and include summary in export dialog
```
