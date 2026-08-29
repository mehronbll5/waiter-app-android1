package com.waiterapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.waiterapp.data.model.TableInfo
import com.waiterapp.data.model.TableStatus

/**
 * Локальная копия списка столов для офлайн-режима.
 * Полностью перезаписывается (replaceAll) при каждом успешном
 * GET /api/v1/tables - так что здесь всегда лежит ровно та картина,
 * что была на сервере в момент последней удачной синхронизации: если
 * стол успели удалить, пока была связь, его тут уже не будет - а не
 * "восстановится" при следующем офлайн-запуске.
 *
 * status хранится как String? (имя enum-константы), т.к. Room без
 * TypeConverter не умеет enum напрямую; конвертация - в toTableInfo()/toEntity().
 */
@Entity(tableName = "tables")
data class TableEntity(
    @PrimaryKey val id: Long,
    val number: String,
    val status: String?
)

fun TableEntity.toTableInfo() = TableInfo(
    id = id,
    number = number,
    status = status?.let { runCatching { TableStatus.valueOf(it) }.getOrNull() }
)

fun TableInfo.toEntity() = TableEntity(
    id = id,
    number = number,
    status = status?.name
)
