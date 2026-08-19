package com.coltexpress.server.game

import kotlin.random.Random

data class MemberSpec(val id: String, val nickname: String, val character: String = "")

/**
 * Подготовка к игре по правилам Colt Express (базовая игра, 3-6 игроков).
 *
 * NOTE: точный состав добычи на полу вагонов (таблички вагонов) и колода карт раундов
 * в PDF-тексте не приведены (они в компонентах). Здесь они заданы таблицей по умолчанию;
 * движок не зависит от конкретного распределения.
 */
object Setup {

    const val MIN_PLAYERS = 3
    const val MAX_PLAYERS = 6
    const val ROUNDS = 5
    const val ACCURACY_PRIZE = 1000
    const val CASE_VALUE = 1000
    const val GEM_VALUE = 500

    // Таблички вагонов: сколько кошельков и самоцветов лежит на полу.
    // Состав компонентов: 2 кошелька, 4 кошелька, 2 кошелька + 2 самоцвета,
    // 1 кошелёк + 3 самоцвета, 3 кошелька + 1 самоцвет, 1 кошелёк.
    // Каждую партию таблички раскладываются по вагонам в случайном порядке.
    private val CAR_TILES: List<Map<LootType, Int>> = listOf(
        mapOf(LootType.WALLET to 2),
        mapOf(LootType.WALLET to 4),
        mapOf(LootType.WALLET to 2, LootType.GEM to 2),
        mapOf(LootType.WALLET to 1, LootType.GEM to 3),
        mapOf(LootType.WALLET to 3, LootType.GEM to 1),
        mapOf(LootType.WALLET to 1),
    )

    // 18 кошельков стоимостью 250-500$.
    private val WALLET_VALUES = listOf(
        250, 250, 250, 250, 250, 250,
        300, 300, 300,
        350, 350, 350,
        400, 400,
        450, 450,
        500, 500,
    )

