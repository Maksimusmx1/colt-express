package com.coltexpress.client

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
}

const val BUILD_NUMBER = 4

private const val RECONNECT_DELAY_MS = 60_000L

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

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private var client = GameClient()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _myId = MutableStateFlow<String?>(null)
    val myId: StateFlow<String?> = _myId.asStateFlow()

    private val _myCharacter = MutableStateFlow<String?>(null)
    val myCharacter: StateFlow<String?> = _myCharacter.asStateFlow()

    private var nickname = ""

    private var currentHost: String? = null

    private var supervisorJob: Job? = null
    private var eventsJob: Job? = null

    private val _room = MutableStateFlow<RoomInfo?>(null)
    val room: StateFlow<RoomInfo?> = _room.asStateFlow()

    private val _rooms = MutableStateFlow<List<RoomListItem>>(emptyList())
    val rooms: StateFlow<List<RoomListItem>> = _rooms.asStateFlow()

    private val _players = MutableStateFlow<Map<String, String>>(emptyMap())
    val players: StateFlow<Map<String, String>> = _players.asStateFlow()

    private val _characters = MutableStateFlow<Map<String, String>>(emptyMap())

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

    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    private val _serverBuild = MutableStateFlow<Int?>(null)
    val serverBuild: StateFlow<Int?> = _serverBuild.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    val isMyTurn: Boolean get() = _currentTurn.value != null && _currentTurn.value == _myId.value

    fun connect(newNickname: String, serverAddress: String) {
        if (_connection.value is ConnectionState.Connected) return
        nickname = newNickname.trim().ifEmpty { "anonymous" }
        val url = normalizeServerUrl(serverAddress)
        currentHost = url.removePrefix("ws://").removePrefix("wss://").substringBefore('/')
        supervisorJob?.cancel()
        eventsJob?.cancel()
        client.disconnect()
        client = GameClient(url)
        _connection.value = ConnectionState.Connecting
        eventsJob = viewModelScope.launch {
            try {
                client.events.collect { message -> handle(message) }
            } catch (e: Exception) {
                addLog("Соединение потеряно: ${e.message}")
            }
        }
        supervisorJob = viewModelScope.launch { connectionSupervisor() }
        viewModelScope.launch {
            val serverBuild = client.fetchServerBuild()
            _serverBuild.value = serverBuild
            if (serverBuild != null && serverBuild >= 0 && serverBuild != BUILD_NUMBER) {
                _updateAvailable.value = true
                addLog("Доступна новая сборка клиента №$serverBuild (у вас $BUILD_NUMBER)")
            }
        }
    }

    private suspend fun connectionSupervisor() {
        var everConnected = false
        var failureLogged = false
        while (true) {
            val opened = try {
                client.connect()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _connection.value = ConnectionState.Disconnected
            if (opened) everConnected = true
            if (!failureLogged) {
                failureLogged = true
                addLog(
                    if (everConnected) "Соединение с сервером потеряно. Повторная проверка каждую минуту."
                    else "Сервер недоступен. Повторная проверка каждую минуту."
                )
            }
            delay(RECONNECT_DELAY_MS)
        }
    }

    fun dismissUpdate() {
        _updateAvailable.value = false
    }

    fun updateClient() {
        val host = currentHost ?: run {
            addLog("Сначала подключитесь к серверу")
            return
        }
        if (_updateProgress.value != null) return
        viewModelScope.launch {
            try {
                _updateProgress.value = 0f
                addLog("Загрузка обновления с $host...")
                val context = getApplication<Application>()
                val file = File(context.cacheDir, "colt-express-update.apk")
                downloadApk("http://$host/apk", file)
                _updateProgress.value = null
                installApk(context, file)
            } catch (e: Exception) {
                _updateProgress.value = null
                addLog("Ошибка обновления: ${e.message}")
            }
        }
    }

    private suspend fun downloadApk(url: String, file: File) = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP $code")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            file.outputStream().use { output ->
                conn.inputStream.use { input ->
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total != null) {
                            _updateProgress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            _updateProgress.value = 1f
        } finally {
            conn.disconnect()
        }
    }

    private fun installApk(context: Context, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            addLog("Разрешите установку из этого источника, затем нажмите «Обновить клиент» ещё раз")
            return
        }
        if (!signaturesMatch(context, file)) {
            val host = currentHost ?: "сервер"
            addLog("Новую версию нельзя установить поверх старой (разные подписи). " +
                "Удалите «Colt Express» на устройстве, затем откройте в браузере http://$host/apk " +
                "и установите скачанный файл.")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        addLog("Запуск установщика обновления...")
    }

    @Suppress("DEPRECATION")
    private fun signaturesMatch(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val installed = try {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            return true
        }
        val archive = try {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            return true
        } ?: return true
        val installedCerts = installed.signatures?.map { it.toByteArray().toList() } ?: return true
        val archiveCerts = archive.signatures?.map { it.toByteArray().toList() } ?: return true
        return installedCerts == archiveCerts
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
                _characters.value = message.players.associate { it.id to it.character }
            }
            is RoomList -> {
                _rooms.value = message.rooms.map {
                    RoomListItem(it.roomId, it.players, it.maxPlayers, it.phase, it.ownerNickname)
                }
            }
            is GameStarted -> {
                _players.value = message.players.associate { it.id to it.nickname }
                _characters.value = message.players.associate { it.id to it.character }
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
        is Shot -> "${name(event.shooterId)} стреляет в ${roleName(event.targetId)}"
        is ShotMissed -> "${name(event.shooterId)} промахивается"
        is Punched -> "${name(event.actorId)} бьёт ${roleName(event.targetId)}"
        is LootDropped -> "${name(event.playerId)} бросает ${lootName(event.lootType)} в вагоне ${event.car}"
        is LootTaken -> "${name(event.playerId)} забирает ${lootName(event.lootType)} из вагона ${event.car}"
        is SheriffMoved -> "Шериф переходит в вагон ${event.to}"
        is BulletReceived -> if (event.neutral) "${name(event.playerId)} получает пулю от шерифа" else "${name(event.playerId)} получает пулю от ${roleName(event.fromId ?: "")}"
        is DrawAction -> "${name(event.playerId)} берёт карты"
    }

    private fun name(id: String): String = _players.value[id] ?: id

    private fun roleName(id: String): String =
        _characters.value[id]?.let { characterName(it) } ?: name(id)

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

internal fun choiceOptionLabel(opt: String, kind: String = "", players: List<Player> = emptyList()): String = when {
    opt == "B" -> "Назад"
    opt == "F" -> "Вперёд"
    opt == "ROOF" -> "На крышу / в вагон"
    opt.matches(Regex("B\\d")) -> "Назад ${opt.drop(1)}"
    opt.matches(Regex("F\\d")) -> "Вперёд ${opt.drop(1)}"
    opt == "PLAY2" -> "Сыграть 2 карты"
    opt == "DRAW6" -> "Взять 6 карт"
    opt == "DRAW3_PLAY1" -> "Взять 3, сыграть 1"
    kind == "SHOOT_TARGET" || kind == "PUNCH_VICTIM" ->
        players.firstOrNull { it.id == opt }?.let { characterName(it.character) } ?: opt
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
    "Cheyenne" -> "Шайенн"
    "Belle" -> "Красотка"
    "Tuco" -> "Туко"
    "Ghost" -> "Призрак"
    else -> character
}

private fun normalizeServerUrl(input: String): String {
    val host = input
        .trim()
        .removePrefix("ws://")
        .removePrefix("wss://")
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')
    return if (host.endsWith("/ws")) "ws://$host" else "ws://$host/ws"
}
