package com.coltexpress.server.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val DefaultJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

// ===== Client -> Server =====

@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("CreateRoom")
data class CreateRoom(val nickname: String, val maxPlayers: Int) : ClientMessage

@Serializable
@SerialName("JoinRoom")
data class JoinRoom(val roomId: String, val nickname: String) : ClientMessage

@Serializable
@SerialName("StartGame")
object StartGame : ClientMessage

@Serializable
@SerialName("PlayAction")
data class PlayAction(val cardType: String) : ClientMessage

@Serializable
@SerialName("DrawCards")
object DrawCards : ClientMessage

@Serializable
@SerialName("MakeChoice")
data class MakeChoice(val choiceId: String, val value: String) : ClientMessage

@Serializable
@SerialName("Say")
data class Say(val text: String) : ClientMessage

@Serializable
@SerialName("ListRooms")
object ListRooms : ClientMessage

// ===== Server -> Client =====

@Serializable
sealed interface ServerMessage

@Serializable
data class Player(val id: String, val nickname: String, val character: String = "")

@Serializable
@SerialName("Connected")
object Connected : ServerMessage

@Serializable
@SerialName("Welcome")
data class Welcome(val playerId: String, val nickname: String, val character: String = "") : ServerMessage

@Serializable
@SerialName("RoomUpdate")
data class RoomUpdate(
    val roomId: String,
    val players: List<Player>,
    val maxPlayers: Int,
    val phase: String,
    val ownerId: String,
) : ServerMessage

@Serializable
@SerialName("RoomList")
data class RoomList(val rooms: List<RoomSummary>) : ServerMessage

@Serializable
data class RoomSummary(
    val roomId: String,
    val players: Int,
    val maxPlayers: Int,
    val phase: String,
    val ownerNickname: String,
)

@Serializable
@SerialName("GameStarted")
data class GameStarted(
    val players: List<Player>,
    val rounds: Int,
    val firstPlayerId: String,
) : ServerMessage

@Serializable
@SerialName("RoundStart")
data class RoundStart(
    val round: Int,
    val mode: String,
    val turns: Int,
    val firstPlayerId: String,
) : ServerMessage

@Serializable
@SerialName("PlanningTurn")
data class PlanningTurnMsg(
    val playerId: String,
    val mode: String,
) : ServerMessage

@Serializable
@SerialName("CardPlayed")
data class CardPlayed(
    val playerId: String,
    val cardType: String?,
    val faceDown: Boolean,
) : ServerMessage

@Serializable
@SerialName("HandUpdate")
data class HandUpdate(
    val hand: List<CardInHand>,
    val deckSize: Int,
    val ownBullets: Int,
) : ServerMessage

@Serializable
data class CardInHand(val uid: String, val type: String)

@Serializable
@SerialName("BoardState")
data class BoardState(val cars: List<BoardCar>, val sheriffCar: Int) : ServerMessage

@Serializable
data class BoardCar(
    val index: Int,
    val inside: List<String>,
    val roof: List<String>,
    val lootInside: Int,
    val lootRoof: Int,
)

@Serializable
@SerialName("ChoiceRequired")
data class ChoiceRequired(
    val playerId: String,
    val choiceId: String,
    val kind: String,
    val options: List<String>,
    val cardType: String?,
    val context: String,
) : ServerMessage

@Serializable
@SerialName("GameEventMsg")
data class GameEventMsg(val event: GameEvent) : ServerMessage

@Serializable
@SerialName("GameEnded")
data class GameEnded(
    val results: List<PlayerResult>,
    val winnerId: String,
) : ServerMessage

@Serializable
data class PlayerResult(
    val playerId: String,
    val nickname: String,
    val lootSum: Int,
    val bulletsLeft: Int,
    val accuracyPrize: Boolean,
    val total: Int,
)

@Serializable
@SerialName("Chat")
data class Chat(val nickname: String, val text: String) : ServerMessage

@Serializable
@SerialName("Error")
data class Error(val message: String) : ServerMessage

@Serializable
data class Health(val status: String, val players: Int, val rooms: Int, val buildNumber: Int = -1)

// ===== Game events =====

@Serializable
sealed interface GameEvent

@Serializable
@SerialName("CardRevealed")
data class CardRevealed(val playerId: String, val cardType: String) : GameEvent

@Serializable
@SerialName("BanditMoved")
data class BanditMoved(val playerId: String, val fromCar: Int, val toCar: Int, val onRoof: Boolean) : GameEvent

@Serializable
@SerialName("BanditRoofed")
data class BanditRoofed(val playerId: String, val car: Int, val climbedUp: Boolean) : GameEvent

@Serializable
@SerialName("Shot")
data class Shot(val shooterId: String, val targetId: String) : GameEvent

@Serializable
@SerialName("ShotMissed")
data class ShotMissed(val shooterId: String) : GameEvent

@Serializable
@SerialName("Punched")
data class Punched(val actorId: String, val targetId: String) : GameEvent

@Serializable
@SerialName("LootDropped")
data class LootDropped(
    val playerId: String,
    val car: Int,
    val onRoof: Boolean,
    val lootType: String,
    val value: Int?,
) : GameEvent

@Serializable
@SerialName("LootTaken")
data class LootTaken(
    val playerId: String,
    val car: Int,
    val onRoof: Boolean,
    val lootType: String,
    val value: Int?,
) : GameEvent

@Serializable
@SerialName("SheriffMoved")
data class SheriffMoved(val from: Int, val to: Int) : GameEvent

@Serializable
@SerialName("BulletReceived")
data class BulletReceived(val playerId: String, val fromId: String?, val neutral: Boolean) : GameEvent

@Serializable
@SerialName("DrawAction")
data class DrawAction(val playerId: String) : GameEvent
