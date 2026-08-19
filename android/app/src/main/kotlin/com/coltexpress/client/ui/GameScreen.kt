package com.coltexpress.client.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

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
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val serverBuild by viewModel.serverBuild.collectAsStateWithLifecycle()

    val isEmu = remember { isEmulator() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("colt", Context.MODE_PRIVATE) }

    var nickname by remember {
        mutableStateOf(prefs.getString("nickname", "") ?: "")
    }
    var serverAddress by remember {
        mutableStateOf(
            prefs.getString("serverAddress", if (isEmu) "10.0.2.2:8080" else "100.102.196.74:8080") ?: ""
        )
    }
    var joinId by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var maxPlayers by remember { mutableStateOf(4) }
    var chatOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(500)
        if (isEmu && viewModel.connection.value is ConnectionState.Disconnected) {
            viewModel.autoConnect(botNickname(), serverAddress)
        }
    }

    LaunchedEffect(isEmu) {
        if (!isEmu) return@LaunchedEffect
        while (true) {
            val connected = viewModel.connection.value is ConnectionState.Connected
            val roomNow = viewModel.room.value
            if (connected && roomNow != null && roomNow.ownerId == viewModel.myId.value &&
                roomNow.phase == "LOBBY" && roomNow.players.size >= 3
            ) {
                viewModel.startGame()
            }
            if (connected && roomNow == null) {
                viewModel.refreshRooms()
                delay(2000)
                val target = viewModel.rooms.value.firstOrNull {
                    it.phase == "LOBBY" && it.players < it.maxPlayers
                }
                if (target != null) viewModel.joinRoom(target.roomId)
            }
            delay(2000)
        }
    }

    LaunchedEffect(isEmu, currentTurn) {
        if (!isEmu) return@LaunchedEffect
        val turn = currentTurn ?: return@LaunchedEffect
        delay(800)
        if (viewModel.room.value?.phase == "PLANNING" && turn == viewModel.myId.value) {
            val playable = viewModel.hand.value.filter { it.type != "BULLET" }
            if (playable.isNotEmpty()) viewModel.play(playable.first().type) else viewModel.draw()
        }
    }

    LaunchedEffect(isEmu, choice) {
        if (!isEmu) return@LaunchedEffect
        val c = choice ?: return@LaunchedEffect
        delay(800)
        if (c.playerId == viewModel.myId.value && c.options.isNotEmpty()) {
            viewModel.choose(c.options.first())
        }
    }

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
                    text = "РЎР±РѕСЂРєР° $BUILD_NUMBER",
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
                        Text("РћР±РЅРѕРІРёС‚СЊ РєР»РёРµРЅС‚")
                    }
                }
                if (isEmu && room?.ownerId == myId) {
                    OutlinedButton(onClick = { viewModel.restartSession() }) { Text("РџРµСЂРµР·Р°РїСѓСЃС‚РёС‚СЊ СЃРµСЃСЃРёСЋ") }
                }
            }
            if (updateAvailable) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissUpdate() },
                    title = { Text("Р”РѕСЃС‚СѓРїРЅРѕ РѕР±РЅРѕРІР»РµРЅРёРµ") },
                    text = {
                        Text(
                            "РЎРµСЂРІРµСЂ РїСЂРµРґР»Р°РіР°РµС‚ СЃР±РѕСЂРєСѓ РєР»РёРµРЅС‚Р° в„–${serverBuild ?: "?"} (Сѓ РІР°СЃ $BUILD_NUMBER). " +
                                "РћР±РЅРѕРІРёС‚СЊ РєР»РёРµРЅС‚ СЃРµР№С‡Р°СЃ?"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.dismissUpdate()
                                viewModel.updateClient()
                            },
                        ) { Text("РћР±РЅРѕРІРёС‚СЊ") }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissUpdate() }) { Text("РџРѕР·Р¶Рµ") }
                    },
                )
            }
    }
    }
}

private val PaperBg = Color(0xFFF2E9D4)
private val PaperCard = Color(0xFFE5D7B6)
private val PaperInk = Color(0xFF2B1D0F)
private val PaperInkMuted = Color(0xFF6B5B45)
private val PaperRust = Color(0xFF8C2F24)

