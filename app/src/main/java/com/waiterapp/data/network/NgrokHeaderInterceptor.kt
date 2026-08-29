package com.waiterapp.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Бесплатный тариф ngrok показывает межстраничную HTML-заглушку
 * ("You are about to visit...") на КАЖДЫЙ запрос через туннель, включая
 * обычные API-вызовы не из браузера - Retrofit получил бы HTML-страницу
 * вместо ожидаемого JSON и упал бы с ошибкой парсинга.
 *
 * Заголовок "ngrok-skip-browser-warning" отключает эту заглушку. Значение
 * заголовка ngrok не проверяет - важно только само его наличие.
 * Добавляем его всегда: если сейчас BASE_URL не ngrok-адрес (обычный
 * http://<локальный-ip>:8081/), лишний заголовок бэкенд просто проигнорирует.
 */
class NgrokHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val requestWithHeader = chain.request().newBuilder()
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        return chain.proceed(requestWithHeader)
    }
}
