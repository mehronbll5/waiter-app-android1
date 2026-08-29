package com.waiterapp.viewmodel

import com.waiterapp.MainDispatcherRule
import com.waiterapp.data.model.LocalOrderItem
import com.waiterapp.data.model.OrderResponse
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.data.repository.LocalOrderRepository
import com.waiterapp.fakes.FakeLocalOrderDao
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class OrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var dao: FakeLocalOrderDao
    private lateinit var api: FakeWaiterApiService
    private lateinit var repository: LocalOrderRepository
    private lateinit var viewModel: OrdersViewModel

    private val items = listOf(LocalOrderItem(menuId = 1, name = "Пицца", price = 45.0, quantity = 1))

    @Before
    fun setUp() {
        dao = FakeLocalOrderDao()
        api = FakeWaiterApiService()
        val tokenStore = FakeTokenStore().apply { saveSession(accessToken = "access", autoLoginToken = "auto", staffId = 1L) }
        repository = LocalOrderRepository(dao, api, tokenStore, StompWebSocketClient("ws://localhost/ws/websocket", tokenStore))
        viewModel = OrdersViewModel(repository)
    }

    @Test
    fun `starts with an empty list when there are no local receipts`() {
        assertTrue(viewModel.receipts.isEmpty())
        assertFalse(viewModel.isLoading)
    }

    @Test
    fun `loadReceipts shows previously saved local orders`() {
        kotlinx.coroutines.runBlocking {
            repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 45.0)
        }

        viewModel.loadReceipts()

        assertEquals(1, viewModel.receipts.size)
    }

    @Test
    fun `retrySend attempts delivery again and refreshes the list`() {
        val id = kotlinx.coroutines.runBlocking {
            repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 45.0)
        }
        api.createOrderResult = {
            Response.success(OrderResponse(orderId = 9, totalPrice = 45.0, status = "NEW", tableNumbers = listOf("3"), comment = null, menu = emptyList()))
        }
        viewModel.loadReceipts()

        viewModel.retrySend(id)

        val updated = viewModel.receipts.first { it.id == id }
        assertEquals("SENT", updated.status)
        assertTrue(viewModel.sendingIds.isEmpty())
    }

    @Test
    fun `delete removes the receipt from the visible list`() {
        val id = kotlinx.coroutines.runBlocking {
            repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 45.0)
        }
        viewModel.loadReceipts()
        assertEquals(1, viewModel.receipts.size)

        viewModel.delete(id)

        assertTrue(viewModel.receipts.isEmpty())
    }

    @Test
    fun `retrySend keeps the receipt visible with NOT_SENT status when server is unreachable`() {
        val id = kotlinx.coroutines.runBlocking {
            repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 45.0)
        }
        api.createOrderResult = { throw IOException("нет сети") }
        viewModel.loadReceipts()

        viewModel.retrySend(id)

        val updated = viewModel.receipts.first { it.id == id }
        assertEquals("NOT_SENT", updated.status)
    }

    // --- cancelOrder (замена удаления на отмену через CANCEL API) ---

    @Test
    fun `cancelOrder marks a sent order as CANCELLED and keeps it in the list`() {
        val id = kotlinx.coroutines.runBlocking {
            repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 45.0)
        }
        api.createOrderResult = {
            Response.success(OrderResponse(orderId = 42, totalPrice = 45.0, status = "NEW", tableNumbers = listOf("3"), comment = null, menu = emptyList()))
        }
        viewModel.retrySend(id)
        api.cancelOrderResult = {
            Response.success(com.waiterapp.data.model.CancelOrderResponse(orderId = 42, status = "CANCELLED"))
        }

        viewModel.cancelOrder(id)

        val updated = viewModel.receipts.first { it.id == id }
        assertEquals("CANCELLED", updated.status)
        assertTrue(viewModel.cancellingIds.isEmpty())
        assertNull(viewModel.cancelErrorMessage)
    }

    @Test
    fun `cancelOrder keeps the order SENT and sets an error message when the backend call fails`() {
        val id = kotlinx.coroutines.runBlocking {
            repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 45.0)
        }
        api.createOrderResult = {
            Response.success(OrderResponse(orderId = 42, totalPrice = 45.0, status = "NEW", tableNumbers = listOf("3"), comment = null, menu = emptyList()))
        }
        viewModel.retrySend(id)
        api.cancelOrderResult = { throw IOException("нет сети") }

        viewModel.cancelOrder(id)

        val updated = viewModel.receipts.first { it.id == id }
        assertEquals("SENT", updated.status)
        assertNotNull(viewModel.cancelErrorMessage)
    }

    @Test
    fun `dismissCancelError clears the error message`() {
        val id = kotlinx.coroutines.runBlocking {
            repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 45.0)
        }
        api.createOrderResult = {
            Response.success(OrderResponse(orderId = 42, totalPrice = 45.0, status = "NEW", tableNumbers = listOf("3"), comment = null, menu = emptyList()))
        }
        viewModel.retrySend(id)
        api.cancelOrderResult = { throw IOException("нет сети") }
        viewModel.cancelOrder(id)
        assertNotNull(viewModel.cancelErrorMessage)

        viewModel.dismissCancelError()

        assertNull(viewModel.cancelErrorMessage)
    }
}
