package com.waiterapp.viewmodel

import com.waiterapp.data.repository.AuthRepository
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import org.junit.Assert.*
import org.junit.Test

class ProfileViewModelTest {

    @Test
    fun `staffId reflects the currently logged in waiter`() {
        val tokenStore = FakeTokenStore().apply { saveSession(accessToken = "access", autoLoginToken = "auto", staffId = 42L) }
        val viewModel = ProfileViewModel(AuthRepository(FakeWaiterApiService(), tokenStore))

        assertEquals(42L, viewModel.staffId)
    }

    @Test
    fun `staffId is null when there is no session`() {
        val viewModel = ProfileViewModel(AuthRepository(FakeWaiterApiService(), FakeTokenStore()))

        assertNull(viewModel.staffId)
    }

    @Test
    fun `nickname reflects the value saved at login`() {
        val tokenStore = FakeTokenStore().apply {
            saveSession(accessToken = "access", autoLoginToken = "auto", staffId = 42L)
            saveStaffName("Иван")
        }
        val viewModel = ProfileViewModel(AuthRepository(FakeWaiterApiService(), tokenStore))

        assertEquals("Иван", viewModel.nickname)
    }

    @Test
    fun `logout clears the session`() {
        val tokenStore = FakeTokenStore().apply {
            saveSession(accessToken = "access", autoLoginToken = "auto", staffId = 42L)
            saveStaffName("Иван")
        }
        val viewModel = ProfileViewModel(AuthRepository(FakeWaiterApiService(), tokenStore))

        viewModel.logout()

        assertNull(viewModel.staffId)
        assertNull(viewModel.nickname)
        assertFalse(tokenStore.isLoggedIn())
    }
}
