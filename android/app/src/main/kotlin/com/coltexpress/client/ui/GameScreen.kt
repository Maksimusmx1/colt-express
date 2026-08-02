package com.coltexpress.client.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coltexpress.client.BUILD_NUMBER
import com.coltexpress.client.ChatLine
import com.coltexpress.client.ConnectionState
import com.coltexpress.client.GameViewModel
import com.coltexpress.client.HandCard
import com.coltexpress.client.RoomListItem
import com.coltexpress.client.cardName
import com.coltexpress.client.characterName
import com.coltexpress.client.choiceKindName
import com.coltexpress.client.choiceOptionLabel

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val myId by viewModel.myId.collectAsStateWithLifecycle()
    val myCharacter by viewModel.myCharacter.collectAsStateWithLifecycle()
    val hand by viewModel.hand.collectAsStateWithLifecycle()
    val ownBullets by viewModel.ownBullets.collectAsStateWithLifecycle()
    val deckSize by viewModel.deckSize.collectAsStateWithLifecycle()
    val round by viewModel.round.collectAsStateWithLifecycle()
    val currentTurn by viewModel.currentTurn.collectAsStateWithLifecycle()
    val choice by viewModel.choice.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val gameOver by viewModel.gameOver.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()

    var nickname by remember { mutableStateOf("") }
    var serverAddress by remember {
        mutableStateOf(if (isEmulator()) "10.0.2.2:8080" else "100.102.196.74:8080")
    }
    var joinId by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var maxPlayers by remember { mutableStateOf(4) }
    var chatOpen by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    connection !is ConnectionState.Connected -> ConnectPanel(
                        connection = connection,
                        nickname = nickname,
                        serverAddress = serverAddress,
                        onNickname = { nickname = it },
                        onServerAddress = { serverAddress = it },
                        onConnect = { viewModel.connect(nickname, serverAddress) },
                    )
                    room == null -> RoomEntryPanel(
                        rooms = rooms,
                        joinId = joinId,
                        maxPlayers = maxPlayers,
                        onJoinId = { joinId = it },
                        onMaxPlayers = { maxPlayers = it },
                        onCreate = { viewModel.createRoom(maxPlayers) },
                        onJoin = { viewModel.joinRoom(joinId) },
                        onJoinRoom = { viewModel.joinRoom(it) },
                        onRefresh = { viewModel.refreshRooms() },
                    )
                    else -> GamePanel(
                        viewModel = viewModel,
                        roomId = room!!.roomId,
                        roomPhase = room!!.phase,
                        players = room!!.players,
                        myId = myId,
                        myCharacter = myCharacter,
                        ownerId = room!!.ownerId,
                        maxPlayers = room!!.maxPlayers,
                        hand = hand,
                        ownBullets = ownBullets,
                        deckSize = deckSize,
                        round = round,
                        currentTurn = currentTurn,
                        choice = choice,
                        log = log,
                        chat = chat,
                        gameOver = gameOver,
                        board = board,
                        draft = draft,
                        onDraft = { draft = it },
                        onSend = { viewModel.sendChat(draft); draft = "" },
                        chatOpen = chatOpen,
                        onChatClosed = { chatOpen = false },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Сборка $BUILD_NUMBER",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                OutlinedButton(
                    onClick = { viewModel.updateClient() },
                    enabled = updateProgress == null,
                ) {
                    if (updateProgress != null) {
                        LinearProgressIndicator(
                            progress = { updateProgress ?: 0f },
                            modifier = Modifier.width(24.dp).height(24.dp),
                        )
                        Text(" ${(updateProgress!! * 100).toInt()}%")
                    } else {
                        Text("Обновить клиент")
                    }
                }
                OutlinedButton(onClick = { chatOpen = true }) { Text("Чат") }
            }
    }
    }
}

@Composable
private fun ConnectPanel(
    connection: ConnectionState,
    nickname: String,
    serverAddress: String,
    onNickname: (String) -> Unit,
    onServerAddress: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Colt Express", style = MaterialTheme.typography.headlineMedium)
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(
                    color = when (connection) {
                        ConnectionState.Connected -> Color(0xFF2E7D32)
                        ConnectionState.Connecting -> Color(0xFFF9A825)
                        ConnectionState.Disconnected -> Color(0xFFC62828)
                    },
                    shape = CircleShape,
                ),
        )
    }
    Text(
        text = when (connection) {
            ConnectionState.Disconnected -> "Сервер недоступен"
            ConnectionState.Connecting -> "Подключение..."
            ConnectionState.Connected -> "Сервер подключён"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = nickname,
        onValueChange = onNickname,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Никнейм") },
        enabled = connection !is ConnectionState.Connecting,
    )
    OutlinedTextField(
        value = serverAddress,
        onValueChange = onServerAddress,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Адрес сервера, например 10.0.2.2:8080 или Tailscale-IP:8080") },
        enabled = connection !is ConnectionState.Connecting,
        singleLine = true,
    )
    Button(onClick = onConnect, enabled = connection !is ConnectionState.Connecting) {
        Text("Подключиться")
    }
}

