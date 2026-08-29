package com.waiterapp.viewmodel

import com.waiterapp.MainDispatcherRule
import com.waiterapp.data.local.TokenStore
import com.waiterapp.data.model.LocalOrderStatus
import com.waiterapp.data.model.MenuItem
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.data.repository.AuthRepository
import com.waiterapp.data.repository.LocalOrderRepository
import com.waiterapp.fakes.FakeLocalOrderDao
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * ВАЖНО: submitOrder() ВСЕГДА сначала сохраняет заказ в локальный блокнот
 * (LocalOrderRepository), а уже потом best-effort пытается отправить его
 * на сервер. Поскольку в этих тестах сервер ничего не настроен, попытка
 * отправки будет неудачной, но это не мешает подтверждению того, что
 * заказ уже в безопасности локально (orderCreatedSuccessfully = true).
 */
class OrderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: OrderViewModel
    private lateinit var tokenStore: TokenStore
    private lateinit var localOrderDao: FakeLocalOrderDao

    private val pizza = MenuItem(id = 1, name = "Пицца Маргарита", price = 45.0, quantity = 100.0)
    private val cola = MenuItem(id = 2, name = "Кола 0.5l", price = 10.0, quantity = 50.0)

    @Before
    fun setUp() {
        tokenStore = FakeTokenStore().apply { saveSession(accessToken = "access", autoLoginToken = "auto", staffId = 1L) }
        localOrderDao = FakeLocalOrderDao()
        val authRepository = AuthRepository(FakeWaiterApiService(), tokenStore)
        val localOrderRepository = LocalOrderRepository(
            localOrderDao,
            FakeWaiterApiService(),
            tokenStore,
            StompWebSocketClient("ws://localhost/ws/websocket", tokenStore)
        )
        viewModel = OrderViewModel(localOrderRepository, authRepository)
    }

    @Test
    fun `adding new item creates a cart line with quantity 1`() {
        viewModel.addToCart(menuId = 1, menuItem = pizza)

        assertEquals(1, viewModel.cart.size)
        assertEquals(1, viewModel.cart.first().quantity)
    }

    @Test
    fun `adding the same item twice increments quantity instead of duplicating`() {
        viewModel.addToCart(menuId = 1, menuItem = pizza)
        viewModel.addToCart(menuId = 1, menuItem = pizza)

        assertEquals(1, viewModel.cart.size)
        assertEquals(2, viewModel.cart.first().quantity)
    }

    @Test
    fun `decrementing to zero removes the line from cart`() {
        viewModel.addToCart(menuId = 1, menuItem = pizza)

        viewModel.decrementQuantity(menuId = 1)

        assertTrue(viewModel.cart.isEmpty())
    }

    @Test
    fun `decrementing above one just reduces quantity`() {
        viewModel.addToCart(menuId = 1, menuItem = pizza)
        viewModel.incrementQuantity(menuId = 1)

        viewModel.decrementQuantity(menuId = 1)

        assertEquals(1, viewModel.cart.size)
        assertEquals(1, viewModel.cart.first().quantity)
    }

    @Test
    fun `total price sums price times quantity across all lines`() {
        viewModel.addToCart(menuId = 1, menuItem = pizza) // 45.0
        viewModel.addToCart(menuId = 2, menuItem = cola)  // 10.0
        viewModel.incrementQuantity(menuId = 2)           // cola x2 = 20.0

        assertEquals(65.0, viewModel.totalPrice, 0.001)
        assertEquals(3, viewModel.totalItemsCount)
    }

    @Test
    fun `submitting an empty cart shows a validation error`() {
        viewModel.submitOrder(tableNumbers = listOf("3"))

        assertNotNull(viewModel.errorMessage)
        assertFalse(viewModel.orderCreatedSuccessfully)
    }

    @Test
    fun `submitting without a session shows a session error`() {
        tokenStore.clearSession()
        viewModel.addToCart(menuId = 1, menuItem = pizza)

        viewModel.submitOrder(tableNumbers = listOf("3"))

        assertNotNull(viewModel.errorMessage)
        assertFalse(viewModel.orderCreatedSuccessfully)
    }

    @Test
    fun `successful submit clears cart and comment`() {
        viewModel.addToCart(menuId = 1, menuItem = pizza)
        viewModel.onCommentChange("без лука")

        viewModel.submitOrder(tableNumbers = listOf("3"))

        assertTrue(viewModel.orderCreatedSuccessfully)
        assertTrue(viewModel.cart.isEmpty())
        assertEquals("", viewModel.comment)
    }

    @Test
    fun `submitting saves the order locally even though there is no server to reach`() {
        viewModel.addToCart(menuId = 1, menuItem = pizza)

        viewModel.submitOrder(tableNumbers = listOf("3"))

        val saved = kotlinx.coroutines.runBlocking { localOrderDao.getAll() }
        assertEquals(1, saved.size)
        // Сервера в тесте нет, поэтому статус остаётся NOT_SENT - и это ОК,
        // заказ уже не потерян, его можно отправить повторно из "Заказов".
        assertEquals(LocalOrderStatus.NOT_SENT.name, saved.first().status)
    }
}
