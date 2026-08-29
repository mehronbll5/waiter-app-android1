package com.waiterapp.data.repository

import com.waiterapp.data.local.TokenStore
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Единая обёртка над Retrofit-вызовом.
 *
 * Раньше в каждом Repository был свой одинаковый try/catch с текстом
 * "Ошибка сети: ${e.message}" - для пользователя это бесполезно
 * (например, e.message для обрыва соединения может быть просто "null"
 * или техническим сообщением на английском).
 *
 * Теперь:
 * - разные типы сетевых проблем (нет интернета / таймаут / сервер недоступен)
 *   получают понятный текст на русском;
 * - HTTP 401 не очищает сессию напрямую: AuthInterceptor сначала выполняет
 *   централизованный refresh/retry. Сессия очищается только если /auth/auto
 *   подтвердил окончание/невалидность 24-часового токена.
 *
 * ВАЖНО: это поведение (clearSession + "сессия истекла") подходит только
 * для запросов, которые идут С УЖЕ имеющимся accessToken (меню, столы,
 * заказы и т.п.) - там 401 действительно значит "токен не годится".
 * Для /login и /create (там сессии ещё нет вообще) передавайте
 * treatUnauthorizedAsSessionExpiry = false, иначе неверный пароль при
 * входе будет ошибочно показан как "сессия истекла".
 *
 * 401 vs 403 - это РАЗНЫЕ ситуации, и раньше они ошибочно обрабатывались
 * одинаково:
 *  - 401 Unauthorized - токен отсутствует/просрочен/невалиден. Сессию
 *    действительно нужно сбросить и отправить на логин.
 *  - 403 Forbidden - токен валиден, официант успешно авторизован, но у его
 *    РОЛИ нет прав на этот конкретный эндпоинт (например, "/api/v1/menus"
 *    и "/api/v1/tables/all" на бэкенде разрешены только роли ADMIN - см.
 *    комментарии в WaiterApiService). Это НЕ истёкшая сессия - сбрасывать
 *    валидный токен и кидать официанта на экран входа тут в корне неверно
 *    (он был бы уверен, что сессия "слетает", хотя на самом деле у его
 *    аккаунта просто не та роль). Поэтому 403 просто возвращается вызывающему
 *    коду как ошибка с понятным текстом, а сессия и SessionEvents не трогаются.
 */
suspend fun <T> safeApiCall(
    tokenManager: TokenStore? = null,
    treatUnauthorizedAsSessionExpiry: Boolean = true,
    call: suspend () -> Response<T>
): ApiResult<T> {
    // 401 уже обрабатывается централизованно в AuthInterceptor:
    // refresh -> один retry. Здесь не очищаем сессию повторно.
    return try {
        val response = call()
        val body = response.body()
        if (response.isSuccessful && body != null) {
            ApiResult.Success(body)
        } else {
            val code = response.code()
            if (code == 401 && treatUnauthorizedAsSessionExpiry) {
                // AuthInterceptor уже попытался централизованный refresh и,
                // если refresh-токен был невалиден, AuthRepository очистил
                // сессию и отправил SessionExpired. Здесь НЕ очищаем сессию
                // повторно: обычный 401 не должен сам по себе выбрасывать
                // пользователя на Login.
                ApiResult.Error(code, httpErrorMessage(code))
            } else if (code == 401) {
                // Сессии ещё не было (например, /login) - 401 значит просто
                // "неверный staffId или пароль", а не "сессия истекла".
                ApiResult.Error(code, "Неверный ID официанта или пароль")
            } else if (code == 403) {
                // Токен валиден, но роли не хватает прав на этот эндпоинт -
                // сессию НЕ трогаем и на логин не кидаем (см. доккомментарий).
                ApiResult.Error(code, "Недостаточно прав для этого действия. Обратитесь к администратору.")
            } else {
                ApiResult.Error(code, httpErrorMessage(code))
            }
        }
    } catch (e: UnknownHostException) {
        ApiResult.Error(-1, "Сервер недоступен. Проверьте адрес сервера или подключение к интернету.")
    } catch (e: SocketTimeoutException) {
        ApiResult.Error(-2, "Сервер не отвечает (таймаут). Попробуйте ещё раз.")
    } catch (e: IOException) {
        ApiResult.Error(-3, "Нет подключения к интернету.")
    } catch (e: Exception) {
        ApiResult.Error(-99, "Непредвиденная ошибка: ${e.message ?: "неизвестно"}")
    }
}

fun httpErrorMessage(code: Int): String = when (code) {
    400 -> "Неверный запрос. Проверьте введённые данные."
    401 -> "Сессия истекла. Пожалуйста, войдите снова."
    403 -> "Недостаточно прав для этого действия. Обратитесь к администратору."
    404 -> "Запрашиваемые данные не найдены."
    408 -> "Сервер не отвечает (таймаут). Попробуйте ещё раз."
    in 500..599 -> "Сервер временно недоступен. Попробуйте позже."
    else -> "Произошла ошибка (код $code). Попробуйте ещё раз."
}
