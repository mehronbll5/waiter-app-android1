package com.waiterapp.ui.theme

import androidx.compose.ui.graphics.Color

// --- Основная зелёная палитра ---
val GreenPrimary = Color(0xFF1E8E3E)       // насыщенный, "профессиональный" зелёный
val GreenPrimaryDark = Color(0xFF14672C)   // для pressed/акцентных состояний
val GreenSecondary = Color(0xFF3AA85C)     // мягче, для вторичных акцентов
val GreenLight = Color(0xFFE3F5E8)         // светлая заливка (бейджи, контейнеры)
val GreenContainer = Color(0xFFD4EFDC)     // контейнер для выбранных состояний (nav, chips)
val GreenBorder = Color(0xFFBFE2CB)        // рамки в зелёном тоне вместо серых

// --- Белый / нейтральные (с лёгким зелёным оттенком, а не чистый серый) ---
val SurfaceWhite = Color(0xFFFFFFFF)
val BackgroundGray = Color(0xFFF4FAF6)     // фон экранов — тёплый белый с зелёным подтоном
val DividerGreenGray = Color(0xFFE1EAE4)   // разделители/рамки неактивных элементов

// --- Текст ---
val TextDark = Color(0xFF16241B)
val TextGray = Color(0xFF6B7A70)

// --- Функциональные (не декоративные) статус-цвета — намеренно НЕ зелёные ---
val RedBusy = Color(0xFFE53935)            // стол занят / удаление / ошибка
val YellowSoon = Color(0xFFF2A93B)         // стол скоро освободится
