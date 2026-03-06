package com.github.halmuratuyghur.rally.export

import com.intellij.openapi.diagnostic.Logger
import com.github.halmuratuyghur.rally.api.RallyApiClient
import com.github.halmuratuyghur.rally.api.RallyAttachment
import com.github.halmuratuyghur.rally.api.RallyTestCaseStep
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class RallyExporter(private val client: RallyApiClient) {

    companion object {
        private val LOG = Logger.getInstance(RallyExporter::class.java)

        private val INLINE_IMG_PATTERN = Pattern.compile(
            """src="((?:https?://[^/]+)?/slm/attachment/(\d+)/([^"]+))"""",
            Pattern.CASE_INSENSITIVE
        )

        private val gson = GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create()

        // Pre-compiled regex patterns for stripHtml (avoid re-creating per call during bulk export)
        private val RE_BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val RE_P_OPEN = Regex("<p[^>]*>", RegexOption.IGNORE_CASE)
        private val RE_P_CLOSE = Regex("</p>", RegexOption.IGNORE_CASE)
        private val RE_LI = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
        private val RE_TAG = Regex("<[^>]+>")
        private val RE_MULTI_NEWLINE = Regex("\n{3,}")
    }

    /** Per-session cache for downloaded attachment paths (deduplicates across JSON+Markdown export). */
    private val downloadedPaths = ConcurrentHashMap<String, String?>()

    // ── Test Case Export ─────────────────────────────────────────

    fun exportTestCaseJson(testCaseId: String, outputDir: String) {
        LOG.info("Generating JSON for test case: $testCaseId")

        val tc = queryTestCaseByFormattedId(testCaseId)
            ?: throw RuntimeException("Test case $testCaseId not found")

        val steps = client.queryTestSteps(testCaseId)
        val attachments = try { client.queryAttachments(testCaseId) } catch (e: Exception) { emptyList() }

        val output = JsonObject().apply {
            addProperty("id", testCaseId)
            addProperty("name", tc.name ?: "")
            addProperty("description", tc.description ?: "")
            addProperty("priority", tc.priority ?: "")
            addProperty("type", tc.testType ?: "")
            addProperty("method", tc.method ?: "")
            addProperty("extractedAt", Instant.now().toString())
            add("steps", stepsToJsonArray(steps))
            addProperty("totalSteps", steps.size)
            add("attachments", attachmentsToJsonArray(attachments, testCaseId, outputDir))
            addProperty("totalAttachments", attachments.size)
        }

        val dir = File(outputDir)
        dir.mkdirs()
        val file = File(dir, "$testCaseId.json")
        file.writeText(gson.toJson(output), StandardCharsets.UTF_8)

        LOG.info("Generated JSON: ${file.absolutePath}")
    }

    fun exportTestCaseMarkdown(testCaseId: String, outputDir: String) {
        LOG.info("Generating Markdown for test case: $testCaseId")

        val tc = queryTestCaseByFormattedId(testCaseId)
            ?: throw RuntimeException("Test case $testCaseId not found")

        val steps = client.queryTestSteps(testCaseId)
        val attachments = try { client.queryAttachments(testCaseId) } catch (e: Exception) { emptyList() }

        val md = StringBuilder()
        md.appendLine("# $testCaseId - ${tc.name ?: ""}")
        md.appendLine()
        md.appendLine("## Description")
        md.appendLine(downloadInlineImages(tc.description ?: "", testCaseId, outputDir))
        md.appendLine()
        md.appendLine("## Test Steps")
        md.appendLine()
        appendStepsTable(md, steps, testCaseId, outputDir)

        if (attachments.isNotEmpty()) {
            md.appendLine("## Attachments")
            md.appendLine()
            appendAttachmentLinks(md, attachments, testCaseId, outputDir)
        }

        Files.createDirectories(Paths.get(outputDir))
        Files.write(
            Paths.get(outputDir, "$testCaseId.md"),
            md.toString().toByteArray(StandardCharsets.UTF_8)
        )
        LOG.info("Generated Markdown: $outputDir/$testCaseId.md")
    }

    // ── Bulk Export (for AI analysis) ──────────────────────────

    /**
     * Export all artifacts to a single consolidated JSON file suitable for AI analysis.
     * Returns the number of artifacts exported.
     */
    fun bulkExportJson(artifacts: List<com.github.halmuratuyghur.rally.api.RallyArtifact>, outputDir: String, fileName: String = "bulk_export", onProgress: ((Int) -> Unit)? = null): Int {
        LOG.info("Bulk exporting ${artifacts.size} artifacts to JSON")
        client.enterBulkMode()
        try {
        // Pre-fetch all descriptions in parallel (10 concurrent)
        val descriptionMap = prefetchDescriptions(artifacts, onProgress)

        val array = JsonArray()
        for (artifact in artifacts) {
            val id = artifact.formattedID ?: continue
            try {
                val desc = descriptionMap[id] ?: ""

                val obj = JsonObject().apply {
                    addProperty("formattedID", id)
                    addProperty("type", artifact.type ?: "")
                    addProperty("name", artifact.name ?: "")
                    addProperty("description", stripHtml(desc))
                    addProperty("state", artifact.scheduleState ?: artifact.state ?: "")
                    addProperty("owner", artifact.owner?.displayName ?: artifact.owner?.refObjectName ?: "")
                    addProperty("creationDate", artifact.creationDate ?: "")
                    addProperty("lastUpdateDate", artifact.lastUpdateDate ?: "")
                }

                // Defect-specific fields
                if (artifact is com.github.halmuratuyghur.rally.api.RallyDefect) {
                    obj.addProperty("severity", artifact.severity ?: "")
                    obj.addProperty("priority", artifact.priority ?: "")
                    obj.addProperty("environment", artifact.environment ?: "")
                    obj.addProperty("project", artifact.project?.refObjectName ?: artifact.project?.name ?: "")
                    obj.addProperty("iteration", artifact.iteration?.refObjectName ?: artifact.iteration?.name ?: "")
                }

                // User story specific fields
                if (artifact is com.github.halmuratuyghur.rally.api.RallyUserStory) {
                    obj.addProperty("planEstimate", artifact.planEstimate ?: 0.0)
                    obj.addProperty("project", artifact.project?.refObjectName ?: artifact.project?.name ?: "")
                    obj.addProperty("iteration", artifact.iteration?.refObjectName ?: artifact.iteration?.name ?: "")
                }

                array.add(obj)
            } catch (e: Exception) {
                LOG.warn("Failed to process $id for bulk export", e)
            }
        }

        val output = JsonObject().apply {
            addProperty("exportedAt", Instant.now().toString())
            addProperty("totalCount", array.size())
            add("artifacts", array)
        }

        val dir = File(outputDir)
        dir.mkdirs()
        val file = File(dir, "$fileName.json")
        file.writeText(gson.toJson(output), StandardCharsets.UTF_8)
        LOG.info("Bulk export JSON: ${file.absolutePath} (${array.size()} artifacts)")
        return array.size()
        } finally {
            client.exitBulkMode()
        }
    }

    /**
     * Export all artifacts to a single consolidated Markdown file suitable for AI analysis.
     * Returns the number of artifacts exported.
     */
    fun bulkExportMarkdown(artifacts: List<com.github.halmuratuyghur.rally.api.RallyArtifact>, outputDir: String, fileName: String = "bulk_export", onProgress: ((Int) -> Unit)? = null): Int {
        LOG.info("Bulk exporting ${artifacts.size} artifacts to Markdown")
        client.enterBulkMode()
        try {
        // Pre-fetch all descriptions in parallel (10 concurrent)
        val descriptionMap = prefetchDescriptions(artifacts, onProgress)

        val dir = File(outputDir)
        dir.mkdirs()
        val file = File(dir, "$fileName.md")

        var count = 0
        file.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write("# Rally Bulk Export\n\n")
            writer.write("Exported: ${Instant.now()}\n")
            writer.write("Total artifacts: ${artifacts.size}\n\n---\n\n")

            for (artifact in artifacts) {
                val id = artifact.formattedID ?: continue
                try {
                    writer.write("## $id — ${artifact.name ?: "Untitled"}\n\n")
                    writer.write("- **Type:** ${artifact.type ?: ""}\n")
                    writer.write("- **State:** ${artifact.scheduleState ?: artifact.state ?: ""}\n")
                    writer.write("- **Owner:** ${artifact.owner?.displayName ?: artifact.owner?.refObjectName ?: ""}\n")
                    writer.write("- **Created:** ${artifact.creationDate?.take(10) ?: ""}\n")
                    writer.write("- **Updated:** ${artifact.lastUpdateDate?.take(10) ?: ""}\n")

                    if (artifact is com.github.halmuratuyghur.rally.api.RallyDefect) {
                        writer.write("- **Severity:** ${artifact.severity ?: ""}\n")
                        writer.write("- **Priority:** ${artifact.priority ?: ""}\n")
                        writer.write("- **Environment:** ${artifact.environment ?: ""}\n")
                        writer.write("- **Project:** ${artifact.project?.refObjectName ?: artifact.project?.name ?: ""}\n")
                        writer.write("- **Iteration:** ${artifact.iteration?.refObjectName ?: artifact.iteration?.name ?: ""}\n")
                    }

                    if (artifact is com.github.halmuratuyghur.rally.api.RallyUserStory) {
                        writer.write("- **Plan Estimate:** ${artifact.planEstimate ?: ""}\n")
                        writer.write("- **Project:** ${artifact.project?.refObjectName ?: artifact.project?.name ?: ""}\n")
                        writer.write("- **Iteration:** ${artifact.iteration?.refObjectName ?: artifact.iteration?.name ?: ""}\n")
                    }

                    val desc = stripHtml(descriptionMap[id] ?: "")
                    if (desc.isNotBlank()) {
                        writer.write("\n### Description\n\n")
                        writer.write(desc)
                        writer.write("\n")
                    }

                    writer.write("\n---\n\n")
                    writer.flush()
                    count++
                } catch (e: Exception) {
                    LOG.warn("Failed to process $id for bulk export", e)
                }
            }
        }

        LOG.info("Bulk export Markdown: ${file.absolutePath} ($count artifacts)")
        return count
        } finally {
            client.exitBulkMode()
        }
    }

    /**
     * Pre-fetch descriptions for all artifacts in parallel (bounded to 10 concurrent).
     * Returns a map of FormattedID -> description HTML.
     */
    private fun prefetchDescriptions(
        artifacts: List<com.github.halmuratuyghur.rally.api.RallyArtifact>,
        onProgress: ((Int) -> Unit)? = null
    ): Map<String, String> {
        val result = ConcurrentHashMap<String, String>()
        val semaphore = Semaphore(10)
        val counter = AtomicInteger(0)

        val futures = artifacts.mapNotNull { artifact ->
            val id = artifact.formattedID ?: return@mapNotNull null
            val ref = artifact.ref
            CompletableFuture.runAsync({
                semaphore.acquire()
                try {
                    val desc = artifact.description
                        ?: ref?.let { client.fetchDescription(it) }
                        ?: ""
                    result[id] = desc
                } catch (e: Exception) {
                    LOG.warn("Failed to fetch description for $id", e)
                    result[id] = ""
                } finally {
                    semaphore.release()
                    onProgress?.invoke(counter.incrementAndGet())
                }
            }, client.apiExecutor)
        }

        CompletableFuture.allOf(*futures.toTypedArray()).join()
        return result
    }

    /**
     * Strip HTML tags for plain-text export (for AI readability).
     */
    private fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(RE_BR, "\n")
            .replace(RE_P_OPEN, "\n")
            .replace(RE_P_CLOSE, "")
            .replace(RE_LI, "- ")
            .replace(RE_TAG, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(RE_MULTI_NEWLINE, "\n\n")
            .trim()
    }

    // ── Artifact Export ──────────────────────────────────────────

    fun exportArtifactJson(artifactId: String, outputDir: String) {
        LOG.info("Generating JSON for artifact: $artifactId")

        val artifact = client.getArtifactByFormattedId(artifactId)
            ?: throw RuntimeException("Artifact $artifactId not found")

        val output = JsonObject().apply {
            addProperty("id", artifactId)
            addProperty("artifactType", artifact.type ?: "")
            addProperty("name", artifact.name ?: "")
            addProperty("extractedAt", Instant.now().toString())
        }

        // Process description with inline images
        val rawDesc = artifact.description ?: ""
        val processedDesc = downloadInlineImages(rawDesc, artifactId, outputDir)
        output.addProperty("description", processedDesc)

        // Type-specific fields
        when {
            artifactId.startsWith("TC", ignoreCase = true) -> {
                val steps = client.queryTestSteps(artifactId)
                output.add("steps", stepsToJsonArray(steps))
                output.addProperty("totalSteps", steps.size)
            }
            artifactId.startsWith("US", ignoreCase = true) || artifactId.startsWith("S-", ignoreCase = true) -> {
                output.addProperty("scheduleState", artifact.scheduleState ?: "")
            }
            artifactId.startsWith("DE", ignoreCase = true) -> {
                output.addProperty("state", artifact.state ?: "")
            }
        }

        // Attachments
        val attachments = try { client.queryAttachments(artifactId) } catch (e: Exception) { emptyList() }
        output.add("attachments", attachmentsToJsonArray(attachments, artifactId, outputDir))
        output.addProperty("totalAttachments", attachments.size)

        val dir = File(outputDir)
        dir.mkdirs()
        val file = File(dir, "$artifactId.json")
        file.writeText(gson.toJson(output), StandardCharsets.UTF_8)
        LOG.info("Generated JSON: ${file.absolutePath}")
    }

    fun exportArtifactMarkdown(artifactId: String, outputDir: String) {
        LOG.info("Generating Markdown for artifact: $artifactId")

        val artifact = client.getArtifactByFormattedId(artifactId)
            ?: throw RuntimeException("Artifact $artifactId not found")

        val md = StringBuilder()
        md.appendLine("# $artifactId - ${artifact.name ?: ""}")
        md.appendLine()

        // Description with inline images
        val desc = downloadInlineImages(artifact.description ?: "", artifactId, outputDir)
        md.appendLine("## Description")
        md.appendLine(desc)
        md.appendLine()

        // Type-specific sections
        when {
            artifactId.startsWith("TC", ignoreCase = true) -> {
                md.appendLine("## Test Steps")
                md.appendLine()
                val steps = client.queryTestSteps(artifactId)
                appendStepsTable(md, steps, artifactId, outputDir)
            }
            artifactId.startsWith("US", ignoreCase = true) || artifactId.startsWith("S-", ignoreCase = true) -> {
                md.appendLine("**Schedule State:** ${artifact.scheduleState ?: ""}")
                md.appendLine()
            }
            artifactId.startsWith("DE", ignoreCase = true) -> {
                md.appendLine("**State:** ${artifact.state ?: ""}")
                md.appendLine()
            }
        }

        // Attachments
        val attachments = try { client.queryAttachments(artifactId) } catch (e: Exception) { emptyList() }
        if (attachments.isNotEmpty()) {
            md.appendLine("## Attachments")
            md.appendLine()
            appendAttachmentLinks(md, attachments, artifactId, outputDir)
        }

        Files.createDirectories(Paths.get(outputDir))
        Files.write(
            Paths.get(outputDir, "$artifactId.md"),
            md.toString().toByteArray(StandardCharsets.UTF_8)
        )
        LOG.info("Generated Markdown: $outputDir/$artifactId.md")
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun queryTestCaseByFormattedId(testCaseId: String): com.github.halmuratuyghur.rally.api.RallyTestCase? {
        return try {
            client.queryTestCaseByFormattedId(testCaseId)
        } catch (e: Exception) {
            LOG.warn("Failed to query test case $testCaseId", e)
            null
        }
    }

    private fun stepsToJsonArray(steps: List<RallyTestCaseStep>): JsonArray {
        val array = JsonArray()
        for ((index, step) in steps.withIndex()) {
            val obj = JsonObject().apply {
                addProperty("stepNumber", index + 1)
                addProperty("description", step.input ?: "")
                addProperty("expectedResult", step.expectedResult ?: "")
            }
            array.add(obj)
        }
        return array
    }

    private fun attachmentsToJsonArray(
        attachments: List<RallyAttachment>,
        artifactId: String,
        outputDir: String
    ): JsonArray {
        val attachDir = "$outputDir${File.separator}${artifactId}_attachments"

        // Download all attachments in parallel
        val futures = attachments.map { att ->
            val attName = att.name ?: "unnamed"
            CompletableFuture.supplyAsync({
                val savedPath = downloadAttachmentContent(att, attachDir, attName)
                Triple(att, attName, savedPath)
            }, client.apiExecutor)
        }

        val array = JsonArray()
        for (future in futures) {
            val (att, attName, savedPath) = future.join()
            val obj = JsonObject().apply {
                addProperty("name", attName)
                addProperty("contentType", att.contentType ?: "")
                addProperty("size", att.size ?: 0)
                addProperty("description", att.description ?: "")
                addProperty("downloaded", savedPath != null)
                if (savedPath != null) addProperty("filePath", savedPath)
            }
            array.add(obj)
        }
        return array
    }

    private fun downloadAttachmentContent(
        attachment: RallyAttachment,
        attachDir: String,
        fileName: String
    ): String? {
        val contentRef = attachment.content?.ref ?: return null
        val cacheKey = "$contentRef:$attachDir:$fileName"

        // Check per-session dedup cache (avoids re-downloading for JSON+Markdown exports)
        downloadedPaths[cacheKey]?.let { return it }

        return try {
            Files.createDirectories(Paths.get(attachDir))

            val base64Content = client.getAttachmentContent(contentRef)
            val fileBytes = Base64.getDecoder().decode(base64Content)

            val outputFile = File(attachDir, fileName)
            Files.write(outputFile.toPath(), fileBytes)
            LOG.info("Saved attachment: ${outputFile.absolutePath} (${fileBytes.size} bytes)")
            val path = outputFile.absolutePath
            downloadedPaths[cacheKey] = path
            path
        } catch (e: Exception) {
            LOG.warn("Failed to download attachment '$fileName'", e)
            downloadedPaths[cacheKey] = null
            null
        }
    }

    fun downloadInlineImages(html: String, artifactId: String, outputDir: String): String {
        if (html.isBlank()) return html

        val matcher = INLINE_IMG_PATTERN.matcher(html)
        if (!matcher.find()) return html

        matcher.reset()
        val result = StringBuilder()
        val imgDir = "$outputDir${File.separator}${artifactId}_images"
        var imgCounter = 0

        while (matcher.find()) {
            val originalSrc = matcher.group(1)
            val objectId = matcher.group(2)
            val fileName = matcher.group(3)
            imgCounter++
            val ext = fileName.substringAfterLast('.', "png").lowercase()
            val uniqueFileName = if (imgCounter == 1) "$artifactId.$ext" else "${artifactId}_$imgCounter.$ext"

            val localPath = downloadRallyImage(objectId, fileName, uniqueFileName, imgDir)
            if (localPath != null) {
                val relativePath = "${artifactId}_images/$uniqueFileName"
                val replacement = matcher.group().replace(originalSrc, relativePath)
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement))
            } else {
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group()))
            }
        }
        matcher.appendTail(result)
        return result.toString()
    }

    private fun downloadRallyImage(objectId: String, originalFileName: String, localFileName: String, imgDir: String): String? {
        try {
            Files.createDirectories(Paths.get(imgDir))

            val baseUrl = client.serverUrl.trimEnd('/')
            val url = if (!baseUrl.startsWith("http")) "https://$baseUrl" else baseUrl
            val imageUrl = "$url/slm/attachment/$objectId/$originalFileName"

            val fileBytes = client.downloadAttachment(imageUrl)

            val outputFile = File(imgDir, localFileName)
            Files.write(outputFile.toPath(), fileBytes)
            LOG.info("Downloaded inline image: ${outputFile.absolutePath} (${fileBytes.size} bytes)")
            return outputFile.absolutePath
        } catch (e: Exception) {
            LOG.warn("Failed to download image OID=$objectId", e)
            return null
        }
    }

    private fun appendStepsTable(
        md: StringBuilder,
        steps: List<RallyTestCaseStep>,
        artifactId: String,
        outputDir: String
    ) {
        md.appendLine("<table>")
        md.appendLine("<tr><th width=\"50\">#</th><th>Action</th><th>Expected Result</th></tr>")
        for ((index, step) in steps.withIndex()) {
            val action = downloadInlineImages(step.input ?: "", artifactId, outputDir)
            val expected = downloadInlineImages(step.expectedResult ?: "", artifactId, outputDir)
            md.appendLine("<tr>")
            md.appendLine("  <td valign=\"top\"><b>${index + 1}</b></td>")
            md.appendLine("  <td valign=\"top\">$action</td>")
            md.appendLine("  <td valign=\"top\">$expected</td>")
            md.appendLine("</tr>")
        }
        md.appendLine("</table>")
        md.appendLine()
    }

    private fun appendAttachmentLinks(
        md: StringBuilder,
        attachments: List<RallyAttachment>,
        artifactId: String,
        outputDir: String
    ) {
        val attachDirName = "${artifactId}_attachments"
        val attachDir = "$outputDir${File.separator}$attachDirName"

        for (att in attachments) {
            val attName = att.name ?: "unnamed"
            val contentType = att.contentType ?: ""
            val savedPath = downloadAttachmentContent(att, attachDir, attName)

            if (savedPath != null && contentType.startsWith("image/")) {
                md.appendLine("### $attName")
                md.appendLine("![$attName]($attachDirName/$attName)")
                md.appendLine()
            } else if (savedPath != null) {
                md.appendLine("- [$attName]($attachDirName/$attName)")
            } else {
                md.appendLine("- $attName *(download failed)*")
            }
        }
        md.appendLine()
    }
}
