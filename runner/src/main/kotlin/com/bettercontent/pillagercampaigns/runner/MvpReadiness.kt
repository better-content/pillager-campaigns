package com.bettercontent.pillagercampaigns.runner

import com.gerald.warband.core.*
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Serializable
data class MvpReadinessCheck(
    val id: String,
    val passed: Boolean,
    val requirement: String,
    val observation: String,
    val evidenceScenarios: List<String> = emptyList(),
)

@Serializable
data class MvpReadinessReport(
    val runtimeSpecRevision: String,
    val passed: Boolean,
    val checks: List<MvpReadinessCheck>,
    val scenarioCount: Int,
    val boundary: ExperimentBoundary = ExperimentBoundary(),
)

class MvpReadinessEvaluator(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false },
) {
    fun evaluate(runtimeSpecFile: File, output: File): MvpReadinessReport {
        val spec = json.decodeFromString<WarbandRuntimeSpec>(runtimeSpecFile.readText())
        spec.requireValidRevision()
        val checks = mutableListOf<MvpReadinessCheck>()
        fun check(id: String, requirement: String, observation: String, evidence: List<String> = emptyList(), passed: Boolean) {
            checks += MvpReadinessCheck(id, passed, requirement, observation, evidence)
        }

        val channels = mapOf(
            "sustenance" to spec.resources.any { it.unitsPerItem.sustenance > 0.0 },
            "munitions" to spec.resources.any { it.unitsPerItem.munitions > 0.0 },
            "maintenance" to spec.resources.any { it.unitsPerItem.maintenance > 0.0 },
            "recovery" to spec.resources.any { it.unitsPerItem.recovery > 0.0 },
        )
        check("content-recruits", "At least two recruit definitions", "recruits=${spec.recruits.size}", passed = spec.recruits.size >= 2)
        check("content-resources", "All four logistics channels have a positive resource", channels.toString(), passed = channels.values.all { it })
        check(
            "content-equipment", "At least one compatible material-backed equipment platform",
            "materials=${spec.materials.size} platforms=${spec.equipmentPlatforms.size}",
            passed = spec.materials.isNotEmpty() && spec.equipmentPlatforms.any { platform ->
                platform.components.isNotEmpty() && platform.components.all { it.compatibleMaterialIds.isNotEmpty() }
            },
        )
        check("content-rewards", "At least one positive reward denomination", "rewards=${spec.rewards.size}", passed = spec.rewards.any { it.value > 0.0 })

        val explorationDir = output.resolve("exploration")
        val exploration = BalanceExplorer(json).explore(runtimeSpecFile, explorationDir)
        val summaries = exploration.summaries
        val summaryNames = summaries.mapTo(hashSetOf(), ExperimentSummary::name)
        check(
            "accounting", "Every dispatch is active or terminal and all numeric state remains bounded",
            "scenarios=${summaries.size}",
            passed = summaries.all { summary ->
                summary.activeCampaigns + summary.resolvedCampaigns == summary.campaignsDispatched &&
                    summary.campaignsReturned <= summary.resolvedCampaigns &&
                    summary.reserveThreat >= 0.0 && summary.raidPool >= 0.0 && summary.materialUnits >= 0.0 &&
                    summary.equipmentCoverage in 0.0..1.0 && summary.dominantRecruitShare in 0.0..1.0 &&
                    (summary.meanSupplySatisfaction?.let { it in 0.0..1.0 } != false)
            },
        )
        check(
            "evidence", "Every report citation names an executed scenario",
            "citations=${exploration.findings.sumOf { it.evidenceScenarios.size }}",
            passed = exploration.findings.flatMap { it.evidenceScenarios }.all(summaryNames::contains),
        )

        val oneDayNominal = summaries.filter { it.name.matches(Regex("^h1d-e\\d+-nominal-s\\d+$")) }
        val badFirst = oneDayNominal.filter { it.firstDispatchTick !in 4_000L..24_000L }
        check(
            "first-dispatch", "Nominal first dispatch occurs between 4,000 and 24,000 Core ticks",
            "range=${range(oneDayNominal.mapNotNull { it.firstDispatchTick })}", badFirst.map { it.name }, badFirst.isEmpty() && oneDayNominal.isNotEmpty(),
        )

        val matureNominal = summaries.filter { it.name.matches(Regex("^h30d-e\\d+-nominal-s\\d+$")) }
        val badNominalCadence = matureNominal.filter { summary ->
            summary.interDispatchTicks.isEmpty() || summary.interDispatchTicks.any { it !in 24_000L..72_000L }
        }
        check(
            "nominal-cadence", "Every mature nominal inter-dispatch interval is 24,000..72,000 Core ticks",
            "range=${range(matureNominal.flatMap { it.interDispatchTicks })}", badNominalCadence.map { it.name },
            badNominalCadence.isEmpty() && matureNominal.isNotEmpty(),
        )

        val matureExtreme = summaries.filter { it.name.matches(Regex("^h30d-e\\d+-(warband-favored|player-favored)-s\\d+$")) }
        val deadlocked = matureExtreme.filter { summary ->
            summary.campaignsDispatched < 2 || summary.interDispatchTicks.any { it > 144_000L }
        }
        check(
            "extreme-recovery", "Extreme envelopes redispatch and never leave a gap above 144,000 Core ticks",
            "worst_gap=${matureExtreme.flatMap { it.interDispatchTicks }.maxOrNull() ?: 0}", deadlocked.map { it.name },
            deadlocked.isEmpty() && matureExtreme.isNotEmpty(),
        )

        val badSquads = summaries.filter { it.name.startsWith("h30d-") }.filter { summary ->
            summary.campaignsDispatched > 0 && (summary.minimumSquadSize < 2 || summary.maximumSquadSize > minOf(6, spec.rules.maximumSquadMembers))
        }
        check(
            "squad-expression", "Mature squads contain 2..6 members when multiple recruits exist",
            "size_range=${summaries.filter { it.name.startsWith("h30d-") }.let { values ->
                "${values.minOfOrNull { it.minimumSquadSize } ?: 0}..${values.maxOfOrNull { it.maximumSquadSize } ?: 0}"
            }}", badSquads.map { it.name }, badSquads.isEmpty(),
        )

        val growthFailures = mutableListOf<String>()
        val growthRatios = mutableListOf<Double>()
        summaries.filter { it.name.matches(Regex("^h3d-e\\d+-nominal-s\\d+$")) }.forEach { early ->
            val matureName = early.name.replaceFirst("h3d-", "h30d-")
            val mature = summaries.firstOrNull { it.name == matureName } ?: return@forEach
            val ratio = mature.peakCampaignThreat / early.peakCampaignThreat.coerceAtLeast(0.0001)
            growthRatios += ratio
            if (ratio !in 1.25..3.0) growthFailures += matureName
        }
        check(
            "power-growth", "Thirty-day nominal peak threat is 1.25x..3.0x its three-day value",
            "ratio_range=${decimalRange(growthRatios)}", growthFailures, growthFailures.isEmpty() && growthRatios.isNotEmpty(),
        )

        val equipmentFailures = matureNominal.filter { it.campaignsDispatched > 0 && it.equipmentCoverage < 0.5 }
        check(
            "equipment-coverage", "Mature nominal equipment coverage is at least 50%",
            "minimum=${matureNominal.minOfOrNull { it.equipmentCoverage } ?: 0.0}", equipmentFailures.map { it.name },
            equipmentFailures.isEmpty() && matureNominal.isNotEmpty(),
        )

        val supply = summaries.filter { it.name.startsWith("sensitivity-supply-") }.associateBy { it.name.substringAfterLast('-').toInt() }
        val emptySupply = supply[0]
        val highSupply = supply[96]
        val supplyDelta = if (emptySupply?.meanSupplySatisfaction != null && highSupply?.meanSupplySatisfaction != null) {
            highSupply.meanSupplySatisfaction - emptySupply.meanSupplySatisfaction
        } else Double.NEGATIVE_INFINITY
        check(
            "logistics-sensitivity", "High supply improves satisfaction by at least 0.05 and never increases total attrition",
            "satisfaction_delta=$supplyDelta attrition=${emptySupply?.attritionLosses}->${highSupply?.attritionLosses}",
            listOfNotNull(emptySupply?.name, highSupply?.name),
            supplyDelta >= 0.05 && (highSupply?.attritionLosses ?: Int.MAX_VALUE) <= (emptySupply?.attritionLosses ?: -1),
        )

        val idle = summaries.filter { it.name.startsWith("sensitivity-idle-") }.sortedBy { it.name.substringAfterLast('-').toLong() }
        check(
            "idle-return", "Shorter idle thresholds produce at least as many returns and aggression stays bounded",
            "returns=${idle.joinToString { "${it.name.substringAfterLast('-')}:${it.campaignsReturned}" }}",
            idle.map { it.name },
            idle.size == 3 && idle.zipWithNext().all { (shorter, longer) -> shorter.campaignsReturned >= longer.campaignsReturned } &&
                idle.flatMap { it.aggressionByWarband.values }.all { it in spec.rules.minimumAggression..spec.rules.maximumAggression },
        )

        val environmentSignatures = matureNominal.groupBy { Regex("-e(\\d+)-").find(it.name)!!.groupValues[1] }
            .mapValues { (_, cells) -> listOf(
                cells.sumOf { it.campaignsDispatched }.toDouble(),
                cells.map { it.peakCampaignThreat }.average(),
                cells.mapNotNull { it.meanSupplySatisfaction }.average(),
                cells.flatMap { it.manufacturedEquipmentCounts.keys }.distinct().size.toDouble(),
            ).map { (it * 1_000.0).roundToInt() } }
        check(
            "environment-expression", "Three environment inputs change at least one strategic aggregate",
            environmentSignatures.toString(), matureNominal.map { it.name },
            environmentSignatures.size == 3 && environmentSignatures.values.distinct().size >= 2,
        )

        val explorer = BalanceExplorer(json)
        val base = explorer.scenario(
            "mvp-boundary", 48_000L, spec, BalanceExplorer.STANDARD_ENVIRONMENTS.first(), BoundedAssumptions(), 101L,
        )
        val boundaryScenarios = listOf(
            base.copy(name = "mvp-no-player", players = emptyList()),
            base.copy(name = "mvp-non-hostile", initialSnapshot = clone(base.initialSnapshot).also { it.territoryRelations.clear() }),
            base.copy(name = "mvp-protected", initialSnapshot = clone(base.initialSnapshot).also { it.protectedPlayersUntilTick["player"] = Long.MAX_VALUE }),
            base.copy(name = "mvp-out-of-range", players = listOf(PlayerFact("player", ChunkPosition("minecraft:overworld", spec.rules.maximumDispatchDistanceChunks + 100, 0)))),
        )
        val boundaryResults = boundaryScenarios.map { ExperimentRunner(json).run(it, retainTrace = false).summary }
        check(
            "dispatch-boundaries", "No-player, non-hostile, protected and out-of-range inputs never dispatch while economy advances",
            boundaryResults.joinToString { "${it.name}:${it.campaignsDispatched}" }, boundaryResults.map { it.name },
            boundaryResults.all { it.campaignsDispatched == 0 && it.eventCounts.getOrDefault("mobilized", 0) > 0 },
        )

        val replayScenario = base.copy(name = "mvp-replay", durationTicks = 24_000L)
        val first = ExperimentRunner(json).run(replayScenario, retainTrace = true)
        val second = ExperimentRunner(json).run(replayScenario, retainTrace = true)
        val trace = requireNotNull(first.deterministicTrace)
        val replay = WarbandTraceCodec(json).replay(trace)
        val physicalKinds = setOf(EffectKind.MATERIALIZE, EffectKind.CAPTURE_SNAPSHOTS, EffectKind.NAVIGATE, EffectKind.DEMATERIALIZE)
        check(
            "deterministic-replay", "Repeated summaries and exact trace replay agree with no unresolved synthetic physical effect",
            "steps=${replay.stepCount} final_hash=${replay.finalStateHash}", listOf(replayScenario.name),
            json.encodeToString(first.summary) == json.encodeToString(second.summary) &&
                replay.finalStateHash == trace.steps.lastOrNull()?.postStateHash &&
                first.trace.lastOrNull()?.state?.pendingEffects?.values?.none { it.kind in physicalKinds } == true,
        )

        val report = MvpReadinessReport(spec.revision, checks.all(MvpReadinessCheck::passed), checks, summaries.size + boundaryResults.size + 1)
        write(report, output)
        return report
    }

    private fun write(report: MvpReadinessReport, output: File) {
        output.mkdirs()
        output.resolve("mvp-readiness.json").writeText(json.encodeToString(report))
        output.resolve("scope.json").writeText(json.encodeToString(report.boundary))
        output.resolve("mvp-readiness.csv").writeText(buildString {
            appendLine("id,passed,requirement,observation")
            report.checks.forEach { check -> appendLine(listOf(check.id, check.passed, csv(check.requirement), csv(check.observation)).joinToString(",")) }
        })
        output.resolve("mvp-readiness.md").writeText(buildString {
            appendLine("# Warband Core MVP Readiness")
            appendLine()
            appendLine("**${if (report.passed) "PASS" else "FAIL"}** — ${report.boundary.statement}")
            appendLine()
            appendLine("Runtime specification: `${report.runtimeSpecRevision}`")
            appendLine()
            report.checks.forEach { check ->
                appendLine("- [${if (check.passed) "x" else " "}] `${check.id}` — ${check.requirement}. ${check.observation}")
            }
        })
    }

    private fun clone(snapshot: WarbandSnapshot): WarbandSnapshot = json.decodeFromString(json.encodeToString(snapshot))
    private fun range(values: List<Long>) = if (values.isEmpty()) "none" else "${values.min()}..${values.max()}"
    private fun decimalRange(values: List<Double>) = if (values.isEmpty()) "none" else "%.3f..%.3f".format(values.min(), values.max())
    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
}
