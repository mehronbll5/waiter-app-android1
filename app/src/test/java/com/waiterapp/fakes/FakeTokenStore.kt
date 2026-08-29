package com.waiterapp.fakes

import com.waiterapp.data.local.TokenStore

/**
 * Простая реализация TokenStore в памяти для юнит-тестов
 * (настоящий TokenManager требует Android Context/Keystore).
 *
 * По умолчанию окно авто-входа считается НЕ истёкшим сразу после
 * saveSession(). Чтобы в тесте сымитировать истёкшую 24-часовую сессию,
 * используйте expireSession().
 */
class FakeTokenStore : TokenStore {
    private var accessToken: String? = null
    private var autoLoginToken: String? = null
    private var staffId: Long? = null
    private var staffName: String? = null
    private var sessionExpired = false
    private var sessionStartedAt: Long? = null

    var clearSessionCallCount = 0
        private set

    override fun saveSession(accessToken: String, autoLoginToken: String, staffId: Long) {
        this.accessToken = accessToken
        this.autoLoginToken = autoLoginToken
        this.staffId = staffId
        this.sessionExpired = false
        this.sessionStartedAt = System.currentTimeMillis()
    }

    override fun getAccessToken(): String? = accessToken

    override fun getAutoLoginToken(): String? = autoLoginToken

    override fun getStaffId(): Long? = staffId

    override fun getSessionStartedAt(): Long? = sessionStartedAt

    override fun updateAccessToken(accessToken: String) {
        this.accessToken = accessToken
    }

    override fun isLoggedIn(): Boolean = checkSession() == TokenStore.SessionState.LOGGED_IN

    override fun checkSession(): TokenStore.SessionState {
        return when {
            autoLoginToken == null && !sessionExpired -> TokenStore.SessionState.LOGGED_OUT
            sessionExpired -> {
                clearSession()
                TokenStore.SessionState.EXPIRED
            }
            else -> TokenStore.SessionState.LOGGED_IN
        }
    }

    override fun clearSession() {
        clearSessionCallCount++
        accessToken = null
        autoLoginToken = null
        staffId = null
        staffName = null
        sessionStartedAt = null
    }

    override fun saveStaffName(name: String) {
        staffName = name
    }

    override fun getStaffName(): String? = staffName

    /** Хелпер для тестов: имитирует, что 24-часовое окно авто-входа истекло. */
    fun expireSession() {
        sessionExpired = true
    }
}
