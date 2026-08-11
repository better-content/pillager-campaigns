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
import com.gerald.warband.core.CoreCatalog
import com.gerald.warband.core.CampaignSnapshotResult
import com.gerald.warband.core.CampaignState as CoreCampaignState
import com.gerald.warband.core.CampaignPhase as CoreCampaignPhase
import com.gerald.warband.core.CoreFrame
import com.gerald.warband.core.CoreCommand
import com.gerald.warband.core.CoreSnapshot
import com.gerald.warband.core.MemberManifest
import com.gerald.warband.core.MemberSnapshot
import com.gerald.warband.core.OfficerState
import com.gerald.warband.core.RecruitDefinition
import com.gerald.warband.core.SelectionMemory
import com.gerald.warband.core.CoreTransition
import com.gerald.warband.core.WarbandCore
import com.gerald.warband.core.CoreRules
import com.gerald.warband.core.WarbandState
import com.gerald.warband.core.RewardDefinition
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
 * Minecraft adapter for the authoritative Warband Core. Registry inspection and
 * ItemStack construction stay here; economy and selection transitions do not.
 */
object WarbandCoreAdapter {
    const val LIVE_CATALOG_REVISION = "pillager-campaigns-live-v1"
    private const val CORE_EQUIPMENT_ID_TAG = "PillagerCoreEquipmentId"
    internal data class LiveRecruit(val mob: Mob, val definition: RecruitDefinition)
    internal data class PlannedLiveMember(
        val manifestId: String,
        val recruitId: String,
        val threat: Double,
        val equipmentIndex: Int?,
        val cargo: Map<String, Int>,
        val healthFraction: Double,
    )

    internal fun rules() = CoreRules(
        minimumAggression = PillagerCampaignsConfig.minimumAggression.get(),
        maximumAggression = PillagerCampaignsConfig.maximumAggression.get(),
        initialAggression = PillagerCampaignsConfig.initialAggression.get(),
        idleReturnTicks = PillagerCampaignsConfig.idleReturnTicks.get().toLong(),
        materializeDistanceChunks = PillagerCampaignsConfig.materializeDistanceChunks.get(),
        warbandLearningRate = PillagerCampaignsConfig.warbandLearningRate.get(),
        captainLearningRate = PillagerCampaignsConfig.captainLearningRate.get(),
        threatLearningRate = PillagerCampaignsConfig.threatLearningRate.get(),
    )

    /**
     * The only Forge entry point allowed to advance the persisted strategic
     * state. Native objects may supply observations and execute returned
     * effects, but they never own a second strategic transition.
     */
    fun transition(
        data: PillagerWorldData,
        frame: CoreFrame,
        catalog: CoreCatalog,
        coreRules: CoreRules = rules(),
    ): CoreTransition {
        require(catalog.revision.isNotBlank()) { "Warband Core catalog revision must not be blank" }
        if (data.coreCatalogRevision == PillagerWorldData.UNRESOLVED_CATALOG_REVISION) {
            data.coreCatalogRevision = catalog.revision
        } else {
            require(data.coreCatalogRevision == catalog.revision) {
                "Warband Core catalog mismatch: save=${data.coreCatalogRevision}, runtime=${catalog.revision}"
            }
        }
        val result = WarbandCore.transition(data.coreState, frame, catalog, coreRules)
        if (frame.elapsedTicks > 0L || result.events.isNotEmpty() || result.effects.isNotEmpty()) data.markChanged()
        return result
    }

    fun registerPlayer(data: PillagerWorldData, playerId: UUID): Boolean {
        val id = playerId.toString()
        if (id in data.coreState.initializedPlayerIds) return false
        transition(data, CoreFrame(0L, commands = listOf(CoreCommand.RegisterPlayer(id))), snapshotCatalog(data))
        return true
    }

    fun protectPlayer(data: PillagerWorldData, playerId: UUID, untilTick: Long) {
        transition(
            data,
            CoreFrame(0L, commands = listOf(CoreCommand.ProtectPlayer(playerId.toString(), untilTick))),
            snapshotCatalog(data),
        )
    }

    fun recordSchedulerProgress(data: PillagerWorldData, discoveryTick: Long? = null, campaignTick: Long? = null) {
        transition(
            data,
            CoreFrame(0L, commands = listOf(CoreCommand.RecordSchedulerProgress(discoveryTick, campaignTick))),
            snapshotCatalog(data),
        )
    }

