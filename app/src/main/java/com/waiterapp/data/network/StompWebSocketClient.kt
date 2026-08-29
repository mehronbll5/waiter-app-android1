package com.waiterapp.data.network

import android.util.Log
import com.waiterapp.data.local.TokenStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

private const val TAG = "StompWebSocketClient"
private const val NULL_BYTE = '\u0000'
private const val RECONNECT_DELAY_MS = 3_000L

/**
 * Минимальный STOMP-клиент поверх обычного OkHttp WebSocket - без SockJS-обёртки
 * и без сторонних библиотек (com.github.NaikSoftware:StompProtocolAndroid и т.п.
 * тянут за собой RxJava, а okhttp у нас в проекте и так уже есть).
 *
 * Подключается НАПРЯМУЮ к "сырому" WebSocket-транспорту, который Spring
 * регистрирует под SockJS-эндпоинтом по пути "<endpoint>/websocket" - это
 * штатный способ для нативных (не-JS) клиентов не реализовывать весь протокол
 * SockJS (long-polling фолбэки, session id и т.д.), а просто говорить STOMP
 * напрямую по WebSocket. На бэкенде эндпоинт зарегистрирован как:
 *   registry.addEndpoint("/ws").withSockJS();
 * поэтому сюда нужно передавать URL вида "ws://host:port/ws/websocket".
 *
 * Поддерживает только то, что реально нужно приложению: CONNECT одним разом
 * и SUBSCRIBE на произвольные топики, без отправки сообщений с клиента
 * (официант через WebSocket ничего не публикует, только слушает).
 *
 * ВАЖНО: на бэкенде эндпоинт "/ws" НЕ входит в список permitAll в
 * SecurityConfig, поэтому попадает под общее правило anyRequest().authenticated().
 * Это правило проверяется на самом HTTP GET-запросе хендшейка (до апгрейда
 * до WebSocket) - JwtAuthFilter читает обычный HTTP-заголовок Authorization
 * из этого запроса точно так же, как из любого REST-запроса. Без него
 * хендшейк отклоняется (401/403 до апгрейда), окно навсегда остаётся в
 * цикле переподключений и никакие STOMP-сообщения не доходят. Поэтому сюда
 * передаётся TokenStore - чтобы подставлять актуальный accessToken в
 * заголовок хендшейка при каждом (пере)подключении, включая случаи, когда
 * токен успел обновиться между обрывами связи.
 */
