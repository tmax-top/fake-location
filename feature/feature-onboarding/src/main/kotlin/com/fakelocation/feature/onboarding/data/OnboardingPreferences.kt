package com.fakelocation.feature.onboarding.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引导页完成状态持久化。
 */
@Singleton
class OnboardingPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.onboardingDataStore

    /**
     * 引导完成状态。
     */
    val isCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_COMPLETED] ?: false
    }

    suspend fun setCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_COMPLETED] = completed }
    }

    private companion object {
        const val DATASTORE_NAME = "onboarding_prefs"
        val KEY_COMPLETED = booleanPreferencesKey("completed")
    }
}

private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onboarding_prefs"
)
