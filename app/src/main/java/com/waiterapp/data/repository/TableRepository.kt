package com.waiterapp.data.repository

import com.waiterapp.data.local.TableDao
import com.waiterapp.data.local.TokenStore
import com.waiterapp.data.local.toEntity
import com.waiterapp.data.local.toTableInfo
import com.waiterapp.data.model.MockTableData
import com.waiterapp.data.model.TableInfo
import com.waiterapp.data.network.StompWebSocketClient
import com.waiterapp.data.network.WaiterApiService
import kotlinx.coroutines.flow.SharedFlow

// Топик стола: ОДИН общий адрес для create/update/delete, обёрнутый в
// WsEvent<TableResponse|Long> (см. websocket-topics.pdf, раздел "3. Table"):
//   record WsEvent<T>(String entity, String action, T data) {}
// где action = "CREATED" | "UPDATED" | "DELETED"; для DELETED в data лежит
// голый Long (id стола), а не TableResponse. Раньше здесь было два разных
// топика ("/topic/create/table" и "/topic/delete/table") - это не совпадало
// с тем, что реально шлёт бэкенд (createTable()/deleteTable() в
// TableServiceImpl шлют в один и тот же "/topic/table"), поэтому события
// стола на клиент никогда не долетали. Исправлено на единый адрес.
private const val TABLE_TOPIC = "/topic/table"

/**
 * Загружает список столов с сервера. Если сервер недоступен (нет сети/
 * таймаут/5xx), отдаёт последнюю сохранённую версию из локального кэша
 * (Room) - точно так же, как MenuRepository кэширует меню.
 *
 * Кэш всегда ПОЛНОСТЬЮ перезаписывается свежим списком с сервера
 * (tableDao.replaceAll = clear + insert одной транзакцией). Из-за этого
 * удаления отражаются в кэше сами собой: если стол удалили, пока была
 * связь, следующий успешный getAllTables() уже не запишет его обратно -
 * отдельно помечать "этот стол удалён" не нужно.
 */
class TableRepository(
    private val api: WaiterApiService,
    private val tokenManager: TokenStore,
    private val stompClient: StompWebSocketClient,
    private val tableDao: TableDao
) {
    suspend fun getAllTables(): ApiResult<List<TableInfo>> {
        // ДЕМО-РЕЖИМ: см. ApiConfig.DEMO_MODE_ENABLED - сразу отдаём тестовые
        // столы, не трогая сеть вообще (у OkHttp таймауты отключены, так что
        // без этой проверки при недоступном сервере экран завис бы навсегда).
        if (com.waiterapp.data.network.ApiConfig.DEMO_MODE_ENABLED) {
            return ApiResult.Success(MockTableData.sampleTables, fromCache = true)
        }

        val result = safeApiCall(tokenManager) { api.getAllTables() }

        return when (result) {
            is ApiResult.Success -> {
                tableDao.replaceAll(result.data.map { it.toEntity() })
                result
            }
            is ApiResult.Error -> {
                val isConnectivityIssue = result.code < 0 || result.code >= 500
                val cached = if (isConnectivityIssue) tableDao.getAll() else emptyList()
                when {
                    cached.isNotEmpty() -> ApiResult.Success(cached.map { it.toTableInfo() }, fromCache = true)
                    // Кэш пуст только на самом первом запуске без интернета -
                    // до этого момента ни разу не было успешной синхронизации.
                    // Тестовые данные тут лишь чтобы экран не был пустым.
                    isConnectivityIssue -> ApiResult.Success(MockTableData.sampleTables, fromCache = true)
                    else -> result
                }
            }
        }
    }

    /**
     * Поток "что-то изменилось со столами" (создание/изменение/удаление -
     * все три приходят в один и тот же топик, см. константу выше). Тело
     * сообщения - это WsEvent<TableResponse|Long>; специально не парсим его
     * в TableInfo здесь, а просто триггерим немедленный getAllTables() и
     * получаем консистентный полный список - надёжнее, чем собирать
     * TableInfo из разных по форме data (DELETED присылает голый id, а не
     * TableResponse).
     */
    fun observeTableEvents(): SharedFlow<String> = stompClient.topic(TABLE_TOPIC)
}
