package ai.retrobot

import android.app.Application
import ai.retrobot.di.AppContainer

/**
 * RetroBot — an Arena Agent client for Android.
 *
 * The application object owns a single [AppContainer] which holds the
 * long-lived collaborators (HTTP client, secure credential store, agent loop,
 * tool registry). Everything is created lazily so a cold start does no
 * network or disk work beyond opening the encrypted preferences.
 */
class RetroBotApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
