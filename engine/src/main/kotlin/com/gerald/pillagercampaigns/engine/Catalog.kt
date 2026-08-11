package com.gerald.pillagercampaigns.engine

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
data class EngineCatalog(
    val revision: String,
    val recruits: List<RecruitDefinition>,
    val materials: List<MaterialDefinition> = emptyList(),
    val equipment: List<EquipmentDefinition> = emptyList(),
    val environmentSamples: List<EnvironmentTraits> = emptyList(),
)

@Serializable
data class PlayerFact(
    val id: String,
    val position: ChunkPosition,
    val hostileWarbands: Set<String> = emptySet(),
    val eligible: Boolean = true,
    val protected: Boolean = false,
)

@Serializable
data class CombatObservation(
    val campaignId: String,
    val campaignDamage: Double,
    val playerDamage: Double,
    val effectiveRange: Double,
    val routeConfidence: Double,
    val cohesion: Double,
    val casualties: Set<String> = emptySet(),
)

@Serializable
data class MaterializationResult(
    val campaignId: String,
    val success: Boolean,
    val physicalMemberIds: Set<String> = emptySet(),
)

@Serializable
data class PositionObservation(val campaignId: String, val position: ChunkPosition)

@Serializable
data class EngineFrame(
    val elapsedTicks: Long,
    val players: List<PlayerFact> = emptyList(),
    val combat: List<CombatObservation> = emptyList(),
    val materializations: List<MaterializationResult> = emptyList(),
    val physicalPositions: List<PositionObservation> = emptyList(),
    val commands: List<EngineCommand> = emptyList(),
)

@Serializable
sealed interface EngineCommand {
    @Serializable data class Dispatch(val warbandId: String, val playerId: String) : EngineCommand
    @Serializable data class BeginReturn(val campaignId: String, val reason: String, val aggressionDelta: Int = 0) : EngineCommand
    @Serializable data class Dematerialize(val campaignId: String) : EngineCommand
    @Serializable data class Manufacture(val warbandId: String, val count: Int = 1) : EngineCommand
}

@Serializable enum class EffectKind { MATERIALIZE, PROBE_ROUTE, CAPTURE_SNAPSHOTS, RESTORE_SNAPSHOTS, WARN_PLAYER, REWARD_PLAYER }

@Serializable
data class EngineEffect(
    val kind: EffectKind,
    val warbandId: String? = null,
    val campaignId: String? = null,
    val playerId: String? = null,
    val position: ChunkPosition? = null,
    val memberIds: List<String> = emptyList(),
)

@Serializable
data class EngineEvent(val tick: Long, val type: String, val subjectId: String, val detail: String = "")

@Serializable
data class TransitionResult(
    val state: EngineState,
    val events: List<EngineEvent>,
    val effects: List<EngineEffect>,
)
