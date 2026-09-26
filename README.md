# Rally Integration for IntelliJ IDEA

[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Browse and manage **Rally** (Broadcom Agile Central) work items without leaving your IDE. A dedicated
**Rally** tool window lists your User Stories, Defects, and Test Cases, shows their details, and lets you
create, update, and export them.

Works with IntelliJ-based IDEs 2024.1 through 2026.2.

## Features

- **Ticket list** — search by ID or name; filter by scope (All Tickets, My Tickets, User Stories, Defects,
  Test Cases, Recent Activity), state, project, and sprint.
- **Detail panel** — the ticket's HTML description (with inline images), plus tabs for linked
  **Test Cases**, **Tasks**, and **Attachments**. Double-click a test case to see its steps; double-click an
  attachment to save it.
- **Create** — User Stories and Defects (project, sprint, severity/priority, optional attachment), and Tasks
  under a story or defect.
- **Update** — move tickets to Defined, In-Progress, or Completed (multi-select supported) and edit story
  points.
- **Start Working** — creates and checks out a Git branch for the ticket (`feature/`, `bugfix/`, …),
  moves it to In-Progress and assigns it to you.
- **Export** — saves tickets and their linked test cases to JSON and Markdown, including attachments and
  inline images. Any failed download is reported, never silently skipped.
- **Sprint summary** — ticket counts, story points, planned velocity, and days remaining.
- Open any ticket in the Rally web UI or copy its ID from the context menu.

## Installation

**From JetBrains Marketplace:** Settings → Plugins → Marketplace → search for **Rally Integration** →
Install.

**From a ZIP:** download the ZIP from the releases page (or build it — see below), then Settings → Plugins →
⚙ → **Install Plugin from Disk…**, and restart the IDE.

## Setup

1. Create an API key in Rally: open your profile (top right) → **API Keys** → **Create New API Key**.
2. In the IDE, open **Settings → Tools → Rally** and fill in:

   | Field | Example |
   |-------|---------|
   | Server URL | `https://rally1.rallydev.com` |
   | API Key | `_abc123…` |
   | Workspace Ref | a workspace ID or ref |
   | Username | `you@company.com` |
   | Export Directory | where exports are written |

3. Click **Test Connection**, then **OK**.
4. Open the **Rally** tool window (bottom of the IDE).

The API key is stored in the IDE's password safe (the system keychain where available), not in plain-text
settings files.

## Troubleshooting

- **"Not configured" or an auth error** — re-check the Server URL and API key in Settings → Tools → Rally
  and use **Test Connection**.
- **"Waiting for API key..." at startup** — the IDE is reading the key from your system keychain; unlock or
  approve the keychain prompt.
- **Behind a proxy** — the plugin uses the IDE's proxy settings (Settings → Appearance & Behavior → System
  Settings → HTTP Proxy).
- **Server behind a reverse proxy** — the Server URL must use the same host and port that Rally puts in its
  own links.

Logs are in **Help → Show Log in Finder/Explorer** (search for `rally`).

## Building from source

Requires JDK 17.

```bash
./gradlew buildPlugin    # ZIP in build/distributions/
./gradlew test           # unit tests
./gradlew runIde         # sandbox IDE with the plugin installed
./gradlew verifyPlugin   # compatibility check across supported IDE versions
```

## Contributing

Issues and pull requests are welcome at
[github.com/Halmurat-Uyghur/rally-plugin](https://github.com/Halmurat-Uyghur/rally-plugin/issues).

## License

[MIT](LICENSE) © Halmurat Tahir

## Disclaimer

This is an independent, community-built plugin. It is not affiliated with, endorsed by, or sponsored by
Broadcom Inc. Rally and Agile Central are trademarks of Broadcom Inc.
