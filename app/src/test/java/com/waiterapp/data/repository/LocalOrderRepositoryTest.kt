package com.waiterapp.data.repository

import com.waiterapp.data.model.LocalOrderItem
import com.waiterapp.data.model.LocalOrderStatus
import com.waiterapp.data.model.OrderResponse
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.fakes.FakeLocalOrderDao
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class LocalOrderRepositoryTest {

    private lateinit var dao: FakeLocalOrderDao
    private lateinit var api: FakeWaiterApiService
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var repository: LocalOrderRepository

    private val items = listOf(LocalOrderItem(menuId = 1, name = "Пицца", price = 45.0, quantity = 2))

    @Before
    fun setUp() {
        dao = FakeLocalOrderDao()
        api = FakeWaiterApiService()
        tokenStore = FakeTokenStore().apply { saveSession(accessToken = "access", autoLoginToken = "auto", staffId = 1L) }
        // StompWebSocketClient подключается лениво только при первом вызове
        // topic() (см. observeKitchenWaiterEvents) - в этих тестах он не
        // вызывается, поэтому реальный сетевой запрос по этому URL не произойдёт.
        repository = LocalOrderRepository(dao, api, tokenStore, StompWebSocketClient("ws://localhost/ws/websocket", tokenStore))
    }

    @Test
    fun `saveDraft immediately persists the order locally as NOT_SENT`() = runTest {
        val id = repository.saveDraft(
            tableNumbers = listOf("3"),
            comment = "без лука",
            items = items,
            totalPrice = 90.0
        )

        val saved = dao.getById(id)
        assertNotNull(saved)
        assertEquals(LocalOrderStatus.NOT_SENT.name, saved!!.status)
        assertEquals("3", saved.tableNumbersCsv)
        assertEquals(90.0, saved.totalPrice, 0.001)
    }

    @Test
    fun `trySend marks the order as SENT when the server accepts it`() = runTest {
        val id = repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 90.0)
        api.createOrderResult = {
            Response.success(OrderResponse(orderId = 555, totalPrice = 90.0, status = "NEW", tableNumbers = listOf("3"), comment = null, menu = emptyList()))
        }

        val result = repository.trySend(id)

        assertTrue(result is ApiResult.Success)
        val updated = dao.getById(id)!!
        assertEquals(LocalOrderStatus.SENT.name, updated.status)
        assertEquals(555L, updated.serverOrderId)
    }

    @Test
    fun `trySend keeps the order as NOT_SENT when the server is unreachable`() = runTest {
        val id = repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 90.0)
        api.createOrderResult = { throw IOException("нет сети") }

        val result = repository.trySend(id)

        assertTrue(result is ApiResult.Error)
        val updated = dao.getById(id)!!
        assertEquals(LocalOrderStatus.NOT_SENT.name, updated.status)
    }

    @Test
    fun `trySend can be retried after a previous failure and eventually succeed`() = runTest {
        val id = repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 90.0)
        api.createOrderResult = { throw IOException("нет сети") }
        repository.trySend(id)
        assertEquals(LocalOrderStatus.NOT_SENT.name, dao.getById(id)!!.status)

        // Связь появилась - повторная попытка
        api.createOrderResult = {
            Response.success(OrderResponse(orderId = 777, totalPrice = 90.0, status = "NEW", tableNumbers = listOf("3"), comment = null, menu = emptyList()))
        }
        repository.trySend(id)

        assertEquals(LocalOrderStatus.SENT.name, dao.getById(id)!!.status)
    }

    @Test
    fun `delete removes the local receipt`() = runTest {
        val id = repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 90.0)

        repository.delete(id)

        assertNull(dao.getById(id))
    }

    // --- cancelOrder (замена удаления на отмену через CANCEL API) ---

    @Test
    fun `cancelOrder deletes a draft that was never sent to the server`() = runTest {
        val id = repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 90.0)
        // Ничего не настраиваем в api.cancelOrderResult - тест докажет, что
        // для черновика без serverOrderId сеть вообще не вызывается.

        val result = repository.cancelOrder(id)

        assertTrue(result is ApiResult.Success)
        assertNull(dao.getById(id))
    }

    @Test
    fun `cancelOrder calls the backend and marks a sent order as CANCELLED without deleting it`() = runTest {
        val id = repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 90.0)
        api.createOrderResult = {
            Response.success(OrderResponse(orderId = 555, totalPrice = 90.0, status = "NEW", tableNumbers = listOf("3"), comment = null, menu = emptyList()))
        }
        repository.trySend(id)
        api.cancelOrderResult = {
            Response.success(com.waiterapp.data.model.CancelOrderResponse(orderId = 555, status = "CANCELLED"))
        }

        val result = repository.cancelOrder(id)

        assertTrue(result is ApiResult.Success)
        val updated = dao.getById(id)
        // Запись НЕ удалена физически - осталась в local_orders со статусом CANCELLED.
        assertNotNull(updated)
        assertEquals(LocalOrderStatus.CANCELLED.name, updated!!.status)
        assertEquals("CANCELLED", updated.backendStatus)
        assertEquals(555L, updated.serverOrderId)
    }

    @Test
    fun `cancelOrder leaves a sent order untouched when the backend call fails`() = runTest {
        val id = repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 90.0)
        api.createOrderResult = {
            Response.success(OrderResponse(orderId = 555, totalPrice = 90.0, status = "NEW", tableNumbers = listOf("3"), comment = null, menu = emptyList()))
        }
        repository.trySend(id)
        api.cancelOrderResult = { throw IOException("нет сети") }

        val result = repository.cancelOrder(id)

        assertTrue(result is ApiResult.Error)
        val updated = dao.getById(id)
        // Заказ не исчез и статус не поменялся на CANCELLED из-за ошибки.
        assertNotNull(updated)
        assertEquals(LocalOrderStatus.SENT.name, updated!!.status)
    }

    @Test
    fun `cancelOrder returns an error for an unknown local id`() = runTest {
        val result = repository.cancelOrder(id = 999L)

        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `trySend without a session returns an error and does not crash`() = runTest {
        val id = repository.saveDraft(tableNumbers = listOf("3"), comment = null, items = items, totalPrice = 90.0)
        tokenStore.clearSession()

        val result = repository.trySend(id)

        assertTrue(result is ApiResult.Error)
    }

    // --- getOldOrders (вкладка "Старые", см. forensic-задание, часть 9.2/10) ---

    @Test
    fun `getOldOrders without a cache dao returns an error instead of crashing`() = runTest {
        val repositoryWithoutCache = LocalOrderRepository(
            dao, api, tokenStore, StompWebSocketClient("ws://localhost/ws/websocket", tokenStore)
            // oldOrderCacheDao не передан - используется дефолтное значение null.
        )

        val result = repositoryWithoutCache.getOldOrders()

        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `getOldOrders fetches from backend and caches locally when cache is empty`() = runTest {
        val cacheDao = com.waiterapp.fakes.FakeOldOrderCacheDao()
        val repositoryWithCache = LocalOrderRepository(
            dao, api, tokenStore, StompWebSocketClient("ws://localhost/ws/websocket", tokenStore), cacheDao
        )
        api.getMyOrdersResult = {
            Response.success(
                com.waiterapp.data.model.WaiterOrdersResponse(
                    orders = listOf(com.waiterapp.data.model.OrderSummary(orderId = 1, orderNumber = "101", status = "NEW"))
                )
            )
        }

        val result = repositoryWithCache.getOldOrders()

        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.size)
        assertFalse(result.fromCache)
        // Реально записалось в локальный кэш - не только вернулось из вызова.
        assertEquals(1, cacheDao.getAll().size)
    }

    @Test
    fun `getOldOrders returns cached data without hitting the network when cache is fresh`() = runTest {
        val cacheDao = com.waiterapp.fakes.FakeOldOrderCacheDao()
        val repositoryWithCache = LocalOrderRepository(
            dao, api, tokenStore, StompWebSocketClient("ws://localhost/ws/websocket", tokenStore), cacheDao
        )
        cacheDao.replaceAll(
            listOf(
                com.waiterapp.data.local.OldOrderCacheEntity(
                    orderId = 5, orderNumber = "105", status = "NEW", cachedAtMillis = System.currentTimeMillis()
                )
            )
        )
        // Сеть намеренно не настроена (api.getMyOrdersResult бросит исключение,
        // если репозиторий всё-таки к ней обратится) - так тест доказывает,
        // что при свежем кэше сетевой вызов реально не происходит.

        val result = repositoryWithCache.getOldOrders()

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).fromCache)
        assertEquals(5L, result.data.first().orderId)
    }

    @Test
    fun `getOldOrders falls back to expired cache when the network fails`() = runTest {
        val cacheDao = com.waiterapp.fakes.FakeOldOrderCacheDao()
        val repositoryWithCache = LocalOrderRepository(
            dao, api, tokenStore, StompWebSocketClient("ws://localhost/ws/websocket", tokenStore), cacheDao
        )
        val twentyFiveHoursAgo = System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        cacheDao.replaceAll(
            listOf(com.waiterapp.data.local.OldOrderCacheEntity(orderId = 7, orderNumber = "7", status = "NEW", cachedAtMillis = twentyFiveHoursAgo))
        )
        api.getMyOrdersResult = { throw IOException("нет сети") }

        val result = repositoryWithCache.getOldOrders()

        // Кэш просрочен по TTL, но сеть недоступна - лучше показать
        // устаревшие данные, чем пустой экран (см. getOldOrders).
        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).fromCache)
        assertEquals(7L, result.data.first().orderId)
    }

    @Test
    fun `getOldOrders forceRefresh ignores a still-fresh cache and hits the network`() = runTest {
        val cacheDao = com.waiterapp.fakes.FakeOldOrderCacheDao()
        val repositoryWithCache = LocalOrderRepository(
            dao, api, tokenStore, StompWebSocketClient("ws://localhost/ws/websocket", tokenStore), cacheDao
        )
        cacheDao.replaceAll(
            listOf(com.waiterapp.data.local.OldOrderCacheEntity(orderId = 1, orderNumber = "1", status = "NEW", cachedAtMillis = System.currentTimeMillis()))
        )
        api.getMyOrdersResult = {
            Response.success(
                com.waiterapp.data.model.WaiterOrdersResponse(
                    orders = listOf(com.waiterapp.data.model.OrderSummary(orderId = 99, orderNumber = "199", status = "CANCELLED"))
                )
            )
        }

        val result = repositoryWithCache.getOldOrders(forceRefresh = true)

        assertTrue(result is ApiResult.Success)
        assertFalse((result as ApiResult.Success).fromCache)
        assertEquals(99L, result.data.first().orderId)
    }
}
