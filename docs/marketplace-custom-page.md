## Getting started

1. **Create a Rally API key.** In Rally, open your profile (top right) → **API Keys** → **Create New API Key**.
2. **Open the plugin settings.** In the IDE, go to **Settings → Tools → Rally**.
3. **Fill in the connection:**

   | Field | What to enter |
   |---|---|
   | Server URL | Your Rally address, for example `https://rally1.rallydev.com` |
   | API Key | The key from step 1 |
   | Workspace Ref | Optional. A workspace ID or ref; leave blank to use your default workspace |
   | Username | Your Rally username (usually your email). Needed for **My Tickets**, **Assign to me** and **Start Working** |
   | Export Directory | Optional. Where exports are saved; defaults to `rally_testcases` in the project root |
   | Page Size | Items fetched per request, 25–200 (default 200) |

4. Click **Test Connection**. It confirms your user, checks the workspace, and tells you if the username matches no Rally user.
5. Click **OK**, then open the **Rally** tool window at the bottom of the IDE.

The API key is stored in the IDE's password safe (the system keychain where available), never in plain-text settings files or logs.

---

## Browsing tickets

The toolbar holds four selectors and a search box:

- **Scope:** All Tickets, My Tickets, User Stories, Defects, Test Cases, Recent Activity
- **State:** Any State, Idea, Defined, In-Progress, Completed, Accepted, or **Active** (hides Idea, Completed and Accepted)
- **Project:** any project in your workspace, or All Projects. Your choice is remembered between sessions.
- **Sprint:** All Sprints, or one sprint (shown with its date range)

**Search** filters the loaded list by ID or name as you type and falls back to a server-side search when nothing local matches. Changing the state filter re-filters instantly without another request.

The footer shows a **sprint summary**: ticket counts by state, story points, planned velocity and days remaining.

If a list is larger than the page size, the status line says so (for example *200 of 934 loaded*), so a truncated list never looks complete.

## Ticket details

Select a ticket and the detail panel opens on the right:

- **Header:** ID, name, state, owner, points, sprint, and severity/priority for defects. Blocked tickets show their blocked reason.
- **Description:** the ticket's formatted description, including inline images, recolored to match your IDE theme.
- **Test Cases tab:** linked test cases with their method (Automated/Manual) and last verdict (Pass/Fail). Select a test case in the main list to see its **Test Steps** (step, input, expected result).
- **Tasks tab:** child tasks with state, owner and remaining hours. Create a new task for the selected story or defect from here.
- **Attachments tab:** file type and size. Double-click to save one, or right-click → **Open in Browser**.

Right-click any ticket for **Open in Browser**, **Copy FormattedID**, **Edit Points** and **Export to JSON/Markdown**.

## Creating tickets

- **Create User Story:** name, project, sprint, description, *Assign to me*, and an optional attachment
- **Create Defect:** the same fields plus severity and priority
- **Create Task:** from the Tasks tab, linked to the selected story or defect

Attachments can be any file type, up to Rally's 50 MB limit. If the ticket is created but the attachment upload fails, the plugin says so, so you don't create a duplicate.

## Updating tickets

- **Change state:** move one or more tickets to **Defined**, **In-Progress** or **Completed** from the toolbar or context menu. The plugin asks for confirmation before changing several tickets, and always before marking one Completed.
- **Edit Points:** set story points on a user story or defect from the context menu.

## Start Working

Select a single user story or defect and click **Start Working**:

1. Pick a branch prefix: `feature`, `bugfix`, `hotfix`, `refactor`, `chore` or `test`. One is preselected based on the ticket type.
2. The plugin creates and checks out a branch for the ticket, moves the ticket to **In-Progress**, and assigns it to you.

The Rally changes happen only after the branch checkout succeeds. In a multi-repository project, the plugin uses the repository that contains the project root and asks you to resolve it if that's ambiguous. Start Working needs the bundled **Git** plugin; everything else works without it.

## Exporting

Select one or more tickets and click **Export**. Each ticket is saved as **JSON** and **Markdown**, together with:

- its linked test cases and their steps
- its attachments
- the images from its description, rewritten to point at the local copies

If any download fails, the export tells you which parts are incomplete. Nothing is skipped silently. Re-exporting a ticket overwrites its files instead of creating copies.

---

## Troubleshooting

| What you see | What to do |
|---|---|
| **Not configured** | Fill in the Server URL and API key in **Settings → Tools → Rally**. |
| **Auth error** | The API key is wrong or was revoked. Create a new one in Rally and use **Test Connection**. |
| **Waiting for API key...** at startup | The IDE is reading the key from your system keychain. Unlock it or approve the keychain prompt. |
| **API key unavailable** | The keychain read failed. Re-enter the key in **Settings → Tools → Rally**. |
| **My Tickets** asks for a username | Set **Username** in the settings. The plugin won't list everyone's tickets in its place. |
| Status line ends with **(incomplete)** | Part of the query (stories or defects) failed. The IDE notification says why; click **Refresh** to retry. |
| Can't connect through a corporate proxy | The plugin uses the IDE's proxy settings: **Settings → Appearance & Behavior → System Settings → HTTP Proxy**. |
| Rally is behind a reverse proxy | The Server URL must use the same host and port that Rally puts in its own links. |

Logs are under **Help → Show Log in Finder/Explorer**. Search for `rally`, and remove API keys and workspace IDs before sharing any lines.

## Privacy and security

- The plugin talks only to the Rally server you configure. It sends no telemetry.
- The API key lives in the IDE's password safe and is never written to logs.
- Descriptions are rendered with remote content blocked: images load only from data the plugin has already downloaded, and scripts, forms and external stylesheets are ignored.

## Compatibility

- IntelliJ IDEA and other IntelliJ-based IDEs, **2024.1 through 2026.2**
- Rally SaaS and on-premises servers using Web Services API 2.0 with API key authentication
- The **Git** plugin is optional and is needed only for Start Working

## Support

- Report a bug or request a feature: [GitHub Issues](https://github.com/Halmurat-Uyghur/rally-plugin/issues)
- Report a security problem privately: [Security advisories](https://github.com/Halmurat-Uyghur/rally-plugin/security/advisories/new)
- Source code: [github.com/Halmurat-Uyghur/rally-plugin](https://github.com/Halmurat-Uyghur/rally-plugin) (MIT license)

---

*This is an independent, community-built plugin. It is not affiliated with, endorsed by, or sponsored by Broadcom Inc. Rally and Agile Central are trademarks of Broadcom Inc.*
