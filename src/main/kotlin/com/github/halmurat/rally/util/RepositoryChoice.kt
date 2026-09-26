package com.github.halmurat.rally.util

/**
 * Which repository Start Working branches. Generic and free of git4idea types so it is
 * unit-testable and loads even when Git4Idea is absent (see [RallyGitOps]).
 */
internal sealed class RepositoryChoice<out R> {
    data class Chosen<R>(val repo: R) : RepositoryChoice<R>()
    data class Refused(val reason: String) : RepositoryChoice<Nothing>()
}

/**
 * In a multi-root project, branch the repository that holds the project root ([rootRepo]) —
 * the user's working repo — rather than whichever root the manager happens to list first
 * (possibly a vendored or submodule repo). A single repository is used even when it doesn't
 * contain the project root (e.g. its root sits above or beside the project dir). With several
 * repositories and none holding the project root, refuse to guess.
 */
internal fun <R> pickRepository(rootRepo: R?, repos: List<R>): RepositoryChoice<R> = when {
    rootRepo != null -> RepositoryChoice.Chosen(rootRepo)
    repos.size == 1 -> RepositoryChoice.Chosen(repos.single())
    repos.isEmpty() -> RepositoryChoice.Refused("No Git repository found in this project")
    else -> RepositoryChoice.Refused(
        "This project has ${repos.size} Git repositories and none contains the project root — " +
            "can't tell which one to create the branch in"
    )
}
