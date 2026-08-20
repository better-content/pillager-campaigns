package com.gerald.warband.core

import kotlinx.serialization.Serializable

@Serializable
data class RecruitDefinition(
    val id: String,
    val baseThreat: Double,
    val capabilities: CapabilityVector,
    val environmentalCost: CapabilityVector = CapabilityVector(),
    val supportedEquipmentActions: Set<String> = emptySet(),
)

@Serializable
data class MaterialDefinition(
    val id: String,
    val tier: Int,
    val extractionCost: Double,
    val capabilities: CapabilityVector = CapabilityVector(),
)

@Serializable
data class EquipmentDefinition(
    val id: String,
    val formulation: List<String>,
    val capabilities: CapabilityVector,
    val cost: Map<String, Double>,
    val actions: Set<String> = emptySet(),
)

@Serializable
data class ResourceVector(
    val sustenance: Double = 0.0,
    val munitions: Double = 0.0,
    val maintenance: Double = 0.0,
    val recovery: Double = 0.0,
) {
    operator fun plus(other: ResourceVector) = ResourceVector(
        sustenance + other.sustenance, munitions + other.munitions,
        maintenance + other.maintenance, recovery + other.recovery,
    )
    operator fun minus(other: ResourceVector) = ResourceVector(
        sustenance - other.sustenance, munitions - other.munitions,
        maintenance - other.maintenance, recovery - other.recovery,
    )
    operator fun times(scale: Double) = ResourceVector(
        sustenance * scale, munitions * scale, maintenance * scale, recovery * scale,
    )
    fun dot(other: ResourceVector) = sustenance * other.sustenance + munitions * other.munitions +
        maintenance * other.maintenance + recovery * other.recovery
    fun sum() = sustenance + munitions + maintenance + recovery
    fun positive() = ResourceVector(
        sustenance.coerceAtLeast(0.0), munitions.coerceAtLeast(0.0),
        maintenance.coerceAtLeast(0.0), recovery.coerceAtLeast(0.0),
    )
    fun finite() = listOf(sustenance, munitions, maintenance, recovery).all(Double::isFinite)
}

@Serializable
data class ResourceDefinition(
    val itemId: String,
    val unitsPerItem: ResourceVector,
    val mass: Double = 1.0,
    val environmentalAffinity: EnvironmentTraits = EnvironmentTraits(),
    val environmentalAvailability: Double = 1.0,
    val maximumStackSize: Int = 64,
)

/** A runtime-supplied reward denomination; Core chooses continuously by value. */
@Serializable
data class RewardDefinition(
    val itemId: String,
    val value: Double,
    val maximumStackSize: Int = 64,
)

@Serializable
data class TerrainObservation(val position: ChunkPosition, val traits: EnvironmentTraits)

@Serializable
data class TacticalPosition(
    val id: String,
    val position: ChunkPosition,
    val pathCost: Double,
    val targetDistance: Double,
    val elevation: Double = 0.0,
    val cover: Double = 0.0,
    val flank: Double = 0.0,
    val nearestAllyDistance: Double = 0.0,
    val reachable: Boolean = true,
    val blockPosition: BlockPosition? = null,
)

@Serializable
data class CoreCatalog(
    val revision: String,
    val recruits: List<RecruitDefinition>,
    val materials: List<MaterialDefinition> = emptyList(),
    val equipment: List<EquipmentDefinition> = emptyList(),
    val environmentSamples: List<EnvironmentTraits> = emptyList(),
    val resources: List<ResourceDefinition> = emptyList(),
    val rewards: List<RewardDefinition> = emptyList(),
    val equipmentPlatforms: List<EquipmentPlatformDefinition> = emptyList(),
)

@Serializable
data class PlayerFact(
    val id: String,
    val position: ChunkPosition,
    val eligible: Boolean = true,
    val gameModeEligible: Boolean = true,
    val physicallyAvailable: Boolean = true,
)

@Serializable enum class PlayerLifecycleKind { JOINED, RESPAWNED, DIED }

@Serializable
data class PlayerLifecycleObservation(val playerId: String, val kind: PlayerLifecycleKind)

@Serializable
data class CombatObservation(
    val campaignId: String,
    val campaignDamage: Double,
    val playerDamage: Double,
    val effectiveRange: Double,
    val routeConfidence: Double,
    val cohesion: Double,
    val casualties: Set<String> = emptySet(),
    val applyHealthDamage: Boolean = true,
)

