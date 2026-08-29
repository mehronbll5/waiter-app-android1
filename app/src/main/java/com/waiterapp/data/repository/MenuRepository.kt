package com.waiterapp.data.repository

import com.waiterapp.data.local.MenuDao
import com.waiterapp.data.local.toEntity
import com.waiterapp.data.local.toMenuItem
import com.waiterapp.data.local.TokenStore
import com.waiterapp.data.model.CategoryInfo
import com.waiterapp.data.model.CreateCategoryRequest
import com.waiterapp.data.model.MenuItem
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.data.network.WaiterApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map

// Realtime-события меню и категорий приходят в ДВА разных STOMP-топика:
//   /topic/menu     -> entity = "Menu"
//   /topic/category -> entity = "Category"
// Их нельзя смешивать: каждый topic имеет отдельную подписку, но оба
// используются одинаково - как realtime-триггер для повторного REST-запроса.
private const val MENU_TOPIC = "/topic/menu"
private const val CATEGORY_TOPIC = "/topic/category"

/**
 * Загружает меню с сервера. Если сервер недоступен (нет сети/таймаут/5xx),
 * пытается отдать последнюю сохранённую версию меню из локального кэша (Room),
 * чтобы официант мог продолжать принимать заказы даже при потере связи.
 * Результат в этом случае помечен ApiResult.Success(fromCache = true) -
 * UI может показать небольшую плашку "показано сохранённое меню".
 */
class MenuRepository(
    private val api: WaiterApiService,
    private val menuDao: MenuDao,
    private val tokenManager: TokenStore,
    private val stompClient: StompWebSocketClient
) {

    // Аналогично TableRepository.observeTableEvents() - "горячий" поток,
    // ViewModel сам решает, что делать при получении события (обычно -
    // перезагрузить категории/меню). Тело сообщения - WsEvent("Menu", action, data);
    // специально не парсим его здесь в MenuItem (см. комментарий у
    // TableRepository.observeTableEvents про DELETED с "голым" id) - вместо
    // этого просто триггерим полный перезапрос через REST, это надёжнее.
    fun observeMenuEvents(): SharedFlow<String> = stompClient.topic(MENU_TOPIC)

    /**
     * Поток realtime-событий категорий. Бэкенд публикует CREATED/UPDATED/DELETED
     * для Category в единый STOMP topic "/topic/category". Как и для столов,
     * тело события здесь не разбираем: любое событие является сигналом
     * перезагрузить актуальные категории и меню через REST.
     */
    fun observeCategoryEvents(): SharedFlow<String> = stompClient.topic(CATEGORY_TOPIC)

    /**
     * Последнее звено цепочки Room -> Flow -> MenuViewModel -> Compose.
     * Не ходит в сеть сама - просто транслирует то, что сейчас лежит в
     * локальном кэше Room, и переиспускает список при каждом изменении
     * таблицы menu_items (в частности, после getAllMenuItems() ниже
     * записывает туда свежие данные через menuDao.replaceAll()).
     * ViewModel подписывается на этот Flow один раз в init{} и больше не
     * обращается к allDishes из ApiResult напрямую - именно так событие из
     * "/topic/menu" в итоге доходит до Compose: WebSocket-триггер -> REST ->
     * Room.replaceAll() -> этот Flow эмитит новый список -> ViewModel -> UI.
     */
    fun observeMenuItems(): Flow<List<MenuItem>> =
        menuDao.observeAll().map { entities -> entities.map { it.toMenuItem() } }

    /**
     * По Swagger-схеме бэкенда нет эндпоинта "все блюда одним списком" -
     * GET "api/v1/menus" не существует (404), есть только список блюд ОДНОЙ
     * категории (getDishesByCategory). Поэтому "всё меню" собирается здесь:
     * сначала список категорий, потом эти блюда по каждой категории отдельным
     * запросом, склеенные в один список. Ответ getDishesByCategory не содержит
     * categoryId/categoryName (по схеме MenuGetResponse их нет) - проставляем
     * их на клиенте вручную, т.к. мы и так знаем, для какой категории делали
     * запрос; это то, на чём строится фильтрация в MenuViewModel.filteredItems.
     */
    suspend fun getAllMenuItems(): ApiResult<List<MenuItem>> {
        val categoriesResult = safeApiCall(tokenManager) { api.getAllCategories() }

        val categoriesList = when (categoriesResult) {
            is ApiResult.Success -> categoriesResult.data
            is ApiResult.Error -> {
                val isConnectivityIssue = categoriesResult.code < 0 || categoriesResult.code >= 500
                val cached = if (isConnectivityIssue) menuDao.getAll() else emptyList()
                return if (cached.isNotEmpty()) {
                    ApiResult.Success(cached.map { it.toMenuItem() }, fromCache = true)
                } else {
                    categoriesResult
                }
            }
        }

        val allDishes = mutableListOf<MenuItem>()
        var lastError: ApiResult.Error? = null

        for (category in categoriesList) {
            when (val dishesResult = safeApiCall(tokenManager) { api.getDishesByCategory(category.id) }) {
                is ApiResult.Success -> {
                    allDishes += dishesResult.data.map {
                        it.copy(categoryId = category.id, categoryName = category.name)
                    }
                }
                // Одна "битая" категория не должна обрушивать всё меню -
                // запоминаем последнюю ошибку и продолжаем остальные категории.
                is ApiResult.Error -> lastError = dishesResult
            }
        }

        return if (allDishes.isNotEmpty() || categoriesList.isEmpty()) {
            // Обновляем кэш свежими данными на будущее.
            menuDao.replaceAll(allDishes.map { it.toEntity() })
            ApiResult.Success(allDishes)
        } else {
            // Ни одна категория не отдала блюд - пробуем офлайн-кэш, как и раньше.
            val code = lastError?.code ?: -1
            val isConnectivityIssue = code < 0 || code >= 500
            val cached = if (isConnectivityIssue) menuDao.getAll() else emptyList()
            if (cached.isNotEmpty()) {
                ApiResult.Success(cached.map { it.toMenuItem() }, fromCache = true)
            } else {
                lastError ?: ApiResult.Error(-1, "Не удалось загрузить меню.")
            }
        }
    }

    // Категории меняются редко и список короткий - отдельного Room-кэша под
    // них не заводим (в отличие от меню/столов), при ошибке сети просто
    // возвращаем пустой список, UI это уже умеет показывать спокойно.
    suspend fun getAllCategories(): ApiResult<List<CategoryInfo>> =
        safeApiCall(tokenManager) { api.getAllCategories() }

    suspend fun createCategory(name: String): ApiResult<CategoryInfo> =
        safeApiCall(tokenManager) { api.createCategory(CreateCategoryRequest(name)) }
}
