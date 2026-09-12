package com.bettercontent.pillagercampaigns.system

import com.bettercontent.downedplayerrevival.api.InjuryApi
import net.minecraft.world.entity.player.Player
import net.minecraftforge.fml.ModList

internal object InjuryCompat {
    fun semanticHealth(player: Player): Float =
        if (ModList.get().isLoaded("downed_player_revival")) InjuryApi.semanticHealth(player) else player.health
}
