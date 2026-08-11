package com.gerald.pillagercampaigns.gametest

import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.data.CampaignState
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
import com.gerald.warband.core.CoreCatalog
import com.gerald.warband.core.EnvironmentTraits
import com.gerald.warband.core.MaterialDefinition
import com.gerald.pillagercampaigns.system.PillagerCampaignCoordinator
import com.gerald.pillagercampaigns.system.PillagerRuntime
import com.gerald.pillagercampaigns.system.PillagerWarbandPresenceSystem
import com.gerald.pillagercampaigns.system.PillagerWarbandDiscoveryRules
import com.gerald.pillagercampaigns.system.PillagerWarbandDiscoveryService
import com.gerald.pillagercampaigns.system.TinkersArmoryOptimizer
import com.gerald.pillagercampaigns.system.WarbandFormulaData
import com.gerald.pillagercampaigns.system.WarbandResourceCatalog
import com.gerald.pillagercampaigns.system.WarbandCoreAdapter
import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.entity.item.ItemEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.registries.ForgeRegistries
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object PillagerCampaignsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 200)
    fun minimumThreatCampaignMaterializesRealRecruit(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerWarbandDiscoveryRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:minimum-threat-materialization".toByteArray()),
            dimension = level.dimension().location(), cellX = 0, cellZ = 0, chunkX = anchor.x, chunkZ = anchor.z,
        )
        helper.assertTrue(PillagerWarbandDiscoveryService.registerDiscoveredWarband(level, data, candidate, level.gameTime), "warband should register")
        val warband = data.warbands.getValue(candidate.id)
        warband.lastPresenceAttemptTick = -1_000L
        val officer = data.officers.getValue(warband.warlordOfficerId)
        val player = FakePlayerFactory.get(level, GameProfile(UUID.nameUUIDFromBytes("gametest:target".toByteArray()), "CampaignTarget"))
        val playerPos = helper.absolutePos(BlockPos(5, 2, 5))
        level.getChunk(playerPos.x shr 4, playerPos.z shr 4)
        val floorY = playerPos.y - 1
        val minX = (playerPos.x shr 4) shl 4
        val minZ = (playerPos.z shr 4) shl 4
        for (x in minX until minX + 16) for (z in minZ until minZ + 16) {
            level.setBlockAndUpdate(BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState())
        }
        player.moveTo(playerPos.x + .5, playerPos.y.toDouble(), playerPos.z + .5)
        val minimum = PillagerRuntime.minimumRecruitThreat(level, warband)
        helper.assertTrue(minimum != null && minimum > 0.0, "live recruit catalogue should expose a minimum threat")
        TinkersArmoryOptimizer.materialDefinitions().forEach { warband.materialLedger[it.id] = 10_000.0 }
        val liveArmament = TinkersArmoryOptimizer.liveEquipmentCandidates(warband, level.server)
            .firstOrNull { TinkersArmoryOptimizer.equipmentSlot(it.stack) != net.minecraft.world.entity.EquipmentSlot.MAINHAND }
            ?: TinkersArmoryOptimizer.liveEquipmentCandidates(warband, level.server).firstOrNull()
        helper.assertTrue(liveArmament != null, "a live TCon armament must be formulable")
        warband.armory += TinkersArmoryOptimizer.realize(liveArmament!!).save(net.minecraft.nbt.CompoundTag())
        val planned = PillagerRuntime.planCampaignSquad(level, warband, officer, minimum!!, 42L)
        helper.assertTrue(planned.isNotEmpty(), "authoritative Warband Core should persist an affordable squad manifest")
        planned.first().cargo["minecraft:bread"] = 3
        val campaign = PillagerCampaign(
            id = UUID.nameUUIDFromBytes("gametest:minimum-campaign".toByteArray()), factionId = warband.factionId,
            originWarbandId = warband.id, officerId = officer.id, targetPlayerId = player.uuid,
            targetDimension = level.dimension().location(), currentChunkX = player.chunkPosition().x,
            currentChunkZ = player.chunkPosition().z, targetChunkX = player.chunkPosition().x, targetChunkZ = player.chunkPosition().z,
            difficultySnapshot = 1, loadoutSeed = 42L, tickDebt = 0, state = CampaignState.READY_TO_MATERIALIZE,
            resumeState = null, materializeAttemptId = null, materializingUntilTick = 0L, squadMemberIds = mutableListOf(),
            committedThreat = planned.sumOf { it.threat }, plannedMembers = planned.toMutableList(),
        )
        data.campaigns[campaign.id] = campaign
        val faction = data.factions.getValue(warband.factionId)
        val spawned = PillagerRuntime.materializeWarbandSquad(
            level, warband, campaign, faction.bannerSeed, officer, player,
            playerPos.x + .5, playerPos.y.toDouble(), playerPos.z + .5,
        )
        campaign.squadMemberIds += spawned
        helper.assertTrue(spawned.isNotEmpty(), "minimum affordable campaign should materialize a live candidate")
        val materializedTypes = spawned.mapNotNull { id -> (level.getEntity(id) as? net.minecraft.world.entity.Mob)?.let { ForgeRegistries.ENTITY_TYPES.getKey(it.type)?.toString() } }
        helper.assertTrue(materializedTypes == planned.map { it.recruitId.toString() }, "Forge must materialize the exact Core-selected recruit manifest")
        planned.zip(spawned).forEach { (member, entityId) -> member.equipment?.let { equipment ->
            val expected = ItemStack.of(equipment)
            val mob = level.getEntity(entityId) as net.minecraft.world.entity.Mob
            val actual = mob.getItemBySlot(TinkersArmoryOptimizer.equipmentSlot(expected))
            helper.assertTrue(ItemStack.isSameItemSameTags(expected, actual), "Forge must materialize the exact TCon formulation in its functional equipment slot")
        } }
        helper.assertTrue(campaign.squadMemberIds.isNotEmpty(), "minimum campaign should contain a real recruit")
        helper.assertTrue(campaign.memberThreat.values.sum() > 0.0, "materialized recruit should carry exact threat")
        val originalIds = campaign.squadMemberIds.toSet()
        helper.assertTrue(PillagerRuntime.snapshotCampaign(level, campaign) == originalIds.size, "loaded members should serialize at the materialization frontier")
        helper.assertTrue(campaign.memberSnapshots.size == originalIds.size, "every member should have an exact serialized snapshot")
        val restored = PillagerRuntime.restoreSnapshots(level, campaign, playerPos)
        helper.assertTrue(restored.toSet() == originalIds, "rematerialization should preserve member identities")
        val cargoMob = level.getEntity(restored.first()) as net.minecraft.world.entity.Mob
        helper.assertTrue(cargoMob.persistentData.getCompound(PillagerRuntime.CARGO_TAG).getInt("minecraft:bread") == 3, "materialization must attach the exact abstract cargo manifest")
        PillagerRuntime.dropCampaignCargo(cargoMob, campaign)
        helper.assertTrue(planned.first().cargo.isEmpty(), "intercepted cargo must leave the abstract manifest exactly once")
        val breadDrops = level.getEntitiesOfClass(ItemEntity::class.java, cargoMob.boundingBox.inflate(8.0))
            .filter { it.item.`is`(Items.BREAD) }.sumOf { it.item.count }
        helper.assertTrue(breadDrops == 3, "interception must drop the exact remaining cargo count")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 200)
    fun liveCatalogSnapshotIsDeterministicAndComplete(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerWarbandDiscoveryRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:live-catalog".toByteArray()), dimension = level.dimension().location(),
            cellX = 0, cellZ = 0, chunkX = anchor.x, chunkZ = anchor.z,
        )
        helper.assertTrue(PillagerWarbandDiscoveryService.registerDiscoveredWarband(level, data, candidate, level.gameTime), "warband should register")
        val warband = data.warbands.getValue(candidate.id)
        val materials = TinkersArmoryOptimizer.materialDefinitions()
        val environments = listOf(EnvironmentTraits()) + WarbandFormulaData.traitWeights.toSortedMap().values.map { delta ->
            EnvironmentTraits(
                .5 + delta.habitability, .5 + delta.biomass, .5 + delta.mineral,
                .5 + delta.exotic, .5 + delta.friction,
            ).bounded()
        }
        val originalReserve = warband.reserve
        val originalEnvironment = warband.environment
        warband.reserve = warband.capacity
        materials.forEach { warband.materialLedger[it.id] = 10_000.0 }
        val recruits = PillagerRuntime.recruitDefinitions(level, warband)
        val equipmentByEnvironment = linkedMapOf<EnvironmentTraits, Set<String>>()
        val equipmentCandidates = buildList {
            environments.forEach { environment ->
                warband.environment = environment
                val candidates = TinkersArmoryOptimizer.liveEquipmentCandidates(warband, level.server)
                equipmentByEnvironment[environment] = candidates.mapTo(linkedSetOf()) { it.definition.id }
                addAll(candidates)
            }
            warband.environment = originalEnvironment
            // The pure runner consumes a static registry boundary while live Forge
            // reformulates from its changing ledger. Sample every observed material
            // and natural three-material ledger window so the static boundary
            // contains the same formulations the runtime can actually derive.
            (materials.map { listOf(it.id) } + materials.map(MaterialDefinition::id).chunked(3)).forEach { supply ->
                warband.materialLedger.clear()
                supply.forEach { warband.materialLedger[it] = 10_000.0 }
                addAll(TinkersArmoryOptimizer.liveEquipmentCandidates(warband, level.server))
            }
        }.distinctBy { it.definition.id }.sortedBy { it.definition.id }
        warband.reserve = originalReserve
        warband.environment = originalEnvironment
        warband.materialLedger.clear()
        materials.forEach { warband.materialLedger[it.id] = 10_000.0 }
        val equipment = equipmentCandidates.map { it.definition }
        val resources = WarbandResourceCatalog.definitions()
        val canonical = Json.encodeToString(CoreCatalog("unhashed", recruits, materials, equipment, environments, resources))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        val catalog = CoreCatalog("forge-live-sha256:$digest", recruits, materials, equipment, environments, resources)
        helper.assertTrue(catalog.recruits.isNotEmpty(), "snapshot must contain the live recruit tag")
        helper.assertTrue(catalog.materials.isNotEmpty(), "snapshot must contain live TCon materials")
        helper.assertTrue(catalog.materials.map { it.capabilities }.distinct().size >= 10, "live TCon material stats must project to a varied capability population")
        helper.assertTrue(catalog.equipment.isNotEmpty(), "snapshot must contain legal live TCon formulations")
        helper.assertTrue(equipmentByEnvironment.values.distinct().size > 1, "environment samples must produce materially distinct live TCon candidate sets")
        val actions = catalog.equipment.flatMapTo(linkedSetOf()) { it.actions }
        helper.assertTrue(actions.containsAll(setOf("melee", "ranged", "defense", "utility")), "live TCon formulations must expose melee, ranged, defensive, and utility functions")
        helper.assertTrue(equipment.any { definition ->
            val candidateStack = equipmentCandidates.firstOrNull { it.definition.id == definition.id }?.stack ?: return@any false
            TinkersArmoryOptimizer.equipmentSlot(candidateStack) in setOf(
                net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET,
            )
        }, "live catalog must contain wearable TCon armor")
        helper.assertTrue(catalog.resources.any { it.unitsPerItem.sustenance > 0.0 }, "snapshot must contain edible logistics resources")
        helper.assertTrue(catalog.resources.any { it.unitsPerItem.munitions > 0.0 }, "snapshot must contain ammunition resources")
        helper.assertTrue(catalog.resources.any { it.unitsPerItem.maintenance > 0.0 }, "snapshot must contain maintenance resources")
        System.getProperty("pillagercampaigns.catalogOutput")?.let { path ->
            File(path).also { it.parentFile.mkdirs() }.writeText(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(catalog))
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 200)
    fun tinkersArmoryConsumesExactPartMaterialUnits(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerWarbandDiscoveryRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:tcon-ledger".toByteArray()), dimension = level.dimension().location(),
            cellX = 0, cellZ = 0, chunkX = anchor.x, chunkZ = anchor.z,
        )
        helper.assertTrue(PillagerWarbandDiscoveryService.registerDiscoveredWarband(level, data, candidate, level.gameTime), "warband should register")
        val warband = data.warbands.getValue(candidate.id)
        val coreWarband = data.coreState.warbands.getValue(candidate.id.toString())
        coreWarband.armory.clear()
        coreWarband.materialLedger.clear()
        TinkersArmoryOptimizer.materialDefinitions().forEach { coreWarband.materialLedger[it.id] = 96.0 }
        WarbandCoreAdapter.synchronizeNativeViews(data)
        val before = coreWarband.materialLedger.toMap()
        WarbandCoreAdapter.manufacture(level.server, data, warband.id)
        val stack = data.warbands.getValue(warband.id).armory.singleOrNull()?.let(ItemStack::of)
        helper.assertTrue(stack != null && !stack.isEmpty, "an affordable live TCon formulation should be constructed")
        val cost = stack?.let(TinkersArmoryOptimizer::cost).orEmpty()
        helper.assertTrue(cost.isNotEmpty() && cost.values.all { it > 0.0 }, "constructed equipment should retain an exact positive bill of materials")
        cost.forEach { (id, amount) -> helper.assertTrue(kotlin.math.abs(before.getOrDefault(id, 0.0) - coreWarband.materialLedger.getOrDefault(id, 0.0) - amount) < 0.0001, "Core ledger should consume exactly $amount of $id") }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun warbandRegistrationCreatesFactionOfficerAndWarband(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerWarbandDiscoveryRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:warband-registration".toByteArray()),
            dimension = level.dimension().location(),
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
        helper.assertTrue(
            warband.reserve == data.coreState.warbands.getValue(warband.id.toString()).reserveThreat.toInt(),
            "native reserve should project the environment-derived Core value",
        )
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

        val dimension = helper.level.dimension().location().toString()
        data.coreState.factions[factionId.toString()] = com.gerald.warband.core.FactionState(factionId.toString(), "Test Faction", 7)
        data.coreState.warbands[warbandId.toString()] = com.gerald.warband.core.WarbandState(
            warbandId.toString(), factionId.toString(), com.gerald.warband.core.ChunkPosition(dimension, 0, 0),
            capacity = 156.0, reserveThreat = 18.0,
        )
        data.coreState.officers[officerId.toString()] = com.gerald.warband.core.OfficerState(
            officerId.toString(), factionId.toString(), warbandId.toString(),
            rank = 3, deployedCampaignId = campaignId.toString(),
        )
        data.coreState.campaigns[campaignId.toString()] = com.gerald.warband.core.CampaignState(
            campaignId.toString(), warbandId.toString(), officerId.toString(), UUID.randomUUID().toString(),
            com.gerald.warband.core.ChunkPosition(dimension, 0, 0),
            com.gerald.warband.core.ChunkPosition(dimension, 1, 1),
            mutableListOf(), phase = com.gerald.warband.core.CampaignPhase.ACTIVE,
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)

        PillagerCampaignCoordinator.collapseWarband(data, warbandId)

        helper.assertTrue(data.warbands.getValue(warbandId).defeated, "warband should be defeated")
        helper.assertTrue(data.campaigns.getValue(campaignId).state == CampaignState.RESOLVED, "campaign should resolve")
        helper.assertTrue(data.officers.getValue(officerId).state == OfficerState.DEAD, "home officer should be marked dead")
        helper.succeed()
    }

    private fun resetWorldData(helper: GameTestHelper): PillagerWorldData {
        val data = PillagerWorldData.get(helper.level.server)
        WarbandCoreAdapter.resetWorld(data)
        return data
    }

    private fun location(id: String): ResourceLocation = ResourceLocation.tryParse(id)!!
}
