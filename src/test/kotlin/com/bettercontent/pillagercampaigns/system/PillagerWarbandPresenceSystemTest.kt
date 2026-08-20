package com.bettercontent.pillagercampaigns.system

import com.gerald.warband.core.CoreEffect
import com.gerald.warband.core.EffectKind
import com.gerald.warband.core.MemberManifest
import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PillagerWarbandPresenceSystemTest {
    @Test
    fun `deferred garrison placements resolve deterministically around the loaded rally`() {
        val members = listOf(
            MemberManifest("one", "minecraft:pillager", 5.0),
            MemberManifest("two", "minecraft:vindicator", 6.0),
        )
        val effect = CoreEffect(
            kind = EffectKind.MATERIALIZE_GARRISON,
            memberIds = members.map { it.id },
            memberManifests = members,
        )

        val realized = PillagerWarbandPresenceSystem.withDeferredGarrisonPlacements(
            effect, BlockPos(100, 70, -20), "minecraft:overworld",
        )

        assertEquals(listOf("one", "two"), realized.memberPlacements.map { it.memberId })
        assertEquals(listOf(99 to -19, 100 to -19), realized.memberPlacements.map { it.position.x to it.position.z })
        assertEquals(listOf(70, 70), realized.memberPlacements.map { it.position.y })
    }

    @Test
    fun `positioned garrison effect remains authoritative`() {
        val member = MemberManifest("one", "minecraft:pillager", 5.0)
        val positioned = CoreEffect(
            kind = EffectKind.MATERIALIZE_GARRISON,
            memberIds = listOf(member.id),
            memberManifests = listOf(member),
            memberPlacements = listOf(com.gerald.warband.core.MemberPlacement(
                member.id, com.gerald.warband.core.BlockPosition("minecraft:overworld", 1, 2, 3),
            )),
        )

        assertSame(
            positioned,
            PillagerWarbandPresenceSystem.withDeferredGarrisonPlacements(
                positioned, BlockPos(100, 70, -20), "minecraft:overworld",
            ),
        )
    }
}
