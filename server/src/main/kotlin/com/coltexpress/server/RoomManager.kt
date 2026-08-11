package com.coltexpress.server

import com.coltexpress.server.game.ActionCard
import com.coltexpress.server.game.BulletCard
import com.coltexpress.server.game.Engine
import com.coltexpress.server.game.EngineUpdate
import com.coltexpress.server.game.GameCard
import com.coltexpress.server.game.MemberSpec
import com.coltexpress.server.game.PlayerState
import com.coltexpress.server.game.RoundMode
import com.coltexpress.server.game.Setup
import com.coltexpress.server.protocol.BoardCar
import com.coltexpress.server.protocol.BoardState
import com.coltexpress.server.protocol.CardInHand
import com.coltexpress.server.protocol.CardPlayed
import com.coltexpress.server.protocol.Chat
import com.coltexpress.server.protocol.ChoiceRequired
import com.coltexpress.server.protocol.ClientMessage
import com.coltexpress.server.protocol.DefaultJson
import com.coltexpress.server.protocol.Error
import com.coltexpress.server.protocol.GameEnded
import com.coltexpress.server.protocol.GameEventMsg
import com.coltexpress.server.protocol.GameStarted
import com.coltexpress.server.protocol.HandUpdate
import com.coltexpress.server.protocol.Player
import com.coltexpress.server.protocol.PlanningTurnMsg
import com.coltexpress.server.protocol.RoomList
import com.coltexpress.server.protocol.RoomSummary
import com.coltexpress.server.protocol.RoomUpdate
import com.coltexpress.server.protocol.RoundStart
import com.coltexpress.server.protocol.ServerMessage
import com.coltexpress.server.protocol.SessionReset
import com.coltexpress.server.protocol.Welcome
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Лобби + оркестрация игр. Каждый WebSocket-клиент сначала создаёт или входит в комнату;
 * дальше вся игра идёт через комнаты. Все операции над одной комнатой сериализуются мьютексом.
 */
class RoomManager {

    private val rooms = ConcurrentHashMap<String, Room>()
    private val sessions = ConcurrentHashMap<String, WebSocketServerSession>()
    private val players = ConcurrentHashMap<String, Player>()
    private val playerRoom = ConcurrentHashMap<String, String>()
    private val bots = ConcurrentHashMap.newKeySet<String>()
    private val botScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun playerCount(): Int = players.size

    fun roomCount(): Int = rooms.size

    // ===== Сессии =====

    private fun register(session: WebSocketServerSession, nickname: String, character: String): String {
        val id = UUID.randomUUID().toString()
        val player = Player(id, nickname.trim().ifEmpty { "anonymous" }, character)
        players[id] = player
        sessions[id] = session
        return id
    }

    private suspend fun unregister(playerId: String) {
        playerRoom.remove(playerId)?.let { roomId ->
            rooms[roomId]?.let { room ->
                room.lock.withLock {
                    leaveRoomLocked(room, playerId)
                }
            }
        }
        sessions.remove(playerId)
        players.remove(playerId)
    }

    /** Снимает игрока с комнаты. В лобби — обычный выход; если все живые участники отключились, комната удаляется. */
    private suspend fun leaveRoomLocked(room: Room, playerId: String) {
        room.players.removeIf { it.id == playerId }
        if (room.players.isEmpty() || room.players.none { sessions.containsKey(it.id) }) {
            dropRoom(room.id)
            return
        }
        if (room.engine == null) {
            if (room.ownerId == playerId) room.ownerId = room.players.first().id
            sendToRoom(room, roomUpdate(room))
        }
    }

    private fun dropRoom(roomId: String) {
        rooms.remove(roomId)
        playerRoom.entries.removeIf { it.value == roomId }
        bots.toList().forEach { id -> if (!playerRoom.containsKey(id)) { bots.remove(id); players.remove(id) } }
    }

    // ===== Вход в комнаты =====

    suspend fun createRoom(session: WebSocketServerSession, nickname: String, maxPlayers: Int): String? {
        if (maxPlayers !in Setup.MIN_PLAYERS..Setup.MAX_PLAYERS) {
            session.sendMessage(Error("maxPlayers must be ${Setup.MIN_PLAYERS}..${Setup.MAX_PLAYERS}"))
            return null
        }
        val playerId = register(session, nickname, CHARACTERS.random())
        val id = UUID.randomUUID().toString().take(8)
        val room = Room(id, maxPlayers, playerId)
        rooms[id] = room
        playerRoom[playerId] = id
        room.players.add(players.getValue(playerId))
        addBots(room)
        session.sendMessage(Welcome(playerId, players.getValue(playerId).nickname, players.getValue(playerId).character))
        sendToRoom(room, roomUpdate(room))
        return playerId
    }