@Composable
private fun RoomEntryPanel(
    rooms: List<RoomListItem>,
    joinId: String,
    maxPlayers: Int,
    onJoinId: (String) -> Unit,
    onMaxPlayers: (Int) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { onRefresh() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("Подключено. Создайте комнату или войдите в существующую.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Игроки:", style = MaterialTheme.typography.bodyMedium)
            (3..6).forEach { n ->
                OutlinedButton(
                    onClick = { onMaxPlayers(n) },
                    border = if (n == maxPlayers) BorderStroke(2.dp, Color(0xFF1976D2)) else null,
                ) { Text(if (n == maxPlayers) "[$n]" else "$n") }
            }
        }
        Button(onClick = onCreate) { Text("Создать комнату ($maxPlayers игроков)") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = joinId,
                onValueChange = onJoinId,
                modifier = Modifier.weight(1f),
                placeholder = { Text("ID комнаты") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onJoin() }),
            )
            Button(onClick = { keyboard?.hide(); onJoin() }) { Text("Войти") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Доступные комнаты:", style = MaterialTheme.typography.titleSmall)
            Button(onClick = onRefresh) { Text("Обновить") }
        }
        if (rooms.isEmpty()) {
            Text("Комнат пока нет.", style = MaterialTheme.typography.bodySmall)
        } else {
            rooms.forEach { r ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Комната ${r.ownerNickname}  ${r.roomId}  (${r.players}/${r.maxPlayers})",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { keyboard?.hide(); onJoinRoom(r.roomId) }) { Text("Войти") }
                }
            }
        }
    }
}

