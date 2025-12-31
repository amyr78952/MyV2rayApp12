package com.example.myv2rayapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val AUTH_PREFS_NAME = "auth_prefs"
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = AUTH_PREFS_NAME)

class TokenDataStore(private val context: Context) {

    companion object {
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
    }

    suspend fun saveTokens(accessToken: String?, refreshToken: String? = null) {
        context.dataStore.edit { preferences ->
            if (accessToken != null) {
                preferences[ACCESS_TOKEN_KEY] = accessToken
            } else {
                preferences.remove(ACCESS_TOKEN_KEY)
            }
            if (refreshToken != null) {
                preferences[REFRESH_TOKEN_KEY] = refreshToken
            } else {
                preferences.remove(REFRESH_TOKEN_KEY)
            }
        }
    }

    val accessToken: Flow<String?> = context.dataStore.data.map { it[ACCESS_TOKEN_KEY] }

    val refreshToken: Flow<String?> = context.dataStore.data.map { it[REFRESH_TOKEN_KEY] }

    suspend fun clearTokens() {
        context.dataStore.edit { preferences ->
            preferences.remove(ACCESS_TOKEN_KEY)
            preferences.remove(REFRESH_TOKEN_KEY)
        }
    }
}