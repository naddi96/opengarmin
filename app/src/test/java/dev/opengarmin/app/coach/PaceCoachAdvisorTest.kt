package dev.opengarmin.app.coach

import dev.opengarmin.app.data.PaceCourse
import dev.opengarmin.app.data.PacePlanCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceCoachAdvisorTest {
    @Test
    fun `parses fenced json and converts split numbers`() {
        val response = """
            Here is the proposal:
            ```json
            {"message":"Ease the climb","changes":[
              {"split":3,"deltaSeconds":-8},
              {"split":5,"deltaSeconds":8}
            ]}
            ```
        """.trimIndent()
        val proposal = PaceCoachAdvisor.parse(response, splitCount = 10)
        assertEquals("Ease the climb", proposal.message)
        assertEquals(-8, proposal.offsets[2])
        assertEquals(8, proposal.offsets[4])
        assertEquals(0, proposal.offsets.values.sum())
    }

    @Test
    fun `drops out of range splits and normalises the total`() {
        val response = """
            {"message":"Faster start","changes":[
              {"split":1,"deltaSeconds":-8},
              {"split":2,"deltaSeconds":5},
              {"split":99,"deltaSeconds":20}
            ]}
        """.trimIndent()
        val proposal = PaceCoachAdvisor.parse(response, splitCount = 5)
        assertEquals(setOf(0, 1), proposal.offsets.keys)
        assertTrue(proposal.offsets.values.sum() == 0)
    }

    @Test
    fun `recovers split pairs from malformed json`() {
        val response = """
            Sure, here is the plan:
            { "message": "Ease the climb", "changes": [
              { "split": 3, "deltaSeconds": -6 },
              { "split": 4, "deltaSeconds": 6 },
            ] }
        """.trimIndent()
        val proposal = PaceCoachAdvisor.parse(response, splitCount = 10)
        assertEquals(-6, proposal.offsets[2])
        assertEquals(6, proposal.offsets[3])
    }

    @Test
    fun `recovers change lines when the provider ignores json`() {
        val response = """
            Coach note: keep the start calm.
            CHANGE 2 -5
            CHANGE 8 5
        """.trimIndent()
        val proposal = PaceCoachAdvisor.parse(response, splitCount = 10)
        assertEquals(-5, proposal.offsets[1])
        assertEquals(5, proposal.offsets[7])
    }

    @Test(expected = PaceCoachException::class)
    fun `rejects a response without changes`() {
        PaceCoachAdvisor.parse("""{"message":"No idea"}""", splitCount = 5)
    }

    @Test
    fun `parses an explicitly requested finish time change`() {
        val response = """
            {"message":"Five extra minutes on the climbs",
             "finishTimeDeltaSeconds":300,
             "changes":[{"split":1,"deltaSeconds":20},{"split":3,"deltaSeconds":280}]}
        """.trimIndent()
        val proposal = PaceCoachAdvisor.parse(response, splitCount = 5)
        assertEquals(300, proposal.offsets.values.sum())
        assertEquals(280, proposal.offsets[2])
    }

    @Test
    fun `local proposal adds requested time by elevation`() {
        val course = PaceCourse(
            id = "hills",
            name = "Hills",
            distanceMeters = 5_000.0,
            gainsPerKm = listOf(0.0, 0.0, 20.0, 50.0, 0.0),
        )
        val splits = PacePlanCalculator.buildSplits(5_000.0, 1_500, 50, 50, course)
        val proposal = PaceCoachAdvisor.localProposal(
            splits,
            "puoi aumentare tempo di 5 minuti e distribuire questi 5 minuti in base al dislivello",
        )
        assertEquals(300, proposal.offsets.values.sum())
        assertTrue((proposal.offsets[3] ?: 0) > (proposal.offsets[0] ?: 0))
        assertEquals(1_800, PacePlanCalculator.applyOffsets(splits, proposal.offsets).sumOf { it.targetSeconds })
    }

    @Test
    fun `local proposals are neutral and stay in range`() {
        val splits = PacePlanCalculator.buildSplits(10_000.0, 3_000, 50, 50)
        listOf("more cautious", "more aggressive", "handle climbs better", "something else").forEach { request ->
            val proposal = PaceCoachAdvisor.localProposal(splits, request)
            assertEquals(0, proposal.offsets.values.sum())
            assertTrue(proposal.offsets.keys.all { it in splits.indices })
            assertTrue(proposal.offsets.isNotEmpty())
        }
    }
}
