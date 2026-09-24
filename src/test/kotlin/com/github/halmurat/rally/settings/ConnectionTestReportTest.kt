package com.github.halmurat.rally.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Test Connection dialogs. IntelliJ renders Messages text as HTML (AlertDialog, with a
 * live-link listener), and the values in these texts come from Rally or the server — the API key
 * owner's DisplayName, the workspace name, error messages — so markup in them must reach the
 * dialog escaped: shown as written, never rendered, and never loading an `<img>`.
 */
class ConnectionTestReportTest {

    private val markup = "Team <img src='https://x.invalid/p.png'>"
    private val escaped = "Team &lt;img src='https://x.invalid/p.png'&gt;"

    @Test
    fun `a successful check reads as before, as literal HTML`() {
        val report = connectionTestReport("Jane Doe", "jane@example.com", WorkspaceCheck.Found("Main"), UsernameCheck.Resolved("jane@example.com", "Jane Doe"))
        assertFalse(report.warning)
        assertEquals(
            "<html>Connected successfully!<br><br>API Key Owner: Jane Doe (jane@example.com)<br><br>Workspace: Main<br><br>" +
                "Configured Username: jane@example.com<br>Resolved to: Jane Doe (valid)</html>",
            report.message
        )
    }

    @Test
    fun `the owner's DisplayName reaches the dialog only escaped`() {
        val report = connectionTestReport(markup, "jane@example.com", null, UsernameCheck.Resolved("jane@example.com", markup))
        assertShownLiterally(report.message)
    }

    @Test
    fun `the workspace name reaches the dialog only escaped`() {
        val report = connectionTestReport("Jane", "jane@example.com", WorkspaceCheck.Found(markup), UsernameCheck.Blank)
        assertShownLiterally(report.message)
    }

    @Test
    fun `a failed workspace check is a warning, with its reason escaped`() {
        val report = connectionTestReport("Jane", "jane@example.com", WorkspaceCheck.Unreadable("/workspace/1", markup), UsernameCheck.Skipped)
        assertTrue(report.warning)
        assertTrue(report.message, report.message.startsWith("<html>Connected, but the workspace check failed."))
        assertShownLiterally(report.message)
    }

    @Test
    fun `a failed username lookup has its reason escaped`() {
        val report = connectionTestReport("Jane", "jane@example.com", null, UsernameCheck.LookupFailed("jane@example.com", markup))
        assertShownLiterally(report.message)
    }

    @Test
    fun `the connection error is escaped`() {
        val message = connectionFailedMessage(RuntimeException("Rally said: $markup"))
        assertEquals("<html>Connection failed: Rally said: $escaped</html>", message)
    }

    private fun assertShownLiterally(message: String) {
        assertTrue(message, message.startsWith("<html>"))
        assertTrue(message, message.contains(escaped))
        assertFalse(message, message.contains("<img"))
    }
}
