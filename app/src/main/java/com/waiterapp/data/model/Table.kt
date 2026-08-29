package com.waiterapp.data.model

/**
 * GET /api/v1/tables - бэкенд отдаёт "сырую" JPA-сущность CafeTable
 * (не отдельный DTO), поэтому в JSON реально приходят только id и number.
 *
 * Поле status в CafeTable.java на сервере ЗАКОММЕНТИРОВАНО - сервер его
 * не возвращает вообще. Оставляем поле здесь как nullable (по умолчанию
 * null = статус неизвестен), чтобы:
 *  1) UI (HallScreen) мог трактовать null как "свободен" по умолчанию;
 *  2) когда бэкенд включит статус обратно, не пришлось трогать модель -
 *     Gson просто начнёт присылать не-null значение.
 */
data class TableInfo(
    val id: Long,
    val number: String,
    val status: TableStatus? = null
)

object MockTableData {
    val sampleTables = listOf(
        TableInfo(1, "1", TableStatus.NOT_RESERVED),
        TableInfo(2, "2", TableStatus.NOT_RESERVED),
        TableInfo(3, "3", TableStatus.NOT_RESERVED),
        TableInfo(4, "4", TableStatus.NOT_RESERVED),
        TableInfo(5, "5", TableStatus.NOT_RESERVED),
        TableInfo(6, "6", TableStatus.NOT_RESERVED),
        TableInfo(7, "7", TableStatus.NOT_RESERVED),
        TableInfo(8, "8", TableStatus.NOT_RESERVED)
    )
}
