package com.gerald.pillagercampaigns

import com.mojang.logging.LogUtils
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import org.slf4j.Logger

@Mod(PillagerCampaignsMod.MOD_ID)
class PillagerCampaignsMod {
    init {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, PillagerCampaignsConfig.SPEC)
        MinecraftForge.EVENT_BUS.register(PillagerCampaignsEvents)
        LOGGER.info("Loaded mod {}", MOD_ID)
    }

    companion object {
        const val MOD_ID: String = "pillagercampaigns"
        const val PATROL_TAG: String = "BoundToMatterPillagerCampaigns"
        val LOGGER: Logger = LogUtils.getLogger()
    }
}
