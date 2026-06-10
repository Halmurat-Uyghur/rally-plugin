package com.github.halmuratuyghur.rally.export

import com.intellij.openapi.diagnostic.Logger
import com.github.halmuratuyghur.rally.api.RallyApiClient
import com.github.halmuratuyghur.rally.api.RallyAttachment
import com.github.halmuratuyghur.rally.api.RallyTestCaseStep
import com.github.halmuratuyghur.rally.util.RallyFileUtils
import com.github.halmuratuyghur.rally.util.RallyHtmlUtils
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

class RallyExporter(private val client: RallyApiClient) {

    companion object {
        private val LOG = Logger.getInstance(RallyExporter::class.java)

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

        /**
         * Hard cap on inline images downloaded per description. Protects against
         * runaway descriptions with 100+ embedded images that previously triggered
         * multi-minute serial download hangs. Beyond the cap, the original Rally
         * URLs are left in place — broken offline but no worse than failing the
         * whole export.
         */
        private const val MAX_INLINE_IMAGES_PER_DESCRIPTION = 50

        /**
         * Escape Markdown special characters that would otherwise corrupt structure
         * when artifact names/owners/values are inlined into headings, list items,
         * or table cells. Backslash-escapes the GFM metacharacters; leaves angle
         * brackets alone (HTML passthrough is fine in our exports).
         *
         * Lives on the companion so unit tests can exercise it without constructing
         * a RallyExporter (which needs a real RallyApiClient).
         */
        internal fun escapeMarkdown(text: String): String {
            if (text.isEmpty()) return text
            return text
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("|", "\\|")
                .replace("#", "\\#")
        }

        /**
         * Strip HTML tags for plain-text export (for AI readability).
         * Lives on the companion for the same testing reason as escapeMarkdown.
         */
        internal fun stripHtml(html: String): String {
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
    }

    /** Per-session cache for downloaded attachment paths (deduplicates across JSON+Markdown export). */
    private val downloadedPaths = ConcurrentHashMap<String, String>()

    /** Per-session cache for downloaded inline-image paths — same dedup role as
     *  [downloadedPaths]: the JSON and Markdown passes generate identical image jobs. */
    private val downloadedImagePaths = ConcurrentHashMap<String, String>()

    // ── Test Case Export ─────────────────────────────────────────

    fun exportTestCaseJson(testCaseId: String, outputDir: String) {
        LOG.info("Generating JSON for test case: $testCaseId")

        val tc = queryTestCaseByFormattedId(testCaseId)
            ?: throw RuntimeException("Test case $testCaseId not found")

        val steps = client.queryTestSteps(testCaseId)
        val attachments = try { client.queryAttachments(testCaseId) } catch (e: Exception) {
            LOG.warn("Failed to fetch attachments for test case $testCaseId", e)
            emptyList()
        }

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

        val outRoot = Paths.get(outputDir)
        Files.createDirectories(outRoot)
        val file = RallyFileUtils.safeResolve(outRoot, "$testCaseId.json").toFile()
        // Stream straight to the file: gson.toJson(JsonElement, Appendable) emits
        // identical output without first materializing the whole document as a String.
        file.bufferedWriter(StandardCharsets.UTF_8).use { gson.toJson(output, it) }

        LOG.info("Generated JSON: ${file.absolutePath}")
    }

    fun exportTestCaseMarkdown(testCaseId: String, outputDir: String) {
        LOG.info("Generating Markdown for test case: $testCaseId")

        val tc = queryTestCaseByFormattedId(testCaseId)
            ?: throw RuntimeException("Test case $testCaseId not found")

        val steps = client.queryTestSteps(testCaseId)
        val attachments = try { client.queryAttachments(testCaseId) } catch (e: Exception) {
            LOG.warn("Failed to fetch attachments for test case $testCaseId", e)
            emptyList()
        }

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

        val outRoot = Paths.get(outputDir)
        Files.createDirectories(outRoot)
        val mdPath = RallyFileUtils.safeResolve(outRoot, "$testCaseId.md")
        Files.write(mdPath, md.toString().toByteArray(StandardCharsets.UTF_8))
        LOG.info("Generated Markdown: $mdPath")
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

        val outRoot = Paths.get(outputDir)
        Files.createDirectories(outRoot)
        val file = RallyFileUtils.safeResolve(outRoot, "$fileName.json").toFile()
        file.bufferedWriter(StandardCharsets.UTF_8).use { gson.toJson(output, it) }
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

        val outRoot = Paths.get(outputDir)
        Files.createDirectories(outRoot)
        val file = RallyFileUtils.safeResolve(outRoot, "$fileName.md").toFile()

        var count = 0
        file.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write("# Rally Bulk Export\n\n")
            writer.write("Exported: ${Instant.now()}\n")
            writer.write("Total artifacts: ${artifacts.size}\n\n---\n\n")

            for (artifact in artifacts) {
                val id = artifact.formattedID ?: continue
                try {
                    val safeName = escapeMarkdown(artifact.name ?: "Untitled")
                    val safeOwner = escapeMarkdown(artifact.owner?.displayName ?: artifact.owner?.refObjectName ?: "")
                    writer.write("## $id — $safeName\n\n")
                    writer.write("- **Type:** ${escapeMarkdown(artifact.type ?: "")}\n")
                    writer.write("- **State:** ${escapeMarkdown(artifact.scheduleState ?: artifact.state ?: "")}\n")
                    writer.write("- **Owner:** $safeOwner\n")
                    writer.write("- **Created:** ${artifact.creationDate?.take(10) ?: ""}\n")
                    writer.write("- **Updated:** ${artifact.lastUpdateDate?.take(10) ?: ""}\n")

                    if (artifact is com.github.halmuratuyghur.rally.api.RallyDefect) {
                        writer.write("- **Severity:** ${escapeMarkdown(artifact.severity ?: "")}\n")
                        writer.write("- **Priority:** ${escapeMarkdown(artifact.priority ?: "")}\n")
                        writer.write("- **Environment:** ${escapeMarkdown(artifact.environment ?: "")}\n")
                        writer.write("- **Project:** ${escapeMarkdown(artifact.project?.refObjectName ?: artifact.project?.name ?: "")}\n")
                        writer.write("- **Iteration:** ${escapeMarkdown(artifact.iteration?.refObjectName ?: artifact.iteration?.name ?: "")}\n")
                    }

                    if (artifact is com.github.halmuratuyghur.rally.api.RallyUserStory) {
                        writer.write("- **Plan Estimate:** ${artifact.planEstimate ?: ""}\n")
                        writer.write("- **Project:** ${escapeMarkdown(artifact.project?.refObjectName ?: artifact.project?.name ?: "")}\n")
                        writer.write("- **Iteration:** ${escapeMarkdown(artifact.iteration?.refObjectName ?: artifact.iteration?.name ?: "")}\n")
                    }

                    val desc = stripHtml(descriptionMap[id] ?: "")
                    if (desc.isNotBlank()) {
                        writer.write("\n### Description\n\n")
                        writer.write(desc)
                        writer.write("\n")
                    }

                    writer.write("\n---\n\n")
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

    private fun escapeMarkdown(text: String): String = Companion.escapeMarkdown(text)

    private fun stripHtml(html: String): String = Companion.stripHtml(html)

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
        val attachments = try { client.queryAttachments(artifactId) } catch (e: Exception) {
            LOG.warn("Failed to fetch attachments for $artifactId", e)
            emptyList()
        }
        output.add("attachments", attachmentsToJsonArray(attachments, artifactId, outputDir))
        output.addProperty("totalAttachments", attachments.size)

        val outRoot = Paths.get(outputDir)
        Files.createDirectories(outRoot)
        val file = RallyFileUtils.safeResolve(outRoot, "$artifactId.json").toFile()
        file.bufferedWriter(StandardCharsets.UTF_8).use { gson.toJson(output, it) }
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
        val attachments = try { client.queryAttachments(artifactId) } catch (e: Exception) {
            LOG.warn("Failed to fetch attachments for $artifactId", e)
            emptyList()
        }
        if (attachments.isNotEmpty()) {
            md.appendLine("## Attachments")
            md.appendLine()
            appendAttachmentLinks(md, attachments, artifactId, outputDir)
        }

        val outRoot = Paths.get(outputDir)
        Files.createDirectories(outRoot)
        val mdPath = RallyFileUtils.safeResolve(outRoot, "$artifactId.md")
        Files.write(mdPath, md.toString().toByteArray(StandardCharsets.UTF_8))
        LOG.info("Generated Markdown: $mdPath")
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
        val attachDir = RallyFileUtils.safeResolve(Paths.get(outputDir), "${artifactId}_attachments").toString()

        // Download attachments sequentially to avoid apiExecutor self-deadlock
        // (this method is called from within an apiExecutor task during export)
        val array = JsonArray()
        for (att in attachments) {
            val attName = att.name ?: "unnamed"
            val savedPath = downloadAttachmentContent(att, attachDir, attName)
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
        val safeFileName = RallyFileUtils.sanitizeFileName(fileName)
        val cacheKey = "$contentRef:$attachDir:$safeFileName"

        // Check per-session dedup cache (avoids re-downloading for JSON+Markdown exports)
        downloadedPaths[cacheKey]?.let { return it }

        return try {
            val attachDirPath = Paths.get(attachDir)
            Files.createDirectories(attachDirPath)

            val base64Content = client.getAttachmentContent(contentRef)
            // Rally returns MIME-encoded base64 with line breaks every 76 chars; the strict
            // decoder throws IllegalArgumentException on real attachments, so use MIME decoder.
            val fileBytes = Base64.getMimeDecoder().decode(base64Content)

            // safeResolve handles sanitization + containment. Dedup on existing names.
            var outputPath = RallyFileUtils.safeResolve(attachDirPath, safeFileName)
            if (Files.exists(outputPath)) {
                val baseName = safeFileName.substringBeforeLast(".", safeFileName)
                val ext = if (safeFileName.contains(".")) ".${safeFileName.substringAfterLast(".")}" else ""
                var counter = 1
                while (Files.exists(outputPath)) {
                    outputPath = RallyFileUtils.safeResolve(attachDirPath, "${baseName}_$counter$ext")
                    counter++
                }
            }

            Files.write(outputPath, fileBytes)
            LOG.info("Saved attachment: $outputPath (${fileBytes.size} bytes)")
            val path = outputPath.toAbsolutePath().toString()
            downloadedPaths[cacheKey] = path
            path
        } catch (e: Exception) {
            LOG.warn("Failed to download attachment '$fileName'", e)
            // Don't cache failures — allow retry on transient errors
            null
        }
    }

    fun downloadInlineImages(html: String, artifactId: String, outputDir: String): String {
        if (html.isBlank()) return html

        val matcher = RallyHtmlUtils.INLINE_IMG_PATTERN.matcher(html)
        if (!matcher.find()) return html

        // Pass 1: walk the matcher once and collect at most MAX_INLINE_IMAGES_PER_DESCRIPTION
        // unique download jobs. Done first so Pass 2 can run in parallel.
        data class ImageJob(val originalSrc: String, val objectId: String, val originalFileName: String, val uniqueFileName: String)
        val jobs = mutableListOf<ImageJob>()
        matcher.reset()
        var imgCounter = 0
        while (matcher.find() && jobs.size < MAX_INLINE_IMAGES_PER_DESCRIPTION) {
            imgCounter++
            val originalSrc = matcher.group(2)
            val objectId = matcher.group(3)
            val fileName = matcher.group(4)
            val ext = fileName.substringAfterLast('.', "png").lowercase()
            val rawUnique = if (imgCounter == 1) "$artifactId.$ext" else "${artifactId}_$imgCounter.$ext"
            val uniqueFileName = RallyFileUtils.sanitizeFileName(rawUnique)
            jobs.add(ImageJob(originalSrc, objectId, fileName, uniqueFileName))
        }

        if (jobs.isEmpty()) return html

        // Pass 2: download sequentially. This MUST NOT submit to client.apiExecutor:
        // export runs each artifact's exportArtifact{Json,Markdown} as a task ON
        // apiExecutor (see RallyToolWindowPanel), so submitting inner download tasks
        // back to the same fixed pool and blocking on .join() self-deadlocks once the
        // selection count reaches the pool size. downloadAttachments() avoids this the
        // same way. The HttpClient does its own connection multiplexing, so sequential
        // here still reuses connections; only request issuance is serialized.
        val imgDir = RallyFileUtils.safeResolve(Paths.get(outputDir), "${artifactId}_images").toString()
        val downloads = HashMap<String, String>()  // originalSrc -> relative local path
        for (job in jobs) {
            val localPath = downloadRallyImage(job.objectId, job.originalFileName, job.uniqueFileName, imgDir)
            if (localPath != null) {
                downloads[job.originalSrc] = "${artifactId}_images/${job.uniqueFileName}"
            }
        }

        // Pass 3: rewrite the HTML using the downloaded paths. Matches beyond the
        // per-call cap pass through unchanged so the rendered output still has
        // every image reference (even if some won't load offline).
        matcher.reset()
        val result = StringBuilder()
        while (matcher.find()) {
            val originalSrc = matcher.group(2)
            val localRelative = downloads[originalSrc]
            val replacement = if (localRelative != null) {
                matcher.group().replace(originalSrc, localRelative)
            } else {
                matcher.group()
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(result)
        return result.toString()
    }

    private fun downloadRallyImage(objectId: String, originalFileName: String, localFileName: String, imgDir: String): String? {
        val cacheKey = "$objectId:$imgDir:$localFileName"
        downloadedImagePaths[cacheKey]?.let { return it }
        try {
            val imgDirPath = Paths.get(imgDir)
            Files.createDirectories(imgDirPath)

            val imageUrl = "${client.webBaseUrl}/slm/attachment/$objectId/$originalFileName"

            val fileBytes = client.downloadAttachment(imageUrl)

            val outputPath = RallyFileUtils.safeResolve(imgDirPath, localFileName)
            Files.write(outputPath, fileBytes)
            LOG.info("Downloaded inline image: $outputPath (${fileBytes.size} bytes)")
            val path = outputPath.toAbsolutePath().toString()
            downloadedImagePaths[cacheKey] = path
            return path
        } catch (e: Exception) {
            // Don't cache failures — allow retry on transient errors
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
        val attachDirName = RallyFileUtils.sanitizeFileName("${artifactId}_attachments")
        val attachDir = RallyFileUtils.safeResolve(Paths.get(outputDir), attachDirName).toString()

        for (att in attachments) {
            val attName = att.name ?: "unnamed"
            val contentType = att.contentType ?: ""
            val savedPath = downloadAttachmentContent(att, attachDir, attName)

            if (savedPath != null) {
                // Use the actual saved filename (may differ from attName due to sanitization/dedup)
                val savedFileName = File(savedPath).name
                if (contentType.startsWith("image/")) {
                    md.appendLine("### $attName")
                    md.appendLine("![$attName]($attachDirName/$savedFileName)")
                    md.appendLine()
                } else {
                    md.appendLine("- [$attName]($attachDirName/$savedFileName)")
                }
            } else {
                md.appendLine("- $attName *(download failed)*")
            }
        }
        md.appendLine()
    }
}
