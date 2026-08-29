package com.bettercontent.pillagercampaigns.system

import net.minecraft.world.entity.player.Player
import java.lang.reflect.Method

internal object DownedCompat {
    private val method: Method? = runCatching {
        Class.forName("com.bettercontent.downedplayerrevival.api.RevivalApi").getMethod("isDowned", Player::class.java)
    }.getOrNull()

    fun isDowned(player: Player): Boolean = method?.let { candidate ->
        runCatching { candidate.invoke(null, player) as Boolean }.getOrDefault(false)
    } ?: false
}
