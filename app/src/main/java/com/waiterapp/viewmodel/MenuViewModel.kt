package com.waiterapp.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waiterapp.data.model.CategoryInfo
import com.waiterapp.data.model.MenuItem
import com.waiterapp.data.network.ApiConfig
import com.waiterapp.data.repository.ApiResult
import com.waiterapp.data.repository.MenuRepository
import kotlinx.coroutines.launch

class MenuViewModel(private val menuRepository: MenuRepository) : ViewModel() {

    companion object {
        private const val TAG = "MenuViewModel"

        // Флаг вынесен в ApiConfig.MOCK_MENU_ENABLED - там единое место
        // для всех настроек подключения к серверу.
        val MOCK_MENU_ENABLED get() = ApiConfig.MOCK_MENU_ENABLED

        // ВАЖНО: связь mock-данных теперь строится через categoryId, а не
        // через categoryName (см. filteredItems ниже и forensic-аудит,
        // раздел "categoryId vs categoryName") - categoryName здесь оставлен
        // только для отображения и специально совпадает с mockCategories,
        // чтобы моки не расходились с реальным контрактом сервера.
        private val mockItems = listOf(
            MenuItem(id = 1, name = "Пицца Маргарита", price = 45.0, quantity = 100.0, imageUrl = "drawable://food_pizza_margherita", categoryId = 2, categoryName = "Пицца"),
            MenuItem(id = 2, name = "Пицца Пепперони", price = 55.0, quantity = 100.0, imageUrl = "drawable://food_pizza_pepperoni", categoryId = 2, categoryName = "Пицца"),
            MenuItem(id = 3, name = "Кола 0.5l", price = 10.0, quantity = 50.0, categoryId = 3, categoryName = "Напитки"),
            MenuItem(id = 4, name = "Сок апельсиновый 0.3l", price = 8.0, quantity = 40.0, categoryId = 3, categoryName = "Напитки"),
            MenuItem(id = 5, name = "Омлет с сыром", price = 20.0, quantity = 30.0, categoryId = 1, categoryName = "Завтрак")
        )
        private val mockCategories = listOf(
            CategoryInfo(id = 1, name = "Завтрак"),
            CategoryInfo(id = 2, name = "Пицца"),
            CategoryInfo(id = 3, name = "Напитки")
        )
    }

    var allItems by mutableStateOf<List<MenuItem>>(emptyList())
        private set
    var categories by mutableStateOf<List<CategoryInfo>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    // Категория, которую выбрали на экране "Новый заказ" - хранит ID
    // категории (Category.id / MenuItem.categoryId), а не её название.
    // Название нестабильно (сервер может его переименовать), id - нет,
    // поэтому связь строится именно по нему (см. filteredItems ниже и
    // forensic-аудит, раздел "categoryId vs categoryName").
    var selectedCategoryId by mutableStateOf<Long?>(null)
        private set
    // true, если сети не было и показано сохранённое офлайн-меню,
    // а не свежие данные с сервера. UI может показать за счёт этого плашку.
    var isShowingCachedData by mutableStateOf(false)
        private set
    var isCreatingCategory by mutableStateOf(false)
        private set
    var createCategoryError by mutableStateOf<String?>(null)
        private set

    /**
     * Фильтрация по categoryId (стабильный ключ), а не по categoryName
     * (см. forensic-аудит: имя категории может измениться на сервере -
     * id при этом остаётся тем же, поэтому фильтр не ломается при
     * переименовании категории, пока сама категория не удалена).
     * selectedCategoryId == null (кнопка "ДОБАВИТЬ ЕЩЁ") - фильтр не
     * применяется, показываются все блюда.
     */
    val filteredItems: List<MenuItem>
        get() = selectedCategoryId?.let { wanted ->
            allItems.filter { it.categoryId == wanted }
        } ?: allItems

    init {
        // Подписка на Room должна начаться ДО первого REST-запроса: тогда
        // allItems сразу отражает то, что уже было сохранено с прошлого
        // запуска (офлайн-старт), а не остаётся пустым до ответа сервера.
        observeMenuFromDb()
        loadMenu()
        loadCategories()
        observeMenuEvents()
        observeCategoryEvents()
    }

    /**
     * Единственное место, где заполняется allItems (для реального,
     * не-мокового режима) - конечное звено цепочки
     * STOMP /topic/menu -> MenuRepository -> REST -> Room -> Flow -> ViewModel -> Compose.
     * loadMenu() сам НЕ трогает allItems напрямую: он только дёргает сеть
     * и (внутри репозитория) перезаписывает Room, а сюда новый список
     * приходит уже через этот Flow.
     */
    private fun observeMenuFromDb() {
        if (MOCK_MENU_ENABLED) return
        viewModelScope.launch {
            menuRepository.observeMenuItems().collect { items ->
                allItems = items
            }
        }
    }

