package com.github.halmurat.rally.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins which repository Start Working branches in single- and multi-root projects. */
class RepositoryChoiceTest {

    @Test
    fun `the repository holding the project root wins among several`() {
        assertEquals(RepositoryChoice.Chosen("app"), pickRepository("app", listOf("vendor", "app", "docs")))
    }

    @Test
    fun `a single repository is used even when it doesn't hold the project root`() {
        assertEquals(RepositoryChoice.Chosen("only"), pickRepository(null, listOf("only")))
    }

    @Test
    fun `several repositories and none holding the project root is refused`() {
        val choice = pickRepository(null, listOf("a", "b"))
        assertTrue(choice is RepositoryChoice.Refused)
        assertTrue((choice as RepositoryChoice.Refused).reason.contains("2 Git repositories"))
    }

    @Test
    fun `no repositories is refused`() {
        assertEquals(
            RepositoryChoice.Refused("No Git repository found in this project"),
            pickRepository<String>(null, emptyList())
        )
    }
}
