package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.CampaignRouteStep
import com.gerald.pillagercampaigns.data.LostAssetCache
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.OfficerState as LiveOfficerState
import com.gerald.pillagercampaigns.data.PlannedCampaignMember
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.data.RallyPresenceRecord
import com.gerald.pillagercampaigns.data.RallyPresenceState
import com.gerald.warband.core.CapabilityVector
import com.gerald.warband.core.ChunkPosition
import com.gerald.warband.core.CampaignSnapshotResult
import com.gerald.warband.core.CampaignState as CoreCampaignState
import com.gerald.warband.core.CampaignPhase as CoreCampaignPhase
import com.gerald.warband.core.CoreFrame
import com.gerald.warband.core.CoreCommand
import com.gerald.warband.core.MemberManifest
import com.gerald.warband.core.MemberSnapshot
import com.gerald.warband.core.PlayerLifecycleKind
import com.gerald.warband.core.PlayerLifecycleObservation
import com.gerald.warband.core.OfficerState
import com.gerald.warband.core.RecruitDefinition
import com.gerald.warband.core.SelectionMemory
import com.gerald.warband.core.CoreRules
import com.gerald.warband.core.EnvironmentModelDefinition
import com.gerald.warband.core.EquipmentRealizationResult
import com.gerald.warband.core.EffectKind
import com.gerald.warband.core.WarbandRuntimeSpec
import com.gerald.warband.core.WarbandTransition
import com.gerald.warband.core.WarbandState
import com.gerald.warband.core.RewardDefinition
import net.minecraft.nbt.CompoundTag
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Minecraft adapter for the authoritative Warband Core. Registry inspection and
 * ItemStack construction stay here; economy and selection transitions do not.
 */
