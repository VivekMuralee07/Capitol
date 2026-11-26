package com.capitol.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * API keys and broker tokens, encrypted at rest with the Android Keystore.
 * Nothing in here is ever logged or sent anywhere except to the service it belongs to.
 */
class SecretStore(context: Context) {

    enum class Key {
        ANTHROPIC_API_KEY,
        KITE_API_KEY, KITE_API_SECRET, KITE_ACCESS_TOKEN, KITE_USER,
        INDMONEY_ACCESS_TOKEN, INDMONEY_BASE_URL,
    }

    private val prefs: SharedPreferences = runCatching {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "capitol_secrets", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Keystore unavailable (rare, e.g. some emulators): fall back to private prefs.
        context.getSharedPreferences("capitol_secrets_fallback", Context.MODE_PRIVATE)
    }

    private val _version = MutableStateFlow(0)
    /** Bumps whenever a secret changes, so UI can recompose. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(key: Key): String? = prefs.getString(key.name, null)?.takeIf { it.isNotBlank() }

    fun put(key: Key, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key.name) else putString(key.name, value.trim())
        }.apply()
        _version.value++
    }

    fun has(key: Key) = get(key) != null
}
