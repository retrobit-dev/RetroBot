package ai.retrobot.core.arena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Elo maths, pinned down.
 *
 * The expected values are hand-computed, and the same numbers are asserted
 * by the JavaScript arena test in `mock/test/arena.test.js`, so the two
 * implementations cannot drift apart silently.
 */
class EloTest {

    @Test
    fun `expected score of an equal pair is exactly a half`() {
        assertEquals(0.5, Elo.expectedScore(1000.0, 1000.0), TOLERANCE)
    }

    @Test
    fun `a 400 point gap means a ten to one chance`() {
        // The weaker side is expected to score 1 in 11; the stronger 10 in 11.
        assertEquals(1.0 / 11.0, Elo.expectedScore(1000.0, 1400.0), TOLERANCE)
        assertEquals(10.0 / 11.0, Elo.expectedScore(1400.0, 1000.0), TOLERANCE)
        assertEquals(
            1.0,
            Elo.expectedScore(1000.0, 1400.0) + Elo.expectedScore(1400.0, 1000.0),
            TOLERANCE,
        )
    }

    @Test
    fun `a win moves the winner up and the loser down by the same amount`() {
        val (left, right) = Elo.update(1000.0, 1000.0, scoreA = 1.0, k = 32.0)

        assertEquals(1016.0, left, TOLERANCE)
        assertEquals(984.0, right, TOLERANCE)
    }

    @Test
    fun `a loss is the mirror image of a win`() {
        val (left, right) = Elo.update(1000.0, 1000.0, scoreA = 0.0, k = 32.0)

        assertEquals(984.0, left, TOLERANCE)
        assertEquals(1016.0, right, TOLERANCE)
    }

    @Test
    fun `a tie between equals changes nothing`() {
        val (left, right) = Elo.update(1000.0, 1000.0, scoreA = 0.5, k = 32.0)

        assertEquals(1000.0, left, TOLERANCE)
        assertEquals(1000.0, right, TOLERANCE)
    }

    @Test
    fun `the pair of ratings stays centred`() {
        val (left, right) = Elo.update(1200.0, 1000.0, scoreA = 1.0, k = 24.0)

        assertEquals(2200.0, left + right, TOLERANCE)
    }

    @Test
    fun `the K factor shrinks as games accumulate`() {
        assertEquals(48.0, Elo.kFactor(0), TOLERANCE)
        assertEquals(48.0, Elo.kFactor(9), TOLERANCE)
        assertEquals(32.0, Elo.kFactor(10), TOLERANCE)
        assertEquals(32.0, Elo.kFactor(29), TOLERANCE)
        assertEquals(24.0, Elo.kFactor(30), TOLERANCE)
        assertEquals(24.0, Elo.kFactor(99), TOLERANCE)
        assertEquals(16.0, Elo.kFactor(100), TOLERANCE)
    }

    @Test
    fun `the confidence band narrows with the square root of the games`() {
        assertEquals(200.0, Elo.confidenceBand(0), TOLERANCE)
        assertEquals(100.0, Elo.confidenceBand(4), TOLERANCE)
        assertEquals(20.0, Elo.confidenceBand(100), TOLERANCE)
    }

    @Test
    fun `a model with few games is marked uncertain`() {
        assertTrue(Elo.isUncertain(0))
        assertTrue(Elo.isUncertain(19))
        assertTrue(!Elo.isUncertain(20))
        assertTrue(!Elo.isUncertain(200))
    }

    @Test
    fun `beating a stronger opponent gains more than beating an equal one`() {
        val gainAgainst = { opponentRating: Double ->
            val (challenger, _) = Elo.update(
                ratingA = 1000.0,
                ratingB = opponentRating,
                scoreA = 1.0,
                k = 32.0,
            )
            challenger - 1000.0
        }

        val gainVsEqual = gainAgainst(1000.0)
        val gainVsStrong = gainAgainst(1400.0)

        assertEquals(16.0, gainVsEqual, TOLERANCE)
        assertTrue(
            "beating a 1400 should gain more than beating a 1000 " +
                "($gainVsStrong vs $gainVsEqual)",
            gainVsStrong > gainVsEqual,
        )
        assertEquals(32.0 * (1.0 - 1.0 / 11.0), gainVsStrong, TOLERANCE)
    }

    @Test
    fun `percentile counts the models rated below`() {
        val field = listOf(800.0, 1000.0, 1200.0)

        assertEquals(33, Elo.percentile(1000.0, field))
        assertEquals(66, Elo.percentile(1200.0, field))
        assertEquals(0, Elo.percentile(800.0, field))
        assertEquals(50, Elo.percentile(1000.0, emptyList()))
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