@Serializable
data class MaterializationResult(
    val campaignId: String,
    val success: Boolean,
    val physicalMemberIds: Set<String> = emptySet(),
    val effectId: String,
    val attemptedMemberIds: Set<String> = emptySet(),
)

/**
 * Runtime-neutral result of capturing a physical campaign member. Adapters
 * translate their native entity/item representation into the canonical
 * manifest before acknowledging dematerialization.
 */
@Serializable
data class MemberSnapshot(
    val memberId: String,
    val healthFraction: Double,
    val experience: Double = 0.0,
    val equipment: EquipmentManifest? = null,
    val cargo: Map<String, Int> = emptyMap(),
)

@Serializable
data class CampaignSnapshotResult(
    val campaignId: String,
    val position: ChunkPosition,
    val members: List<MemberSnapshot>,
    val effectId: String? = null,
)

@Serializable
data class WarbandDiscoveryObservation(
    val siteId: String,
    val rally: ChunkPosition,
    val environment: EnvironmentTraits,
    val cellX: Int? = null,
    val cellZ: Int? = null,
    val worldSeed: Long = 0L,
    val siteCandidates: List<BlockPosition> = emptyList(),
    /** Eligible player whose local pressure coverage this candidate may guarantee. */
    val coveragePlayerId: String? = null,
)

@Serializable
data class MaterializationSiteObservation(
    val campaignId: String,
    val candidates: List<BlockPosition>,
)

@Serializable
data class TerritoryContactObservation(
    val warbandId: String,
    val playerId: String,
    val distanceChunks: Double,
    val attacked: Boolean = false,
)

@Serializable
data class GarrisonResult(
    val garrisonId: String,
    val success: Boolean,
    val physicalMemberIds: Set<String> = emptySet(),
    val effectId: String,
)

@Serializable
data class GarrisonSnapshotResult(
    val garrisonId: String,
    val members: List<MemberSnapshot>,
    val effectId: String? = null,
)

@Serializable
data class DefeatObservation(
    val campaignId: String,
    val memberId: String,
    val playerId: String,
    val authority: Double = 0.0,
)

@Serializable
data class WarlordDefeatObservation(
    val warbandId: String,
    val memberId: String,
    val playerId: String? = null,
)

@Serializable
data class TacticalObservation(
    val campaignId: String,
    val positions: List<TacticalPosition>,
    val cohesionRadius: Double = 24.0,
)

@Serializable
data class EffectAcknowledgement(
    val effectId: String,
    val successful: Boolean = true,
    val detail: String = "",
)

@Serializable
data class EquipmentRealizationResult(
    val effectId: String,
    val equipmentId: String,
    val successful: Boolean,
    val measuredCapabilities: CapabilityVector? = null,
    val detail: String = "",
)

@Serializable enum class NavigationStatus { ACCEPTED, FAILED, UNREACHABLE, STALE }

@Serializable
data class NavigationResult(
    val effectId: String,
    val campaignId: String,
    val memberId: String,
    val status: NavigationStatus,
    val detail: String = "",
)

@Serializable
enum class CampaignOutcomeKind { CAPTAIN_VICTORY, SURVIVING_DEFEAT, CAPTAIN_KILLED, WARBAND_COLLAPSE, ABORTED }

/** Outcome facts originate in a physical adapter; their consequences do not. */
@Serializable
data class CampaignOutcomeObservation(
    val campaignId: String,
    val outcome: CampaignOutcomeKind,
    val reason: String,
)

@Serializable
data class PositionObservation(val campaignId: String, val position: ChunkPosition)