@Composable
private fun GamePanel(
    viewModel: GameViewModel,
    roomId: String,
    roomPhase: String,
    players: List<com.coltexpress.client.protocol.Player>,
    myId: String?,
    myCharacter: String?,
    ownerId: String,
    maxPlayers: Int,
    hand: List<HandCard>,
    ownBullets: Int,
    deckSize: Int,
    round: com.coltexpress.client.protocol.RoundStart?,
    currentTurn: String?,
    choice: com.coltexpress.client.protocol.ChoiceRequired?,
    log: List<String>,
    chat: List<ChatLine>,
    gameOver: com.coltexpress.client.protocol.GameEnded?,
    board: com.coltexpress.client.protocol.BoardState?,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    chatOpen: Boolean,
    onChatClosed: () -> Unit,
) {
    val myTurn = currentTurn == myId && roomPhase == "PLANNING"
    val isOwner = myId == ownerId
    val lastLog = log.lastOrNull()

    if (roomPhase == "LOBBY") {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(top = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Комната $roomId", style = MaterialTheme.typography.titleMedium)
            }
            if (myCharacter != null) {
                Text("Вы — ${characterName(myCharacter)}", style = MaterialTheme.typography.bodySmall)
            }
            Column {
                players.forEach { p ->
                    Text(
                        buildAnnotatedString {
                            append(p.nickname)
                            append(" — ")
                            withStyle(SpanStyle(color = characterColor(p.character), shadow = characterShadow(p.character))) {
                                append(characterName(p.character))
                            }
                            if (p.id == myId) append(" (вы)")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("${players.size}/$maxPlayers игроков")
                if (isOwner && players.size >= 3) {
                    Button(onClick = { viewModel.startGame() }) { Text("Начать игру") }
                } else {
                    Text("Ожидание начала игры владельцем (нужно минимум 3)...", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (lastLog != null) {
                Text(lastLog, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB00020))
            }
        }
        ChatSheet(chat, draft, onDraft, onSend, open = chatOpen, onDismiss = onChatClosed)
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(top = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        if (round != null) {
                            append("Раунд ${round!!.round}/5  ${round!!.mode}  (${round!!.turns} ходов)")
                        }
                        if (roomPhase == "ROBBERY") append("  Ограбление")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    buildAnnotatedString {
                        if (myCharacter != null) {
                            withStyle(SpanStyle(color = characterColor(myCharacter!!), shadow = characterShadow(myCharacter!!))) {
                                append(characterName(myCharacter!!))
                            }
                            append("  |  ")
                        }
                        append("Пули: $ownBullets  |  Колода: $deckSize")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        TrainView(board, players, myId)

        when {
            gameOver != null -> {
                Text("Игра окончена!", style = MaterialTheme.typography.headlineSmall)
                gameOver.results.forEach { r ->
                    Text("${r.nickname}: ${r.total} (добыча ${r.lootSum} + ${if (r.accuracyPrize) "приз 1000" else "0"})")
                }
            }
            myTurn -> {
                Text("Ваш ход!", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                Text("Нажмите карту, чтобы сыграть, или возьмите 3:", style = MaterialTheme.typography.bodySmall)
            }
            choice != null && choice!!.playerId == myId -> {
                Text("Выберите ${choiceKindName(choice!!.kind)}:", style = MaterialTheme.typography.titleMedium)
                choice!!.options.forEach { opt ->
                    OutlinedButton(
                        onClick = { viewModel.choose(opt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(choiceOptionLabel(opt, choice!!.kind, players)) }
                }
            }
            else -> {
                Text(
                    text = if (currentTurn != null) "Ожидание: ${name(players, currentTurn!!)}..." else "Думает...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (roomPhase == "PLANNING" && hand.isNotEmpty()) {
            Text("Ваши карты (${hand.size}):", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                hand.forEach { card ->
                    OutlinedButton(
                        onClick = { viewModel.play(card.type) },
                        enabled = myTurn && card.type != "BULLET",
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (card.type == "BULLET") "Пуля" else cardName(card.type),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (myTurn) {
                Button(onClick = { viewModel.draw() }) { Text("Взять 3 карты") }
            }
        }

        Text("Лог:", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.height(64.dp)) {
            items(log.takeLast(3)) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
    ChatSheet(chat, draft, onDraft, onSend, open = chatOpen, onDismiss = onChatClosed)
}

@Composable
private fun TrainView(
    board: com.coltexpress.client.protocol.BoardState?,
    players: List<com.coltexpress.client.protocol.Player>,
    myId: String?,
) {
    if (board == null) return
    val charById = players.associate { it.id to it.character }
    Column(Modifier.fillMaxWidth()) {
        Text("Состав поезда:", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(board.cars, key = { it.index }) { car ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (car.index == 0) Color(0xFF6D4C41) else Color(0xFFB0BEC5)),
                ) {
                    Column(Modifier.width(120.dp).padding(6.dp)) {
                        Text(
                            if (car.index == 0) "Локомотив" else "Вагон ${car.index}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (board.sheriffCar == car.index) {
                            Text("Шериф", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB8860B))
                        }
                        car.inside.forEach { id ->
                            val who = charById[id] ?: id
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = characterColor(who), shadow = characterShadow(who))) {
                                        append(characterName(who))
                                    }
                                    if (id == myId) append(" (вы)")
                                    append(": в вагоне")
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        car.roof.forEach { id ->
                            val who = charById[id] ?: id
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = characterColor(who), shadow = characterShadow(who))) {
                                        append(characterName(who))
                                    }
                                    if (id == myId) append(" (вы)")
                                    append(": на крыше")
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (car.lootInside + car.lootRoof > 0) {
                            Text("Добыча: ${car.lootInside + car.lootRoof}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSheet(
    chat: List<ChatLine>,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    open: Boolean,
    onDismiss: () -> Unit,
) {
    if (open) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            ChatSheetContent(chat, draft, onDraft, onSend)
        }
    }
}

@Composable
private fun ChatSheetContent(
    chat: List<ChatLine>,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(chat.takeLast(20)) { line ->
                Text(
                    "${line.sender}: ${line.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        line.isLocal -> Color(0xFF1976D2)
                        else -> Color.Unspecified
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Чат") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
            Button(
                onClick = {
                    keyboard?.hide()
                    onSend()
                },
            ) { Text("Отправить") }
        }
    }
}

private fun name(players: List<com.coltexpress.client.protocol.Player>, id: String): String =
    players.firstOrNull { it.id == id }?.nickname ?: id

private fun characterColor(character: String): Color = when (character) {
    "Doc" -> Color(0xFF03A9F4)
    "Django" -> Color.Black
    "Cheyenne" -> Color(0xFF4CAF50)
    "Tuco" -> Color(0xFFF44336)
    "Ghost" -> Color.White
    "Belle" -> Color(0xFF9C27B0)
    else -> Color.Unspecified
}

private fun characterShadow(character: String): Shadow? = when (character) {
    "Ghost" -> Shadow(color = Color(0xFF37474F), offset = Offset.Zero, blurRadius = 6f)
    else -> null
}

private fun isEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.contains("unknown") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK built for x86") ||
        Build.MANUFACTURER.contains("Genymotion") ||
        Build.PRODUCT.contains("sdk_gphone") ||
        (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
        Build.FINGERPRINT.contains("sdk_gphone")
