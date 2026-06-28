package com.gerald.pillagercampaigns

import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class PillagerCampaignsEventsFormatTest {
    @Test
    fun `warband list line always includes rally coordinates structure and failure`() {
        val warband = PillagerWarband(
            id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            factionId = UUID.randomUUID(),
            dimension = ResourceLocation.tryParse("minecraft:overworld")!!,
            structureId = ResourceLocation.tryParse("minecraft:pillager_outpost")!!,
            bannerSeed = 0,
            rallyChunkX = 12,
            rallyChunkZ = -3,
            strength = 4,
            defeated = false,
            warlordOfficerId = UUID.randomUUID(),
            warlordEntityId = null,
            nextRaidTick = 0L,
            cooldownUntilTick = 0L,
            lastIntelTick = 0L,
            lastPresenceFailure = PresenceMaterializationResult.COOLDOWN,
        )

        val line = PillagerCampaignsEvents.formatWarbandLine(warband)
        assertTrue("rally_chunk=12,-3" in line)
        assertTrue("rally_xyz=200,64,-40" in line)
        assertTrue("structure=minecraft:pillager_outpost" in line)
        assertTrue("strength=4" in line)
        assertTrue("failure=cooldown" in line)
    }
}
