package top.hsyscn.opedrgent.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "onboarding_settings")

object OnboardingDataStore {
    private val KEY_COMPLETED = booleanPreferencesKey("onboarding_completed")

    fun isCompleted(context: Context): Flow<Boolean> {
        return context.onboardingDataStore.data.map { it[KEY_COMPLETED] ?: false }
    }

    suspend fun markCompleted(context: Context) {
        context.onboardingDataStore.edit { it[KEY_COMPLETED] = true }
    }

    suspend fun reset(context: Context) {
        context.onboardingDataStore.edit { it[KEY_COMPLETED] = false }
    }
}
