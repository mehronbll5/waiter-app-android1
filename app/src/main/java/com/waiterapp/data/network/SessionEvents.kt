package com.waiterapp.data.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Глобальный канал события "сессия истекла" (сервер вернул 401 - не 403,
 * см. SafeApiCall.kt: 403 означает нехватку прав у роли при валидном
 * токене, а не истёкшую сессию, и этот канал не оповещает).
 *
 * Раньше при просроченном токене пользователь просто видел текст ошибки
 * на текущем экране и не понимал, что нужно перелогиниться. Теперь
 * AppNavHost подписан на этот поток и сам переводит на экран входа,
 * как только сервер сообщает, что токен больше не валиден.
 */
object SessionEvents {
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    fun notifySessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }
}
