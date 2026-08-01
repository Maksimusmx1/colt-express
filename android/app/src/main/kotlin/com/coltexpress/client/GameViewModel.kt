package com.coltexpress.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coltexpress.client.network.GameClient
import com.coltexpress.client.protocol.BanditMoved
import com.coltexpress.client.protocol.BanditRoofed
import com.coltexpress.client.protocol.BoardState
import com.coltexpress.client.protocol.BulletReceived
import com.coltexpress.client.protocol.CardPlayed
import com.coltexpress.client.protocol.CardRevealed
import com.coltexpress.client.protocol.Chat
import com.coltexpress.client.protocol.ChoiceRequired
import com.coltexpress.client.protocol.Connected
import com.coltexpress.client.protocol.DrawAction
import com.coltexpress.client.protocol.Error
import com.coltexpress.client.protocol.GameEnded
import com.coltexpress.client.protocol.GameEvent
import com.coltexpress.client.protocol.GameEventMsg
import com.coltexpress.client.protocol.GameStarted
import com.coltexpress.client.protocol.HandUpdate
import com.coltexpress.client.protocol.LootDropped
import com.coltexpress.client.protocol.LootTaken
import com.coltexpress.client.protocol.PlanningTurnMsg
import com.coltexpress.client.protocol.Player
import com.coltexpress.client.protocol.Punched
import com.coltexpress.client.protocol.RoomList
import com.coltexpress.client.protocol.RoomSummary
import com.coltexpress.client.protocol.RoomUpdate
import com.coltexpress.client.protocol.RoundStart
import com.coltexpress.client.protocol.ServerMessage
import com.coltexpress.client.protocol.SheriffMoved
import com.coltexpress.client.protocol.Shot
import com.coltexpress.client.protocol.ShotMissed
import com.coltexpress.client.protocol.Welcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
}

data class RoomInfo(
    val roomId: String,
    val players: List<Player>,
    val maxPlayers: Int,
    val phase: String,
    val ownerId: String,
)

data class RoomListItem(
    val roomId: String,
    val players: Int,
    val maxPlayers: Int,
    val phase: String,
    val ownerNickname: String,
)

data class HandCard(val uid: String, val type: String)

data class ChatLine(val sender: String, val text: String, val isLocal: Boolean = false)

class GameViewModel : ViewModel() {

    private val client = GameClient()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _myId = MutableStateFlow<String?>(null)
    val myId: StateFlow<String?> = _myId.asStateFlow()

    private val _myCharacter = MutableStateFlow<String?>(null)
    val myCharacter: StateFlow<String?> = _myCharacter.asStateFlow()

    private var nickname = ""

    private val _room = MutableStateFlow<RoomInfo?>(null)
    val room: StateFlow<RoomInfo?> = _room.asStateFlow()

    private val _rooms = MutableStateFlow<List<RoomListItem>>(emptyList())
    val rooms: StateFlow<List<RoomListItem>> = _rooms.asStateFlow()

    private val _players = MutableStateFlow<Map<String, String>>(emptyMap())
    val players: StateFlow<Map<String, String>> = _players.asStateFlow()

    private val _hand = MutableStateFlow<List<HandCard>>(emptyList())
    val hand: StateFlow<List<HandCard>> = _hand.asStateFlow()

    private val _ownBullets = MutableStateFlow(6)
    val ownBullets: StateFlow<Int> = _ownBullets.asStateFlow()

    private val _deckSize = MutableStateFlow(0)
    val deckSize: StateFlow<Int> = _deckSize.asStateFlow()

    private val _round = MutableStateFlow<RoundStart?>(null)
    val round: StateFlow<RoundStart?> = _round.asStateFlow()

    private val _currentTurn = MutableStateFlow<String?>(null)
    val currentTurn: StateFlow<String?> = _currentTurn.asStateFlow()

    private val _choice = MutableStateFlow<ChoiceRequired?>(null)
    val choice: StateFlow<ChoiceRequired?> = _choice.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat.asStateFlow()

