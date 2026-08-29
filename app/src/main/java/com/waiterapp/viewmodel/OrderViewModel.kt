package com.waiterapp.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waiterapp.data.local.LocalOrderEntity
import com.waiterapp.data.model.LocalOrderItem
import com.waiterapp.data.model.LocalOrderStatus
import com.waiterapp.data.model.MenuItem
import com.waiterapp.data.repository.ApiResult
import com.waiterapp.data.repository.AuthRepository
import com.waiterapp.data.repository.LocalOrderRepository
import com.google.gson.Gson
import kotlinx.coroutines.launch

/**
 * Элемент корзины: блюдо + выбранное количество.
 * menuId - настоящий id блюда с сервера (GET /api/menu/all_dishes теперь
 * возвращает поле id явно, см. MenuScreen.kt).
 */
data class CartLine(
    val menuId: Long,
    val menuItem: MenuItem,
    val quantity: Int
)

/**
 * Куда должен уйти этот экран корзины при нажатии "Отправить"/"Сохранить":
 * - null: обычный НОВЫЙ заказ (как раньше) - saveDraft + попытка отправки.
 * - EditDraft: официант открыл ещё НЕ отправленный чек из "Заказы" и правит
 *   его целиком (можно менять количество уже добавленных блюд) - т.к. заказ
 *   ещё не создан на сервере, черновик просто пересохраняется полностью.
 * - AppendToSent: чек уже принят сервером (статус "отправлен") - корзина
 *   тут пустая с самого начала, в неё добавляются ТОЛЬКО новые блюда,
 *   которые уходят на сервер отдельным запросом "добавить позиции".
 */
sealed class EditTarget {
    data class EditDraft(val localOrderId: Long) : EditTarget()
    data class AppendToSent(val localOrderId: Long) : EditTarget()
}

