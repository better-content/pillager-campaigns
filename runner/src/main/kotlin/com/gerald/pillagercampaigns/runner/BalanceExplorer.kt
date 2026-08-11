package com.gerald.pillagercampaigns.runner

import com.gerald.pillagercampaigns.engine.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class BalanceExplorer(private val json: Json = Json { prettyPrint = true; encodeDefaults = true }) {
    data class Settings(
        val horizonsDays: List<Long> = listOf(1L, 3L, 10L, 30L),
        val seeds: List<Long> = listOf(11L, 29L, 47L),
        val includeSensitivity: Boolean = true,
    )

    fun explore(catalogFile: File, output: File, settings: Settings = Settings()): BalanceExploration {
        val catalog = json.decodeFromString<EngineCatalog>(catalogFile.readText())
        require(catalog.recruits.isNotEmpty()) { "live catalog has no recruits" }
        require(catalog.revision.startsWith("forge-live-sha256:")) { "catalog lacks Forge provenance hash" }
        val environments = catalog.environmentSamples.ifEmpty { listOf(EnvironmentTraits()) }
        val bands = linkedMapOf(
            "warband-favored" to BoundedAssumptions(.85, .90, 6.0, 3.0, 10.0, 1_200L),
            "nominal" to BoundedAssumptions(),
            "player-favored" to BoundedAssumptions(.35, .45, 2.0, 8.0, 6.0, 1_200L),
        )
        val summaries = mutableListOf<ExperimentSummary>()
        val runner = ExperimentRunner(json)
        settings.horizonsDays.forEach { days ->
            environments.forEachIndexed { environmentIndex, environment ->
                bands.forEach { (band, assumptions) ->
                    settings.seeds.forEach { seed ->
                        val name = "h${days}d-e$environmentIndex-$band-s$seed"
                        summaries += runner.run(
                            scenario(name, days * TICKS_PER_DAY, catalog, environment, assumptions, seed),
                            retainTrace = false,
                        ).summary
                    }
                }
            }
        }

        if (settings.includeSensitivity) {
            val baseEnvironment = environments.first()
            val nominal = bands.getValue("nominal")
            listOf(6, 12, 18).forEach { aggression ->
                summaries += runner.run(scenario("sensitivity-aggression-$aggression", 10 * TICKS_PER_DAY, catalog, baseEnvironment, nominal, 11L, aggression = aggression), false).summary
            }
            listOf(6.0, 18.0, 54.0).forEach { reserve ->
                summaries += runner.run(scenario("sensitivity-reserve-${reserve.toInt()}", 10 * TICKS_PER_DAY, catalog, baseEnvironment, nominal, 11L, reserve = reserve), false).summary
            }
            listOf(6, 16, 32).forEach { distance ->
                summaries += runner.run(scenario("sensitivity-distance-$distance", 10 * TICKS_PER_DAY, catalog, baseEnvironment, nominal, 11L, distance = distance), false).summary
            }
            val disengagement = nominal.copy(campaignDamagePerEngagement = 0.0, playerDamagePerEngagement = 0.0, engagementsBeforeDisengage = 1)
            listOf(6_000L, 12_000L, 24_000L).forEach { idle ->
                summaries += runner.run(scenario("sensitivity-idle-$idle", 10 * TICKS_PER_DAY, catalog, baseEnvironment, disengagement, 11L, rules = WarbandRules(idleReturnTicks = idle)), false).summary
            }
            listOf(0.0, .05, .15).forEach { rate ->
                val rules = WarbandRules(warbandLearningRate = rate, captainLearningRate = rate * 2.0, threatLearningRate = rate * 2.0)
                summaries += runner.run(scenario("sensitivity-learning-$rate", 10 * TICKS_PER_DAY, catalog, baseEnvironment, nominal, 11L, rules = rules), false).summary
            }
        }

        val exploration = BalanceExploration(catalog.revision, summaries, findings(summaries, catalog))
        write(exploration, output)
        return exploration
    }

    private fun scenario(
        name: String,
        duration: Long,
        catalog: EngineCatalog,
        environment: EnvironmentTraits,
        assumptions: BoundedAssumptions,
        seed: Long,
        aggression: Int = 6,
        reserve: Double = 18.0,
        distance: Int = 12,
        rules: WarbandRules = WarbandRules(),
    ): ExperimentScenario {
        val preferences = FormulaMath.initialPreferences(seed, environment)
        val ledger = catalog.materials.sortedBy(MaterialDefinition::id).take(3).associateTo(linkedMapOf()) { it.id to 8.0 }
        val warband = WarbandState(
            "warband", "faction", ChunkPosition("minecraft:overworld", 0, 0), rules.capacity(environment).toDouble(), reserve,
            aggression = aggression, environment = environment, preferences = preferences, materialLedger = ledger,
        )
        return ExperimentScenario(
            name, duration, 20L,
            EngineState(
                sequence = seed,
                factions = linkedMapOf("faction" to FactionState("faction", "Exploration", seed.toInt())),
                warbands = linkedMapOf(warband.id to warband),
                officers = linkedMapOf("captain" to OfficerState("captain", "faction", warband.id)),
            ),
            catalog, rules,
            listOf(PlayerFact("player", ChunkPosition("minecraft:overworld", distance, 0), setOf(warband.id))),
            assumptions,
        )
    }

    private fun findings(summaries: List<ExperimentSummary>, catalog: EngineCatalog): List<BalanceFinding> {
        val baseline = summaries.filter { it.name.startsWith("h") }
        val early = baseline.filter { it.name.startsWith("h3d-") }
        val mature = baseline.filter { it.name.startsWith("h30d-") }
        val findings = mutableListOf<BalanceFinding>()

        val dispatching = baseline.filter { it.campaignsDispatched > 0 }
        val firstHorizonWithoutDispatch = baseline.count { it.name.startsWith("h1d-") && it.campaignsDispatched == 0 }
        findings += BalanceFinding(
            1, "Formulaic squad readiness",
            "Across ${dispatching.size} dispatching longitudinal cells, mean squad size ranges from ${decimal(dispatching.minOfOrNull(ExperimentSummary::meanSquadSize) ?: 0.0)} to ${decimal(dispatching.maxOfOrNull(ExperimentSummary::meanSquadSize) ?: 0.0)}. $firstHorizonWithoutDispatch of ${baseline.count { it.name.startsWith("h1d-") }} one-day cells do not dispatch yet; every three-day cell does.",
            listOfNotNull(dispatching.minByOrNull(ExperimentSummary::meanSquadSize)?.name, baseline.filter { it.campaignsDispatched == 0 }.firstOrNull()?.name),
            "Retain the preference-selected-lead plus distinct-support readiness formula. Adjust mobilization or cooldown separately if first-contact cadence needs tuning.",
            "Weakening readiness directly can reintroduce singleton raids; strengthening it can delay contact in low-habitability terrain.", "high",
        )

        val dominant = early.maxByOrNull(ExperimentSummary::dominantRecruitShare)
        if (dominant != null) findings += BalanceFinding(
            2, "Low-level and mature roster variety",
            "The worst three-day cell assigns ${percent(dominant.dominantRecruitShare)} of members to one recruit; the envelope mean is ${percent(early.map { it.dominantRecruitShare }.averageOrZero())}. ${mature.flatMap { it.recruitCounts.keys }.distinct().size} of ${catalog.recruits.size} live recruits appear across mature cells, with ${mature.minOfOrNull(ExperimentSummary::distinctRecruits) ?: 0}–${mature.maxOfOrNull(ExperimentSummary::distinctRecruits) ?: 0} distinct recruits per cell.",
            early.sortedByDescending(ExperimentSummary::dominantRecruitShare).take(3).map(ExperimentSummary::name),
            "Keep the within-squad diminishing marginal utility. Add a bounded, decaying recent-deployment share only if longer-term repetition remains visible in playtests.",
            "Too much penalty can force weak recruits despite strong environmental preferences.", "high",
        )

        val leastMaterialVariety = mature.minByOrNull { it.extractedMaterialCounts.size }
        if (leastMaterialVariety != null) findings += BalanceFinding(
            3, "Material and equipment diversity",
            "At least one mature cell extracts only ${leastMaterialVariety.extractedMaterialCounts.size} material type(s) and manufactures ${leastMaterialVariety.manufacturedEquipmentCounts.size} equipment formulation(s).",
            mature.sortedBy { it.extractedMaterialCounts.size }.take(3).map(ExperimentSummary::name),
            "Include current ledger abundance and recent extraction frequency as bounded scarcity terms in material utility.",
            "Scarcity pressure must not select unaffordable or environmentally impossible materials.", "high",
        )

        val fastest = baseline.maxByOrNull { raidsPerDay(it) }
        val slowest = baseline.filter { it.ticks >= 3 * TICKS_PER_DAY }.minByOrNull { raidsPerDay(it) }
        if (fastest != null && slowest != null) findings += BalanceFinding(
            4, "Pressure cadence",
            "Observed cadence ranges from ${decimal(raidsPerDay(slowest))} to ${decimal(raidsPerDay(fastest))} dispatches per day across the bounded envelope.",
            listOf(slowest.name, fastest.name),
            "Make cooldown recovery a smooth function of aggression, surviving threat, and recent completed-cycle duration.",
            "Faster recycling can become oppressive when several warbands target one travel corridor.", "medium",
        )

        val equipment = mature.minByOrNull(ExperimentSummary::equipmentCoverage)
        if (equipment != null) findings += BalanceFinding(
            5, "Equipment expression",
            "The weakest mature cell equips ${percent(equipment.equipmentCoverage)} of dispatched members while retaining ${equipment.armoryItems} armory items.",
            mature.sortedBy(ExperimentSummary::equipmentCoverage).take(3).map(ExperimentSummary::name),
            "Score equipment assignment by capability gain and action compatibility, with a bounded penalty for long-idle armory stock.",
            "Aggressive stock rotation can erase the identity created by material preferences.", "medium",
        )

        val idle = summaries.filter { it.name.startsWith("sensitivity-idle-") }
        if (idle.isNotEmpty()) findings += BalanceFinding(
            6, "Idle-return aggression feedback",
            "Idle-return sensitivity changes completed campaigns from ${idle.minOf { it.campaignsReturned }} to ${idle.maxOf { it.campaignsReturned }} over ten days; final aggression spans ${idle.flatMap { it.aggressionByWarband.values }.minOrNull()}–${idle.flatMap { it.aggressionByWarband.values }.maxOrNull()}.",
            idle.map(ExperimentSummary::name),
            "Keep the aggression increase on disengaged return, but scale it by idle duration relative to travel time instead of a flat increment.",
            "Scaling too softly makes kiting a permanent suppression strategy; scaling too strongly creates runaway raid budgets.", "medium",
        )
        return findings.sortedBy(BalanceFinding::priority)
    }

    private fun write(exploration: BalanceExploration, output: File) {
        output.mkdirs()
        output.resolve("exploration.json").writeText(json.encodeToString(exploration))
        output.resolve("summaries.csv").writeText(buildString {
            appendLine("name,ticks,dispatched,returned,active,raid_pool,distinct_recruits,dominant_share,longest_streak,equipment_coverage,mean_cycle_ticks,preference_drift")
            exploration.summaries.forEach { summary ->
                appendLine(listOf(summary.name, summary.ticks, summary.campaignsDispatched, summary.campaignsReturned, summary.activeCampaigns, summary.raidPool, summary.distinctRecruits, summary.dominantRecruitShare, summary.longestRecruitStreak, summary.equipmentCoverage, summary.meanCampaignCycleTicks, summary.preferenceDrift).joinToString(","))
            }
        })
        output.resolve("balance-notes.md").writeText(buildString {
            appendLine("# Pillager Campaigns Programmatic Balance Notes")
            appendLine()
            appendLine("Catalog: `${exploration.catalogRevision}`")
            appendLine()
            appendLine("The report covers ${exploration.summaries.size} deterministic cells using the exact authoritative engine and a Forge-captured live catalog.")
            appendLine()
            appendLine("## Longitudinal overview")
            appendLine()
            appendLine("| Horizon | Cells | Raids/day | Distinct recruits | Dominant share | Equipment coverage |")
            appendLine("|---:|---:|---:|---:|---:|---:|")
            exploration.summaries.asSequence().mapNotNull { Regex("^h(\\d+)d-").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }.distinct().sorted().forEach { days ->
                val cells = exploration.summaries.filter { it.name.startsWith("h${days}d-") }
                appendLine("| $days days | ${cells.size} | ${decimal(cells.map(::raidsPerDay).averageOrZero())} | ${decimal(cells.map { it.distinctRecruits.toDouble() }.averageOrZero())} | ${percent(cells.map { it.dominantRecruitShare }.averageOrZero())} | ${percent(cells.map { it.equipmentCoverage }.averageOrZero())} |")
            }
            appendLine()
            appendLine("## One-factor sensitivity")
            appendLine()
            appendLine("| Factor | Cells | Dispatch range | Dominant-share range | Peak-threat range |")
            appendLine("|---|---:|---:|---:|---:|")
            listOf("aggression", "reserve", "distance", "idle", "learning").forEach { factor ->
                val cells = exploration.summaries.filter { it.name.startsWith("sensitivity-$factor-") }
                if (cells.isNotEmpty()) appendLine("| $factor | ${cells.size} | ${cells.minOf { it.campaignsDispatched }}–${cells.maxOf { it.campaignsDispatched }} | ${percent(cells.minOf { it.dominantRecruitShare })}–${percent(cells.maxOf { it.dominantRecruitShare })} | ${decimal(cells.minOf { it.peakCampaignThreat })}–${decimal(cells.maxOf { it.peakCampaignThreat })} |")
            }
            appendLine()
            appendLine("## Findings and candidate changes")
            appendLine()
            exploration.findings.forEach { finding ->
                appendLine("### ${finding.priority}. ${finding.topic}")
                appendLine()
                appendLine(finding.observation)
                appendLine()
                appendLine("Evidence: ${finding.evidenceScenarios.joinToString { "`$it`" }}.")
                appendLine()
                appendLine("Candidate: ${finding.candidate}")
                appendLine()
                appendLine("Risk: ${finding.risk} Confidence: **${finding.confidence}**.")
                appendLine()
            }
            appendLine("## Interpretation boundary")
            appendLine()
            appendLine("Minecraft pathfinding and combat are bounded observations. Economy, selection, learning, travel, return, and reconciliation are the same compiled engine used by Forge.")
        })
    }

    private fun raidsPerDay(summary: ExperimentSummary) = if (summary.ticks == 0L) 0.0 else summary.campaignsDispatched * TICKS_PER_DAY.toDouble() / summary.ticks
    private fun percent(value: Double) = "%.1f%%".format(value * 100.0)
    private fun decimal(value: Double) = "%.2f".format(value)
    private fun Collection<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()

    companion object { const val TICKS_PER_DAY = 24_000L }
}
