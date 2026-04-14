package com.github.halmuratuyghur.rally.util

import java.nio.file.Path

/**
 * Cross-platform filename hardening and traversal-safe path resolution.
 * Used everywhere the plugin writes files derived from Rally API responses.
 */
object RallyFileUtils {
    private val RE_UNSAFE = Regex("[^a-zA-Z0-9._\\-()\\[\\] ]")
    private val RESERVED: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) { add("COM$i"); add("LPT$i") }
    }
    private const val DEFAULT_MAX_LEN = 200

    fun sanitizeFileName(name: String, maxLen: Int = DEFAULT_MAX_LEN): String {
        var s = name.replace(RE_UNSAFE, "_")
            .trimStart('.')
            .trimEnd('.', ' ')
        if (s.isBlank()) return "unnamed"

        val dot = s.lastIndexOf('.')
        if (s.length > maxLen) {
            s = if (dot > 0 && s.length - dot <= 10) {
                val ext = s.substring(dot)
                s.substring(0, (maxLen - ext.length).coerceAtLeast(1)) + ext
            } else {
                s.substring(0, maxLen)
            }
        }

        // Windows treats everything before the FIRST dot as the device name
        // (e.g. CON.txt.bak collides with the CON device), so check the first segment.
        val firstDot = s.indexOf('.')
        val base = if (firstDot > 0) s.substring(0, firstDot) else s
        if (base.uppercase() in RESERVED) s = "_$s"
        return s
    }

    /**
     * Resolve `child` under `parent`, sanitizing first and verifying the normalized
     * result is still a descendant of `parent`. Throws IllegalArgumentException on
     * traversal attempts. The NIO `normalize().startsWith()` idiom is more reliable
     * than canonical-path string comparison and does not require the file to exist.
     *
     * NOTE: normalize() does not resolve symlinks. If the caller-supplied `parent`
     * already contains a symlink to an outside location, a write through that symlink
     * will land outside the intended directory. In the Rally export threat model the
     * user picks `parent` via a folder chooser and Rally API cannot create symlinks
     * inside it, so this is acceptable — but do not use safeResolve as the only guard
     * in contexts where the parent directory itself is untrusted.
     */
    fun safeResolve(parent: Path, child: String): Path {
        val safe = sanitizeFileName(child)
        val normalizedParent = parent.toAbsolutePath().normalize()
        val resolved = normalizedParent.resolve(safe).normalize()
        require(resolved.startsWith(normalizedParent)) {
            "Path traversal attempt: $child"
        }
        return resolved
    }
}
