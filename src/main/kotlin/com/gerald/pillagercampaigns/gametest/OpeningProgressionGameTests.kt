package com.gerald.pillagercampaigns.gametest

import com.gerald.pillagercampaigns.PillagerCampaignsMod
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object OpeningProgressionGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "opening_progression", timeoutTicks = 80)
    fun primitiveOpeningRouteRemainsRuntimeReachable(helper: GameTestHelper) {
        OpeningProgressionRuntimeValidation.validate(helper.level, helper.absolutePos(BlockPos.ZERO))
        helper.succeed()
    }
}
