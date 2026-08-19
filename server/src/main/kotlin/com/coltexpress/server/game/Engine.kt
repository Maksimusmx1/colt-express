package com.coltexpress.server.game

import com.coltexpress.server.protocol.BanditMoved
import com.coltexpress.server.protocol.BanditRoofed
import com.coltexpress.server.protocol.BulletReceived
import com.coltexpress.server.protocol.CardRevealed
import com.coltexpress.server.protocol.ChoiceRequired
import com.coltexpress.server.protocol.GameEvent
import com.coltexpress.server.protocol.LootDropped
import com.coltexpress.server.protocol.LootTaken
import com.coltexpress.server.protocol.PlayerResult
import com.coltexpress.server.protocol.Punched
import com.coltexpress.server.protocol.SheriffMoved
import com.coltexpress.server.protocol.Shot
import com.coltexpress.server.protocol.ShotMissed
import kotlin.random.Random

sealed interface EngineUpdate {
    data class RoundStarted(val round: Int, val mode: String, val turns: Int, val firstPlayerId: String, val modes: List<String> = listOf(mode)) : EngineUpdate
    data class PlanningTurn(val playerId: String, val mode: String, val faceDownAvailable: Boolean, val turnIndex: Int = 0) : EngineUpdate
    data class PlanningChoice(val choice: ChoiceRequired) : EngineUpdate
    data class Robbery(val events: List<GameEvent>, val choice: ChoiceRequired?) : EngineUpdate
    data class GameOver(val results: List<PlayerResult>, val winnerId: String) : EngineUpdate
}

data class PlayResult(val played: Boolean, val updates: List<EngineUpdate>, val faceDown: Boolean = false)
data class DrawResult(val drew: Boolean, val updates: List<EngineUpdate>)

private sealed interface Outcome {
    data class NeedInput(val choice: PendingChoice) : Outcome
    data object Complete : Outcome
    data object NoEffect : Outcome
}

/**
 * Движок базовой игры Colt Express (3-6 игроков).
 * Правила: 5 раундов, 2 фазы (Планирование -> Ограбление), 6 действий,
 * встречи с шерифом, пули, финальный подсчёт с призом самому меткому стрелку.
 */
