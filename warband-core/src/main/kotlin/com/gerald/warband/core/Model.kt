package com.gerald.warband.core

import kotlinx.serialization.Serializable

@Serializable
data class CapabilityVector(
    val durability: Double = 0.0,
    val damage: Double = 0.0,
    val mobility: Double = 0.0,
    val range: Double = 0.0,
    val control: Double = 0.0,
) {
    operator fun plus(other: CapabilityVector) = CapabilityVector(
        durability + other.durability,
        damage + other.damage,
        mobility + other.mobility,
        range + other.range,
        control + other.control,
    )

    operator fun times(scale: Double) = CapabilityVector(
        durability * scale, damage * scale, mobility * scale, range * scale, control * scale,
    )

    fun dot(other: CapabilityVector): Double =
        durability * other.durability + damage * other.damage + mobility * other.mobility +
            range * other.range + control * other.control

    fun finite() = listOf(durability, damage, mobility, range, control).all(Double::isFinite)
}

@Serializable
data class EnvironmentTraits(
    val habitability: Double = 0.5,
    val biomass: Double = 0.5,
    val mineralPotential: Double = 0.5,
    val exoticPotential: Double = 0.5,
    val travelFriction: Double = 0.5,
) {
    fun bounded() = EnvironmentTraits(
        habitability.coerceIn(0.0, 1.0), biomass.coerceIn(0.0, 1.0),
        mineralPotential.coerceIn(0.0, 1.0), exoticPotential.coerceIn(0.0, 1.0),
        travelFriction.coerceIn(0.0, 1.0),
    )
}

@Serializable data class ChunkPosition(val dimension: String, val x: Int, val z: Int)
@Serializable data class BlockPosition(val dimension: String, val x: Int, val y: Int, val z: Int)

@Serializable
data class EquipmentManifest(
    val id: String,
    val definitionId: String,
    val formulation: List<String>,
    val billOfMaterials: Map<String, Double>,
    val capabilities: CapabilityVector,
    val supportedActions: Set<String> = emptySet(),
    var durabilityFraction: Double = 1.0,
)

@Serializable
data class MemberManifest(
    val id: String,
    val recruitId: String,
    val threat: Double,
    var healthFraction: Double = 1.0,
    var experience: Double = 0.0,
    var equipment: EquipmentManifest? = null,
    val cargo: MutableMap<String, Int> = linkedMapOf(),
)

@Serializable
data class SelectionMemory(
    val recruits: MutableMap<String, Double> = linkedMapOf(),
    val materials: MutableMap<String, Double> = linkedMapOf(),
    val equipment: MutableMap<String, Double> = linkedMapOf(),
    var lastDecayTick: Long = 0L,
)

@Serializable
data class LostCache(
    val id: String,
    val position: ChunkPosition,
    val cargo: MutableMap<String, Int> = linkedMapOf(),
    val equipment: MutableList<EquipmentManifest> = mutableListOf(),
)

@Serializable
data class SquadPlan(
    val members: List<MemberManifest>,
    val committedThreat: Double,
)

@Serializable
data class WarbandState(
    val id: String,
    val factionId: String,
    var rally: ChunkPosition,
    var capacity: Double,
    var reserveThreat: Double,
    var raidPool: Double = 0.0,
    var garrisonThreat: Double = 0.0,
    var aggression: Int = 6,
    var environment: EnvironmentTraits = EnvironmentTraits(),
    val preferences: MutableMap<String, Double> = linkedMapOf(),
    val materialLedger: MutableMap<String, Double> = linkedMapOf(),
    val armory: MutableList<EquipmentManifest> = mutableListOf(),
    val empiricalThreat: MutableMap<String, Double> = linkedMapOf(),
    val stockpile: MutableMap<String, Int> = linkedMapOf(),
    val selectionMemory: SelectionMemory = SelectionMemory(),
    var recruitTickDebt: Double = 0.0,
    var mobilizationTickDebt: Double = 0.0,
    var extractionTickDebt: Double = 0.0,
    var nextRaidTick: Long = 0L,
    var defeated: Boolean = false,
    var activeCampaignLimit: Int = 1,
    var warlord: MemberManifest? = null,
)

