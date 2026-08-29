package com.waiterapp.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Хранит accessToken, autoLoginToken и staffId в зашифрованном виде на
 * устройстве. autoLoginToken используется для авто-логина при повторном
 * запуске приложения, пока не прошло 24 часа с момента сохранения
 * (см. TokenStore.SessionState и checkSession()).
 */
class TokenManager(context: Context) : TokenStore {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "waiter_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun saveSession(accessToken: String, autoLoginToken: String, staffId: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_AUTO_LOGIN_TOKEN, autoLoginToken)
            .putLong(KEY_STAFF_ID, staffId)
            .putLong(KEY_SESSION_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    override fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    override fun getAutoLoginToken(): String? = prefs.getString(KEY_AUTO_LOGIN_TOKEN, null)

    override fun getSessionStartedAt(): Long? {
        val value = prefs.getLong(KEY_SESSION_SAVED_AT, 0L)
        return value.takeIf { it > 0L }
    }

    override fun updateAccessToken(accessToken: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken).apply()
    }

    override fun getStaffId(): Long? {
        val id = prefs.getLong(KEY_STAFF_ID, -1L)
        return if (id == -1L) null else id
    }

    override fun isLoggedIn(): Boolean = checkSession() == TokenStore.SessionState.LOGGED_IN

    override fun checkSession(): TokenStore.SessionState {
        val autoLoginToken = getAutoLoginToken() ?: return TokenStore.SessionState.LOGGED_OUT
        val savedAt = prefs.getLong(KEY_SESSION_SAVED_AT, 0L)
        val elapsedMillis = System.currentTimeMillis() - savedAt

        return if (autoLoginToken.isNotBlank() && elapsedMillis in 0 until SESSION_TTL_MILLIS) {
            TokenStore.SessionState.LOGGED_IN
        } else {
            // 24 часа истекли (или дата сохранения повреждена/в будущем) -
            // чистим сессию сразу, чтобы не осталось "полу-залогиненного" состояния.
            clearSession()
            TokenStore.SessionState.EXPIRED
        }
    }

    override fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_AUTO_LOGIN_TOKEN)
            .remove(KEY_STAFF_ID)
            .remove(KEY_SESSION_SAVED_AT)
            .remove(KEY_STAFF_NAME)
            .apply()
    }

    override fun saveStaffName(name: String) {
        prefs.edit().putString(KEY_STAFF_NAME, name).apply()
    }

    override fun getStaffName(): String? = prefs.getString(KEY_STAFF_NAME, null)

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_AUTO_LOGIN_TOKEN = "auto_login_token"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_STAFF_NAME = "staff_name"
        private const val KEY_SESSION_SAVED_AT = "session_saved_at"

        /** Окно авто-входа: 24 часа с момента получения токенов при логине. */
        private const val SESSION_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
