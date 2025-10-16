# Rally IntelliJ Plugin - Detailed Implementation Plan

## Project Overview

**Goal**: Create an IntelliJ IDEA plugin that integrates Rally (Broadcom Agile Central) with IntelliJ's task management system.

**Key Features**:
- Connect to Rally server using API Key authentication
- Browse and search User Stories, Defects, and Tasks
- Open tasks in IntelliJ with context switching
- View task details (ID, summary, description, status, owner)
- Quick link to Rally web interface

## Technical Foundation

### Technology Stack
- **Language**: Kotlin 1.9+
- **Build System**: Gradle 8.5+ with Kotlin DSL
- **JVM Target**: Java 21
- **IntelliJ Platform**: 2024.1+
- **IntelliJ Platform Gradle Plugin**: 2.x
- **Rally API**: WSAPI v2.0 REST API

### Rally API Details
- **Base URL**: `https://rally1.rallydev.com/slm/webservice/v2.0/`
- **Authentication**: API Key via `zsessionid` HTTP header
- **Artifact Types**:
  - `hierarchicalrequirement` - User Stories (FormattedID: S-###)
  - `defect` - Defects (FormattedID: DE###)
  - `task` - Tasks (FormattedID: TA###)
- **Query Format**: `?query=(Field operator Value)`
- **Pagination**: Max 2000 items per page, use `start` and `pagesize` params
- **Response Format**: JSON with `QueryResult` wrapper containing `Results` array

### IntelliJ Task API Components
1. **TaskRepositoryType**: Factory for repository and editor
2. **TaskRepository**: Core logic for connecting to Rally and fetching tasks
3. **Task**: Represents individual Rally work items
4. **RepositoryEditor**: Configuration UI in Settings

---

## Phase 1: Project Initialization

### Step 1.1: Create Gradle Build Files

**File**: `settings.gradle.kts`
```kotlin
rootProject.name = "rally-plugin"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("kotlin", "1.9.25")
            version("intellijPlatform", "2.1.0")

            plugin("kotlin", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
            plugin("intellijPlatform", "org.jetbrains.intellij.platform").versionRef("intellijPlatform")
        }
    }
}
```

**File**: `build.gradle.kts`
```kotlin
plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
}

group = "com.intellij.plugins"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugin("com.intellij.tasks")
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }

    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.intellij.rally"
        name = "Rally Integration"
        version = project.version.toString()
        description = """
            Integrates Rally (Broadcom Agile Central) with IntelliJ IDEA's task management system.
            Browse and work with User Stories, Defects, and Tasks directly from your IDE.
        """.trimIndent()

        vendor {
            name = "Rally Plugin Contributors"
        }

        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
                <li>Initial release</li>
                <li>Connect to Rally using API Key</li>
                <li>Browse User Stories, Defects, and Tasks</li>
                <li>Open tasks with context switching</li>
            </ul>
        """.trimIndent()
    }

    verifyPlugin {
        ides {
            recommended()
        }
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
}
```

**File**: `gradle.properties`
```properties
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m
```

### Step 1.2: Create Project Structure

```
rally-plugin/
├── src/
│   ├── main/
│   │   ├── kotlin/com/intellij/plugins/rally/
│   │   │   ├── RallyRepositoryType.kt
│   │   │   ├── RallyRepository.kt
│   │   │   ├── RallyTask.kt
│   │   │   ├── RallyRepositoryEditor.kt
│   │   │   ├── api/
│   │   │   │   ├── RallyApiClient.kt
│   │   │   │   ├── RallyApiModels.kt
│   │   │   │   └── RallyApiException.kt
│   │   │   └── ui/
│   │   │       └── RallyConfigurationPanel.kt
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── plugin.xml
│   │       └── icons/
│   │           └── rally.svg (13x13 icon)
│   └── test/
│       └── kotlin/com/intellij/plugins/rally/
│           └── RallyApiClientTest.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── README.md
└── IMPLEMENTATION_PLAN.md (this file)
```

### Step 1.3: Create plugin.xml

**File**: `src/main/resources/META-INF/plugin.xml`
```xml
<idea-plugin>
    <id>com.intellij.rally</id>
    <name>Rally Integration</name>
    <vendor>Rally Plugin Contributors</vendor>

    <description><![CDATA[
        Integrates Rally (Broadcom Agile Central) with IntelliJ IDEA's task management system.
        <br/><br/>
        <b>Features:</b>
        <ul>
            <li>Connect to Rally server using API Key authentication</li>
            <li>Browse User Stories, Defects, and Tasks</li>
            <li>Open tasks with automatic context switching</li>
            <li>Quick access to Rally web interface</li>
            <li>Search and filter tasks</li>
        </ul>
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.tasks</depends>

    <extensions defaultExtensionNs="com.intellij">
        <tasks.repositoryType implementation="com.intellij.plugins.rally.RallyRepositoryType"/>
    </extensions>
</idea-plugin>
```

---

## Phase 2: Rally API Client Implementation

### Step 2.1: Create API Models

**File**: `src/main/kotlin/com/intellij/plugins/rally/api/RallyApiModels.kt`

Data classes for:
- `RallyQueryResult<T>` - Wrapper for API responses
- `RallyArtifact` - Base for all Rally work items
- `RallyUserStory` - Hierarchical Requirement
- `RallyDefect` - Defect
- `RallyTaskItem` - Task
- `RallyUser` - User/Owner information
- `RallyRef` - Reference objects

### Step 2.2: Create API Exception

**File**: `src/main/kotlin/com/intellij/plugins/rally/api/RallyApiException.kt`

Custom exception class for Rally API errors with:
- HTTP status codes
- Error messages from Rally
- Connection errors

### Step 2.3: Create API Client

**File**: `src/main/kotlin/com/intellij/plugins/rally/api/RallyApiClient.kt`

Core API client with methods:
- `testConnection(): Boolean` - Verify API key and connection
- `getCurrentUser(): RallyUser` - Get authenticated user info
- `queryUserStories(query: String?, pageSize: Int): List<RallyUserStory>`
- `queryDefects(query: String?, pageSize: Int): List<RallyDefect>`
- `queryTasks(query: String?, pageSize: Int): List<RallyTaskItem>`
- `getArtifactByFormattedId(formattedId: String): RallyArtifact?`
- `queryAllArtifacts(query: String?, pageSize: Int): List<RallyArtifact>` - Combined query

HTTP client features:
- Use `java.net.http.HttpClient` (built-in Java 11+)
- Add `zsessionid` header for authentication
- Handle pagination automatically
- Parse JSON with Gson
- Proper error handling and timeouts

---

## Phase 3: IntelliJ Task Integration

### Step 3.1: Implement RallyTask

**File**: `src/main/kotlin/com/intellij/plugins/rally/RallyTask.kt`

Extends `com.intellij.tasks.Task` with:
- Map Rally artifact fields to Task interface
- Generate proper issue URLs
- Format display strings
- Handle different artifact types (US/DE/TA)

Key fields:
- `id`: FormattedID (e.g., "S-1234")
- `summary`: Name field from Rally
- `description`: Description with HTML stripped
- `created`: CreationDate
- `updated`: LastUpdateDate
- `issueUrl`: Deep link to Rally web UI
- `type`: TaskType (Feature/Bug/Other)
- `state`: TaskState (Open/Resolved)

### Step 3.2: Implement RallyRepository

**File**: `src/main/kotlin/com/intellij/plugins/rally/RallyRepository.kt`

Extends `com.intellij.tasks.TaskRepository` with:
- Constructor with `RallyRepositoryType`
- Configuration properties (serverUrl, apiKey, workspace, project)
- `clone(): TaskRepository` - Create copy
- `getIssues(query: String?, offset: Int, limit: Int): Task[]` - Main method
- `findTask(id: String): Task?` - Find by FormattedID
- `isConfigured(): Boolean` - Check if API key is set
- `testConnection()` - Validate connection
- `getComment(): String` - Repository name for display

Features to enable:
- `NATIVE_SEARCH` - Support server-side filtering
- `BASIC_HTTP_AUTHORIZATION` - Though we use API key

Caching strategy:
- Use IntelliJ's built-in task cache
- Cache timeout: 30 minutes
- Force refresh on explicit user action

### Step 3.3: Implement RallyRepositoryType

**File**: `src/main/kotlin/com/intellij/plugins/rally/RallyRepositoryType.kt`

Extends `com.intellij.tasks.TaskRepositoryType<RallyRepository>` with:
- `getName(): String` - "Rally"
- `getIcon(): Icon` - Rally logo (load from resources)
- `createRepository(): RallyRepository` - Factory method
- `getRepositoryClass(): Class<RallyRepository>`

---

## Phase 4: Configuration UI

### Step 4.1: Create Configuration Panel

**File**: `src/main/kotlin/com/intellij/plugins/rally/ui/RallyConfigurationPanel.kt`

UI components:
- Server URL text field (default: https://rally1.rallydev.com)
- API Key password field (with "How to get API key" link)
- Workspace text field (optional - for multi-workspace instances)
- Project filter text field (optional - filter by project name)
- Test Connection button

Validation:
- Server URL format
- API Key non-empty
- Test connection on blur or button click

### Step 4.2: Implement Repository Editor

**File**: `src/main/kotlin/com/intellij/plugins/rally/RallyRepositoryEditor.kt`

Extends `com.intellij.tasks.config.BaseRepositoryEditor`:
- Use `RallyConfigurationPanel` for UI
- `apply()` - Save settings to repository
- `createComponent()` - Build UI
- Add "Share URL" checkbox (optional)

---

## Phase 5: Icon and Resources

### Step 5.1: Create Rally Icon

**File**: `src/main/resources/icons/rally.svg`

- 13x13 pixels (standard IntelliJ icon size)
- Simple, recognizable Rally logo
- SVG format for scalability
- Suitable for both light and dark themes

Alternative: Use 16x16 PNG with @2x retina version

---

## Phase 6: Testing

### Step 6.1: Unit Tests

**File**: `src/test/kotlin/com/intellij/plugins/rally/RallyApiClientTest.kt`

Tests:
- API client initialization
- Query building
- JSON parsing
- Error handling
- Mock HTTP responses

### Step 6.2: Manual Testing Checklist

1. Install plugin in IDE
2. Open Settings → Tools → Tasks → Servers
3. Add Rally server
4. Configure with valid API key
5. Test connection (should succeed)
6. Open task browser (Tools → Tasks & Contexts → Open Task)
7. Search for tasks
8. Open a task (should switch context)
9. Verify task details in "Open Task" dialog
10. Click task link (should open Rally in browser)

---

## Phase 7: Documentation

### Step 7.1: Create README

**File**: `README.md`

Contents:
- Plugin description
- Features list
- Installation instructions
- Configuration guide
- How to obtain Rally API Key
- Screenshots
- Troubleshooting
- Contributing guidelines
- License

### Step 7.2: API Key Instructions

Document how to get Rally API Key:
1. Log in to Rally
2. Click user profile (top right)
3. Go to "API Keys" section
4. Generate new API Key
5. Copy the key (starts with underscore)
6. Paste into IntelliJ plugin configuration

---

## Phase 8: Build and Verify

### Step 8.1: Build Plugin

```bash
./gradlew buildPlugin
```

Output: `build/distributions/rally-plugin-1.0.0.zip`

### Step 8.2: Run Plugin Verifier

```bash
./gradlew verifyPlugin
```

Ensures compatibility with target IDE versions.

### Step 8.3: Test in IDE

```bash
./gradlew runIde
```

Opens IntelliJ with plugin installed for testing.

---

## Implementation Priority

### Must Have (v1.0.0)
1. ✅ Project setup with Gradle
2. ✅ Rally API client with authentication
3. ✅ Fetch User Stories and Defects
4. ✅ Display tasks in IntelliJ task browser
5. ✅ Basic configuration UI
6. ✅ Test connection functionality

### Nice to Have (v1.1.0)
- Task state updates (mark as In-Progress)
- Time tracking integration
- Custom field mapping
- Advanced query builder UI
- Pagination in task browser

### Future (v2.0.0)
- Create new defects from IDE
- Attach code/stacktraces to defects
- Branch naming from task ID
- Commit message templates
- Multi-workspace support
- OAuth authentication

---

## Key Design Decisions

1. **Why Kotlin?**
   - Modern, concise syntax
   - Null safety
   - Standard for IntelliJ plugins

2. **Why API Key over Username/Password?**
   - More secure (can be revoked)
   - Recommended by Rally
   - No session management needed

3. **Which Artifact Types?**
   - User Stories: Primary work items
   - Defects: Bug tracking
   - Tasks: Sub-items (may skip for v1.0)

4. **Pagination Strategy?**
   - Fetch first 200 items by default
   - Allow custom page size in settings
   - Background refresh every 30 minutes

5. **Error Handling?**
   - Show user-friendly messages
   - Log detailed errors for debugging
   - Graceful degradation (offline mode)

---

## Success Criteria

✅ Plugin installs without errors
✅ Connects to Rally with valid API key
✅ Displays at least User Stories and Defects
✅ Opens task in IDE with context switch
✅ Links to Rally web UI work correctly
✅ Configuration persists between IDE restarts
✅ No exceptions in normal usage
✅ Passes IntelliJ plugin verifier

---

## Resources

- [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [Rally WSAPI Documentation](https://techdocs.broadcom.com/us/en/ca-enterprise-software/valueops/rally/rally-help/reference/rally-web-services-api.html)
- [GitLab Repository Implementation](https://github.com/JetBrains/intellij-community/blob/master/plugins/tasks/tasks-core/src/com/intellij/tasks/gitlab/GitlabRepository.java)
- [Task API Forum Discussion](https://intellij-support.jetbrains.com/hc/en-us/community/posts/207098975-Documenation-about-Task-Api)

---

## Timeline Estimate

- **Phase 1** (Setup): 1-2 hours
- **Phase 2** (API Client): 3-4 hours
- **Phase 3** (Task Integration): 4-5 hours
- **Phase 4** (Configuration UI): 2-3 hours
- **Phase 5** (Icon/Resources): 1 hour
- **Phase 6** (Testing): 2-3 hours
- **Phase 7** (Documentation): 1-2 hours
- **Phase 8** (Build/Verify): 1 hour

**Total**: ~15-21 hours for v1.0.0

---

## Next Steps

1. Create `build.gradle.kts` and `settings.gradle.kts`
2. Create `plugin.xml`
3. Implement `RallyApiModels.kt`
4. Implement `RallyApiClient.kt`
5. Implement `RallyTask.kt`
6. Implement `RallyRepository.kt`
7. Implement `RallyRepositoryType.kt`
8. Implement `RallyRepositoryEditor.kt`
9. Add Rally icon
10. Test and iterate

**Ready to begin implementation!** 🚀
