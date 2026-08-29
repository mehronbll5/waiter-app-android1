package com.waiterapp.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waiterapp.data.model.TableInfo
import com.waiterapp.data.repository.ApiResult
import com.waiterapp.data.repository.TableRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Бэкенд шлёт WebSocket-события создания/изменения/удаления стола в один
// топик "/topic/table" (WsEvent-обёртка, см. подробный комментарий в
// TableRepository) - тело не парсится, так что все случаи уже покрыты
// observeTableEvents() ниже. Фоновый опрос по таймеру всё равно оставлен
// как подстраховка на случай обрыва WebSocket-соединения между
// переподключениями (StompWebSocketClient переподключается не мгновенно) и
// на случай пропущенного сообщения. Тот же интервал, что и в OrdersViewModel.
private const val AUTO_REFRESH_INTERVAL_MS = 5_000L

class HallViewModel(private val tableRepository: TableRepository) : ViewModel() {

    var tables by mutableStateOf<List<TableInfo>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // true, пока нет подтверждённой связи с реальным сервером (используются
    // тестовые/кэшированные данные) - на это время карточки столов в UI
    // показываются одним нейтральным зелёным цветом, т.к. их реальный статус
    // не подтверждён сервером. Как только придёт настоящий ответ - становится false,
    // и карточки перекрашиваются по фактическому статусу (свободен/занят/скоро освободится).
    var isConnectedToServer by mutableStateOf(false)
        private set

    init {
        loadTables()
        observeTableEvents()
        startAutoRefresh()
    }

    fun loadTables() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            when (val result = tableRepository.getAllTables()) {
                is ApiResult.Success -> {
                    isLoading = false
                    isConnectedToServer = !result.fromCache
                    tables = result.data.sortedBy { it.number }
                }
                is ApiResult.Error -> {
                    isLoading = false
                    isConnectedToServer = false
                    errorMessage = result.message
                }
            }
        }
    }

    /**
     * Слушает WebSocket-топик "/topic/table" (создание/изменение/удаление
     * стола, все три - один топик, см. TableRepository). В пришедшем
     * сообщении лежит WsEvent<TableResponse|Long> - вместо того чтобы
     * разбирать его тело и action, просто тихо перезапрашиваем полный
     * список сразу, как только пришло любое событие, получая гарантированно
     * консистентную картину. Если соединение оборвётся, StompWebSocketClient
     * сам переподключится и снова начнёт присылать события.
     */
    private fun observeTableEvents() {
        // ДЕМО-РЕЖИМ: нет смысла держать WebSocket, который вечно пытается
        // переподключиться к несуществующему серверу.
        if (com.waiterapp.data.network.ApiConfig.DEMO_MODE_ENABLED) return
        viewModelScope.launch {
            tableRepository.observeTableEvents().collect {
                loadTables()
            }
        }
    }

    /**
     * Фоновое обновление без крутилки - чисто подстраховка на случай
     * пропущенного/непришедшего WS-сообщения или временного разрыва
     * соединения (событие удаления стола тоже приходит по WebSocket, см.
     * observeTableEvents() и комментарий в TableRepository).
     */
    private fun startAutoRefresh() {
        if (com.waiterapp.data.network.ApiConfig.DEMO_MODE_ENABLED) return
        viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                when (val result = tableRepository.getAllTables()) {
                    is ApiResult.Success -> {
                        isConnectedToServer = !result.fromCache
                        tables = result.data.sortedBy { it.number }
                    }
                    is ApiResult.Error -> Unit // тихо игнорируем - следующий тик попробует снова
                }
            }
        }
    }
}
