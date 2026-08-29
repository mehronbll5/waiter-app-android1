package com.waiterapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuDao {

    @Query("SELECT * FROM menu_items")
    suspend fun getAll(): List<MenuEntity>

    /**
     * "Горячий" источник правды для UI: Room сам уведомляет всех подписчиков
     * этого запроса, когда таблица menu_items меняется (insert/delete/replaceAll),
     * без ручного повторного вызова. Именно на этот Flow подписывается
     * MenuViewModel - конечное звено цепочки
     * STOMP /topic/menu -> MenuRepository -> REST -> Room -> Flow -> ViewModel -> Compose.
     */
    @Query("SELECT * FROM menu_items")
    fun observeAll(): Flow<List<MenuEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MenuEntity>)

    @Query("DELETE FROM menu_items")
    suspend fun clear()

    /**
     * Полностью заменяет кэш свежими данными с сервера (в одной транзакции,
     * чтобы между delete и insert не оказался пустой список, если экран
     * в этот момент читает кэш).
     */
    @Transaction
    suspend fun replaceAll(items: List<MenuEntity>) {
        clear()
        insertAll(items)
    }
}
