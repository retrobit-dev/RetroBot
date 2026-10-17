package ai.retrobot.di

import ai.retrobot.core.arena.BattleEngine
import ai.retrobot.core.net.SseClient
import ai.retrobot.core.providers.ProviderFactory
import ai.retrobot.core.security.KeyVault
import ai.retrobot.core.security.SettingsStore
import ai.retrobot.data.ArenaDatabase
import ai.retrobot.data.ArenaRepository
import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * The graph is small and every dependency is a singleton, so a hand-written
 * container beats an annotation processor: the build stays fast and there is
 * nothing to misconfigure.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Streaming answers can idle for a long time between tokens.
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val sseClient: SseClient = SseClient(httpClient)

    val keyVault: KeyVault = KeyVault(appContext)

    val settingsStore: SettingsStore = SettingsStore(appContext)

    private val database: ArenaDatabase = ArenaDatabase.get(appContext)

    val providerFactory: ProviderFactory = ProviderFactory(sseClient)

    val repository: ArenaRepository = ArenaRepository(
        accountDao = database.providerAccountDao(),
        modelDao = database.modelDao(),
        voteDao = database.voteDao(),
        ratingDao = database.ratingDao(),
        keyVault = keyVault,
        providerFactory = providerFactory,
    )

    val battleEngine: BattleEngine = BattleEngine()
}
