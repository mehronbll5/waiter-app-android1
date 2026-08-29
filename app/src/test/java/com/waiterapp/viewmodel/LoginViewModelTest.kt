package com.waiterapp.viewmodel

import com.waiterapp.MainDispatcherRule
import com.waiterapp.data.model.LoginResponse
import com.waiterapp.data.model.AuthAutoResponse
import com.waiterapp.data.repository.AuthRepository
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/**
 * Экран входа теперь запрашивает только staffId (ровно 9 цифр) и пароль -
 * поля "ник" больше нет, имя официанта подтягивается с сервера отдельно
 * (см. AuthRepository.login -> api/worker/auth/auto/).
 *
 * ApiConfig.MOCK_LOGIN_ENABLED = false, поэтому login() идёт по реальной
 * сетевой ветке через FakeWaiterApiService - в тестах, которые должны
 * дойти до сети, нужно настроить api.loginResult (и обычно api.authAutoResult).
 */
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var api: FakeWaiterApiService
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        api = FakeWaiterApiService()
        val authRepository = AuthRepository(api, FakeTokenStore())
        viewModel = LoginViewModel(authRepository)
    }

    @Test
    fun `non-digit staffId input is filtered out`() {
        viewModel.onStaffIdChange("12a3b")

        assertEquals("123", viewModel.staffIdInput)
    }

    @Test
    fun `staffId input longer than 9 digits is truncated`() {
        viewModel.onStaffIdChange("1234567890123")

        assertEquals("123456789", viewModel.staffIdInput)
    }

    @Test
    fun `login with staffId shorter than 9 digits shows validation error`() {
        viewModel.onStaffIdChange("5")
        viewModel.onPasswordChange("secret")

        viewModel.login()

        assertEquals("ID официанта должен содержать ровно 9 цифр", viewModel.errorMessage)
        assertFalse(viewModel.loginSuccess)
    }

    @Test
    fun `login with blank password shows validation error`() {
        viewModel.onStaffIdChange("123456789")
        viewModel.onPasswordChange("   ")

        viewModel.login()

        assertNotNull(viewModel.errorMessage)
        assertFalse(viewModel.loginSuccess)
    }

    @Test
    fun `changing input clears previous error message`() {
        viewModel.onPasswordChange("")
        viewModel.login()
        assertNotNull(viewModel.errorMessage)

        viewModel.onStaffIdChange("5")

        assertNull(viewModel.errorMessage)
    }

    @Test
    fun `valid input logs in successfully via the real (fake) network call`() {
        api.loginResult = { Response.success(LoginResponse(token = "auto-1", accessToken = "access-1")) }
        api.authAutoResult = { Response.success(AuthAutoResponse(waiterName = "Иван", staffId = 123456789, accessToken = "access-1")) }

        viewModel.onStaffIdChange("123456789")
        viewModel.onPasswordChange("secret")

        viewModel.login()

        assertTrue(viewModel.loginSuccess)
        assertNull(viewModel.errorMessage)
    }

    @Test
    fun `wrong password shows error and does not log in`() {
        api.loginResult = { Response.error(401, "{}".toResponseBody(null)) }

        viewModel.onStaffIdChange("123456789")
        viewModel.onPasswordChange("wrong")

        viewModel.login()

        assertFalse(viewModel.loginSuccess)
        assertEquals("Неверный ID официанта или пароль", viewModel.errorMessage)
    }
}
