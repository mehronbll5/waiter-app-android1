package com.waiterapp.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.waiterapp.ui.theme.BackgroundGray
import com.waiterapp.ui.theme.GreenPrimary

/**
 * Экран стартовой проверки авторизации (Screen.Splash).
 *
 * Показывается, пока AppNavHost ждёт ответ POST /auth/auto (или, если
 * сохранённого токена нет вовсе, - долю секунды до мгновенного перехода
 * на Login). Никакой другой логики тут нет специально: экран не должен
 * ничего решать сам, он только визуально закрывает собой момент "Checking",
 * чтобы Hall не мог появиться до того, как решение принято (см. форензик-
 * аудит auth flow в AppNavHost.kt).
 */
@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGray),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = GreenPrimary)
    }
}
