package com.waiterapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface LocalOrderDao {

    @Query("SELECT * FROM local_orders ORDER BY createdAtMillis DESC")
    suspend fun getAll(): List<LocalOrderEntity>

    @Query("SELECT * FROM local_orders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LocalOrderEntity?

    // Нужен, чтобы сматчить входящее WS-событие с топика "/topic/order/waiter"
    // (там только orderId - серверной id заказа) с локальной записью
    // "блокнота" (см. LocalOrderRepository.applyKitchenWaiterEvent).
    @Query("SELECT * FROM local_orders WHERE serverOrderId = :serverOrderId LIMIT 1")
    suspend fun getByServerOrderId(serverOrderId: Long): LocalOrderEntity?

    @Insert
    suspend fun insert(order: LocalOrderEntity): Long

    @Update
    suspend fun update(order: LocalOrderEntity)

    @Query("DELETE FROM local_orders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM local_orders")
    suspend fun clear()
}
