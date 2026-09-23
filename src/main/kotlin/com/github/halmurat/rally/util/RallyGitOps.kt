package com.github.halmurat.rally.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the Git4Idea APIs used by "Start Working". Lives in a
 * separate file so RallyToolWindowPanel doesn't statically reference
 * git4idea.* classes in its own class-load path — that matters because the
 * plugin now declares Git4Idea as an optional dependency, so the classes may
 * be absent at runtime in IDE builds that ship without Git support.
 *
 * Callers MUST check [isAvailable] before invoking [createOrCheckoutBranch];
 * touching this object's other members when Git4Idea is absent will fail
 * with NoClassDefFoundError on the first git4idea reference.
 */
object RallyGitOps {
    private val LOG = Logger.getInstance(RallyGitOps::class.java)

    /**
     * Probe for Git4Idea without triggering class resolution of our own
     * git4idea.* references. A missing class means Git4Idea isn't installed
     * or isn't enabled in the current IDE.
     */
    fun isAvailable(): Boolean {
        return try {
            Class.forName("git4idea.repo.GitRepositoryManager", false, RallyGitOps::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: NoClassDefFoundError) {
            false
        }
    }

    /**
     * Create [branchName] if it doesn't exist, check it out, and verify that
     * the current branch matches after completion. Runs the actual Git
     * operations synchronously (callers should already be on a pooled thread)
     * and returns null on success or a short error message on failure.
     *
     * Must only be called after [isAvailable] returns true.
     */
    fun createOrCheckoutBranch(project: Project, branchName: String): String? {
        val repoManager = GitRepositoryManager.getInstance(project)
        val repos = repoManager.repositories
        if (repos.isEmpty()) return "No Git repository found in this project"

        // In a multi-root project, branch the repository that holds the project root — the
        // user's working repo — rather than whichever root the manager happens to list first
        // (possibly a vendored or submodule repo). Refuse to guess when that's ambiguous.
        val repo = project.guessProjectDir()?.let { repoManager.getRepositoryForFileQuick(it) }
            ?: repos.singleOrNull()
            ?: return "This project has ${repos.size} Git repositories and none contains the project root — " +
                "can't tell which one to create the branch in"
        val targetRepos = listOf(repo)
        val existingBranches = repo.branches.localBranches.map { it.name }
        val brancher = GitBrancher.getInstance(project)
        val latch = CountDownLatch(1)
        var error: String? = null

        val verifyCheckout = Runnable {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    repo.update()
                    val currentBranch = repo.currentBranchName
                    if (currentBranch != branchName) {
                        error = "Checkout not confirmed (expected: $branchName, current: $currentBranch)"
                    }
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    latch.countDown()
                }
            }
        }

        try {
            if (branchName in existingBranches) {
                brancher.checkout(branchName, false, targetRepos, verifyCheckout)
            } else {
                // createBranch and checkout are both async background tasks with no
                // ordering guarantee between them. Calling them back-to-back races:
                // checkout could run before the branch ref exists and fail with
                // "branch not found". Chaining checkout inside createBranch's completion
                // callback guarantees the ref is present first. (verifyCheckout still
                // confirms the final state, so a failed create is caught either way.)
                brancher.createBranch(branchName, mapOf(repo to "HEAD"), Runnable {
                    brancher.checkout(branchName, false, targetRepos, verifyCheckout)
                })
            }
        } catch (e: Exception) {
            LOG.warn("Branch operation failed for $branchName", e)
            latch.countDown()
            return "Branch operation failed: ${e.message}"
        }

        if (!latch.await(30, TimeUnit.SECONDS)) {
            // GitBrancher's background task can't be cancelled from here, so it may still
            // finish after we give up. Say so, so the user checks before retrying.
            return "Branch operation for $branchName in ${repo.root.name} did not finish within 30 s. " +
                "It may still complete in the background — check the current branch before retrying"
        }
        return error
    }
}
