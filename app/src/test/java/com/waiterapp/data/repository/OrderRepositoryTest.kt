package com.waiterapp.data.repository

import com.waiterapp.data.model.CreateOrderRequest
import com.waiterapp.data.model.OrderResponse
import com.waiterapp.data.model.OrderSummary
import com.waiterapp.data.model.WaiterOrdersResponse
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.net.SocketTimeoutException

class OrderRepositoryTest {

    private lateinit var api: FakeWaiterApiService
    private lateinit var repository: OrderRepository

    @Before
    fun setUp() {
        api = FakeWaiterApiService()
        repository = OrderRepository(api, FakeTokenStore())
    }

    @Test
    fun `createOrder success returns order response`() = runTest {
        val response = OrderResponse(
            orderId = 1, totalPrice = 100.0, status = "NEW",
            tableNumbers = listOf("3"), comment = null, menu = emptyList()
        )
        api.createOrderResult = { Response.success(response) }

        val result = repository.createOrder(
            CreateOrderRequest(tableNumbers = setOf(3L), items = emptyList())
        )

        assertTrue(result is ApiResult.Success)
        assertEquals(1L, (result as ApiResult.Success).data.orderId)
    }

    @Test
    fun `createOrder timeout returns understandable error message`() = runTest {
        api.createOrderResult = { throw SocketTimeoutException() }

        val result = repository.createOrder(
            CreateOrderRequest(tableNumbers = setOf(3L), items = emptyList())
        )

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("таймаут", ignoreCase = true))
    }

    @Test
    fun `getMyOrders returns list of orders for staff`() = runTest {
        api.getMyOrdersResult = {
            Response.success(WaiterOrdersResponse(orders = listOf(OrderSummary(orderId = 5, orderNumber = "105", status = "NEW"))))
        }

        val result = repository.getMyOrders()

        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.orders.size)
    }

    @Test
    fun `getMyOrders server error 500 returns generic server message`() = runTest {
        api.getMyOrdersResult = { Response.error(500, "{}".toResponseBody(null)) }

        val result = repository.getMyOrders()

        assertTrue(result is ApiResult.Error)
    }
}
