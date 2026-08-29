package com.waiterapp.data.repository

import com.waiterapp.data.model.CreateWaiterResponse
import com.waiterapp.data.model.LoginResponse
import com.waiterapp.data.model.AuthAutoResponse
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class AuthRepositoryTest {

    private lateinit var api: FakeWaiterApiService
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        api = FakeWaiterApiService()
        tokenStore = FakeTokenStore()
        repository = AuthRepository(api, tokenStore)
    }

    @Test
    fun `login success saves session and fetches nickname from server`() = runTest {
        api.loginResult = { Response.success(LoginResponse(token = "abc123", accessToken = "jwt-abc123")) }
        api.authAutoResult = { Response.success(AuthAutoResponse(waiterName = "Иван", staffId = 7, accessToken = "jwt-abc123")) }

        val result = repository.login(staffId = 7, password = "secret")

        assertTrue(result is ApiResult.Success)
        assertEquals("jwt-abc123", tokenStore.getAccessToken())
        assertEquals("abc123", tokenStore.getAutoLoginToken())
        assertEquals(7L, tokenStore.getStaffId())
        assertEquals("Иван", tokenStore.getStaffName())
        assertTrue(tokenStore.isLoggedIn())
    }

    @Test
    fun `login success still succeeds even if fetching nickname fails`() = runTest {
        api.loginResult = { Response.success(LoginResponse(token = "abc123", accessToken = "jwt-abc123")) }
        api.authAutoResult = { Response.error(500, "{}".toResponseBody(null)) }

        val result = repository.login(staffId = 7, password = "secret")

        assertTrue(result is ApiResult.Success)
        assertTrue(tokenStore.isLoggedIn())
        assertNull(tokenStore.getStaffName())
    }

    @Test
    fun `login 401 (wrong password) is NOT treated as session expiry`() = runTest {
        // У официанта уже есть валидная сессия (например, в другой вкладке/раньше)…
        tokenStore.saveSession(accessToken = "old-access", autoLoginToken = "old-auto", staffId = 1L)
        api.loginResult = { Response.error(401, "{}".toResponseBody(null)) }

        // …он по ошибке пробует залогиниться ещё раз с неверным паролем.
        val result = repository.login(staffId = 1, password = "wrong")

        assertTrue(result is ApiResult.Error)
        result as ApiResult.Error
        assertEquals(401, result.code)
        assertEquals("Неверный ID официанта или пароль", result.message)
        // Неверный пароль при логине НЕ должен чистить существующую сессию
        // и НЕ должен показываться как "сессия истекла" (это разные вещи).
        assertEquals(0, tokenStore.clearSessionCallCount)
        assertTrue(tokenStore.isLoggedIn())
    }

    @Test
    fun `register success returns staffId from response`() = runTest {
        api.createWaiterResult = { Response.success(CreateWaiterResponse(name = "Ivan", staffId = 42)) }

        val result = repository.register("Ivan", "pass")

        assertTrue(result is ApiResult.Success)
        assertEquals(42L, (result as ApiResult.Success).data)
    }

    @Test
    fun `register 400 returns error without touching session`() = runTest {
        api.createWaiterResult = { Response.error(400, "{}".toResponseBody(null)) }

        val result = repository.register("Ivan", "pass")

        assertTrue(result is ApiResult.Error)
        assertEquals(0, tokenStore.clearSessionCallCount)
    }

    @Test
    fun `saveMockSession stores mock tokens, staffId and a placeholder nickname`() {
        repository.saveMockSession(99)

        assertTrue(repository.isLoggedIn())
        assertEquals(99L, repository.getStaffId())
        assertEquals("Официант #99", repository.getNickname())
    }


    @Test
    fun `refresh updates only access token and preserves 24 hour session`() = runTest {
        api.loginResult = { Response.success(LoginResponse(token = "R", accessToken = "A")) }
        api.authAutoResult = { Response.success(AuthAutoResponse(waiterName = "Иван", staffId = 7, accessToken = "B")) }

        repository.login(7, "secret")
        val startedAt = tokenStore.getSessionStartedAt()

        val result = repository.refreshAccessToken()

        assertTrue(result is ApiResult.Success)
        assertEquals("B", tokenStore.getAccessToken())
        assertEquals("R", tokenStore.getAutoLoginToken())
        assertEquals(7L, tokenStore.getStaffId())
        assertEquals(startedAt, tokenStore.getSessionStartedAt())
    }

    @Test
    fun `auth auto 401 clears session and emits expiration`() = runTest {
        tokenStore.saveSession("A", "R", 7)
        api.authAutoResult = { Response.error(401, "{}".toResponseBody(null)) }

        val result = repository.refreshAccessToken()

        assertTrue(result is ApiResult.Error)
        assertFalse(repository.isLoggedIn())
        assertNull(tokenStore.getAccessToken())
        assertNull(tokenStore.getAutoLoginToken())
    }

    @Test
    fun `403 does not clear session`() = runTest {
        tokenStore.saveSession("A", "R", 7)
        api.authAutoResult = { Response.error(403, "{}".toResponseBody(null)) }

        val result = repository.refreshAccessToken()

        assertTrue(result is ApiResult.Error)
        assertEquals(403, (result as ApiResult.Error).code)
        assertEquals("A", tokenStore.getAccessToken())
        assertEquals("R", tokenStore.getAutoLoginToken())
    }

    @Test
    fun `logout clears session`() {
        tokenStore.saveSession(accessToken = "access", autoLoginToken = "auto", staffId = 5)

        repository.logout()

        assertFalse(repository.isLoggedIn())
    }
}
