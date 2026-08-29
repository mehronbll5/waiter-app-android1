package com.waiterapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TableDao {

    @Query("SELECT * FROM tables")
    suspend fun getAll(): List<TableEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TableEntity>)

    @Query("DELETE FROM tables")
    suspend fun clear()

    /**
     * Полностью заменяет кэш свежими данными с сервера (в одной транзакции,
     * чтобы между delete и insert не оказался пустой список, если экран
     * в этот момент читает кэш). Это же и есть механизм отражения удалений:
     * если стола больше нет в свежем списке с сервера, после clear()+insertAll()
     * его не будет и в кэше - отдельная логика "удалить одну запись" не нужна.
     */
    @Transaction
    suspend fun replaceAll(items: List<TableEntity>) {
        clear()
        insertAll(items)
    }
}