    /** Автоматически создаёт ботов и подключает их к только что созданной сессии. */
    private fun addBots(room: Room) {
        val usedChars = room.players.map { it.character }.toMutableSet()
        var toAdd = minOf(BOT_COUNT, room.maxPlayers - room.players.size)
        var index = 0
        while (toAdd > 0) {
            val character = CHARACTERS.filter { it !in usedChars }.random()
            usedChars += character
            val botId = UUID.randomUUID().toString()
            players[botId] = Player(botId, "Бот-${++index}", character)
            bots.add(botId)
            playerRoom[botId] = room.id
            room.players.add(players.getValue(botId))
            toAdd--
        }
    }

    suspend fun joinRoom(session: WebSocketServerSession, roomId: String, nickname: String): String? {
        val room = rooms[roomId] ?: run {
            session.sendMessage(Error("Room not found"))
            return null
        }
        return room.lock.withLock {
            if (room.engine != null) {
                session.sendMessage(Error("Game already started"))
                return@withLock null
            }
            if (room.players.size >= room.maxPlayers) {
                session.sendMessage(Error("Room is full"))
                return@withLock null
            }
            val character = CHARACTERS.filter { c -> room.players.none { it.character == c } }.random()
            val playerId = register(session, nickname, character)
            playerRoom[playerId] = roomId
            room.players.add(players.getValue(playerId))
            session.sendMessage(Welcome(playerId, players.getValue(playerId).nickname, players.getValue(playerId).character))
            sendToRoom(room, roomUpdate(room))
            playerId
        }
    }

    // ===== Игровые команды =====

    suspend fun listRooms(session: WebSocketServerSession) {
        val summaries = rooms.values
            .filter { it.engine == null }
            .map { room ->
                RoomSummary(
                    roomId = room.id,
                    players = room.players.size,
                    maxPlayers = room.maxPlayers,
                    phase = "LOBBY",
                    ownerNickname = players[room.ownerId]?.nickname ?: "?",
                )
            }
            .sortedBy { it.roomId }
        session.sendMessage(RoomList(summaries))
    }

    suspend fun startGame(playerId: String) {
        val room = roomOf(playerId) ?: return
        room.lock.withLock {
            if (room.ownerId != playerId) {
                sendToPlayer(playerId, Error("Only the room owner can start the game"))
                return
            }
            if (room.engine != null) return
            if (room.players.size < Setup.MIN_PLAYERS) {
                sendToPlayer(playerId, Error("Need at least ${Setup.MIN_PLAYERS} players"))
                return
            }
            val members = room.players.map { MemberSpec(it.id, it.nickname, it.character) }
            val engine = Setup.createEngine(members)
            room.engine = engine
            sendToRoom(room, GameStarted(room.players, Setup.ROUNDS, engine.firstPlayer))
            dispatch(room, engine.startRound())
        }
    }

    suspend fun playAction(playerId: String, cardType: String) {
        val room = roomOf(playerId) ?: return
        room.lock.withLock {
            val engine = room.engine ?: return
            val result = engine.playCard(playerId, cardType)
            if (!result.played) {
                sendToPlayer(playerId, Error("Cannot play $cardType right now"))
                return
            }
            val faceDown = engine.roundCard.mode == RoundMode.TUNNEL
            sendToRoom(room, CardPlayed(playerId, if (faceDown) null else cardType, faceDown))
            sendToPlayer(playerId, handUpdate(engine, playerId))
            dispatch(room, result.updates)
        }
    }

    suspend fun drawCards(playerId: String) {
        val room = roomOf(playerId) ?: return
        room.lock.withLock {
            val engine = room.engine ?: return
            val result = engine.drawCards(playerId)
            if (!result.drew) {
                sendToPlayer(playerId, Error("Cannot draw right now"))
                return
            }
            sendToPlayer(playerId, handUpdate(engine, playerId))
            dispatch(room, result.updates)
        }
    }

    suspend fun makeChoice(playerId: String, choiceId: String, value: String) {
        val room = roomOf(playerId) ?: return
        room.lock.withLock {
            val engine = room.engine ?: return
            val updates = engine.submitChoice(playerId, choiceId, value)
            if (updates.isEmpty()) {
                sendToPlayer(playerId, Error("Invalid choice"))
                return
            }
            dispatch(room, updates)
        }
    }

    suspend fun say(playerId: String, text: String) {
        val nickname = players[playerId]?.nickname ?: return
        val room = roomOf(playerId) ?: return
        sendToRoom(room, Chat(nickname, text))
    }

    /** Сбрасывает игру в комнате для всех: возвращает в лобби и сообщает каждому клиенту. */
    suspend fun resetSession(playerId: String) {
        val room = roomOf(playerId) ?: return
        room.lock.withLock {
            if (room.ownerId != playerId) {
                sendToPlayer(playerId, Error("Only the room owner can reset the session"))
                return
            }
            room.engine = null
            sendToRoom(room, SessionReset)
            sendToRoom(room, roomUpdate(room))
        }
    }

    suspend fun leave(playerId: String) {
        unregister(playerId)
    }

    // ===== Перевод событий движка в сообщения =====

