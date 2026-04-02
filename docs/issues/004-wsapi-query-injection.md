## Title
security: Rally WSAPI query injection via incomplete escapeQueryValue() — FormattedIDs interpolated without escaping

## Labels
security, bug, high-priority

## Description

### Summary
The `escapeQueryValue()` function only strips backslashes and double-quotes but leaves Rally WSAPI metacharacters (parentheses, logical operators, comparison operators) unescaped. Additionally, `FormattedID` values are interpolated directly into query strings without going through any escape function at all in several places.

### Affected Files & Lines
`src/main/kotlin/com/github/halmuratuyghur/rally/api/RallyApiClient.kt`

**Insufficient escape function (lines 154–155):**
```kotlin
fun escapeQueryValue(value: String): String =
    value.replace("\\", "").replace("\"", "")
```

**Direct interpolation without escaping (representative lines):**
- Line 468: `val query = "(FormattedID = \"$formattedId\")"`
- Line 899: `val query = "(TestCase.FormattedID = \"$testCaseFormattedId\")"`
- Line 926: `val query = "(Artifact.FormattedID = \"$artifactFormattedId\")"`
- Line 950: `val query = "(FormattedID = \"$formattedId\")"`

### Attack Scenario
A crafted FormattedID or search term such as:
```
TC-1") OR (Owner.UserName = "victim@company.com
```
transforms the generated query into:
```
(TestCase.FormattedID = "TC-1") OR (Owner.UserName = "victim@company.com")
```
returning all test cases owned by that user — data the caller may not be authorised to see.

### Impact
- **CWE-89 equivalent** (Improper Neutralization of Special Elements in Query Language)
- Bypass of project/iteration scope filters
- Potential exfiltration of work items across the workspace
- Exposure of data to unauthorised users

### Expected Behavior
User-controlled values must never alter the query structure. FormattedIDs should additionally be validated against a strict whitelist pattern before use.

### Suggested Fix
1. **Validate FormattedIDs** against a strict pattern:
```kotlin
require(formattedId.matches(Regex("^[A-Z]{0,4}\\d{1,8}$"))) {
    "Invalid FormattedID: $formattedId"
}
```

2. **Strengthen `escapeQueryValue`** to handle all metacharacters:
```kotlin
fun escapeQueryValue(value: String): String =
    value.replace("\\", "\\\\")
         .replace("\"", "\\\"")
         .replace("\u0000", "")
```

3. **Apply the escape function consistently** to every user-supplied value before interpolation.
