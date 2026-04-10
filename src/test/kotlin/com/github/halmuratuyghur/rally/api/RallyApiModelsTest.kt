package com.github.halmuratuyghur.rally.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class RallyApiModelsTest {

    private val gson = Gson()

    @Test
    fun `parse user story from JSON`() {
        val json = """
        {
            "_ref": "https://rally1.rallydev.com/slm/webservice/v2.0/hierarchicalrequirement/123",
            "ObjectID": "123",
            "FormattedID": "US1234",
            "Name": "Test Story",
            "ScheduleState": "In-Progress",
            "_type": "HierarchicalRequirement",
            "PlanEstimate": 5.0,
            "Owner": {
                "_ref": "https://rally1.rallydev.com/slm/webservice/v2.0/user/456",
                "DisplayName": "John Doe",
                "UserName": "john.doe@example.com"
            },
            "Iteration": {
                "_ref": "https://rally1.rallydev.com/slm/webservice/v2.0/iteration/789",
                "Name": "Sprint 42"
            }
        }
        """.trimIndent()

        val story = gson.fromJson(json, RallyUserStory::class.java)
        assertEquals("US1234", story.formattedID)
        assertEquals("Test Story", story.name)
        assertEquals("In-Progress", story.scheduleState)
        assertEquals("HierarchicalRequirement", story.type)
        assertEquals(5.0, story.planEstimate!!, 0.001)
        assertEquals("John Doe", story.owner?.displayName)
        assertEquals("Sprint 42", story.iteration?.name)
    }

    @Test
    fun `parse defect with severity and priority`() {
        val json = """
        {
            "FormattedID": "DE5678",
            "Name": "Login Bug",
            "ScheduleState": "Defined",
            "State": "Submitted",
            "_type": "Defect",
            "Severity": "Major Problem",
            "Priority": "High Attention",
            "Environment": "Production"
        }
        """.trimIndent()

        val defect = gson.fromJson(json, RallyDefect::class.java)
        assertEquals("DE5678", defect.formattedID)
        assertEquals("Submitted", defect.state)
        assertEquals("Defined", defect.scheduleState)
        assertEquals("Major Problem", defect.severity)
        assertEquals("High Attention", defect.priority)
        assertEquals("Production", defect.environment)
    }

    @Test
    fun `parse defect with blocked and release fields`() {
        val json = """
        {
            "FormattedID": "DE200",
            "Name": "Blocked Defect",
            "_type": "Defect",
            "Blocked": true,
            "BlockedReason": "Waiting on infra",
            "Ready": false,
            "Release": {"_ref": "https://rally1.rallydev.com/slm/webservice/v2.0/release/888", "Name": "Q2 2026"}
        }
        """.trimIndent()

        val defect = gson.fromJson(json, RallyDefect::class.java)
        assertEquals(true, defect.blocked)
        assertEquals("Waiting on infra", defect.blockedReason)
        assertEquals(false, defect.ready)
        assertEquals("Q2 2026", defect.release?.name)
    }

    @Test
    fun `parse test case with method and verdict`() {
        val json = """
        {
            "FormattedID": "TC9999",
            "Name": "Verify Login",
            "_type": "TestCase",
            "Method": "Automated",
            "LastVerdict": "Pass"
        }
        """.trimIndent()

        val tc = gson.fromJson(json, RallyTestCase::class.java)
        assertEquals("TC9999", tc.formattedID)
        assertEquals("Automated", tc.method)
        assertEquals("Pass", tc.lastVerdict)
    }

    @Test
    fun `parse query result with safe results accessor`() {
        val json = """
        {
            "QueryResult": {
                "Results": [
                    {"FormattedID": "US1", "Name": "Story 1", "_type": "HierarchicalRequirement"},
                    {"FormattedID": "US2", "Name": "Story 2", "_type": "HierarchicalRequirement"}
                ],
                "TotalResultCount": 2,
                "PageSize": 200,
                "StartIndex": 1
            }
        }
        """.trimIndent()

        val type = object : TypeToken<RallyQueryResult<RallyUserStory>>() {}.type
        val result = gson.fromJson<RallyQueryResult<RallyUserStory>>(json, type)
        assertEquals(2, result.queryResult.totalResultCount)
        assertEquals(2, result.queryResult.safeResults.size)
        assertEquals("US1", result.queryResult.safeResults[0].formattedID)
    }

    @Test
    fun `query result with null results uses safeResults`() {
        val json = """
        {
            "QueryResult": {
                "TotalResultCount": 0,
                "PageSize": 200,
                "StartIndex": 1,
                "Errors": ["Not authorized"]
            }
        }
        """.trimIndent()

        val type = object : TypeToken<RallyQueryResult<RallyUserStory>>() {}.type
        val result = gson.fromJson<RallyQueryResult<RallyUserStory>>(json, type)
        assertNull(result.queryResult.results)
        assertTrue(result.queryResult.safeResults.isEmpty())
        assertEquals(1, result.queryResult.errors?.size)
        assertEquals("Not authorized", result.queryResult.errors?.first())
    }

    @Test
    fun `parse task with estimate and todo`() {
        val json = """
        {
            "FormattedID": "TA1001",
            "Name": "Write unit tests",
            "_type": "Task",
            "State": "In-Progress",
            "Estimate": 8.0,
            "Actuals": 3.5,
            "ToDo": 4.5,
            "Owner": {"DisplayName": "Jane Smith"}
        }
        """.trimIndent()

        val task = gson.fromJson(json, RallyTaskItem::class.java)
        assertEquals("TA1001", task.formattedID)
        assertEquals("In-Progress", task.state)
        assertEquals(8.0, task.estimate!!, 0.001)
        assertEquals(3.5, task.actuals!!, 0.001)
        assertEquals(4.5, task.toDo!!, 0.001)
    }

    @Test
    fun `parse test case step`() {
        val json = """
        {
            "StepIndex": 1,
            "Input": "Enter credentials",
            "ExpectedResult": "Login successful"
        }
        """.trimIndent()

        val step = gson.fromJson(json, RallyTestCaseStep::class.java)
        assertEquals(1, step.stepIndex)
        assertEquals("Enter credentials", step.input)
        assertEquals("Login successful", step.expectedResult)
    }

    @Test
    fun `parse attachment with content ref`() {
        val json = """
        {
            "ObjectID": "555",
            "Name": "screenshot.png",
            "ContentType": "image/png",
            "Size": 1024,
            "Content": {
                "_ref": "https://rally1.rallydev.com/slm/webservice/v2.0/attachmentcontent/555"
            }
        }
        """.trimIndent()

        val attachment = gson.fromJson(json, RallyAttachment::class.java)
        assertEquals("screenshot.png", attachment.name)
        assertEquals("image/png", attachment.contentType)
        assertEquals(1024L, attachment.size)
        assertNotNull(attachment.content?.ref)
    }

    @Test
    fun `parse user story with blocked and release fields`() {
        val json = """
        {
            "FormattedID": "US100",
            "Name": "Blocked Story",
            "_type": "HierarchicalRequirement",
            "Blocked": true,
            "BlockedReason": "Waiting on API",
            "Ready": false,
            "Release": {"_ref": "https://rally1.rallydev.com/slm/webservice/v2.0/release/999", "Name": "Q1 2026"}
        }
        """.trimIndent()

        val story = gson.fromJson(json, RallyUserStory::class.java)
        assertEquals(true, story.blocked)
        assertEquals("Waiting on API", story.blockedReason)
        assertEquals(false, story.ready)
        assertEquals("Q1 2026", story.release?.name)
    }

    @Test
    fun `missing fields default to null`() {
        val json = """{"FormattedID": "US1", "_type": "HierarchicalRequirement"}"""
        val story = gson.fromJson(json, RallyUserStory::class.java)
        assertEquals("US1", story.formattedID)
        assertNull(story.name)
        assertNull(story.description)
        assertNull(story.owner)
        assertNull(story.planEstimate)
        assertNull(story.iteration)
    }
}
