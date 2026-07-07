package com.github.halmurat.rally.api

import com.google.gson.annotations.SerializedName

/**
 * Wrapper for Rally API query responses
 */
data class RallyQueryResult<T>(
    @SerializedName("QueryResult")
    val queryResult: QueryResultData<T>
)

data class QueryResultData<T>(
    @SerializedName("Results")
    val results: List<T>? = null,

    @SerializedName("TotalResultCount")
    val totalResultCount: Int = 0,

    @SerializedName("PageSize")
    val pageSize: Int = 0,

    @SerializedName("StartIndex")
    val startIndex: Int = 0,

    @SerializedName("Errors")
    val errors: List<String>? = null,

    @SerializedName("Warnings")
    val warnings: List<String>? = null
) {
    /** Null-safe accessor for results — Gson bypasses Kotlin defaults and can inject null. */
    val safeResults: List<T> get() = results ?: emptyList()
}

/**
 * Rally WSAPI 2.0 returns HTTP 200 with a populated `Errors` array for field/permission/scoping
 * errors — the request "succeeds" at the transport level but `Results` is empty. Without this guard,
 * such errors are silently swallowed and callers get empty lists. This turns a non-empty `Errors`
 * array into a thrown [RallyApiException] (HIGH-1).
 *
 * `internal` so it is unit-testable. [RallyApiException] is in the same package (api) — no import needed.
 */
internal fun QueryResultData<*>.requireNoErrors(context: String) {
    if (!errors.isNullOrEmpty()) throw RallyApiException("Rally query error ($context): ${errors!!.joinToString("; ")}")
}

/**
 * Result of an artifact query that may have partially failed (e.g. user stories loaded but defects
 * errored, or vice-versa). Carries the human-readable [partialFailureReasons] so the UI can surface
 * an "incomplete" indicator instead of silently showing a truncated list (MED-8).
 */
data class ArtifactQueryResult(
    val artifacts: List<RallyArtifact>,
    val partialFailureReasons: List<String> = emptyList()
) {
    val isPartial: Boolean get() = partialFailureReasons.isNotEmpty()
}

/**
 * Base interface for all Rally artifacts
 */
interface RallyArtifact {
    val ref: String?
    val objectID: String?
    val formattedID: String?
    val name: String?
    val description: String?
    val creationDate: String?
    val lastUpdateDate: String?
    val owner: RallyUser?
    val scheduleState: String?
    val state: String?
    val type: String?
}

/**
 * Rally User Story (HierarchicalRequirement)
 */
data class RallyUserStory(
    @SerializedName("_ref")
    override val ref: String? = null,

    @SerializedName("ObjectID")
    override val objectID: String? = null,

    @SerializedName("FormattedID")
    override val formattedID: String? = null,

    @SerializedName("Name")
    override val name: String? = null,

    @SerializedName("Description")
    override val description: String? = null,

    @SerializedName("CreationDate")
    override val creationDate: String? = null,

    @SerializedName("LastUpdateDate")
    override val lastUpdateDate: String? = null,

    @SerializedName("Owner")
    override val owner: RallyUser? = null,

    @SerializedName("ScheduleState")
    override val scheduleState: String? = null,

    @SerializedName("State")
    override val state: String? = null,

    @SerializedName("_type")
    override val type: String? = "HierarchicalRequirement",

    @SerializedName("Project")
    val project: RallyRef? = null,

    @SerializedName("Iteration")
    val iteration: RallyRef? = null,

    @SerializedName("PlanEstimate")
    val planEstimate: Double? = null,

    @SerializedName("TaskActualTotal")
    val taskActualTotal: Double? = null,

    @SerializedName("TaskEstimateTotal")
    val taskEstimateTotal: Double? = null,

    @SerializedName("Blocked")
    val blocked: Boolean? = null,

    @SerializedName("BlockedReason")
    val blockedReason: String? = null,

    @SerializedName("Release")
    val release: RallyRef? = null,

    @SerializedName("Ready")
    val ready: Boolean? = null
) : RallyArtifact

/**
 * Rally Defect
 */
data class RallyDefect(
    @SerializedName("_ref")
    override val ref: String? = null,

    @SerializedName("ObjectID")
    override val objectID: String? = null,

    @SerializedName("FormattedID")
    override val formattedID: String? = null,

    @SerializedName("Name")
    override val name: String? = null,

    @SerializedName("Description")
    override val description: String? = null,

    @SerializedName("CreationDate")
    override val creationDate: String? = null,

    @SerializedName("LastUpdateDate")
    override val lastUpdateDate: String? = null,

    @SerializedName("Owner")
    override val owner: RallyUser? = null,

    @SerializedName("ScheduleState")
    override val scheduleState: String? = null,

    @SerializedName("State")
    override val state: String? = null,

    @SerializedName("_type")
    override val type: String? = "Defect",

    @SerializedName("Project")
    val project: RallyRef? = null,

    @SerializedName("Iteration")
    val iteration: RallyRef? = null,

    @SerializedName("Severity")
    val severity: String? = null,

    @SerializedName("Priority")
    val priority: String? = null,

    @SerializedName("Environment")
    val environment: String? = null,

    @SerializedName("PlanEstimate")
    val planEstimate: Double? = null,

    @SerializedName("Blocked")
    val blocked: Boolean? = null,

    @SerializedName("BlockedReason")
    val blockedReason: String? = null,

    @SerializedName("Release")
    val release: RallyRef? = null,

    @SerializedName("Ready")
    val ready: Boolean? = null
) : RallyArtifact

