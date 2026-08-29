package com.waiterapp.data.network

import com.waiterapp.data.local.TokenStore
import okhttp3.Interceptor
import okhttp3.Response
import kotlinx.coroutines.runBlocking

/**
 * Добавляет только accessToken как Bearer.
 *
 * Если API неожиданно отвечает 401, выполняется ровно один централизованный
 * refresh по сохранённому autoLoginToken и исходный запрос повторяется один раз.
 * Конкурентные refresh синхронизируются внутри AuthRepository.
 */
class AuthInterceptor(
    private val tokenManager: TokenStore,
    private val refreshAccessToken: suspend (failedAccessToken: String?) -> Boolean
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath

        // Один 401 -> один refresh -> один повтор. Если повторный запрос
        // тоже получил 401, НЕ запускаем refresh по кругу и НЕ считаем это
        // успешным обновлением токена. Это защищает от циклических запросов.
        val isRetry = original.header(AUTH_RETRY_HEADER) == "1"

        // Login/auto-login используют свои тела. Не отправляем accessToken
        // туда вообще, чтобы autoLoginToken никогда не мог быть перепутан с Bearer.
        val isAuthEndpoint = path.endsWith("/auth/logIn") || path.endsWith("/auth/auto")
        val accessToken = tokenManager.getAccessToken()

        val request = if (!isAuthEndpoint && !accessToken.isNullOrBlank()) {
            original.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            original
        }

        val response = chain.proceed(request)
        if (response.code != 401 || isAuthEndpoint || isRetry || accessToken.isNullOrBlank()) {
            return response
        }

        val refreshed = runBlocking {
            refreshAccessToken(accessToken)
        }
        if (!refreshed) {
            // Refresh не удался: возвращаем исходный 401. AuthRepository
            // сам решает, истекла ли 24-часовая сессия.
            return response
        }

        val newAccessToken = tokenManager.getAccessToken()
        if (newAccessToken.isNullOrBlank()) {
            return response
        }

        response.close()

        val retryRequest = original.newBuilder()
            .header("Authorization", "Bearer $newAccessToken")
            .header(AUTH_RETRY_HEADER, "1")
            .build()

        return chain.proceed(retryRequest)
    }

    companion object {
        private const val AUTH_RETRY_HEADER = "X-Auth-Retry"
    }
}
