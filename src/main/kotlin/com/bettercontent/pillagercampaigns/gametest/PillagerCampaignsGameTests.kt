package com.bettercontent.pillagercampaigns.gametest

import com.bettercontent.pillagercampaigns.PillagerCampaignsMod
import com.bettercontent.pillagercampaigns.core.*
import com.bettercontent.pillagercampaigns.system.InvasionRoster
import com.bettercontent.pillagercampaigns.system.InvasionRuntime
import com.bettercontent.pillagercampaigns.system.SurfaceGridSampler
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.util.FakePlayer
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import com.mojang.authlib.GameProfile
import java.util.UUID

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object PillagerCampaignsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 80)
    fun runtimeRosterAndDummyPlayerAtYDriveProductionPolicy(helper: GameTestHelper) {
        val spec = InvasionRoster.runtimeSpec()
        spec.requireValid()
        val required = setOf(
            "minecraft:pillager", "takesapillage:archer", "takesapillage:skirmisher",
            "takesapillage:legioner", "savage_and_ravage:griefer", "savage_and_ravage:trickster",
            "savage_and_ravage:executioner", "savage_and_ravage:iceologer",
        )
        helper.assertTrue(spec.recruits.map { it.entityId }.containsAll(required),
            "Build roster must expose the authored It Takes a Pillage and Savage & Ravage recruits")
        val fake = fake(helper, "policy")
        fake.setGameMode(GameType.SURVIVAL)
        val atY = helper.absolutePos(BlockPos(8, 3, 8))
        fake.moveTo(atY.x + 0.5, atY.y.toDouble(), atY.z + 0.5)
        val director = InvasionDirector.create(19, InvasionRuntimeSpec.create(
            InvasionRules(scoutWindowMinTicks = 0, scoutWindowMaxTicks = 0), spec.recruits))
        director.transition(DirectorFrame(0, listOf(PlayerObservation(fake.uuid.toString(), true, true, true,
            BlockPoint("minecraft:overworld", atY.x, atY.y, atY.z)))))
        val encounter = director.snapshot().tracks[fake.uuid.toString()]?.invasion
        helper.assertTrue(encounter?.kind == EncounterKind.SCOUT,
            "A real dummy survival player at the supplied Y must start the due scout policy")
        helper.assertTrue(encounter?.members?.size == 3, "Initial scout must contain exactly three authored recruits")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 100)
    fun loadedSurfaceGateCollisionFluidAndReachabilityAreExact(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val fake = fake(helper, "route")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val zombie = EntityType.ZOMBIE.create(helper.level)!!
        zombie.moveTo(base.x + 3.5, base.y.toDouble(), base.z + 7.5)
        helper.assertTrue(InvasionRuntime.exactCandidate(helper.level, zombie, zombie.blockPosition()),
            "Dry two-block space on a solid floor must be an exact candidate")
        helper.level.addFreshEntity(zombie)
        zombie.setOnGround(true)
        val openPath = zombie.navigation.createPath(fake.blockPosition(), 0)
        helper.assertTrue(openPath != null && openPath.canReach(),
            "Open gate path=$openPath canReach=${openPath?.canReach()} mob=${zombie.blockPosition()} target=${fake.blockPosition()} added=${!zombie.isRemoved}")

        for (z in 6..8) {
            helper.level.setBlockAndUpdate(base.offset(7, 0, z), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(7, 1, z), Blocks.STONE.defaultBlockState())
        }
        val closedPath = zombie.navigation.createPath(fake.blockPosition(), 0)
        helper.assertTrue(closedPath == null || !closedPath.canReach(), "Closed gate must be partial or unreachable")

        val solid = base.offset(3, 0, 3)
        helper.level.setBlockAndUpdate(solid, Blocks.STONE.defaultBlockState())
        zombie.moveTo(solid.x + 0.5, solid.y.toDouble(), solid.z + 0.5)
        helper.assertTrue(!InvasionRuntime.exactCandidate(helper.level, zombie, solid),
            "Solid occupation must reject exact materialization")
        val fluid = base.offset(4, 0, 3)
        helper.level.setBlockAndUpdate(fluid, Blocks.WATER.defaultBlockState())
        zombie.moveTo(fluid.x + 0.5, fluid.y.toDouble(), fluid.z + 0.5)
        helper.assertTrue(!InvasionRuntime.exactCandidate(helper.level, zombie, fluid),
            "Fluid occupation must reject exact materialization")

        val remoteChunkX = (base.x shr 4) + 20
        val remoteChunkZ = base.z shr 4
        helper.assertTrue(helper.level.chunkSource.getChunkNow(remoteChunkX, remoteChunkZ) == null,
            "Remote route chunk must begin unloaded")
        helper.assertTrue(SurfaceGridSampler.surfaceCell(helper.level, remoteChunkX shl 4, remoteChunkZ shl 4) == null,
            "Unknown surface cells must remain impassable")
        helper.assertTrue(helper.level.chunkSource.getChunkNow(remoteChunkX, remoteChunkZ) == null,
            "Sampling an unknown cell must not load, ticket, or generate its chunk")
        zombie.discard()
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 100)
    fun eventAuthoredSpawnGetsTargetProvenanceAndCleansUp(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val fake = fake(helper, "spawn")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val anchor = base.offset(3, 0, 7)
        helper.assertTrue(SurfaceGridSampler.loadedRectangle(helper.level, anchor, fake.blockPosition()),
            "The complete bounded navigation region must already be loaded before EVENT materialization")
        InvasionRoster.runtimeSpec().recruits.forEachIndexed { index, recruit ->
            val invasionId = "catalogue-test-$index"
            val memberId = "wave:1:member:${index + 1}"
            val effect = DirectorEffect(
                "test-effect-$index", EffectKind.MATERIALIZE, fake.uuid.toString(), invasionId,
                EncounterKind.ASSAULT, 1,
                BlockPoint("minecraft:overworld", anchor.x, anchor.y, anchor.z),
                listOf(MemberPlan(memberId, recruit.entityId, recruit.cost, 1)),
            )
            helper.assertTrue(InvasionRuntime.materializeAgainst(helper.level, fake, effect),
                "EVENT-authored ${recruit.entityId} must spawn and reach the dummy player")
            val member = InvasionRuntime.liveMembers(helper.level.server, invasionId).single()
            helper.assertTrue(member.target === fake, "${recruit.entityId} must target the eligible dummy player")
            helper.assertTrue(member.persistentData.getString(InvasionRuntime.INVASION_TAG) == invasionId &&
                member.persistentData.getString(InvasionRuntime.MEMBER_TAG) == memberId,
                "${recruit.entityId} must receive encounter and member provenance")
            helper.assertTrue(member.persistentData.getString(InvasionRuntime.KIND_TAG) == "assault" &&
                member.persistentData.getInt(InvasionRuntime.WAVE_TAG) == 1,
                "${recruit.entityId} must receive kind and wave provenance")
            InvasionRuntime.retire(helper.level.server, invasionId)
            helper.assertTrue(InvasionRuntime.liveMembers(helper.level.server, invasionId).isEmpty(),
                "${recruit.entityId} must be cleaned after its catalogue probe")
        }
        helper.succeed()
    }

    private fun buildSurface(helper: GameTestHelper, base: BlockPos) {
        for (x in 0..14) for (z in 0..14) {
            helper.level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.AIR.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(x, 1, z), Blocks.AIR.defaultBlockState())
        }
        for (z in 0..14) if (z !in 6..8) {
            helper.level.setBlockAndUpdate(base.offset(7, 0, z), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(7, 1, z), Blocks.STONE.defaultBlockState())
        }
    }

    private fun fake(helper: GameTestHelper, suffix: String) =
        FakePlayer(helper.level, GameProfile(UUID.nameUUIDFromBytes("campaign-test-$suffix".toByteArray()), "campaign-test-$suffix"))
}
