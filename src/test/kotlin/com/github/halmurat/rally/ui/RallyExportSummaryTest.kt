package com.github.halmurat.rally.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RallyExportSummaryTest {

    private fun counts(
        artifactsExported: Int = 2, artifactsFailed: Int = 0,
        testCasesExported: Int = 3, testCasesFailed: Int = 0,
        testCaseLookupsFailed: Int = 0, downloadsFailed: Int = 0,
    ) = ExportCounts(artifactsExported, artifactsFailed, testCasesExported, testCasesFailed,
        testCaseLookupsFailed, downloadsFailed)

    @Test
    fun `a clean export reports no failures`() {
        val s = buildExportSummary(counts(), "/out")
        assertFalse(s.anyFailed)
        assertEquals("Exported to:\n/out\n\nArtifacts: 2 exported\nTest Cases: 3 exported", s.balloonText)
        assertEquals("Exported 2 artifact(s), 3 test case(s)", s.statusText)
    }

    @Test
    fun `failed artifacts and test cases are counted`() {
        val s = buildExportSummary(counts(artifactsFailed = 1, testCasesExported = 0, testCasesFailed = 2), "/out")
        assertTrue(s.anyFailed)
        assertTrue(s.balloonText.contains("Artifacts: 2 exported, 1 failed"))
        assertTrue(s.balloonText.contains("Test Cases: 0 exported, 2 failed"))
        assertTrue(s.statusText.endsWith("— with errors"))
    }

    @Test
    fun `a failed test-case lookup is reported and warns`() {
        val s = buildExportSummary(counts(testCaseLookupsFailed = 1), "/out")
        assertTrue(s.anyFailed)
        assertTrue(s.balloonText.contains("Couldn't list linked test cases for 1 artifact(s)"))
    }

    @Test
    fun `failed downloads warn even though every file was written`() {
        val s = buildExportSummary(counts(downloadsFailed = 2), "/out")
        assertTrue(s.anyFailed)
        assertTrue(s.balloonText.contains("2 attachment/image download(s) failed"))
    }
}