@Serializable
data class CoreFrame(
    val elapsedTicks: Long,
    val players: List<PlayerFact> = emptyList(),
    val playerLifecycle: List<PlayerLifecycleObservation> = emptyList(),
    val combat: List<CombatObservation> = emptyList(),
    val materializations: List<MaterializationResult> = emptyList(),
    val snapshots: List<CampaignSnapshotResult> = emptyList(),
    val outcomes: List<CampaignOutcomeObservation> = emptyList(),
    val physicalPositions: List<PositionObservation> = emptyList(),
    val terrain: List<TerrainObservation> = emptyList(),
    val discoveries: List<WarbandDiscoveryObservation> = emptyList(),
    val territoryContacts: List<TerritoryContactObservation> = emptyList(),
    val garrisonResults: List<GarrisonResult> = emptyList(),
    val garrisonSnapshots: List<GarrisonSnapshotResult> = emptyList(),
    val defeats: List<DefeatObservation> = emptyList(),
    val warlordDefeats: List<WarlordDefeatObservation> = emptyList(),
    val tactical: List<TacticalObservation> = emptyList(),
    val materializationSites: List<MaterializationSiteObservation> = emptyList(),
    val acknowledgements: List<EffectAcknowledgement> = emptyList(),
    val equipmentRealizations: List<EquipmentRealizationResult> = emptyList(),
    val navigationResults: List<NavigationResult> = emptyList(),
    val commands: List<CoreCommand> = emptyList(),
)

@Serializable
sealed interface CoreCommand {
    @Serializable data class Manufacture(val warbandId: String, val count: Int = 1) : CoreCommand
    @Serializable data class CollapseWarband(val warbandId: String, val reason: String) : CoreCommand
    @Serializable data class CollapseFaction(val factionId: String, val reason: String) : CoreCommand
    @Serializable data class RegisterPlayer(val playerId: String) : CoreCommand
    @Serializable data class ProtectPlayer(val playerId: String, val untilTick: Long) : CoreCommand
    @Serializable data object ResetWorld : CoreCommand
}

@Serializable internal data class BeginReturnCommand(val campaignId: String, val reason: String, val aggressionDelta: Int = 0) : CoreCommand
@Serializable internal data class DematerializeCommand(val campaignId: String) : CoreCommand
@Serializable internal data class ReserveGarrisonCommand(
    val warbandId: String,
    val position: ChunkPosition,
    val desiredThreat: Double? = null,
    val blockPosition: BlockPosition? = null,
) : CoreCommand
@Serializable internal data class ResolveGarrisonCommand(val garrisonId: String, val survivingMemberIds: Set<String> = emptySet()) : CoreCommand
@Serializable internal data class PromoteSuccessorCommand(val warbandId: String, val fallenOfficerId: String? = null) : CoreCommand
@Serializable internal data class SelectCampaignSuccessorCommand(
    val campaignId: String,
    val excludedMemberIds: Set<String> = emptySet(),
) : CoreCommand
@Serializable internal data class DelayWarbandCommand(val warbandId: String, val untilTick: Long) : CoreCommand
@Serializable internal data class ResolveCampaignCommand(val campaignId: String, val reason: String) : CoreCommand
@Serializable internal data class RecordSchedulerProgressCommand(
    val discoveryTick: Long? = null,
    val campaignTick: Long? = null,
) : CoreCommand

@Serializable enum class EffectKind {
    MATERIALIZE, PROBE_ROUTE, CAPTURE_SNAPSHOTS, RESTORE_SNAPSHOTS, WARN_PLAYER, REWARD_PLAYER,
    MATERIALIZE_GARRISON, MATERIALIZE_WARLORD, NAVIGATE, PROMOTE_SUCCESSOR, REALIZE_EQUIPMENT,
    REMOVE_ENTITIES, DEMATERIALIZE,
}

@Serializable
data class CoreEffect(
    val kind: EffectKind,
    val warbandId: String? = null,
    val campaignId: String? = null,
    val playerId: String? = null,
    val position: ChunkPosition? = null,
    val memberIds: List<String> = emptyList(),
    val garrisonId: String? = null,
    val tacticalPositionId: String? = null,
    val itemId: String? = null,
    val count: Int = 0,
    val effectId: String = "",
    val equipmentManifest: EquipmentManifest? = null,
    val memberManifest: MemberManifest? = null,
    val memberManifests: List<MemberManifest> = emptyList(),
    val blockPosition: BlockPosition? = null,
    val memberPlacements: List<MemberPlacement> = emptyList(),
)

@Serializable data class MemberPlacement(val memberId: String, val position: BlockPosition)

@Serializable
data class CoreEvent(val tick: Long, val type: String, val subjectId: String, val detail: String = "")

@Serializable
internal data class CoreTransition(
    val state: CoreSnapshot,
    val events: List<CoreEvent>,
    val effects: List<CoreEffect>,
)
