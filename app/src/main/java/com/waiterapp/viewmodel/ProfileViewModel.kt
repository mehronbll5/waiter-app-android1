package com.waiterapp.viewmodel

import androidx.lifecycle.ViewModel
import com.waiterapp.data.repository.AuthRepository

/**
 * Раздел "Профиль". Пока сервер не отдаёт расширенные данные официанта
 * (имя, фото и т.п. - в документации API этого нет), показываем то,
 * что реально доступно на клиенте: ID официанта и кнопку выхода.
 * Когда бэкенд добавит отдельный эндпоинт профиля,
 * можно расширить этот ViewModel полем staffName и т.п.
 */
class ProfileViewModel(private val authRepository: AuthRepository) : ViewModel() {

    val staffId: Long?
        get() = authRepository.getStaffId()

    val nickname: String?
        get() = authRepository.getNickname()

    fun logout() {
        authRepository.logout()
    }
}