@Composable
private fun ConnectPanel(
    connection: ConnectionState,
    nickname: String,
    serverAddress: String,
    onNickname: (String) -> Unit,
    onServerAddress: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(PaperBg)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Р•Р–Р•Р”РќР•Р’РќР«Р™ Р’Р•РЎРўРќРРљ Р–Р•Р›Р•Р—РќР«РҐ Р”РћР РћР“",
                style = MaterialTheme.typography.labelSmall,
                color = PaperInkMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Left,
            )
            Text(
                "РћСЃРЅРѕРІР°РЅ РІ 1871 РіРѕРґСѓ В· РџРµС‡Р°С‚Р°РµС‚СЃСЏ РїРѕ РјРµСЂРµ РїРѕСЃС‚СѓРїР»РµРЅРёСЏ РґРѕР±С‹С‡Рё",
                style = MaterialTheme.typography.labelSmall,
                color = PaperInkMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Left,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "The Coltshead Tribune",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Black,
                fontSize = 46.sp,
                color = PaperInk,
                textAlign = TextAlign.Center,
            )
            Text(
                "Р“РђР—Р•РўРђ Р—РђРџРђР”Рђ",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 7.sp,
                color = PaperRust,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("РЎСЂРµРґР°, 3 Р°РІРіСѓСЃС‚Р° 1887 Рі.", style = MaterialTheme.typography.labelSmall, color = PaperInkMuted)
                Text("в„– 42", style = MaterialTheme.typography.labelSmall, color = PaperInkMuted)
            }
            HorizontalDivider(thickness = 2.dp, color = PaperInk)
            HorizontalDivider(thickness = 1.dp, color = PaperInk)
            Spacer(Modifier.height(16.dp))
            Text(
                "Р‘РђРќР”Рђ РљРћР›Р¬РўРђ РЎРќРћР’Рђ Р’Р«РҐРћР”РРў РќРђ Р‘РћР›Р¬РЁРЈР® Р”РћР РћР“РЈ!",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = PaperInk,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "РўРµР»РµРіСЂР°С„ РґРѕРЅРѕСЃРёС‚: РїРѕ Р·Р°РїР°РґРЅС‹Рј С€С‚Р°С‚Р°Рј РєСѓСЂСЃРёСЂСѓРµС‚ СЃРѕСЃС‚Р°РІ СЃ Р·РѕР»РѕС‚РѕРј. " +
                    "Р”Р»СЏ РѕРіСЂР°Р±Р»РµРЅРёСЏ С‚СЂРµР±СѓСЋС‚СЃСЏ РѕС‚С‡Р°СЏРЅРЅС‹Рµ Р±Р°РЅРґРёС‚С‹. РџРѕРґРєР»СЋС‡РёС‚РµСЃСЊ Рє СЃРµСЂРІРµСЂСѓ Рё РїСЂРёСЃРѕРµРґРёРЅСЏР№С‚РµСЃСЊ Рє Р±Р°РЅРґРµ.",
                style = MaterialTheme.typography.bodyMedium,
                color = PaperInkMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            val status = when (connection) {
                ConnectionState.Connected -> "Р‘Р°РЅРґР° РЅР° СЃРІСЏР·Рё: СЃРµСЂРІРµСЂ РѕС‚РІРµС‡Р°РµС‚." to Color(0xFF2E7D32)
                ConnectionState.Connecting -> "РЈСЃС‚Р°РЅР°РІР»РёРІР°РµРј СЃРІСЏР·СЊ СЃ С‚РµР»РµРіСЂР°С„РѕРј..." to Color(0xFFB07A00)
                ConnectionState.Disconnected -> "РЎРІСЏР·СЊ РїСЂРµСЂРІР°РЅР°: СЃРµСЂРІРµСЂ РЅРµРґРѕСЃС‚СѓРїРµРЅ. РџСЂРѕРІРµСЂРєР° РєР°Р¶РґСѓСЋ РјРёРЅСѓС‚Сѓ." to Color(0xFFC62828)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PaperCard, RoundedCornerShape(6.dp))
                    .border(1.dp, PaperInk)
                    .padding(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(status.second, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "РўР•Р›Р•Р“Р РђР¤РќРђРЇ РЎР’РћР”РљРђ",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = PaperInk,
                        )
                    }
                    Text(status.first, style = MaterialTheme.typography.bodyMedium, color = PaperInk)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = onNickname,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Р’Р°С€Рµ РёРјСЏ (РґР»СЏ РїРѕРґРїРёСЃРё РІ РіР°Р·РµС‚Рµ)") },
                placeholder = { Text("Р‘Р°РЅРґРёС‚") },
                singleLine = true,
                enabled = connection !is ConnectionState.Connecting,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverAddress,
                onValueChange = onServerAddress,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("РђРґСЂРµСЃ С‚РµР»РµРіСЂР°С„Р° (СЃРµСЂРІРµСЂ)") },
                placeholder = { Text("colt.example.com:8080") },
                singleLine = true,
                enabled = connection !is ConnectionState.Connecting,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = connection !is ConnectionState.Connecting,
            ) {
                Text("РќРђР‘РћР  Р’ Р‘РђРќР”РЈ", fontWeight = FontWeight.Bold)
            }
        }
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
        Text("РџРѕРґРєР»СЋС‡РµРЅРѕ. РЎРѕР·РґР°Р№С‚Рµ РєРѕРјРЅР°С‚Сѓ РёР»Рё РІРѕР№РґРёС‚Рµ РІ СЃСѓС‰РµСЃС‚РІСѓСЋС‰СѓСЋ.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("РРіСЂРѕРєРё:", style = MaterialTheme.typography.bodyMedium)
            (3..6).forEach { n ->
                OutlinedButton(
                    onClick = { onMaxPlayers(n) },
                    border = if (n == maxPlayers) BorderStroke(2.dp, Color(0xFF1976D2)) else null,
                ) { Text(if (n == maxPlayers) "[$n]" else "$n") }
            }
        }
        Button(onClick = onCreate) { Text("РЎРѕР·РґР°С‚СЊ РєРѕРјРЅР°С‚Сѓ ($maxPlayers РёРіСЂРѕРєРѕРІ)") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = joinId,
                onValueChange = onJoinId,
                modifier = Modifier.weight(1f),
                placeholder = { Text("ID РєРѕРјРЅР°С‚С‹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onJoin() }),
            )
            Button(onClick = { keyboard?.hide(); onJoin() }) { Text("Р’РѕР№С‚Рё") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Р”РѕСЃС‚СѓРїРЅС‹Рµ РєРѕРјРЅР°С‚С‹:", style = MaterialTheme.typography.titleSmall)
            Button(onClick = onRefresh) { Text("РћР±РЅРѕРІРёС‚СЊ") }
        }
        if (rooms.isEmpty()) {
            Text("РљРѕРјРЅР°С‚ РїРѕРєР° РЅРµС‚.", style = MaterialTheme.typography.bodySmall)
        } else {
            rooms.forEach { r ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "РљРѕРјРЅР°С‚Р° ${r.ownerNickname}  ${r.roomId}  (${r.players}/${r.maxPlayers})",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { keyboard?.hide(); onJoinRoom(r.roomId) }) { Text("Р’РѕР№С‚Рё") }
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
                Text("РљРѕРјРЅР°С‚Р° $roomId", style = MaterialTheme.typography.titleMedium)
            }
            if (myCharacter != null) {
                Text("Р’С‹ вЂ” ${characterName(myCharacter)}", style = MaterialTheme.typography.bodySmall)
            }
            Column {
                players.forEach { p ->
                    Text(
                        buildAnnotatedString {
                            append(p.nickname)
                            append(" вЂ” ")
                            withStyle(SpanStyle(color = characterColor(p.character), shadow = characterShadow(p.character))) {
                                append(characterName(p.character))
                            }
                            if (p.id == myId) append(" (РІС‹)")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("${players.size}/$maxPlayers РёРіСЂРѕРєРѕРІ")
                if (isOwner && players.size >= 3) {
                    Button(onClick = { viewModel.startGame() }) { Text("РќР°С‡Р°С‚СЊ РёРіСЂСѓ") }
                } else {
                    Text("РћР¶РёРґР°РЅРёРµ РЅР°С‡Р°Р»Р° РёРіСЂС‹ РІР»Р°РґРµР»СЊС†РµРј (РЅСѓР¶РЅРѕ РјРёРЅРёРјСѓРј 3)...", style = MaterialTheme.typography.bodySmall)
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
                            append("Р Р°СѓРЅРґ ${round!!.round}/5  ${round!!.mode}  (${round!!.turns} С…РѕРґРѕРІ)")
                        }
                        if (roomPhase == "ROBBERY") append("  РћРіСЂР°Р±Р»РµРЅРёРµ")
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
                        append("РџСѓР»Рё: $ownBullets  |  РљРѕР»РѕРґР°: $deckSize")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Train3dPanel(board, players)

        when {
            gameOver != null -> {
                Text("РРіСЂР° РѕРєРѕРЅС‡РµРЅР°!", style = MaterialTheme.typography.headlineSmall)
                gameOver.results.forEach { r ->
                    Text("${r.nickname}: ${r.total} (РґРѕР±С‹С‡Р° ${r.lootSum} + ${if (r.accuracyPrize) "РїСЂРёР· 1000" else "0"})")
                }
            }
            myTurn -> {
                Text("Р’Р°С€ С…РѕРґ!", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                Text("РќР°Р¶РјРёС‚Рµ РєР°СЂС‚Сѓ, С‡С‚РѕР±С‹ СЃС‹РіСЂР°С‚СЊ, РёР»Рё РІРѕР·СЊРјРёС‚Рµ 3:", style = MaterialTheme.typography.bodySmall)
            }
            choice != null && choice!!.playerId == myId -> {
                Text("Р’С‹Р±РµСЂРёС‚Рµ ${choiceKindName(choice!!.kind)}:", style = MaterialTheme.typography.titleMedium)
                choice!!.options.forEach { opt ->
                    OutlinedButton(
                        onClick = { viewModel.choose(opt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(choiceOptionLabel(opt, choice!!.kind, players)) }
                }
            }
            else -> {
                Text(
                    text = if (currentTurn != null) "РћР¶РёРґР°РЅРёРµ: ${name(players, currentTurn!!)}..." else "Р”СѓРјР°РµС‚...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (roomPhase == "PLANNING" && hand.isNotEmpty()) {
            Text("Р’Р°С€Рё РєР°СЂС‚С‹ (${hand.size}):", style = MaterialTheme.typography.titleSmall)
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
                            text = if (card.type == "BULLET") "РџСѓР»СЏ" else cardName(card.type),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (myTurn && round?.mode != "ON_THE_RUN") {
                Button(onClick = { viewModel.draw() }) { Text("Р’Р·СЏС‚СЊ 3 РєР°СЂС‚С‹") }
            }
        }

        Text("Р›РѕРі:", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.height(64.dp)) {
            items(log.takeLast(3)) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
    ChatSheet(chat, draft, onDraft, onSend, open = chatOpen, onDismiss = onChatClosed)
}

private val TrainDeep = Color(0xFFC9B585)
private val SheriffYellow = Color(0xFFFBC02D)

@Composable
private fun TrainView(
    board: com.coltexpress.client.protocol.BoardState?,
    players: List<com.coltexpress.client.protocol.Player>,
    myId: String?,
) {
    if (board == null) return
    val charById = players.associate { it.id to it.character }
    Column(
        Modifier
            .fillMaxWidth()
            .background(PaperBg, RoundedCornerShape(6.dp))
            .border(1.dp, PaperInk)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = PaperInk)
            Text(
                "  РЎРћРЎРўРђР’ РџРћР•Р—Р”Рђ  ",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = PaperInk,
            )
            HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = PaperInk)
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(board.cars, key = { it.index }) { car ->
                TrainCar(
                    car = car,
                    charById = charById,
                    myId = myId,
                    isSheriff = board.sheriffCar == car.index,
                )
            }
        }
    }
}

@Composable
private fun TrainCar(
    car: com.coltexpress.client.protocol.BoardCar,
    charById: Map<String, String>,
    myId: String?,
    isSheriff: Boolean,
) {
    val isLoc = car.index == 0
    val w = if (isLoc) 180.dp else 150.dp
    val cabPad = if (isLoc) 66.dp else 0.dp
    Column(Modifier.width(w)) {
        Box(Modifier.width(w).height(160.dp)) {
            Canvas(Modifier.fillMaxSize()) { drawCarBody(isLoc) }
            if (car.roof.isNotEmpty()) {
                Box(Modifier.align(Alignment.TopCenter).padding(start = cabPad, top = 52.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        car.roof.forEach { id ->
                            MeepleFigure(characterColor(charById[id] ?: id), id == myId)
                        }
                    }
                }
            }
            if (car.inside.isNotEmpty() || isSheriff) {
                Box(Modifier.align(Alignment.TopCenter).padding(start = cabPad, top = 94.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        if (isSheriff) SheriffFigure()
                        car.inside.forEach { id ->
                            MeepleFigure(characterColor(charById[id] ?: id), id == myId)
                        }
                    }
                }
            }
            if (car.lootRoof.isNotEmpty()) {
                LootBadge(car.lootRoof.size, Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 56.dp))
            }
            if (car.lootInside.isNotEmpty()) {
                LootBadge(car.lootInside.size, Modifier.align(Alignment.TopStart).padding(start = cabPad + 10.dp, top = 88.dp))
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (isLoc) "Р›РћРљРћРњРћРўРР’" else "Р’РђР“РћРќ в„–${car.index}",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = PaperInk,
                )
                if (isSheriff) {
                    Text("РЁРµСЂРёС„", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PaperRust)
                }
            }
            CarLegend("Р’ РІР°РіРѕРЅРµ: ", car.inside, charById)
            CarLegend("РќР° РєСЂС‹С€Рµ: ", car.roof, charById)
            if ((car.lootInside.size + car.lootRoof.size) > 0) {
                Text("Добыча: ${car.lootInside.size + car.lootRoof.size}", fontSize = 9.sp, color = PaperInkMuted)
            }
        }
    }
}

@Composable
private fun CarLegend(prefix: String, ids: List<String>, charById: Map<String, String>) {
    val chars = ids.map { charById[it] ?: it }.filter { it.isNotBlank() }
    if (chars.isEmpty()) return
    Text(
        buildAnnotatedString {
            append(prefix)
            chars.forEachIndexed { i, ch ->
                if (i > 0) append(", ")
                withStyle(SpanStyle(color = characterInk(ch), fontWeight = FontWeight.Bold)) {
                    append(characterName(ch))
                }
            }
        },
        fontSize = 9.sp,
        color = PaperInkMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LootBadge(count: Int, modifier: Modifier) {
    Box(
        modifier
            .background(PaperRust, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text("$$count", color = Color(0xFFF7EFDC), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MeepleFigure(color: Color, isMe: Boolean) {
    Canvas(Modifier.size(width = 20.dp, height = 28.dp)) {
        drawMeeple(color, PaperInk, isMe)
    }
}

@Composable
private fun SheriffFigure() {
    Canvas(Modifier.size(width = 22.dp, height = 30.dp)) {
        drawMeeple(SheriffYellow, PaperInk, isMe = false)
    }
}

private fun characterInk(character: String): Color = when (character) {
    "Ghost" -> Color(0xFF37474F)
    else -> characterColor(character)
}

private fun DrawScope.drawMeeple(color: Color, ink: Color, isMe: Boolean) {
    val d = density
    val cx = size.width / 2f
    val base = size.height
    drawOval(
        color = ink.copy(alpha = 0.18f),
        topLeft = Offset(cx - 6f * d, base - 1.5f * d),
        size = Size(12f * d, 3f * d),
    )
    val head = Rect(cx - 4.2f * d, base - 24.5f * d, cx + 4.2f * d, base - 15.5f * d)
    drawOval(color, topLeft = Offset(head.left, head.top), size = Size(head.width, head.height))
    drawOval(ink, topLeft = Offset(head.left, head.top), size = Size(head.width, head.height), style = Stroke(width = 1.1f * d))
    val body = Path().apply {
        moveTo(cx - 4.5f * d, base - 15f * d)
        lineTo(cx - 6.5f * d, base - 13f * d)
        lineTo(cx - 3.5f * d, base - 8f * d)
        lineTo(cx - 6f * d, base - 1f * d)
        lineTo(cx + 6f * d, base - 1f * d)
        lineTo(cx + 3.5f * d, base - 8f * d)
        lineTo(cx + 6.5f * d, base - 13f * d)
        lineTo(cx + 4.5f * d, base - 15f * d)
        close()
    }
    drawPath(body, color)
    drawPath(body, ink, style = Stroke(width = 1.1f * d))
    if (isMe) {
        drawLine(PaperRust, Offset(cx - 6.5f * d, base), Offset(cx + 6.5f * d, base), strokeWidth = 2.2f * d)
    }
}

private fun DrawScope.drawCarBody(isLoc: Boolean) {
    val d = density
    val w = size.width
    val h = size.height
    fun p(v: Float) = v * d
    val ink = PaperInk
    val wood = PaperCard
    val deep = TrainDeep
    val muted = PaperInkMuted
    val rust = PaperRust
    val thin = Stroke(width = p(1.2f))

    if (isLoc) {
        val cowcatcher = Path().apply {
            moveTo(p(4f), h - p(30f))
            lineTo(p(10f), h - p(52f))
            lineTo(p(34f), h - p(30f))
            close()
        }
        drawPath(cowcatcher, wood)
        drawPath(cowcatcher, ink, style = thin)
        drawRoundRect(
            wood,
            topLeft = Offset(p(12f), h - p(82f)),
            size = Size(p(40f), p(44f)),
            cornerRadius = CornerRadius(p(6f)),
            style = Fill,
        )
        drawRoundRect(
            ink,
            topLeft = Offset(p(12f), h - p(82f)),
            size = Size(p(40f), p(44f)),
            cornerRadius = CornerRadius(p(6f)),
            style = thin,
        )
        drawLine(muted, Offset(p(28f), h - p(78f)), Offset(p(28f), h - p(44f)), strokeWidth = p(1.5f))
        drawLine(muted, Offset(p(42f), h - p(78f)), Offset(p(42f), h - p(44f)), strokeWidth = p(1.5f))
        drawRect(wood, topLeft = Offset(p(18f), h - p(102f)), size = Size(p(8f), p(20f)))
        drawRect(ink, topLeft = Offset(p(18f), h - p(102f)), size = Size(p(8f), p(20f)), style = thin)
        drawRect(rust, topLeft = Offset(p(15f), h - p(108f)), size = Size(p(14f), p(6f)))
        drawCircle(rust, radius = p(6f), center = Offset(p(18f), h - p(58f)))
        drawCircle(ink, radius = p(6f), center = Offset(p(18f), h - p(58f)), style = thin)
        drawRect(wood, topLeft = Offset(p(56f), h - p(80f)), size = Size(p(120f), p(42f)))
        drawRect(ink, topLeft = Offset(p(56f), h - p(80f)), size = Size(p(120f), p(42f)), style = thin)
        drawRect(deep, topLeft = Offset(p(60f), h - p(76f)), size = Size(p(112f), p(38f)))
        drawRect(wood, topLeft = Offset(p(54f), h - p(86f)), size = Size(w - p(58f), p(6f)))
        drawRect(ink, topLeft = Offset(p(54f), h - p(86f)), size = Size(w - p(58f), p(6f)), style = thin)
    } else {
        drawRect(wood, topLeft = Offset(p(4f), h - p(80f)), size = Size(w - p(8f), p(6f)))
        drawRect(ink, topLeft = Offset(p(4f), h - p(80f)), size = Size(w - p(8f), p(6f)), style = thin)
        drawRoundRect(
            deep,
            topLeft = Offset(p(10f), h - p(74f)),
            size = Size(w - p(20f), p(36f)),
            cornerRadius = CornerRadius(p(4f)),
            style = Fill,
        )
        drawRoundRect(
            ink,
            topLeft = Offset(p(10f), h - p(74f)),
            size = Size(w - p(20f), p(36f)),
            cornerRadius = CornerRadius(p(4f)),
            style = thin,
        )
    }

    drawRect(wood, topLeft = Offset(p(6f), h - p(38f)), size = Size(w - p(12f), p(4f)))
    drawRect(ink, topLeft = Offset(p(6f), h - p(38f)), size = Size(w - p(12f), p(4f)), style = thin)
    drawRect(muted, topLeft = Offset(p(8f), h - p(34f)), size = Size(w - p(16f), p(6f)))
    val wheelXs = if (isLoc) listOf(p(34f), p(110f), p(158f)) else listOf(w * 0.3f, w * 0.7f)
    wheelXs.forEach { cx ->
        drawCircle(wood, radius = p(10f), center = Offset(cx, h - p(20f)))
        drawCircle(ink, radius = p(10f), center = Offset(cx, h - p(20f)), style = Stroke(width = p(1.6f)))
        drawCircle(ink, radius = p(3f), center = Offset(cx, h - p(20f)))
        drawLine(muted, Offset(cx - p(6f), h - p(20f)), Offset(cx + p(6f), h - p(20f)), strokeWidth = p(1.2f))
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
                placeholder = { Text("Р§Р°С‚") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
            Button(
                onClick = {
                    keyboard?.hide()
                    onSend()
                },
            ) { Text("РћС‚РїСЂР°РІРёС‚СЊ") }
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

private fun botNickname(): String {
    val tags = listOf("Р”РёРЅР°РјРёС‚", "РљСѓРІР°Р»РґР°", "РЎРµРґР»Рѕ", "РџС‹Р»СЊ", "Р“СЂРѕР·Р°", "Р Р¶Р°РІС‹Р№")
    return "${tags.random()}-${Random.nextInt(100, 999)}"
}
