package com.waiterapp.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.waiterapp.ui.navigation.BottomNavItem
import com.waiterapp.ui.theme.GreenContainer
import com.waiterapp.ui.theme.GreenPrimary
import com.waiterapp.ui.theme.SurfaceWhite
import com.waiterapp.ui.theme.TextGray
import com.waiterapp.data.local.LocalOrderEntity
import com.waiterapp.ui.screens.hall.HallScreen
import com.waiterapp.ui.screens.orders.OrdersScreen
import com.waiterapp.ui.screens.profile.ProfileScreen
import com.waiterapp.viewmodel.HallViewModel
import com.waiterapp.viewmodel.OrdersViewModel
import com.waiterapp.viewmodel.ProfileViewModel
import com.waiterapp.viewmodel.ViewModelFactory

/**
 * Обёртка с нижней навигацией из трёх вкладок (ГЛАВНАЯ / ЗАКАЗЫ / ПРОФИЛЬ).
 * Переключение вкладок - локальное состояние (без отдельного back stack),
 * как в большинстве приложений с нижней навигацией: назад с вкладки
 * не "проматывает" историю кликов по вкладкам.
 *
 * "Новый заказ" (кнопка на карте зала или тап по столу) уводит на отдельный
 * полноэкранный флоу через ВНЕШНИЙ NavController (см. AppNavHost) - поэтому
 * здесь только колбэк onNavigateToNewOrder, а не сама навигация.
 */
@Composable
fun MainScreen(
    factory: ViewModelFactory,
    onNavigateToNewOrder: (tableNumbers: List<String>, editOrderId: Long?) -> Unit,
    onOrderClick: (LocalOrderEntity) -> Unit,
    onLoggedOut: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(BottomNavItem.HOME) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceWhite,
                tonalElevation = 3.dp
            ) {
                BottomNavItem.entries.forEach { item ->
                    NavigationBarItem(
                        selected = selectedTab == item,
                        onClick = { selectedTab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelResId)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GreenPrimary,
                            selectedTextColor = GreenPrimary,
                            indicatorColor = GreenContainer,
                            unselectedIconColor = TextGray,
                            unselectedTextColor = TextGray
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                BottomNavItem.HOME -> {
                    val hallViewModel: HallViewModel = viewModel(factory = factory)
                    HallScreen(
                        viewModel = hallViewModel,
                        onBack = {},
                        onNewOrder = { onNavigateToNewOrder(emptyList(), null) },
                        onTableClick = { tableNumbers -> onNavigateToNewOrder(tableNumbers, null) }
                    )
                }
                BottomNavItem.ORDERS -> {
                    val ordersViewModel: OrdersViewModel = viewModel(factory = factory)
                    OrdersScreen(
                        viewModel = ordersViewModel,
                        // Тап по заказу открывает отдельный экран-чек (см.
                        // Screen.OrderDetail в AppNavHost), а не сразу редактирование.
                        onOrderClick = onOrderClick,
                        // Кнопка "+" ведёт сразу в меню выбора блюд, без стола -
                        // бывают заказы "навынос"/для клиентов без места, когда
                        // все столы заняты, и ждать освобождения стола не нужно.
                        onAddOrder = { onNavigateToNewOrder(emptyList(), null) }
                    )
                }
                BottomNavItem.PROFILE -> {
                    val profileViewModel: ProfileViewModel = viewModel(factory = factory)
                    ProfileScreen(viewModel = profileViewModel, onLoggedOut = onLoggedOut)
                }
            }
        }
    }
}
