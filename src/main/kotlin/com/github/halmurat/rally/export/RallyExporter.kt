package com.github.halmurat.rally.export

import com.intellij.openapi.diagnostic.Logger
import com.github.halmurat.rally.api.RallyApiClient
import com.github.halmurat.rally.api.RallyArtifact
import com.github.halmurat.rally.api.RallyAttachment
import com.github.halmurat.rally.api.RallyTestCaseStep
import com.github.halmurat.rally.api.RallyType
import com.github.halmurat.rally.api.effectiveStateOrEmpty
import com.github.halmurat.rally.api.storyPoints
import com.github.halmurat.rally.util.RallyFileUtils
import com.github.halmurat.rally.util.RallyHtmlUtils
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
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class RallyExporter(private val client: RallyApiClient) : AutoCloseable {

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
         * Width of the shared download pool ([downloadPool]) used by [downloadInlineImages]
         * and [attachmentsToJsonArray] (L6). One pool is lazily created per [RallyExporter]
         * instance and released via [close] — it MUST stay separate from the pool the export
         * task itself runs on (the per-export "rally-export-orchestrator" pool — see
         * exportSelectedArtifact in RallyToolWindowPanel): the export task blocks on join(),
         * so submitting its inner download tasks to its own fixed pool would self-deadlock.
         * It also stays off [RallyApiClient.apiExecutor] so long downloads never occupy the
         * interactive-query threads.
         */
        private const val DOWNLOAD_POOL_SIZE = 8

        /**
         * Escape Markdown special characters that would otherwise corrupt structure
         * when artifact names/owners/values are inlined into headings, list items,
         * or table cells. Backslash-escapes the GFM metacharacters; leaves angle
         * brackets alone (HTML passthrough is fine in our exports).
         *
         * Lives on the companion so unit tests can exercise it without constructing
         * a RallyExporter (which needs a real RallyApiClient).
         */
        /**
         * A relative path usable as a Markdown link destination. Sanitized file names keep
         * spaces, parentheses and brackets, and CommonMark ends a bare destination at a space
         * (so "Screenshot 1.png" rendered as literal text) or an unbalanced ')'. Percent-encode
         * them, per path segment so the '/' separators stay.
         */
        internal fun markdownLinkTarget(path: String): String =
            path.split('/').joinToString("/") { segment ->
                buildString {
                    for (c in segment) when (c) {
                        ' ' -> append("%20")
                        '(' -> append("%28")
                        ')' -> append("%29")
                        '[' -> append("%5B")
                        ']' -> append("%5D")
                        '%' -> append("%25")
                        '<' -> append("%3C")
                        '>' -> append("%3E")
                        else -> append(c)
                    }
                }
            }

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
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                // &amp; must decode LAST: decoding it first turns &amp;lt; into &lt;
                // which the later passes then double-decode into a real '<' (L1).
                .replace("&amp;", "&")
                .replace(RE_MULTI_NEWLINE, "\n\n")
                .trim()
        }

        /**
         * Local file name for an exported inline image. Keyed on the attachment
         * ObjectID (unique in Rally) rather than a per-call counter: the
         * description and each test step run their own downloadInlineImages()
         * call, so counters restart at 1 and distinct images collide on the
         * same "$artifactId.$ext" name, silently overwriting each other on disk.
         */
        internal fun inlineImageLocalName(artifactId: String, objectId: String, originalFileName: String): String {
            val ext = originalFileName.substringAfterLast('.', "png").lowercase()
            return RallyFileUtils.sanitizeFileName("${artifactId}_${objectId}.$ext")
        }

        /**
         * Local file name for an exported attachment (M5). Keyed on the attachment
         * ObjectID (unique in Rally) so re-exports overwrite deterministically instead
         * of accumulating name_1, name_2, … copies: the old collision counter deduped
         * within one exporter instance, but every export click creates a fresh exporter
         * while the files persist on disk. Falls back to the Content ref's trailing OID
         * when ObjectID is absent. Mirrors inlineImageLocalName.
         */
        internal fun attachmentLocalName(objectId: String?, contentRef: String?, originalFileName: String): String {
            val key = objectId ?: contentRef?.trimEnd('/')?.substringAfterLast('/') ?: "0"
            val baseName = originalFileName.substringAfterLast('/')
            return RallyFileUtils.sanitizeFileName("${key}_$baseName")
        }
    }

    /**
     * Create a dedicated, DAEMON-threaded download pool. Daemon so an in-flight export can never
     * stall IDE/tool-window shutdown: CompletableFuture.join() is not interruptible, so a worker
     * parked on it ignores any shutdown/interrupt signal — daemon threads let the JVM/IDE proceed
     * regardless. Separate from the pool the export task itself runs on (the per-export
     * "rally-export-orchestrator" pool — see exportSelectedArtifact in RallyToolWindowPanel)
     * so the task's blocking join() never waits on downloads queued behind it on its own
     * pool, and from client.apiExecutor so downloads never starve interactive queries
     * (see the call sites). Called once, from [downloadPoolLazy]'s initializer (L6) — not
     * per download call.
     */
    private fun newDownloadPool(size: Int) =
        Executors.newFixedThreadPool(size) { r ->
            Thread(r, "rally-export-download").apply { isDaemon = true }
        }

    /**
     * Shared download pool for inline images + attachments (L6). Previously each
     * downloadInlineImages / attachmentsToJsonArray call created and destroyed its
     * own pool — appendStepsTable calls downloadInlineImages twice per test step, so
     * an image-heavy 20-step test case churned ~40 pools. Lazily created on first
     * use; released via [close] (call sites use `use {}` / a finally). Threads are
     * daemon, so a leaked exporter can never stall IDE shutdown. INVARIANT: tasks on
     * this pool never submit-and-join back into it — joins happen only on the export
     * orchestration threads (see the M1 pool in RallyToolWindowPanel).
     */
    private val downloadPoolLazy = lazy { newDownloadPool(DOWNLOAD_POOL_SIZE) }
    private val downloadPool by downloadPoolLazy

    override fun close() {
        if (downloadPoolLazy.isInitialized()) downloadPool.shutdownNow()
    }

    /** Per-session cache for downloaded attachment paths (deduplicates across JSON+Markdown export). */
    private val downloadedPaths = ConcurrentHashMap<String, String>()

    /** Per-session cache for downloaded inline-image paths — same dedup role as
     *  [downloadedPaths]: the JSON and Markdown passes generate identical image jobs. */
    private val downloadedImagePaths = ConcurrentHashMap<String, String>()

    /** Descriptions fetched by [resolveDescription], keyed by artifact ref — shared by the
     *  JSON and Markdown passes of one export. Only successful fetches are stored. */
    private val resolvedDescriptions = ConcurrentHashMap<String, String>()

    /** Attachment/inline-image downloads that failed and never succeeded on a later pass.
     *  The file marks each one ("downloaded": false / "(download failed)"), but the export
     *  still reads as complete, so callers report [failedDownloadCount] in their summary. */
    private val failedDownloads: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Distinct attachment/inline-image downloads that failed during this export session. */
    val failedDownloadCount: Int get() = failedDownloads.size

    /**
     * Guards the filesystem name-allocation + write critical section in
     * [downloadAttachmentContent] (MED-1/P2). Attachment downloads now run on a
     * dedicated pool, so two distinct attachments sharing a sanitized name could
     * otherwise race the `Files.exists` collision counter and pick the same suffix.
     * The network download stays outside the lock; only the resolve-and-write is serialized.
     */
    private val attachmentWriteLock = Any()

    // ── Test Case Export ─────────────────────────────────────────

    fun exportTestCaseJson(testCaseId: String, outputDir: String) {
        LOG.info("Generating JSON for test case: $testCaseId")

        val tc = queryTestCaseByFormattedId(testCaseId)
            ?: throw RuntimeException("Test case $testCaseId not found")

        val steps = client.queryTestSteps(testCaseId)
        // No fallback to an empty list: a failed lookup must fail this export (like the
        // strict description fetch) instead of writing a file that silently lacks attachments.
        val attachments = client.queryAttachments(testCaseId)

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
        // No fallback to an empty list: a failed lookup must fail this export (like the
        // strict description fetch) instead of writing a file that silently lacks attachments.
        val attachments = client.queryAttachments(testCaseId)

        val md = StringBuilder()
        md.appendLine("# $testCaseId - ${escapeMarkdown(tc.name ?: "")}")
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
     *
     * NOTE: no UI entry point yet — deliberately kept for the planned Bulk Export UI
     * (see CLAUDE.md "Not Yet Implemented"). Do not delete as dead code.
     */
    fun bulkExportJson(artifacts: List<com.github.halmurat.rally.api.RallyArtifact>, outputDir: String, fileName: String = "bulk_export", onProgress: ((Int) -> Unit)? = null): Int {
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
                    addProperty("state", artifact.effectiveStateOrEmpty)
                    addProperty("owner", artifact.owner?.displayName ?: artifact.owner?.refObjectName ?: "")
                    addProperty("creationDate", artifact.creationDate ?: "")
                    addProperty("lastUpdateDate", artifact.lastUpdateDate ?: "")
                }

                // Defect-specific fields
                if (artifact is com.github.halmurat.rally.api.RallyDefect) {
                    obj.addProperty("severity", artifact.severity ?: "")
                    obj.addProperty("priority", artifact.priority ?: "")
                    obj.addProperty("environment", artifact.environment ?: "")
                    obj.addProperty("project", artifact.project?.refObjectName ?: artifact.project?.name ?: "")
                    obj.addProperty("iteration", artifact.iteration?.refObjectName ?: artifact.iteration?.name ?: "")
                }

                // User story specific fields
                if (artifact is com.github.halmurat.rally.api.RallyUserStory) {
                    obj.addProperty("planEstimate", artifact.storyPoints ?: 0.0)
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
     *
     * NOTE: no UI entry point yet — deliberately kept for the planned Bulk Export UI
     * (see CLAUDE.md "Not Yet Implemented"). Do not delete as dead code.
     */
    fun bulkExportMarkdown(artifacts: List<com.github.halmurat.rally.api.RallyArtifact>, outputDir: String, fileName: String = "bulk_export", onProgress: ((Int) -> Unit)? = null): Int {
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
                    writer.write("- **State:** ${escapeMarkdown(artifact.effectiveStateOrEmpty)}\n")
                    writer.write("- **Owner:** $safeOwner\n")
                    writer.write("- **Created:** ${artifact.creationDate?.take(10) ?: ""}\n")
                    writer.write("- **Updated:** ${artifact.lastUpdateDate?.take(10) ?: ""}\n")

                    if (artifact is com.github.halmurat.rally.api.RallyDefect) {
                        writer.write("- **Severity:** ${escapeMarkdown(artifact.severity ?: "")}\n")
                        writer.write("- **Priority:** ${escapeMarkdown(artifact.priority ?: "")}\n")
                        writer.write("- **Environment:** ${escapeMarkdown(artifact.environment ?: "")}\n")
                        writer.write("- **Project:** ${escapeMarkdown(artifact.project?.refObjectName ?: artifact.project?.name ?: "")}\n")
                        writer.write("- **Iteration:** ${escapeMarkdown(artifact.iteration?.refObjectName ?: artifact.iteration?.name ?: "")}\n")
                    }

                    if (artifact is com.github.halmurat.rally.api.RallyUserStory) {
                        writer.write("- **Plan Estimate:** ${artifact.storyPoints ?: ""}\n")
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
     *
     * THREADING CONTRACT: this fans out N tasks onto [RallyApiClient.apiExecutor] (the fixed
     * 4-thread pool) and blocks the caller on join(), so it MUST NOT be called from an
     * apiExecutor thread — that worker would park on join() while its own tasks queue behind it,
     * starving the pool (the same hazard `queryAllArtifactsParallel` and the dedicated export
     * download pools are designed around). Its callers (`bulkExport*`) run on
     * `executeOnPooledThread`, never on apiExecutor.
     *
     * NOTE: no UI entry point yet — deliberately kept for the planned Bulk Export UI
     * (see CLAUDE.md "Not Yet Implemented"). Do not delete as dead code.
     */
    private fun prefetchDescriptions(
        artifacts: List<com.github.halmurat.rally.api.RallyArtifact>,
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

    // The String-ID entry points resolve the artifact via a FormattedID search
    // (getArtifactByFormattedId) and then delegate to the artifact-object overloads.
    // The test-case export entry points and any caller that only has an ID still use
    // these.
    fun exportArtifactJson(artifactId: String, outputDir: String) {
        val artifact = client.getArtifactByFormattedId(artifactId)
            ?: throw RuntimeException("Artifact $artifactId not found")
        exportArtifactJson(artifact, outputDir)
    }

    /**
     * Export an already-in-memory artifact to JSON (MED-3). Avoids the redundant
     * FormattedID search the String-ID overload pays: the artifact object is already
     * loaded, and its Description (excluded from list queries) is obtained via the
     * cheaper direct-ref [RallyApiClient.fetchDescriptionStrict] GET rather than another
     * heavy search query. If the object already carries a non-blank Description, no
     * fetch is issued at all.
     */
    fun exportArtifactJson(artifact: RallyArtifact, outputDir: String) {
        val artifactId = artifact.formattedID ?: ""
        LOG.info("Generating JSON for artifact: $artifactId")
        // No fallback to an empty list: a failed lookup must fail this export (like the
        // strict description fetch) instead of writing a file that silently lacks attachments.
        // Looked up first so a failure leaves nothing behind (not even downloaded inline images).
        val attachments = client.queryAttachments(artifactId)

        val output = JsonObject().apply {
            addProperty("id", artifactId)
            addProperty("artifactType", artifact.type ?: "")
            addProperty("name", artifact.name ?: "")
            addProperty("extractedAt", Instant.now().toString())
        }

        // Process description with inline images
        val rawDesc = resolveDescription(artifact)
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
        output.add("attachments", attachmentsToJsonArray(attachments, artifactId, outputDir))
        output.addProperty("totalAttachments", attachments.size)

        val outRoot = Paths.get(outputDir)
        Files.createDirectories(outRoot)
        val file = RallyFileUtils.safeResolve(outRoot, "$artifactId.json").toFile()
        file.bufferedWriter(StandardCharsets.UTF_8).use { gson.toJson(output, it) }
        LOG.info("Generated JSON: ${file.absolutePath}")
    }

    fun exportArtifactMarkdown(artifactId: String, outputDir: String) {
        val artifact = client.getArtifactByFormattedId(artifactId)
            ?: throw RuntimeException("Artifact $artifactId not found")
        exportArtifactMarkdown(artifact, outputDir)
    }

    /**
     * Export an already-in-memory artifact to Markdown (MED-3). Same rationale as
     * [exportArtifactJson]: no redundant FormattedID search, Description resolved via
     * the cheaper direct-ref [RallyApiClient.fetchDescriptionStrict] (or skipped entirely
     * when already populated).
     */
    fun exportArtifactMarkdown(artifact: RallyArtifact, outputDir: String) {
        val artifactId = artifact.formattedID ?: ""
        LOG.info("Generating Markdown for artifact: $artifactId")
        // No fallback to an empty list: a failed lookup must fail this export (like the
        // strict description fetch) instead of writing a file that silently lacks attachments.
        // Looked up first so a failure leaves nothing behind (not even downloaded inline images).
        val attachments = client.queryAttachments(artifactId)

        val md = StringBuilder()
        md.appendLine("# $artifactId - ${escapeMarkdown(artifact.name ?: "")}")
        md.appendLine()

        // Description with inline images
        val desc = downloadInlineImages(resolveDescription(artifact), artifactId, outputDir)
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

    /**
     * Description for an in-memory artifact (MED-3). List queries exclude Description
     * for smaller payloads, so the object's [RallyArtifact.description] is usually null;
     * we then fetch it via the cheap direct-ref GET instead of re-running a FormattedID
     * search. When the object already carries a non-blank Description, no fetch is issued.
     *
     * The fetch is strict ([RallyApiClient.fetchDescriptionStrict]): a failed request or a
     * deleted artifact throws and fails the export instead of writing an empty description
     * (EXP-1). A successful result is memoized in [resolvedDescriptions] so the JSON and
     * Markdown passes share one fetch and can't disagree; failures are not memoized.
     */
    private fun resolveDescription(artifact: RallyArtifact): String {
        artifact.description?.takeIf { it.isNotBlank() }?.let { return it }
        val ref = artifact.ref ?: return ""
        resolvedDescriptions[ref]?.let { return it }
        val desc = client.fetchDescriptionStrict(ref)
        return resolvedDescriptions.putIfAbsent(ref, desc) ?: desc
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Lookup failures propagate: turning them into null would report a network or auth
     *  error as "Test case X not found". */
    private fun queryTestCaseByFormattedId(testCaseId: String): com.github.halmurat.rally.api.RallyTestCase? =
        client.queryTestCaseByFormattedId(testCaseId)

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
        if (attachments.isEmpty()) return JsonArray()

        // Download attachments in parallel on the shared download pool (L6, was MED-1/P2's
        // per-call pool). This pool MUST be separate from the pool the export task runs on
        // (the per-export orchestration pool — see exportSelectedArtifact in
        // RallyToolWindowPanel): the export task blocks on join(), so submitting inner
        // download tasks back to its own fixed pool would self-deadlock once the selection
        // count reaches the pool size. It also stays off client.apiExecutor so downloads
        // never occupy the interactive-query threads. The dedup caches (downloadedPaths) are
        // ConcurrentHashMap and the filename-allocation+write is serialized via
        // attachmentWriteLock, so concurrent downloads stay correct. Results are written
        // into an index-keyed array so the emitted JSON order matches the input order
        // regardless of completion order.
        val savedPaths = arrayOfNulls<String>(attachments.size)
        val futures = attachments.mapIndexed { index, att ->
            val attName = att.name ?: "unnamed"
            CompletableFuture.runAsync({
                savedPaths[index] = downloadAttachmentContent(att, attachDir, attName)
            }, downloadPool)
        }
        CompletableFuture.allOf(*futures.toTypedArray()).join()

        val array = JsonArray()
        for ((index, att) in attachments.withIndex()) {
            val attName = att.name ?: "unnamed"
            val savedPath = savedPaths[index]
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
        val objectId = attachment.objectID
        val contentRef = attachment.content?.ref
        if (objectId == null && contentRef == null) return null
        val safeFileName = attachmentLocalName(objectId, contentRef, fileName)
        val cacheKey = "${objectId ?: contentRef}:$attachDir:$safeFileName"

        // Check per-session dedup cache (avoids re-downloading for JSON+Markdown exports)
        downloadedPaths[cacheKey]?.let { return it }

        return try {
            val attachDirPath = Paths.get(attachDir)
            Files.createDirectories(attachDirPath)

            // Raw-first with base64 fallback, shared with the detail panel's
            // Save to Disk (M4) — see RallyApiClient.downloadAttachmentBytes.
            val fileBytes = client.downloadAttachmentBytes(attachment)
                ?: run { failedDownloads.add("att:$cacheKey"); return null }

            // OID-keyed names are unique per attachment (M5), so same-name collisions
            // are impossible and re-exports idempotently overwrite. The lock now only
            // serializes two threads racing the SAME attachment past a dedup-cache
            // miss — a concurrent truncate-mid-write would corrupt the file.
            val path = synchronized(attachmentWriteLock) {
                val outputPath = RallyFileUtils.safeResolve(attachDirPath, safeFileName)
                Files.write(outputPath, fileBytes)
                LOG.info("Saved attachment: $outputPath (${fileBytes.size} bytes)")
                outputPath.toAbsolutePath().toString()
            }
            // putIfAbsent: harmless today (passes are sequential per artifact), but keeps
            // a same-key race from ever returning two different paths for one attachment.
            failedDownloads.remove("att:$cacheKey")
            downloadedPaths.putIfAbsent(cacheKey, path) ?: path
        } catch (e: Exception) {
            LOG.warn("Failed to download attachment '$fileName'", e)
            failedDownloads.add("att:$cacheKey")
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
        while (matcher.find() && jobs.size < MAX_INLINE_IMAGES_PER_DESCRIPTION) {
            val originalSrc = matcher.group(2)
            val objectId = matcher.group(3)
            val fileName = matcher.group(4)
            jobs.add(ImageJob(originalSrc, objectId, fileName, inlineImageLocalName(artifactId, objectId, fileName)))
        }

        if (jobs.isEmpty()) return html

        // Pass 2: download in parallel on the shared download pool (L6, was MED-1/P2's
        // per-call pool). This pool MUST be separate from the pool the export task runs
        // on: each artifact's exportArtifact{Json,Markdown} runs as a task on the per-export
        // orchestration pool (see exportSelectedArtifact in RallyToolWindowPanel), so
        // submitting inner download tasks back to that fixed pool and blocking on .join()
        // would self-deadlock once the selection count reaches the pool size —
        // attachmentsToJsonArray avoids it the same way. It also stays off client.apiExecutor
        // so downloads never starve interactive queries.
        //
        // De-dup the actual downloads by target filename (distinctBy uniqueFileName): the same
        // OID can appear in more than one <img>, which yields the same output path — writing it
        // from two threads at once would race (truncate-mid-write). The sequential version was
        // safe because the second job hit the dedup cache; here we collapse same-file jobs so
        // each path is written exactly once, then map the result back to EVERY originalSrc so
        // Pass 3 still rewrites all occurrences. Results consumed in document order by Pass 3,
        // so completion order does not affect output.
        val imgDir = RallyFileUtils.safeResolve(Paths.get(outputDir), "${artifactId}_images").toString()
        val byFile = ConcurrentHashMap<String, String>()  // uniqueFileName -> relative local path
        val uniqueJobs = jobs.distinctBy { it.uniqueFileName }
        val futures = uniqueJobs.map { job ->
            CompletableFuture.runAsync({
                val localPath = downloadRallyImage(job.objectId, job.originalFileName, job.uniqueFileName, imgDir)
                if (localPath != null) {
                    byFile[job.uniqueFileName] = "${artifactId}_images/${job.uniqueFileName}"
                }
            }, downloadPool)
        }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        // Map every original src occurrence to its downloaded path (single-threaded, post-join).
        val downloads = HashMap<String, String>()  // originalSrc -> relative local path
        for (job in jobs) {
            byFile[job.uniqueFileName]?.let { downloads[job.originalSrc] = it }
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

            // inlineImageUrl percent-encodes URI-illegal filenames (spaces, quotes) —
            // without it the regex match is moot: URI() throws inside the download
            // layer and the image silently stays remote.
            val imageUrl = RallyHtmlUtils.inlineImageUrl(client.webBaseUrl, objectId, originalFileName)

            val fileBytes = client.downloadAttachment(imageUrl, cache = false)

            val outputPath = RallyFileUtils.safeResolve(imgDirPath, localFileName)
            Files.write(outputPath, fileBytes)
            LOG.info("Downloaded inline image: $outputPath (${fileBytes.size} bytes)")
            val path = outputPath.toAbsolutePath().toString()
            failedDownloads.remove("img:$cacheKey")
            return downloadedImagePaths.putIfAbsent(cacheKey, path) ?: path
        } catch (e: Exception) {
            // Don't cache failures — allow retry on transient errors
            LOG.warn("Failed to download image OID=$objectId", e)
            failedDownloads.add("img:$cacheKey")
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
                val target = markdownLinkTarget("$attachDirName/$savedFileName")
                val label = escapeMarkdown(attName)
                if (contentType.startsWith("image/")) {
                    md.appendLine("### $label")
                    md.appendLine("![$label]($target)")
                    md.appendLine()
                } else {
                    md.appendLine("- [$label]($target)")
                }
            } else {
                md.appendLine("- ${escapeMarkdown(attName)} *(download failed)*")
            }
        }
        md.appendLine()
    }
}
