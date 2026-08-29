package com.waiterapp.ui.screens.hall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waiterapp.R
import com.waiterapp.data.model.TableInfo
import com.waiterapp.data.model.TableStatus
import com.waiterapp.ui.theme.*
import com.waiterapp.ui.util.toDisplayTableNumber
import com.waiterapp.viewmodel.HallViewModel

@Composable
fun HallScreen(
    viewModel: HallViewModel,
    onBack: () -> Unit,
    onNewOrder: () -> Unit,
    onTableClick: (List<String>) -> Unit
) {
    // Несколько столиков можно выбрать под один заказ (например, сдвинутые столы
    // для большой компании). Пока сервер не подтвердил реальные статусы столов
    // (isConnectedToServer == false), выбрать можно любой стол - мы ещё не знаем,
    // какие из них реально забронированы. После подключения выбор ограничивается
    // только свободными (NOT_RESERVED) столами.
    var selectedTables by remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGray)
    ) {
        TopBar(
            title = stringResource(R.string.hall_title),
            onBack = onBack,
            onRefresh = { viewModel.loadTables() }
        )

        Box(modifier = Modifier.weight(1f)) {
            if (viewModel.isLoading && viewModel.tables.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = GreenPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.hall_loading), color = TextGray, fontSize = 13.sp)
                }
            } else if (viewModel.tables.isEmpty() && viewModel.errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        viewModel.errorMessage ?: "",
                        color = Color.Red,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.loadTables() }) {
                        Text(stringResource(R.string.hall_retry_button))
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!viewModel.isConnectedToServer) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(GreenLight)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.hall_offline_hint),
                                color = GreenPrimaryDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(viewModel.tables) { table ->
                            // Бэкенд сейчас не отдаёт статус стола вообще (поле
                            // status закомментировано на сервере) - table.status
                            // приходит null. Пока нет подтверждённой связи с сервером
                            // ИЛИ статус неизвестен - выбрать можно любой стол.
                            val isSelectable = !viewModel.isConnectedToServer ||
                                (table.status ?: TableStatus.NOT_RESERVED) == TableStatus.NOT_RESERVED
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                TableCard(
                                    table = table,
                                    isConnectedToServer = viewModel.isConnectedToServer,
                                    isSelected = selectedTables.contains(table.number),
                                    isSelectable = isSelectable,
                                    onClick = {
                                        selectedTables = if (selectedTables.contains(table.number)) {
                                            selectedTables - table.number
                                        } else {
                                            selectedTables + table.number
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        val hasSelection = selectedTables.isNotEmpty()

        Button(
            onClick = {
                if (hasSelection) {
                    onTableClick(selectedTables.sorted())
                }
            },
            enabled = hasSelection,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasSelection) GreenPrimary else Color.White,
                disabledContainerColor = Color.White
            ),
            shape = RoundedCornerShape(24.dp),
            border = if (hasSelection) null else androidx.compose.foundation.BorderStroke(1.dp, GreenBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(52.dp)
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = if (hasSelection) Color.White else GreenPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (selectedTables.size > 1) {
                    stringResource(R.string.hall_new_order_button_multi, selectedTables.size)
                } else {
                    stringResource(R.string.hall_new_order_button)
                },
                color = if (hasSelection) Color.White else GreenPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
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
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        // Ручное обновление списка столов - раньше это делал только STOMP-топик
        // "/topic/table" (сейчас закомментирован в HallViewModel) и фоновый
        // опрос раз в 5 сек; кнопка даёт мгновенное обновление по требованию.
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = stringResource(R.string.refresh),
            modifier = Modifier.clickable(onClick = onRefresh)
        )
    }
}

@Composable
private fun TableCard(
    table: TableInfo,
    isConnectedToServer: Boolean,
    isSelected: Boolean,
    isSelectable: Boolean,
    onClick: () -> Unit
) {
    // Пока связь с сервером не подтверждена, реальный статус стола не известен -
    // показываем нейтральный "свободен"/зелёный по умолчанию, а не гадаем.
    // Как только сервер подтвердит статус (isConnectedToServer == true),
    // отображаем настоящий цвет: свободен/забронирован.
    val circleColor = when {
        !isConnectedToServer -> GreenPrimary
        (table.status ?: TableStatus.NOT_RESERVED) == TableStatus.NOT_RESERVED -> GreenPrimary
        else -> YellowSoon
    }

    // Сервер отдаёт номер стола как строку вида "T-01" - для отображения
    // оставляем только сами цифры, без буквы и ведущих нулей ("T-01" -> "1").
    // Сам table.number (с "T-01") не трогаем - он остаётся идентификатором
    // для API (создание заказа и т.п.), меняется только то, что видно на экране.
    val displayNumber = table.number.toDisplayTableNumber()

    val chairColor = circleColor.copy(alpha = 0.45f)
    val chairSize = 13.dp
    val chairInset = 1.dp // насколько "стул" заходит под кружок стола - смотрится единым кластером

    Box(
        modifier = Modifier
            .size(78.dp)
            .alpha(if (isSelectable) 1f else 0.5f),
        contentAlignment = Alignment.Center
    ) {
        // 4 маленьких кружка-"стула" вокруг стола - чисто декоративные,
        // не кликабельны сами по себе (клик работает на самом столе).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = chairInset)
                .size(chairSize)
                .clip(CircleShape)
                .background(chairColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -chairInset)
                .size(chairSize)
                .clip(CircleShape)
                .background(chairColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = chairInset)
                .size(chairSize)
                .clip(CircleShape)
                .background(chairColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = -chairInset)
                .size(chairSize)
                .clip(CircleShape)
                .background(chairColor)
        )

        // Сам стол - поверх "стульев".
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(circleColor)
                .then(
                    // Выбранный стол отмечаем контрастной обводкой.
                    if (isSelected) {
                        Modifier.border(3.dp, GreenPrimaryDark, CircleShape)
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = isSelectable, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(
                    text = displayNumber,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    }
}
