package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.CampaignRouteStep
import com.gerald.pillagercampaigns.data.LostAssetCache
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.engine.CapabilityVector
import com.gerald.pillagercampaigns.engine.ChunkPosition
import com.gerald.pillagercampaigns.engine.EngineCatalog
import com.gerald.pillagercampaigns.engine.CampaignOutcomeObservation
import com.gerald.pillagercampaigns.engine.CampaignSnapshotResult
import com.gerald.pillagercampaigns.engine.CampaignState as EngineCampaignState
import com.gerald.pillagercampaigns.engine.CampaignPhase as EngineCampaignPhase
import com.gerald.pillagercampaigns.engine.CombatObservation
import com.gerald.pillagercampaigns.engine.EngineFrame
import com.gerald.pillagercampaigns.engine.EngineEffect
import com.gerald.pillagercampaigns.engine.EngineState
import com.gerald.pillagercampaigns.engine.MaterializationResult
import com.gerald.pillagercampaigns.engine.MemberManifest
import com.gerald.pillagercampaigns.engine.MemberSnapshot
import com.gerald.pillagercampaigns.engine.OfficerState
import com.gerald.pillagercampaigns.engine.PlayerFact
import com.gerald.pillagercampaigns.engine.PositionObservation
import com.gerald.pillagercampaigns.engine.RecruitDefinition
import com.gerald.pillagercampaigns.engine.ResourceDefinition
import com.gerald.pillagercampaigns.engine.SelectionMemory
import com.gerald.pillagercampaigns.engine.TerrainObservation
import com.gerald.pillagercampaigns.engine.TransitionResult
import com.gerald.pillagercampaigns.engine.WarbandEngine
import com.gerald.pillagercampaigns.engine.WarbandRules
import com.gerald.pillagercampaigns.engine.WarbandState
import net.minecraft.nbt.CompoundTag
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Minecraft adapter for the authoritative pure engine. Registry inspection and
 * ItemStack construction stay here; economy and selection transitions do not.
 */
object PillagerEngineBridge {
    internal data class LiveRecruit(val mob: Mob, val definition: RecruitDefinition)
    internal data class PlannedLiveMember(
        val manifestId: String,
        val recruitId: String,
        val threat: Double,
        val equipmentIndex: Int?,
        val cargo: Map<String, Int>,
        val healthFraction: Double,
    )
    internal data class PlannedLiveCampaign(
        val members: List<PlannedLiveMember>,
        val route: List<ChunkPosition>,
        val nextSequence: Long,
    )
    internal data class CampaignTransition(val result: TransitionResult, val effects: List<EngineEffect>)

    internal fun rules() = WarbandRules(
        minimumAggression = PillagerCampaignsConfig.minimumAggression.get(),
        maximumAggression = PillagerCampaignsConfig.maximumAggression.get(),
        idleReturnTicks = PillagerCampaignsConfig.idleReturnTicks.get().toLong(),
        materializeDistanceChunks = PillagerCampaignsConfig.materializeDistanceChunks.get(),
        warbandLearningRate = PillagerCampaignsConfig.warbandLearningRate.get(),
        captainLearningRate = PillagerCampaignsConfig.captainLearningRate.get(),
        threatLearningRate = PillagerCampaignsConfig.threatLearningRate.get(),
    )

    fun advanceEconomies(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        data.warbands.values.asSequence().filter { !it.defeated }.forEach { warband ->
            val elapsed = (now - warband.lastEconomyTick).coerceAtLeast(0L)
            if (elapsed == 0L) return@forEach
            warband.lastEconomyTick = now
            val candidates = TinkersArmoryOptimizer.liveEquipmentCandidates(warband, server)
            val recruits = server.getLevel(ResourceKey.create(Registries.DIMENSION, warband.dimension))
                ?.let { level -> PillagerRuntime.recruitDefinitions(level, warband) }.orEmpty()
            val activeThreat = data.campaigns.values.asSequence()
                .filter { it.originWarbandId == warband.id && it.state != CampaignState.RESOLVED }
                .sumOf { it.committedThreat.toDouble() }
            val core = coreWarband(warband).copy(
                capacity = (warband.capacity - warband.garrisonThreat.values.sum() - activeThreat).coerceAtLeast(1.0),
            )
            val state = EngineState(
                tick = now - elapsed,
                sequence = data.engineSequence,
                warbands = linkedMapOf(core.id to core),
            )
            val catalog = EngineCatalog(
                revision = "forge-live",
                recruits = recruits,
                materials = TinkersArmoryOptimizer.materialDefinitions(),
                equipment = candidates.map { it.definition },
                resources = WarbandResourceCatalog.definitions(),
            )
            val result = WarbandEngine.transition(state, EngineFrame(elapsed), catalog, rules())
            data.engineSequence = state.sequence
            warband.reserve = core.reserveThreat.roundToInt().coerceAtLeast(0)
            warband.raidPool = core.raidPool.coerceAtLeast(0.0)
            warband.recruitTickDebt = core.recruitTickDebt
            warband.mobilizationTickDebt = core.mobilizationTickDebt
            warband.extractionTickDebt = core.extractionTickDebt
            warband.materialLedger.clear()
            warband.materialLedger.putAll(core.materialLedger)
            warband.stockpile.clear()
            warband.stockpile.putAll(core.stockpile)
            syncSelectionMemory(warband, core)
            result.events.asSequence().filter { it.type == "manufactured" }.forEach manufactured@{ event ->
                if (warband.armory.size >= warband.capacity) return@manufactured
                candidates.firstOrNull { it.definition.id == event.detail }?.let { candidate ->
                    TinkersArmoryOptimizer.realize(warband, candidate, consume = false)?.let { stack ->
                        warband.armory += stack.save(net.minecraft.nbt.CompoundTag())
                    }
                }
            }
        }
    }

