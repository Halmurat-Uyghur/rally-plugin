package com.github.halmurat.rally.export

import com.github.halmurat.rally.api.RallyApiClient
import com.github.halmurat.rally.api.RallyUserStory
import com.github.halmurat.rally.testutil.FakeRallyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The in-memory export path (MED-3) resolves each artifact's Description with a direct-ref
 * GET. A failed or empty-because-deleted fetch must fail the export visibly — never write
 * a file with an empty description and count it as exported (EXP-1).
 */
class RallyExporterDescriptionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeRallyServer()
    private val client = RallyApiClient(server.baseUrl, "fake-key")
    private val exporter = RallyExporter(client)

    private val storyPath = "/slm/webservice/v2.0/hierarchicalrequirement/1"
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

    @Test
    fun `export fails and writes nothing when the artifact no longer exists`() {
        server.route(storyPath, 200, FakeRallyServer.OBJECT_NOT_FOUND)

        assertThrows(Exception::class.java) { exporter.exportArtifactJson(story, outDir()) }
        assertThrows(Exception::class.java) { exporter.exportArtifactMarkdown(story, outDir()) }

        assertFalse(File(outDir(), "US1.json").exists())
        assertFalse(File(outDir(), "US1.md").exists())
    }

    @Test
    fun `export fails and writes nothing when the description request errors`() {
        server.route(storyPath, 500, """{"error":"boom"}""")

        assertThrows(Exception::class.java) { exporter.exportArtifactJson(story, outDir()) }

        assertFalse(File(outDir(), "US1.json").exists())
    }

    @Test
    fun `JSON and Markdown passes reuse one description fetch`() {
        // Both passes must see the same description. Before, each pass fetched on its
        // own and a failure wasn't cached, so a blip could leave JSON empty and MD full.
        server.route(storyPath, 200, """{"HierarchicalRequirement":{"Description":"<p>Accept SSO logins</p>"}}""")

        exporter.exportArtifactJson(story, outDir())
        exporter.exportArtifactMarkdown(story, outDir())

        assertEquals(1, server.hitCount(storyPath))
        assertTrue(File(outDir(), "US1.json").readText().contains("Accept SSO logins"))
        assertTrue(File(outDir(), "US1.md").readText().contains("Accept SSO logins"))
    }
}
