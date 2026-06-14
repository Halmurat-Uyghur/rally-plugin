package com.github.halmurat.rally.ui

import com.intellij.openapi.project.Project
import com.github.halmurat.rally.api.RallyIteration
import com.github.halmurat.rally.api.RallyProject

/**
 * Create User Story dialog. Uses the shared form from [AbstractCreateArtifactDialog]
 * with no extra rows.
 */
class CreateUserStoryDialog(
    project: Project,
    projects: List<RallyProject>,
    iterations: List<RallyIteration>,
    preselectProjectRef: String?,
    preselectIterationRef: String?
) : AbstractCreateArtifactDialog(
    project, "Create User Story", projects, iterations, preselectProjectRef, preselectIterationRef
) {
    init {
        init()
    }
}