class Engine(
    val players: List<PlayerState>,
    val cars: List<CarState>,
    val roundDeck: List<RoundCard>,
    firstPlayer: String,
    private val neutralBullets: ArrayDeque<BulletCard>,
    private val random: Random,
) {
    val seatOrder: List<String> = players.sortedBy { it.seat }.map { it.id }
    private val byId: Map<String, PlayerState> = players.associateBy { it.id }

    var firstPlayer: String = firstPlayer
        private set
    var sheriffIndex: Int = 0
        private set
    var currentRound: Int = 0
        private set
    var phase: Phase = Phase.PLANNING
        private set

    val roundCard: RoundCard get() = roundDeck[currentRound - 1]

    private val playStack = mutableListOf<PlayedCard>()
    private val turnQueue = ArrayDeque<String>()
    private var currentActor: String? = null

    private var pendingPlays = 0
    private var turnsPlayed = 0
    private var playersInTurn = 0
    private var pendingChoice: PendingChoice? = null
    private var resolveCtx: ResolveContext? = null
    private val resolveQueue = ArrayDeque<PlayedCard>()
    private val eventBuffer = mutableListOf<GameEvent>()
    private var choiceCounter = 0

    companion object {
        private val DOUBLE_OPTIONS = listOf("PLAY2", "DRAW6", "DRAW3_PLAY1")
        private const val GHOST = "Ghost"
        private const val CHEYENNE = "Cheyenne"
        private const val DOC = "Doc"
        private const val BELLE = "Belle"
        private const val TUCO = "Tuco"
        private const val DJANGO = "Django"
    }

    /** Режим текущего хода раунда. */
    private fun currentTurnMode(): RoundMode {
        val modes = roundCard.modes
        return if (modes.isEmpty()) roundCard.mode else modes[turnsPlayed % modes.size]
    }

    fun player(id: String): PlayerState? = byId[id]

    /** Может ли игрок сейчас сыграть карту в фазе планирования. */
    fun canPlayAs(id: String): Boolean = phase == Phase.PLANNING && currentActor == id && pendingChoice == null

    /** Активный выбор, требующий решения указанного игрока (или null). */
    fun pendingChoiceFor(id: String): PendingChoice? = pendingChoice?.takeIf { it.playerId == id }

    /** Игрок, которому сейчас нужно действовать (ход в планировании или выбор в ограблении), или null. */
    fun actorNeedingInput(): String? = pendingChoice?.playerId ?: (if (phase == Phase.PLANNING) currentActor else null)

    fun abort() {
        phase = Phase.FINISHED
    }

    // ===== Раунд =====

    fun startRound(): List<EngineUpdate> {
        currentRound++
        if (currentRound > 1) firstPlayer = nextSeat(firstPlayer)
        val fpChar = player(firstPlayer)?.character ?: "?"
        println("[startRound] round=$currentRound firstPlayer=$fpChar")
        phase = Phase.PLANNING
        players.forEach { p ->
            p.hand.clear()
            p.deck.toMutableList().shuffled(random).let { p.deck.clear(); p.deck.addAll(it) }
            val n = if (p.character == DOC) 7 else 6
            draw(p, n)
            p.firstActionDone = false
            println("[deal] round $currentRound ${p.nickname} (${p.character}): $n cards -> "
                + p.hand.filterIsInstance<ActionCard>().joinToString { it.type.name })
        }
        playStack.clear()
        turnQueue.clear()
        currentActor = null
        pendingPlays = 0
        turnsPlayed = 0
        playersInTurn = 0
        pendingChoice = null
        resolveCtx = null
        resolveQueue.clear()
        eventBuffer.clear()
        buildQueueForTurn(0)
        return listOf(EngineUpdate.RoundStarted(currentRound, roundCard.mode.name, roundCard.turns, firstPlayer, roundCard.modes.map { it.name })) +
            advancePlanning()
    }

    // ===== Планирование =====

    fun playCard(playerId: String, cardType: String, faceDown: Boolean = false): PlayResult {
        if (phase != Phase.PLANNING || pendingChoice != null || currentActor != playerId) return PlayResult(false, emptyList())
        val p = byId.getValue(playerId)
        val card = p.hand.filterIsInstance<ActionCard>().firstOrNull { it.type.name == cardType }
            ?: return PlayResult(false, emptyList())
        p.hand.remove(card)
        val effectiveFaceDown = currentTurnMode() == RoundMode.TUNNEL ||
            (faceDown && p.character == GHOST && !p.firstActionDone)
        p.firstActionDone = true
        playStack.add(PlayedCard(card, playerId, effectiveFaceDown))
        if (pendingPlays > 0) pendingPlays--
        return PlayResult(true, advancePlanning(), effectiveFaceDown)
    }

    fun drawCards(playerId: String): DrawResult {
        if (phase != Phase.PLANNING || pendingChoice != null || currentActor != playerId) return DrawResult(false, emptyList())
        if (currentTurnMode() == RoundMode.DOUBLE) return DrawResult(false, emptyList())
        val p = byId.getValue(playerId)
        draw(p, 3)
        p.firstActionDone = true
        return DrawResult(true, advancePlanning())
    }

    private fun advancePlanning(): List<EngineUpdate> {
        if (pendingPlays > 0 && currentActor != null) {
            val actor = byId[currentActor]
            if (actor == null || actor.hand.none { it is ActionCard }) {
                pendingPlays = 0
            } else {
                val ti = turnsPlayed % roundCard.modes.size
                return listOf(EngineUpdate.PlanningTurn(currentActor!!, currentTurnMode().name, faceDownAvailableFor(currentActor!!), ti))
            }
        }
        if (turnQueue.isEmpty()) {
            if (playersInTurn >= players.size) {
                turnsPlayed++
                playersInTurn = 0
            }
            if (turnsPlayed >= roundCard.turns) return beginRobbery()
            buildQueueForTurn(turnsPlayed)
        }
        val actor = turnQueue.removeFirst()
        currentActor = actor
        val ti = turnsPlayed % roundCard.modes.size
        if (currentTurnMode() == RoundMode.DOUBLE) {
            val choice = makeChoice(actor, ChoiceKind.DOUBLE_OPTION, null, DOUBLE_OPTIONS, "PLANNING")
            pendingChoice = choice
            return listOf(EngineUpdate.PlanningChoice(choice.toRequest()))
        }
        playersInTurn++
        return listOf(EngineUpdate.PlanningTurn(actor, currentTurnMode().name, faceDownAvailableFor(actor), ti))
    }

    /** Способность Призрака: первый ход раунда можно сыграть взакрытую. */
    private fun faceDownAvailableFor(playerId: String): Boolean {
        val p = byId[playerId] ?: return false
        return p.character == GHOST && !p.firstActionDone
    }

    // ===== Ограбление =====

    private fun beginRobbery(): List<EngineUpdate> {
        phase = Phase.ROBBERY
        players.forEach { p ->
            p.hand.forEach { p.deck.addFirst(it) }
            p.hand.clear()
        }
        resolveQueue.clear()
        playStack.forEach { resolveQueue.addLast(it) }
        pendingChoice = null
        resolveCtx = null
        return continueRobbery()
    }

    private fun continueRobbery(): List<EngineUpdate> {
        while (true) {
            if (resolveCtx == null) {
                val played = resolveQueue.removeFirstOrNull()
                if (played == null) {
                    val tail = mutableListOf<EngineUpdate>()
                    if (eventBuffer.isNotEmpty()) {
                        tail += EngineUpdate.Robbery(eventBuffer.toList(), null)
                        eventBuffer.clear()
                    }
                    tail += endRoundOrGame()
                    return tail
                }
                resolveCtx = ResolveContext(played, byId.getValue(played.ownerId))
                eventBuffer += CardRevealed(played.ownerId, played.card.type.name)
            }
            val ctx = resolveCtx!!
            when (val out = stepResolve(ctx)) {
                is Outcome.NeedInput -> {
                    val choice = out.choice
                    if (choice.kind == ChoiceKind.PUNCH_DIRECTION && choice.options.size == 1) {
                        val done = applyStep(ctx, choice.options.single())
                        if (done) {
                            ctx.owner.deck.addFirst(ctx.played.card)
                            resolveCtx = null
                        }
                        continue
                    }
                    pendingChoice = choice
                    val ev = eventBuffer.toList()
                    eventBuffer.clear()
                    return listOf(EngineUpdate.Robbery(ev, choice.toRequest()))
                }
                is Outcome.Complete -> {
                    ctx.owner.deck.addFirst(ctx.played.card)
                    resolveCtx = null
                }
                is Outcome.NoEffect -> {
                    ctx.owner.deck.addFirst(ctx.played.card)
                    resolveCtx = null
                }
            }
        }
    }

    private fun endRoundOrGame(): List<EngineUpdate> {
        return if (currentRound >= roundDeck.size) {
            phase = Phase.FINISHED
            val results = computeResults()
            listOf(EngineUpdate.GameOver(results, computeWinner(results)))
        } else {
            startRound()
        }
    }

    fun submitChoice(playerId: String, choiceId: String, value: String): List<EngineUpdate> {
        val c = pendingChoice ?: return emptyList()
        if (c.playerId != playerId || c.id != choiceId || value !in c.options) return emptyList()
        if (phase == Phase.PLANNING) {
            if (c.kind != ChoiceKind.DOUBLE_OPTION) return emptyList()
            pendingChoice = null
            return applyDoubleOption(value)
        }
        pendingChoice = null
        val ctx = resolveCtx ?: return emptyList()
        val done = applyStep(ctx, value)
        if (done) {
            ctx.owner.deck.addFirst(ctx.played.card)
            resolveCtx = null
        }
        return continueRobbery()
    }

    /** Применяет выбранный вариант «Сдвоенного хода» (2 действия игрока). */
    private fun applyDoubleOption(value: String): List<EngineUpdate> {
        val p = byId[currentActor ?: return emptyList()] ?: return emptyList()
        playersInTurn++
        when (value) {
            "PLAY2" -> pendingPlays = 2
            "DRAW6" -> {
                draw(p, 6)
                pendingPlays = 0
            }
            "DRAW3_PLAY1" -> {
                draw(p, 3)
                pendingPlays = 1
            }
        }
        return advancePlanning()
    }

    // ===== Разрешение одной карты =====

    private fun stepResolve(ctx: ResolveContext): Outcome {
        val p = ctx.owner
        return when (ctx.played.card.type) {
            ActionType.MOVE -> {
                val opts = moveOptions(p)
                if (opts.isEmpty()) Outcome.NoEffect
                else Outcome.NeedInput(makeChoice(p.id, ChoiceKind.MOVE_DIRECTION, ActionType.MOVE, opts, "ROBBERY"))
            }
            ActionType.LADDER -> {
                applyLadder(p)
                Outcome.Complete
            }
            ActionType.SHOOT -> {
                if (p.ownBullets <= 0) {
                    eventBuffer += ShotMissed(p.id)
                    Outcome.NoEffect
                } else {
                    val targets = shootTargets(p)
                    if (targets.isEmpty()) {
                        eventBuffer += ShotMissed(p.id)
                        Outcome.NoEffect
                    } else {
                        Outcome.NeedInput(makeChoice(p.id, ChoiceKind.SHOOT_TARGET, ActionType.SHOOT, targets, "ROBBERY"))
                    }
                }
            }
            ActionType.ROB -> {
                val loot = lootAt(p)
                if (loot.isEmpty()) Outcome.NoEffect
                else Outcome.NeedInput(makeChoice(p.id, ChoiceKind.ROB_TOKEN, ActionType.ROB, loot.map { it.uid }, "ROBBERY"))
            }
            ActionType.MARSHAL -> {
                Outcome.NeedInput(makeChoice(p.id, ChoiceKind.MARSHAL_DIRECTION, ActionType.MARSHAL, sheriffOptions(), "ROBBERY"))
            }
            ActionType.PUNCH -> {
                when (ctx.step) {
                    0 -> {
                        val victims = punchVictims(p)
                        if (victims.isEmpty()) Outcome.NoEffect
                        else Outcome.NeedInput(makeChoice(p.id, ChoiceKind.PUNCH_VICTIM, ActionType.PUNCH, victims, "ROBBERY"))
                    }
                    1 -> {
                        val victim = byId.getValue(ctx.victimId!!)
                        val token = chooseDropToken(victim)
                        if (token != null) {
                            applyPunchDrop(victim, token)
                            ctx.droppedToken = token
                        }
                        val dropped = ctx.droppedToken
                        if (dropped != null && dropped.type == LootType.WALLET && p.character == CHEYENNE) {
                            Outcome.NeedInput(makeChoice(p.id, ChoiceKind.PUNCH_TAKE, ActionType.PUNCH, listOf("TAKE", "LEAVE"), "ROBBERY"))
                        } else {
                            Outcome.NeedInput(makeChoice(p.id, ChoiceKind.PUNCH_DIRECTION, ActionType.PUNCH, punchDirections(victim), "ROBBERY"))
                        }
                    }
                    2 -> {
                        val victim = byId.getValue(ctx.victimId!!)
                        Outcome.NeedInput(makeChoice(p.id, ChoiceKind.PUNCH_DIRECTION, ActionType.PUNCH, punchDirections(victim), "ROBBERY"))
                    }
                    else -> Outcome.Complete
                }
            }
        }
    }

    /** Применяет выбранное значение для текущего шага. Возвращает true, если карта полностью применена. */
    private fun applyStep(ctx: ResolveContext, value: String): Boolean {
        val p = ctx.owner
        return when (ctx.played.card.type) {
            ActionType.MOVE -> {
                applyMove(ctx, value)
                true
            }
            ActionType.LADDER -> {
                applyLadder(p)
                true
            }
            ActionType.SHOOT -> {
                applyShot(p, value)
                true
            }
            ActionType.ROB -> {
                applyRob(p, value)
                true
            }
            ActionType.MARSHAL -> {
                applyMarshal(value)
                true
            }
            ActionType.PUNCH -> when (ctx.step) {
                0 -> {
                    ctx.victimId = value
                    ctx.step = 1
                    eventBuffer += Punched(p.id, value)
                    false
                }
                1 -> {
                    if (value == "TAKE" || value == "LEAVE") {
                        val victim = byId.getValue(ctx.victimId!!)
                        if (value == "TAKE") {
                            ctx.droppedToken?.let { applyCheyenneTake(p, victim, it) }
                        }
                        ctx.droppedToken = null
                        ctx.step = 2
                        false
                    } else {
                        applyPunchMove(ctx.victimId!!, value)
                        ctx.step = 3
                        true
                    }
                }
                else -> {
                    applyPunchMove(ctx.victimId!!, value)
                    ctx.step = 3
                    true
                }
            }
        }
    }

    // ===== Применение действий =====

    private fun applyMove(ctx: ResolveContext, value: String) {
        val p = ctx.owner
        val from = p.car
        val to = (from + parseDelta(value)).coerceIn(0, cars.lastIndex)
        movePlayer(p, to)
        eventBuffer += BanditMoved(p.id, from, to, p.onRoof)
        if (!p.onRoof) sheriffEncounter()
    }

    private fun applyLadder(p: PlayerState) {
        val car = cars[p.car]
        val climb = !p.onRoof
        if (p.onRoof) car.roof.remove(p.id) else car.inside.remove(p.id)
        p.onRoof = climb
        if (climb) car.roof.add(p.id) else car.inside.add(p.id)
        eventBuffer += BanditRoofed(p.id, p.car, climb)
        if (!p.onRoof) sheriffEncounter()
    }

    private fun applyShot(shooter: PlayerState, targetId: String) {
        shooter.ownBullets--
        val target = byId.getValue(targetId)
        target.deck.addFirst(BulletCard(uuid(), shooter.id, neutral = false))
        target.receivedBullets++
        eventBuffer += Shot(shooter.id, targetId)
        eventBuffer += BulletReceived(targetId, fromId = shooter.id, neutral = false)
        if (shooter.character == DJANGO) pushTarget(shooter, target)
    }

    /** Способность Джанго: выстрел отбрасывает цель на 1 вагон в направлении выстрела. Уровень сохраняется, за пределы поезда — нельзя. */
    private fun pushTarget(shooter: PlayerState, target: PlayerState) {
        if (target.car == shooter.car) return
        val to = target.car + if (target.car > shooter.car) 1 else -1
        if (to < 0 || to > cars.lastIndex) return
        val from = target.car
        movePlayer(target, to)
        eventBuffer += BanditMoved(target.id, from, to, target.onRoof)
        if (!target.onRoof) sheriffEncounter()
    }

    private fun applyRob(p: PlayerState, tokenUid: String) {
        val car = cars[p.car]
        val list = if (p.onRoof) car.lootRoof else car.lootInside
        val token = list.firstOrNull { it.uid == tokenUid } ?: return
        list.remove(token)
        p.loot.add(token)
        eventBuffer += LootTaken(p.id, p.car, p.onRoof, token.type.name, revealValue(token))
    }

    private fun applyMarshal(value: String) {
        val from = sheriffIndex
        sheriffIndex = (from + parseDelta(value)).coerceIn(0, cars.lastIndex)
        eventBuffer += SheriffMoved(from, sheriffIndex)
        sheriffEncounter()
    }

    private fun applyPunchDrop(victim: PlayerState, token: LootToken) {
        victim.loot.remove(token)
        val car = cars[victim.car]
        val list = if (victim.onRoof) car.lootRoof else car.lootInside
        list.add(token)
        eventBuffer += LootDropped(victim.id, victim.car, victim.onRoof, token.type.name, revealValue(token))
    }

    /** Шайенн может сразу забрать кошелёк, упавший с жертвы при ударе. */
    private fun applyCheyenneTake(attacker: PlayerState, victim: PlayerState, token: LootToken) {
        val car = cars[victim.car]
        val list = if (victim.onRoof) car.lootRoof else car.lootInside
        list.remove(token)
        attacker.loot.add(token)
        eventBuffer += LootTaken(attacker.id, victim.car, victim.onRoof, token.type.name, revealValue(token))
    }

    private fun applyPunchMove(victimId: String, value: String) {
        val victim = byId.getValue(victimId)
        val from = victim.car
        val to = (from + parseDelta(value)).coerceIn(0, cars.lastIndex)
        movePlayer(victim, to)
        eventBuffer += BanditMoved(victim.id, from, to, victim.onRoof)
        if (!victim.onRoof) sheriffEncounter()
    }

    /** Встреча с шерифом: бандиты внутри вагона шерифа спасаются на крышу и получают пулю. */
    private fun sheriffEncounter() {
        val car = cars[sheriffIndex]
        if (car.inside.isEmpty()) return
        for (id in car.inside.toList()) {
            val p = byId.getValue(id)
            p.onRoof = true
            car.inside.remove(id)
            car.roof.add(id)
            eventBuffer += BanditRoofed(id, sheriffIndex, climbedUp = true)
            if (neutralBullets.isNotEmpty()) {
                neutralBullets.removeLast()
                p.deck.addFirst(BulletCard(uuid(), null, neutral = true))
                p.receivedBullets++
                eventBuffer += BulletReceived(id, fromId = null, neutral = true)
            }
        }
    }

    // ===== Хелперы =====

    private fun movePlayer(p: PlayerState, to: Int) {
        val fromCar = cars[p.car]
        if (p.onRoof) fromCar.roof.remove(p.id) else fromCar.inside.remove(p.id)
        p.car = to
        val toCar = cars[to]
        if (p.onRoof) toCar.roof.add(p.id) else toCar.inside.add(p.id)
    }

    private fun parseDelta(value: String): Int {
        val dir = value[0]
        val dist = value.substring(1).toIntOrNull() ?: 1
        return if (dir == 'B') -dist else dist
    }

    private fun moveOptions(p: PlayerState): List<String> {
        val opts = mutableListOf<String>()
        if (p.onRoof) {
            for (d in 1..3) {
                if (p.car - d >= 0) opts += "B$d"
                if (p.car + d <= cars.lastIndex) opts += "F$d"
            }
        } else {
            if (p.car > 0) opts += "B"
            if (p.car < cars.lastIndex) opts += "F"
        }
        return opts
    }

    private fun shootTargets(p: PlayerState): List<String> {
        val targets = mutableListOf<String>()
        if (p.onRoof) {
            for (c in cars.indices) {
                if (c == p.car || cars[c].roof.isEmpty()) continue
                val lo = minOf(c, p.car)
                val hi = maxOf(c, p.car)
                val blocked = (lo + 1 until hi).any { cars[it].roof.isNotEmpty() }
                if (!blocked) targets += cars[c].roof.toList()
            }
        } else {
            if (p.car > 0) targets += cars[p.car - 1].inside
            if (p.car < cars.lastIndex) targets += cars[p.car + 1].inside
            if (p.character == TUCO) targets += cars[p.car].roof
        }
        return eligibleTargets(targets)
    }

    private fun punchVictims(p: PlayerState): List<String> {
        val set = if (p.onRoof) cars[p.car].roof else cars[p.car].inside
        return eligibleTargets(set.filter { it != p.id })
    }

    /** Возвращает цели с учётом способности Красотки: она не цель, пока есть другая цель. */
    private fun eligibleTargets(targets: List<String>): List<String> {
        if (targets.size <= 1) return targets
        return targets.filter { byId.getValue(it).character != BELLE }
    }

    private fun punchDirections(victim: PlayerState): List<String> {
        val opts = mutableListOf<String>()
        if (victim.car > 0) opts += "B"
        if (victim.car < cars.lastIndex) opts += "F"
        return opts
    }

    private fun sheriffOptions(): List<String> {
        val opts = mutableListOf<String>()
        if (sheriffIndex > 0) opts += "B"
        if (sheriffIndex < cars.lastIndex) opts += "F"
        return opts
    }

    private fun lootAt(p: PlayerState): List<LootToken> {
        val car = cars[p.car]
        return if (p.onRoof) car.lootRoof else car.lootInside
    }

    /**
     * Жетон, который жертва теряет при ударе: если есть самоцвет или сейф — теряется
     * самое ценное, иначе случайный кошелёк.
     */
    private fun chooseDropToken(victim: PlayerState): LootToken? {
        if (victim.loot.isEmpty()) return null
        return if (victim.loot.any { it.type != LootType.WALLET })
            victim.loot.maxBy { it.value }
        else
            victim.loot[random.nextInt(victim.loot.size)]
    }

    private fun draw(p: PlayerState, n: Int) {
        repeat(n) { p.deck.removeFirstOrNull()?.let { p.hand.add(it) } }
    }

    private fun revealValue(token: LootToken): Int? =
        if (token.type == LootType.WALLET) null else token.value

    private fun makeChoice(
        playerId: String,
        kind: ChoiceKind,
        cardType: ActionType?,
        options: List<String>,
        context: String,
    ): PendingChoice {
        val c = PendingChoice("c${choiceCounter++}", kind, playerId, cardType, options, context)
        return c
    }

    private fun buildQueueForTurn(turnIdx: Int) {
        turnQueue.clear()
        val modes = roundCard.modes
        val mode = if (modes.isEmpty()) roundCard.mode else modes[turnIdx % modes.size]
        val cw = clockwiseOrder(firstPlayer)
        val order = if (mode == RoundMode.TURN_BACK) {
            listOf(firstPlayer) + cw.filter { it != firstPlayer }.reversed()
        } else {
            cw
        }
        val fpChar = player(firstPlayer)?.character ?: "?"
        val orderChars = order.map { id -> player(id)?.character ?: "?" }
        println("[buildQueue] round=$currentRound turn=$turnIdx mode=$mode firstPlayer=$fpChar order=$orderChars")
        order.forEach { turnQueue.addLast(it) }
    }

    private fun clockwiseOrder(start: String): List<String> {
        val idx = seatOrder.indexOf(start)
        return List(seatOrder.size) { seatOrder[(idx + it) % seatOrder.size] }
    }

    private fun nextSeat(id: String): String {
        val idx = seatOrder.indexOf(id)
        return seatOrder[(idx + 1) % seatOrder.size]
    }

    // ===== Итоги =====

    fun computeResults(): List<PlayerResult> {
        val minBullets = players.minOf { it.ownBullets }
        return players.map { p ->
            val loot = p.loot.sumOf { it.value }
            val prize = p.ownBullets == minBullets
            PlayerResult(p.id, p.nickname, loot, p.ownBullets, prize, loot + if (prize) Setup.ACCURACY_PRIZE else 0)
        }
    }

    private fun computeWinner(results: List<PlayerResult>): String {
        val maxTotal = results.maxOf { it.total }
        val tied = results.filter { it.total == maxTotal }
        val minReceived = tied.minOf { byId.getValue(it.playerId).receivedBullets }
        return tied.first { byId.getValue(it.playerId).receivedBullets == minReceived }.playerId
    }

    private fun uuid() = java.util.UUID.randomUUID().toString()
}
