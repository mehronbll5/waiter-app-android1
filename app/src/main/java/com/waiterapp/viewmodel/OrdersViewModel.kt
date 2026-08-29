package com.waiterapp.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waiterapp.data.local.LocalOrderEntity
import com.waiterapp.data.local.OldOrderCacheEntity
import com.waiterapp.data.network.ApiConfig
import com.waiterapp.data.repository.ApiResult
import com.waiterapp.data.repository.LocalOrderRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Тот же принцип, что и на карте зала: список чеков сам обновляется по
// таймеру, чтобы статус "отправлен"/"оплачен" не требовал ручного обновления.
private const val AUTO_REFRESH_INTERVAL_MS = 5_000L

/** Вкладка раздела "Заказы" - см. OrdersScreen. */
enum class OrdersTab { NEW, OLD }

/**
 * Раздел "Заказы" = локальный "блокнот" (см. LocalOrderRepository):
 * список чеков, которые официант отправлял, независимо от того,
 * дошли они реально до сервера или нет. Для непринятых сервером
 * заказов доступна кнопка "Отправить повторно".
 */
class OrdersViewModel(private val localOrderRepository: LocalOrderRepository) : ViewModel() {

    var receipts by mutableStateOf<List<LocalOrderEntity>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    // id чеков, для которых прямо сейчас идёт (повторная) отправка -
    // чтобы показать крутилку именно на нужной карточке, а не на всём экране.
    var sendingIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    // id чеков, для которых прямо сейчас идёт отмена (см. cancelOrder) -
    // отдельный набор от sendingIds, чтобы крутилки не путались, если
    // вдруг обе операции запустят на одной карточке одновременно.
    var cancellingIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    // Сообщение об ошибке ПОСЛЕДНЕЙ неудачной попытки отмены заказа -
    // показывается диалогом поверх экрана (см. OrdersScreen/OrderDetailScreen).
    // Заказ при этом остаётся в списке как был - см. cancelOrder ниже и
    // LocalOrderRepository.cancelOrder.
    var cancelErrorMessage by mutableStateOf<String?>(null)
        private set

    // --- Вкладка "Старые" (см. LocalOrderRepository.getOldOrders) ---
    // "Новые" (receipts выше) не тронуты - вся существующая логика
    // блокнота/отправки заказов работает как раньше.
    var selectedTab by mutableStateOf(OrdersTab.NEW)
        private set
    var oldOrders by mutableStateOf<List<OldOrderCacheEntity>>(emptyList())
        private set
    var isLoadingOld by mutableStateOf(false)
        private set
    var oldOrdersError by mutableStateOf<String?>(null)
        private set
    var isShowingCachedOldOrders by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            localOrderRepository.seedSampleDataIfEmpty()
            loadReceipts()
        }
        startAutoRefresh()
        observeKitchenEvents()
    }

    /**
     * Слушает "/topic/order/waiter" - реального времени оповещение "кухня
     * отметила заказ готовым" (см. LocalOrderRepository.applyKitchenWaiterEvent).
     * Фоновый опрос по таймеру (startAutoRefresh) сам по себе этот статус
     * никогда бы не подтянул - сервер его нигде не отдаёт через REST,
     * только через этот WS-топик.
     */
    private fun observeKitchenEvents() {
        if (ApiConfig.DEMO_MODE_ENABLED) return
        viewModelScope.launch {
            localOrderRepository.observeKitchenWaiterEvents().collect { raw ->
                localOrderRepository.applyKitchenWaiterEvent(raw)
                loadReceipts()
            }
        }
    }

    fun loadReceipts() {
        viewModelScope.launch {
            isLoading = true
            receipts = localOrderRepository.getAll()
            isLoading = false
        }
    }

    /** Фоновое обновление без крутилки - см. аналогичный метод в HallViewModel. */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                receipts = localOrderRepository.getAll()
            }
        }
    }

    fun retrySend(receiptId: Long) {
        viewModelScope.launch {
            sendingIds = sendingIds + receiptId
            val receipt = receipts.find { it.id == receiptId }
            if (receipt?.serverOrderId != null && !receipt.pendingAppendItemsJson.isNullOrBlank()) {
                // Это не новый заказ целиком, а блюда, добавленные позже к уже
                // принятому серверу заказу и не дошедшие с первого раза.
                localOrderRepository.retryAppend(receiptId)
            } else {
                localOrderRepository.trySend(receiptId)
            }
            sendingIds = sendingIds - receiptId
            loadReceipts()
        }
    }

    fun delete(receiptId: Long) {
        viewModelScope.launch {
            localOrderRepository.delete(receiptId)
            loadReceipts()
        }
    }

    /**
     * Отмена заказа (кнопка "Отменить заказ" вместо прежнего "удалить" -
     * см. LocalOrderRepository.cancelOrder про разницу CANCEL/DELETE).
     * При успехе локальная запись остаётся в списке (просто со статусом
     * CANCELLED - см. loadReceipts ниже), при ошибке заказ не трогается
     * и показывается [cancelErrorMessage].
     */
    fun cancelOrder(receiptId: Long) {
        viewModelScope.launch {
            cancellingIds = cancellingIds + receiptId
            when (val result = localOrderRepository.cancelOrder(receiptId)) {
                is ApiResult.Success -> loadReceipts()
                is ApiResult.Error -> cancelErrorMessage = result.message
            }
            cancellingIds = cancellingIds - receiptId
        }
    }

    fun dismissCancelError() {
        cancelErrorMessage = null
    }

    // ВАЖНО: специально НЕТ метода togglePaid()/setPaid() и т.п. -
    // приложение официанта не имеет права само устанавливать статус
    // оплаты заказа (см. forensic-задание про PAID). Раньше здесь был
    // togglePaid(), который просто переключал LocalOrderEntity.isPaid по
    // тапу на бейджике, без единого обращения к серверу - именно из-за
    // него UI мог показать "Оплачено" для NOT_SENT/SENT заказа. Пока
    // backend не отдаёт реальный платёжный статус по заказу ни в одном
    // существующем эндпоинте, экраны "Заказы"/чек ВСЕГДА показывают
    // "Не оплачено" для вкладки "Новые" (см. StatusBadge в
    // OrdersScreen.kt/OrderDetailScreen.kt) - когда backend начнёт
    // присылать реальный payment status, брать его оттуда, а не
    // изобретать локально.

    /**
     * Переключение вкладки [Новые]/[Старые] (см. forensic-задание, часть 9).
     * При первом открытии "Старые" сразу подгружаем данные (кэш или сеть -
     * решает LocalOrderRepository.getOldOrders по TTL).
     */
    fun selectTab(tab: OrdersTab) {
        selectedTab = tab
        if (tab == OrdersTab.OLD && oldOrders.isEmpty() && !isLoadingOld) {
            loadOldOrders()
        }
    }

    /**
     * forceRefresh = true - кнопка "Обновить" на вкладке "Старые": игнорирует
     * ещё не истёкший 24-часовой кэш и идёт в сеть заново.
     */
    fun loadOldOrders(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            isLoadingOld = true
            oldOrdersError = null
            when (val result = localOrderRepository.getOldOrders(forceRefresh)) {
                is ApiResult.Success -> {
                    isLoadingOld = false
                    oldOrders = result.data
                    isShowingCachedOldOrders = result.fromCache
                }
                is ApiResult.Error -> {
                    isLoadingOld = false
                    oldOrdersError = result.message
                }
            }
        }
    }
}
