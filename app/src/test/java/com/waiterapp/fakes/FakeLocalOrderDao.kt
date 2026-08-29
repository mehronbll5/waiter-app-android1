package com.waiterapp.fakes

import com.waiterapp.data.local.LocalOrderDao
import com.waiterapp.data.local.LocalOrderEntity

/** In-memory реализация LocalOrderDao для тестов - без реальной Room/SQLite. */
class FakeLocalOrderDao : LocalOrderDao {
    private val storage = mutableListOf<LocalOrderEntity>()
    private var nextId = 1L

    override suspend fun getAll(): List<LocalOrderEntity> =
        storage.sortedByDescending { it.createdAtMillis }

    override suspend fun getById(id: Long): LocalOrderEntity? =
        storage.find { it.id == id }

    override suspend fun getByServerOrderId(serverOrderId: Long): LocalOrderEntity? =
        storage.find { it.serverOrderId == serverOrderId }

    override suspend fun insert(order: LocalOrderEntity): Long {
        val withId = order.copy(id = nextId++)
        storage.add(withId)
        return withId.id
    }

    override suspend fun update(order: LocalOrderEntity) {
        val index = storage.indexOfFirst { it.id == order.id }
        if (index >= 0) storage[index] = order
    }

    override suspend fun deleteById(id: Long) {
        storage.removeAll { it.id == id }
    }

    override suspend fun clear() {
        storage.clear()
    }
}
