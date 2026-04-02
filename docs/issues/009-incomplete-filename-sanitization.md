## Title
bug: Incomplete filename sanitization — Windows reserved names, null bytes, and non-ASCII characters not handled

## Labels
bug, portability, medium-priority

## Description

### Summary
`RallyExporter.sanitizeFileName()` only strips forward/back slashes and double dots. It does not handle Windows reserved device names (`CON`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`), null bytes, colons, angle brackets, pipe characters, or Unicode characters that may cause filesystem failures on Windows or cross-platform interoperability issues.

### Affected File & Lines
`src/main/kotlin/com/github/halmuratuyghur/rally/export/RallyExporter.kt` — lines 461–465

```kotlin
private fun sanitizeFileName(name: String): String {
    val sanitized = name.replace(Regex("[/\\\\]"), "_").replace("..", "_")
    return sanitized.ifBlank { "unnamed" }
}
```

### Missing Sanitization Cases
| Case | Example | Current result | Should be |
|------|---------|----------------|-----------|
| Windows reserved names | `CON.txt` | `CON.txt` — opens device | `_CON.txt` |
| Windows invalid chars | `file:name<1>.txt` | unchanged | `file_name_1_.txt` |
| Null byte | `file\x00name` | unchanged — truncates on Linux | `file_name` |
| Leading dot/dash | `.hidden`, `-file` | unchanged | `_hidden`, `_file` |
| Trailing dot/space | `file.` | unchanged — Windows strips silently | `file_` |
| Non-ASCII Unicode | `Uyghur-رەسىم.png` | preserved (NFC/NFD mismatch) | normalized |

### Impact
- Exports fail with `IOException` on Windows for reserved names or invalid characters
- Files created on macOS/Linux may not open on Windows (and vice versa)
- Null bytes can cause filenames to be silently truncated, creating collisions
- Markdown link paths in exported `.md` files reference wrong filenames

### Suggested Fix
```kotlin
private val WINDOWS_RESERVED = setOf("CON","PRN","AUX","NUL") +
    (1..9).flatMap { listOf("COM$it", "LPT$it") }

private fun sanitizeFileName(name: String): String {
    var s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
    s = s.replace(Regex("[/\\\\:<>|?*\u0000]"), "_")
         .replace("..", "_")
         .trimEnd('.', ' ')
         .trimStart('.')
         .ifBlank { "unnamed" }

    val nameWithoutExt = s.substringBeforeLast('.')
    if (nameWithoutExt.uppercase() in WINDOWS_RESERVED) s = "_$s"
    return s
}
```
