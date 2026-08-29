package com.waiterapp.data.repository

import com.waiterapp.data.model.CategoryInfo
import com.waiterapp.data.model.MenuItem
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.fakes.FakeMenuDao
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * По Swagger-схеме бэкенда нет эндпоинта "все блюда одним списком"
 * (GET "api/v1/menus" не существует) - MenuRepository.getAllMenuItems()
 * теперь сначала грузит категории (getAllCategories), а затем блюда КАЖДОЙ
 * категории отдельным запросом (getDishesByCategory) и склеивает их,
 * проставляя categoryId/categoryName на клиенте. Тесты ниже настраивают
 * оба фейковых вызова соответственно.
 */
class MenuRepositoryTest {

    private lateinit var api: FakeWaiterApiService
    private lateinit var menuDao: FakeMenuDao
    private lateinit var repository: MenuRepository

    private val serverCategories = listOf(
        CategoryInfo(id = 2, name = "Пицца")
    )

    // То, что реально отдаёт getDishesByCategory (без categoryId/categoryName -
    // их в схеме MenuGetResponse нет, репозиторий проставляет сам).
    private val serverDishesForCategory = listOf(
        MenuItem(id = 1, name = "Пицца Маргарита", price = 45.0, quantity = 100.0)
    )

    // То, что должно получиться ПОСЛЕ склейки в репозитории.
    private val expectedMergedMenu = listOf(
        MenuItem(id = 1, name = "Пицца Маргарита", price = 45.0, quantity = 100.0, categoryId = 2, categoryName = "Пицца")
    )

    private fun stubHappyPath() {
        api.getAllCategoriesResult = { Response.success(serverCategories) }
        api.getDishesByCategoryResult = { Response.success(serverDishesForCategory) }
    }

    @Before
    fun setUp() {
        api = FakeWaiterApiService()
        menuDao = FakeMenuDao()
        repository = MenuRepository(
            api,
            menuDao,
            FakeTokenStore(),
            // Как и в TableRepositoryTest: клиент подключается лениво только
            // при первом вызове .topic(), так что фейковый URL здесь не
            // вызывает реальных сетевых обращений в тестах.
            StompWebSocketClient("ws://localhost/ws/websocket", FakeTokenStore())
        )
    }

    @Test
    fun `successful load returns fresh merged data and updates cache`() = runTest {
        stubHappyPath()

        val result = repository.getAllMenuItems()

        assertTrue(result is ApiResult.Success)
        result as ApiResult.Success
        assertEquals(expectedMergedMenu, result.data)
        assertFalse(result.fromCache)
        // Кэш должен обновиться свежими данными
        assertEquals(1, menuDao.getAll().size)
    }

    @Test
    fun `network failure with existing cache falls back to cached menu`() = runTest {
        // Симулируем, что раньше меню уже успешно загружалось
        stubHappyPath()
        repository.getAllMenuItems()

        // Теперь категории недоступны (сервер/сеть недоступны)
        api.getAllCategoriesResult = { throw IOException("нет сети") }

        val result = repository.getAllMenuItems()

        assertTrue(result is ApiResult.Success)
        result as ApiResult.Success
        assertTrue(result.fromCache)
        assertEquals(expectedMergedMenu.map { it.name }, result.data.map { it.name })
    }

    @Test
    fun `network failure without any cache returns the original error`() = runTest {
        api.getAllCategoriesResult = { throw IOException("нет сети") }

        val result = repository.getAllMenuItems()

        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `client error like 400 does not fall back to cache`() = runTest {
        // 400 - это не проблема сети, кэш подставлять не нужно, это ошибка запроса
        stubHappyPath()
        repository.getAllMenuItems()

        api.getAllCategoriesResult = { Response.error(400, "{}".toResponseBody(null)) }

        val result = repository.getAllMenuItems()

        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `one failing category does not blank out dishes from other categories`() = runTest {
        val categories = listOf(
            CategoryInfo(id = 1, name = "Завтрак"),
            CategoryInfo(id = 2, name = "Пицца")
        )
        api.getAllCategoriesResult = { Response.success(categories) }
        api.getDishesByCategoryResult = { categoryId ->
            if (categoryId == 1L) {
                Response.error(500, "{}".toResponseBody(null))
            } else {
                Response.success(serverDishesForCategory)
            }
        }

        val result = repository.getAllMenuItems()

        assertTrue(result is ApiResult.Success)
        result as ApiResult.Success
        // Категория 1 не отдала блюд, но категория 2 - отдала, и они должны быть в результате
        assertEquals(expectedMergedMenu, result.data)
    }
}
