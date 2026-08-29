package com.waiterapp.ui.screens.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waiterapp.R
import com.waiterapp.data.local.LocalOrderEntity
import com.waiterapp.data.local.OldOrderCacheEntity
import com.waiterapp.data.model.LocalOrderStatus
import com.waiterapp.ui.theme.BackgroundGray
import com.waiterapp.ui.theme.GreenLight
import com.waiterapp.ui.theme.GreenPrimary
import com.waiterapp.ui.theme.RedBusy
import com.waiterapp.ui.theme.TextDark
import com.waiterapp.ui.theme.YellowSoon
import com.waiterapp.ui.util.toDisplayTableNumber
import com.waiterapp.ui.theme.TextGray
import com.waiterapp.viewmodel.OrdersTab
import com.waiterapp.viewmodel.OrdersViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Раздел "Заказы" = локальный "блокнот" (см. LocalOrderRepository):
 * каждый отправленный официантом заказ отображается здесь в виде чека
 * с составом блюд, независимо от того, дошёл он до сервера или нет.
 */
@Composable
fun OrdersScreen(
    viewModel: OrdersViewModel,
    onOrderClick: (LocalOrderEntity) -> Unit,
    onAddOrder: () -> Unit
) {
    // Обновляем список при каждом возврате на этот экран (например, после
    // того как официант добавил блюда к заказу и вернулся назад) - иначе
    // список показывал бы устаревшие данные до следующего ручного обновления.
    LaunchedEffect(Unit) {
        viewModel.loadReceipts()
    }

    Scaffold(
        containerColor = BackgroundGray,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddOrder,
                containerColor = GreenPrimary,
                contentColor = Color.White,
                shape = androidx.compose.foundation.shape.CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.orders_add_order_button))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BackgroundGray)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.orders_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // Ручное обновление списка чеков - раньше это делал только
                // STOMP-топик "/topic/order/waiter" (сейчас закомментирован в
                // OrdersViewModel) и фоновый опрос раз в 5 сек; кнопка даёт
                // мгновенное обновление по требованию. На вкладке "Старые"
                // обновляет 24-часовой локальный кэш принудительно (см.
                // OrdersViewModel.loadOldOrders(forceRefresh = true)).
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                    modifier = Modifier.clickable(onClick = {
                        if (viewModel.selectedTab == OrdersTab.OLD) {
                            viewModel.loadOldOrders(forceRefresh = true)
                        } else {
                            viewModel.loadReceipts()
                        }
                    })
                )
            }

            // --- [Новые] [Старые] ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OrdersTabButton(
                    label = stringResource(R.string.orders_tab_new),
                    isSelected = viewModel.selectedTab == OrdersTab.NEW,
                    onClick = { viewModel.selectTab(OrdersTab.NEW) },
                    modifier = Modifier.weight(1f)
                )
                OrdersTabButton(
                    label = stringResource(R.string.orders_tab_old),
                    isSelected = viewModel.selectedTab == OrdersTab.OLD,
                    onClick = { viewModel.selectTab(OrdersTab.OLD) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (viewModel.selectedTab == OrdersTab.NEW) {
                NewOrdersList(viewModel, onOrderClick)
            } else {
                OldOrdersList(viewModel)
            }
        }
    }

    // Ошибка отмены заказа (см. OrdersViewModel.cancelOrder) - показывается
    // поверх любой вкладки, сам заказ при этом остаётся в списке как был.
    if (viewModel.cancelErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCancelError() },
            title = { Text(stringResource(R.string.orders_cancel_error_title)) },
            text = { Text(viewModel.cancelErrorMessage ?: stringResource(R.string.orders_cancel_error_generic)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCancelError() }) {
                    Text(stringResource(R.string.ok_button))
                }
            }
        )
    }
}

