package com.github.halmurat.rally.ui

/** Outcome counts of one tool-window export. */
internal data class ExportCounts(
    val artifactsExported: Int,
    val artifactsFailed: Int,
    val testCasesExported: Int,
    val testCasesFailed: Int,
    /** Artifacts whose linked-test-case lookup failed: those test cases were never attempted. */
    val testCaseLookupsFailed: Int,
    /** Distinct attachment/inline-image downloads that failed (marked inside the files). */
    val downloadsFailed: Int,
)

/** Balloon text (plain, "\n"-separated), status-bar text, and whether to warn. */
internal data class ExportSummary(val balloonText: String, val statusText: String, val anyFailed: Boolean)

/**
 * Summary of an export for the balloon and status bar. Any failure, including the ones
 * that still produce files (failed downloads), makes [ExportSummary.anyFailed] true so the
 * export never reads as complete when it isn't. [safeOutputDir] must already be
 * HTML-escaped: the balloon renders HTML. Pure and top-level so it is unit-testable.
 */
internal fun buildExportSummary(c: ExportCounts, safeOutputDir: String): ExportSummary {
    val anyFailed = c.artifactsFailed + c.testCasesFailed + c.testCaseLookupsFailed + c.downloadsFailed > 0
    val balloon = buildString {
        append("Exported to:\n$safeOutputDir\n\n")
        append("Artifacts: ${c.artifactsExported} exported")
        if (c.artifactsFailed > 0) append(", ${c.artifactsFailed} failed")
        append("\nTest Cases: ${c.testCasesExported} exported")
        if (c.testCasesFailed > 0) append(", ${c.testCasesFailed} failed")
        if (c.testCaseLookupsFailed > 0) {
            append("\nCouldn't list linked test cases for ${c.testCaseLookupsFailed} artifact(s)")
        }
        if (c.downloadsFailed > 0) {
            append("\n${c.downloadsFailed} attachment/image download(s) failed (marked in the files)")
        }
    }
    val status = "Exported ${c.artifactsExported} artifact(s), ${c.testCasesExported} test case(s)" +
        if (anyFailed) " — with errors" else ""
    return ExportSummary(balloon, status, anyFailed)
}
