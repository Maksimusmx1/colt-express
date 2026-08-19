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
    fun `punch drops random wallet at victim place and may send victim to adjacent car`() {
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

        val dirChoice = choices(e.submitChoice("A", victimChoice.choiceId, "V")).last()
        assertEquals("PUNCH_DIRECTION", dirChoice.kind, "кошелёк теряется автоматически, без выбора жетона")
        assertEquals(listOf("B", "F"), dirChoice.options, "удар перемещает жертву в соседний вагон, уровень сохраняется")

        e.submitChoice("A", dirChoice.choiceId, "B")

        assertEquals(1, v.car)
        assertFalse(v.onRoof, "удар сохраняет уровень жертвы при движении в соседний вагон")
        assertTrue(v.loot.isEmpty())
        assertTrue(train[2].lootInside.any { it.uid == lootToken.uid }, "выпавший кошелёк остаётся на месте жертвы")
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
        assertEquals(listOf("B", "F"), dirChoice.options, "удар не может сбить жертву с крыши")

        e.submitChoice("A", dirChoice.choiceId, "F")

        assertEquals(3, v.car)
        assertTrue(v.onRoof, "жертва с крыши остаётся на крыше соседнего вагона")
    }

    @Test
    fun `punch with a single possible knock direction applies automatically`() {
        val train = cars(5)
        val a = player("A", 0, car = 0, on = train, deckTypes = listOf(ActionType.PUNCH))
        val v = player("V", 1, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(a, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("A", "PUNCH").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val victimChoice = choices(e.drawCards("D").updates).last()
        assertEquals("PUNCH_VICTIM", victimChoice.kind)

        val updates = e.submitChoice("A", victimChoice.choiceId, "V")

        assertTrue(choices(updates).none { it.kind == "PUNCH_DIRECTION" },
            "единственный вариант направления выполняется без подтверждения")
        assertEquals(1, v.car, "жертва из локомотива отбрасывается в единственный соседний вагон")
    }

    @Test
    fun `punch drops the most valuable token when victim has gem or case`() {
        val train = cars(5)
        val wallet = LootToken(uuid(), LootType.WALLET, 250)
        val gem = LootToken(uuid(), LootType.GEM, 500)
        val a = player("A", 0, car = 2, on = train, deckTypes = listOf(ActionType.PUNCH))
        val v = player("V", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE), loot = listOf(wallet, gem))
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

        assertTrue(v.loot.contains(wallet), "при наличии самоцвета теряется самое ценное")
        assertFalse(v.loot.contains(gem))
        assertTrue(train[2].lootInside.any { it.uid == gem.uid }, "самоцвет остаётся на месте жертвы")
    }

    @Test
    fun `punch without loot drops nothing`() {
        val train = cars(5)
        val a = player("A", 0, car = 2, on = train, deckTypes = listOf(ActionType.PUNCH))
        val v = player("V", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(a, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("A", "PUNCH").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val victimChoice = choices(e.drawCards("D").updates).last()
        val dirChoice = choices(e.submitChoice("A", victimChoice.choiceId, "V")).last()
        assertEquals("PUNCH_DIRECTION", dirChoice.kind, "без добычи удар не роняет жетоны")
        assertTrue(train[2].lootInside.isEmpty())
    }

    @Test
    fun `cheyenne may take the wallet dropped by a punch`() {
        val train = cars(5)
        val lootToken = LootToken(uuid(), LootType.WALLET, 250)
        val cheyenne = player("Cheyenne", 0, car = 2, on = train, deckTypes = listOf(ActionType.PUNCH))
        val v = player("V", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE), loot = listOf(lootToken))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(cheyenne, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("Cheyenne", "PUNCH").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val victimChoice = choices(e.drawCards("D").updates).last()

        val takeChoice = choices(e.submitChoice("Cheyenne", victimChoice.choiceId, "V")).last()
        assertEquals("PUNCH_TAKE", takeChoice.kind)
        assertEquals(listOf("TAKE", "LEAVE"), takeChoice.options)

        val dirChoice = choices(e.submitChoice("Cheyenne", takeChoice.choiceId, "TAKE")).last()
        assertEquals("PUNCH_DIRECTION", dirChoice.kind)

        e.submitChoice("Cheyenne", dirChoice.choiceId, "F")

        assertTrue(cheyenne.loot.any { it.uid == lootToken.uid }, "Шайенн забирает упавший кошелёк")
        assertFalse(train[2].lootInside.any { it.uid == lootToken.uid })
    }

    @Test
    fun `only cheyenne may take the dropped wallet`() {
        val train = cars(5)
        val lootToken = LootToken(uuid(), LootType.WALLET, 250)
        val ghost = player("Ghost", 0, car = 2, on = train, deckTypes = listOf(ActionType.PUNCH))
        val v = player("V", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE), loot = listOf(lootToken))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(ghost, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("Ghost", "PUNCH").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val victimChoice = choices(e.drawCards("D").updates).last()

        val dirChoice = choices(e.submitChoice("Ghost", victimChoice.choiceId, "V")).last()
        assertEquals("PUNCH_DIRECTION", dirChoice.kind, "Призрак не может забрать упавший кошелёк")

        e.submitChoice("Ghost", dirChoice.choiceId, "F")

        assertFalse(ghost.loot.any { it.uid == lootToken.uid }, "кошелёк остаётся лежать на месте жертвы")
        assertTrue(train[2].lootInside.any { it.uid == lootToken.uid })
    }

    @Test
    fun `cheyenne cannot take a dropped gem`() {
        val train = cars(5)
        val gem = LootToken(uuid(), LootType.GEM, 500)
        val cheyenne = player("Cheyenne", 0, car = 2, on = train, deckTypes = listOf(ActionType.PUNCH))
        val v = player("V", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE), loot = listOf(gem))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(cheyenne, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("Cheyenne", "PUNCH").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val victimChoice = choices(e.drawCards("D").updates).last()

        val dirChoice = choices(e.submitChoice("Cheyenne", victimChoice.choiceId, "V")).last()
        assertEquals("PUNCH_DIRECTION", dirChoice.kind, "самоцвет не забирается, а кладётся на пол")

        e.submitChoice("Cheyenne", dirChoice.choiceId, "F")

        assertTrue(train[2].lootInside.any { it.uid == gem.uid }, "самоцвет остаётся на полу вагона")
        assertFalse(cheyenne.loot.any { it.uid == gem.uid })
    }

    @Test
    fun `ghost may play his first card of the round face down`() {
        val train = cars(5)
        val ghost = player("Ghost", 0, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val b = player("B", 1, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(ghost, b, c, d), train, RoundMode.STANDARD)
        val updates = e.startRound()

        val turn = updates.filterIsInstance<EngineUpdate.PlanningTurn>().last()
        assertTrue(turn.faceDownAvailable, "Призраку доступна способность на первом ходу")

        val res = e.playCard("Ghost", "MOVE", faceDown = true)
        assertTrue(res.played)
        assertTrue(res.faceDown, "Призрак играет первую карту раунда взакрытую")
    }

    @Test
    fun `only ghost may play face down`() {
        val train = cars(5)
        val cheyenne = player("Cheyenne", 0, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val b = player("B", 1, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(cheyenne, b, c, d), train, RoundMode.STANDARD)
        e.startRound()

        val res = e.playCard("Cheyenne", "MOVE", faceDown = true)
        assertTrue(res.played)
        assertFalse(res.faceDown, "только Призрак может сыграть взакрытую")
    }

    @Test
    fun `ghost loses the face down ability after drawing on his first turn`() {
        val train = cars(5)
        val ghost = player("Ghost", 0, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val b = player("B", 1, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(ghost, b, c, d), train, RoundMode.STANDARD, turns = 2)
        e.startRound()

        assertTrue(e.drawCards("Ghost").drew, "первое действие Призрака — добор 3 карт")
        assertTrue(e.drawCards("B").drew)
        assertTrue(e.drawCards("C").drew)
        val turn = e.drawCards("D").updates.filterIsInstance<EngineUpdate.PlanningTurn>().last()
        assertEquals("Ghost", turn.playerId)
        assertFalse(turn.faceDownAvailable, "после добора в первый ход способность не работает в этом раунде")

        val res = e.playCard("Ghost", "MOVE", faceDown = true)
        assertTrue(res.played)
        assertFalse(res.faceDown, "взакрытую нельзя, способность потрачена")
    }

    @Test
    fun `on the run offers three options and honours them`() {
        val train = cars(5)
        val deck = List(10) { ActionType.MOVE }
        val a = player("A", 0, car = 0, on = train, deckTypes = deck)
        val b = player("B", 1, car = 1, on = train, deckTypes = deck)
        val c = player("C", 2, car = 2, on = train, deckTypes = deck)
        val e = engine(listOf(a, b, c), train, RoundMode.DOUBLE)
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
    fun `doc draws 7 cards at the start of each round`() {
        val train = cars(5)
        val doc = player("Doc", 0, car = 1, on = train, deckTypes = List(10) { ActionType.SHOOT })
        val b = player("B", 1, car = 2, on = train, deckTypes = List(10) { ActionType.MOVE })
        val c = player("C", 2, car = 3, on = train, deckTypes = List(10) { ActionType.MOVE })
        val d = player("D", 3, car = 4, on = train, deckTypes = List(10) { ActionType.MOVE })
        val e = engine(listOf(doc, b, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertEquals(7, doc.hand.size, "Док берёт 7 карт")
        assertEquals(6, b.hand.size, "остальные берут по 6 карт")
    }

    @Test
    fun `belle cannot be a shoot target while another target is available`() {
        val train = cars(5)
        val shooter = player("S", 0, car = 2, on = train, deckTypes = listOf(ActionType.SHOOT))
        val belle = player("Belle", 1, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val other = player("Other", 2, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(shooter, belle, other, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("S", "SHOOT").played)
        assertTrue(e.drawCards("Belle").drew)
        assertTrue(e.drawCards("Other").drew)
        val choice = choices(e.drawCards("D").updates).last()

        assertFalse(choice.options.contains("Belle"), "Красотка защищена, пока есть другая цель")
        assertTrue(choice.options.contains("Other"))
    }

    @Test
    fun `belle can be a shoot target when she is the only option`() {
        val train = cars(5)
        val shooter = player("S", 0, car = 2, on = train, deckTypes = listOf(ActionType.SHOOT))
        val belle = player("Belle", 1, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(shooter, belle, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("S", "SHOOT").played)
        assertTrue(e.drawCards("Belle").drew)
        assertTrue(e.drawCards("C").drew)
        val choice = choices(e.drawCards("D").updates).last()

        assertEquals(listOf("Belle"), choice.options, "Красотка — единственная цель, значит доступна")
    }

    @Test
    fun `belle cannot be punched while another victim is available`() {
        val train = cars(5)
        val attacker = player("A", 0, car = 2, on = train, deckTypes = listOf(ActionType.PUNCH))
        val belle = player("Belle", 1, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val other = player("Other", 2, car = 2, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(attacker, belle, other, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("A", "PUNCH").played)
        assertTrue(e.drawCards("Belle").drew)
        assertTrue(e.drawCards("Other").drew)
        val victimChoice = choices(e.drawCards("D").updates).last()

        assertEquals(listOf("Other"), victimChoice.options, "Красотку нельзя ударить, пока есть другая жертва")
    }

    @Test
    fun `tuco inside a wagon can shoot bandits on its roof`() {
        val train = cars(5)
        val tuco = player("Tuco", 0, car = 2, on = train, deckTypes = listOf(ActionType.SHOOT))
        val roof = player("Roof", 1, car = 2, on = train, roof = true, deckTypes = listOf(ActionType.MOVE))
        val b = player("B", 2, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(tuco, roof, b, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("Tuco", "SHOOT").played)
        assertTrue(e.drawCards("Roof").drew)
        assertTrue(e.drawCards("B").drew)
        val choice = choices(e.drawCards("D").updates).last()

        assertTrue(choice.options.contains("Roof"), "Туко стреляет на крышу своего вагона")
    }

    @Test
    fun `only tuco shoots the roof of his own wagon`() {
        val train = cars(5)
        val normal = player("N", 0, car = 2, on = train, deckTypes = listOf(ActionType.SHOOT))
        val roof = player("Roof", 1, car = 2, on = train, roof = true, deckTypes = listOf(ActionType.MOVE))
        val b = player("B", 2, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(normal, roof, b, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("N", "SHOOT").played)
        assertTrue(e.drawCards("Roof").drew)
        assertTrue(e.drawCards("B").drew)
        val choice = choices(e.drawCards("D").updates).last()

        assertFalse(choice.options.contains("Roof"), "обычный бандит не стреляет на крышу своего вагона")
    }

    @Test
    fun `django shot pushes the target one wagon away`() {
        val train = cars(5)
        val django = player("Django", 0, car = 2, on = train, deckTypes = listOf(ActionType.SHOOT))
        val v = player("V", 1, car = 3, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(django, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("Django", "SHOOT").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val choice = choices(e.drawCards("D").updates).last()
        e.submitChoice("Django", choice.choiceId, "V")

        assertEquals(4, v.car, "жертва отбрасывается на вагон дальше от стрелка")
        assertFalse(v.onRoof)
        assertEquals(1, v.receivedBullets)
    }

    @Test
    fun `django cannot push the target beyond the last wagon`() {
        val train = cars(5)
        val django = player("Django", 0, car = 3, on = train, deckTypes = listOf(ActionType.SHOOT))
        val v = player("V", 1, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 1, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(django, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("Django", "SHOOT").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val choice = choices(e.drawCards("D").updates).last()
        e.submitChoice("Django", choice.choiceId, "V")

        assertEquals(4, v.car, "жертва на краю поезда не вылетает за его пределы")
    }

    @Test
    fun `django push keeps the target on the roof`() {
        val train = cars(5)
        val django = player("Django", 0, car = 2, on = train, roof = true, deckTypes = listOf(ActionType.SHOOT))
        val v = player("V", 1, car = 3, on = train, roof = true, deckTypes = listOf(ActionType.MOVE))
        val c = player("C", 2, car = 0, on = train, deckTypes = listOf(ActionType.MOVE))
        val d = player("D", 3, car = 4, on = train, deckTypes = listOf(ActionType.MOVE))
        val e = engine(listOf(django, v, c, d), train, RoundMode.STANDARD)
        e.startRound()

        assertTrue(e.playCard("Django", "SHOOT").played)
        assertTrue(e.drawCards("V").drew)
        assertTrue(e.drawCards("C").drew)
        val choice = choices(e.drawCards("D").updates).last()
        e.submitChoice("Django", choice.choiceId, "V")

        assertEquals(4, v.car)
        assertTrue(v.onRoof, "отбрасывание сохраняет уровень жертвы")
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
