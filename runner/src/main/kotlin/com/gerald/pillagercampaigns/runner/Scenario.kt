package com.gerald.pillagercampaigns.runner

import com.gerald.pillagercampaigns.engine.*
import kotlinx.serialization.Serializable

@Serializable
data class BoundedAssumptions(
    val routeConfidence: Double = 0.6,
    val cohesion: Double = 0.7,
    val campaignDamagePerEngagement: Double = 4.0,
    val playerDamagePerEngagement: Double = 5.0,
    val effectiveRange: Double = 8.0,
    val engagementEveryTicks: Long = 1_200L,
) {
    fun validate() {
        require(routeConfidence in 0.0..1.0 && cohesion in 0.0..1.0)
        require(campaignDamagePerEngagement >= 0.0 && playerDamagePerEngagement >= 0.0)
        require(effectiveRange >= 0.0 && engagementEveryTicks > 0L)
    }
}

@Serializable
data class ExperimentScenario(
    val name: String,
    val durationTicks: Long,
    val stepTicks: Long = 20L,
    val state: EngineState,
    val catalog: EngineCatalog,
    val rules: WarbandRules = WarbandRules(),
    val players: List<PlayerFact> = emptyList(),
    val assumptions: BoundedAssumptions = BoundedAssumptions(),
) {
    fun validate() {
        require(name.isNotBlank() && durationTicks >= 0L && stepTicks > 0L)
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
