# Rally Plugin - Testing Guide

## Quick Start: Test the Plugin Locally

### Method 1: Run in Development IDE (Recommended for Testing)

This will launch a new IntelliJ instance with your plugin installed:

```bash
# Run IntelliJ with the plugin
./gradlew runIde
```

**What happens:**
- Gradle downloads IntelliJ IDEA Community 2024.1
- Creates a sandbox environment
- Installs your Rally plugin
- Launches IntelliJ in a new window

**Using Java 21 explicitly:**
```bash
JAVA_HOME=/Users/halmurat/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home ./gradlew runIde
```

---

## Step-by-Step Testing Workflow

### 1. Launch the Test IDE

```bash
./gradlew runIde
```

This will take a few moments on first run as it downloads IntelliJ.

### 2. Verify Plugin Installation

Once IntelliJ opens:
1. Go to **Settings/Preferences** (Cmd+, or Ctrl+Alt+S)
2. Navigate to **Plugins**
3. Look for **Rally Work Items** in the installed plugins list
4. You should see it enabled ✅

### 3. Configure Rally Connection

1. Go to **Settings → Tools → Tasks → Servers**
2. Click **+** (Add Server)
3. Select **Rally** from the dropdown
4. Fill in the configuration:

   | Field | Example |
   |-------|---------|
   | Server URL | `https://rally1.rallydev.com` |
   | API Key | `_your_api_key_here` |
   | Workspace | (optional) |
   | Project Filter | (optional) |

5. Click **Test** button
   - ✅ Success: "Connection successful"
   - ❌ Failure: Check error message

6. Click **OK** to save

### 4. Test Browsing Rally Tasks

1. Press **Alt+Shift+N** (or **⌥⇧N** on Mac)
   - Or go to **Tools → Tasks & Contexts → Open Task**

