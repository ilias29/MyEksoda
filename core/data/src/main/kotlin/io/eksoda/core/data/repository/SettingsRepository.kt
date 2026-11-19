package io.eksoda.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.eksoda.core.data.imports.ImportNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** App preferences shown in Settings (preferences only, never data or secrets). */
interface SettingsRepository {
    /** A notification when a receipt finishes importing while the app is closed. Off by default. */
    val notifyImports: Flow<Boolean>

    suspend fun setNotifyImports(enabled: Boolean)

    /** The Insights cards the user folded, by key; all are open by default. */
    val foldedInsightsCards: Flow<Set<String>>

    /** Folds the card [key] if it is open, or opens it if it is folded. */
    suspend fun toggleInsightsCardFolded(key: String)
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(private val preferences: DataStore<Preferences>) :
    SettingsRepository {
    override val notifyImports: Flow<Boolean> =
        preferences.data.map { it[ImportNotifier.NOTIFY_IMPORTS_KEY] == true }

    override suspend fun setNotifyImports(enabled: Boolean) {
        preferences.edit { it[ImportNotifier.NOTIFY_IMPORTS_KEY] = enabled }
    }

    override val foldedInsightsCards: Flow<Set<String>> =
        preferences.data.map { it[FOLDED_INSIGHTS_CARDS_KEY].orEmpty() }

    override suspend fun toggleInsightsCardFolded(key: String) {
        // Read and written in one edit, so quick taps never undo each other.
        preferences.edit {
            val current = it[FOLDED_INSIGHTS_CARDS_KEY].orEmpty()
            it[FOLDED_INSIGHTS_CARDS_KEY] = if (key in current) current - key else current + key
        }
    }

    private companion object {
        val FOLDED_INSIGHTS_CARDS_KEY = stringSetPreferencesKey("folded_insights_cards")
    }
}
