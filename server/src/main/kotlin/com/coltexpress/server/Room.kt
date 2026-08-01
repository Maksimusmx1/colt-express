package com.coltexpress.server

import com.coltexpress.server.game.Engine
import com.coltexpress.server.protocol.Player
import kotlinx.coroutines.sync.Mutex

class Room(
    val id: String,
    val maxPlayers: Int,
    ownerId: String,
) {
    var ownerId: String = ownerId
    val players = mutableListOf<Player>()
    var engine: Engine? = null
    val lock = Mutex()
}