    internal fun chooseRecruit(
        warband: PillagerWarband,
        officerPreferences: Map<String, Double>,
        budget: Double,
        options: List<LiveRecruit>,
        sequence: Long,
    ): LiveRecruit? {
        if (options.isEmpty()) return null
        val core = coreWarband(warband)
        val officer = OfficerState("live-officer", core.factionId, core.id, officerPreferences.toMutableMap())
        val state = EngineState(sequence = sequence.and(Long.MAX_VALUE), warbands = linkedMapOf(core.id to core), officers = linkedMapOf(officer.id to officer))
        val selected = WarbandEngine.chooseRecruit(
            state, core, officer, EngineCatalog("forge-live", options.map { it.definition }), budget, rules = rules(),
        ) ?: return null
        return options.firstOrNull { it.definition.id == selected.id }
    }

    internal fun planSquad(
        warband: PillagerWarband,
        officerPreferences: Map<String, Double>,
        budget: Double,
        recruits: List<RecruitDefinition>,
        armory: List<CompoundTag>,
        sequence: Long,
    ): List<PlannedLiveMember> {
        val core = coreWarband(warband).copy(
            armory = armory.mapIndexedTo(mutableListOf()) { index, tag ->
                TinkersArmoryOptimizer.manifest("forge-armory:$index", ItemStack.of(tag))
            },
        )
        val officer = OfficerState("live-officer", core.factionId, core.id, officerPreferences.toMutableMap())
        val state = EngineState(sequence = sequence.and(Long.MAX_VALUE), warbands = linkedMapOf(core.id to core), officers = linkedMapOf(officer.id to officer))
        return WarbandEngine.planSquad(state, core, officer, EngineCatalog("forge-live", recruits), budget, rules()).members.map { member ->
            PlannedLiveMember(
                member.id, member.recruitId, member.threat,
                member.equipment?.id?.substringAfter("forge-armory:")?.toIntOrNull(), member.cargo.toMap(), member.healthFraction,
            )
        }.also { syncSelectionMemory(warband, core) }
    }

    internal fun planCampaign(
        level: ServerLevel,
        warband: PillagerWarband,
        officerPreferences: Map<String, Double>,
        recruits: List<RecruitDefinition>,
        armory: List<CompoundTag>,
        targetPlayerId: UUID,
        targetChunkX: Int,
        targetChunkZ: Int,
        now: Long,
        sequence: Long,
    ): PlannedLiveCampaign? {
        val core = coreWarband(warband).copy(
            armory = armory.mapIndexedTo(mutableListOf()) { index, tag ->
                TinkersArmoryOptimizer.manifest("forge-armory:$index", ItemStack.of(tag))
            },
        )
        val officer = OfficerState("live-officer", core.factionId, core.id, officerPreferences.toMutableMap())
        val state = EngineState(
            tick = now,
            sequence = sequence.and(Long.MAX_VALUE),
            warbands = linkedMapOf(core.id to core),
            officers = linkedMapOf(officer.id to officer),
        )
        val target = ChunkPosition(level.dimension().location().toString(), targetChunkX, targetChunkZ)
        val catalog = EngineCatalog("forge-live", recruits, resources = WarbandResourceCatalog.definitions())
        WarbandEngine.transition(
            state,
            EngineFrame(
                0L,
                players = listOf(PlayerFact(targetPlayerId.toString(), target, setOf(core.id))),
                terrain = EnvironmentSampler.corridor(level, warband.rallyChunkX, warband.rallyChunkZ, targetChunkX, targetChunkZ),
            ),
            catalog,
            rules(),
        )
        val campaign = state.campaigns.values.singleOrNull() ?: return null
        warband.raidPool = core.raidPool.coerceAtLeast(0.0)
        warband.nextRaidTick = core.nextRaidTick
        warband.stockpile.clear()
        warband.stockpile.putAll(core.stockpile)
        syncSelectionMemory(warband, core)
        return PlannedLiveCampaign(
            campaign.members.map { member ->
                PlannedLiveMember(
                    member.id, member.recruitId, member.threat,
                    member.equipment?.id?.substringAfter("forge-armory:")?.toIntOrNull(),
                    member.cargo.toMap(), member.healthFraction,
                )
            },
            campaign.route.toList(),
            state.sequence,
        )
    }