/** Простая переключаемая кнопка-вкладка - без сторонних Tab-компонентов, чтобы не тянуть новые зависимости ради одной пары кнопок. */
@Composable
private fun OrdersTabButton(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                if (isSelected) GreenPrimary else BackgroundGray,
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (isSelected) Color.White else TextGray,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

/** Вкладка "Новые" - существующий список чеков-черновиков (блокнот), поведение не менялось. */
@Composable
private fun ColumnScope.NewOrdersList(
    viewModel: OrdersViewModel,
    onOrderClick: (LocalOrderEntity) -> Unit
) {
    when {
        viewModel.isLoading && viewModel.receipts.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GreenPrimary)
            }
        }
        viewModel.receipts.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.orders_empty),
                    color = TextGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        else -> {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(viewModel.receipts, key = { it.id }) { receipt ->
                    ReceiptCard(
                        receipt = receipt,
                        isSending = viewModel.sendingIds.contains(receipt.id),
                        isCancelling = viewModel.cancellingIds.contains(receipt.id),
                        onRetry = { viewModel.retrySend(receipt.id) },
                        onDelete = { viewModel.delete(receipt.id) },
                        onCancel = { viewModel.cancelOrder(receipt.id) },
                        onClick = { onOrderClick(receipt) }
                    )
                }
            }
        }
    }
}

/**
 * Вкладка "Старые" - см. OrdersViewModel.loadOldOrders/LocalOrderRepository.getOldOrders.
 * ВАЖНО: backend не предоставляет отдельного эндпоинта "старых"/"завершённых"
 * заказов с полным составом (стол/блюда/сумма) - только GET /api/v1/orders/my
 * (orderId + непрозрачный status). Поэтому карточка здесь заведомо более
 * скромная, чем ReceiptCard "Новых" - показывает то, что реально есть.
 */
@Composable
private fun ColumnScope.OldOrdersList(viewModel: OrdersViewModel) {
    when {
        viewModel.isLoadingOld && viewModel.oldOrders.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GreenPrimary)
            }
        }
        viewModel.oldOrdersError != null && viewModel.oldOrders.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(viewModel.oldOrdersError ?: "", color = Color.Red, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
        viewModel.oldOrders.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.orders_old_empty), color = TextGray, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
        else -> {
            Column(modifier = Modifier.weight(1f)) {
                // Заметная, но не блокирующая подсказка о том, что данные могут
                // быть из локального кэша (сеть недоступна или кэш ещё не истёк) -
                // никогда не выдаём кэш за "свежие данные с сервера" молча.
                if (viewModel.isShowingCachedOldOrders) {
                    Text(
                        if (viewModel.oldOrdersError != null) {
                            stringResource(R.string.orders_old_error_with_cache_hint)
                        } else {
                            stringResource(R.string.orders_old_cached_notice)
                        },
                        color = TextGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(viewModel.oldOrders, key = { it.orderId }) { order ->
                        OldOrderCard(order)
                    }
                }
            }
        }
    }
}

