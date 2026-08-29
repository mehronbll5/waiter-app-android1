package com.waiterapp.ui.screens.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.waiterapp.R
import com.waiterapp.data.local.LocalOrderEntity
import com.waiterapp.data.model.LocalOrderItem
import com.waiterapp.data.model.LocalOrderStatus
import com.waiterapp.ui.theme.BackgroundGray
import com.waiterapp.ui.theme.GreenLight
import com.waiterapp.ui.theme.GreenPrimary
import com.waiterapp.ui.theme.GreenPrimaryDark
import com.waiterapp.ui.theme.RedBusy
import com.waiterapp.ui.theme.TextDark
import com.waiterapp.ui.theme.TextGray
import com.waiterapp.ui.util.toDisplayTableNumber
import com.waiterapp.viewmodel.OrdersViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Полноэкранный "чек" одного заказа - открывается тапом по карточке в
 * разделе "Заказы" (вместо разворачивания списка блюд прямо в списке).
 * Показывает состав заказа целиком и даёт перейти в редактирование.
 */
@Composable
fun OrderDetailScreen(
    viewModel: OrdersViewModel,
    orderId: Long,
    onBack: () -> Unit,
    onEdit: (LocalOrderEntity) -> Unit
) {
    LaunchedEffect(Unit) {
        viewModel.loadReceipts()
    }

    val receipt = viewModel.receipts.find { it.id == orderId }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    Scaffold(containerColor = BackgroundGray) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.clickable(onClick = onBack)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    stringResource(R.string.order_detail_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (receipt == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (viewModel.isLoading) {
                        CircularProgressIndicator(color = GreenPrimary)
                    } else {
                        Text(
                            stringResource(R.string.order_detail_not_found),
                            color = TextGray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            } else {
                OrderDetailContent(
                    receipt = receipt,
                    isSending = viewModel.sendingIds.contains(receipt.id),
                    isCancelling = viewModel.cancellingIds.contains(receipt.id),
                    onRetry = { viewModel.retrySend(receipt.id) },
                    onEdit = { onEdit(receipt) },
                    onDeleteRequest = { showDeleteConfirm = true },
                    onCancelRequest = { showCancelConfirm = true }
                )
            }
        }
    }

    if (showDeleteConfirm && receipt != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.orders_delete_confirm_title)) },
            text = { Text(stringResource(R.string.orders_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete(receipt.id)
                    onBack()
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

    if (showCancelConfirm && receipt != null) {
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
                    viewModel.cancelOrder(receipt.id)
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

    // Ошибка отмены (см. OrdersViewModel.cancelOrder) - заказ остаётся как
    // был, экран НЕ закрываем (в отличие от успешного delete выше), чтобы
    // официант видел, что заказ никуда не делся.
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

@Composable
private fun OrderDetailContent(
    receipt: LocalOrderEntity,
    isSending: Boolean,
    isCancelling: Boolean,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
    onCancelRequest: () -> Unit
) {
    val items = remember(receipt.itemsJson) {
        runCatching {
            Gson().fromJson(receipt.itemsJson, Array<LocalOrderItem>::class.java).toList()
        }.getOrDefault(emptyList())
    }
    val isSent = receipt.status == LocalOrderStatus.SENT.name && receipt.pendingAppendItemsJson.isNullOrBlank()
    val isCancelled = receipt.status == LocalOrderStatus.CANCELLED.name
    // Заказ реально существует на сервере - для него нужна настоящая отмена
    // через CANCEL API, а не локальное удаление (см. ReceiptCard в
    // OrdersScreen.kt и LocalOrderRepository.cancelOrder про тот же принцип).
    val hasServerOrder = receipt.serverOrderId != null
    val dateLabel = remember(receipt.createdAtMillis) {
        SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date(receipt.createdAtMillis))
    }
    val displayTableLabel = remember(receipt.tableNumbersCsv) {
        val numbers = receipt.tableNumbersCsv.split(",").filter { it.isNotBlank() }
        if (numbers.isEmpty()) {
            null
        } else {
            numbers.joinToString(", ") { it.trim().toDisplayTableNumber() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(12.dp), clip = false)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayTableLabel?.let { stringResource(R.string.orders_table_label, it) }
                        ?: stringResource(R.string.order_no_table_label_capitalized),
                    color = TextDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                StatusBadge(isSent = isSent, isSending = isSending, isCancelled = isCancelled)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(dateLabel, color = TextGray, fontSize = 12.sp)

            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))

            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.orders_receipt_line, item.quantity, item.name),
                        color = TextDark,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.orders_receipt_line_price, item.price * item.quantity),
                        color = TextDark,
                        fontSize = 15.sp
                    )
                }
            }

            if (!receipt.comment.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.orders_receipt_comment, receipt.comment),
                    color = TextGray,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.order_total_label), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    stringResource(R.string.orders_receipt_line_price, receipt.totalPrice),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isCancelled) {
            Button(
                onClick = onEdit,
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimaryDark),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.order_detail_edit_button), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        if (!isSent && !isCancelled) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRetry,
                enabled = !isSending,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, GreenPrimary),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.orders_retry_send_button))
                }
            }
        }

        // Заказ уже отменён - кнопки отмены/удаления больше не показываем,
        // отменять/удалять уже нечего (см. ReceiptCard в OrdersScreen.kt).
        if (!isCancelled) {
            Spacer(modifier = Modifier.height(8.dp))
            if (hasServerOrder) {
                // Заказ принят сервером - вместо удаления показываем реальную
                // отмену через CANCEL API (см. OrdersViewModel.cancelOrder).
                OutlinedButton(
                    onClick = onCancelRequest,
                    enabled = !isCancelling,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RedBusy),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RedBusy),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (isCancelling) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = RedBusy)
                    } else {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.orders_cancel_content_description))
                    }
                }
            } else {
                // Черновик ни разу не отправлялся на сервер - обычное
                // локальное удаление, как и раньше.
                OutlinedButton(
                    onClick = onDeleteRequest,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RedBusy),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RedBusy),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.orders_delete_content_description))
                }
            }
        }
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
