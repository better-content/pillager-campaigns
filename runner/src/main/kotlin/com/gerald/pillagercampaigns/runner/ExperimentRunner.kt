package com.gerald.pillagercampaigns.runner

import com.gerald.pillagercampaigns.engine.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

class ExperimentRunner(private val json: Json = Json { prettyPrint = false; encodeDefaults = true }) {
    data class RunResult(val summary: ExperimentSummary, val trace: List<TransitionResult>)

    fun run(scenario: ExperimentScenario, retainTrace: Boolean = true): RunResult {
        scenario.validate()
        val trace = mutableListOf<TransitionResult>()
        val eventCounts = linkedMapOf<String, Int>()
        val recruitCounts = linkedMapOf<String, Int>()
        var dispatched = 0
        var returned = 0
        var peakThreat = 0.0
        val seenMembers = mutableSetOf<String>()
        val seenCampaigns = mutableSetOf<String>()
        val squadSizes = mutableListOf<Int>()
        val recruitSequence = mutableListOf<String>()
        val dispatchTicks = mutableMapOf<String, Long>()
        val cycleTicks = mutableListOf<Long>()
        val returnReasons = linkedMapOf<String, Int>()
        val extractedMaterials = linkedMapOf<String, Int>()
        val manufacturedEquipment = linkedMapOf<String, Int>()
        val armamentActions = linkedMapOf<String, Int>()
        val armamentUtilities = mutableListOf<Double>()
        val supplySatisfaction = mutableListOf<Double>()
        var resourcesAcquired = 0
        var resourcesConsumed = 0
        val initialPreferences = scenario.state.warbands.mapValues { (_, warband) -> warband.preferences.toMap() }
        var equippedMembers = 0
        var totalMembers = 0
        var firstDispatchTick: Long? = null
        var elapsed = 0L
        var engagementDebt = 0L
        val engagementCounts = mutableMapOf<String, Int>()
        fun record(result: TransitionResult) {
            if (retainTrace) trace += result
            result.events.forEach { event ->
                eventCounts[event.type] = eventCounts.getOrDefault(event.type, 0) + 1
                if (event.type == "dispatched") {
                    dispatched++
                    dispatchTicks[event.subjectId] = event.tick
                    if (firstDispatchTick == null) firstDispatchTick = event.tick
                }
                if (event.type == "returned") {
                    returned++
                    dispatchTicks[event.subjectId]?.let { cycleTicks += event.tick - it }
                    scenario.state.campaigns[event.subjectId]?.returnReason?.let { reason ->
                        returnReasons[reason] = returnReasons.getOrDefault(reason, 0) + 1
                    }
                }
                if (event.type == "extracted") extractedMaterials[event.detail] = extractedMaterials.getOrDefault(event.detail, 0) + 1
                if (event.type == "manufactured") manufacturedEquipment[event.detail] = manufacturedEquipment.getOrDefault(event.detail, 0) + 1
                if (event.type == "resource_acquired") resourcesAcquired++
                if (event.type == "resource_consumed") resourcesConsumed += event.detail.substringAfter('=').toIntOrNull() ?: 0
                if (event.type == "logistics_segment") event.detail.substringAfter("satisfaction=").toDoubleOrNull()?.let(supplySatisfaction::add)
            }
        }
        while (elapsed < scenario.durationTicks) {
            val step = minOf(scenario.stepTicks, scenario.durationTicks - elapsed)
            engagementDebt += step
            val combat = mutableListOf<CombatObservation>()
            if (engagementDebt >= scenario.assumptions.engagementEveryTicks) {
                engagementDebt %= scenario.assumptions.engagementEveryTicks
                scenario.state.campaigns.values.filter { it.phase == CampaignPhase.ACTIVE }
                    .filter { campaign -> scenario.assumptions.engagementsBeforeDisengage?.let { engagementCounts.getOrDefault(campaign.id, 0) < it } != false }
                    .forEach { campaign ->
                    combat += CombatObservation(
                        campaign.id,
                        scenario.assumptions.campaignDamagePerEngagement,
                        scenario.assumptions.playerDamagePerEngagement,
                        scenario.assumptions.effectiveRange,
                        scenario.assumptions.routeConfidence,
                        scenario.assumptions.cohesion,
                    )
                    engagementCounts[campaign.id] = engagementCounts.getOrDefault(campaign.id, 0) + 1
                }
            }
            val readyBefore = scenario.state.campaigns.values.filter { it.phase == CampaignPhase.READY_TO_MATERIALIZE }.map { it.id }
            val materializations = readyBefore.map { MaterializationResult(it, true) }
            val result = WarbandEngine.transition(
                scenario.state,
                EngineFrame(step, scenario.players, combat, materializations, terrain = scenario.terrain),
                scenario.catalog,
                scenario.rules,
            )
            record(result)
            val dematerializations = result.effects.filter { it.kind == EffectKind.CAPTURE_SNAPSHOTS }
                .mapNotNull { effect -> effect.campaignId?.let(EngineCommand::Dematerialize) }
            if (dematerializations.isNotEmpty()) {
                // The runner's bounded physical adapter treats the emitted member list as
                // the captured snapshot and acknowledges removal immediately. The exact
                // abstract return/reconciliation still occurs in the engine afterward.
                record(
                    WarbandEngine.transition(
                        scenario.state,
                        EngineFrame(0L, scenario.players, commands = dematerializations),
                        scenario.catalog,
                        scenario.rules,
                    ),
                )
            }
            scenario.state.campaigns.values.forEach { campaign ->
                val threat = campaign.members.sumOf { it.threat }
                peakThreat = maxOf(peakThreat, threat)
                if (seenCampaigns.add(campaign.id)) {
                    squadSizes += campaign.members.size
                    totalMembers += campaign.members.size
                    equippedMembers += campaign.members.count { it.equipment != null }
                    recruitSequence += campaign.members.map(MemberManifest::recruitId)
                    val warband = scenario.state.warbands[campaign.warbandId]
                    val officer = scenario.state.officers[campaign.officerId]
                    if (warband != null) campaign.members.mapNotNull(MemberManifest::equipment).forEach { equipment ->
                        equipment.supportedActions.forEach { action -> armamentActions[action] = armamentActions.getOrDefault(action, 0) + 1 }
                        armamentUtilities += scenario.rules.capabilityUtility(
                            equipment.capabilities, scenario.rules.armamentPreferences(warband, officer),
                        )
                    }
                }
                campaign.members.forEach { member ->
                    if (seenMembers.add(member.id)) recruitCounts[member.recruitId] = recruitCounts.getOrDefault(member.recruitId, 0) + 1
                }
            }
            elapsed += step
        }
        val warbands = scenario.state.warbands.values
        val recruitTotal = recruitCounts.values.sum().coerceAtLeast(1)
        val dominantShare = recruitCounts.values.maxOrNull()?.toDouble()?.div(recruitTotal) ?: 0.0
        val preferenceDrift = scenario.state.warbands.values.sumOf { warband ->
            val initial = initialPreferences[warband.id].orEmpty()
            (initial.keys + warband.preferences.keys).sumOf { key -> abs(warband.preferences.getOrDefault(key, 0.0) - initial.getOrDefault(key, 0.0)) }
        }
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
                firstDispatchTick,
                scenario.state.campaigns.values.count { it.phase != CampaignPhase.RESOLVED },
                scenario.state.campaigns.values.count { it.phase == CampaignPhase.RESOLVED },
                squadSizes.averageIntsOrZero(),
                recruitCounts.size,
                dominantShare,
                longestStreak(recruitSequence),
                if (totalMembers == 0) 0.0 else equippedMembers.toDouble() / totalMembers,
                cycleTicks.map(Long::toDouble).averageDoublesOrZero(),
                returnReasons,
                scenario.state.warbands.mapValues { it.value.aggression },
                preferenceDrift,
                scenario.state.warbands.values.sumOf { it.empiricalThreat.size },
                extractedMaterials,
                manufacturedEquipment,
                warbands.sumOf { it.stockpile.values.sum() },
                resourcesAcquired,
                resourcesConsumed,
                supplySatisfaction.averageDoublesOrOne(),
                returnReasons.getOrDefault("supply_shortage", 0),
                eventCounts.getOrDefault("member_lost_to_attrition", 0),
                scenario.state.campaigns.values.sumOf { it.lostCaches.size },
                scenario.state.campaigns.values.map { it.route.size.toDouble() }.averageDoublesOrZero(),
                (warbands.flatMap { it.armory } + scenario.state.campaigns.values.flatMap { it.members }.mapNotNull { it.equipment })
                    .map { it.durabilityFraction }.averageDoublesOrOne(),
                armamentActions,
                armamentUtilities.averageDoublesOrZero(),
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
            "name,ticks,reserve_threat,raid_pool,material_units,armory_items,dispatched,returned,peak_campaign_threat,distinct_recruits,dominant_recruit_share,longest_recruit_streak,equipment_coverage,mean_cycle_ticks\n" +
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
                    result.summary.distinctRecruits,
                    result.summary.dominantRecruitShare,
                    result.summary.longestRecruitStreak,
                    result.summary.equipmentCoverage,
                    result.summary.meanCampaignCycleTicks,
                ).joinToString(",") + "\n",
        )
    }

    fun compare(current: ExperimentSummary, baseline: ExperimentSummary) = ExperimentComparison(
        current.name,
        current.reserveThreat - baseline.reserveThreat,
        current.raidPool - baseline.raidPool,
        current.materialUnits - baseline.materialUnits,
        current.armoryItems - baseline.armoryItems,
        current.campaignsDispatched - baseline.campaignsDispatched,
        current.campaignsReturned - baseline.campaignsReturned,
        current.peakCampaignThreat - baseline.peakCampaignThreat,
    )

    private fun Collection<Int>.averageIntsOrZero() = if (isEmpty()) 0.0 else average()
    private fun Collection<Double>.averageDoublesOrZero() = if (isEmpty()) 0.0 else average()
    private fun Collection<Double>.averageDoublesOrOne() = if (isEmpty()) 1.0 else average()

    private fun longestStreak(values: List<String>): Int {
        var longest = 0
        var current = 0
        var previous: String? = null
        values.forEach { value ->
            current = if (value == previous) current + 1 else 1
            longest = maxOf(longest, current)
            previous = value
        }
        return longest
    }
}