    fun raidBudget(
        warband: PillagerWarband,
        officerPreferences: Map<String, Double>,
        recruits: List<RecruitDefinition>,
        sequence: Long,
    ): Double {
        val core = coreWarband(warband)
        val officer = OfficerState("live-officer", core.factionId, core.id, officerPreferences.toMutableMap())
        val state = EngineState(sequence = sequence.and(Long.MAX_VALUE), warbands = linkedMapOf(core.id to core), officers = linkedMapOf(officer.id to officer))
        return WarbandEngine.raidBudget(state, core, officer, EngineCatalog("forge-live", recruits), rules())
    }

    /**
     * Projects one persisted live campaign through the same transition used by
     * the empirical runner. Minecraft records are storage/effect sidecars; all
     * lifecycle consequences are copied back from the resulting engine state.
     */
    internal fun transitionCampaign(
        warband: PillagerWarband,
        officer: PillagerOfficer?,
        campaign: PillagerCampaign,
        recruits: List<RecruitDefinition>,
        now: Long,
        elapsed: Long,
        physical: Boolean,
        players: List<PlayerFact> = emptyList(),
        terrain: List<TerrainObservation> = emptyList(),
        combat: List<CombatObservation> = emptyList(),
        materializations: List<MaterializationResult> = emptyList(),
        snapshots: List<CampaignSnapshotResult> = emptyList(),
        outcomes: List<CampaignOutcomeObservation> = emptyList(),
        positions: List<PositionObservation> = emptyList(),
        sequence: Long = campaign.loadoutSeed.and(Long.MAX_VALUE),
        resources: List<ResourceDefinition> = WarbandResourceCatalog.definitions(),
        coreRules: WarbandRules = rules(),
    ): CampaignTransition {
        val equipmentTags = linkedMapOf<String, CompoundTag>()
        val coreMembers = campaign.plannedMembers.mapIndexedTo(mutableListOf()) { index, member ->
            val memberId = member.manifestId.ifBlank { "legacy:${campaign.id}:$index" }
            val equipment = member.equipment?.let { tag ->
                val equipmentId = "$memberId:equipment"
                equipmentTags[equipmentId] = tag.copy()
                TinkersArmoryOptimizer.manifest(equipmentId, ItemStack.of(tag))
            }
            MemberManifest(
                memberId, member.recruitId.toString(), member.threat,
                member.healthFraction, equipment = equipment, cargo = member.cargo.toMutableMap(),
            )
        }
        val coreWarband = coreWarband(warband)
        val coreOfficer = officer?.let {
            OfficerState(
                it.id.toString(), it.factionId.toString(), it.homeWarbandId.toString(),
                it.preferenceGraph.toMutableMap(), it.rank.ordinal + 1,
                it.campaignVictories, it.campaignDefeats, it.injuryOrRecoveryUntilTick, it.lastTargetPlayerId?.toString(),
            )
        }
        val coreCampaign = EngineCampaignState(
            campaign.id.toString(), warband.id.toString(), officer?.id?.toString().orEmpty(), campaign.targetPlayerId.toString(),
            ChunkPosition(warband.dimension.toString(), campaign.currentChunkX, campaign.currentChunkZ),
            ChunkPosition(campaign.targetDimension.toString(), campaign.targetChunkX, campaign.targetChunkZ),
            coreMembers,
            phase = campaign.state.toEnginePhase(),
            physical = physical,
            travelTickDebt = campaign.tickDebt.toLong(),
            lastCombatTick = campaign.lastCombatTick,
            returnReason = campaign.returnReason,
            returnAggressionDelta = campaign.returnAggressionDelta,
            route = campaign.route.mapTo(mutableListOf()) { ChunkPosition(warband.dimension.toString(), it.chunkX, it.chunkZ) },
            routeIndex = campaign.routeIndex,
            supplySatisfaction = campaign.supplySatisfaction,
            deficitExposure = campaign.deficitExposure,
            forageDebt = campaign.forageDebt,
        )
        val state = EngineState(
            tick = (now - elapsed).coerceAtLeast(0L),
            sequence = sequence.and(Long.MAX_VALUE),
            warbands = linkedMapOf(coreWarband.id to coreWarband),
            officers = coreOfficer?.let { linkedMapOf(it.id to it) } ?: linkedMapOf(),
            campaigns = linkedMapOf(coreCampaign.id to coreCampaign),
        )
        val result = WarbandEngine.transition(
            state,
            EngineFrame(
                elapsed, players, combat, materializations, snapshots, outcomes, positions, terrain,
                advanceEconomy = false, allowAutomaticDispatch = false,
            ),
            EngineCatalog("forge-live", recruits, resources = resources),
            coreRules,
        )
        applyCanonicalState(warband, officer, campaign, coreWarband, coreOfficer, coreCampaign, equipmentTags)
        return CampaignTransition(result, result.effects)
    }

