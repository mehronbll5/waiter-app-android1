package com.waiterapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface OldOrderCacheDao {

    @Query("SELECT * FROM old_orders_cache ORDER BY orderId DESC")
    suspend fun getAll(): List<OldOrderCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<OldOrderCacheEntity>)

    // ВАЖНО: это ЧИСТО локальная очистка SQLite-таблицы - здесь нет и не
    // может быть обращения к backend. Используется только когда TTL кэша
    // истёк и мы собираемся положить свежий список (см. replaceAll ниже)
    // или когда список заказов на сервере оказался пуст. Backend-заказы
    // этот метод никак не удаляет и не отменяет (см. forensic-задание,
    // часть 10.2/10.3 - LOCAL DELETE ≠ SERVER DELETE, здесь для этого
    // вообще нет технической возможности: WaiterApiService не содержит
    // ни одного DELETE-эндпоинта для заказов).
    @Query("DELETE FROM old_orders_cache")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<OldOrderCacheEntity>) {
        clear()
        insertAll(items)
    }
}
