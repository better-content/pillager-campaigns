package com.gerald.pillagerpressure

import com.mojang.logging.LogUtils
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import org.slf4j.Logger

@Mod(PillagerPressureMod.MOD_ID)
class PillagerPressureMod {
    init {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, PillagerPressureConfig.SPEC)
        MinecraftForge.EVENT_BUS.register(PillagerPressureEvents)
        LOGGER.info("Loaded mod {}", MOD_ID)
    }

    companion object {
        const val MOD_ID: String = "pillagerpressure"
        const val PATROL_TAG: String = "BoundToMatterPillagerPressure"
        val LOGGER: Logger = LogUtils.getLogger()
    }
}
