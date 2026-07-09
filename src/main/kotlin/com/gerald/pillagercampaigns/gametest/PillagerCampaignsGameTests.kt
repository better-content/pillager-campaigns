package com.gerald.pillagercampaigns.gametest

import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.OfficerClass
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.data.RallyPresenceState
import com.gerald.pillagercampaigns.system.PillagerCampaignEngine
import com.gerald.pillagercampaigns.system.PillagerWarbandDiscoveryRules
import com.gerald.pillagercampaigns.system.PillagerWarbandDiscoveryService
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object PillagerCampaignsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun warbandRegistrationCreatesFactionOfficerAndWarband(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerWarbandDiscoveryRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:warband-registration".toByteArray()),
            dimension = level.dimension().location(),
            structureId = location("minecraft:pillager_outpost"),
            cellX = 0,
            cellZ = 0,
            chunkX = anchor.x,
            chunkZ = anchor.z,
        )

        val registered = PillagerWarbandDiscoveryService.registerDiscoveredWarband(level, data, candidate, level.gameTime)
        val warband = data.warbands[candidate.id]

        helper.assertTrue(registered, "warband should register")
        helper.assertTrue(warband != null, "warband record should exist")
        helper.assertTrue(data.factions.containsKey(warband!!.factionId), "warband faction should exist")
        helper.assertTrue(data.officers.containsKey(warband.warlordOfficerId), "warband should have a warlord officer")
        helper.assertTrue(data.officers.getValue(warband.warlordOfficerId).role == OfficerRole.WARLORD, "rally leader should stay a warlord role")
        helper.assertTrue(warband.strength == PillagerCampaignEngine.INITIAL_WARBAND_STRENGTH, "new warbands should start at the gentlest pressure tier")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 80)
    fun samWarbandCommandsAreRegisteredAndNonBlocking(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerWarbandDiscoveryRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:sam-warband-command".toByteArray()),
            dimension = level.dimension().location(),
            structureId = location("minecraft:pillager_outpost"),
            cellX = 0,
            cellZ = 0,
            chunkX = anchor.x,
            chunkZ = anchor.z,
        )
        helper.assertTrue(PillagerWarbandDiscoveryService.registerDiscoveredWarband(level, data, candidate, level.gameTime), "warband should register")
        val source = level.server.createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput()
        val prefix = candidate.id.toString().take(8)

        val status = level.server.commands.performPrefixedCommand(source, "sam status")
        val movements = level.server.commands.performPrefixedCommand(source, "sam movements list")
        val warbands = level.server.commands.performPrefixedCommand(source, "sam warbands list")
        val materialized = level.server.commands.performPrefixedCommand(source, "sam warbands materialize_warlord $prefix")
        val missing = level.server.commands.performPrefixedCommand(source, "sam warbands materialize_warlord does-not-exist")

        helper.assertTrue(status == 1, "sam status should succeed")
        helper.assertTrue(movements == 1, "sam movements list should succeed")
        helper.assertTrue(warbands == 1, "sam warbands list should succeed")
        helper.assertTrue(materialized == 1, "sam warbands materialize_warlord should return a handled result")
        helper.assertTrue(missing == 0, "unknown warband materialize target should fail without throwing")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 80)
    fun warlordMaterializationCommandRecordsPresenceAttempt(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerWarbandDiscoveryRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:materialize-warlord-command".toByteArray()),
            dimension = level.dimension().location(),
            structureId = location("minecraft:pillager_outpost"),
            cellX = 0,
            cellZ = 0,
            chunkX = anchor.x,
            chunkZ = anchor.z,
        )
        helper.assertTrue(PillagerWarbandDiscoveryService.registerDiscoveredWarband(level, data, candidate, level.gameTime), "warband should register")
        level.getChunk(anchor.x, anchor.z)
        val source = level.server.createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput()
        val result = level.server.commands.performPrefixedCommand(source, "sam warbands materialize_warlord ${candidate.id.toString().take(8)}")
        val warband = data.warbands[candidate.id]

        helper.assertTrue(result == 1, "sam warbands materialize_warlord should return a handled result")
        helper.assertTrue(warband != null, "warband should exist after registration")
        helper.assertTrue(warband!!.lastPresenceAttemptTick == level.gameTime, "warlord materialization should record an attempt tick")
        helper.assertTrue(warband.rallyPresence?.state == RallyPresenceState.MATERIALIZED || warband.lastPresenceFailure != PresenceMaterializationResult.SUCCESS, "warlord materialization should preserve rally presence semantics")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun warbandCollapseResolvesCampaignAndKillsHomeOfficer(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val warbandId = UUID.nameUUIDFromBytes("gametest:collapse-warband".toByteArray())
        val factionId = UUID.nameUUIDFromBytes("gametest:collapse-faction".toByteArray())
        val officerId = UUID.nameUUIDFromBytes("gametest:collapse-officer".toByteArray())
        val campaignId = UUID.nameUUIDFromBytes("gametest:collapse-campaign".toByteArray())

        data.factions[factionId] = PillagerFaction(factionId, "Test Faction", 7, officerId)
        data.warbands[warbandId] = PillagerWarband(
            id = warbandId,
            factionId = factionId,
            dimension = helper.level.dimension().location(),
            structureId = location("minecraft:pillager_outpost"),
            bannerSeed = 7,
            rallyChunkX = 0,
            rallyChunkZ = 0,
            strength = 3,
            defeated = false,
            warlordOfficerId = officerId,
            warlordEntityId = UUID.randomUUID(),
            nextRaidTick = 0L,
            cooldownUntilTick = 0L,
            lastIntelTick = 0L,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
        )
        data.officers[officerId] = PillagerOfficer(
            id = officerId,
            factionId = factionId,
            homeWarbandId = warbandId,
            name = "Ghor",
            title = "the Warlord",
            role = OfficerRole.WARLORD,
            rank = OfficerRank.DREAD_CAPTAIN,
            officerClass = OfficerClass.VINDICATOR,
            state = OfficerState.DEPLOYED,
            preferenceGraph = mutableMapOf(),
        )
        data.campaigns[campaignId] = PillagerCampaign(
            id = campaignId,
            factionId = factionId,
            originWarbandId = warbandId,
            officerId = officerId,
            targetPlayerId = UUID.randomUUID(),
            targetDimension = helper.level.dimension().location(),
            currentChunkX = 0,
            currentChunkZ = 0,
            targetChunkX = 1,
            targetChunkZ = 1,
            difficultySnapshot = 3,
            loadoutSeed = 1L,
            tickDebt = 0,
            state = CampaignState.ACTIVE,
            resumeState = null,
            materializeAttemptId = null,
            materializingUntilTick = 0L,
            squadMemberIds = mutableListOf(),
        )

        PillagerCampaignEngine.collapseWarband(data, warbandId)

        helper.assertTrue(data.warbands.getValue(warbandId).defeated, "warband should be defeated")
        helper.assertTrue(data.campaigns.getValue(campaignId).state == CampaignState.RESOLVED, "campaign should resolve")
        helper.assertTrue(data.officers.getValue(officerId).state == OfficerState.DEAD, "home officer should be marked dead")
        helper.succeed()
    }

    private fun resetWorldData(helper: GameTestHelper): PillagerWorldData {
        val data = PillagerWorldData.get(helper.level.server)
        data.factions.clear()
        data.warbands.clear()
        data.officers.clear()
        data.campaigns.clear()
        data.lastCampaignTick = 0L
        data.lastDiscoveryTick = 0L
        data.markChanged()
        return data
    }

    private fun location(id: String): ResourceLocation = ResourceLocation.tryParse(id)!!
}
