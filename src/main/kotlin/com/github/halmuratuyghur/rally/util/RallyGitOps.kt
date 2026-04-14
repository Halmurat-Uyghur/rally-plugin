package com.github.halmuratuyghur.rally.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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

        val repo = repos.first()
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
                brancher.createBranch(branchName, mapOf(repo to "HEAD"))
                brancher.checkout(branchName, false, targetRepos, verifyCheckout)
            }
        } catch (e: Exception) {
            LOG.error("Branch operation failed for $branchName", e)
            latch.countDown()
            return "Branch operation failed: ${e.message}"
        }

        if (!latch.await(30, TimeUnit.SECONDS)) {
            return "Branch operation timed out"
        }
        return error
    }
}
