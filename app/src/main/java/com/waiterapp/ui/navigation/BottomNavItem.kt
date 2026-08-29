package com.waiterapp.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.waiterapp.R

/**
 * Три вкладки нижней навигации:
 * - ГЛАВНАЯ - карта зала (то, что уже было сделано)
 * - ЗАКАЗЫ - заказы, реально отправленные на сервер этим официантом
 * - ПРОФИЛЬ - профиль официанта
 */
enum class BottomNavItem(@StringRes val labelResId: Int, val icon: ImageVector) {
    HOME(R.string.bottom_nav_home, Icons.Default.Home),
    ORDERS(R.string.bottom_nav_orders, Icons.Default.Assignment),
    PROFILE(R.string.bottom_nav_profile, Icons.Default.Person)
}
