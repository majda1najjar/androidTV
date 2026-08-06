package com.xtreamtv.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Persists the Xtream provider URL + credentials in EncryptedSharedPreferences.
 * When credentials exist, the app boots straight to the dashboard.
 */
class SessionManager(context: Context) {

    private val appContext = context.applicationContext
    private var usingFallback = false

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        absorbFallback(encrypted)
        encrypted
    } catch (e: Exception) {
        // Some TV boxes ship broken Keystore implementations; a plain-prefs
        // fallback beats a crash loop on boot.
        usingFallback = true
        fallbackPrefs()
    }

    // Keystore failures are often transient. If an earlier launch fell back to
    // plain prefs, migrate those credentials into the encrypted store and wipe
    // the plain copy. Fallback credentials always win: they can only exist from
    // a broken-Keystore launch that forced a fresh login, so they are strictly
    // newer than whatever the encrypted store holds.
    private fun absorbFallback(encrypted: SharedPreferences) {
        val fallback = fallbackPrefs()
        if (fallback.all.isEmpty()) return
        encrypted.edit()
            .putString(KEY_URL, fallback.getString(KEY_URL, null))
            .putString(KEY_USER, fallback.getString(KEY_USER, null))
            .putString(KEY_PASS, fallback.getString(KEY_PASS, null))
            .apply()
        fallback.edit().clear().apply()
    }

    private fun fallbackPrefs(): SharedPreferences =
        appContext.getSharedPreferences("${PREFS_FILE}_fallback", Context.MODE_PRIVATE)

    val serverUrl: String? get() = prefs.getString(KEY_URL, null)
    val username: String? get() = prefs.getString(KEY_USER, null)
    val password: String? get() = prefs.getString(KEY_PASS, null)

    fun isLoggedIn(): Boolean =
        !serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()

    fun save(serverUrl: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_URL, serverUrl.trim().trimEnd('/'))
            .putString(KEY_USER, username.trim())
            .putString(KEY_PASS, password)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        fallbackPrefs().edit().clear().apply()
        // When this launch is running on the fallback, the encrypted file is
        // unreachable through `prefs` and would resurrect the old session once
        // the Keystore recovers — delete the file itself.
        if (usingFallback) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appContext.deleteSharedPreferences(PREFS_FILE)
            } else {
                File(
                    File(appContext.applicationInfo.dataDir, "shared_prefs"),
                    "$PREFS_FILE.xml"
                ).delete()
            }
        }
    }

    companion object {
        private const val PREFS_FILE = "xtream_session"
        private const val KEY_URL = "server_url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
    }
}
