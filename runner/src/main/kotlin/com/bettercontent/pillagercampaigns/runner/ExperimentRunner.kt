package com.bettercontent.pillagercampaigns.runner

import com.gerald.warband.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

class ExperimentRunner(private val json: Json = Json { prettyPrint = false; encodeDefaults = true }) {
    data class RunResult(
        val summary: ExperimentSummary,
        val trace: List<RunnerTransition>,
        val deterministicTrace: WarbandTrace? = null,
    )

    @Serializable
    data class RunnerTransition(
        val events: List<WarbandEvent>,
        val effects: List<WarbandEffect>,
        val state: WarbandSnapshot,
    )

    fun run(scenario: ExperimentScenario, retainTrace: Boolean = true): RunResult {
        scenario.validate()
        val engine = WarbandEngine.restore(scenario.initialSnapshot, scenario.runtimeSpec)
        var current = engine.snapshot()
        val traceCodec = WarbandTraceCodec(json)
        val initialState = if (retainTrace) traceCodec.cloneState(current) else null
        val trace = mutableListOf<RunnerTransition>()
        val deterministicSteps = mutableListOf<WarbandTraceStep>()
        val eventCounts = linkedMapOf<String, Int>()
        val recruitCounts = linkedMapOf<String, Int>()
        var dispatched = 0
        var returned = 0
        var peakThreat = 0.0
        val seenMembers = mutableSetOf<String>()
        val seenCampaigns = mutableSetOf<String>()
        val squadSizes = mutableListOf<Int>()
        val routeLengths = mutableListOf<Int>()
        val recruitSequence = mutableListOf<String>()
        val dispatchTicks = mutableMapOf<String, Long>()
        val allDispatchTicks = mutableListOf<Long>()
        val cycleTicks = mutableListOf<Long>()
        val returnReasons = linkedMapOf<String, Int>()
        val extractedMaterials = linkedMapOf<String, Int>()
        val manufacturedEquipment = linkedMapOf<String, Int>()
        val armamentActions = linkedMapOf<String, Int>()
        val armamentUtilities = mutableListOf<Double>()
        val supplySatisfaction = mutableListOf<Double>()
        var resourcesAcquired = 0
        var resourcesConsumed = 0
        val initialPreferences = current.warbands.mapValues { (_, warband) -> warband.preferences.toMap() }
        var equippedMembers = 0
        var totalMembers = 0
        var firstDispatchTick: Long? = null
        var elapsed = 0L
        var engagementDebt = 0L
        val engagementCounts = mutableMapOf<String, Int>()
        fun record(frame: WarbandFrame, result: WarbandTransition) {
            current = engine.snapshot()
            if (retainTrace) {
                val capturedState = traceCodec.cloneState(current)
                trace += RunnerTransition(result.events.toList(), result.effects.toList(), capturedState)
                deterministicSteps += WarbandTraceStep(
                    deterministicSteps.size,
                    traceCodec.cloneFrame(frame),
                    result.events.toList(),
                    result.effects.toList(),
                    traceCodec.stateHash(capturedState),
                )
            }
            result.events.forEach { event ->
                eventCounts[event.type] = eventCounts.getOrDefault(event.type, 0) + 1
                if (event.type == "dispatched") {
                    dispatched++
                    dispatchTicks[event.subjectId] = event.tick
                    allDispatchTicks += event.tick
                    if (firstDispatchTick == null) firstDispatchTick = event.tick
                }
                if (event.type == "returned") {
                    returned++
                    dispatchTicks[event.subjectId]?.let { cycleTicks += event.tick - it }
                    current.campaigns[event.subjectId]?.returnReason?.let { reason ->
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
                current.campaigns.values.filter { it.phase == CampaignPhase.ACTIVE }
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
            val readyBefore = current.campaigns.values.filter { it.phase == CampaignPhase.READY_TO_MATERIALIZE }.map { it.id }
            val materializations = readyBefore.mapNotNull { campaignId ->
                val effectId = current.pendingEffects.values.firstOrNull {
                    it.kind == EffectKind.MATERIALIZE && it.campaignId == campaignId
                }?.effectId ?: return@mapNotNull null
                MaterializationResult(
                    campaignId,
                    true,
                    effectId = effectId,
                )
            }
            val frame = CoreFrame(
                elapsedTicks = step,
                players = scenario.players,
                combat = combat,
                materializations = materializations,
                materializationSites = readyBefore.map { campaignId ->
                    val campaign = current.campaigns.getValue(campaignId)
                    MaterializationSiteObservation(
                        campaignId,
                        listOf(BlockPosition(campaign.position.dimension, campaign.position.x shl 4, 64, campaign.position.z shl 4)),
                    )
                },
                terrain = scenario.terrain,
            )
            val recordedFrame = if (retainTrace) traceCodec.cloneFrame(frame) else frame
            val result = engine.transition(frame)
            record(recordedFrame, result)
            val immediateMaterializations = current.pendingEffects.values.filter { it.kind == EffectKind.MATERIALIZE }
                .mapNotNull { effect -> effect.campaignId?.let { MaterializationResult(it, true, effectId = effect.effectId) } }
            if (immediateMaterializations.isNotEmpty()) {
                val realizationFrame = CoreFrame(0L, scenario.players, materializations = immediateMaterializations)
                val recordedRealizationFrame = if (retainTrace) traceCodec.cloneFrame(realizationFrame) else realizationFrame
                record(recordedRealizationFrame, engine.transition(realizationFrame))
            }
            val snapshots = current.pendingEffects.values.filter { it.kind == EffectKind.CAPTURE_SNAPSHOTS }
                .mapNotNull { effect ->
                    val campaign = effect.campaignId?.let(current.campaigns::get) ?: return@mapNotNull null
                    CampaignSnapshotResult(
                        campaign.id,
                        campaign.position,
                        campaign.members.map { member ->
                            MemberSnapshot(
                                member.id, member.healthFraction, member.experience,
                                member.equipment, member.cargo.toMap(),
                            )
                        },
                        effect.effectId,
                    )
                }
            if (snapshots.isNotEmpty()) {
                // Synthetic harness behavior: assume every emitted member remains in the
                // supplied snapshot. This exercises Core's protocol; it does not model a
                // Minecraft entity capture, removal, or rematerialization.
                val snapshotFrame = CoreFrame(0L, scenario.players, snapshots = snapshots)
                val recordedSnapshotFrame = if (retainTrace) traceCodec.cloneFrame(snapshotFrame) else snapshotFrame
                record(
                    recordedSnapshotFrame,
                    engine.transition(snapshotFrame),
                )
            }
            current.campaigns.values.forEach { campaign ->
                val threat = campaign.members.sumOf { it.threat }
                peakThreat = maxOf(peakThreat, threat)
                if (seenCampaigns.add(campaign.id)) {
                    squadSizes += campaign.members.size
                    routeLengths += campaign.route.size
                    totalMembers += campaign.members.size
                    equippedMembers += campaign.members.count { it.equipment != null }
                    recruitSequence += campaign.members.map(MemberManifest::recruitId)
                    val warband = current.warbands[campaign.warbandId]
                    val officer = current.officers[campaign.officerId]
                    if (warband != null) campaign.members.mapNotNull(MemberManifest::equipment).forEach { equipment ->
                        equipment.supportedActions.forEach { action -> armamentActions[action] = armamentActions.getOrDefault(action, 0) + 1 }
                        armamentUtilities += scenario.runtimeSpec.rules.capabilityUtility(
                            equipment.capabilities, scenario.runtimeSpec.rules.armamentPreferences(warband, officer),
                        )
                    }
                }
                campaign.members.forEach { member ->
                    if (seenMembers.add(member.id)) recruitCounts[member.recruitId] = recruitCounts.getOrDefault(member.recruitId, 0) + 1
                }
            }
            elapsed += step
        }
        val warbands = current.warbands.values
        val recruitTotal = recruitCounts.values.sum().coerceAtLeast(1)
        val dominantShare = recruitCounts.values.maxOrNull()?.toDouble()?.div(recruitTotal) ?: 0.0
        val preferenceDrift = current.warbands.values.sumOf { warband ->
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
                current.campaigns.values.count { it.phase != CampaignPhase.RESOLVED },
                seenCampaigns.count { campaignId ->
                    current.campaigns[campaignId]?.phase == CampaignPhase.RESOLVED || campaignId !in current.campaigns
                },
                squadSizes.averageIntsOrZero(),
                recruitCounts.size,
                dominantShare,
                longestStreak(recruitSequence),
                if (totalMembers == 0) 0.0 else equippedMembers.toDouble() / totalMembers,
                cycleTicks.map(Long::toDouble).averageDoublesOrZero(),
                returnReasons,
                current.warbands.mapValues { it.value.aggression },
                preferenceDrift,
                current.warbands.values.sumOf { it.empiricalThreat.size },
                extractedMaterials,
                manufacturedEquipment,
                warbands.sumOf { it.stockpile.values.sum() },
                resourcesAcquired,
                resourcesConsumed,
                supplySatisfaction.takeIf(List<Double>::isNotEmpty)?.average(),
                supplySatisfaction.size,
                returnReasons.getOrDefault("supply_shortage", 0),
                eventCounts.getOrDefault("member_lost_to_attrition", 0),
                current.campaigns.values.sumOf { it.lostCaches.size },
                routeLengths.averageIntsOrZero(),
                (warbands.flatMap { it.armory } + current.campaigns.values.flatMap { it.members }.mapNotNull { it.equipment })
                    .map { it.durabilityFraction }.averageDoublesOrOne(),
                armamentActions,
                armamentUtilities.averageDoublesOrZero(),
                dispatchTicks = allDispatchTicks.toList(),
                interDispatchTicks = allDispatchTicks.zipWithNext { earlier, later -> later - earlier },
                minimumSquadSize = squadSizes.minOrNull() ?: 0,
                maximumSquadSize = squadSizes.maxOrNull() ?: 0,
            ),
            trace,
            initialState?.let {
                WarbandTrace(
                    runtimeSpecRevision = scenario.runtimeSpec.revision,
                    initialState = it,
                    initialStateHash = traceCodec.stateHash(it),
                    runtimeSpec = scenario.runtimeSpec,
                    steps = deterministicSteps,
                )
            },
        )
    }

    fun write(result: RunResult, outputDirectory: File) {
        outputDirectory.mkdirs()
        val lineJson = Json { encodeDefaults = true }
        val traceFile = File(outputDirectory, "trace.jsonl")
        if (result.trace.isEmpty()) {
            traceFile.delete()
        } else {
            traceFile.bufferedWriter().use { writer ->
                result.trace.forEach { writer.appendLine(lineJson.encodeToString(it)) }
            }
        }
        File(outputDirectory, "summary.json").writeText(json.encodeToString(result.summary))
        File(outputDirectory, "scope.json").writeText(json.encodeToString(result.summary.boundary))
        File(outputDirectory, "summary.csv").writeText(
            "name,ticks,reserve_threat,raid_pool,material_units,armory_items,dispatched,returned,peak_campaign_threat,distinct_recruits,dominant_recruit_share,longest_recruit_streak,equipment_coverage,mean_cycle_ticks,supply_satisfaction,supply_observations,model,minecraft_simulation,synthetic_external_observations\n" +
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
                    result.summary.meanSupplySatisfaction ?: "",
                    result.summary.supplyObservationCount,
                    result.summary.boundary.model,
                    result.summary.boundary.minecraftSimulation,
                    result.summary.boundary.externalObservationsAreSynthetic,
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
