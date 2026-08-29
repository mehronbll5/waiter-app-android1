package com.waiterapp.data.repository

import com.google.gson.Gson
import com.waiterapp.data.local.LocalOrderDao
import com.waiterapp.data.local.LocalOrderEntity
import com.waiterapp.data.local.OldOrderCacheDao
import com.waiterapp.data.local.OldOrderCacheEntity
import com.waiterapp.data.local.TokenStore
import com.waiterapp.data.model.CreateOrderRequest
import com.waiterapp.data.model.LocalOrderItem
import com.waiterapp.data.model.LocalOrderStatus
import com.waiterapp.data.model.OrderItemRequest
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.data.network.WaiterApiService
import kotlinx.coroutines.flow.SharedFlow

// 24 часа - TTL локального кэша вкладки "Старые" (см. getOldOrders ниже).
// Один timestamp (cachedAtMillis) достаточно - отдельная система версий/
// миграций кэша не нужна (см. forensic-задание, часть 10.1).
private const val OLD_ORDERS_CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L

// Топик, в который кухня шлёт официанту "заказ готов" (см. раздел "4. Order"
// в websocket-topics.pdf). На него в бэкенде шлют ДВА разных метода с
// разными DTO под одну и ту же цель (READY):
//   KitchensAnswerToWaiterResponse(orderId, orderNumber, status: String)
//   UpdateOrderFromKitchenResponse(orderId, orderNumber, orderStatus: OrderStatus)
// Поле называется по-разному ("status" vs "orderStatus") и это
// задокументированное несоответствие типов - см. KitchenWaiterEvent ниже,
// который читает оба варианта. ВАЖНО: топик сейчас общий broadcast на ВСЕХ
// официантов (адресная доставка через /user/** пока не реализована на
// бэкенде - см. "Не реализовано" в документации), поэтому applyKitchenWaiterEvent
// ниже сам фильтрует "чужие" заказы: событие применяется только если
// orderId совпадает с serverOrderId ОДНОГО ИЗ заказов в локальном "блокноте"
// этого официанта - события по чужим orderId просто не находят совпадения
// и молча игнорируются.
private const val ORDER_WAITER_TOPIC = "/topic/order/waiter"

private data class KitchenWaiterEvent(
    val orderId: Long? = null,
    val orderNumber: String? = null,
    val status: String? = null,
    val orderStatus: String? = null
)

/**
 * "Блокнот" заказов на телефоне официанта (Room). Заказ сохраняется сюда
 * СРАЗУ при нажатии "Отправить" в NewOrderScreen - ещё до сетевого запроса,
 * поэтому даже при полном отсутствии связи заказ не теряется.
 *
 * После сохранения делается попытка реальной отправки на сервер
 * (см. trySend). Если не получилось - запись остаётся со статусом
 * NOT_SENT, и официант может нажать "Отправить повторно" в разделе "Заказы"
 * в любой момент (например, когда связь появится).
 */
