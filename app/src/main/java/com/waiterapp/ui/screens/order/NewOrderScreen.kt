package com.waiterapp.ui.screens.order

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waiterapp.R
import com.waiterapp.data.model.CategoryInfo
import com.waiterapp.ui.theme.BackgroundGray
import com.waiterapp.ui.theme.GreenBorder
import com.waiterapp.ui.util.toDisplayTableNumber
import com.waiterapp.ui.theme.GreenPrimary
import com.waiterapp.ui.theme.GreenPrimaryDark
import com.waiterapp.ui.theme.TextDark
import com.waiterapp.ui.theme.TextGray
import com.waiterapp.viewmodel.CartLine
import com.waiterapp.viewmodel.MenuViewModel
import com.waiterapp.viewmodel.OrderViewModel

/**
 * Главный экран заказа (соответствует макету "НОВЫЙ ЗАКАЗ (Стол 3)").
 * Верхняя часть - горизонтально прокручиваемые категории (не скроллится вместе
 * со списком корзины ниже). Тап по категории открывает MenuScreen с фильтром.
 * Нижняя часть - список выбранных блюд, примечание, тотал, кнопка "Отправить".
 */
@Composable
fun NewOrderScreen(
    viewModel: OrderViewModel,
    menuViewModel: MenuViewModel,
    tableNumbers: List<String>,
    isEditing: Boolean = false,
    onBack: () -> Unit,
    onCategoryClick: (CategoryInfo) -> Unit,
    onAddMoreClick: () -> Unit,
    onOrderSubmitted: () -> Unit
) {
    LaunchedEffect(viewModel.orderCreatedSuccessfully) {
        if (viewModel.orderCreatedSuccessfully) {
            viewModel.resetOrderCreatedFlag()
            onOrderSubmitted()
        }
    }

    // Перезапрашиваем категории каждый раз при входе на этот экран - иначе
    // категория, добавленная снаружи приложения (Swagger, curl, другой
    // клиент) пока MenuViewModel уже был создан, не появится в CategoryRow
    // до перезапуска приложения. MenuViewModel хоть и общий на весь nav-граф
    // (см. AppNavHost), но сам список categories обновляется только здесь
    // и локально - при успешном создании категории прямо в приложении
    // (см. MenuViewModel.createCategory).
    LaunchedEffect(Unit) {
        menuViewModel.loadCategories()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGray)
    ) {
        // --- Шапка ---
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
            val displayTableNumbers = tableNumbers.joinToString(", ") { it.toDisplayTableNumber() }
            Text(
                text = when {
                    tableNumbers.isEmpty() && isEditing -> stringResource(R.string.order_edit_title_no_table)
                    tableNumbers.isEmpty() -> stringResource(R.string.order_title_no_table)
                    isEditing -> stringResource(R.string.order_edit_title, displayTableNumbers)
                    else -> stringResource(R.string.order_title, displayTableNumbers)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // --- Категории: фиксированный блок, прокручивается только сам ряд по горизонтали ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(vertical = 12.dp)
        ) {
            CategoryRow(
                categories = menuViewModel.categories,
                onCategoryClick = onCategoryClick
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Нижняя часть: корзина + примечание + тотал, скроллится независимо от категорий ---
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedButton(
                onClick = onAddMoreClick,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, GreenPrimary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimary),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.order_add_more_button), fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(viewModel.cart) { line ->
                    CartLineRow(
                        line = line,
                        onIncrement = { viewModel.incrementQuantity(line.menuId) },
                        onDecrement = { viewModel.decrementQuantity(line.menuId) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(stringResource(R.string.order_kitchen_note_label), fontSize = 12.sp, color = TextGray, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = viewModel.comment,
                onValueChange = viewModel::onCommentChange,
                placeholder = { Text(stringResource(R.string.order_kitchen_note_placeholder)) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.order_total_label), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "%.2f".format(viewModel.totalPrice),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (viewModel.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(viewModel.errorMessage ?: "", color = Color.Red, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = { viewModel.submitOrder(tableNumbers) },
            enabled = !viewModel.isSubmitting,
            colors = ButtonDefaults.buttonColors(
                containerColor = GreenPrimaryDark,
                disabledContainerColor = GreenPrimaryDark.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (viewModel.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (isEditing) stringResource(R.string.order_save_button) else stringResource(R.string.order_submit_button),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Горизонтальный прокручиваемый ряд категорий. Категории приходят с сервера
 * (GET /api/v1/categories) - в отличие от старого захардкоженного enum,
 * их названия произвольные, поэтому у каждой нет заранее нарисованной
 * иконки: вместо картинки показываем цветной кружок с первой буквой имени
 * категории, как аватарку.
 */
@Composable
private fun CategoryRow(
    categories: List<CategoryInfo>,
    onCategoryClick: (CategoryInfo) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(categories, key = { it.id }) { category ->
            CategoryItem(
                label = category.name,
                onClick = { onCategoryClick(category) }
            )
        }
    }
}

@Composable
private fun CategoryItem(
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, GreenBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Первая буква имени категории вместо иконки - у произвольных
            // категорий с сервера нет заранее подготовленной картинки.
            Text(
                text = label.trim().take(1).uppercase(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimary
            )
        }
        if (label.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextDark)
        }
    }
}

@Composable
private fun CartLineRow(line: CartLine, onIncrement: () -> Unit, onDecrement: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), clip = false)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.order_cart_line, line.menuItem.name, line.quantity), color = TextDark)

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onIncrement, modifier = Modifier.size(28.dp)) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(GreenPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.order_increment_content_description), tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onDecrement, modifier = Modifier.size(28.dp)) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(GreenPrimaryDark, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.order_decrement_content_description), tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
