package com.waiterapp.data.model

// --- 1. Создание заказа (POST api/v1/orders) ---
// staffId в теле не передаётся - официант определяется по JWT
// (Authorization: Bearer accessToken, см. AuthInterceptor).
//
// ВАЖНО про tableNumbers: на бэкенде CreateAndUpdateOrderRequest.tableNumbers
// это Set<Long>, а не список строк вида "T-01"! При этом сам стол (CafeTable.number)
// хранится в БД как String, и TableRepository.findByNumberIn(Set<Long> numbers)
// сравнивает эту строку с числами - т.е. номер стола на сервере должен быть
// ЧИСТО ЦИФРОВОЙ строкой ("1", "12"...), иначе Set<Long> просто не сможет
// сматчиться со String-полем и создание заказа будет падать. Если админ
// в вашей БД создаёт столы в формате "T-01" - создание заказа не будет
// работать, пока номера столов не станут чисто числовыми.
data class OrderItemRequest(
    val menuId: Long,
    val quantity: Int
)

data class CreateOrderRequest(
    val tableNumbers: Set<Long>,
    val items: List<OrderItemRequest>,
    val comment: String? = null
)

// Блюдо внутри ответа по заказу (OrderWaiterResponse.menu на бэкенде) -
// без id, только имя/цена/количество.
data class OrderDish(
    val name: String,
    val price: Double,
    val quantity: Int
)

// Ответ сервера на создание/добавление позиций/увеличение/уменьшение/
// получение одного заказа - один и тот же тип OrderWaiterResponse на
// бэкенде используется для всех этих операций.
data class OrderResponse(
    val orderId: Long,
    val totalPrice: Double,
    val status: String,
    val tableNumbers: List<String>,
    val comment: String?,
    val menu: List<OrderDish>
)

// --- 2. Отмена заказа (PATCH api/v1/orders/{orderId}/cancel) ---
data class CancelOrderResponse(
    val orderId: Long,
    val status: String
)

// --- 3. Список заказов текущего официанта (GET api/v1/orders/my) ---
// Официант определяется по JWT - staffId в запросе не передаётся.
// ВАЖНО: сервер отдаёт только orderId + status, без стола/блюд/суммы -
// см. комментарий у WaiterApiService.getMyOrders().
data class OrderSummary(
    val orderId: Long,
    val orderNumber: String,
    val status: String
)

data class WaiterOrdersResponse(
    val orders: List<OrderSummary>
)

// TableStatus на бэкенде объявлен (см. TableStatus.java), но само поле
// "status" у CafeTable сейчас ЗАКОММЕНТИРОВАНО - GET api/v1/table/all его
// не возвращает вовсе. Оставляем enum на клиенте на будущее (когда бэкенд
// включит это поле обратно), но по факту сейчас статус всегда неизвестен -
// см. TableInfo.status в Table.kt и HallScreen.kt.
enum class TableStatus {
    NOT_RESERVED, RESERVED
}

// --- Локальный "блокнот" заказов (клиентское хранилище, не с сервера) ---

/**
 * Одна позиция в локальном чеке. Хранит menuId, чтобы при повторной
 * отправке можно было собрать корректный CreateOrderRequest.
 */
data class LocalOrderItem(
    val menuId: Long,
    val name: String,
    val price: Double,
    val quantity: Int
)

/**
 * Статус локального чека:
 * - NOT_SENT: заказ сохранён на телефоне, но ещё не подтверждён сервером
 *   (либо отправка ещё не пробовалась, либо не получилось - для официанта
 *   разницы никакой, в обоих случаях доступна кнопка "Отправить повторно").
 * - SENT: сервер подтвердил приём заказа.
 * - CANCELLED: заказ был отправлен на сервер (serverOrderId != null), а
 *   затем отменён через PATCH api/v1/orders/{orderId}/cancel (см.
 *   LocalOrderRepository.cancelOrder). Запись НЕ удаляется физически из
 *   local_orders - она остаётся видимой (как отменённая), т.к. локальная
 *   таблица используется в том числе как история отправленных чеков.
 */
enum class LocalOrderStatus {
    NOT_SENT, SENT, CANCELLED
}
