package com.coltexpress.server.game

import com.coltexpress.server.protocol.ChoiceRequired

enum class Phase { PLANNING, ROBBERY, FINISHED }
enum class ActionType { MOVE, LADDER, SHOOT, ROB, MARSHAL, PUNCH }
enum class RoundMode { STANDARD, TUNNEL, ON_THE_RUN, TURN_BACK }
enum class LootType { WALLET, GEM, CASE }
enum class ChoiceKind {
    MOVE_DIRECTION,
    SHOOT_TARGET,
    ROB_TOKEN,
    MARSHAL_DIRECTION,
    PUNCH_VICTIM,
    PUNCH_LOOT,
    PUNCH_DIRECTION,
}

data class RoundCard(val turns: Int, val mode: RoundMode)

sealed interface GameCard {
    val uid: String
}

data class ActionCard(override val uid: String, val type: ActionType) : GameCard
data class BulletCard(override val uid: String, val ownerId: String?, val neutral: Boolean) : GameCard

data class LootToken(val uid: String, val type: LootType, val value: Int)

data class PlayedCard(val card: ActionCard, val ownerId: String)

class PlayerState(val id: String, val nickname: String, val character: String, val seat: Int) {
    val deck = ArrayDeque<GameCard>()
    val hand = mutableListOf<GameCard>()
    val loot = mutableListOf<LootToken>()
    var ownBullets = 6
    var receivedBullets = 0
    var car = 0
    var onRoof = false
}

class CarState(val index: Int) {
    val inside = mutableSetOf<String>()
    val roof = mutableSetOf<String>()
    val lootInside = mutableListOf<LootToken>()
    val lootRoof = mutableListOf<LootToken>()
}

class PendingChoice(
    val id: String,
    val kind: ChoiceKind,
    val playerId: String,
    val cardType: ActionType?,
    val options: List<String>,
    val context: String,
) {
    fun toRequest() = ChoiceRequired(playerId, id, kind.name, options, cardType?.name, context)
}

class ResolveContext(val played: PlayedCard, val owner: PlayerState) {
    var step = 0
    var victimId: String? = null
}