2. You should see a dialog with Rally tasks:
   - User Stories (S-####)
   - Defects (DE####)
   - Tasks (TA####)

3. Try searching:
   - By ID: Type `S-1234` or `DE5678`
   - By name: Type any keyword

4. Select a task and press **Enter**
   - IntelliJ will switch to that task context
   - The task appears in the bottom right corner

### 5. Test Task Details

1. In the Open Task dialog, hover over a task
2. You should see:
   - FormattedID (e.g., S-1234)
   - Name/Summary
   - Description
   - Status (Open, In-Progress, Resolved)
   - Type (Feature, Bug, Other)

3. Click the link icon 🔗 to open in Rally web UI

---

## Debugging the Plugin

### Enable Debug Logging

1. In the test IDE, go to **Help → Diagnostic Tools → Debug Log Settings**
2. Add: `#com.intellij.plugins.rally`
3. Click **OK**
4. Logs will appear in **Help → Show Log in Finder/Explorer**

### Watch Console Output

When you run `./gradlew runIde`, the terminal shows:
- Plugin initialization messages
- API calls to Rally
- Errors and warnings

Look for lines starting with:
- `[Rally]` or `[gradle-intellij-plugin]`

### Common Debug Scenarios

**Connection Test Fails:**
```
Look for:
- Rally authentication failed
- Connection failed
- Check server URL and API key
```

**No Tasks Appear:**
```
Look for:
- Rally repository is not configured
- Empty response from Rally API
- Check workspace and project filters
```

**Tasks Load Slowly:**
```
- Rally has rate limits
- Large workspaces may take time
- Check page size setting
```

---

## Advanced Testing

### Test with Different Rally Instances

1. Change Server URL to your organization's Rally instance
2. Test with different API keys (different users)
3. Try different workspaces

### Test Search Functionality

Try these queries in the task browser:
- `S-` - Should show user stories
- `DE` - Should show defects
- `bugfix` - Should search by name
- Empty - Should show all recent tasks

### Test Error Handling

1. **Invalid API Key:**
   - Configure with wrong API key
   - Test connection
   - Should show: "Authentication failed"

2. **Wrong Server URL:**
   - Use `https://invalid.rally.com`
   - Test connection
   - Should show: "Connection failed"

3. **No Network:**
   - Disconnect network
   - Try to browse tasks
   - Should handle gracefully

---

## Performance Testing

### Run Performance Tests

```bash
./gradlew runIdePerformanceTest
```

This runs IntelliJ with performance profiling enabled.

### Monitor Memory Usage

1. In test IDE, go to **Help → Diagnostic Tools → Show Memory Indicator**
2. Browse many Rally tasks
3. Watch memory usage (should stay reasonable)

---

## UI Testing

### Run with UI Test Framework

```bash
./gradlew runIdeForUiTests
```

This launches IntelliJ with robot-server for automated UI testing.

---

## Plugin Verification

### Verify Plugin Structure

```bash
./gradlew verifyPlugin
```

Checks:
- plugin.xml is valid
- All required files present
- Proper plugin structure

### Verify Plugin Compatibility

```bash
./gradlew runPluginVerifier
```

Tests plugin against multiple IntelliJ versions for compatibility.

---

## Rebuild and Retest

### After Code Changes

```bash
# Clean build
./gradlew clean buildPlugin

# Run with changes
./gradlew runIde
```

The test IDE will reload with your latest changes.

### Hot Reload (if available)

Some changes can be hot-reloaded:
1. Make code changes
2. In test IDE: **File → Invalidate Caches / Restart**
3. Select **Invalidate and Restart**

---

## Testing Checklist

Before considering the plugin ready:

### ✅ Configuration
- [ ] Plugin appears in Settings → Plugins
- [ ] Rally server option appears in Tasks → Servers
- [ ] Can add Rally server configuration
- [ ] Server URL field works
- [ ] API Key field works (masked)
- [ ] Test Connection button works
- [ ] Configuration persists after restart

### ✅ Functionality
- [ ] Can fetch Rally tasks
- [ ] User Stories appear (S-####)
- [ ] Defects appear (DE####)
- [ ] Tasks appear (TA####)
- [ ] Search by ID works
- [ ] Search by name works
- [ ] Task details display correctly
- [ ] Can switch to a task
- [ ] Link to Rally web UI works

### ✅ Error Handling
- [ ] Invalid API key shows error
- [ ] Invalid server URL shows error
- [ ] No network shows error
- [ ] Empty workspace handled
- [ ] Large task lists handled

### ✅ Performance
- [ ] Tasks load in reasonable time (<5 sec)
- [ ] No memory leaks with many tasks
- [ ] UI remains responsive

### ✅ UI/UX
- [ ] Rally icon appears
- [ ] Configuration UI is clear
- [ ] Error messages are helpful
- [ ] Task browser shows tasks clearly
- [ ] Task details are readable

---

## Troubleshooting Test Environment

### "Could not determine the dependencies"

```bash
# Use specific Java version
JAVA_HOME=/Users/halmurat/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home ./gradlew runIde
```

### "Port already in use"

Another IntelliJ instance is running. Close it and try again.

### "Plugin failed to load"

Check the IDE log:
```bash
# Location shown in terminal output
tail -f /path/to/idea-sandbox/system/log/idea.log
```

### Clean Sandbox

```bash
# Remove sandbox and start fresh
rm -rf build/idea-sandbox
./gradlew runIde
```

---

## Next Steps After Testing

1. ✅ **Test with Real Data** - Use actual Rally API key and data
2. 📝 **Document Issues** - Note any bugs or improvements
3. 🔄 **Iterate** - Fix issues and retest
4. 📦 **Package** - Create final build: `./gradlew buildPlugin`
5. 🚀 **Deploy** - Install in production IntelliJ or publish to Marketplace

---

## Quick Reference Commands

```bash
# Build plugin
./gradlew buildPlugin

# Run in test IDE
./gradlew runIde

# Verify plugin
./gradlew verifyPlugin

# Run plugin verifier
./gradlew runPluginVerifier

# Clean and rebuild
./gradlew clean buildPlugin

# List all tasks
./gradlew tasks --all
```

---

## Getting Your Rally API Key

If you don't have a Rally API Key yet:

1. Log in to Rally (https://rally1.rallydev.com or your instance)
2. Click your profile picture (top right)
3. Click **My Settings** or **Profile**
4. Navigate to **API Keys** section
5. Click **Create New API Key**
6. Copy the key (starts with underscore: `_abc123...`)
7. **Save it securely** - you can't view it again!

---

**Happy Testing!** 🎉

If you encounter any issues, check:
1. Terminal output from `./gradlew runIde`
2. IntelliJ log: **Help → Show Log in Finder/Explorer**
3. Error messages in the test IDE
