package com.waiterapp.viewmodel

import com.waiterapp.MainDispatcherRule
import com.waiterapp.data.model.CategoryInfo
import com.waiterapp.data.model.MenuItem
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.data.repository.MenuRepository
import com.waiterapp.fakes.FakeMenuDao
import com.waiterapp.fakes.FakeTokenStore
import com.waiterapp.fakes.FakeWaiterApiService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/**
 * ВАЖНО: ApiConfig.MOCK_MENU_ENABLED сейчас = false (см. ApiConfig.kt -
 * DEMO_MODE_ENABLED = false, MOCK_MENU_ENABLED_RAW = false), поэтому
 * MenuViewModel.init{} реально идёт по сетевой ветке через MenuRepository,
 * а НЕ по companion-object mockItems/mockCategories.
 *
 * По Swagger-схеме бэкенда нет "плоского" эндпоинта для всего меню -
 * MenuRepository.getAllMenuItems() грузит категории, а затем блюда КАЖДОЙ
 * категории отдельным запросом (getDishesByCategory) и сам проставляет
 * categoryId/categoryName по тому, какую категорию запрашивал. Поэтому
 * getDishesByCategoryResult здесь настраивается как РОУТЕР по categoryId -
 * он возвращает только блюда этой категории, как и настоящий сервер.
 * ВАЖНО: у этого эндпоинта нет способа получить блюдо, вообще не
 * привязанное ни к одной категории (в отличие от старой версии API) -
 * такое блюдо просто никогда не попадёт ни в один из циклических запросов
 * по категориям, поэтому кейс "блюдо без категории" тут больше не
 * тестируется как часть общего меню.
 *
 * Связь Category-Menu строится через categoryId (стабильный ключ), а не
 * через categoryName - тесты специально покрывают переименование и
 * удаление категории, чтобы зафиксировать именно это поведение.
 */
class MenuViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val categoryPizza = CategoryInfo(id = 1, name = "Пицца")
    private val categoryDrinks = CategoryInfo(id = 2, name = "Напитки")

    private val pizzaItem = MenuItem(id = 1, name = "Маргарита", price = 45.0, quantity = 100.0, categoryId = 1, categoryName = "Пицца")
    private val drinkItem = MenuItem(id = 2, name = "Кола", price = 10.0, quantity = 50.0, categoryId = 2, categoryName = "Напитки")

    private lateinit var api: FakeWaiterApiService
    private lateinit var viewModel: MenuViewModel

    /**
     * items должны иметь непустой categoryId, соответствующий одной из
     * переданных categories - именно так реально ходит запрос
     * (getDishesByCategory роутится по этому полю, как и настоящий сервер).
     */
    private fun createViewModel(
        categories: List<CategoryInfo> = listOf(categoryPizza, categoryDrinks),
        items: List<MenuItem> = listOf(pizzaItem, drinkItem)
    ): MenuViewModel {
        api = FakeWaiterApiService()
        api.getAllCategoriesResult = { Response.success(categories) }
        api.getDishesByCategoryResult = { categoryId ->
            Response.success(items.filter { it.categoryId == categoryId })
        }
        val repository = MenuRepository(
            api,
            FakeMenuDao(),
            FakeTokenStore(),
            // Лениво подключается только при первом topic() - реальных
            // сетевых обращений в этих тестах не будет (как и в
            // MenuRepositoryTest/TableRepositoryTest).
            StompWebSocketClient("ws://localhost/ws/websocket", FakeTokenStore())
        )
        return MenuViewModel(repository)
    }

    @Before
    fun setUp() {
        viewModel = createViewModel()
    }

    @Test
    fun `menu and categories are loaded on init`() {
        assertEquals(2, viewModel.allItems.size)
        assertEquals(2, viewModel.categories.size)
    }

    @Test
    fun `no category selected shows all items`() {
        viewModel.selectCategory(null)

        assertEquals(viewModel.allItems.size, viewModel.filteredItems.size)
    }

    @Test
    fun `TEST 1 - selecting category by id shows the matching dish`() {
        viewModel.selectCategory(categoryPizza.id)

        assertEquals(listOf(pizzaItem), viewModel.filteredItems)
        assertEquals(categoryPizza.id, viewModel.selectedCategoryId)
    }

    @Test
    fun `TEST 2 - dish with a different categoryId is not shown`() {
        viewModel.selectCategory(categoryPizza.id)

        // drinkItem.categoryId = 2, выбран id = 1 - блюдо не должно попасть в фильтр.
        assertFalse(viewModel.filteredItems.contains(drinkItem))
        assertEquals(listOf(pizzaItem), viewModel.filteredItems)
    }

    @Test
    fun `TEST 3 - two categories with the same name are distinguished by id`() {
        // Обе категории называются "Напитки", но у них разные id -
        // раньше (фильтрация по categoryName) это бы схлопнуло их в одну.
        val duplicateNameCategory = CategoryInfo(id = 3, name = "Напитки")
        val duplicateNameItem = MenuItem(id = 4, name = "Сок", price = 8.0, quantity = 10.0, categoryId = 3, categoryName = "Напитки")
        viewModel = createViewModel(
            categories = listOf(categoryDrinks, duplicateNameCategory),
            items = listOf(drinkItem, duplicateNameItem)
        )

        viewModel.selectCategory(categoryDrinks.id)

        assertEquals(listOf(drinkItem), viewModel.filteredItems)
    }

    @Test
    fun `TEST 4 - renaming a category keeps filtering by id working`() {
        viewModel.selectCategory(categoryPizza.id)
        assertEquals(listOf(pizzaItem), viewModel.filteredItems)

        // Backend переименовал категорию: id = 1 остался прежним, name сменился.
        // categoryName у блюда репозиторий проставляет сам из категории,
        // поэтому в ответе getDishesByCategory его можно даже не менять.
        val renamedCategory = categoryPizza.copy(name = "Пицца 2")
        val renamedItem = pizzaItem.copy(categoryName = "Пицца 2")
        api.getAllCategoriesResult = { Response.success(listOf(renamedCategory, categoryDrinks)) }
        api.getDishesByCategoryResult = { categoryId ->
            when (categoryId) {
                renamedCategory.id -> Response.success(listOf(renamedItem))
                categoryDrinks.id -> Response.success(listOf(drinkItem))
                else -> Response.success(emptyList())
            }
        }

        viewModel.loadCategories()
        viewModel.loadMenu()

        // Раньше (фильтр по имени) filteredItems стал бы пустым здесь -
        // selectedCategory хранил старое "Пицца", а у блюда уже "Пицца 2".
        assertEquals(categoryPizza.id, viewModel.selectedCategoryId)
        assertEquals(listOf(renamedItem), viewModel.filteredItems)
    }

    @Test
    fun `TEST 5 - category and menu reload (as triggered by the category WebSocket topic) does not break selectedCategoryId`() {
        // MenuRepository.observeCategoryEvents()/MenuViewModel.observeCategoryEvents()
        // при любом сообщении в "/topic/category" вызывают ровно loadCategories()
        // и loadMenu() (см. MenuViewModel.kt) - тест воспроизводит этот же эффект
        // напрямую, т.к. FakeWaiterApiService/StompWebSocketClient не даёт простого
        // способа сымитировать сырой STOMP-фрейм на уровне юнит-теста.
        viewModel.selectCategory(categoryDrinks.id)
        assertEquals(listOf(drinkItem), viewModel.filteredItems)

        // Порядок ответов сети не гарантирован - проверяем оба варианта.
        viewModel.loadMenu()
        viewModel.loadCategories()

        assertEquals(categoryDrinks.id, viewModel.selectedCategoryId)
        assertEquals(listOf(drinkItem), viewModel.filteredItems)
    }

    @Test
    fun `deleting the selected category resets the filter to show all`() {
        viewModel.selectCategory(categoryPizza.id)
        assertEquals(listOf(pizzaItem), viewModel.filteredItems)

        // Категория удалена на сервере - её больше нет в свежем списке categories.
        api.getAllCategoriesResult = { Response.success(listOf(categoryDrinks)) }

        viewModel.loadCategories()

        assertNull(viewModel.selectedCategoryId)
        assertEquals(viewModel.allItems.size, viewModel.filteredItems.size)
    }

    @Test
    fun `TEST 6 - no dish matches the selected category id`() {
        viewModel.selectCategory(999L) // несуществующий id

        assertTrue(viewModel.filteredItems.isEmpty())
    }
}
