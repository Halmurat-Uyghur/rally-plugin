## Title
security: Path traversal in export — attachment and image filenames not fully sanitized

## Labels
security, bug, high-priority

## Description

### Summary
`RallyExporter` constructs file paths from attachment names and image filenames received from the Rally API without fully preventing directory traversal. A malicious or compromised Rally server could supply filenames containing `../` sequences to write files outside the intended export directory.

### Affected Files & Lines
`src/main/kotlin/com/github/halmuratuyghur/rally/export/RallyExporter.kt`

**Insufficient sanitization (lines 461–465):**
```kotlin
private fun sanitizeFileName(name: String): String {
    val sanitized = name.replace(Regex("[/\\\\]"), "_").replace("..", "_")
    return sanitized.ifBlank { "unnamed" }
}
```

**`artifactId` used unsanitized in directory paths (multiple lines):**
```kotlin
val attachDir = "$outputDir${File.separator}${artifactId}_attachments"  // line 434
val imgDir = "$outputDir${File.separator}${artifactId}_images"          // line 524
```

**Image filename from URL regex match used without canonical-path check (lines 530–565):**
```kotlin
val fileName = matcher.group(3)   // Unsanitized rally filename
val ext = fileName.substringAfterLast('.', "png").lowercase()
// ...
val outputFile = File(imgDir, localFileName)  // No canonical path verification
Files.write(outputFile.toPath(), fileBytes)
```

### Attack Scenario (CWE-22)
An attachment named `../../.bashrc` passes the current sanitizer (replacing `/` and `\` yields `.._.bashrc`, but a crafted name like `..%2F..%2Fetc%2Fpasswd` after URL-decoding could bypass the check). Additionally, `artifactId` values such as `TC-1/..` are not sanitized at directory-creation time.

### Impact
- Arbitrary file write to attacker-controlled paths on the developer's machine
- Potential overwrite of configuration files or shell initialization scripts
- CWE-22 (Path Traversal)

### Suggested Fix
1. **Apply `sanitizeFileName` to `artifactId`** before using it in directory names.
2. **Expand sanitization** to remove null bytes, colons, and other OS-invalid characters.
3. **Add a canonical-path check** before every file write:
```kotlin
val resolvedPath = outputFile.canonicalPath
val basePath = File(attachDir).canonicalPath + File.separator
require(resolvedPath.startsWith(basePath)) {
    "Path traversal detected: $resolvedPath is outside $basePath"
}
```
4. **Use `StandardOpenOption.CREATE_NEW`** to atomically fail if the target already exists, removing the TOCTOU race in the deduplication loop.
