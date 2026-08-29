package com.waiterapp.data.repository

import com.waiterapp.data.model.MockTableData
import com.waiterapp.data.model.TableInfo
import com.waiterapp.data.model.TableStatus
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.fakes.FakeTableDao
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class TableRepositoryTest {

    private lateinit var api: FakeWaiterApiService
    private lateinit var tableDao: FakeTableDao
    private lateinit var repository: TableRepository

    @Before
    fun setUp() {
        api = FakeWaiterApiService()
        tableDao = FakeTableDao()
        // StompWebSocketClient подключается лениво только при первом вызове
        // topic() (см. observeTableEvents) - в этих тестах он не вызывается,
        // поэтому реальный сетевой запрос по этому URL не произойдёт.
        repository = TableRepository(
            api,
            FakeTokenStore(),
            StompWebSocketClient("ws://localhost/ws/websocket", FakeTokenStore()),
            tableDao
        )
    }

    @Test
    fun `successful load returns fresh data and updates cache`() = runTest {
        val realTables = listOf(TableInfo(id = 10, number = "T-10", status = TableStatus.NOT_RESERVED))
        api.getAllTablesResult = { Response.success(realTables) }

        val result = repository.getAllTables()

        assertTrue(result is ApiResult.Success)
        assertEquals(realTables, (result as ApiResult.Success).data)
        assertFalse(result.fromCache)
        // Кэш должен обновиться свежими данными - это и есть офлайн-сохранение
        // "последних изменений" (в т.ч. удалений - см. следующий тест).
        assertEquals(1, tableDao.getAll().size)
    }

    @Test
    fun `deleted table disappears from cache after next successful sync`() = runTest {
        // Стол №10 был, синхронизировался и осел в кэше...
        api.getAllTablesResult = { Response.success(listOf(TableInfo(id = 10, number = "T-10"))) }
        repository.getAllTables()
        assertEquals(1, tableDao.getAll().size)

        // ...затем стол удалили на сервере - следующий успешный список уже пуст.
        api.getAllTablesResult = { Response.success(emptyList()) }
        val result = repository.getAllTables()

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).data.isEmpty())
        // Кэш отразил удаление, а не сохранил старую запись.
        assertTrue(tableDao.getAll().isEmpty())
    }

    @Test
    fun `network failure with existing cache falls back to last synced tables, not hardcoded mock`() = runTest {
        val realTables = listOf(TableInfo(id = 7, number = "T-7", status = TableStatus.RESERVED))
        api.getAllTablesResult = { Response.success(realTables) }
        repository.getAllTables()

        // Теперь сервер недоступен (офлайн)
        api.getAllTablesResult = { throw IOException("нет сети") }

        val result = repository.getAllTables()

        assertTrue(result is ApiResult.Success)
        result as ApiResult.Success
        assertTrue(result.fromCache)
        // Именно реальные данные последней синхронизации, а не MockTableData.
        assertEquals(realTables, result.data)
    }

    @Test
    fun `network failure without any prior sync falls back to mock tables`() = runTest {
        // Самый первый запуск приложения без интернета - синхронизации ещё не было.
        api.getAllTablesResult = { throw IOException("нет сети") }

        val result = repository.getAllTables()

        assertTrue(result is ApiResult.Success)
        result as ApiResult.Success
        assertTrue(result.fromCache)
        assertEquals(MockTableData.sampleTables, result.data)
    }

    @Test
    fun `client error like 404 does not fall back to cache or mock`() = runTest {
        // 404 - это не проблема сети, подставлять кэш/моки не нужно,
        // это реальная ошибка запроса, которую должен увидеть пользователь.
        api.getAllTablesResult = { Response.success(listOf(TableInfo(id = 1, number = "1"))) }
        repository.getAllTables()

        api.getAllTablesResult = { Response.error(404, "{}".toResponseBody(null)) }

        val result = repository.getAllTables()

        assertTrue(result is ApiResult.Error)
    }
}
