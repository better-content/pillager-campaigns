package com.gerald.pillagercampaigns.gametest

import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.system.WarbandCoreAdapter
import com.gerald.warband.core.WarbandRuntimeSpec
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

/**
 * Behavioral GameTests intentionally remain out of this source-refactor phase.
 * This registration smoke test only guards the shared runtime-spec boundary;
 * full Minecraft behavior validation is restored in the next requested phase.
 */
@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object PillagerCampaignsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun runtimeSpecAttachesToPrivateEngine(helper: GameTestHelper) {
        val data = PillagerWorldData.get(helper.level.server)
        val spec = WarbandCoreAdapter.runtimeSpec(helper.level.server)
        helper.assertTrue(
            spec.schemaVersion == WarbandRuntimeSpec.CURRENT_SCHEMA_VERSION,
            "Forge and Core must agree on the runtime-spec schema",
        )
        helper.assertTrue(spec.revision == spec.computedRevision(), "runtime-spec revision must cover all decision inputs")
        data.attachRuntimeSpec(spec)
        helper.assertTrue(data.runtimeSpecRevision() == spec.revision, "world must retain the exact runtime-spec revision")
        helper.succeed()
    }
}
