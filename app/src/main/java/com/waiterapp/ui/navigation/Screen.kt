package com.waiterapp.ui.navigation

import android.net.Uri

// Специальное значение категории для маршрута Menu - означает "без фильтра,
// показать весь список блюд" (используется кнопкой "ДОБАВИТЬ ЕЩЁ" в
// NewOrderScreen, в отличие от тапа по конкретной иконке категории).
// Маршрут передаёт categoryId (Long), а не имя категории (см. forensic-аудит:
// имя нестабильно, id - нет), поэтому и метка "без фильтра" теперь тоже Long -
// тот же приём, что уже применялся для editOrderId ниже (сентинел -1L вместо
// nullable-аргумента, т.к. Navigation Compose не различает "аргумент не
// передан" от "аргумента нет в маршруте" так просто, как хотелось бы).
const val MENU_CATEGORY_ALL_ID = -1L

// Заглушка для сегмента {tableNumbers} в маршруте, когда список столов пуст
// (например, заказ создаётся без стола через кнопку "+" в разделе "Заказы").
// ВАЖНО: пустая строка здесь недопустима - Navigation Compose сопоставляет
// каждый сегмент "{name}" через regex, требующий минимум один символ.
// Если передать tableNumbers.joinToString(",") от пустого списка, получится
// маршрут вида "new_order/" (пустой сегмент), который не матчится ни с одним
// destination -> IllegalArgumentException -> краш всего приложения. Поэтому
// пустой список кодируется этой меткой, а на приёме разворачивается обратно
// в emptyList().
private const val NO_TABLES = "none"

// ВАЖНО: номера столов и, особенно, название категории приходят с реального
// бэкенда и могут содержать пробелы или другие спецсимволы (например,
// "Горячие напитки", "Кофе/чай"). Navigation Compose разбирает route как
// URI-шаблон и сопоставляет сегменты по regex - непроэкранированные пробелы
// и "/" ломают это сопоставление, из-за чего navigate() кидает
// IllegalArgumentException ("Navigation destination that matches route ...
// cannot be found") и всё приложение крашится. Раньше здесь подставляли имя
// категории в маршрут "как есть" - с мок-категориями без пробелов (см.
// MenuViewModel.mockCategories) это не проявлялось, а с настоящими
// категориями с сервера - падало сразу при заходе в категорию. Кодируем
// Uri.encode()/decode() на входе и выходе, чтобы спецсимволы не ломали
// сопоставление маршрута.
private fun encodeTableNumbers(tableNumbers: List<String>): String =
    if (tableNumbers.isEmpty()) NO_TABLES else Uri.encode(tableNumbers.joinToString(","))

internal fun decodeTableNumbers(raw: String?): List<String> =
    if (raw.isNullOrBlank() || raw == NO_TABLES) {
        emptyList()
    } else {
        Uri.decode(raw).split(",").filter { it.isNotBlank() }
    }

sealed class Screen(val route: String) {
    // Промежуточный экран стартовой проверки авторизации (см. AppNavHost).
    // Показывается ПЕРВЫМ при обычном запуске приложения, пока идёт
    // POST /auth/auto - Hall не появляется, пока проверка не завершится.
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Hall : Screen("hall")
    // {categoryId} - раньше здесь было имя категории (String, требовавшее
    // Uri.encode/decode из-за пробелов/спецсимволов/кириллицы). Long не
    // нуждается в таком экранировании и, в отличие от имени, не ломается
    // при переименовании категории на сервере (см. forensic-аудит).
    object Menu : Screen("menu/{tableNumbers}/{categoryId}") {
        fun createRoute(tableNumbers: List<String>, categoryId: Long) =
            "menu/${encodeTableNumbers(tableNumbers)}/$categoryId"
    }
    object NewOrder : Screen("new_order/{tableNumbers}?editOrderId={editOrderId}") {
        fun createRoute(tableNumbers: List<String>, editOrderId: Long? = null): String {
            val base = "new_order/${encodeTableNumbers(tableNumbers)}"
            return if (editOrderId != null) "$base?editOrderId=$editOrderId" else base
        }
    }
    // Экран-чек: открывается тапом по заказу в разделе "Заказы", показывает
    // полный состав заказа и даёт перейти в редактирование (NewOrder).
    object OrderDetail : Screen("order_detail/{orderId}") {
        fun createRoute(orderId: Long) = "order_detail/$orderId"
    }
}