    private suspend fun dispatch(room: Room, updates: List<EngineUpdate>) {
        for (update in updates) {
            val engine = room.engine ?: continue
            when (update) {
                is EngineUpdate.RoundStarted -> {
                    sendToRoom(room, RoundStart(update.round, update.mode, update.turns, update.firstPlayerId))
                    room.players.forEach { sendToPlayer(it.id, handUpdate(engine, it.id)) }
                }
                is EngineUpdate.PlanningTurn -> {
                    sendToRoom(room, PlanningTurnMsg(update.playerId, update.mode))
                    sendToPlayer(update.playerId, handUpdate(engine, update.playerId))
                }
                is EngineUpdate.PlanningChoice -> sendChoice(room, update.choice)
                is EngineUpdate.Robbery -> {
                    update.events.forEach { sendToRoom(room, GameEventMsg(it)) }
                    update.choice?.let { sendChoice(room, it) }
                }
                is EngineUpdate.GameOver -> sendToRoom(room, GameEnded(update.results, update.winnerId))
            }
        }
        room.engine?.let {
            sendToRoom(room, roomUpdate(room))
            sendToRoom(room, boardState(it))
        }
        driveBots(room)
    }

    // ===== Боты =====

    /**
     * Если сейчас должен ходить бот — даёт ему случайный ход с небольшой задержкой.
     * Вызывается после каждой отдачи обновлений, поэтому боты «просыпаются» сами.
     */
    private fun driveBots(room: Room) {
        val engine = room.engine ?: return
        val actor = engine.actorNeedingInput() ?: return
        if (!bots.contains(actor)) return
        botScope.launch {
            delay(BOT_DELAY_MS)
            val action = room.lock.withLock {
                val e = room.engine ?: return@withLock null
                when {
                    e.pendingChoiceFor(actor) != null -> {
                        val c = e.pendingChoiceFor(actor)!!
                        BotAction.Choice(c.id, c.options.random())
                    }
                    e.canPlayAs(actor) -> {
                        val cardType = e.player(actor)?.hand?.filterIsInstance<ActionCard>()?.randomOrNull()?.type?.name
                        if (cardType != null) BotAction.Play(cardType) else BotAction.Draw
                    }
                    else -> null
                }
            }
            when (action) {
                is BotAction.Play -> playAction(actor, action.cardType)
                is BotAction.Draw -> drawCards(actor)
                is BotAction.Choice -> makeChoice(actor, action.choiceId, action.value)
                null -> Unit
            }
        }
    }

    private sealed interface BotAction {
        data class Play(val cardType: String) : BotAction
        data object Draw : BotAction
        data class Choice(val choiceId: String, val value: String) : BotAction
    }

    private fun boardState(engine: Engine): BoardState =
        BoardState(
            cars = engine.cars.map { car ->
                BoardCar(car.index, car.inside.sorted(), car.roof.sorted(), car.lootInside.size, car.lootRoof.size)
            },
            sheriffCar = engine.sheriffIndex,
        )

    private suspend fun sendChoice(room: Room, choice: ChoiceRequired) {
        sendToPlayer(choice.playerId, choice)
    }

    private fun handUpdate(engine: Engine, playerId: String): HandUpdate {
        val p = engine.player(playerId) ?: return HandUpdate(emptyList(), 0, 0)
        return HandUpdate(p.hand.map { CardInHand(it.uid, cardTypeName(it)) }, p.deck.size, p.ownBullets)
    }

    private fun cardTypeName(card: GameCard): String = when (card) {
        is ActionCard -> card.type.name
        is BulletCard -> "BULLET"
    }

    private fun roomOf(playerId: String): Room? = playerRoom[playerId]?.let { rooms[it] }

    private fun roomUpdate(room: Room): RoomUpdate {
        val phase = room.engine?.phase?.name ?: "LOBBY"
        return RoomUpdate(room.id, room.players.toList(), room.maxPlayers, phase, room.ownerId)
    }

    // ===== Отправка =====

    private suspend fun sendToRoom(room: Room, message: ServerMessage) {
        val payload = DefaultJson.encodeToString(ServerMessage.serializer(), message)
        room.players.forEach { p ->
            sessions[p.id]?.send(Frame.Text(payload))
        }
    }

    private suspend fun sendToPlayer(playerId: String, message: ServerMessage) {
        sessions[playerId]?.sendMessage(message)
    }

    private suspend fun WebSocketServerSession.sendMessage(message: ServerMessage) {
        send(Frame.Text(DefaultJson.encodeToString(ServerMessage.serializer(), message)))
    }

    companion object {
        private val CHARACTERS = listOf("Doc", "Django", "Cheyenne", "Belle", "Tuco", "Ghost")

        /** Сколько ботов сервер автоматически добавляет в новую сессию. */
        const val BOT_COUNT = 3

        /** Пауза перед ходом бота, мс. */
        const val BOT_DELAY_MS = 600L
    }
}