    private val _gameOver = MutableStateFlow<GameEnded?>(null)
    val gameOver: StateFlow<GameEnded?> = _gameOver.asStateFlow()

    private val _board = MutableStateFlow<BoardState?>(null)
    val board: StateFlow<BoardState?> = _board.asStateFlow()

    val isMyTurn: Boolean get() = _currentTurn.value != null && _currentTurn.value == _myId.value

    fun connect(newNickname: String) {
        if (_connection.value is ConnectionState.Connected) return
        nickname = newNickname.trim().ifEmpty { "anonymous" }
        _connection.value = ConnectionState.Connecting
        viewModelScope.launch {
            try {
                client.events.collect { message -> handle(message) }
            } catch (e: Exception) {
                addLog("Соединение потеряно: ${e.message}")
            } finally {
                _connection.value = ConnectionState.Disconnected
            }
        }
        viewModelScope.launch {
            try {
                client.connect()
            } catch (e: Exception) {
                addLog("Не удалось подключиться: ${e.message}")
                _connection.value = ConnectionState.Disconnected
            }
        }
    }

    fun createRoom(maxPlayers: Int) {
        viewModelScope.launch { client.createRoom(nickname, maxPlayers) }
    }

    fun joinRoom(roomId: String) {
        viewModelScope.launch { client.joinRoom(roomId.trim(), nickname) }
    }

    fun refreshRooms() {
        viewModelScope.launch { client.listRooms() }
    }

    fun startGame() {
        viewModelScope.launch { client.startGame() }
    }

    fun play(cardType: String) {
        viewModelScope.launch { client.playAction(cardType) }
    }

    fun draw() {
        viewModelScope.launch { client.drawCards() }
    }

    fun choose(value: String) {
        val c = _choice.value ?: return
        viewModelScope.launch {
            client.makeChoice(c.choiceId, value)
            _choice.value = null
        }
    }

    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _chat.value += ChatLine("вы", trimmed, isLocal = true)
            client.say(trimmed)
        }
    }

    private fun handle(message: ServerMessage) {
        when (message) {
            is Connected -> _connection.value = ConnectionState.Connected
            is Welcome -> {
                _myId.value = message.playerId
                _myCharacter.value = message.character.ifEmpty { null }
                _connection.value = ConnectionState.Connected
            }
            is RoomUpdate -> {
                _room.value = RoomInfo(message.roomId, message.players, message.maxPlayers, message.phase, message.ownerId)
                _players.value = message.players.associate { it.id to it.nickname }
            }
            is RoomList -> {
                _rooms.value = message.rooms.map {
                    RoomListItem(it.roomId, it.players, it.maxPlayers, it.phase, it.ownerNickname)
                }
            }
            is GameStarted -> {
                _players.value = message.players.associate { it.id to it.nickname }
                _room.value = _room.value?.copy(phase = "PLANNING")
                addLog("Игра началась. Игроков: ${message.players.size}, раундов: ${message.rounds}.")
            }
            is RoundStart -> {
                _round.value = message
                _room.value = _room.value?.copy(phase = "PLANNING")
                addLog("Раунд ${message.round}: ${message.mode}, ходов: ${message.turns}. Первый: ${name(message.firstPlayerId)}")
            }
            is PlanningTurnMsg -> {
                _currentTurn.value = message.playerId
                _choice.value = null
                addLog("Планирование: ${name(message.playerId)}")
            }
            is CardPlayed -> {
                val what = if (message.faceDown) "карту рубашкой вверх" else cardName(message.cardType ?: "")
                addLog("${name(message.playerId)} играет $what")
            }
            is HandUpdate -> {
                _hand.value = message.hand.map { HandCard(it.uid, it.type) }
                _deckSize.value = message.deckSize
                _ownBullets.value = message.ownBullets
            }
            is BoardState -> _board.value = message
            is ChoiceRequired -> {
                _choice.value = message
                if (message.playerId == _myId.value) {
                    addLog("Выбор: ${choiceKindName(message.kind)}")
                } else {
                    addLog("Ожидание: ${name(message.playerId)} выбирает ${choiceKindName(message.kind)}")
                }
            }
            is GameEventMsg -> addLog(describe(message.event))
            is GameEnded -> {
                _gameOver.value = message
                addLog("Игра окончена. Победитель: ${name(message.winnerId)}")
                message.results.forEach { r ->
                    addLog("  ${r.nickname}: добыча=${r.lootSum} пули=${r.bulletsLeft} приз=${if (r.accuracyPrize) "+1000" else ""} итог=${r.total}")
                }
            }
            is Chat -> _chat.value += ChatLine(message.nickname, message.text)
            is Error -> addLog("Ошибка: ${message.message}")
        }
    }

    private fun describe(event: GameEvent): String = when (event) {
        is CardRevealed -> "Вскрытие: ${name(event.playerId)} сыграл ${cardName(event.cardType)}"
        is BanditMoved -> {
            val where = if (event.onRoof) "крыша" else "внутри"
            "${name(event.playerId)} перемещается в вагон ${event.toCar} ($where)"
        }
        is BanditRoofed -> "${name(event.playerId)} ${if (event.climbedUp) "забирается на крышу" else "спускается с крыши"} вагона ${event.car}"
        is Shot -> "${name(event.shooterId)} стреляет в ${name(event.targetId)}"
        is ShotMissed -> "${name(event.shooterId)} промахивается"
        is Punched -> "${name(event.actorId)} бьёт ${name(event.targetId)}"
        is LootDropped -> "${name(event.playerId)} бросает ${lootName(event.lootType)} в вагоне ${event.car}"
        is LootTaken -> "${name(event.playerId)} забирает ${lootName(event.lootType)} из вагона ${event.car}"
        is SheriffMoved -> "Шериф переходит в вагон ${event.to}"
        is BulletReceived -> if (event.neutral) "${name(event.playerId)} получает пулю от шерифа" else "${name(event.playerId)} получает пулю от ${name(event.fromId ?: "")}"
        is DrawAction -> "${name(event.playerId)} берёт карты"
    }

    private fun name(id: String): String = _players.value[id] ?: id

    private fun addLog(line: String) {
        _log.value += line
    }
}

