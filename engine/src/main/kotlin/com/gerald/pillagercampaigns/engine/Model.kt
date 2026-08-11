package com.gerald.pillagercampaigns.engine

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

@Serializable
data class EquipmentManifest(
    val id: String,
    val definitionId: String,
    val formulation: List<String>,
    val billOfMaterials: Map<String, Double>,
    val capabilities: CapabilityVector,
    val supportedActions: Set<String> = emptySet(),
)

@Serializable
data class MemberManifest(
    val id: String,
    val recruitId: String,
    val threat: Double,
    var healthFraction: Double = 1.0,
    var experience: Double = 0.0,
    var equipment: EquipmentManifest? = null,
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
    var recruitTickDebt: Double = 0.0,
    var mobilizationTickDebt: Double = 0.0,
    var extractionTickDebt: Double = 0.0,
    var nextRaidTick: Long = 0L,
    var defeated: Boolean = false,
    var activeCampaignLimit: Int = 1,
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
)

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
)

@Serializable
data class FactionState(val id: String, var name: String, var bannerSeed: Int)

@Serializable
data class EngineState(
    var tick: Long = 0L,
    var sequence: Long = 0L,
    val factions: MutableMap<String, FactionState> = linkedMapOf(),
    val warbands: MutableMap<String, WarbandState> = linkedMapOf(),
    val officers: MutableMap<String, OfficerState> = linkedMapOf(),
    val campaigns: MutableMap<String, CampaignState> = linkedMapOf(),
    val protectedPlayersUntilTick: MutableMap<String, Long> = linkedMapOf(),
)
