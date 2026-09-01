package com.pray.booklisten.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class ReaderSettings(
    val speed: Float = 1f,
    val ttsEngine: String = "",
    val voiceName: String = "",
    val searchEngine: String = "https://cn.bing.com/search?q=",
)

class AppSettings(private val context: Context) {
    private object Keys {
        val speed = floatPreferencesKey("speed")
        val engine = stringPreferencesKey("tts_engine")
        val voice = stringPreferencesKey("voice")
        val search = stringPreferencesKey("search_engine")
    }

    val values: Flow<ReaderSettings> = context.dataStore.data.map {
        ReaderSettings(
            speed = it[Keys.speed] ?: 1f,
            ttsEngine = it[Keys.engine].orEmpty(),
            voiceName = it[Keys.voice].orEmpty(),
            searchEngine = it[Keys.search] ?: "https://cn.bing.com/search?q=",
        )
    }

    suspend fun setSpeed(value: Float) = context.dataStore.edit { it[Keys.speed] = value }
    suspend fun setEngine(value: String) = context.dataStore.edit { it[Keys.engine] = value }
    suspend fun setVoice(value: String) = context.dataStore.edit { it[Keys.voice] = value }
}
