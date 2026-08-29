package com.waiterapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Локальный "блокнот" заказов - сохраняется на телефоне СРАЗУ при отправке
 * заказа (см. OrderViewModel.submitOrder), ещё до того, как известно,
 * дошёл ли он до сервера. Так официант не теряет заказ, даже если
 * связь пропала или сервер не ответил.
 *
 * itemsJson - список LocalOrderItem, сериализованный в JSON (Gson),
 * чтобы не заводить отдельную таблицу для позиций ради такого небольшого
 * объёма данных.
 */
@Entity(tableName = "local_orders")
data class LocalOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableNumbersCsv: String,
    val comment: String?,
    val itemsJson: String,
    val totalPrice: Double,
    val createdAtMillis: Long,
    val status: String,
    val serverOrderId: Long? = null,
    // Блюда, добавленные официантом к уже отправленному (SENT) заказу через
    // "Изменить заказ", но которые не удалось передать на сервер (нет сети
    // и т.п.). Они уже вписаны в itemsJson/totalPrice (чек выглядит цельным),
    // но по факту кухня их ещё не получила - поэтому карточка показывает
    // "не отправлен" и кнопку "Отправить повторно", которая пробует отправить
    // именно эти позиции (см. LocalOrderRepository.retryAppend), не создавая
    // на сервере отдельный дублирующий заказ.
    val pendingAppendItemsJson: String? = null,
    // Статус оплаты чека - пока чисто локальная отметка официанта (сервер
    // ещё не даёт отдельного эндпоинта "отметить оплаченным"), переключается
    // тапом по бейджу статуса в разделе "Заказы".
    val isPaid: Boolean = false,
    // Статус, пришедший от кухни через WebSocket-топик "/topic/order/waiter"
    // (см. LocalOrderRepository.applyKitchenWaiterEvent) - например "READY",
    // когда кухня отметила заказ готовым. null, пока такого события не
    // приходило (или для заказов, которые ещё не приняты сервером и не
    // имеют serverOrderId, к которому можно было бы привязать событие).
    // Обновляется только по факту WS-события, backend НЕ отдаёт этот статус
    // при обычном создании/изменении заказа.
    val kitchenStatus: String? = null,
    // Точное текстовое значение статуса, которое вернул backend в ответе
    // PATCH api/v1/orders/{orderId}/cancel (CancelOrderResponse.status) -
    // см. LocalOrderRepository.cancelOrder. null, пока заказ не отменялся.
    // Хранится "как есть", без интерпретации на клиенте (см. комментарий
    // у getOldOrders про status в OrderSummary - тот же принцип).
    val backendStatus: String? = null
)
