package com.github.halmurat.rally.export

import com.github.halmurat.rally.api.RallyApiClient
import com.github.halmurat.rally.api.RallyApiException
import com.github.halmurat.rally.api.RallyUserStory
import com.github.halmurat.rally.testutil.FakeRallyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A failed attachment lookup must fail the export visibly — never write a file that
 * silently lacks the artifact's attachments and count it as exported. A failed attachment
 * download still produces the file (marked inside it) but is counted for the summary.
 *
 * The failure routes answer 500 on purpose: it is outside RallyApiClient's retryable
 * statuses (429/502/503/504), which would sleep through several backoff rounds.
 */
class RallyExporterAttachmentsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeRallyServer()
    private val client = RallyApiClient(server.baseUrl, "fake-key")
    private val exporter = RallyExporter(client)

    private val storyPath = "/slm/webservice/v2.0/hierarchicalrequirement/1"
    private val attachmentPath = "/slm/webservice/v2.0/attachment"
    private val testCasePath = "/slm/webservice/v2.0/testcase"
    private val story = RallyUserStory(
        ref = server.apiBase + "/hierarchicalrequirement/1",
        formattedID = "US1",
        name = "Login page",
        scheduleState = "In-Progress",
    )

    @After
    fun tearDown() {
        exporter.close()
        client.apiExecutor.shutdownNow()
        server.close()
    }

    private fun outDir(): String = tmp.root.absolutePath

    private fun queryResult(vararg results: String) =
        """{"QueryResult":{"Errors":[],"Warnings":[],"TotalResultCount":${results.size},"StartIndex":1,""" +
            """"PageSize":20,"Results":[${results.joinToString(",")}]}}"""

    @Test
    fun `artifact export fails and writes nothing when the attachment lookup errors`() {
        // The description carries an inline image: the lookup must fail before it is downloaded.
        server.route(storyPath, 200,
            """{"HierarchicalRequirement":{"Description":"<img src=\"/slm/attachment/7/pic.png\">"}}""")
        server.route("/slm/attachment/7/pic.png", 200, "png-bytes")
        server.route(attachmentPath, 500, """{"error":"boom"}""")

        assertThrows(RallyApiException::class.java) { exporter.exportArtifactJson(story, outDir()) }
        assertThrows(RallyApiException::class.java) { exporter.exportArtifactMarkdown(story, outDir()) }

        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
        assertEquals(0, server.hitCount("/slm/attachment/7/pic.png"))
    }

    @Test
    fun `test case export fails and writes nothing when the attachment lookup errors`() {
        server.route(testCasePath, 200,
            queryResult("""{"_ref":"${server.apiBase}/testcase/1","FormattedID":"TC1","Name":"Login works"}"""))
        server.route(attachmentPath, 500, """{"error":"boom"}""")

        assertThrows(RallyApiException::class.java) { exporter.exportTestCaseJson("TC1", outDir()) }
        assertThrows(RallyApiException::class.java) { exporter.exportTestCaseMarkdown("TC1", outDir()) }

        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
    }

    @Test
    fun `export succeeds when the artifact has no attachments`() {
        server.route(storyPath, 200, """{"HierarchicalRequirement":{"Description":"<p>ok</p>"}}""")
        // Unrouted attachment path answers an empty QueryResult.

        exporter.exportArtifactJson(story, outDir())
        exporter.exportArtifactMarkdown(story, outDir())

        assertTrue(File(outDir(), "US1.json").exists())
        assertTrue(File(outDir(), "US1.md").exists())
        assertEquals(0, exporter.failedDownloadCount)
    }

    @Test
    fun `a failed attachment download is counted once across both passes`() {
        server.route(storyPath, 200, """{"HierarchicalRequirement":{"Description":"<p>ok</p>"}}""")
        server.route(attachmentPath, 200, queryResult("""{"ObjectID":"42","Name":"log.txt","Size":3}"""))
        server.route("/slm/attachment/42/log.txt", 500, "boom")

        exporter.exportArtifactJson(story, outDir())
        exporter.exportArtifactMarkdown(story, outDir())

        assertTrue(File(outDir(), "US1.json").readText().contains("\"downloaded\": false"))
        assertEquals(1, exporter.failedDownloadCount)
    }
}
