package com.waiterapp.fakes

import com.waiterapp.data.local.TableDao
import com.waiterapp.data.local.TableEntity

/** In-memory реализация TableDao для тестов - без реальной Room/SQLite. */
class FakeTableDao : TableDao {
    private val storage = mutableListOf<TableEntity>()

    override suspend fun getAll(): List<TableEntity> = storage.toList()

    override suspend fun insertAll(items: List<TableEntity>) {
        storage.addAll(items)
    }

    override suspend fun clear() {
        storage.clear()
    }

    override suspend fun replaceAll(items: List<TableEntity>) {
        clear()
        insertAll(items)
    }
}
