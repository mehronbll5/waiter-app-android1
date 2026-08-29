package com.waiterapp.fakes

import com.waiterapp.data.local.OldOrderCacheDao
import com.waiterapp.data.local.OldOrderCacheEntity

/** In-memory реализация OldOrderCacheDao для тестов - без реальной Room/SQLite. */
class FakeOldOrderCacheDao : OldOrderCacheDao {
    private val storage = mutableListOf<OldOrderCacheEntity>()

    override suspend fun getAll(): List<OldOrderCacheEntity> = storage.toList()

    override suspend fun insertAll(items: List<OldOrderCacheEntity>) {
        storage.addAll(items)
    }

    override suspend fun clear() {
        storage.clear()
    }

    override suspend fun replaceAll(items: List<OldOrderCacheEntity>) {
        clear()
        insertAll(items)
    }
}
