package com.coltexpress.server

import com.coltexpress.server.protocol.ClientMessage
import com.coltexpress.server.protocol.Connected
import com.coltexpress.server.protocol.CreateRoom
import com.coltexpress.server.protocol.DefaultJson
import com.coltexpress.server.protocol.DrawCards
import com.coltexpress.server.protocol.Health
import com.coltexpress.server.protocol.JoinRoom
import com.coltexpress.server.protocol.ListRooms
import com.coltexpress.server.protocol.MakeChoice
import com.coltexpress.server.protocol.PlayAction
import com.coltexpress.server.protocol.ResetSession
import com.coltexpress.server.protocol.Say
import com.coltexpress.server.protocol.ServerMessage
import com.coltexpress.server.protocol.StartGame
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File

const val PORT = 8080

/** Version of the client APK this server distributes via /apk. Keep in sync with the client BUILD_NUMBER. */
const val SERVER_BUILD_NUMBER = 4

fun main() {
    embeddedServer(Netty, port = PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val roomManager = RoomManager()
    val log = environment.log

    install(CallLogging)
    install(ContentNegotiation) {
        json(DefaultJson)
    }
    install(WebSockets)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    routing {
        get("/health") {
            call.respond(Health("ok", roomManager.playerCount(), roomManager.roomCount(), SERVER_BUILD_NUMBER))
        }

        get("/apk") {
            val apkPath = System.getenv("COLT_APK_PATH")
                ?: "C:\\Users\\максим\\OneDrive\\Документы\\Default Project\\android\\app\\build\\outputs\\apk\\debug\\app-debug.apk"
            val apk = File(apkPath)
            if (apk.exists()) call.respondFile(apk) else call.respond(HttpStatusCode.NotFound)
        }

        webSocket("/ws") {
            var playerId: String? = null
            try {
                send(Frame.Text(DefaultJson.encodeToString(ServerMessage.serializer(), Connected)))
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    when (val message = DefaultJson.decodeFromString<ClientMessage>(frame.readText())) {
                        is CreateRoom -> {
                            playerId?.let { roomManager.leave(it) }
                            playerId = roomManager.createRoom(this, message.nickname, message.maxPlayers)
                        }
                        is JoinRoom -> {
                            playerId?.let { roomManager.leave(it) }
                            playerId = roomManager.joinRoom(this, message.roomId, message.nickname)
                        }
                        is ListRooms -> roomManager.listRooms(this)
                        is StartGame -> playerId?.let { roomManager.startGame(it) }
                        is PlayAction -> playerId?.let { roomManager.playAction(it, message.cardType) }
                        is DrawCards -> playerId?.let { roomManager.drawCards(it) }
                        is MakeChoice -> playerId?.let { roomManager.makeChoice(it, message.choiceId, message.value) }
                        is Say -> playerId?.let { roomManager.say(it, message.text) }
                        is ResetSession -> playerId?.let { roomManager.resetSession(it) }
                    }
                }
            } catch (e: Exception) {
                log.error("WS handler error", e)
            }
            playerId?.let { roomManager.leave(it) }
        }
    }
}