class OrderViewModel(
    private val localOrderRepository: LocalOrderRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    var cart by mutableStateOf<List<CartLine>>(emptyList())
        private set
    var comment by mutableStateOf("")
        private set
    var isSubmitting by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var orderCreatedSuccessfully by mutableStateOf(false)
        private set
    var editTarget by mutableStateOf<EditTarget?>(null)
        private set

    val totalPrice: Double
        get() = cart.sumOf { it.menuItem.price * it.quantity }

    val totalItemsCount: Int
        get() = cart.sumOf { it.quantity }

    fun addToCart(menuId: Long, menuItem: MenuItem) {
        val existing = cart.find { it.menuId == menuId }
        cart = if (existing != null) {
            cart.map {
                if (it.menuId == menuId) it.copy(quantity = it.quantity + 1) else it
            }
        } else {
            cart + CartLine(menuId, menuItem, 1)
        }
    }

    fun incrementQuantity(menuId: Long) {
        cart = cart.map {
            if (it.menuId == menuId) it.copy(quantity = it.quantity + 1) else it
        }
    }

    fun decrementQuantity(menuId: Long) {
        cart = cart.mapNotNull {
            if (it.menuId == menuId) {
                if (it.quantity > 1) it.copy(quantity = it.quantity - 1) else null
            } else it
        }
    }

    fun onCommentChange(value: String) {
        comment = value
    }

    /**
     * Готовит корзину к правке заказа из раздела "Заказы".
     * Для ещё не отправленного чека (NOT_SENT) - подгружает уже выбранные
     * блюда в корзину, чтобы их количество тоже можно было поменять.
     * Для уже отправленного (SENT) - корзина остаётся пустой: в неё можно
     * только ДОБАВЛЯТЬ новые блюда, старые уже ушли на кухню и здесь не трогаются.
     */
    fun startEditingOrder(order: LocalOrderEntity) {
        val isSent = order.status == LocalOrderStatus.SENT.name
        cart = if (isSent) {
            emptyList()
        } else {
            val items = runCatching {
                Gson().fromJson(order.itemsJson, Array<LocalOrderItem>::class.java).toList()
            }.getOrDefault(emptyList())
            items.map {
                CartLine(
                    menuId = it.menuId,
                    menuItem = MenuItem(id = it.menuId, name = it.name, price = it.price, quantity = 0.0),
                    quantity = it.quantity
                )
            }
        }
        comment = order.comment ?: ""
        editTarget = if (isSent) EditTarget.AppendToSent(order.id) else EditTarget.EditDraft(order.id)
        errorMessage = null
    }

    /** Сбрасывает корзину и режим правки - вызывать при выходе с экрана без сохранения. */
    fun discardEditing() {
        cart = emptyList()
        comment = ""
        editTarget = null
        errorMessage = null
    }

    /**
     * Заказ ВСЕГДА сначала сохраняется локально (см. LocalOrderRepository) -
     * это и есть "блокнот": даже если дальше нет связи с сервером или он
     * не отвечает, заказ не теряется. Сразу же после сохранения делается
     * попытка реальной отправки; если она не удалась - заказ останется
     * в разделе "Заказы" со статусом "не отправлен" и кнопкой
     * "Отправить повторно".
     *
     * Если экран открыт для правки существующего заказа (editTarget != null) -
     * вместо создания нового чека обновляется/дополняется существующий
     * (см. startEditingOrder).
     */
    fun submitOrder(tableNumbers: List<String>) {
        if (authRepository.getStaffId() == null) {
            errorMessage = "Сессия не найдена, войдите заново"
            return
        }
        if (cart.isEmpty()) {
            errorMessage = "Добавьте хотя бы одно блюдо в заказ"
            return
        }

        val localItems = cart.map {
            LocalOrderItem(menuId = it.menuId, name = it.menuItem.name, price = it.menuItem.price, quantity = it.quantity)
        }
        val finalComment = comment.ifBlank { null }
        val finalTotal = totalPrice
        val target = editTarget

        viewModelScope.launch {
            isSubmitting = true
            errorMessage = null

            when (target) {
                is EditTarget.EditDraft -> {
                    localOrderRepository.updateDraftItems(
                        id = target.localOrderId,
                        tableNumbers = tableNumbers,
                        comment = finalComment,
                        items = localItems,
                        totalPrice = finalTotal
                    )
                    // Отправка "best effort", как и для обычного нового заказа.
                    localOrderRepository.trySend(target.localOrderId)
                    isSubmitting = false
                    orderCreatedSuccessfully = true
                    cart = emptyList()
                    comment = ""
                    editTarget = null
                }
                is EditTarget.AppendToSent -> {
                    // В отличие от остальных веток - тут результат реального
                    // PATCH-запроса нужно проверить: если он не удался, чек
                    // уже принят сервером и "сохранить в блокнот как есть"
                    // не вариант, поэтому корзину и editTarget НЕ очищаем,
                    // чтобы официант мог просто нажать "Сохранить" ещё раз.
                    when (val result = localOrderRepository.addItemsToSentOrder(target.localOrderId, localItems)) {
                        is ApiResult.Success -> {
                            isSubmitting = false
                            orderCreatedSuccessfully = true
                            cart = emptyList()
                            comment = ""
                            editTarget = null
                        }
                        is ApiResult.Error -> {
                            isSubmitting = false
                            errorMessage = result.message
                        }
                    }
                }
                null -> {
                    val localId = localOrderRepository.saveDraft(
                        tableNumbers = tableNumbers,
                        comment = finalComment,
                        items = localItems,
                        totalPrice = finalTotal
                    )
                    // Отправка "best effort": получится - отлично, не получится -
                    // заказ всё равно уже в безопасности в локальном блокноте.
                    localOrderRepository.trySend(localId)
                    isSubmitting = false
                    orderCreatedSuccessfully = true
                    cart = emptyList()
                    comment = ""
                    editTarget = null
                }
            }
        }
    }

    fun resetOrderCreatedFlag() {
        orderCreatedSuccessfully = false
    }
}
