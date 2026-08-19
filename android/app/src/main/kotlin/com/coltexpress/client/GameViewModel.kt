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
import com.coltexpress.client.protocol.SessionReset
import com.coltexpress.client.protocol.Welcome
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
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

val BUILD_NUMBER: Int get() = BuildConfig.BUILD_NUMBER

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
data class PlayedCardEntry(val character: String, val cardType: String, val faceDown: Boolean)

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
    private var currentIsTls = false

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

    private val _loot = MutableStateFlow<List<String>>(emptyList())
    val loot: StateFlow<List<String>> = _loot.asStateFlow()

    private val _round = MutableStateFlow<RoundStart?>(null)
    val round: StateFlow<RoundStart?> = _round.asStateFlow()

    private val _currentTurn = MutableStateFlow<String?>(null)
    val currentTurn: StateFlow<String?> = _currentTurn.asStateFlow()

    private val _turnIndex = MutableStateFlow(0)
    val turnIndex: StateFlow<Int> = _turnIndex.asStateFlow()

    private val _currentTurnMode = MutableStateFlow("")
    val currentTurnMode: StateFlow<String> = _currentTurnMode.asStateFlow()

    private val _playedCards = MutableStateFlow<List<PlayedCardEntry>>(emptyList())
    val playedCards: StateFlow<List<PlayedCardEntry>> = _playedCards.asStateFlow()

    private val _choice = MutableStateFlow<ChoiceRequired?>(null)
    val choice: StateFlow<ChoiceRequired?> = _choice.asStateFlow()

    private val _faceDownAvailable = MutableStateFlow(false)
    val faceDownAvailable: StateFlow<Boolean> = _faceDownAvailable.asStateFlow()

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
        getApplication<Application>().getSharedPreferences("colt", Context.MODE_PRIVATE).edit()
            .putString("nickname", nickname)
            .putString("serverAddress", serverAddress)
            .apply()
        val url = normalizeServerUrl(serverAddress)
        currentHost = url.removePrefix("ws://").removePrefix("wss://").substringBefore('/')
        currentIsTls = url.startsWith("wss://")
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

    fun restartSession() {
        clearGameState()
        addLog("Запрос на сброс сессии...")
        if (_connection.value is ConnectionState.Connected) {
            viewModelScope.launch { client.resetSession() }
        }
    }

    /** Подключается автоматически при старте приложения: использует сохранённые ник и адрес. */
    fun autoConnect(defaultNickname: String, defaultAddress: String) {
        if (_connection.value is ConnectionState.Connected) return
        val prefs = getApplication<Application>().getSharedPreferences("colt", Context.MODE_PRIVATE)
        val savedNick = prefs.getString("nickname", "")?.trim().orEmpty()
        val savedAddr = prefs.getString("serverAddress", "")?.trim().orEmpty()
        connect(savedNick.ifEmpty { defaultNickname }, savedAddr.ifEmpty { defaultAddress })
    }

    private fun clearGameState() {
        _hand.value = emptyList()
        _ownBullets.value = 6
        _deckSize.value = 0
        _loot.value = emptyList()
        _round.value = null
        _currentTurn.value = null
        _choice.value = null
        _faceDownAvailable.value = false
        _log.value = emptyList()
        _gameOver.value = null
        _board.value = null
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
                downloadApk("${if (currentIsTls) "https" else "http"}://$host/apk", file)
                _updateProgress.value = null
                installApk(context, file)
            } catch (e: Exception) {
                _updateProgress.value = null
                addLog("Ошибка обновления: ${e.message}")
            }
        }
    }

    private suspend fun downloadApk(url: String, file: File) = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection()
        if (conn is HttpsURLConnection) {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            conn.sslSocketFactory = sslContext.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        if (conn is HttpURLConnection) {
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
            val scheme = if (currentIsTls) "https" else "http"
            addLog("Новую версию нельзя установить поверх старой (разные подписи). " +
                "Удалите «Colt Express» на устройстве, затем откройте в браузере $scheme://$host/apk " +
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

    fun play(cardType: String, faceDown: Boolean = false) {
        viewModelScope.launch { client.playAction(cardType, faceDown) }
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
            is Connected -> {
                _connection.value = ConnectionState.Connected
                _myId.value = null
                _myCharacter.value = null
                _room.value = null
                _rooms.value = emptyList()
                clearGameState()
                refreshRooms()
            }
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
                _faceDownAvailable.value = false
                _playedCards.value = emptyList()
                _room.value = _room.value?.copy(phase = "PLANNING")
                addLog("Раунд ${message.round}: ${message.mode}, ходов: ${message.turns}. Первый: ${roleName(message.firstPlayerId)}")
            }
            is PlanningTurnMsg -> {
                _currentTurn.value = message.playerId
                _turnIndex.value = message.turnIndex
                _currentTurnMode.value = message.mode
                _choice.value = null
                _faceDownAvailable.value = message.faceDownAvailable
                addLog("Планирование: ${roleName(message.playerId)}")
            }
            is CardPlayed -> {
                val what = if (message.faceDown) "карту рубашкой вверх" else cardName(message.cardType ?: "")
                addLog("${roleName(message.playerId)} играет $what")
                val character = _characters.value[message.playerId] ?: message.playerId
                _playedCards.value = _playedCards.value + PlayedCardEntry(character, message.cardType ?: "?", message.faceDown)
            }
            is HandUpdate -> {
                _hand.value = message.hand.map { HandCard(it.uid, it.type) }
                _deckSize.value = message.deckSize
                _ownBullets.value = message.ownBullets
                _loot.value = message.loot
            }
            is BoardState -> _board.value = message
            is ChoiceRequired -> {
                _choice.value = message
                if (message.playerId == _myId.value) {
                    addLog("Выбор: ${choiceKindName(message.kind)}")
                } else {
                    addLog("Ожидание: ${roleName(message.playerId)} выбирает ${choiceKindName(message.kind)}")
                }
            }
            is GameEventMsg -> {
                addLog(describe(message.event))
                if (message.event is Shot && message.event.shooterId == _myId.value) {
                    _ownBullets.value = (_ownBullets.value - 1).coerceAtLeast(0)
                }
                if (message.event is LootTaken && message.event.playerId == _myId.value) {
                    _loot.value = _loot.value + message.event.lootType
                }
                if (message.event is LootDropped && message.event.playerId == _myId.value) {
                    val list = _loot.value.toMutableList()
                    val idx = list.indexOf(message.event.lootType)
                    if (idx >= 0) list.removeAt(idx)
                    _loot.value = list
                }
            }
            is GameEnded -> {
                _gameOver.value = message
                addLog("Игра окончена. Победитель: ${roleName(message.winnerId)}")
                message.results.forEach { r ->
                    addLog("  ${r.nickname}: добыча=${r.lootSum} пули=${r.bulletsLeft} приз=${if (r.accuracyPrize) "+1000" else ""} итог=${r.total}")
                }
            }
            is Chat -> _chat.value += ChatLine(message.nickname, message.text)
            is SessionReset -> {
                clearGameState()
                _room.value = _room.value?.copy(phase = "LOBBY")
                addLog("Сессия сброшена. Все возвращаются в лобби.")
            }
            is Error -> addLog("Ошибка: ${message.message}")
        }
    }

    private fun describe(event: GameEvent): String = when (event) {
        is CardRevealed -> "Вскрытие: ${roleName(event.playerId)} сыграл ${cardName(event.cardType)}"
        is BanditMoved -> {
            val where = if (event.onRoof) "крыша" else "внутри"
            "${roleName(event.playerId)} перемещается в вагон ${event.toCar} ($where)"
        }
        is BanditRoofed -> "${roleName(event.playerId)} ${if (event.climbedUp) "забирается на крышу" else "спускается с крыши"} вагона ${event.car}"
        is Shot -> "${roleName(event.shooterId)} стреляет в ${roleName(event.targetId)}"
        is ShotMissed -> "${roleName(event.shooterId)} промахивается"
        is Punched -> "${roleName(event.actorId)} бьёт ${roleName(event.targetId)}"
        is LootDropped -> "${roleName(event.playerId)} бросает ${lootName(event.lootType)} в вагоне ${event.car}"
        is LootTaken -> "${roleName(event.playerId)} забирает ${lootName(event.lootType)} из вагона ${event.car}"

        is BulletReceived -> if (event.neutral) "${roleName(event.playerId)} получает пулю от шерифа" else "${roleName(event.playerId)} получает пулю от ${roleName(event.fromId ?: "")}"
        is DrawAction -> "${roleName(event.playerId)} берёт карты"
        is SheriffMoved -> "Шериф перемещается в вагон ${event.to}"
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
    "PUNCH_TAKE" -> "забрать упавшую добычу"
    "PUNCH_DIRECTION" -> "направление"
    "DOUBLE_OPTION" -> "вариант действия"
    else -> kind
}

internal fun choiceOptionLabel(opt: String, kind: String = "", players: List<Player> = emptyList()): String = when {
    opt == "B" -> "Влево"
    opt == "F" -> "Вправо"
    opt == "TAKE" -> "Забрать"
    opt == "LEAVE" -> "Оставить на месте"
    opt.matches(Regex("B\\d")) -> "Влево ${opt.drop(1)}"
    opt.matches(Regex("F\\d")) -> "Вправо ${opt.drop(1)}"
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
    val raw = input.trim()
    val isTls = raw.startsWith("wss://") || raw.startsWith("https://")
    val host = raw
        .removePrefix("ws://")
        .removePrefix("wss://")
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')
    val scheme = if (isTls) "wss" else "ws"
    return if (host.endsWith("/ws")) "${scheme}://${host}" else "${scheme}://${host}/ws"
}