class StompWebSocketClient(
    private val wsUrl: String,
    private val tokenStore: TokenStore
) {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var connected = false
    private var subscriptionCounter = 0

    // destination -> (id подписки, flow, на который льются тела сообщений)
    private val subscriptions = mutableMapOf<String, Pair<String, MutableSharedFlow<String>>>()

    /**
     * Возвращает "горячий" поток тел сообщений (JSON-строка) для указанного
     * топика, например "/topic/create/table/". Подключается лениво: сокет
     * поднимается при первой подписке на любой топик, а не в конструкторе -
     * так AppContainer можно создавать без немедленного сетевого запроса.
     */
    fun topic(destination: String): SharedFlow<String> {
        val existing = subscriptions[destination]
        if (existing != null) return existing.second.asSharedFlow()

        val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
        val subId = "sub-${subscriptionCounter++}"
        subscriptions[destination] = subId to flow

        if (connected) sendSubscribe(destination, subId)
        if (webSocket == null) connect()

        return flow.asSharedFlow()
    }

    private fun connect() {
        // Тот же заголовок, что и в NgrokHeaderInterceptor для обычных
        // REST-запросов - иначе бесплатный ngrok может подсунуть HTML-заглушку
        // прямо на этапе WebSocket handshake вместо апгрейда соединения.
        val requestBuilder = Request.Builder()
            .url(wsUrl)
            .addHeader("ngrok-skip-browser-warning", "true")

        // Читаем токен заново при каждом connect() (а не один раз в
        // конструкторе), т.к. StompWebSocketClient живёт на весь процесс и
        // может переподключаться спустя долгое время, когда accessToken уже
        // сменится (см. AuthInterceptor - ровно та же логика для REST).
        // Без этого заголовка бэкенд отклоняет сам HTTP-хендшейк ещё до
        // апгрейда до WebSocket (см. доккомментарий класса).
        val accessToken = tokenStore.getAccessToken()
        if (!accessToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $accessToken")
        } else {
            Log.w(TAG, "Нет accessToken - подключаюсь к WebSocket без авторизации, бэкенд, скорее всего, отклонит хендшейк")
        }

        webSocket = client.newWebSocket(requestBuilder.build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            Log.d(TAG, "WebSocket connected")
            // heart-beat:0,0 - отключаем STOMP heartbeat'ы, чтобы не пришлось
            // отдельно гонять фоновую корутину с "\n" каждые N секунд;
            // за живостью TCP-соединения и так следит OkHttp pingInterval выше.
            val connectFrame = frame(
                command = "CONNECT",
                headers = mapOf(
                    "accept-version" to "1.1,1.2",
                    "heart-beat" to "0,0"
                )
            )
            webSocket.send(connectFrame)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Сервер может прислать несколько STOMP-фреймов в одном
            // WebSocket-сообщении (или пустой heartbeat "\n") - разбираем по NULL_BYTE.
            text.split(NULL_BYTE).forEach { raw ->
                val trimmed = raw.trim('\n', '\r')
                if (trimmed.isEmpty()) return@forEach
                handleFrame(trimmed)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.w(TAG, "WebSocket соединение упало, переподключаюсь через ${RECONNECT_DELAY_MS}мс: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
        }
    }

    private fun handleFrame(raw: String) {
        val lines = raw.split("\n")
        when (val command = lines.firstOrNull()) {
            "CONNECTED" -> {
                connected = true
                Log.d(TAG, "STOMP CONNECTED")
                // Как только брокер подтвердил соединение - подписываемся
                // на всё, что успели запросить до этого момента.
                subscriptions.forEach { (destination, pair) -> sendSubscribe(destination, pair.first) }
            }
            "MESSAGE" -> {
                var i = 1
                var destination: String? = null
                while (i < lines.size && lines[i].isNotEmpty()) {
                    val idx = lines[i].indexOf(':')
                    if (idx > 0) {
                        val key = lines[i].substring(0, idx)
                        val value = lines[i].substring(idx + 1)
                        if (key == "destination") destination = value
                    }
                    i++
                }
                val body = lines.drop(i + 1).joinToString("\n")
                val target = destination ?: return
                Log.d(TAG, "STOMP MESSAGE $target: $body")
                val delivered = subscriptions[target]?.second?.tryEmit(body) ?: false
                if (!delivered) {
                    Log.w(TAG, "Нет активной подписки/буфер полон для $target - событие потеряно")
                }
            }
            "ERROR" -> {
                Log.w(TAG, "Брокер вернул STOMP ERROR фрейм: $raw")
            }
            else -> Unit // heartbeat / неизвестный фрейм - игнорируем
        }
    }

    private fun sendSubscribe(destination: String, subId: String) {
        val subscribeFrame = frame(
            command = "SUBSCRIBE",
            headers = mapOf("id" to subId, "destination" to destination)
        )
        webSocket?.send(subscribeFrame)
        Log.d(TAG, "STOMP subscribed: $destination")
    }

    private fun scheduleReconnect() {
        connected = false
        webSocket = null
        Log.d(TAG, "Reconnect через ${RECONNECT_DELAY_MS}мс")
        // Простая задержка без отдельного скоупа корутин - клиент живёт
        // на уровне всего приложения (AppContainer), поток окей занять ненадолго.
        // ВАЖНО: subscriptions НЕ очищается здесь - при новом CONNECTED (см.
        // handleFrame выше) переподписка идёт по уже существующим id из той же
        // map, а не создаёт новые записи/id, поэтому дублирующихся подписок на
        // один и тот же топик после reconnect не возникает.
        Thread {
            Thread.sleep(RECONNECT_DELAY_MS)
            connect()
        }.start()
    }

    private fun frame(command: String, headers: Map<String, String>, body: String = ""): String {
        val sb = StringBuilder()
        sb.append(command).append('\n')
        headers.forEach { (key, value) -> sb.append(key).append(':').append(value).append('\n') }
        sb.append('\n')
        sb.append(body)
        sb.append(NULL_BYTE)
        return sb.toString()
    }
}
