package com.waiterapp.fakes

import com.waiterapp.data.local.MenuDao
import com.waiterapp.data.local.MenuEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory реализация MenuDao для тестов - без реальной Room/SQLite.
 * storageFlow имитирует то, чем в реальной Room является @Query fun
 * observeAll(): Flow<...> - автоматически переиспускает список при любом
 * изменении storage, чтобы MenuViewModel/MenuRepository можно было тестировать
 * по той же цепочке Room -> Flow, что используется в проде.
 */
class FakeMenuDao : MenuDao {
    private val storage = mutableListOf<MenuEntity>()
    private val storageFlow = MutableStateFlow<List<MenuEntity>>(emptyList())

    override suspend fun getAll(): List<MenuEntity> = storage.toList()

    override fun observeAll(): Flow<List<MenuEntity>> = storageFlow

    override suspend fun insertAll(items: List<MenuEntity>) {
        storage.addAll(items)
        storageFlow.value = storage.toList()
    }

    override suspend fun clear() {
        storage.clear()
        storageFlow.value = storage.toList()
    }

    override suspend fun replaceAll(items: List<MenuEntity>) {
        clear()
        insertAll(items)
    }
}
