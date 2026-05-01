package com.gerald.pillagercampaigns.util

import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.BaseType
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.ChunkRef
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficerOrdersRulesTest {
    @Test
    fun generateIncludesFactionBaseOfficerAndCampaignSignal() {
        val faction = faction()
        val base = base(faction.id)
        val officer = officer(faction.id, base.id)
        val campaign = campaign(faction.id, base.id, officer.id)

        val orders = OfficerOrdersRules.generate(faction, base, officer, campaign)

        assertEquals("Orders of ${officer.name}", orders.title)
        assertTrue(orders.loreLines.any { it.contains("Faction: ${faction.name}") })
        assertTrue(orders.loreLines.any { it.contains("Assigned Base: ${base.center.x}, ${base.center.z}") })
        assertTrue(orders.loreLines.any { it.contains("Engineering:") })
        assertTrue(orders.loreLines.any { it.contains("Loadout:") })
        assertTrue(orders.loreLines.any { it.contains("Squad:") })
        assertTrue(orders.loreLines.any { it.contains("Campaign: engagement underway") })
        assertTrue(orders.loreLines.any { it.contains("Route: 0,0 -> 3,5") })
    }

    @Test
    fun generateWithoutOfficerFallsBackToRecoveredOrders() {
        val faction = faction()
        val base = base(faction.id)

        val orders = OfficerOrdersRules.generate(faction, base, officer = null, campaign = null)

        assertEquals("Recovered Field Orders", orders.title)
        assertTrue(orders.loreLines.any { it == "Officer: unknown" })
        assertTrue(orders.loreLines.any { it == "Command: recover banner intelligence" })
    }

    @Test
    fun generateCapsLoreAtTenLines() {
        val faction = faction()
        val base = base(faction.id)
        val officer = officer(faction.id, base.id)
        val campaign = campaign(faction.id, base.id, officer.id)

        val orders = OfficerOrdersRules.generate(faction, base, officer, campaign)

        assertTrue(orders.loreLines.size <= 10)
    }

    private fun faction() = PillagerFaction(UUID.randomUUID(), "Blackroot Standard", "black", "red", 12, 3, 2)

    private fun base(factionId: UUID) = PillagerBase(
        UUID.randomUUID(),
        factionId,
        null,
        BaseType.MAJOR,
        ResourceLocation("minecraft", "overworld"),
        null,
        BlockPos(120, 80, -64),
        ChunkRef(0, 0),
        null,
        BaseState.ACTIVE,
        40,
        60,
        90,
        20,
        80,
        30,
        0L,
    )

    private fun officer(factionId: UUID, baseId: UUID) = PillagerOfficer(
        UUID.randomUUID(),
        "Ghor",
        "the Finder",
        factionId,
        baseId,
        OfficerRank.CAPTAIN,
        OfficerRole.HUNTER,
        OfficerState.ACTIVE,
        3,
        1,
        0,
        2,
    )

    private fun campaign(factionId: UUID, baseId: UUID, officerId: UUID) = PillagerCampaign(
        UUID.randomUUID(),
        factionId,
        baseId,
        officerId,
        CampaignState.ENGAGING,
        ChunkRef(0, 0),
        ChunkRef(3, 5),
        40,
        0,
        6,
        1,
        0L,
        0L,
    )
}
