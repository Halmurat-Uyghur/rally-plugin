# JetBrains Marketplace listing — 1.0.0

Draft of everything the Marketplace asks for, per JetBrains'
[Best practices for listing your plugin](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
(last modified 23 July 2026).

## 1. Decisions before upload

These live in `plugin.xml` and can only change by uploading a new version.

- [x] **Plugin name.** Done: renamed to **Rally Work Items** (the old name, "Rally Integration", went against two of the guidelines:
  - they say not to use generic terms like "Integration" or "Support";
  - they recommend at most 20 characters).

  Suggested replacements (the plugin `<id>` stays frozen, so existing installs still update):

  | Candidate | Chars | Note |
  |---|---|---|
  | **Rally Work Items** | 16 | Describes what the plugin shows. Recommended |
  | Rally Tickets | 13 | Shorter, but "tickets" is less Rally-native |
  | Rally Agile Central | 19 | Uses both Broadcom marks, so it's the most trademark-heavy |

  Every option contains "Rally" because the name has to say what it connects to. The
  "not affiliated with Broadcom" disclaimer in the description is what makes that acceptable
  (nominative use). If review still objects, fall back to something like "Agile Work Items for Rally".
- [x] **Description summary.** The first 40 characters become the preview card text. The current
  opening is "Browse and manage Rally (Broadcom Agile " and gets cut off mid-phrase. Suggested new first sentence:
  > Browse, update, and export Rally work items in the IDE. A dedicated tool window lists …

  That makes the card read "Browse, update, and export Rally work i…".
- [x] **Inline links.** The guidelines recommend putting issue-tracker and source links in the
  description, since that's the only place they appear inside the IDE's Plugin Manager. Add this before the disclaimer:
  ```html
  <p><a href="https://github.com/Halmurat-Uyghur/rally-plugin/issues">Report an issue</a> ·
  <a href="https://github.com/Halmurat-Uyghur/rally-plugin">Source code</a></p>
  ```
  This only works once the repo is **public**. JetBrains requires every external link on the page to be reachable.
- [x] Rebuild the ZIP after any `plugin.xml` change:
  `./gradlew clean test buildPlugin verifyPlugin`.

## 2. Upload form (plugins.jetbrains.com → Upload plugin)

| Field | Value |
|---|---|
| Plugin file | `build/distributions/rally-plugin-1.0.0.zip` |
| License | MIT, with the link `https://github.com/Halmurat-Uyghur/rally-plugin/blob/main/LICENSE` |
| Tags | Pick from the dropdown. The closest fits are likely **Tools Integration** and a productivity or task-tracking tag. Only choose tags that actually fit |
| Channel | Stable (the default) |
| Pricing | Free |

## 3. After upload: admin panel → General / Technical Information

| Field | Value |
|---|---|
| Source code | `https://github.com/Halmurat-Uyghur/rally-plugin` |
| Issue tracker | `https://github.com/Halmurat-Uyghur/rally-plugin/issues` (enable Issues on the repo) |
| Documentation | `https://github.com/Halmurat-Uyghur/rally-plugin#readme` |
| Video | Optional. A YouTube demo under 5 minutes |

### Getting started section (HTML, pasted into the admin panel)

```html
<ol>
  <li>Open <b>Settings | Tools | Rally</b>.</li>
  <li>Enter your Rally server URL, for example <code>https://rally1.rallydev.com</code>.</li>
  <li>Create an API key in Rally (your profile | <b>API Keys</b>) and paste it into <b>API Key</b>.
      It is stored in the IDE's password safe.</li>
  <li>Optionally enter your workspace ref, and your Rally username to use <b>My Tickets</b>
      and <b>Start Working</b>.</li>
  <li>Click <b>Test Connection</b>, then <b>OK</b>.</li>
  <li>Open the <b>Rally</b> tool window (bottom bar), pick a project and sprint, and select a ticket.</li>
</ol>
<p>Start Working needs the bundled Git plugin. Everything else works without it.</p>
```

## 4. Screenshot checklist

### Capture rules (from JetBrains)

- [ ] At least **1200 × 760 px**, and **every screenshot the same aspect ratio**. On a Retina Mac,
  sizing the IDE window to 1200 × 760 points gives 2400 × 1520 px, which is the same 1.58:1 ratio.
- [ ] **Default IDE theme.** Use the default new UI theme (Dark in current versions) and the default font size.
  Don't use custom themes.
- [ ] Only the IDE window: no desktop background, Dock, browser windows, or other apps.
  Use ⌘⇧4 then Space to capture a single window, and then crop the shadow out.
- [ ] Sharp and readable. No scaling down that makes the ticket list text blur.
- [ ] Overlay captions or one informational slide are allowed, but don't use them to promote anything unrelated.

### Data hygiene (the most important item)

- [ ] **No real employer or client data.** The guidelines ban personal information, and ticket
  names, project names, people's names and descriptions from your work Rally could expose
  confidential material. Use a free Rally trial or a separate sandbox workspace filled with made-up content:
  - a project such as "Demo Project";
  - sprints such as "Sprint 14" and "Sprint 15" with current dates, so the footer shows days remaining;
  - 10–15 user stories and defects spread across all ScheduleStates, with a couple of them blocked;
  - one story with an inline image in the description, 2–3 linked test cases (Automated and Manual,
    Pass and Fail), 2 tasks, and 2 attachments (a PNG and a PDF);
  - an owner name that is clearly fictional, or your own if you're fine publishing it.
- [ ] No real API key, server URL or email address visible. That includes the Settings screenshot:
  the API key field must show dots only, and the username should be a demo account.
- [ ] The project in the editor behind the tool window is harmless (for example, this plugin's own
  source, or an empty sample project).
- [ ] Run from `./gradlew runIde` or a clean IDE with only this plugin added, so other
  plugins' tool-window icons don't show up in the frame.

### Shot list (in upload order; the first one is the hero image)

1. [ ] **Tool window overview (hero).** Ticket list with colored state chips, the detail panel open on a
   story that has a rendered description with an inline image, the Test Cases tab showing Method and Verdict
   badges, and the sprint footer (points, velocity, days remaining).
2. [ ] **Filters.** The scope dropdown or state filter open, with the project and sprint switchers visible,
   so the "My Tickets / User Stories / Defects / Test Cases" choices are readable.
3. [ ] **Test case steps.** A test case selected, showing the Test Steps tab (Step, Input, Expected Result).
4. [ ] **Create dialog.** Create Defect filled in (Name, Project, Sprint, Severity, Priority, an attachment chosen).
5. [ ] **Start Working.** The branch-prefix dialog with the generated branch name (for example `feature/US1234-...`).
6. [ ] **Export result.** The success balloon, optionally next to the exported Markdown file opened in the editor.
7. [ ] **Settings.** Settings | Tools | Rally after a successful Test Connection, with the key masked.

Optional: a 10–20 s GIF of selecting a ticket (with the detail panel expanding) and changing its state.
Keep it at the same aspect ratio as the stills.

### Before uploading the images

- [ ] All files have the same pixel dimensions (check with `sips -g pixelWidth -g pixelHeight *.png`).
- [ ] Zoom to 100% on each image and check again for names, emails, URLs or company terms.
- [ ] Keep the originals in a private folder, not in the public repo, unless they are fully sanitized.

## 5. Final pre-submit pass

- [ ] Repo made public (after reviewing `docs/audits/`, `.claude/`, `AGENTS.md` and `GEMINI.md` for anything private).
- [x] README's Marketplace section uses the final plugin name.
- [ ] ZIP rebuilt after any `plugin.xml` edits, tests pass, and `verifyPlugin` reports Compatible on all 7 IDEs.
- [ ] Uploaded, and the listing fields and screenshots filled in. Review usually takes a few working days.
- [ ] After approval: create a Marketplace token, set `PUBLISH_TOKEN`, and tag `v1.0.0`.
