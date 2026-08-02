package com.coltexpress.client.network

import com.coltexpress.client.protocol.ClientMessage
import com.coltexpress.client.protocol.CreateRoom
import com.coltexpress.client.protocol.DefaultJson
import com.coltexpress.client.protocol.DrawCards
import com.coltexpress.client.protocol.Health
import com.coltexpress.client.protocol.JoinRoom
import com.coltexpress.client.protocol.ListRooms
import com.coltexpress.client.protocol.MakeChoice
import com.coltexpress.client.protocol.PlayAction
import com.coltexpress.client.protocol.Say
import com.coltexpress.client.protocol.ServerMessage
import com.coltexpress.client.protocol.StartGame
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class GameClient(
    private val url: String = DEFAULT_URL,
) {
    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private val json = DefaultJson
    private var session: ClientWebSocketSession? = null

    private val _events = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerMessage> = _events.asSharedFlow()

    val isConnected: Boolean get() = session != null

    /** Runs while connected; returns when the socket closes. */
    suspend fun connect() {
        client.webSocket(url) {
            this@GameClient.session = this
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val message = json.decodeFromString<ServerMessage>(frame.readText())
                    _events.emit(message)
                }
            }
        }
        session = null
    }

    suspend fun createRoom(nickname: String, maxPlayers: Int) = send(CreateRoom(nickname, maxPlayers))
    suspend fun joinRoom(roomId: String, nickname: String) = send(JoinRoom(roomId, nickname))
    suspend fun listRooms() = send(ListRooms)
    suspend fun startGame() = send(StartGame)
    suspend fun playAction(cardType: String) = send(PlayAction(cardType))
    suspend fun drawCards() = send(DrawCards)
    suspend fun makeChoice(choiceId: String, value: String) = send(MakeChoice(choiceId, value))
    suspend fun say(text: String) = send(Say(text))

    private suspend fun send(message: ClientMessage) {
        session?.send(Frame.Text(json.encodeToString(ClientMessage.serializer(), message)))
    }

    /** Fetches the client build number that the server distributes via /apk. */
    suspend fun fetchServerBuild(): Int? {
        val base = url
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
            .substringBeforeLast('/')
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("$base/health").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    conn.inputStream.bufferedReader().use { reader ->
                        json.decodeFromString<Health>(reader.readText()).buildNumber
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun disconnect() {
        client.close()
    }

    companion object {
        const val DEFAULT_URL = "ws://10.0.2.2:8080/ws"
    }
}