object WarbandCoreAdapter {
    private const val CORE_EQUIPMENT_ID_TAG = "PillagerCoreEquipmentId"
    private val recruitTag = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation("pillagercampaigns", "recruits"))

    internal fun rules() = CoreRules(
        minimumAggression = PillagerCampaignsConfig.minimumAggression.get(),
        maximumAggression = PillagerCampaignsConfig.maximumAggression.get(),
        initialAggression = PillagerCampaignsConfig.initialAggression.get(),
        idleReturnAggressionGrowth = PillagerCampaignsConfig.idleReturnAggressionGrowth.get(),
        defeatAggressionGrowth = PillagerCampaignsConfig.defeatAggressionGrowth.get(),
        victoryAggressionGrowth = PillagerCampaignsConfig.victoryAggressionGrowth.get(),
        idleReturnTicks = PillagerCampaignsConfig.idleReturnTicks.get().toLong(),
        respawnProtectionTicks = PillagerCampaignsConfig.respawnProtectionTicks.get().toLong(),
        deathProtectionTicks = PillagerCampaignsConfig.deathProtectionTicks.get().toLong(),
        resolvedRetentionTicks = PillagerCampaignsConfig.resolvedRetentionTicks.get().toLong(),
        materializeDistanceChunks = PillagerCampaignsConfig.materializeDistanceChunks.get(),
        travelTicksPerChunk = PillagerCampaignsConfig.travelTicksPerChunk.get().toLong(),
        raidCooldownTicks = PillagerCampaignsConfig.raidCooldownTicks.get().toLong(),
        captainRecoveryTicks = PillagerCampaignsConfig.captainRecoveryTicks.get().toLong(),
        captainSuccessRecoveryTicks = PillagerCampaignsConfig.captainSuccessRecoveryTicks.get().toLong(),
        dispatchIntervalTicks = PillagerCampaignsConfig.schedulerIntervalTicks.get().toLong(),
        dispatchWorkBudget = PillagerCampaignsConfig.workBudgetPerTick.get(),
        maximumDispatchDistanceChunks = PillagerCampaignsConfig.territoryRadiusChunks.get(),
        discoveryIntervalTicks = PillagerCampaignsConfig.schedulerIntervalTicks.get().toLong() * 10L,
        discoveryWorkBudget = PillagerCampaignsConfig.workBudgetPerTick.get(),
        discoveryGridSpacingChunks = PillagerCampaignsConfig.gridSpacingChunks.get(),
        discoveryMinimumSpacingChunks = PillagerCampaignsConfig.gridSpacingChunks.get(),
        discoveryGridJitterChunks = PillagerCampaignsConfig.gridJitterChunks.get(),
        discoveryMinimumPlayerDistanceChunks = PillagerCampaignsConfig.minSpawnDistanceChunks.get(),
        discoveryMaximumDistanceChunks = PillagerCampaignsConfig.minSpawnDistanceChunks.get().coerceAtLeast(1) * 4,
        discoveryChance = PillagerCampaignsConfig.spawnChancePercent.get() / 100.0,
        discoveryInitialReserveThreat = PillagerCampaignsConfig.initialReserve.get().toDouble(),
        territoryRadiusChunks = PillagerCampaignsConfig.territoryRadiusChunks.get(),
        territoryWarningBandChunks = PillagerCampaignsConfig.warningBandChunks.get(),
        maximumSquadMembers = PillagerCampaignsConfig.maximumSquadMembers.get(),
        recruitBaseTicksPerThreat = PillagerCampaignsConfig.recruitBaseTicksPerThreat.get(),
        recruitHabitabilityPenaltyTicksPerThreat = PillagerCampaignsConfig.recruitHabitabilityPenaltyTicksPerThreat.get(),
        mobilizationBaseTicksPerThreat = PillagerCampaignsConfig.mobilizationBaseTicksPerThreat.get(),
        mobilizationFrictionTicksPerThreat = PillagerCampaignsConfig.mobilizationFrictionTicksPerThreat.get(),
        extractionTicksMultiplier = PillagerCampaignsConfig.extractionTicksMultiplier.get(),
        sustenancePerThreatChunk = PillagerCampaignsConfig.sustenancePerThreatChunk.get(),
        munitionsPerRangedThreatChunk = PillagerCampaignsConfig.munitionsPerRangedThreatChunk.get(),
        maintenancePerEquipmentChunk = PillagerCampaignsConfig.maintenancePerEquipmentChunk.get(),
        deficitGraceChunks = PillagerCampaignsConfig.deficitGraceChunks.get(),
        attritionPerDeficitChunk = PillagerCampaignsConfig.attritionPerDeficitChunk.get(),
        equipmentWearPerFrictionChunk = PillagerCampaignsConfig.equipmentWearPerFrictionChunk.get(),
        forageUnitsPerDeficitChunk = PillagerCampaignsConfig.forageUnitsPerDeficitChunk.get(),
        shortageRetreatBaseChunks = PillagerCampaignsConfig.shortageRetreatBaseChunks.get(),
        shortageAggressionRunwayChunks = PillagerCampaignsConfig.shortageAggressionRunwayChunks.get(),
        warbandLearningRate = PillagerCampaignsConfig.warbandLearningRate.get(),
        captainLearningRate = PillagerCampaignsConfig.captainLearningRate.get(),
        threatLearningRate = PillagerCampaignsConfig.threatLearningRate.get(),
    )

    fun runtimeSpec(server: MinecraftServer): WarbandRuntimeSpec = WarbandRuntimeSpec.create(
        rules = rules(),
        recruits = captureRecruitDefinitions(server),
        resources = WarbandResourceCatalog.definitions(),
        equipmentPlatforms = TinkersArmoryOptimizer.equipmentPlatforms(server),
        materials = TinkersArmoryOptimizer.materialDefinitions(),
        environmentModel = EnvironmentModelDefinition(
            traitWeights = WarbandFormulaData.traitWeights.toSortedMap().mapValues { (_, delta) ->
                com.gerald.warband.core.EnvironmentTraits(
                    delta.habitability, delta.biomass, delta.mineral, delta.exotic, delta.friction,
                )
            },
        ),
        rewards = rewardDefinitions(),
    )

    private fun captureRecruitDefinitions(server: MinecraftServer): List<RecruitDefinition> {
        val level = server.overworld()
        return net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.tags()?.getTag(recruitTag)?.toList().orEmpty()
            .mapNotNull { type ->
                val mob = type.create(level) as? Mob ?: return@mapNotNull null
                try {
                    val id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type)?.toString()
                        ?: return@mapNotNull null
                    val threat = (
                        mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) / 10.0 +
                            mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) +
                            mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR) / 4.0 +
                            mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE) / 32.0
                        ) * WarbandFormulaData.threatCorrections.getOrDefault(id, 1.0)
                    recruitDefinition(id, threat.coerceAtLeast(1.0), mob)
                } finally {
                    mob.discard()
                }
            }.sortedBy(RecruitDefinition::id)
    }

    private fun rewardDefinitions(): List<RewardDefinition> = listOf(
        "createdeco:copper_coin", "createdeco:zinc_coin", "createdeco:iron_coin",
        "createdeco:industrial_iron_coin", "createdeco:brass_coin", "createdeco:gold_coin", "createdeco:netherite_coin",
    ).mapIndexedNotNull { index, id ->
        val item = ResourceLocation.tryParse(id)?.let(net.minecraftforge.registries.ForgeRegistries.ITEMS::getValue)
            ?: return@mapIndexedNotNull null
        RewardDefinition(id, (1 shl index).toDouble(), ItemStack(item).maxStackSize)
    }

    /**
     * The only Forge entry point allowed to advance the persisted strategic
     * state. Native objects may supply observations and execute returned
     * effects, but they never own a second strategic transition.
     */
    fun transition(
        data: PillagerWorldData,
        frame: CoreFrame,
    ): WarbandTransition {
        val initial = data.requireEngine().transition(frame)
        val result = realizeEquipmentEffects(data, initial)
        if (frame.elapsedTicks > 0L || result.events.isNotEmpty() || result.effects.isNotEmpty()) data.markChanged()
        return result
    }

    fun transition(data: PillagerWorldData, frame: CoreFrame, server: MinecraftServer): WarbandTransition {
        ensureEngine(data, server)
        return transition(data, frame)
    }

    private fun ensureEngine(data: PillagerWorldData, server: MinecraftServer) =
        runCatching(data::requireEngine).getOrElse { data.attachRuntimeSpec(runtimeSpec(server)) }

    fun registerPlayer(data: PillagerWorldData, playerId: UUID): Boolean {
        return observePlayerLifecycle(data, playerId, PlayerLifecycleKind.JOINED)
    }

    fun observePlayerLifecycle(data: PillagerWorldData, playerId: UUID, kind: PlayerLifecycleKind): Boolean {
        val id = playerId.toString()
        val wasInitialized = id in data.snapshot().initializedPlayerIds
        transition(data, CoreFrame(0L, playerLifecycle = listOf(PlayerLifecycleObservation(id, kind))))
        return !wasInitialized
    }

    fun protectPlayer(data: PillagerWorldData, playerId: UUID, untilTick: Long) {
        transition(
            data,
            CoreFrame(0L, commands = listOf(CoreCommand.ProtectPlayer(playerId.toString(), untilTick))),
        )
    }

    fun resetWorld(data: PillagerWorldData) {
        transition(data, CoreFrame(0L, commands = listOf(CoreCommand.ResetWorld)))
        synchronizeNativeViews(data)
    }

    /** Rebuilds Minecraft-only views from the persisted canonical snapshot. */
    fun synchronizeNativeViews(data: PillagerWorldData) {
        val snapshot = data.snapshot()
        data.factions.keys.retainAll(snapshot.factions.keys.mapNotNullTo(hashSetOf()) { it.asUuidOrNull() })
        data.warbands.keys.retainAll(snapshot.warbands.keys.mapNotNullTo(hashSetOf()) { it.asUuidOrNull() })
        data.officers.keys.retainAll(snapshot.officers.keys.mapNotNullTo(hashSetOf()) { it.asUuidOrNull() })
        data.campaigns.keys.retainAll(snapshot.campaigns.keys.mapNotNullTo(hashSetOf()) { it.asUuidOrNull() })
        snapshot.factions.values.forEach { core ->
            val id = core.id.asUuidOrNull() ?: return@forEach
            val cosmetic = data.minecraftSidecar.cosmetics[core.id]
            val existing = data.factions[id]
            if (existing == null) {
                data.factions[id] = PillagerFaction(id, cosmetic?.name ?: core.name, core.bannerSeed, null)
            } else {
                existing.name = cosmetic?.name ?: core.name
                existing.bannerSeed = core.bannerSeed
            }
        }
        snapshot.officers.values.forEach { core ->
            val id = core.id.asUuidOrNull() ?: return@forEach
            val factionId = core.factionId.asUuidOrNull() ?: return@forEach
            val warbandId = core.homeWarbandId.asUuidOrNull() ?: return@forEach
            val cosmetic = data.minecraftSidecar.cosmetics[core.id]
            val existing = data.officers[id]
            if (existing == null) {
                data.officers[id] = PillagerOfficer(
                    id, factionId, warbandId,
                    cosmetic?.name ?: "Captain ${id.toString().take(8)}",
                    cosmetic?.title ?: "the Captain",
                    role = OfficerRole.CAPTAIN,
                    rank = OfficerRank.entries[(core.rank - 1).coerceIn(0, OfficerRank.entries.lastIndex)],
                    state = when {
                        core.availableAtTick == Long.MAX_VALUE -> LiveOfficerState.DEAD
                        core.deployedCampaignId != null -> LiveOfficerState.DEPLOYED
                        core.availableAtTick > snapshot.tick -> LiveOfficerState.RECOVERING
                        else -> LiveOfficerState.IDLE
                    },
                    preferenceGraph = core.preferences.toMutableMap(),
                    campaignVictories = core.victories,
                    campaignDefeats = core.defeats,
                    lastTargetPlayerId = core.lastTargetPlayerId?.asUuidOrNull(),
                    injuryOrRecoveryUntilTick = core.availableAtTick,
                    promotionTier = core.rank - 1,
                )
            } else {
                existing.homeWarbandId = warbandId
                existing.preferenceGraph.clear(); existing.preferenceGraph.putAll(core.preferences)
                existing.campaignVictories = core.victories
                existing.campaignDefeats = core.defeats
                existing.rank = OfficerRank.entries[(core.rank - 1).coerceIn(0, OfficerRank.entries.lastIndex)]
                existing.promotionTier = core.rank - 1
                existing.injuryOrRecoveryUntilTick = core.availableAtTick
                existing.lastTargetPlayerId = core.lastTargetPlayerId?.asUuidOrNull()
                existing.state = when {
                    core.availableAtTick == Long.MAX_VALUE -> LiveOfficerState.DEAD
                    core.deployedCampaignId != null -> LiveOfficerState.DEPLOYED
                    core.availableAtTick > snapshot.tick -> LiveOfficerState.RECOVERING
                    else -> LiveOfficerState.IDLE
                }
            }
        }
        snapshot.warbands.values.forEach { core ->
            val id = core.id.asUuidOrNull() ?: return@forEach
            val factionId = core.factionId.asUuidOrNull() ?: return@forEach
            val dimension = net.minecraft.resources.ResourceLocation.tryParse(core.rally.dimension) ?: return@forEach
            val officerId = snapshot.officers.values.firstOrNull { it.homeWarbandId == core.id }?.id?.asUuidOrNull() ?: return@forEach
            val existing = data.warbands[id]
            val live = existing ?: PillagerWarband(
                id, factionId, dimension, snapshot.factions[core.factionId]?.bannerSeed ?: id.hashCode(),
                core.rally.x, core.rally.z, core.reserveThreat.roundToInt(), core.capacity.roundToInt(),
                defeated = core.defeated, warlordOfficerId = officerId, warlordEntityId = null,
                nextRaidTick = core.nextRaidTick, cooldownUntilTick = 0L, lastIntelTick = snapshot.tick,
                lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
                rallyPresence = RallyPresenceRecord(RallyPresenceState.DORMANT, officerId),
            ).also { data.warbands[id] = it }
            live.rallyChunkX = core.rally.x; live.rallyChunkZ = core.rally.z
            live.capacity = core.capacity.roundToInt(); live.reserve = core.reserveThreat.roundToInt()
            live.raidPool = core.raidPool; live.aggression = core.aggression; live.environment = core.environment
            live.preferences.clear(); live.preferences.putAll(core.preferences)
            live.materialLedger.clear(); live.materialLedger.putAll(core.materialLedger)
            live.empiricalThreat.clear(); live.empiricalThreat.putAll(core.empiricalThreat)
            live.stockpile.clear(); live.stockpile.putAll(core.stockpile)
            live.playerRelations.clear()
            snapshot.territoryRelations.values.filter { it.warbandId == core.id }.forEach { relation ->
                relation.playerId.asUuidOrNull()?.let { live.playerRelations[it] = relation.status.name }
            }
            live.recruitTickDebt = core.recruitTickDebt; live.mobilizationTickDebt = core.mobilizationTickDebt
            live.extractionTickDebt = core.extractionTickDebt; live.nextRaidTick = core.nextRaidTick
            live.defeated = core.defeated; live.activeCampaignLimit = core.activeCampaignLimit
            syncSelectionMemory(live, core)
            live.armory.clear()
            core.armory.mapNotNull { equipment ->
                data.minecraftSidecar.itemSnapshots[equipment.id]?.firstOrNull()?.copy()?.also {
                    it.putString(CORE_EQUIPMENT_ID_TAG, equipment.id)
                }
            }.forEach(live.armory::add)
            live.garrisonThreat.clear()
            snapshot.garrisons.values.asSequence()
                .filter { it.warbandId == core.id && it.phase == com.gerald.warband.core.GarrisonPhase.ACTIVE }
                .flatMap { it.members.asSequence() }
                .forEach { member -> data.minecraftSidecar.entityIds[member.id]?.let { live.garrisonThreat[it] = member.threat } }
        }
        snapshot.campaigns.values.forEach { core -> synchronizeCampaignView(data, core) }
    }

    fun advanceCanonical(server: MinecraftServer, data: PillagerWorldData, now: Long, observations: CoreFrame): WarbandTransition {
        ensureEngine(data, server)
        val elapsed = (now - data.snapshot().tick).coerceAtLeast(0L)
        val result = transition(
            data,
            observations.copy(elapsedTicks = elapsed),
        )
        synchronizeNativeViews(data)
        return result
    }

    fun manufacture(server: MinecraftServer, data: PillagerWorldData, warbandId: UUID, count: Int = 1): WarbandTransition {
        ensureEngine(data, server)
        val result = transition(
            data,
            CoreFrame(0L, commands = listOf(CoreCommand.Manufacture(warbandId.toString(), count))),
        )
        synchronizeNativeViews(data)
        return result
    }

    private fun realizeEquipmentEffects(data: PillagerWorldData, transition: WarbandTransition): WarbandTransition {
        val results = transition.effects.asSequence()
            .filter { it.kind == EffectKind.REALIZE_EQUIPMENT }
            .mapNotNull { effect ->
                val manifest = effect.equipmentManifest ?: return@mapNotNull null
                val existing = data.minecraftSidecar.itemSnapshots[manifest.id]?.firstOrNull()?.let(ItemStack::of)
                val stack = existing ?: TinkersArmoryOptimizer.realize(manifest)
                if (stack != null && existing == null) {
                    data.minecraftSidecar.itemSnapshots[manifest.id] = mutableListOf(
                        stack.save(CompoundTag()).also { it.putString(CORE_EQUIPMENT_ID_TAG, manifest.id) },
                    )
                }
                val measured = stack?.let { TinkersArmoryOptimizer.manifest(manifest.id, it).capabilities }
                EquipmentRealizationResult(
                    effect.effectId,
                    manifest.id,
                    stack != null,
                    measured,
                    if (stack != null) "exact_formulation_realized" else "exact_formulation_failed",
                )
            }.toList()
        if (results.isEmpty()) return transition
        val acknowledged = data.requireEngine().transition(CoreFrame(0L, equipmentRealizations = results))
        data.markChanged()
        return WarbandTransition(transition.events + acknowledged.events, acknowledged.effects)
    }

    internal fun snapshotResult(campaign: PillagerCampaign, effectId: String? = null): CampaignSnapshotResult = CampaignSnapshotResult(
        campaign.id.toString(),
        ChunkPosition(campaign.targetDimension.toString(), campaign.currentChunkX, campaign.currentChunkZ),
        campaign.plannedMembers.mapIndexed { index, member ->
            val memberId = member.manifestId.ifBlank { "legacy:${campaign.id}:$index" }
            MemberSnapshot(
                memberId,
                member.healthFraction,
                equipment = member.equipment?.let { TinkersArmoryOptimizer.manifest("$memberId:equipment", ItemStack.of(it)) },
                cargo = member.cargo.toMap(),
            )
        },
        effectId,
    )

    private fun synchronizeCampaignView(data: PillagerWorldData, core: CoreCampaignState) {
        val id = core.id.asUuidOrNull() ?: return
        val warbandId = core.warbandId.asUuidOrNull() ?: return
        val warband = data.warbands[warbandId] ?: return
        val factionId = warband.factionId
        val officerId = core.officerId.asUuidOrNull() ?: return
        val playerId = core.targetPlayerId.asUuidOrNull() ?: return
        val dimension = net.minecraft.resources.ResourceLocation.tryParse(core.target.dimension) ?: return
        val members = core.members.mapNotNullTo(mutableListOf()) { member ->
            val recruitId = net.minecraft.resources.ResourceLocation.tryParse(member.recruitId) ?: return@mapNotNullTo null
            PlannedCampaignMember(
                recruitId,
                member.threat,
                member.equipment?.let { equipment ->
                    data.minecraftSidecar.itemSnapshots[equipment.id]?.firstOrNull()?.copy()?.also {
                        it.putString(CORE_EQUIPMENT_ID_TAG, equipment.id)
                    }
                },
                member.cargo.toMutableMap(),
                member.id,
                member.healthFraction,
            )
        }
        val existing = data.campaigns[id]
        val live = existing ?: PillagerCampaign(
            id, factionId, warbandId, officerId, playerId, dimension,
            core.position.x, core.position.z, core.target.x, core.target.z,
            kotlin.math.ceil(core.members.sumOf(MemberManifest::threat)).toInt(), data.snapshot().sequence,
            core.travelTickDebt.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), core.phase.toLivePhase(), null,
            null, 0L, mutableListOf(), core.lastCombatTick,
            committedThreat = core.members.sumOf(MemberManifest::threat),
            plannedMembers = members,
            route = core.route.mapTo(mutableListOf()) { CampaignRouteStep(it.x, it.z) },
        ).also { data.campaigns[id] = it }
        live.currentChunkX = core.position.x; live.currentChunkZ = core.position.z
        live.targetChunkX = core.target.x; live.targetChunkZ = core.target.z
        live.targetDimension = dimension; live.state = core.phase.toLivePhase()
        live.tickDebt = core.travelTickDebt.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        live.lastCombatTick = core.lastCombatTick; live.returnReason = core.returnReason
        live.returnAggressionDelta = core.returnAggressionDelta; live.routeIndex = core.routeIndex
        live.supplySatisfaction = core.supplySatisfaction; live.deficitExposure = core.deficitExposure
        live.forageDebt = core.forageDebt; live.committedThreat = core.members.sumOf(MemberManifest::threat)
        live.route.clear(); live.route += core.route.map { CampaignRouteStep(it.x, it.z) }
        live.plannedMembers.clear(); live.plannedMembers += members
        live.squadMemberIds.clear()
        core.physicalMemberIds.mapNotNullTo(live.squadMemberIds) { data.minecraftSidecar.entityIds[it] }
    }

    private fun CoreCampaignPhase.toLivePhase(): CampaignState = when (this) {
        CoreCampaignPhase.OUTBOUND -> CampaignState.TRAVELING
        CoreCampaignPhase.READY_TO_MATERIALIZE -> CampaignState.READY_TO_MATERIALIZE
        CoreCampaignPhase.MATERIALIZING -> CampaignState.MATERIALIZING
        CoreCampaignPhase.ACTIVE -> CampaignState.ACTIVE
        CoreCampaignPhase.RETURNING -> CampaignState.RETURNING
        CoreCampaignPhase.RESOLVED -> CampaignState.RESOLVED
    }

    private fun syncSelectionMemory(warband: PillagerWarband, core: WarbandState) {
        warband.recruitSelectionMemory.clear()
        warband.recruitSelectionMemory.putAll(core.selectionMemory.recruits)
        warband.materialSelectionMemory.clear()
        warband.materialSelectionMemory.putAll(core.selectionMemory.materials)
        warband.equipmentSelectionMemory.clear()
        warband.equipmentSelectionMemory.putAll(core.selectionMemory.equipment)
        warband.selectionMemoryLastTick = core.selectionMemory.lastDecayTick
    }

    fun recruitDefinition(id: String, threat: Double, mob: Mob): RecruitDefinition = RecruitDefinition(
        id,
        threat,
        CapabilityVector(
            durability = (mob.maxHealth + mob.armorValue * 2.0) / 40.0,
            damage = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) / 8.0,
            mobility = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) / 0.3,
            range = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE) / 32.0,
        ),
        supportedEquipmentActions = buildSet {
            if (mob is net.minecraft.world.entity.monster.CrossbowAttackMob || mob is net.minecraft.world.entity.monster.RangedAttackMob) add("ranged")
            if (mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) > 0.0) add("melee")
        },
    )

    private fun String.asUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
}