    // Колода из 7 карт раундов для 5-6 игроков (вытягиваются 4 + 1 станция).
    private val ROUND_DECK_5_6 = listOf(
        RoundCard(4, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD, RoundMode.STANDARD)),
        RoundCard(4, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD, RoundMode.TUNNEL)),
        RoundCard(4, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD, RoundMode.TURN_BACK)),
        RoundCard(3, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD)),
        RoundCard(3, RoundMode.DOUBLE, listOf(RoundMode.STANDARD, RoundMode.DOUBLE, RoundMode.TURN_BACK)),
        RoundCard(3, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.STANDARD, RoundMode.TURN_BACK)),
        RoundCard(2, RoundMode.DOUBLE, listOf(RoundMode.STANDARD, RoundMode.DOUBLE)),
    )

    // Колода из 7 карт раундов для 2-4 игроков (вытягиваются 4 + 1 станция).
    private val ROUND_DECK_2_4 = listOf(
        RoundCard(4, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.STANDARD, RoundMode.STANDARD, RoundMode.STANDARD)),
        RoundCard(5, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD)),
        RoundCard(5, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD, RoundMode.STANDARD)),
        RoundCard(4, RoundMode.STANDARD, listOf(RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD, RoundMode.STANDARD)),
        RoundCard(4, RoundMode.TUNNEL, listOf(RoundMode.STANDARD, RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.TURN_BACK)),
        RoundCard(3, RoundMode.DOUBLE, listOf(RoundMode.STANDARD, RoundMode.DOUBLE, RoundMode.STANDARD)),
        RoundCard(4, RoundMode.DOUBLE, listOf(RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.DOUBLE, RoundMode.STANDARD)),
    )

    // 3 карты железнодорожных станций (вытягивается 1 для раунда 5).
    private val STATION_CARDS = listOf(
        RoundCard(4, RoundMode.STATION, listOf(RoundMode.STANDARD, RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD)),
        RoundCard(4, RoundMode.STATION, listOf(RoundMode.STANDARD, RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD)),
        RoundCard(4, RoundMode.STATION, listOf(RoundMode.STANDARD, RoundMode.STANDARD, RoundMode.TUNNEL, RoundMode.STANDARD)),
    )

    private val NEUTRAL_BULLET_COUNT = 13

    fun createEngine(members: List<MemberSpec>, random: Random = Random.Default): Engine {
        require(members.size in MIN_PLAYERS..MAX_PLAYERS) { "players must be 3..6" }

        val players = members.mapIndexed { i, m -> PlayerState(m.id, m.nickname, m.character, i) }
        val carCount = players.size
        val cars = (0..carCount).map { CarState(it) }

        // Локомотив: кейс.
        cars[0].lootInside.add(LootToken(uuid(), LootType.CASE, CASE_VALUE))

        // Резерв кошельков.
        val pool = WALLET_VALUES.toMutableList().shuffled(random).toMutableList()
        val reservedWallets = mutableListOf<Int>()
        repeat(players.size) {
            val idx = pool.indexOf(250)
            if (idx >= 0) {
                reservedWallets += 250
                pool.removeAt(idx)
            } else {
                reservedWallets += 250
            }
        }

        // Добыча в вагонах по табличкам: таблички раскладываются в случайном порядке.
        val usedTiles = CAR_TILES.shuffled(random).take(carCount)
        for (carIndex in 1..carCount) {
            val tile = usedTiles[carIndex - 1]
            repeat(tile[LootType.WALLET] ?: 0) {
                val value = if (pool.isNotEmpty()) pool.removeAt(random.nextInt(pool.size)) else 250
                cars[carIndex].lootInside.add(LootToken(uuid(), LootType.WALLET, value))
            }
            repeat(tile[LootType.GEM] ?: 0) {
                cars[carIndex].lootInside.add(LootToken(uuid(), LootType.GEM, GEM_VALUE))
            }
        }

        // Первый игрок случайным образом.
        val seatOrder = players.sortedBy { it.seat }
        val first = seatOrder[random.nextInt(seatOrder.size)]
        val firstIndex = seatOrder.indexOf(first)

        // Размещение бандитов: нечётные номера -> последний вагон, чётные -> предпоследний.
        seatOrder.forEachIndexed { i, p ->
            val number = java.lang.Math.floorMod(i - firstIndex, seatOrder.size) + 1
            val car = if (number % 2 == 1) carCount else carCount - 1
            p.car = car
            cars[car].inside.add(p.id)
        }

        // Колоды действий (10 карт: Движение x2, Лестница x2, Выстрел x2, Кража x2, Удар x1, Шериф x1).
        val actionDefs = listOf(
            ActionType.MOVE, ActionType.MOVE,
            ActionType.LADDER, ActionType.LADDER,
            ActionType.SHOOT, ActionType.SHOOT,
            ActionType.ROB, ActionType.ROB,
            ActionType.PUNCH,
            ActionType.MARSHAL,
        )
        players.forEach { p ->
            actionDefs.mapTo(p.deck) { ActionCard(uuid(), it) }
            p.deck.toMutableList().shuffled(random).let { p.deck.clear(); p.deck.addAll(it) }
        }
        // Стартовый кошелёк 250$ каждому игроку (отложенный жетон).
        players.forEachIndexed { i, p -> p.loot.add(LootToken(uuid(), LootType.WALLET, reservedWallets[i])) }

        // Нейтральные пули.
        val neutralBullets = ArrayDeque<BulletCard>()
        repeat(NEUTRAL_BULLET_COUNT) { neutralBullets.addLast(BulletCard(uuid(), null, neutral = true)) }

        val roundDeck = if (players.size <= 4) {
            ROUND_DECK_2_4.shuffled(random).take(4) + STATION_CARDS.shuffled(random).take(1)
        } else {
            ROUND_DECK_5_6.shuffled(random).take(4) + STATION_CARDS.shuffled(random).take(1)
        }
        return Engine(players, cars, roundDeck, first.id, neutralBullets, random)
    }

    private fun uuid() = java.util.UUID.randomUUID().toString()
}
