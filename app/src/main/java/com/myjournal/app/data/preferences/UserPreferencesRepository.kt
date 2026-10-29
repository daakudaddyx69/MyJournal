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
    val userName: String = "Alex Carter",
    val userEmail: String = "alex.carter@gmail.com",
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val isPrivacyShieldActive: Boolean = false,
    val autoLockTimeout: String = "Immediate",
    val dailyReminder: Boolean = true
)
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PRIVACY_SHIELD = booleanPreferencesKey("privacy_shield")
        val AUTO_LOCK_TIMEOUT = stringPreferencesKey("auto_lock_timeout")
        val DAILY_REMINDER = booleanPreferencesKey("daily_reminder")
    }
    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: AppThemeMode.LIGHT.name
        val theme = runCatching { AppThemeMode.valueOf(themeStr) }.getOrDefault(AppThemeMode.LIGHT)
        UserSettings(
            userName = prefs[Keys.USER_NAME] ?: "Alex Carter",
            userEmail = prefs[Keys.USER_EMAIL] ?: "alex.carter@gmail.com",
            themeMode = theme,
            isPrivacyShieldActive = prefs[Keys.PRIVACY_SHIELD] ?: false,
            autoLockTimeout = prefs[Keys.AUTO_LOCK_TIMEOUT] ?: "Immediate",
            dailyReminder = prefs[Keys.DAILY_REMINDER] ?: true
        )
    }
    suspend fun updateProfile(name: String, email: String) {
        context.dataStore.edit {
            it[Keys.USER_NAME] = name
            it[Keys.USER_EMAIL] = email
        }
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