    fun resetWorld(data: PillagerWorldData) {
        transition(data, CoreFrame(0L, commands = listOf(CoreCommand.ResetWorld)), snapshotCatalog(data))
        synchronizeNativeViews(data)
    }

    /** Rebuilds Minecraft-only views from the persisted canonical snapshot. */
    fun synchronizeNativeViews(data: PillagerWorldData) {
        data.coreState.factions.values.forEach { core ->
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
        data.coreState.officers.values.forEach { core ->
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
                        core.availableAtTick > data.coreState.tick -> LiveOfficerState.RECOVERING
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
                    core.availableAtTick > data.coreState.tick -> LiveOfficerState.RECOVERING
                    else -> LiveOfficerState.IDLE
                }
            }
        }
        data.coreState.warbands.values.forEach { core ->
            val id = core.id.asUuidOrNull() ?: return@forEach
            val factionId = core.factionId.asUuidOrNull() ?: return@forEach
            val dimension = net.minecraft.resources.ResourceLocation.tryParse(core.rally.dimension) ?: return@forEach
            val officerId = data.coreState.officers.values.firstOrNull { it.homeWarbandId == core.id }?.id?.asUuidOrNull() ?: return@forEach
            val existing = data.warbands[id]
            val live = existing ?: PillagerWarband(
                id, factionId, dimension, data.coreState.factions[core.factionId]?.bannerSeed ?: id.hashCode(),
                core.rally.x, core.rally.z, core.reserveThreat.roundToInt(), core.capacity.roundToInt(),
                defeated = core.defeated, warlordOfficerId = officerId, warlordEntityId = null,
                nextRaidTick = core.nextRaidTick, cooldownUntilTick = 0L, lastIntelTick = data.coreState.tick,
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
            data.coreState.territoryRelations.values.filter { it.warbandId == core.id }.forEach { relation ->
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
            data.coreState.garrisons.values.asSequence()
                .filter { it.warbandId == core.id && it.phase == com.gerald.warband.core.GarrisonPhase.ACTIVE }
                .flatMap { it.members.asSequence() }
                .forEach { member -> data.minecraftSidecar.entityIds[member.id]?.let { live.garrisonThreat[it] = member.threat } }
        }
        data.coreState.campaigns.values.forEach { core -> synchronizeCampaignView(data, core) }
        val canonicalCampaignIds = data.coreState.campaigns.keys.mapNotNullTo(hashSetOf()) { it.asUuidOrNull() }
        data.campaigns.keys.retainAll(canonicalCampaignIds)
    }

    fun liveCatalog(server: MinecraftServer, data: PillagerWorldData): CoreCatalog {
        synchronizeNativeViews(data)
        val recruits = data.warbands.values.flatMap { warband ->
            server.getLevel(ResourceKey.create(Registries.DIMENSION, warband.dimension))
                ?.let { PillagerRuntime.recruitDefinitions(it, warband) }.orEmpty()
        }.distinctBy(RecruitDefinition::id)
        val equipment = data.warbands.values.flatMap { TinkersArmoryOptimizer.liveEquipmentCandidates(it, server) }
            .map { it.definition }.distinctBy { it.id }
        val rewardIds = listOf(
            "createdeco:copper_coin", "createdeco:zinc_coin", "createdeco:iron_coin",
            "createdeco:industrial_iron_coin", "createdeco:brass_coin", "createdeco:gold_coin", "createdeco:netherite_coin",
        )
        val rewards = rewardIds.mapIndexedNotNull { index, id ->
            val location = net.minecraft.resources.ResourceLocation.tryParse(id) ?: return@mapIndexedNotNull null
            val item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(location) ?: return@mapIndexedNotNull null
            RewardDefinition(id, (1 shl index).toDouble(), ItemStack(item).maxStackSize)
        }
        return CoreCatalog(
            LIVE_CATALOG_REVISION,
            recruits,
            TinkersArmoryOptimizer.materialDefinitions(),
            equipment,
            resources = WarbandResourceCatalog.definitions(),
            rewards = rewards,
        )
    }

    fun snapshotCatalog(data: PillagerWorldData): CoreCatalog {
        val recruits = (data.coreState.campaigns.values.flatMap { it.members } +
            data.coreState.garrisons.values.flatMap { it.members })
            .distinctBy(MemberManifest::recruitId)
            .map { RecruitDefinition(it.recruitId, it.threat.coerceAtLeast(1.0), CapabilityVector()) }
        return CoreCatalog(LIVE_CATALOG_REVISION, recruits)
    }

    fun advanceCanonical(server: MinecraftServer, data: PillagerWorldData, now: Long, observations: CoreFrame): CoreTransition {
        val elapsed = (now - data.coreState.tick).coerceAtLeast(0L)
        val catalog = liveCatalog(server, data)
        val result = transition(
            data,
            observations.copy(elapsedTicks = elapsed, advanceEconomy = true, allowAutomaticDispatch = false),
            catalog,
        )
        result.events.filter { it.type == "manufactured" }.forEach { event ->
            val owner = data.coreState.warbands.values.firstOrNull { warband -> warband.armory.any { it.id == event.subjectId } }
                ?: return@forEach
            val native = owner.id.asUuidOrNull()?.let(data.warbands::get) ?: return@forEach
            val candidate = TinkersArmoryOptimizer.liveEquipmentCandidates(native, server)
                .firstOrNull { it.definition.id == event.detail } ?: return@forEach
            val stack = TinkersArmoryOptimizer.realize(candidate)
            data.minecraftSidecar.itemSnapshots[event.subjectId] = mutableListOf(
                stack.save(CompoundTag()).also { it.putString(CORE_EQUIPMENT_ID_TAG, event.subjectId) },
            )
        }
        synchronizeNativeViews(data)
        return result
    }

    fun manufacture(server: MinecraftServer, data: PillagerWorldData, warbandId: UUID, count: Int = 1): CoreTransition {
        val catalog = liveCatalog(server, data)
        val result = transition(
            data,
            CoreFrame(0L, commands = listOf(CoreCommand.Manufacture(warbandId.toString(), count))),
            catalog,
        )
        result.events.filter { it.type == "manufactured" }.forEach { event ->
            val native = data.warbands[warbandId] ?: return@forEach
            val candidate = TinkersArmoryOptimizer.liveEquipmentCandidates(native, server)
                .firstOrNull { it.definition.id == event.detail } ?: return@forEach
            val stack = TinkersArmoryOptimizer.realize(candidate)
            data.minecraftSidecar.itemSnapshots[event.subjectId] = mutableListOf(
                stack.save(CompoundTag()).also { it.putString(CORE_EQUIPMENT_ID_TAG, event.subjectId) },
            )
        }
        synchronizeNativeViews(data)
        return result
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
        val state = CoreSnapshot(sequence = sequence.and(Long.MAX_VALUE), warbands = linkedMapOf(core.id to core), officers = linkedMapOf(officer.id to officer))
        val selected = WarbandCore.chooseRecruit(
            state, core, officer, CoreCatalog("forge-live", options.map { it.definition }), budget, rules = rules(),
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
        val state = CoreSnapshot(sequence = sequence.and(Long.MAX_VALUE), warbands = linkedMapOf(core.id to core), officers = linkedMapOf(officer.id to officer))
        return WarbandCore.planSquad(state, core, officer, CoreCatalog("forge-live", recruits), budget, rules()).members.map { member ->
            PlannedLiveMember(
                member.id, member.recruitId, member.threat,
                member.equipment?.id?.substringAfter("forge-armory:")?.toIntOrNull(), member.cargo.toMap(), member.healthFraction,
            )
        }.also { syncSelectionMemory(warband, core) }
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
            kotlin.math.ceil(core.members.sumOf(MemberManifest::threat)).toInt(), data.coreState.sequence,
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

    fun coreWarband(warband: PillagerWarband): WarbandState = WarbandState(
        id = warband.id.toString(),
        factionId = warband.factionId.toString(),
        rally = ChunkPosition(warband.dimension.toString(), warband.rallyChunkX, warband.rallyChunkZ),
        capacity = warband.capacity.toDouble(),
        reserveThreat = warband.reserve.toDouble(),
        raidPool = warband.raidPool,
        garrisonThreat = warband.garrisonThreat.values.sum(),
        aggression = warband.aggression,
        environment = com.gerald.warband.core.EnvironmentTraits(
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
            TinkersArmoryOptimizer.manifest(
                tag.getString(CORE_EQUIPMENT_ID_TAG).ifBlank { "forge-home:$index" }, ItemStack.of(tag),
            )
        },
        recruitTickDebt = warband.recruitTickDebt,
        mobilizationTickDebt = warband.mobilizationTickDebt,
        extractionTickDebt = warband.extractionTickDebt,
        nextRaidTick = warband.nextRaidTick,
        defeated = warband.defeated,
        activeCampaignLimit = warband.activeCampaignLimit,
    )

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
