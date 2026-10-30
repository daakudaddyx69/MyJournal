package com.myjournal.app.data.preferences
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.myjournal.app.ui.viewmodel.AppThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
private val Context.dataStore by preferencesDataStore(name = "user_settings")
data class UserSettings(
    val isSettingsLoaded: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val hasSeededDefaultFolders: Boolean = false,
    val userName: String = "",
    val googleAccountEmail: String = "",
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val isPrivacyShieldActive: Boolean = false,
    val autoLockTimeout: String = "Immediate",
    val dailyReminder: Boolean = true
)
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val HAS_SEEDED_FOLDERS = booleanPreferencesKey("has_seeded_default_folders")
        val USER_NAME = stringPreferencesKey("user_name")
        val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PRIVACY_SHIELD = booleanPreferencesKey("privacy_shield")
        val AUTO_LOCK_TIMEOUT = stringPreferencesKey("auto_lock_timeout")
        val DAILY_REMINDER = booleanPreferencesKey("daily_reminder")
    }
    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: AppThemeMode.DARK.name
        val theme = runCatching { AppThemeMode.valueOf(themeStr) }.getOrDefault(AppThemeMode.DARK)
        UserSettings(
            isSettingsLoaded = true,
            hasCompletedOnboarding = prefs[Keys.HAS_COMPLETED_ONBOARDING] ?: false,
            hasSeededDefaultFolders = prefs[Keys.HAS_SEEDED_FOLDERS] ?: false,
            userName = (prefs[Keys.USER_NAME] ?: "").trim(),
            googleAccountEmail = (prefs[Keys.GOOGLE_ACCOUNT_EMAIL] ?: "").trim(),
            themeMode = theme,
            isPrivacyShieldActive = prefs[Keys.PRIVACY_SHIELD] ?: false,
            autoLockTimeout = prefs[Keys.AUTO_LOCK_TIMEOUT] ?: "Immediate",
            dailyReminder = prefs[Keys.DAILY_REMINDER] ?: true
        )
    }
    suspend fun markDefaultFoldersSeeded() {
        context.dataStore.edit {
            it[Keys.HAS_SEEDED_FOLDERS] = true
        }
    }
    suspend fun setOnboardingCompleted(name: String) {
        val cleanName = name.trim().ifBlank { "Rockstar" }
        context.dataStore.edit {
            it[Keys.HAS_COMPLETED_ONBOARDING] = true
            it[Keys.USER_NAME] = cleanName
        }
    }
    suspend fun updateProfile(name: String, email: String) {
        context.dataStore.edit {
            it[Keys.USER_NAME] = name.trim().ifBlank { "Rockstar" }
            it[Keys.GOOGLE_ACCOUNT_EMAIL] = email.trim()
        }
    }
    suspend fun setGoogleAccountEmail(email: String) {
        context.dataStore.edit { it[Keys.GOOGLE_ACCOUNT_EMAIL] = email.trim() }
    }
    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }
    suspend fun setPrivacyShield(active: Boolean) {
        context.dataStore.edit { it[Keys.PRIVACY_SHIELD] = active }
    }
    suspend fun setAutoLockTimeout(timeout: String) {
        context.dataStore.edit { it[Keys.AUTO_LOCK_TIMEOUT] = timeout }
    }
    suspend fun setDailyReminder(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DAILY_REMINDER] = enabled }
    }
}