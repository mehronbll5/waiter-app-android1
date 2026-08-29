package com.waiterapp.ui.screens.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.waiterapp.R
import com.waiterapp.data.model.MenuItem
import com.waiterapp.ui.theme.BackgroundGray
import com.waiterapp.ui.theme.GreenPrimary
import com.waiterapp.ui.theme.TextDark
import com.waiterapp.viewmodel.MenuViewModel
import com.waiterapp.viewmodel.OrderViewModel

@Composable
fun MenuScreen(
    menuViewModel: MenuViewModel,
    orderViewModel: OrderViewModel,
    categoryId: Long?,
    onBack: () -> Unit,
    onGoToCart: () -> Unit
) {
    // При открытии экрана сразу применяем фильтр по категории, пришедшей из "Новый заказ".
    // categoryId == null (кнопка "ДОБАВИТЬ ЕЩЁ") - фильтр не применяется, показываются все блюда.
    LaunchedEffect(categoryId) {
        menuViewModel.selectCategory(categoryId)
    }

    // Название для заголовка берём из актуального списка categories, а не
    // передаём отдельной строкой через навигацию - так заголовок всегда
    // показывает текущее имя категории, даже если она была переименована,
    // пока экран уже открыт (см. forensic-аудит). Если категория была
    // удалена, categoryName будет null - заголовок покажет общий "МЕНЮ".
    val categoryName = categoryId?.let { id -> menuViewModel.categories.find { it.id == id }?.name }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGray)
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
                text = categoryName?.let { stringResource(R.string.menu_title, it) }
                    ?: stringResource(R.string.menu_title_all),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. Полноэкранный спиннер показываем ТОЛЬКО при первой загрузке (когда список пуст)
        if (menuViewModel.isLoading && menuViewModel.filteredItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = GreenPrimary)
            }
        }
        // 2. Если произошла ошибка и данных нет
        else if (menuViewModel.errorMessage != null && menuViewModel.filteredItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(menuViewModel.errorMessage ?: "", color = Color.Red)
            }
        }
        // 3. Во всех остальных случаях всегда показываем контент
        else {
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(menuViewModel.filteredItems) { index, item ->
                        MenuItemCard(
                            index = index,
                            item = item,
                            onAddToCart = {
                                orderViewModel.addToCart(item.id, item)
                            }
                        )
                    }
                }

                // Индикатор фоновой загрузки сверху (без скрытия экрана)
                if (menuViewModel.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = GreenPrimary
                    )
                }
            }
        }

        if (orderViewModel.totalItemsCount > 0) {
            Button(
                onClick = onGoToCart,
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp)
            ) {
                Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.menu_cart_button, orderViewModel.totalItemsCount),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MenuItemCard(index: Int, item: MenuItem, onAddToCart: () -> Unit) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(16.dp), clip = false)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            val localDrawableName = item.imageUrl?.removePrefix("drawable://")
            val localResId = localDrawableName?.let {
                val id = context.resources.getIdentifier(it, "drawable", context.packageName)
                if (id != 0) id else null
            }

            when {
                // Локальная картинка из res/drawable (пока нет фото с реального сервера)
                item.imageUrl?.startsWith("drawable://") == true && localResId != null -> {
                    Image(
                        painter = painterResource(id = localResId),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .background(BackgroundGray, RoundedCornerShape(12.dp))
                    )
                }
                // Настоящий URL с сервера (когда бэкенд начнёт присылать фото блюд)
                item.imageUrl != null -> {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .background(BackgroundGray, RoundedCornerShape(12.dp))
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(BackgroundGray, RoundedCornerShape(12.dp))
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(GreenPrimary, RoundedCornerShape(6.dp))
                    .align(Alignment.TopStart),
                contentAlignment = Alignment.Center
            ) {
                Text((index + 1).toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.Medium, color = TextDark)
            Spacer(modifier = Modifier.height(4.dp))
            Text("${"%.2f".format(item.price)}", color = TextDark, fontSize = 14.sp)
        }

        IconButton(onClick = onAddToCart) {
            Icon(Icons.Default.ShoppingCart, contentDescription = stringResource(R.string.menu_add_to_cart_content_description), tint = GreenPrimary)
        }
    }
}
