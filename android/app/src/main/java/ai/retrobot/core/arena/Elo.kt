package ai.retrobot.core.arena

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Elo ratings, the way an arena app needs them.
 *
 * Every model starts at [DEFAULT_RATING]. A vote moves the two models
 * towards the observed outcome, in proportion to how surprising it was.
 *
 * The confidence band is an **approximation**, not a bootstrap: the width
 * shrinks with the square root of the number of games. It is there to tell
 * the user "this model has only been tried twice", not to make a statistical
 * claim.
 */
object Elo {

    const val DEFAULT_RATING = 1000.0

    /** Scale of the rating system; 400 means a 200-point gap is a 10:1 chance. */
    private const val SCALE = 400.0

    /**
     * The K factor shrinks as a model accumulates games, so one early win
     * does not lock a rating in for good.
     */
    fun kFactor(gamesPlayed: Int): Double = when {
        gamesPlayed < 10 -> 48.0
        gamesPlayed < 30 -> 32.0
        gamesPlayed < 100 -> 24.0
        else -> 16.0
    }

    /**
     * The expected score of A against B, as a number between 0 and 1.
     */
    fun expectedScore(ratingA: Double, ratingB: Double): Double =
        1.0 / (1.0 + 10.0.pow((ratingB - ratingA) / SCALE))

    /**
     * Apply one result and return the two new ratings.
     *
     * @param scoreA 1.0 if A won, 0.0 if A lost, 0.5 for a tie.
     * @param k the K factor to apply; see [kFactor].
     */
    fun update(
        ratingA: Double,
        ratingB: Double,
        scoreA: Double,
        k: Double = kFactor(0),
    ): Pair<Double, Double> {
        val expectedA = expectedScore(ratingA, ratingB)
        val delta = k * (scoreA - expectedA)
        return (ratingA + delta) to (ratingB - delta)
    }

    /**
     * Half-width of the approximate 95% band around a rating.
     *
     * One game gives a band of about +/-200; a hundred games narrows it to
     * about +/-20.
     */
    fun confidenceBand(gamesPlayed: Int): Double {
        if (gamesPlayed <= 0) return 200.0
        return 200.0 / sqrt(gamesPlayed.toDouble())
    }

    /**
     * True when the two models are too close to call, given how few games
     * each has played. The UI uses this to explain that a ranking is noise.
     */
    fun isUncertain(gamesPlayed: Int): Boolean = gamesPlayed < MIN_GAMES_FOR_CONFIDENCE

    const val MIN_GAMES_FOR_CONFIDENCE = 20

    /**
     * The rating of a model expressed as a percentile against the field,
     * which is how arena leaderboards usually present it.
     *
     * @param ratings every rating in the field, including this one.
     */
    fun percentile(rating: Double, ratings: List<Double>): Int {
        if (ratings.isEmpty()) return 50
        val below = ratings.count { it < rating }
        return ((below.toDouble() / ratings.size) * 100).toInt().coerceIn(0, 100)
    }
}