@Serializable
data class OfficerState(
    val id: String,
    val factionId: String,
    var homeWarbandId: String,
    val preferences: MutableMap<String, Double> = linkedMapOf(),
    var rank: Int = 1,
    var victories: Int = 0,
    var defeats: Int = 0,
    var availableAtTick: Long = 0L,
    var lastTargetPlayerId: String? = null,
    var deployedCampaignId: String? = null,
)

@Serializable data class DispatchAssignment(val officerId: String, val playerId: String, val score: Int)

@Serializable enum class CampaignPhase { OUTBOUND, READY_TO_MATERIALIZE, MATERIALIZING, ACTIVE, RETURNING, RESOLVED }

@Serializable
data class CampaignState(
    val id: String,
    val warbandId: String,
    val officerId: String,
    val targetPlayerId: String,
    var position: ChunkPosition,
    var target: ChunkPosition,
    val members: MutableList<MemberManifest>,
    var phase: CampaignPhase = CampaignPhase.OUTBOUND,
    var physical: Boolean = false,
    var travelTickDebt: Long = 0L,
    var lastCombatTick: Long = 0L,
    var returnReason: String? = null,
    var returnAggressionDelta: Int = 0,
    val route: MutableList<ChunkPosition> = mutableListOf(),
    var routeIndex: Int = 0,
    var supplySatisfaction: Double = 1.0,
    var deficitExposure: Double = 0.0,
    var forageDebt: Double = 0.0,
    val lostCaches: MutableList<LostCache> = mutableListOf(),
    val physicalMemberIds: MutableSet<String> = linkedSetOf(),
    var leaderMemberId: String? = null,
    var resolvedAtTick: Long = 0L,
)

@Serializable
data class FactionState(val id: String, var name: String, var bannerSeed: Int)

@Serializable
enum class TerritoryStatus { UNCONTACTED, WARNED, HOSTILE }

@Serializable
data class TerritoryRelationState(
    val warbandId: String,
    val playerId: String,
    var status: TerritoryStatus,
    var protectedUntilTick: Long = 0L,
) {
    val hostile: Boolean get() = status == TerritoryStatus.HOSTILE
}

@Serializable enum class GarrisonPhase { RESERVED, ACTIVE, RESOLVED }

@Serializable
data class GarrisonState(
    val id: String,
    val warbandId: String,
    val position: ChunkPosition,
    val members: MutableList<MemberManifest>,
    var phase: GarrisonPhase = GarrisonPhase.RESERVED,
    val physicalMemberIds: MutableSet<String> = linkedSetOf(),
)

@Serializable
data class CoreSnapshot(
    var tick: Long = 0L,
    var sequence: Long = 0L,
    var effectSequence: Long = 0L,
    var lastDiscoveryTick: Long = 0L,
    var lastCampaignTick: Long = 0L,
    var dispatchCursor: Int = 0,
    var discoveryCursor: Int = 0,
    val factions: MutableMap<String, FactionState> = linkedMapOf(),
    val warbands: MutableMap<String, WarbandState> = linkedMapOf(),
    val officers: MutableMap<String, OfficerState> = linkedMapOf(),
    val campaigns: MutableMap<String, CampaignState> = linkedMapOf(),
    val protectedPlayersUntilTick: MutableMap<String, Long> = linkedMapOf(),
    val terrain: MutableMap<String, TerrainObservation> = linkedMapOf(),
    val initializedPlayerIds: MutableSet<String> = linkedSetOf(),
    val discoveredSiteIds: MutableSet<String> = linkedSetOf(),
    val territoryRelations: MutableMap<String, TerritoryRelationState> = linkedMapOf(),
    val garrisons: MutableMap<String, GarrisonState> = linkedMapOf(),
    val pendingEffects: MutableMap<String, CoreEffect> = linkedMapOf(),
    val acknowledgedEffectIds: MutableSet<String> = linkedSetOf(),
    val rewardedDefeatIds: MutableSet<String> = linkedSetOf(),
    val defeatedWarlordIds: MutableSet<String> = linkedSetOf(),
)