internal fun cardName(type: String): String = when (type) {
    "ROB" -> "Ограбление"
    "SHOOT" -> "Выстрел"
    "MARSHAL" -> "Шериф"
    "PUNCH" -> "Удар"
    "LADDER" -> "Вагон ↔ Крыша"
    "MOVE" -> "Перемещение"
    "BULLET" -> "Пуля"
    else -> type
}

internal fun choiceKindName(kind: String): String = when (kind) {
    "MOVE_DIRECTION" -> "направление движения"
    "SHOOT_TARGET" -> "цель выстрела"
    "ROB_TOKEN" -> "добычу"
    "MARSHAL_DIRECTION" -> "направление шерифа"
    "PUNCH_VICTIM" -> "жертву удара"
    "PUNCH_LOOT" -> "добычу"
    "PUNCH_DIRECTION" -> "направление"
    "ON_THE_RUN_OPTION" -> "вариант действия"
    else -> kind
}

internal fun choiceOptionLabel(opt: String): String = when {
    opt == "B" -> "Назад"
    opt == "F" -> "Вперёд"
    opt.matches(Regex("B\\d")) -> "Назад ${opt.drop(1)}"
    opt.matches(Regex("F\\d")) -> "Вперёд ${opt.drop(1)}"
    opt == "PLAY2" -> "Сыграть 2 карты"
    opt == "DRAW6" -> "Взять 6 карт"
    opt == "DRAW3_PLAY1" -> "Взять 3, сыграть 1"
    else -> opt
}

internal fun lootName(type: String): String = when (type) {
    "WALLET" -> "кошелёк"
    "GEM" -> "самоцвет"
    "CASE" -> "сейф"
    else -> type
}

internal fun characterName(character: String): String = when (character) {
    "Doc" -> "Док"
    "Django" -> "Джанго"
    "Kid" -> "Кид"
    "Belle" -> "Белль"
    "Clover" -> "Кловер"
    "Tuco" -> "Туко"
    else -> character
}
