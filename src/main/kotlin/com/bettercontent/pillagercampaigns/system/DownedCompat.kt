package com.bettercontent.pillagercampaigns.system

import com.bettercontent.downedplayerrevival.api.RevivalApi
import net.minecraft.world.entity.player.Player
import net.minecraftforge.fml.ModList

internal object DownedCompat {
    fun isDowned(player: Player): Boolean =
        ModList.get().isLoaded("downed_player_revival") && RevivalApi.isDowned(player)
}
