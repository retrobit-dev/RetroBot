package ai.retrobot.feature.keys

import ai.retrobot.core.providers.ProviderCatalog
import ai.retrobot.core.providers.ProviderSpec
import ai.retrobot.data.ArenaRepository
import ai.retrobot.data.ModelEntity
import ai.retrobot.data.ProviderAccountEntity
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

data class AccountRow(
    val account: ProviderAccountEntity,
    val modelCount: Int,
    val spec: ProviderSpec?,
)

data class KeysUiState(
    val accounts: List<AccountRow> = emptyList(),
    val syncingAccountId: String? = null,
    val message: String? = null,
    val error: String? = null,
    val keyVaultAvailable: Boolean = true,
)

/**
 * Manages provider accounts: adding them, and pulling in the list of models
 * each one can reach.
 */
class KeysViewModel(
    private val repository: ArenaRepository,
    private val keyVaultAvailable: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(KeysUiState(keyVaultAvailable = keyVaultAvailable))
    val state: StateFlow<KeysUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeAccounts(),
                repository.observeModels(),
            ) { accounts, models ->
                accounts.map { account ->
                    AccountRow(
                        account = account,
                        modelCount = models.count { it.accountId == account.id },
                        spec = ProviderCatalog.byId(account.providerId),
                    )
                }
            }.collect { rows ->
                _state.update { it.copy(accounts = rows) }
            }
        }
    }

    /**
     * Add a provider account.
     *
     * The key is encrypted before it touches the database.
     */
    fun addAccount(spec: ProviderSpec, label: String, baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            runCatching {
                repository.addAccount(spec, label, baseUrl, apiKey)
            }.onSuccess { id ->
                _state.update { it.copy(message = "Provider added.", error = null) }
                syncModels(id)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        error = error.message ?: "Could not add the provider.",
                        message = null,
                    )
                }
            }
        }
    }

    fun deleteAccount(id: String) {
        viewModelScope.launch {
            repository.deleteAccount(id)
            _state.update { it.copy(message = "Provider removed.", error = null) }
        }
    }

    /** Ask the provider which models it offers and store them. */
    fun syncModels(accountId: String) {
        viewModelScope.launch {
            _state.update { it.copy(syncingAccountId = accountId, error = null, message = null) }
            repository.syncModels(accountId)
                .onSuccess { count ->
                    _state.update {
                        it.copy(
                            syncingAccountId = null,
                            message = if (count == 0) {
                                "That provider returned no models."
                            } else {
                                "$count models available."
                            },
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            syncingAccountId = null,
                            error = error.message ?: "Could not reach the provider.",
                            message = null,
                        )
                    }
                }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    companion object {
        fun factory(
            repository: ArenaRepository,
            keyVaultAvailable: Boolean,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { KeysViewModel(repository, keyVaultAvailable) }
        }
    }
}
