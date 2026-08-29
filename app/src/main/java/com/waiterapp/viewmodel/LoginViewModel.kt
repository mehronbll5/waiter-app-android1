package com.waiterapp.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waiterapp.data.network.ApiConfig
import com.waiterapp.data.repository.ApiResult
import com.waiterapp.data.repository.AuthRepository
import kotlinx.coroutines.launch

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

    companion object {
        // Флаг вынесен в ApiConfig.MOCK_LOGIN_ENABLED - там единое место
        // для всех настроек подключения к серверу.
        val MOCK_LOGIN_ENABLED get() = ApiConfig.MOCK_LOGIN_ENABLED
    }

    // Ника на экране входа больше нет - официант вводит только ID и пароль.
    // Имя официанта сервер возвращает сам (см. AuthRepository.login -> api/worker/auth/auto/).
    var staffIdInput by mutableStateOf("")
        private set
    var passwordInput by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var loginSuccess by mutableStateOf(false)
        private set

    fun onStaffIdChange(value: String) {
        // staffId всегда ровно 9 цифр - не даём ввести больше.
        staffIdInput = value.filter { it.isDigit() }.take(9)
        errorMessage = null
    }

    fun onPasswordChange(value: String) {
        passwordInput = value
        errorMessage = null
    }

    fun login() {
        if (staffIdInput.length != 9) {
            errorMessage = "ID официанта должен содержать ровно 9 цифр"
            return
        }
        val staffId = staffIdInput.toLongOrNull()
        if (staffId == null) {
            errorMessage = "Введите корректный ID официанта (только цифры)"
            return
        }
        if (passwordInput.isBlank()) {
            errorMessage = "Введите пароль"
            return
        }

        if (MOCK_LOGIN_ENABLED) {
            // Тестовый вход без сервера: сохраняем staffId локально и пускаем дальше.
            authRepository.saveMockSession(staffId)
            loginSuccess = true
            return
        }

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            when (val result = authRepository.login(staffId, passwordInput)) {
                is ApiResult.Success -> {
                    isLoading = false
                    loginSuccess = true
                }
                is ApiResult.Error -> {
                    isLoading = false
                    errorMessage = result.message
                }
            }
        }
    }

    /**
     * ВРЕМЕННО: автоматически подставляет тестовые staffId/пароль и сразу
     * логинится на настоящий сервер (см. ApiConfig.DEV_AUTO_LOGIN_ENABLED).
     * Вызывается из AppNavHost сразу при открытии экрана входа, так что
     * пользователь его фактически не видит (кроме краткого лоадера, пока
     * идёт настоящий сетевой запрос).
     */
    fun devAutoLogin() {
        if (!ApiConfig.DEV_AUTO_LOGIN_ENABLED || isLoading) return
        onStaffIdChange(ApiConfig.DEV_STAFF_ID.toString())
        onPasswordChange(ApiConfig.DEV_PASSWORD)
        login()
    }

    /**
     * ВРЕМЕННО: вход по уже готовому refresh-токену вместо staffId/пароля
     * (см. ApiConfig.DEV_REFRESH_TOKEN_ENABLED). Реальный сетевой запрос
     * на /api/v1/worker/auth/auto - сервер сам проверит токен.
     */
    fun devAutoLoginWithRefreshToken() {
        if (!ApiConfig.DEV_REFRESH_TOKEN_ENABLED || isLoading) return
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            when (val result = authRepository.loginWithRefreshToken(ApiConfig.DEV_REFRESH_TOKEN)) {
                is ApiResult.Success -> {
                    isLoading = false
                    loginSuccess = true
                }
                is ApiResult.Error -> {
                    isLoading = false
                    errorMessage = result.message
                }
            }
        }
    }

    /**
     * Вызывается из AppNavHost, когда официант оказался на экране входа
     * из-за того, что 24-часовое окно авто-входа истекло (а не потому,
     * что он просто ни разу не логинился).
     */
    fun showSessionExpiredMessage() {
        errorMessage = "Срок сессии истёк. Введите staffId и пароль заново."
    }
}
