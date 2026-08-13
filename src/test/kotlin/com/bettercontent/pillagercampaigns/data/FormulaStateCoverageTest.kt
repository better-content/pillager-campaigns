package com.bettercontent.pillagercampaigns.data

import net.minecraft.nbt.CompoundTag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormulaStateCoverageTest {
    @Test fun `nemesis event preserves every optional observation`() {
        val event = NemesisEvent(44, NemesisEventType.PROMOTED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "high")
        assertEquals(event, NemesisEvent.load(event.save()))
        val sparse = NemesisEvent.load(CompoundTag())
        assertEquals(0, sparse.tick)
        assertEquals(NemesisEventType.LOST_CAMPAIGN, sparse.type)
        assertNull(sparse.playerId)
    }

    @Test fun `rally presence handles full and legacy sparse records`() {
        val record = RallyPresenceRecord(RallyPresenceState.MATERIALIZED, UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3, 99)
        assertEquals(record, RallyPresenceRecord.load(record.save()))
        val sparse = CompoundTag().also { it.putString("state", "invalid") }
        val loaded = RallyPresenceRecord.load(sparse)
        assertEquals(RallyPresenceState.DORMANT, loaded.state)
        assertEquals(UUID(0, 0), loaded.warlordId)
    }

    @Test fun `all persisted enum values remain loadable`() {
        assertEquals(2, OfficerRole.entries.size)
        assertEquals(5, OfficerState.entries.size)
        assertEquals(3, OfficerRank.entries.size)
        assertEquals(7, CampaignState.entries.size)
        assertEquals(2, PresenceType.entries.size)
        assertEquals(3, RallyPresenceState.entries.size)
        assertEquals(5, PresenceMaterializationResult.entries.size)
        assertEquals(10, NemesisEventType.entries.size)
        assertEquals(5, CampaignOutcome.entries.size)
    }
}
