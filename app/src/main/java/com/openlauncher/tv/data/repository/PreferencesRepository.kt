package com.openlauncher.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

val Context.dataStore by preferencesDataStore(name = "launcher_preferences")

class PreferencesRepository(private val context: Context) {

    companion object {
        val KEY_PIN_HASH = stringPreferencesKey("parental_pin_hash")
        val KEY_PIN_SALT = stringPreferencesKey("parental_pin_salt")
        val KEY_KIDS_MODE_ENABLED = booleanPreferencesKey("kids_mode_enabled")
        val KEY_SINGLE_LIMIT_MINUTES = intPreferencesKey("single_limit_minutes")
        val KEY_DAILY_LIMIT_MINUTES = intPreferencesKey("daily_limit_minutes")
        val KEY_REMAINING_SECONDS = intPreferencesKey("remaining_seconds")
        val KEY_LAST_TRACK_DATE = stringPreferencesKey("last_track_date")
    }

    val isKidsModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_KIDS_MODE_ENABLED] ?: false
    }

    val singleLimitMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_SINGLE_LIMIT_MINUTES] ?: 45 // 默认单次45分钟
    }

    val dailyLimitMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_DAILY_LIMIT_MINUTES] ?: 120 // 默认每日最多2小时
    }

    val remainingSeconds: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_REMAINING_SECONDS] ?: (45 * 60)
    }

    val hasPinSet: Flow<Boolean> = context.dataStore.data.map { preferences ->
        !preferences[KEY_PIN_HASH].isNullOrEmpty()
    }

    suspend fun setKidsModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_KIDS_MODE_ENABLED] = enabled }
    }

    suspend fun setSingleLimitMinutes(minutes: Int) {
        context.dataStore.edit {
            it[KEY_SINGLE_LIMIT_MINUTES] = minutes
            it[KEY_REMAINING_SECONDS] = minutes * 60
        }
    }

    suspend fun setDailyLimitMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_DAILY_LIMIT_MINUTES] = minutes }
    }

    suspend fun updateRemainingSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_REMAINING_SECONDS] = seconds }
    }

    suspend fun setPin(pin: String) {
        val salt = System.currentTimeMillis().toString()
        val hash = hashPin(pin, salt)
        context.dataStore.edit {
            it[KEY_PIN_SALT] = salt
            it[KEY_PIN_HASH] = hash
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        var isValid = false
        context.dataStore.edit { preferences ->
            val storedHash = preferences[KEY_PIN_HASH] ?: ""
            val storedSalt = preferences[KEY_PIN_SALT] ?: ""
            if (storedHash.isNotEmpty() && storedSalt.isNotEmpty()) {
                val inputHash = hashPin(pin, storedSalt)
                isValid = (inputHash == storedHash)
            }
        }
        return isValid
    }

    private fun hashPin(pin: String, salt: String): String {
        val combined = "$pin:$salt"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(combined.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
