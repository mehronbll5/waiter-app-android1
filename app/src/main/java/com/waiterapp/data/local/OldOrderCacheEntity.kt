package com.waiterapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Локальный 24-часовой кэш вкладки "Старые" в разделе "Заказы" (см.
 * OrdersViewModel/LocalOrderRepository.getOldOrders). НЕ путать с
 * LocalOrderEntity ("блокнот" черновиков/отправленных заказов, который
 * питает вкладку "Новые" без изменений).
 *
 * Источник данных - реально существующий GET /api/v1/orders/my
 * (WaiterApiService.getMyOrders -> OrderSummary{orderId, status}).
 * Отдельного backend-эндпоинта для "старых"/"завершённых" заказов в
 * контракте нет, поэтому здесь кэшируется тот же список, что возвращает
 * этот эндпоинт целиком - см. подробный комментарий у
 * LocalOrderRepository.getOldOrders().
 *
 * status хранится как есть (непрозрачная строка с сервера, её значения
 * нигде в клиенте не описаны) - экран показывает его как текст, не
 * пытаясь угадать бизнес-смысл.
 *
 * cachedAtMillis - момент, когда этот список был последний раз получен
 * с сервера. TTL = 24 часа (см. LocalOrderRepository.OLD_ORDERS_CACHE_TTL_MILLIS).
 */
@Entity(tableName = "old_orders_cache")
data class OldOrderCacheEntity(
    @PrimaryKey val orderId: Long,
    val orderNumber: String,
    val status: String,
    val cachedAtMillis: Long
)