    fun loadMenu() {
        if (MOCK_MENU_ENABLED) {
            allItems = mockItems
            return
        }

        viewModelScope.launch {
            // Показываем лоадер ТОЛЬКО если данных ещё нет
            if (allItems.isEmpty()) {
                isLoading = true
            }

            errorMessage = null
            when (val result = menuRepository.getAllMenuItems()) {
                is ApiResult.Success -> {
                    // allItems здесь намеренно НЕ присваивается из result.data:
                    // getAllMenuItems() уже записал свежие данные в Room
                    // (menuDao.replaceAll), а сам список в allItems попадёт
                    // через observeMenuFromDb() - так UI всегда видит именно
                    // то, что реально лежит в локальном кэше.
                    isLoading = false
                    isShowingCachedData = result.fromCache
                    if (result.fromCache) {
                        errorMessage = "Нет связи с сервером. Показано сохранённое меню."
                    }
                }
                is ApiResult.Error -> {
                    isLoading = false
                    errorMessage = result.message
                }
            }
        }
    }

    fun loadCategories() {
        if (MOCK_MENU_ENABLED) {
            categories = mockCategories
            pruneSelectedCategoryIfMissing()
            return
        }

        viewModelScope.launch {
            when (val result = menuRepository.getAllCategories()) {
                is ApiResult.Success -> {
                    categories = result.data
                    pruneSelectedCategoryIfMissing()
                }
                // Молча не мешаем основному экрану меню - если категории не
                // загрузились, ряд категорий на "Новый заказ" будет просто
                // пустым (плюс кнопка "+"), это не должно блокировать работу.
                is ApiResult.Error -> Unit
            }
        }
    }

    /**
     * Если категория, которую пользователь выбрал раньше, реально исчезла
     * из свежего списка categories (удалена на сервере) - сбрасываем фильтр
     * на "показать всё", а не оставляем пользователя на несуществующей
     * категории с молча пустым списком. Переименование сюда не попадает:
     * id остаётся прежним, категория просто с новым именем, фильтр
     * продолжает находить её как раньше.
     */
    private fun pruneSelectedCategoryIfMissing() {
        val current = selectedCategoryId ?: return
        if (categories.none { it.id == current }) {
            selectedCategoryId = null
        }
    }

    fun createCategory(name: String, onSuccess: () -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            createCategoryError = "Введите название категории"
            return
        }

        viewModelScope.launch {
            isCreatingCategory = true
            createCategoryError = null
            when (val result = menuRepository.createCategory(trimmed)) {
                is ApiResult.Success -> {
                    isCreatingCategory = false
                    categories = categories + result.data
                    onSuccess()
                }
                is ApiResult.Error -> {
                    isCreatingCategory = false
                    createCategoryError = result.message
                }
            }
        }
    }

    fun clearCreateCategoryError() {
        createCategoryError = null
    }

    fun selectCategory(categoryId: Long?) {
        selectedCategoryId = categoryId
    }

    // Топик "/topic/menu" - бэкенд шлёт сюда WsEvent("Menu", action, data),
    // когда блюдо создали/изменили/удалили (см. MenuRepository.observeMenuEvents).
    // Не разбираем action/data на клиенте - событие используется только как
    // realtime-триггер, актуальные данные всё равно перезапрашиваются через
    // REST (loadCategories()/loadMenu()), это безопаснее конфликтующих частичных
    // обновлений StateFlow. loadMenu()/loadCategories() не трогают
    // selectedCategoryId (кроме pruneSelectedCategoryIfMissing, который сбрасывает
    // выбор, только если категория реально исчезла) - поэтому выбранная категория
    // и фильтрация по её id сохраняются после обновления.
    private fun observeMenuEvents() {
        if (MOCK_MENU_ENABLED) return
        viewModelScope.launch {
            menuRepository.observeMenuEvents().collect { body ->
                Log.d(TAG, "Menu event received: $body")
                loadCategories()
                loadMenu()
            }
        }
    }

    /**
     * Слушает отдельный STOMP-топик "/topic/category", предусмотренный
     * документацией backend. Для CREATED/UPDATED/DELETED применяется тот же
     * безопасный паттерн, что и в HallViewModel для "/topic/table": событие
     * используется только как realtime-триггер, а актуальные данные заново
     * запрашиваются через REST. Это особенно важно для DELETE, где payload
     * содержит только id категории. После обновления selectedCategoryId не
     * теряется, если категория всё ещё существует; если категория удалена,
     * pruneSelectedCategoryIfMissing() сбросит фильтр.
     */
    private fun observeCategoryEvents() {
        if (MOCK_MENU_ENABLED) return
        viewModelScope.launch {
            menuRepository.observeCategoryEvents().collect { body ->
                Log.d(TAG, "Category event received: $body")
                loadCategories()
                loadMenu()
            }
        }
    }
}
