# Rally Integration Plugin for IntelliJ IDEA

[![Build](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Version](https://img.shields.io/badge/version-1.0.0-blue)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-orange)]()

Integrates **Rally** (Broadcom Agile Central) with IntelliJ IDEA's built-in task management system.

Browse and work with Rally User Stories, Defects, and Tasks directly from your IDE without switching context.

## Features

- **Seamless Integration**: Connect to Rally using secure API Key authentication
- **Browse Work Items**: View User Stories, Defects, and Tasks in IntelliJ's task browser
- **Context Switching**: Open Rally tasks with automatic IDE context switching (branches, changesets)
- **Search & Filter**: Search for tasks by ID or name, filter by project
- **Task Details**: View task summary, description, status, owner, and other details
- **Quick Links**: Jump directly to Rally web interface from the IDE

## Installation

### From JetBrains Marketplace (Future)
1. Open IntelliJ IDEA
2. Go to **Settings/Preferences → Plugins**
3. Click **Marketplace** tab
4. Search for "Rally Integration"
5. Click **Install**

### From Source
1. Clone this repository
2. Run `./gradlew buildPlugin`
3. Install the plugin from disk: `build/distributions/rally-plugin-1.0.0.zip`

## Configuration

### Step 1: Get Your Rally API Key

1. Log in to your Rally instance (e.g., `https://rally1.rallydev.com`)
2. Click on your **user profile** (top right corner)
3. Navigate to **API Keys** section
4. Click **Create New API Key**
5. Copy the generated key (it starts with an underscore `_`)

> **Important**: Keep your API key secure. It provides full access to your Rally account.

### Step 2: Configure the Plugin

1. Open **Settings/Preferences** (Ctrl+Alt+S / Cmd+,)
2. Navigate to **Tools → Tasks → Servers**
3. Click **+** (Add) button
4. Select **Rally** from the list
5. Fill in the configuration:

   | Field | Description | Example |
   |-------|-------------|---------|
   | **Server URL** | Your Rally server URL | `https://rally1.rallydev.com` |
   | **API Key** | Your Rally API Key | `_abc123...` |
   | **Workspace** (optional) | Workspace name if you have multiple | `My Workspace` |
   | **Project Filter** (optional) | Filter tasks by project name | `Mobile App` |

6. Click **Test** to verify connection
7. Click **OK** to save

## Usage

### Open Task Browser

1. Go to **Tools → Tasks & Contexts → Open Task** (Alt+Shift+N / ⌥⇧N)
2. The task browser will show Rally work items
3. Use the search box to filter by:
   - **FormattedID**: `S-1234`, `DE5678`
   - **Name**: Any text in the task name

### Switch to a Task

1. Select a task from the list
2. Click **OK** or press Enter
3. IntelliJ will:
   - Save your current context
   - Create/switch to a branch (optional)
   - Open the task details
   - Track time spent (if configured)

### View Task Details

- **Task Summary**: Formatted ID and name
- **Description**: Full task description (HTML stripped)
- **Status**: Current schedule state (e.g., "In-Progress", "Completed")
- **Type**: Bug (Defect), Feature (User Story), or Other (Task)
- **Link**: Click to open in Rally web interface

### Return to Previous Context

1. Go to **Tools → Tasks & Contexts → Switch Task**
2. Select your previous task or context
3. IntelliJ restores your working state

## Supported Rally Artifacts

| Rally Type | FormattedID Prefix | IntelliJ Type |
|------------|-------------------|---------------|
| User Story (HierarchicalRequirement) | `S-####` or `US####` | Feature |
| Defect | `DE####` | Bug |
| Task | `TA####` | Other |

## Architecture

### Plugin Structure

```
rally-plugin/
├── src/main/kotlin/com/intellij/plugins/rally/
│   ├── RallyRepositoryType.kt       # Factory for Rally repositories
│   ├── RallyRepository.kt           # Core integration logic
│   ├── RallyTask.kt                 # Task representation
│   ├── RallyRepositoryEditor.kt     # Configuration UI
│   └── api/
│       ├── RallyApiClient.kt        # HTTP client for Rally WSAPI
│       ├── RallyApiModels.kt        # Data models
│       └── RallyApiException.kt     # Exception handling
└── src/main/resources/
    └── META-INF/plugin.xml          # Plugin configuration
```

### Rally WSAPI Integration

- **API Version**: WSAPI v2.0 REST API
- **Authentication**: API Key via `zsessionid` HTTP header
- **Base URL**: `{server}/slm/webservice/v2.0/`
- **Endpoints Used**:
  - `/hierarchicalrequirement` - User Stories
  - `/defect` - Defects
  - `/task` - Tasks
- **Query Format**: `?query=(Field operator Value)&fetch=fields&pagesize=200`

### IntelliJ Platform APIs

- **Task Management**: `com.intellij.tasks` module
- **Extension Point**: `com.intellij.tasks.repositoryType`
- **Base Classes**:
  - `BaseRepositoryType` - Repository factory
  - `BaseRepositoryImpl` - Repository implementation
  - `Task` - Task representation

## Troubleshooting

### Connection Issues

**Problem**: "Connection failed" error

**Solutions**:
- Verify your Rally server URL is correct
- Check your network connection
- Ensure you're not behind a firewall blocking Rally
- Try accessing Rally in a web browser

### Authentication Issues

**Problem**: "Authentication failed" error

**Solutions**:
- Verify your API key is correct (should start with `_`)
- Check if your API key has expired (regenerate in Rally)
- Ensure your Rally account has proper permissions
- Try copying the API key again (avoid extra spaces)

### No Tasks Showing

**Problem**: Task browser is empty

**Solutions**:
- Check if you have tasks assigned in Rally
- Try removing project filter to see all tasks
- Verify your workspace setting (if using multiple workspaces)
- Look at IntelliJ logs: **Help → Show Log in Finder/Explorer**

### API Rate Limiting

**Problem**: Intermittent failures or slow responses

**Solutions**:
- Rally enforces API rate limits
- Reduce page size in repository settings
- Avoid frequent manual refreshes
- Contact your Rally administrator about rate limits

## Development

### Building from Source

```bash
# Clone the repository
git clone https://github.com/yourusername/rally-plugin.git
cd rally-plugin

# Build the plugin
./gradlew buildPlugin

# Run in a test IDE
./gradlew runIde

# Run tests
./gradlew test

# Verify plugin compatibility
./gradlew verifyPlugin
```

### Project Requirements

- **JDK**: Java 17 or higher
- **Gradle**: 8.5+ (wrapper included)
- **IntelliJ IDEA**: 2024.1+ for development

### Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Commit your changes: `git commit -m 'Add amazing feature'`
4. Push to the branch: `git push origin feature/amazing-feature`
5. Open a Pull Request

### Code Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Write unit tests for new features

## Roadmap

### Version 1.1.0
- [ ] Task state updates (mark as In-Progress, Completed)
- [ ] Time tracking integration
- [ ] Custom field mapping
- [ ] Better error messages with suggested fixes

### Version 2.0.0
- [ ] Create new defects from IDE
- [ ] Attach code snippets to defects
- [ ] Branch naming templates based on task ID
- [ ] Commit message templates with task references
- [ ] Multi-workspace support with switcher
- [ ] OAuth authentication (in addition to API Key)

### Version 3.0.0
- [ ] Inline task comments and discussions
- [ ] Rally portfolio view
- [ ] Sprint/Iteration planning board
- [ ] Burndown charts and velocity metrics

## Related Links

- [Rally (Broadcom Agile Central)](https://www.broadcom.com/products/software/value-stream-management/rally)
- [Rally WSAPI Documentation](https://techdocs.broadcom.com/us/en/ca-enterprise-software/valueops/rally/rally-help/reference/rally-web-services-api.html)
- [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [IntelliJ Task Management](https://www.jetbrains.com/help/idea/managing-tasks-and-context.html)

## License

Copyright 2025 Rally Plugin Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Acknowledgments

- Inspired by [IJPL-92100](https://youtrack.jetbrains.com/issue/IJPL-92100) feature request
- Built with [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/)
- Rally is a product of [Broadcom Inc.](https://www.broadcom.com/)

## Support

- **Issues**: [GitHub Issues](https://github.com/yourusername/rally-plugin/issues)
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/rally-plugin/discussions)
- **Email**: support@example.com

---

Made with ❤️ for the IntelliJ and Rally communities
