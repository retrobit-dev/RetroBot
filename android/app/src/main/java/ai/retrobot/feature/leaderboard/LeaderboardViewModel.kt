package ai.retrobot.feature.leaderboard

import ai.retrobot.core.arena.Elo
import ai.retrobot.data.ArenaRepository
import ai.retrobot.data.ModelEntity
import ai.retrobot.data.RatingEntity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line of the leaderboard. */
data class LeaderboardRow(
    val rank: Int,
    val modelKey: String,
    val displayName: String,
    val providerId: String,
    val rating: Double,
    val games: Int,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val percentile: Int,
    val band: Double,
    /** True when too few games have been played to trust the ordering. */
    val isUncertain: Boolean,
)

data class LeaderboardUiState(
    val rows: List<LeaderboardRow> = emptyList(),
    val totalVotes: Int = 0,
    val hasModels: Boolean = false,
    val isLoading: Boolean = true,
)

/**
 * Builds the leaderboard from the cached ratings and the model registry.
 *
 * Models that have never been voted on are shown too, so the user can see
 * what is available before casting a first vote.
 */
class LeaderboardViewModel(
    private val repository: ArenaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LeaderboardUiState())
    val state: StateFlow<LeaderboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeRatings(),
                repository.observeModels(),
            ) { ratings, models ->
                buildRows(ratings, models)
            }.collect { rows ->
                _state.update { it.copy(rows = rows, isLoading = false) }
            }
        }
        viewModelScope.launch {
            val count = repository.voteCount()
            _state.update { it.copy(totalVotes = count) }
        }
    }

    private fun buildRows(
        ratings: List<RatingEntity>,
        models: List<ModelEntity>,
    ): List<LeaderboardRow> {
        val modelByKey = models.associateBy(ModelEntity::key)
        val ratingByKey = ratings.associateBy(RatingEntity::modelKey)
        val allRatings = ratings.map(RatingEntity::rating)

        // Models with a rating first, then the rest, both alphabetically.
        val keys = (ratingByKey.keys + models.map(ModelEntity::key))
            .distinct()
            .sortedWith(
                compareByDescending<String> { ratingByKey[it]?.rating ?: -1.0 }
                    .thenBy { modelByKey[it]?.displayName.orEmpty() },
            )

        return keys.mapIndexed { index, key ->
            val rating = ratingByKey[key]
            val model = modelByKey[key]
            val games = rating?.games ?: 0
            LeaderboardRow(
                rank = index + 1,
                modelKey = key,
                displayName = model?.displayName ?: key.substringAfter("::"),
                providerId = model?.providerId ?: key.substringBefore("::"),
                rating = rating?.rating ?: Elo.DEFAULT_RATING,
                games = games,
                wins = rating?.wins ?: 0,
                losses = rating?.losses ?: 0,
                ties = rating?.ties ?: 0,
                percentile = Elo.percentile(rating?.rating ?: Elo.DEFAULT_RATING, allRatings),
                band = Elo.confidenceBand(games),
                isUncertain = Elo.isUncertain(games),
            )
        }
    }

    /** Recompute every rating from the vote log. */
    fun recompute() {
        viewModelScope.launch { repository.recomputeRatings() }
    }

    companion object {
        fun factory(repository: ArenaRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { LeaderboardViewModel(repository) }
        }
    }
}
