package com.waiterapp.data.repository

import com.waiterapp.data.local.TokenStore
import com.waiterapp.data.model.AuthAutoRequest
import com.waiterapp.data.model.CreateWaiterRequest
import com.waiterapp.data.model.LoginRequest
import com.waiterapp.data.network.WaiterApiService
import com.waiterapp.data.network.SessionEvents
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Результат сетевого запроса: либо успех с данными, либо ошибка с сообщением.
 * Используется во всех репозиториях, чтобы UI мог единообразно показывать
 * Toast/Snackbar.
 *
 * fromCache = true означает, что показанные данные не свежие, а взяты
 * из локального офлайн-кэша (см. MenuRepository), пока сети нет.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T, val fromCache: Boolean = false) : ApiResult<T>()
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
}

class AuthRepository(
    private val api: WaiterApiService,
    private val tokenManager: TokenStore,
) {
    private val refreshMutex = Mutex()

    // 24 часа — максимальная длительность сохранённой сессии.
    // Это НЕ срок жизни accessToken: accessToken обновляется только
    // по фактическому HTTP 401 от защищённого API.
    private companion object {
        const val SESSION_TTL_MILLIS = 24L * 60 * 60 * 1000
    }


    // Доступно только ADMIN на бэкенде (см. SecurityConfig) - обычный
    // официант получит 403. Оставлено для админского флоу приложения, если он есть.
    suspend fun register(name: String, password: String, role: String = "WAITER"): ApiResult<Long> {
        return when (
            val result = safeApiCall(treatUnauthorizedAsSessionExpiry = false) {
                api.createWaiter(CreateWaiterRequest(name, password, role))
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.data.staffId)
            is ApiResult.Error -> result
        }
    }

    /**
     * Вход по staffId + паролю.
     * Сервер (POST api/v1/worker/auth/logIn) отдаёт:
     *  - accessToken - JWT для каждого запроса (Bearer), его срок определяется бэкендом;
     *  - token       - refresh-токен для авто-входа, живёт 24 часа.
     * Имя официанта логин НЕ возвращает - его отдельно достаём через
     * api/v1/worker/auth/auto, единственный эндпоинт, который его знает.
     */
    suspend fun login(staffId: Long, password: String): ApiResult<Unit> {
        val loginResult = safeApiCall(treatUnauthorizedAsSessionExpiry = false) {
            api.login(LoginRequest(staffId, password))
        }

        return when (loginResult) {
            is ApiResult.Success -> {
                tokenManager.saveSession(
                    accessToken = loginResult.data.accessToken,
                    autoLoginToken = loginResult.data.token,
                    staffId = staffId
                )
                // Имя официанта не критично для входа - если запрос не удался
                // (сервер временно недоступен и т.п.), вход всё равно успешен,
                // просто без имени (профиль покажет только staffId).
                fetchAndSaveNickname(loginResult.data.token)
                                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> loginResult
        }
    }

    private suspend fun fetchAndSaveNickname(refreshToken: String) {
        when (val result = safeApiCall(treatUnauthorizedAsSessionExpiry = false) { api.authAuto(AuthAutoRequest(refreshToken)) }) {
            is ApiResult.Success -> tokenManager.saveStaffName(result.data.waiterName)
            is ApiResult.Error -> Unit // не критично - профиль просто останется без имени
        }
    }

    fun isLoggedIn(): Boolean = tokenManager.isLoggedIn()

    /**
     * Есть ли вообще сохранённый refresh/autoLogin token - НЕ смотрит на
     * локальный 24-часовой таймер (см. TokenStore.checkSession), только на
     * факт наличия токена. Используется стартовой проверкой авторизации
     * (см. AppNavHost/Screen.Splash), чтобы решить, стоит ли вообще
     * пытаться POST /auth/auto: окончательное решение "авторизован или
     * нет" в любом случае принимает сервер через tryAutoLogin(), а не
     * этот локальный таймер.
     */
    fun hasStoredAutoLoginToken(): Boolean = !tokenManager.getAutoLoginToken().isNullOrBlank()

    /**
     * Проверка сессии при старте приложения (см. TokenStore.SessionState):
     * LOGGED_IN - авто-вход возможен (не прошло 24ч с момента логина);
     * EXPIRED   - сессия только что автоматически очищена (была, но истекла);
     * LOGGED_OUT- сохранённой сессии не было вовсе.
     *
     * ВАЖНО: локальный таймер на 24ч - это лишь клиентская эвристика.
     * Настоящая проверка живости refresh-токена - вызов api/v1/worker/auth/auto;
     * если сервер вернёт ошибку раньше 24ч (например, токен отозван),
     * SafeApiCall сам почистит сессию и оповестит SessionEvents.
     */
    fun checkSession(): TokenStore.SessionState = tokenManager.checkSession()

    /**
     * Пытается обновить accessToken по сохранённому refresh-токену -
     * вызывать при старте приложения, если checkSession() == LOGGED_IN.
     * Получает свежий accessToken через /auth/auto; это не связано
     * с фиксированным 30-минутным таймером.
     */
    suspend fun tryAutoLogin(): ApiResult<Unit> {
        val refreshToken = tokenManager.getAutoLoginToken()
            ?: return ApiResult.Error(-1, "Сессия не найдена, войдите заново")

        val startedAt = tokenManager.getSessionStartedAt()
        if (startedAt == null || System.currentTimeMillis() - startedAt >= SESSION_TTL_MILLIS) {
            tokenManager.clearSession()
                        return ApiResult.Error(401, "Срок 24-часовой сессии истёк. Войдите снова.")
        }

        return when (
            val result = safeApiCall(treatUnauthorizedAsSessionExpiry = false) {
                api.authAuto(AuthAutoRequest(refreshToken))
            }
        ) {
            is ApiResult.Success -> {
                tokenManager.updateAccessToken(result.data.accessToken)
                tokenManager.saveStaffName(result.data.waiterName)
                // sessionStartedAt остаётся от первоначального ручного login.
                                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                if (result.code == 401) {
                    tokenManager.clearSession()
                                    }
                result
            }
        }
    }

    /**
     * Обновляет только accessToken. sessionStartedAt и autoLoginToken не меняются.
     * Mutex гарантирует, что параллельные 401 выполнят только один /auth/auto.
     */
    suspend fun refreshAccessToken(): ApiResult<Unit> = refreshAccessTokenIfNeeded(
        failedAccessToken = tokenManager.getAccessToken()
    )

    suspend fun refreshAccessTokenIfNeeded(failedAccessToken: String?): ApiResult<Unit> {
        return refreshMutex.withLock {
            val refreshToken = tokenManager.getAutoLoginToken()
                ?: return@withLock ApiResult.Error(401, "Сессия истекла. Войдите снова.")

            val startedAt = tokenManager.getSessionStartedAt()
            if (startedAt == null || System.currentTimeMillis() - startedAt >= SESSION_TTL_MILLIS) {
                tokenManager.clearSession()
                                SessionEvents.notifySessionExpired()
                return@withLock ApiResult.Error(401, "Сессия истекла. Войдите снова.")
            }

            // Если другой запрос уже успел обновить токен, повторный refresh не нужен.
            if (failedAccessToken != null && tokenManager.getAccessToken() != failedAccessToken) {
                return@withLock ApiResult.Success(Unit)
            }

            when (val result = safeApiCall(treatUnauthorizedAsSessionExpiry = false) {
                api.authAuto(AuthAutoRequest(refreshToken))
            }) {
                is ApiResult.Success -> {
                    tokenManager.updateAccessToken(result.data.accessToken)
                    tokenManager.saveStaffName(result.data.waiterName)
                    ApiResult.Success(Unit)
                }
                is ApiResult.Error -> {
                    if (result.code == 401) {
                        tokenManager.clearSession()
                                                SessionEvents.notifySessionExpired()
                    }
                    result
                }
            }
        }
    }

    fun logout() {
                tokenManager.clearSession()
    }

    fun getStaffId(): Long? = tokenManager.getStaffId()

    fun getNickname(): String? = tokenManager.getStaffName()

    /**
     * ВРЕМЕННО: сохраняет тестовую сессию без реального сервера.
     * Используется только когда ApiConfig.MOCK_LOGIN_ENABLED = true.
     */
    fun saveMockSession(staffId: Long) {
        tokenManager.saveSession(
            accessToken = "mock-access-token",
            autoLoginToken = "mock-auto-login-token",
            staffId = staffId
        )
        tokenManager.saveStaffName("Официант #$staffId")
    }

    /**
     * ВРЕМЕННО: подставляет уже готовый (настоящий) accessToken напрямую в
     * хранилище, минуя запрос /logIn. В отличие от saveMockSession, этот
     * токен реальный - им подписаны настоящие запросы к бэкенду, поэтому
     * Столы/Меню/Заказы/WebSocket будут работать, пока токен не истечёт
     * его срок определяется бэкендом. autoLoginToken тут заглушка - авто-обновление
     * (tryAutoLogin) с ним не сработает, но это сейчас нигде и не вызывается.
     */
    fun injectDevAccessToken(accessToken: String, staffId: Long) {
        tokenManager.saveSession(
            accessToken = accessToken,
            autoLoginToken = "dev-injected-token",
            staffId = staffId
        )
        tokenManager.saveStaffName("Официант #$staffId")
    }

    /**
     * ВРЕМЕННО: вход по уже готовому refresh-токену (например, полученному
     * от бэкенд-разработчика через Swagger), без ввода staffId/пароля.
     * В отличие от injectDevAccessToken, тут реально идёт сетевой запрос -
     * сервер (api/v1/worker/auth/auto) сам проверит токен и вернёт СВЕЖИЙ
     * accessToken, staffId и имя официанта.
     */
    suspend fun loginWithRefreshToken(refreshToken: String): ApiResult<Unit> {
        return when (
            val result = safeApiCall(treatUnauthorizedAsSessionExpiry = false) {
                api.authAuto(AuthAutoRequest(refreshToken))
            }
        ) {
            is ApiResult.Success -> {
                tokenManager.saveSession(
                    accessToken = result.data.accessToken,
                    autoLoginToken = refreshToken,
                    staffId = result.data.staffId
                )
                tokenManager.saveStaffName(result.data.waiterName)
                                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> result
        }
    }
}
