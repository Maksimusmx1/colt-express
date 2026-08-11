package com.coltexpress.server.game

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineRulesTest {

    private fun uuid() = java.util.UUID.randomUUID().toString()

    private fun cars(count: Int): List<CarState> = (0 until count).map { CarState(it) }

    private fun player(
        id: String,
        seat: Int,
        car: Int,
        on: List<CarState>,
        roof: Boolean = false,
        deckTypes: List<ActionType> = emptyList(),
        loot: List<LootToken> = emptyList(),
    ): PlayerState {
        val p = PlayerState(id, "nick-$id", id, seat)
        p.car = car
        p.onRoof = roof
        deckTypes.forEach { p.deck.addLast(ActionCard(uuid(), it)) }
        loot.forEach { p.loot.add(it) }
        if (roof) on[car].roof.add(id) else on[car].inside.add(id)
        return p
    }

    private fun engine(
        players: List<PlayerState>,
        train: List<CarState>,
        mode: RoundMode,
        turns: Int = 1,
        first: String = players.first().id,
    ): Engine {
        val neutral = ArrayDeque<BulletCard>()
        repeat(13) { neutral.addLast(BulletCard(uuid(), null, neutral = true)) }
        return Engine(players, train, listOf(RoundCard(turns, mode)), first, neutral, Random(42))
    }

    private fun choices(updates: List<EngineUpdate>): List<com.coltexpress.server.protocol.ChoiceRequired> =
        updates.filterIsInstance<EngineUpdate.Robbery>().mapNotNull { it.choice }

    @Test
    fun `shoot from inside targets only inside adjacent cars`() {
        val train = cars(5)
        val a = player("A", 0, car = 2, on = train, deckTypes = listOf(ActionType.SHOOT))
        val b = player("B", 1, car = 1, on = train, roof = true, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(a, b, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("A", "SHOOT").played)
        assertTrue(e.drawCards("B").drew)
        assertTrue(e.drawCards("C").drew)
        val choice = choices(e.drawCards("D").updates).last()

        assertEquals("SHOOT_TARGET", choice.kind)
        assertTrue(choice.options.contains("C"))
        assertTrue(choice.options.contains("D"))
        assertFalse(choice.options.contains("B"), "крыша соседнего вагона не должна быть целью изнутри")
    }

    @Test
    fun `shoot from roof targets only roofs`() {
        val train = cars(5)
        val a = player("A", 0, car = 2, on = train, roof = true, deckTypes = listOf(ActionType.SHOOT))
        val b = player("B", 1, car = 1, on = train, roof = true, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, roof = true, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(a, b, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("A", "SHOOT").played)
        assertTrue(e.drawCards("B").drew)
        assertTrue(e.drawCards("C").drew)
        val choice = choices(e.drawCards("D").updates).last()

        assertTrue(choice.options.contains("B"))
        assertTrue(choice.options.contains("D"))
        assertFalse(choice.options.contains("C"), "внутренность соседнего вагона не должна быть целью с крыши")
    }

    @Test
    fun `punch moves victim to adjacent car on the same floor without roof option`() {
        val train = cars(5)
        val lootToken = LootToken(uuid(), LootType.WALLET, 250)
        val a = player("A", 0, car = 2, on = train, deckTypes = listOf(ActionType.PUNCH))
        val v = player("V", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE), loot = listOf(lootToken))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(a, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("A", "PUNCH").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val victimChoice = choices(e.drawCards("D").updates).last()
        assertEquals("PUNCH_VICTIM", victimChoice.kind)
        assertEquals(listOf("V"), victimChoice.options)

        val lootChoice = choices(e.submitChoice("A", victimChoice.choiceId, "V")).last()
        assertEquals("PUNCH_LOOT", lootChoice.kind)
        assertEquals(listOf(lootToken.uid), lootChoice.options)

        val dirChoice = choices(e.submitChoice("A", lootChoice.choiceId, lootToken.uid)).last()
        assertEquals("PUNCH_DIRECTION", dirChoice.kind)
        assertEquals(listOf("B", "F"), dirChoice.options, "удар не может отправить жертву на крышу")

        e.submitChoice("A", dirChoice.choiceId, "B")

        assertEquals(1, v.car)
        assertFalse(v.onRoof, "удар сохраняет уровень жертвы")
        assertTrue(v.loot.isEmpty())
        assertTrue(train[2].lootInside.any { it.uid == lootToken.uid })
    }

    @Test
    fun `punch keeps roof victim on the roof`() {
        val train = cars(5)
        val a = player("A", 0, car = 2, on = train, roof = true, deckTypes = listOf(ActionType.PUNCH))
        val v = player("V", 1, car = 2, on = train, roof = true, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(a, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("A", "PUNCH").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val victimChoice = choices(e.drawCards("D").updates).last()
        val dirChoice = choices(e.submitChoice("A", victimChoice.choiceId, "V")).last()
        assertEquals("PUNCH_DIRECTION", dirChoice.kind)
        assertEquals(listOf("B", "F"), dirChoice.options)

        e.submitChoice("A", dirChoice.choiceId, "F")

        assertEquals(3, v.car)
        assertTrue(v.onRoof)
    }

    @Test
    fun `on the run offers three options and honours them`() {
        val train = cars(5)
        val deck = List(10) { ActionType.MOVE }
        val a = player("A", 0, car = 0, on = train, deckTypes = deck)
        val b = player("B", 1, car = 1, on = train, deckTypes = deck)
        val c = player("C", 2, car = 2, on = train, deckTypes = deck)
        val e = engine(listOf(a, b, c), train, RoundMode.ON_THE_RUN)
        val start = e.startRound()

        val opt1 = start.filterIsInstance<EngineUpdate.PlanningChoice>().first().choice
        assertEquals("A", opt1.playerId)
        assertEquals(listOf("PLAY2", "DRAW6", "DRAW3_PLAY1"), opt1.options)
        assertFalse(e.drawCards("A").drew, "прямой добор запрещён в разгоне")

        val u2 = e.submitChoice("A", opt1.choiceId, "DRAW6")
        assertEquals(10, a.hand.size, "DRAW6 берёт до 6 карт (в колоде оставалось 4)")
        val opt2 = u2.filterIsInstance<EngineUpdate.PlanningChoice>().first().choice
        assertEquals("B", opt2.playerId)

        val u3 = e.submitChoice("B", opt2.choiceId, "DRAW3_PLAY1")
        assertEquals(9, b.hand.size, "DRAW3_PLAY1 берёт 3 карты")
        assertEquals("B", u3.filterIsInstance<EngineUpdate.PlanningTurn>().first().playerId)
        val u4 = e.playCard("B", "MOVE")
        assertEquals(8, b.hand.size)
        val opt3 = u4.updates.filterIsInstance<EngineUpdate.PlanningChoice>().first().choice
        assertEquals("C", opt3.playerId)

        val u5 = e.submitChoice("C", opt3.choiceId, "PLAY2")
        assertEquals("C", u5.filterIsInstance<EngineUpdate.PlanningTurn>().first().playerId)
        assertTrue(e.playCard("C", "MOVE").played)
        assertEquals(5, c.hand.size)
        assertTrue(e.playCard("C", "MOVE").played)
        assertTrue(e.phase == Phase.ROBBERY)
        assertTrue(c.hand.isEmpty(), "после начала ограбления рука возвращается в колоду")
    }

    @Test
    fun `turn back plays counter-clockwise starting with the first player`() {
        val train = cars(5)
        val deck = listOf(ActionType.MOVE)
        val a = player("A", 0, car = 0, on = train, deckTypes = deck)
        val b = player("B", 1, car = 1, on = train, deckTypes = deck)
        val c = player("C", 2, car = 2, on = train, deckTypes = deck)
        val d = player("D", 3, car = 3, on = train, deckTypes = deck)
        val e = engine(listOf(a, b, c, d), train, RoundMode.TURN_BACK)

        val start = e.startRound()
        assertEquals("A", start.filterIsInstance<EngineUpdate.PlanningTurn>().first().playerId)
        assertEquals("D", e.drawCards("A").updates.filterIsInstance<EngineUpdate.PlanningTurn>().first().playerId)
        assertEquals("C", e.drawCards("D").updates.filterIsInstance<EngineUpdate.PlanningTurn>().first().playerId)
        assertEquals("B", e.drawCards("C").updates.filterIsInstance<EngineUpdate.PlanningTurn>().first().playerId)
    }

    @Test
    fun `standard rules ignore character abilities`() {
        val train = cars(5)
        val doc = player("Doc", 0, car = 1, on = train, deckTypes = List(10) { ActionType.SHOOT })
        val belle = player("Belle", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val other = player("Other", 2, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(doc, belle, other, d), train, RoundMode.STANDARD)
        e.startRound()

        assertEquals(6, doc.hand.size, "Док по стандартным правилам берёт 6 карт")
        assertTrue(e.playCard("Doc", "SHOOT").played)
        assertTrue(e.drawCards("Belle").drew)
        assertTrue(e.drawCards("Other").drew)
        val choice = choices(e.drawCards("D").updates).last()
        assertTrue(choice.options.contains("Belle"), "Красотка без иммунитета — валидная цель")
    }

    @Test
    fun `sheriff encounter sends bandit to roof and gives neutral bullet`() {
        val train = cars(4)
        val a = player("A", 0, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val b = player("B", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(a, b, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("A", "MOVE").played)
        assertTrue(e.drawCards("B").drew)
        assertTrue(e.drawCards("C").drew)
        val moveChoice = choices(e.drawCards("D").updates).last()
        assertEquals(listOf("B", "F"), moveChoice.options)
        e.submitChoice("A", moveChoice.choiceId, "B")

        assertEquals(0, a.car)
        assertTrue(a.onRoof, "бандит сбегает на крышу от шерифа")
        assertEquals(1, a.receivedBullets)
    }
}
