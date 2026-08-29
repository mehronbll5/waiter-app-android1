package com.waiterapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.waiterapp.ui.navigation.AppNavHost
import com.waiterapp.ui.theme.WaiterAppTheme

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appContainer = AppContainer(applicationContext)

        setContent {
            WaiterAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(appContainer = appContainer)
                }
            }
        }
    }
}