    internal fun snapshotResult(campaign: PillagerCampaign): CampaignSnapshotResult = CampaignSnapshotResult(
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
    )

    fun coreWarband(warband: PillagerWarband): WarbandState = WarbandState(
        id = warband.id.toString(),
        factionId = warband.factionId.toString(),
        rally = ChunkPosition(warband.dimension.toString(), warband.rallyChunkX, warband.rallyChunkZ),
        capacity = warband.capacity.toDouble(),
        reserveThreat = warband.reserve.toDouble(),
        raidPool = warband.raidPool,
        garrisonThreat = warband.garrisonThreat.values.sum(),
        aggression = warband.aggression,
        environment = com.gerald.pillagercampaigns.engine.EnvironmentTraits(
            warband.environment.habitability,
            warband.environment.biomass,
            warband.environment.mineralPotential,
            warband.environment.exoticPotential,
            warband.environment.travelFriction,
        ),
        preferences = warband.preferences.toMutableMap(),
        materialLedger = warband.materialLedger.toMutableMap(),
        empiricalThreat = warband.empiricalThreat.toMutableMap(),
        stockpile = warband.stockpile.toMutableMap(),
        selectionMemory = SelectionMemory(
            warband.recruitSelectionMemory.toMutableMap(),
            warband.materialSelectionMemory.toMutableMap(),
            warband.equipmentSelectionMemory.toMutableMap(),
            warband.selectionMemoryLastTick,
        ),
        armory = warband.armory.mapIndexedTo(mutableListOf()) { index, tag ->
            TinkersArmoryOptimizer.manifest("forge-home:$index", ItemStack.of(tag))
        },
        recruitTickDebt = warband.recruitTickDebt,
        mobilizationTickDebt = warband.mobilizationTickDebt,
        extractionTickDebt = warband.extractionTickDebt,
        nextRaidTick = warband.nextRaidTick,
        defeated = warband.defeated,
        activeCampaignLimit = warband.activeCampaignLimit,
    )

