package com.intellij.plugins.rally.export

import com.google.gson.GsonBuilder
import com.intellij.plugins.rally.api.RallyApiClient

/**
 * Exports Rally test cases to Markdown or JSON format.
 */
object TestCaseExporter {

    /**
     * Fetch test cases and their steps for a user story, then format as Markdown.
     */
    fun exportToMarkdown(
        client: RallyApiClient,
        userStoryObjectID: String,
        userStoryFormattedID: String,
        userStoryName: String
    ): String {
        val testCases = client.queryTestCasesByUserStory(userStoryObjectID)

        val sb = StringBuilder()
        sb.appendLine("# Test Cases for $userStoryFormattedID: $userStoryName")
        sb.appendLine()

        if (testCases.isEmpty()) {
            sb.appendLine("_No test cases found._")
            return sb.toString()
        }

        for (tc in testCases) {
            sb.appendLine("## ${tc.formattedID}: ${tc.name}")
            sb.appendLine()

            if (!tc.priority.isNullOrBlank()) sb.appendLine("**Priority:** ${tc.priority}  ")
            if (!tc.method.isNullOrBlank()) sb.appendLine("**Method:** ${tc.method}  ")
            if (!tc.testCaseType.isNullOrBlank()) sb.appendLine("**Type:** ${tc.testCaseType}  ")
            sb.appendLine()

            if (!tc.preConditions.isNullOrBlank()) {
                sb.appendLine("### Pre-Conditions")
                sb.appendLine(stripHtml(tc.preConditions))
                sb.appendLine()
            }

            if (!tc.description.isNullOrBlank()) {
                sb.appendLine("### Description")
                sb.appendLine(stripHtml(tc.description))
                sb.appendLine()
            }

            // Fetch steps if present
            val stepsRef = tc.steps?.ref
            if (stepsRef != null && (tc.steps?.count ?: 0) > 0) {
                val steps = client.fetchTestCaseSteps(stepsRef)
                if (steps.isNotEmpty()) {
                    sb.appendLine("### Steps")
                    sb.appendLine()
                    sb.appendLine("| # | Input | Expected Result |")
                    sb.appendLine("|---|-------|-----------------|")
                    for (step in steps) {
                        val input = stripHtml(step.input ?: "").replace("|", "\\|")
                        val expected = stripHtml(step.expectedResult ?: "").replace("|", "\\|")
                        sb.appendLine("| ${step.stepIndex ?: "-"} | $input | $expected |")
                    }
                    sb.appendLine()
                }
            }

            if (!tc.postConditions.isNullOrBlank()) {
                sb.appendLine("### Post-Conditions")
                sb.appendLine(stripHtml(tc.postConditions))
                sb.appendLine()
            }

            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Same data, but as JSON for programmatic consumption.
     */
    fun exportToJson(
        client: RallyApiClient,
        userStoryObjectID: String,
        userStoryFormattedID: String,
        userStoryName: String
    ): String {
        val testCases = client.queryTestCasesByUserStory(userStoryObjectID)
        val gson = GsonBuilder().setPrettyPrinting().create()

        val output = mapOf(
            "userStory" to mapOf(
                "formattedID" to userStoryFormattedID,
                "name" to userStoryName
            ),
            "testCases" to testCases.map { tc ->
                val steps = tc.steps?.ref?.let { ref ->
                    if ((tc.steps?.count ?: 0) > 0) {
                        client.fetchTestCaseSteps(ref).map { step ->
                            mapOf(
                                "stepIndex" to step.stepIndex,
                                "input" to stripHtml(step.input ?: ""),
                                "expectedResult" to stripHtml(step.expectedResult ?: "")
                            )
                        }
                    } else emptyList()
                } ?: emptyList()

                mapOf(
                    "formattedID" to tc.formattedID,
                    "name" to tc.name,
                    "type" to tc.testCaseType,
                    "priority" to tc.priority,
                    "method" to tc.method,
                    "description" to tc.description?.let { stripHtml(it) },
                    "preConditions" to tc.preConditions?.let { stripHtml(it) },
                    "postConditions" to tc.postConditions?.let { stripHtml(it) },
                    "steps" to steps
                )
            }
        )

        return gson.toJson(output)
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }
}