@Composable
private fun OldOrderCard(order: OldOrderCacheEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), clip = false)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            stringResource(
                    R.string.orders_old_order_number,
                    order.orderNumber
                ),
            color = TextDark,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        // status - непрозрачная строка с бэкенда (см. OldOrderCacheEntity),
        // показываем как есть, без попытки перевести/интерпретировать.
        Text(
            stringResource(R.string.orders_old_status_label, order.status),
            color = TextGray,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ReceiptCard(
    receipt: LocalOrderEntity,
    isSending: Boolean,
    isCancelling: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onClick: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    val isSent = receipt.status == LocalOrderStatus.SENT.name && receipt.pendingAppendItemsJson.isNullOrBlank()
    val isCancelled = receipt.status == LocalOrderStatus.CANCELLED.name
    // Заказ реально существует на сервере (serverOrderId != null) - для
    // такого нельзя просто "удалить" локально, нужна настоящая отмена
    // через CANCEL API (см. LocalOrderRepository.cancelOrder). Для черновика,
    // который ни разу не был принят сервером, отменять нечего - там остаётся
    // обычное локальное удаление.
    val hasServerOrder = receipt.serverOrderId != null
    val dateLabel = remember(receipt.createdAtMillis) {
        SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date(receipt.createdAtMillis))
    }

    // Вся карточка - это "превью чека": тап по любому месту (кроме кнопок
    // статуса/удаления) открывает полноэкранный чек этого заказа с
    // полным составом и возможностью редактирования.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), clip = false)
            .background(Color.White, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val displayTableLabel = remember(receipt.tableNumbersCsv) {
                val numbers = receipt.tableNumbersCsv.split(",").filter { it.isNotBlank() }
                if (numbers.isEmpty()) null else numbers.joinToString(", ") { it.trim().toDisplayTableNumber() }
            }
            Text(
                text = displayTableLabel?.let { stringResource(R.string.orders_table_label, it) }
                    ?: stringResource(R.string.order_no_table_label_capitalized),
                color = TextDark,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            StatusBadge(isSent = isSent, isSending = isSending, isCancelled = isCancelled)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dateLabel, color = TextGray, fontSize = 12.sp)
            // Приходит асинхронно через "/topic/order/waiter" (кухня отметила
            // заказ готовым) - см. OrdersViewModel.observeKitchenEvents.
            // "READY" сравнивается без учёта регистра, т.к. на бэкенде это
            // значение приходит из двух разных DTO с несогласованными типами
            // (String и enum OrderStatus) - см. LocalOrderRepository.
            if (receipt.kitchenStatus?.equals("READY", ignoreCase = true) == true) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(YellowSoon.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        stringResource(R.string.orders_status_kitchen_ready),
                        color = YellowSoon,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Divider()
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.orders_receipt_total, receipt.totalPrice),
                color = TextDark,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )

            Row {
                if (!isSent && !isCancelled) {
                    TextButton(onClick = onRetry, enabled = !isSending) {
                        if (isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.orders_retry_send_button))
                        }
                    }
                }
                // Заказ уже отменён - показывать кнопку отмены/удаления
                // больше не нужно, отменять/удалять уже нечего.
                if (!isCancelled) {
                    if (hasServerOrder) {
                        // Заказ принят сервером - "ведро" заменено на кнопку
                        // отмены, вызывающую реальный CANCEL API (см.
                        // OrdersViewModel.cancelOrder).
                        IconButton(onClick = { showCancelConfirm = true }, enabled = !isCancelling) {
                            if (isCancelling) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = RedBusy)
                            } else {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = stringResource(R.string.orders_cancel_content_description),
                                    tint = RedBusy
                                )
                            }
                        }
                    } else {
                        // Черновик ни разу не отправлялся на сервер - нечего
                        // отменять на бэкенде, локальное удаление уместно как раньше.
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.orders_delete_content_description), tint = RedBusy)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.orders_delete_confirm_title)) },
            text = { Text(stringResource(R.string.orders_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.orders_delete_content_description), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.orders_cancel_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.orders_cancel_confirm_message,
                        receipt.serverOrderId ?: 0L
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    onCancel()
                }) {
                    Text(stringResource(R.string.orders_cancel_confirm_button), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun StatusBadge(isSent: Boolean, isSending: Boolean, isCancelled: Boolean = false) {
    // Отменённый заказ - отдельный, самодостаточный статус (см.
    // LocalOrderRepository.cancelOrder): не показываем его вместе с
    // "оплачен/не оплачен".
    if (isCancelled) {
        Box(
            modifier = Modifier
                .background(Color(0xFFFDECEC), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                stringResource(R.string.orders_status_cancelled),
                color = RedBusy,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    val sentLabel = when {
        isSending -> stringResource(R.string.orders_status_sending)
        isSent -> stringResource(R.string.orders_status_sent)
        else -> stringResource(R.string.orders_status_not_sent)
    }
    // Статус оплаты для этого ("Новые") списка сервер нигде не отдаёт -
    // здесь только локальный "блокнот" отправки (NOT_SENT/SENT), а не
    // платёжный статус. Приложение официанта НЕ имеет права само решать,
    // что чек оплачен: пока backend не пришлёт реальный статус оплаты по
    // этому заказу, бейдж всегда показывает "не оплачено" и не реагирует
    // на тап - ни один локальный чек здесь не может стать "Оплачено" сам
    // по себе (см. forensic-задание про PAID).
    val paidLabel = stringResource(R.string.orders_status_not_paid)
    // Зелёный цвет теперь зависит только от статуса отправки - платёжный
    // статус тут не участвует, т.к. он не приходит с сервера для этого списка.
    val bg = if (isSent || isSending) GreenLight else Color(0xFFFDECEC)
    val textColor = if (isSent || isSending) GreenPrimary else RedBusy

    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            "$sentLabel / $paidLabel",
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End
        )
    }
}