    private fun applyCanonicalState(
        warband: PillagerWarband,
        officer: PillagerOfficer?,
        campaign: PillagerCampaign,
        coreWarband: WarbandState,
        coreOfficer: OfficerState?,
        coreCampaign: EngineCampaignState,
        equipmentTags: Map<String, CompoundTag>,
    ) {
        warband.reserve = coreWarband.reserveThreat.roundToInt().coerceAtLeast(0)
        warband.raidPool = coreWarband.raidPool.coerceAtLeast(0.0)
        warband.aggression = coreWarband.aggression
        warband.preferences.clear(); warband.preferences.putAll(coreWarband.preferences)
        warband.empiricalThreat.clear(); warband.empiricalThreat.putAll(coreWarband.empiricalThreat)
        warband.stockpile.clear(); warband.stockpile.putAll(coreWarband.stockpile)
        syncSelectionMemory(warband, coreWarband)
        coreOfficer?.let { canonical -> officer?.let { live ->
            live.preferenceGraph.clear(); live.preferenceGraph.putAll(canonical.preferences)
            live.campaignVictories = canonical.victories
            live.campaignDefeats = canonical.defeats
            live.injuryOrRecoveryUntilTick = canonical.availableAtTick
            live.lastTargetPlayerId = canonical.lastTargetPlayerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        } }
        campaign.currentChunkX = coreCampaign.position.x
        campaign.currentChunkZ = coreCampaign.position.z
        campaign.targetChunkX = coreCampaign.target.x
        campaign.targetChunkZ = coreCampaign.target.z
        campaign.tickDebt = coreCampaign.travelTickDebt.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        campaign.state = coreCampaign.phase.toLivePhase()
        campaign.lastCombatTick = coreCampaign.lastCombatTick
        campaign.returnReason = coreCampaign.returnReason
        campaign.returnAggressionDelta = coreCampaign.returnAggressionDelta
        campaign.route.clear()
        campaign.route += coreCampaign.route.map { CampaignRouteStep(it.x, it.z) }
        campaign.routeIndex = coreCampaign.routeIndex
        campaign.supplySatisfaction = coreCampaign.supplySatisfaction
        campaign.deficitExposure = coreCampaign.deficitExposure
        campaign.forageDebt = coreCampaign.forageDebt
        campaign.plannedMembers.clear()
        coreCampaign.members.forEach { member ->
            campaign.plannedMembers += com.gerald.pillagercampaigns.data.PlannedCampaignMember(
                net.minecraft.resources.ResourceLocation.tryParse(member.recruitId) ?: return@forEach,
                member.threat,
                member.equipment?.let { equipment -> equipmentTags[equipment.id]?.copy()?.also { applyDurability(it, equipment.durabilityFraction) } },
                member.cargo.toMutableMap(), member.id, member.healthFraction,
            )
        }
        coreWarband.armory.mapNotNull { equipment ->
            equipmentTags[equipment.id]?.copy()?.also { applyDurability(it, equipment.durabilityFraction) }
        }.forEach(warband.armory::add)
        coreCampaign.lostCaches.forEach { cache ->
            val stacks = cache.cargo.flatMap { (id, count) -> resourceTags(id, count) }.toMutableList()
            cache.equipment.mapNotNullTo(stacks) { equipment -> equipmentTags[equipment.id]?.copy() }
            if (stacks.isNotEmpty()) campaign.lostAssetCaches += LostAssetCache(cache.position.x, cache.position.z, stacks)
        }
    }

    private fun CampaignState.toEnginePhase(): EngineCampaignPhase = when (this) {
        CampaignState.TRAVELING -> EngineCampaignPhase.OUTBOUND
        CampaignState.READY_TO_MATERIALIZE -> EngineCampaignPhase.READY_TO_MATERIALIZE
        CampaignState.MATERIALIZING -> EngineCampaignPhase.MATERIALIZING
        CampaignState.ACTIVE -> EngineCampaignPhase.ACTIVE
        CampaignState.PAUSED -> EngineCampaignPhase.OUTBOUND
        CampaignState.RETURNING -> EngineCampaignPhase.RETURNING
        CampaignState.RESOLVED -> EngineCampaignPhase.RESOLVED
    }

    private fun EngineCampaignPhase.toLivePhase(): CampaignState = when (this) {
        EngineCampaignPhase.OUTBOUND -> CampaignState.TRAVELING
        EngineCampaignPhase.READY_TO_MATERIALIZE -> CampaignState.READY_TO_MATERIALIZE
        EngineCampaignPhase.MATERIALIZING -> CampaignState.MATERIALIZING
        EngineCampaignPhase.ACTIVE -> CampaignState.ACTIVE
        EngineCampaignPhase.RETURNING -> CampaignState.RETURNING
        EngineCampaignPhase.RESOLVED -> CampaignState.RESOLVED
    }

    private fun applyDurability(tag: CompoundTag, fraction: Double) {
        val stack = ItemStack.of(tag)
        if (!stack.isDamageableItem) return
        stack.damageValue = kotlin.math.ceil(stack.maxDamage * (1.0 - fraction.coerceIn(0.0, 1.0))).toInt()
            .coerceIn(0, stack.maxDamage)
        tag.allKeys.toList().forEach(tag::remove)
        tag.merge(stack.save(CompoundTag()))
    }

    private fun resourceTags(id: String, count: Int): List<CompoundTag> {
        val item = net.minecraft.resources.ResourceLocation.tryParse(id)?.let(net.minecraftforge.registries.ForgeRegistries.ITEMS::getValue)
            ?: return emptyList()
        val maximum = ItemStack(item).maxStackSize.coerceAtLeast(1)
        var remaining = count.coerceAtLeast(0)
        return buildList {
            while (remaining > 0) {
                val amount = minOf(remaining, maximum)
                add(ItemStack(item, amount).save(CompoundTag()))
                remaining -= amount
            }
        }
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
}
