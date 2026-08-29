package com.waiterapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.waiterapp.data.model.MenuItem

/**
 * Локальная копия блюда меню для офлайн-режима.
 * Заполняется каждый раз, когда меню успешно загружено с сервера,
 * и используется как fallback, если в следующий раз сети не будет.
 *
 * categoryId/categoryName возвращены обратно после того, как на бэкенде
 * поправили MenuMapperImpl - GET /api/v1/menus снова отдаёт категорию блюда
 * (см. комментарий в data/model/Menu.kt).
 */
@Entity(tableName = "menu_items")
data class MenuEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val price: Double,
    val quantity: Double,
    val imageUrl: String?,
    val categoryId: Long?,
    val categoryName: String?
)

fun MenuEntity.toMenuItem() = MenuItem(
    id = id,
    name = name,
    price = price,
    quantity = quantity,
    imageUrl = imageUrl,
    categoryId = categoryId,
    categoryName = categoryName
)

fun MenuItem.toEntity() = MenuEntity(
    id = id,
    name = name,
    price = price,
    quantity = quantity,
    imageUrl = imageUrl,
    categoryId = categoryId,
    categoryName = categoryName
)
