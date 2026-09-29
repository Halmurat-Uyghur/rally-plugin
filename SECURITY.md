# Security Policy

## Supported versions

Only the latest release on JetBrains Marketplace receives security fixes.

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Report them privately through
[GitHub's private vulnerability reporting](https://github.com/Halmurat-Uyghur/rally-plugin/security/advisories/new)
(Security tab → Report a vulnerability).

Include the plugin and IDE versions, what an attacker can do, and steps to reproduce. If the problem
involves Rally content (for example, a crafted ticket description), attach a minimal made-up sample
rather than data from a real workspace.

Reports are handled as quickly as possible.

## Handling your Rally data

- The plugin stores your Rally API key only in the IDE's password safe, never in settings files or logs.
- When sharing logs or screenshots in an issue, remove API keys, `zsessionid` headers, workspace and
  project IDs, and any ticket content you aren't allowed to share.
