package com.waiterapp

import android.content.Context
import com.waiterapp.data.local.AppDatabase
import com.waiterapp.data.local.TokenManager
import com.waiterapp.data.network.ApiConfig
import com.waiterapp.data.network.RetrofitProvider
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.data.repository.AuthRepository
import com.waiterapp.data.repository.LocalOrderRepository
import com.waiterapp.data.repository.MenuRepository
import com.waiterapp.data.repository.OrderRepository
import com.waiterapp.data.repository.TableRepository

/**
 * Простой ручной контейнер зависимостей (без Hilt/Dagger),
 * чтобы было проще разобраться новичку в Kotlin.
 * Создаёт по одному экземпляру каждого репозитория на всё приложение.
 */
class AppContainer(context: Context) {
    private val tokenManager = TokenManager(context.applicationContext)

    // RetrofitInterceptor создаётся до AuthRepository, поэтому callback лениво
    // обращается к уже созданному repository. Весь refresh при 401 проходит
    // через один и тот же AuthRepository/Mutex.
    private lateinit var authRepositoryImpl: AuthRepository
    private val apiService = RetrofitProvider.create(tokenManager) { failedAccessToken ->
        if (::authRepositoryImpl.isInitialized) {
            authRepositoryImpl.refreshAccessTokenIfNeeded(failedAccessToken) is com.waiterapp.data.repository.ApiResult.Success
        } else {
            false
        }
    }

    private val database = AppDatabase.getInstance(context.applicationContext)


    // Один WebSocket-клиент на всё приложение: соединение поднимается лениво
    // при первой подписке на топик (см. StompWebSocketClient.topic) и живёт,
    // пока живёт процесс - экраны просто переиспользуют один и тот же сокет.
    private val stompClient = StompWebSocketClient(ApiConfig.WS_URL, tokenManager)

    val authRepository: AuthRepository
        get() = authRepositoryImpl

    init {
        authRepositoryImpl = AuthRepository(apiService, tokenManager)
    }
    val menuRepository = MenuRepository(apiService, database.menuDao(), tokenManager, stompClient)
    val orderRepository = OrderRepository(apiService, tokenManager)
    val tableRepository = TableRepository(apiService, tokenManager, stompClient, database.tableDao())
    val localOrderRepository = LocalOrderRepository(
        database.localOrderDao(),
        apiService,
        tokenManager,
        stompClient,
        database.oldOrderCacheDao()
    )

    /** Очищает все локальные Room-кэши текущего аккаунта. */
    suspend fun clearLocalCache() {
        database.clearLocalCache()
    }
}
