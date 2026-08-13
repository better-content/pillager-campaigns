package com.bettercontent.pillagercampaigns.runner

import com.gerald.warband.core.*
import kotlinx.serialization.Serializable

/** Machine-readable scope carried by every runner result and trace. */
@Serializable
data class ExperimentBoundary(
    val model: String = "warband-core",
    val minecraftSimulation: Boolean = false,
    val externalObservationsAreSynthetic: Boolean = true,
    val statement: String = NOT_MINECRAFT_SIMULATION,
)

const val NOT_MINECRAFT_SIMULATION =
    "Warband Core scenario only; does not simulate Minecraft entities, combat, pathfinding, world generation, or players."

/** Synthetic facts supplied to Core by an experiment; these are not Minecraft measurements or predictions. */
@Serializable
data class BoundedAssumptions(
    val routeConfidence: Double = 0.6,
    val cohesion: Double = 0.7,
    val campaignDamagePerEngagement: Double = 4.0,
    val playerDamagePerEngagement: Double = 5.0,
    val effectiveRange: Double = 8.0,
    val engagementEveryTicks: Long = 1_200L,
    val engagementsBeforeDisengage: Int? = null,
) {
    fun validate() {
        require(routeConfidence in 0.0..1.0 && cohesion in 0.0..1.0)
        require(campaignDamagePerEngagement >= 0.0 && playerDamagePerEngagement >= 0.0)
        require(effectiveRange >= 0.0 && engagementEveryTicks > 0L)
        require(engagementsBeforeDisengage == null || engagementsBeforeDisengage > 0)
    }
}

@Serializable
data class ExperimentScenario(
    val name: String,
    val durationTicks: Long,
    val stepTicks: Long = 20L,
    val initialSnapshot: WarbandSnapshot,
    val runtimeSpec: WarbandRuntimeSpec,
    val players: List<PlayerFact> = emptyList(),
    val terrain: List<TerrainObservation> = emptyList(),
    val assumptions: BoundedAssumptions = BoundedAssumptions(),
) {
    fun validate() {
        require(name.isNotBlank() && durationTicks >= 0L && stepTicks > 0L)
        runtimeSpec.requireValidRevision()
        assumptions.validate()
    }
}

@Serializable
data class ExperimentSummary(
    val name: String,
    val ticks: Long,
    val reserveThreat: Double,
    val raidPool: Double,
    val materialUnits: Double,
    val armoryItems: Int,
    val campaignsDispatched: Int,
    val campaignsReturned: Int,
    val peakCampaignThreat: Double,
    val recruitCounts: Map<String, Int>,
    val eventCounts: Map<String, Int>,
    val firstDispatchTick: Long? = null,
    val activeCampaigns: Int = 0,
    val resolvedCampaigns: Int = 0,
    val meanSquadSize: Double = 0.0,
    val distinctRecruits: Int = 0,
    val dominantRecruitShare: Double = 0.0,
    val longestRecruitStreak: Int = 0,
    val equipmentCoverage: Double = 0.0,
    val meanCampaignCycleTicks: Double = 0.0,
    val returnReasons: Map<String, Int> = emptyMap(),
    val aggressionByWarband: Map<String, Int> = emptyMap(),
    val preferenceDrift: Double = 0.0,
    val empiricalThreatEntries: Int = 0,
    val extractedMaterialCounts: Map<String, Int> = emptyMap(),
    val manufacturedEquipmentCounts: Map<String, Int> = emptyMap(),
    val stockpileItems: Int = 0,
    val resourcesAcquired: Int = 0,
    val resourcesConsumed: Int = 0,
    val meanSupplySatisfaction: Double? = null,
    val supplyObservationCount: Int = 0,
    val shortageReturns: Int = 0,
    val attritionLosses: Int = 0,
    val recoverableCaches: Int = 0,
    val meanRouteChunks: Double = 0.0,
    val meanEquipmentDurability: Double = 1.0,
    val armamentActionCounts: Map<String, Int> = emptyMap(),
    val meanArmamentUtility: Double = 0.0,
    val dispatchTicks: List<Long> = emptyList(),
    val interDispatchTicks: List<Long> = emptyList(),
    val minimumSquadSize: Int = 0,
    val maximumSquadSize: Int = 0,
    val boundary: ExperimentBoundary = ExperimentBoundary(),
)

@Serializable
data class AssumptionSweep(
    val lower: BoundedAssumptions,
    val nominal: BoundedAssumptions,
    val upper: BoundedAssumptions,
)

@Serializable data class ExperimentMatrix(val scenario: ExperimentScenario, val assumptions: AssumptionSweep)

@Serializable
data class ExperimentComparison(
    val name: String,
    val reserveThreatDelta: Double,
    val raidPoolDelta: Double,
    val materialUnitsDelta: Double,
    val armoryItemsDelta: Int,
    val campaignsDispatchedDelta: Int,
    val campaignsReturnedDelta: Int,
    val peakCampaignThreatDelta: Double,
)

@Serializable
data class BalanceFinding(
    val priority: Int,
    val topic: String,
    val observation: String,
    val evidenceScenarios: List<String>,
    val candidate: String,
    val risk: String,
    val confidence: String,
)

@Serializable
data class BalanceExploration(
    val runtimeSpecRevision: String,
    val summaries: List<ExperimentSummary>,
    val findings: List<BalanceFinding>,
    val boundary: ExperimentBoundary = ExperimentBoundary(),
)