class LocalOrderRepository(
    private val dao: LocalOrderDao,
    private val api: WaiterApiService,
    private val tokenStore: TokenStore,
    private val stompClient: StompWebSocketClient,
    // Отдельная локальная таблица под вкладку "Старые" (см. getOldOrders) -
    // nullable-с-дефолтом, чтобы не ломать существующие вызовы конструктора
    // (например, в тестах), которые про неё ничего не знают.
    private val oldOrderCacheDao: OldOrderCacheDao? = null
) {
    private val gson = Gson()

    /**
     * Поток сырых JSON-тел с "/topic/order/waiter" - подписчик (OrdersViewModel)
     * сам решает, когда вызывать applyKitchenWaiterEvent + перезапрашивать
     * receipts, чтобы не тянуть viewModelScope сюда, в репозиторий.
     */
    fun observeKitchenWaiterEvents(): SharedFlow<String> = stompClient.topic(ORDER_WAITER_TOPIC)

    /**
     * Разбирает событие с "/topic/order/waiter" и, если оно относится к
     * заказу из ЭТОГО блокнота (см. комментарий у ORDER_WAITER_TOPIC выше),
     * сохраняет присланный статус в kitchenStatus локальной записи.
     * Тихо ничего не делает при ошибке парсинга или незнакомом orderId.
     */
    suspend fun applyKitchenWaiterEvent(rawJson: String) {
        val event = runCatching { gson.fromJson(rawJson, KitchenWaiterEvent::class.java) }.getOrNull() ?: return
        val orderId = event.orderId ?: return
        val status = event.status ?: event.orderStatus ?: return
        val local = dao.getByServerOrderId(orderId) ?: return
        dao.update(local.copy(kitchenStatus = status))
    }

    suspend fun getAll(): List<LocalOrderEntity> = dao.getAll()

    /**
     * Данные для вкладки "Старые" в разделе "Заказы" (см. forensic-задание,
     * часть 9.2/10). ВАЖНО про backend contract: отдельного эндпоинта
     * "старые"/"завершённые" заказы в WaiterApiService НЕТ и придумывать
     * его нельзя (см. forensic-аудит) - единственный существующий способ
     * получить список заказов официанта - GET /api/v1/orders/my
     * (getMyOrders -> WaiterOrdersResponse{orders: List<OrderSummary{orderId,orderNumber,status}>}),
     * без каких-либо query-параметров для фильтрации по статусу или дате.
     * status в OrderSummary - непрозрачная строка, её возможные значения
     * нигде в клиентском контракте не описаны, поэтому этот метод не
     * пытается сам решать, что такое "старый"/"завершённый" заказ - просто
     * кэширует список целиком, как его отдаёт сервер; экран показывает
     * status как текст без интерпретации.
     *
     * Кэш живёт 24 часа (OLD_ORDERS_CACHE_TTL_MILLIS) в отдельной локальной
     * таблице old_orders_cache (НЕ в local_orders/блокноте - вкладка
     * "Новые" его не касается и продолжает работать как раньше).
     *
     * forceRefresh = true игнорирует ещё не истёкший кэш и идёт в сеть
     * заново (кнопка "Обновить" на экране).
     */
    suspend fun getOldOrders(forceRefresh: Boolean = false): ApiResult<List<OldOrderCacheEntity>> {
        val cacheDao = oldOrderCacheDao
            ?: return ApiResult.Error(-1, "Локальный кэш старых заказов недоступен")

        if (!forceRefresh) {
            val cached = cacheDao.getAll()
            val oldestCacheAgeMillis = cached.maxOfOrNull { System.currentTimeMillis() - it.cachedAtMillis }
            if (cached.isNotEmpty() && oldestCacheAgeMillis != null && oldestCacheAgeMillis < OLD_ORDERS_CACHE_TTL_MILLIS) {
                return ApiResult.Success(cached, fromCache = true)
            }
        }

        return when (val result = safeApiCall(tokenStore) { api.getMyOrders() }) {
            is ApiResult.Success -> {
                val now = System.currentTimeMillis()
                val entities = result.data.orders.map {
                    OldOrderCacheEntity(orderId = it.orderId, orderNumber = it.orderNumber, status = it.status, cachedAtMillis = now)
                }
                // ЧИСТО локальная запись (Room) - никакого обращения к
                // backend здесь нет (см. OldOrderCacheDao.replaceAll).
                cacheDao.replaceAll(entities)
                ApiResult.Success(entities, fromCache = false)
            }
            is ApiResult.Error -> {
                // Сети нет (или сервер недоступен) - если есть хоть какой-то,
                // пусть и просроченный по TTL, локальный кэш, лучше показать
                // его как fallback, чем пустой экран. Backend при этом никак
                // не трогаем и просроченную локальную копию не удаляем -
                // именно она сейчас единственный источник данных.
                val cached = cacheDao.getAll()
                if (cached.isNotEmpty()) ApiResult.Success(cached, fromCache = true) else result
            }
        }
    }

    /**
     * Наполняет "Заказы" тестовыми чеками для демонстрации UI, когда локальная
     * таблица пуста (не трогает реальные данные, если там уже что-то есть).
     * Позиции условные (menuId выдуманные) - это только для показа списка
     * заказов, отправить такой чек на сервер по-настоящему не получится, пока
     * меню в БД бэкенда пустое.
     */
    suspend fun seedSampleDataIfEmpty() {
        if (dao.getAll().isNotEmpty()) return

        val now = System.currentTimeMillis()
        val samples = listOf(
            LocalOrderEntity(
                tableNumbersCsv = "1,2,3",
                comment = null,
                itemsJson = gson.toJson(
                    listOf(
                        LocalOrderItem(menuId = 1, name = "Пицца", price = 50.0, quantity = 1),
                        LocalOrderItem(menuId = 2, name = "Чай", price = 5.0, quantity = 2),
                        LocalOrderItem(menuId = 3, name = "Суп", price = 20.0, quantity = 2)
                    )
                ),
                totalPrice = 100.0,
                createdAtMillis = now,
                status = LocalOrderStatus.NOT_SENT.name
            ),
            LocalOrderEntity(
                tableNumbersCsv = "4,5",
                comment = null,
                itemsJson = gson.toJson(
                    listOf(LocalOrderItem(menuId = 4, name = "Плов", price = 75.0, quantity = 1))
                ),
                totalPrice = 75.0,
                createdAtMillis = now - 60_000,
                status = LocalOrderStatus.NOT_SENT.name
            ),
            LocalOrderEntity(
                tableNumbersCsv = "6,7",
                comment = null,
                itemsJson = gson.toJson(
                    listOf(LocalOrderItem(menuId = 5, name = "Шашлык", price = 120.0, quantity = 1))
                ),
                totalPrice = 120.0,
                createdAtMillis = now - 120_000,
                status = LocalOrderStatus.SENT.name
            )
        )
        samples.forEach { dao.insert(it) }
    }

    suspend fun getById(id: Long): LocalOrderEntity? = dao.getById(id)

    suspend fun saveDraft(
        tableNumbers: List<String>,
        comment: String?,
        items: List<LocalOrderItem>,
        totalPrice: Double
    ): Long {
        val entity = LocalOrderEntity(
            tableNumbersCsv = tableNumbers.joinToString(","),
            comment = comment,
            itemsJson = gson.toJson(items),
            totalPrice = totalPrice,
            createdAtMillis = System.currentTimeMillis(),
            status = LocalOrderStatus.NOT_SENT.name
        )
        return dao.insert(entity)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    /**
     * Переключает локальную отметку "оплачен/не оплачен" у чека.
     * Пока это чисто клиентское состояние - сервер не хранит статус оплаты
     * отдельно от статуса заказа.
     */
    suspend fun togglePaid(id: Long) {
        val local = dao.getById(id) ?: return
        dao.update(local.copy(isPaid = !local.isPaid))
    }

    /**
     * Полностью заменяет состав ещё НЕ отправленного черновика (статус NOT_SENT).
     */
    suspend fun updateDraftItems(
        id: Long,
        tableNumbers: List<String>,
        comment: String?,
        items: List<LocalOrderItem>,
        totalPrice: Double
    ) {
        val local = dao.getById(id) ?: return
        dao.update(
            local.copy(
                tableNumbersCsv = tableNumbers.joinToString(","),
                comment = comment,
                itemsJson = gson.toJson(items),
                totalPrice = totalPrice,
                status = LocalOrderStatus.NOT_SENT.name
            )
        )
    }

    /**
     * Добавляет новые позиции к заказу, который сервер УЖЕ принял (статус SENT,
     * есть serverOrderId) - через PATCH api/v1/orders/{orderId}/items, тело
     * запроса - список позиций напрямую (без обёртки), как того требует
     * OrderController.updateOrder на бэкенде.
     */
    suspend fun addItemsToSentOrder(id: Long, newItems: List<LocalOrderItem>): ApiResult<Unit> {
        val local = dao.getById(id)
            ?: return ApiResult.Error(-1, "Локальный заказ не найден")

        val existingItems: List<LocalOrderItem> = runCatching {
            gson.fromJson(local.itemsJson, Array<LocalOrderItem>::class.java).toList()
        }.getOrDefault(emptyList())
        val mergedItems = mergeItems(existingItems, newItems)
        val mergedTotal = mergedItems.sumOf { it.price * it.quantity }

        // ДЕМО-РЕЖИМ: сразу считаем добавление успешным, без сетевого запроса.
        if (com.waiterapp.data.network.ApiConfig.DEMO_MODE_ENABLED) {
            dao.update(local.copy(itemsJson = gson.toJson(mergedItems), totalPrice = mergedTotal))
            return ApiResult.Success(Unit)
        }

        val serverOrderId = local.serverOrderId
        if (serverOrderId == null) {
            dao.update(
                local.copy(
                    itemsJson = gson.toJson(mergedItems),
                    totalPrice = mergedTotal,
                    pendingAppendItemsJson = gson.toJson(mergeItems(pendingItemsOf(local), newItems))
                )
            )
            return ApiResult.Error(-1, "У заказа нет id на сервере")
        }

        val requestItems = newItems.map { OrderItemRequest(menuId = it.menuId, quantity = it.quantity) }

        return when (val result = safeApiCall(tokenStore) { api.addItemsToOrder(serverOrderId, requestItems) }) {
            is ApiResult.Success -> {
                dao.update(local.copy(itemsJson = gson.toJson(mergedItems), totalPrice = mergedTotal))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                val pending = mergeItems(pendingItemsOf(local), newItems)
                dao.update(
                    local.copy(
                        itemsJson = gson.toJson(mergedItems),
                        totalPrice = mergedTotal,
                        pendingAppendItemsJson = gson.toJson(pending)
                    )
                )
                result
            }
        }
    }

    /**
     * Повторная попытка отправить блюда, добавленные к уже отправленному
     * заказу, но не дошедшие до сервера с первого раза (см. addItemsToSentOrder).
     */
    suspend fun retryAppend(id: Long): ApiResult<Unit> {
        val local = dao.getById(id)
            ?: return ApiResult.Error(-1, "Локальный заказ не найден")
        if (com.waiterapp.data.network.ApiConfig.DEMO_MODE_ENABLED) {
            dao.update(local.copy(pendingAppendItemsJson = null))
            return ApiResult.Success(Unit)
        }
        val serverOrderId = local.serverOrderId
            ?: return ApiResult.Error(-1, "У заказа нет id на сервере")
        val pending = pendingItemsOf(local)
        if (pending.isEmpty()) return ApiResult.Success(Unit)

        val requestItems = pending.map { OrderItemRequest(menuId = it.menuId, quantity = it.quantity) }

        return when (val result = safeApiCall(tokenStore) { api.addItemsToOrder(serverOrderId, requestItems) }) {
            is ApiResult.Success -> {
                dao.update(local.copy(pendingAppendItemsJson = null))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * Отмена заказа (замена старой кнопки "удалить" для чеков, которые
     * УЖЕ приняты сервером - см. forensic-задание "замена удаления заказа
     * на отмену через CANCEL API").
     *
     * ВАЖНО про разницу с [delete]:
     * - Если у чека ещё нет serverOrderId (черновик, который ни разу не
     *   был принят сервером - status NOT_SENT без serverOrderId), то
     *   отменять на бэкенде нечего: там физически не существует такого
     *   заказа. В этом случае просто удаляем локальную запись, как и
     *   раньше делала кнопка "удалить" - это не нарушает контракт,
     *   потому что DELETE тут не подменяет собой отмену реального заказа.
     * - Если serverOrderId есть (заказ был отправлен и принят сервером),
     *   запись НЕЛЬЗЯ просто удалить локально - нужно вызвать реальный
     *   PATCH api/v1/orders/{orderId}/cancel и только при успешном ответе
     *   обновить статус локальной записи на CANCELLED, не удаляя её
     *   физически (заказ должен остаться виден в истории).
     */
    suspend fun cancelOrder(id: Long): ApiResult<Unit> {
        val local = dao.getById(id)
            ?: return ApiResult.Error(-1, "Локальный заказ не найден")

        val serverOrderId = local.serverOrderId
        if (serverOrderId == null) {
            // Черновик никогда не доходил до сервера - отменять на бэкенде
            // нечего, убираем только локальную запись.
            dao.deleteById(id)
            return ApiResult.Success(Unit)
        }

        // ДЕМО-РЕЖИМ: как и другие операции с "сервером" в демо-режиме,
        // сразу считаем отмену успешной без реального сетевого запроса.
        if (com.waiterapp.data.network.ApiConfig.DEMO_MODE_ENABLED) {
            dao.update(local.copy(status = LocalOrderStatus.CANCELLED.name))
            return ApiResult.Success(Unit)
        }

        return when (val result = safeApiCall(tokenStore) { api.cancelOrder(serverOrderId) }) {
            is ApiResult.Success -> {
                // Заказ ОСТАЁТСЯ в local_orders (никакого deleteById/remove).
                // Локальный статус (NOT_SENT/SENT/CANCELLED) - это флаг
                // "дошёл ли чек до сервера", а не зеркало реального
                // OrderStatus бэкенда, поэтому переводим в CANCELLED (иначе
                // карточка продолжила бы считаться "отправленной" и
                // предлагать её редактировать/повторно отправлять). Точное
                // текстовое значение статуса, которое реально вернул backend
                // (result.data.status - например "CANCELLED"), сохраняем
                // отдельно в backendStatus - именно его показываем в UI как
                // есть, без досочинённого текста.
                dao.update(
                    local.copy(
                        status = LocalOrderStatus.CANCELLED.name,
                        backendStatus = result.data.status
                    )
                )
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                // Ошибка API - НЕ трогаем статус локальной записи и не
                // удаляем её, заказ должен остаться как был.
                result
            }
        }
    }

    private fun pendingItemsOf(local: LocalOrderEntity): List<LocalOrderItem> =
        local.pendingAppendItemsJson?.let {
            runCatching { gson.fromJson(it, Array<LocalOrderItem>::class.java).toList() }.getOrDefault(emptyList())
        } ?: emptyList()

    private fun mergeItems(base: List<LocalOrderItem>, additions: List<LocalOrderItem>): List<LocalOrderItem> {
        val merged = base.toMutableList()
        additions.forEach { addition ->
            val idx = merged.indexOfFirst { it.menuId == addition.menuId }
            if (idx >= 0) {
                merged[idx] = merged[idx].copy(quantity = merged[idx].quantity + addition.quantity)
            } else {
                merged.add(addition)
            }
        }
        return merged
    }

    /**
     * Пытается отправить сохранённый черновик заказа на сервер.
     * Обновляет статус записи по факту: SENT при успехе, иначе остаётся
     * (или снова становится) NOT_SENT, чтобы попытку можно было повторить.
     *
     * ВАЖНО: бэкенд ждёт tableNumbers как Set<Long> (числа), а не строки
     * вида "T-01" - см. комментарий у CreateOrderRequest в Order.kt. Если
     * номер стола не удаётся привести к числу, отправка не выполняется -
     * заказ остаётся NOT_SENT, чтобы официант не потерял данные и мог
     * попробовать снова после того, как номера столов на сервере поправят.
     */
    suspend fun trySend(id: Long): ApiResult<Unit> {
        val local = dao.getById(id)
            ?: return ApiResult.Error(-1, "Локальный заказ не найден")

        // ДЕМО-РЕЖИМ: см. ApiConfig.DEMO_MODE_ENABLED - без реального сервера
        // сразу считаем заказ "отправленным", с выдуманным id, вместо реального
        // сетевого запроса (у OkHttp таймауты отключены - без этой проверки
        // "Заказы" зависли бы на попытке связаться с недоступным сервером).
        if (com.waiterapp.data.network.ApiConfig.DEMO_MODE_ENABLED) {
            val fakeServerOrderId = local.serverOrderId ?: System.currentTimeMillis()
            dao.update(local.copy(status = LocalOrderStatus.SENT.name, serverOrderId = fakeServerOrderId))
            return ApiResult.Success(Unit)
        }

        if (tokenStore.getStaffId() == null) {
            return ApiResult.Error(-1, "Сессия не найдена, войдите заново")
        }

        val tableNumberStrings = local.tableNumbersCsv.split(",").filter { it.isNotBlank() }
        val tableNumbers = tableNumberStrings.mapNotNull { it.trim().toLongOrNull() }.toSet()
        if (tableNumbers.size != tableNumberStrings.size) {
            dao.update(local.copy(status = LocalOrderStatus.NOT_SENT.name))
            return ApiResult.Error(
                -1,
                "Номер стола должен быть числом для отправки на сервер (столы вида \"T-01\" сервер пока не принимает)"
            )
        }

        val items: List<LocalOrderItem> = runCatching {
            gson.fromJson(local.itemsJson, Array<LocalOrderItem>::class.java).toList()
        }.getOrDefault(emptyList())

        val request = CreateOrderRequest(
            tableNumbers = tableNumbers,
            items = items.map { OrderItemRequest(menuId = it.menuId, quantity = it.quantity) },
            comment = local.comment
        )

        return when (val result = safeApiCall(tokenStore) { api.createOrder(request) }) {
            is ApiResult.Success -> {
                dao.update(local.copy(status = LocalOrderStatus.SENT.name, serverOrderId = result.data.orderId))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                dao.update(local.copy(status = LocalOrderStatus.NOT_SENT.name))
                result
            }
        }
    }
}
