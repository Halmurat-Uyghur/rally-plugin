package com.github.halmurat.rally.settings

import com.github.halmurat.rally.api.RallyApiException
import com.github.halmurat.rally.api.RallyAuthenticationException
import com.github.halmurat.rally.api.RallyConnectionException
import com.github.halmurat.rally.api.RallySecurityException
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
    fun `a failed workspace lookup is a warning that does not blame the ref, with its reason escaped`() {
        val report = connectionTestReport("Jane", "jane@example.com", WorkspaceCheck.LookupFailed("/workspace/1", markup), UsernameCheck.Blank)
        assertTrue(report.warning)
        assertTrue(report.message, report.message.startsWith("<html>Connected, but the workspace check failed."))
        assertTrue(report.message, report.message.contains("Workspace Ref '/workspace/1' could not be verified (lookup failed: $escaped). If it is correct, loads will still work — try Test Connection again."))
        assertFalse(report.message, report.message.contains("Check the Workspace Ref"))
        assertShownLiterally(report.message)
    }

    @Test
    fun `a transient workspace-read failure is a lookup failure, not the ref's fault`() {
        // What getWorkspaceName throws once the retries are exhausted (429/5xx) or the send fails,
        // a proxy challenge it can't answer, and a reply that isn't Rally JSON (a maintenance page).
        val transient = listOf(
            RallyApiException("Rally API request failed with status 503", 503, null),
            RallyApiException("Rally API request failed with status 500", 500, null),
            RallyApiException("Rally API request failed with status 429", 429, null),
            RallyApiException("Rally API request failed with status 407", 407, null),
            RallyConnectionException("Failed to connect to Rally server: HTTP/1.1 header parser received no bytes"),
            IllegalStateException("Not a JSON Object: \"<html>Down for maintenance</html>\""),
        )
        for (e in transient) {
            assertEquals(e.toString(), WorkspaceCheck.LookupFailed("12345", e.message!!), workspaceCheckFailure("12345", e))
        }
    }

    @Test
    fun `a workspace Rally cannot read for that ref stays the ref's fault`() {
        // 200-with-Errors (null status), 400, 404, no access, and a ref that isn't even a URL.
        val refsFault = listOf(
            RallyApiException("Rally query error (workspace): Cannot find object to read"),
            RallyApiException("Rally API request failed with status 400", 400, null),
            RallyApiException("Rally API endpoint not found. Check your server URL.", 404, null),
            RallyAuthenticationException("Access forbidden. Check your permissions.", 403),
            RallySecurityException("Malformed URL rejected: https://rally1.rallydev.com/slm/webservice/v2.0/workspace/My Space"),
        )
        for (e in refsFault) {
            assertEquals(e.toString(), WorkspaceCheck.Unreadable("12345", e.message!!), workspaceCheckFailure("12345", e))
        }
    }

    @Test
    fun `the Username is still looked up unless the Workspace Ref itself is the problem`() {
        assertTrue(usernameLookupSkipped(WorkspaceCheck.Unreadable("12345", "Cannot find object to read")))
        assertFalse(usernameLookupSkipped(WorkspaceCheck.LookupFailed("12345", "status 503")))
        assertFalse(usernameLookupSkipped(WorkspaceCheck.Found("Main")))
        assertFalse(usernameLookupSkipped(null))
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
