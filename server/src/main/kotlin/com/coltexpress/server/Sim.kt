package com.coltexpress.server

import com.coltexpress.server.game.ActionCard
import com.coltexpress.server.game.Engine
import com.coltexpress.server.game.EngineUpdate
import com.coltexpress.server.game.MemberSpec
import com.coltexpress.server.game.Setup
import kotlin.random.Random

/**
 * Автосимуляция полной партии для проверки правил движка.
 * Политика бота: в планировании разыгрывает первую карту действия из руки (иначе добирает 3);
 * в ограблении всегда выбирает первый вариант выбора.
 */
object Sim {

    private fun pickAction(engine: Engine, playerId: String): String? =
        engine.player(playerId)?.hand?.filterIsInstance<ActionCard>()?.firstOrNull()?.type?.name

    fun playGame(seed: Long, players: Int) {
        val random = Random(seed)
        val members = (1..players).map { MemberSpec("p$it", "Bandit$it") }
        val engine = Setup.createEngine(members, random)
        var updates = engine.startRound()
        var guard = 0
        var events = 0

        while (updates.isNotEmpty()) {
            if (++guard > 20000) {
                println("STUCK at guard $guard (round ${engine.currentRound}, phase ${engine.phase})")
                break
            }
            val next = mutableListOf<EngineUpdate>()
            for (u in updates) {
                when (u) {
                    is EngineUpdate.RoundStarted ->
                        println("R${u.round} [${u.mode}] turns=${u.turns} first=${u.firstPlayerId}")
                    is EngineUpdate.PlanningTurn -> {
                        val cardType = pickAction(engine, u.playerId)
                        val res: Pair<Boolean, List<EngineUpdate>> = if (cardType != null) {
                            val r = engine.playCard(u.playerId, cardType)
                            Pair(r.played, r.updates)
                        } else {
                            val r = engine.drawCards(u.playerId)
                            Pair(r.drew, r.updates)
                        }
                        if (res.first) println("  plan ${u.playerId} plays $cardType")
                        else println("  plan ${u.playerId} draws")
                        next += res.second
                    }
                    is EngineUpdate.Robbery -> {
                        for (ev in u.events) {
                            events++
                            println("  rob $ev")
                        }
                        if (u.choice != null) {
                            val v = u.choice.options.first()
                            println("  rob ${u.choice.playerId} chooses ${u.choice.kind} -> $v")
                            next += engine.submitChoice(u.choice.playerId, u.choice.choiceId, v)
                        }
                    }
                    is EngineUpdate.GameOver -> {
                        println("GAME OVER winner=${u.winnerId}")
                        for (r in u.results) {
                            println("  ${r.nickname}: loot=${r.lootSum} bulletsLeft=${r.bulletsLeft} prize=${r.accuracyPrize} total=${r.total}")
                        }
                    }
                }
            }
            updates = next
        }
        println("-- game seed=$seed done, events=$events")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val games = args.firstOrNull()?.toIntOrNull() ?: 3
        val players = args.getOrNull(1)?.toIntOrNull() ?: 4
        repeat(games) { i -> playGame((i + 1) * 1000L, players) }
    }
}
