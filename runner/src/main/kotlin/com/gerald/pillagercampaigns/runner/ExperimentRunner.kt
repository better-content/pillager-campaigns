package com.gerald.pillagercampaigns.runner

import com.gerald.pillagercampaigns.engine.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ExperimentRunner(private val json: Json = Json { prettyPrint = false; encodeDefaults = true }) {
    data class RunResult(val summary: ExperimentSummary, val trace: List<TransitionResult>)

    fun run(scenario: ExperimentScenario): RunResult {
        scenario.validate()
        val trace = mutableListOf<TransitionResult>()
        val eventCounts = linkedMapOf<String, Int>()
        val recruitCounts = linkedMapOf<String, Int>()
        var dispatched = 0
        var returned = 0
        var peakThreat = 0.0
        var elapsed = 0L
        var engagementDebt = 0L
        while (elapsed < scenario.durationTicks) {
            val step = minOf(scenario.stepTicks, scenario.durationTicks - elapsed)
            engagementDebt += step
            val combat = mutableListOf<CombatObservation>()
            if (engagementDebt >= scenario.assumptions.engagementEveryTicks) {
                engagementDebt %= scenario.assumptions.engagementEveryTicks
                scenario.state.campaigns.values.filter { it.phase == CampaignPhase.ACTIVE }.forEach { campaign ->
                    combat += CombatObservation(
                        campaign.id,
                        scenario.assumptions.campaignDamagePerEngagement,
                        scenario.assumptions.playerDamagePerEngagement,
                        scenario.assumptions.effectiveRange,
                        scenario.assumptions.routeConfidence,
                        scenario.assumptions.cohesion,
                    )
                }
            }
            val readyBefore = scenario.state.campaigns.values.filter { it.phase == CampaignPhase.READY_TO_MATERIALIZE }.map { it.id }
            val materializations = readyBefore.map { MaterializationResult(it, true) }
            val result = WarbandEngine.transition(
                scenario.state,
                EngineFrame(step, scenario.players, combat, materializations),
                scenario.catalog,
                scenario.rules,
            )
            trace += result
            result.events.forEach { event ->
                eventCounts[event.type] = eventCounts.getOrDefault(event.type, 0) + 1
                if (event.type == "dispatched") dispatched++
                if (event.type == "returned") returned++
            }
            scenario.state.campaigns.values.forEach { campaign ->
                val threat = campaign.members.sumOf { it.threat }
                peakThreat = maxOf(peakThreat, threat)
                campaign.members.forEach { recruitCounts[it.recruitId] = recruitCounts.getOrDefault(it.recruitId, 0) + 1 }
            }
            elapsed += step
        }
        val warbands = scenario.state.warbands.values
        return RunResult(
            ExperimentSummary(
                scenario.name,
                elapsed,
                warbands.sumOf { it.reserveThreat },
                warbands.sumOf { it.raidPool },
                warbands.sumOf { it.materialLedger.values.sum() },
                warbands.sumOf { it.armory.size },
                dispatched,
                returned,
                peakThreat,
                recruitCounts,
                eventCounts,
            ),
            trace,
        )
    }

    fun write(result: RunResult, outputDirectory: File) {
        outputDirectory.mkdirs()
        File(outputDirectory, "trace.jsonl").bufferedWriter().use { writer ->
            result.trace.forEach { writer.appendLine(json.encodeToString(it)) }
        }
        File(outputDirectory, "summary.json").writeText(json.encodeToString(result.summary))
        File(outputDirectory, "summary.csv").writeText(
            "name,ticks,reserve_threat,raid_pool,material_units,armory_items,dispatched,returned,peak_campaign_threat\n" +
                listOf(
                    result.summary.name,
                    result.summary.ticks,
                    result.summary.reserveThreat,
                    result.summary.raidPool,
                    result.summary.materialUnits,
                    result.summary.armoryItems,
                    result.summary.campaignsDispatched,
                    result.summary.campaignsReturned,
                    result.summary.peakCampaignThreat,
                ).joinToString(",") + "\n",
        )
    }
}
