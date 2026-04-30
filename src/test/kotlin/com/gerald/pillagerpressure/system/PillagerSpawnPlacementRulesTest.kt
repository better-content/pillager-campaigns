package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.data.CampaignState
import com.gerald.pillagerpressure.data.ChunkRef
import com.gerald.pillagerpressure.data.PillagerCampaign
import net.minecraft.core.BlockPos
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PillagerSpawnPlacementRulesTest {
    @Test
    fun offsetsAreSortedFarthestFirstWithinConfiguredRing() {
        val offsets = PillagerSpawnPlacementRules.farthestFirstOffsets(16, 32, step = 8)

        assertTrue(offsets.isNotEmpty())
        assertTrue(offsets.zipWithNext().all { (a, b) -> a.distanceSqr >= b.distanceSqr })
        assertTrue(offsets.all { it.distanceSqr >= 16 * 16 })
        assertTrue(offsets.all { it.distanceSqr <= 32 * 32 })
        assertEquals(32 * 32, offsets.first().distanceSqr)
    }

    @Test
    fun chooseFarthestSkipsUnloadedAndInvalidCandidatesBeforeCloserOnes() {
        val center = BlockPos(0, 70, 0)
        val farthest = PillagerSpawnPlacementRules.farthestFirstOffsets(16, 32, step = 8).first()
        val expected = center.offset(farthest.dx, 0, farthest.dz)

        val chosen = PillagerSpawnPlacementRules.chooseFarthest(
            center = center,
            minRadius = 16,
            maxRadius = 32,
            isLoaded = { it == expected || distanceSqr(it, center) < 20 * 20 },
            isValid = { it == expected || distanceSqr(it, center) < 20 * 20 },
        )

        assertEquals(expected, chosen)
    }

    @Test
    fun chooseFarthestFallsBackToCloserLoadedValidCandidate() {
        val center = BlockPos(0, 70, 0)
        val nearer = BlockPos(16, 70, 0)

        val chosen = PillagerSpawnPlacementRules.chooseFarthest(
            center = center,
            minRadius = 16,
            maxRadius = 32,
            isLoaded = { it == nearer },
            isValid = { it == nearer },
        )

        assertEquals(nearer, chosen)
    }

    @Test
    fun objectiveForCampaignUsesCampaignStateAndTargetChunk() {
        val campaign = campaign(CampaignState.APPROACHING_INTEL).also {
            it.target = ChunkRef(9, -4)
        }

        val objective = PillagerObjectiveRules.objectiveFor(campaign, target = null, fallback = BlockPos(1, 72, 1))

        assertEquals("hunt_intel", objective.kind)
        assertEquals(ChunkRef(9, -4).centerBlock(72), objective.pos)
    }

    @Test
    fun objectiveWithoutCampaignFallsBackToPatrolObjective() {
        val fallback = BlockPos(7, 80, -3)

        val objective = PillagerObjectiveRules.objectiveFor(campaign = null, target = null, fallback = fallback)

        assertEquals("patrol", objective.kind)
        assertEquals(fallback, objective.pos)
    }

    @Test
    fun generatedOffsetsContainEveryCardinalMaxPoint() {
        val offsets = PillagerSpawnPlacementRules.farthestFirstOffsets(8, 64).toSet()

        assertNotNull(offsets.firstOrNull { it.dx == 64 && it.dz == 0 })
        assertNotNull(offsets.firstOrNull { it.dx == -64 && it.dz == 0 })
        assertNotNull(offsets.firstOrNull { it.dx == 0 && it.dz == 64 })
        assertNotNull(offsets.firstOrNull { it.dx == 0 && it.dz == -64 })
    }

    private fun campaign(state: CampaignState) = PillagerCampaign(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        state,
        ChunkRef(0, 0),
        ChunkRef(1, 1),
        80,
        0,
        3,
        0,
        0L,
        0L,
    )

    private fun distanceSqr(a: BlockPos, b: BlockPos): Int {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }
}