/**
 * Rally Task
 */
data class RallyTaskItem(
    @SerializedName("_ref")
    override val ref: String? = null,

    @SerializedName("ObjectID")
    override val objectID: String? = null,

    @SerializedName("FormattedID")
    override val formattedID: String? = null,

    @SerializedName("Name")
    override val name: String? = null,

    @SerializedName("Description")
    override val description: String? = null,

    @SerializedName("CreationDate")
    override val creationDate: String? = null,

    @SerializedName("LastUpdateDate")
    override val lastUpdateDate: String? = null,

    @SerializedName("Owner")
    override val owner: RallyUser? = null,

    @SerializedName("ScheduleState")
    override val scheduleState: String? = null,

    @SerializedName("State")
    override val state: String? = null,

    @SerializedName("_type")
    override val type: String? = "Task",

    @SerializedName("WorkProduct")
    val workProduct: RallyRef? = null,

    @SerializedName("Estimate")
    val estimate: Double? = null,

    @SerializedName("Actuals")
    val actuals: Double? = null,

    @SerializedName("ToDo")
    val toDo: Double? = null
) : RallyArtifact

/**
 * Rally User/Owner
 */
data class RallyUser(
    @SerializedName("_ref")
    val ref: String? = null,

    @SerializedName("_refObjectName")
    val refObjectName: String? = null,

    @SerializedName("DisplayName")
    val displayName: String? = null,

    @SerializedName("UserName")
    val userName: String? = null,

    @SerializedName("EmailAddress")
    val emailAddress: String? = null
)

/**
 * Rally reference object (for projects, iterations, etc.)
 */
data class RallyRef(
    @SerializedName("_ref")
    val ref: String? = null,

    @SerializedName("_refObjectName")
    val refObjectName: String? = null,

    @SerializedName("Name")
    val name: String? = null
)

/**
 * Rally Project
 */
data class RallyProject(
    @SerializedName("_ref")
    val ref: String? = null,

    @SerializedName("ObjectID")
    val objectID: String? = null,

    @SerializedName("Name")
    val name: String? = null,

    @SerializedName("State")
    val state: String? = null
)

/**
 * Rally Iteration (Sprint)
 */
data class RallyIteration(
    @SerializedName("_ref")
    val ref: String? = null,

    @SerializedName("ObjectID")
    val objectID: String? = null,

    @SerializedName("Name")
    val name: String? = null,

    @SerializedName("StartDate")
    val startDate: String? = null,

    @SerializedName("EndDate")
    val endDate: String? = null,

    @SerializedName("PlannedVelocity")
    val plannedVelocity: Double? = null,

    @SerializedName("State")
    val state: String? = null,

    @SerializedName("Project")
    val project: RallyRef? = null
)

/**
 * Rally Test Case
 */
data class RallyTestCase(
    @SerializedName("_ref")
    override val ref: String? = null,

    @SerializedName("ObjectID")
    override val objectID: String? = null,

    @SerializedName("FormattedID")
    override val formattedID: String? = null,

    @SerializedName("Name")
    override val name: String? = null,

    @SerializedName("Description")
    override val description: String? = null,

    @SerializedName("CreationDate")
    override val creationDate: String? = null,

    @SerializedName("LastUpdateDate")
    override val lastUpdateDate: String? = null,

    @SerializedName("Owner")
    override val owner: RallyUser? = null,

    @SerializedName("ScheduleState")
    override val scheduleState: String? = null,

    @SerializedName("State")
    override val state: String? = null,

    @SerializedName("_type")
    override val type: String? = "TestCase",

    @SerializedName("Method")
    val method: String? = null,

    @SerializedName("Type")
    val testType: String? = null,

    @SerializedName("LastVerdict")
    val lastVerdict: String? = null,

    @SerializedName("LastRun")
    val lastRun: String? = null,

    @SerializedName("WorkProduct")
    val workProduct: RallyRef? = null,

    @SerializedName("Project")
    val project: RallyRef? = null,

    @SerializedName("Priority")
    val priority: String? = null
) : RallyArtifact

/**
 * Rally Test Case Step
 */
data class RallyTestCaseStep(
    @SerializedName("_ref")
    val ref: String? = null,

    @SerializedName("StepIndex")
    val stepIndex: Int? = null,

    @SerializedName("Input")
    val input: String? = null,

    @SerializedName("ExpectedResult")
    val expectedResult: String? = null
)

/**
 * Rally Attachment
 */
data class RallyAttachment(
    @SerializedName("_ref")
    val ref: String? = null,

    @SerializedName("ObjectID")
    val objectID: String? = null,

    @SerializedName("Name")
    val name: String? = null,

    @SerializedName("ContentType")
    val contentType: String? = null,

    @SerializedName("Size")
    val size: Long? = null,

    @SerializedName("Description")
    val description: String? = null,

    @SerializedName("Content")
    val content: RallyRef? = null
)
