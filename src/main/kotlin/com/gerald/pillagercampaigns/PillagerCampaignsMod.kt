package com.gerald.pillagercampaigns

import com.gerald.pillagercampaigns.gametest.PillagerCampaignsGameTests
import com.mojang.logging.LogUtils
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterGameTestsEvent
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import org.slf4j.Logger

@Mod(PillagerCampaignsMod.MOD_ID)
class PillagerCampaignsMod {
    init {
        FMLJavaModLoadingContext.get().modEventBus.addListener(::registerGameTests)
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, PillagerCampaignsConfig.SPEC)
        MinecraftForge.EVENT_BUS.register(PillagerCampaignsEvents)
        LOGGER.info("Loaded mod {}", MOD_ID)
    }

    private fun registerGameTests(event: RegisterGameTestsEvent) {
        event.register(PillagerCampaignsGameTests::class.java)
    }

    companion object {
        const val MOD_ID: String = "pillagercampaigns"
        const val PATROL_TAG: String = "BoundToMatterPillagerCampaigns"
        val LOGGER: Logger = LogUtils.getLogger()
    }
}
