package com.waiterapp.ui.util

/**
 * Сервер отдаёт номер стола как строку вида "T-01" (см. TableInfo.number) -
 * этот же формат используется как идентификатор в API (создание заказа
 * и т.п.), поэтому исходную строку менять нельзя.
 *
 * Для экрана же нужно показывать только цифру, без буквы и ведущих нулей:
 * "T-01" -> "1", "T-08" -> "8", "T-12" -> "12".
 *
 * Если в строке вообще нет цифр (неожиданный формат от сервера) -
 * возвращаем исходную строку как есть, чтобы ничего не потерять.
 */
fun String.toDisplayTableNumber(): String {
    val digitsOnly = filter { it.isDigit() }
    if (digitsOnly.isEmpty()) return this
    return digitsOnly.trimStart('0').ifEmpty { "0" }
}
