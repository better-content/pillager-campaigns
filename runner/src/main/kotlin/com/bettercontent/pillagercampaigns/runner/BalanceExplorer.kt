package com.bettercontent.pillagercampaigns.runner

import com.gerald.warband.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.random.Random

class BalanceExplorer(private val json: Json = Json { prettyPrint = true; encodeDefaults = true }) {
    data class Settings(
        val horizonsDays: List<Long> = listOf(1L, 3L, 10L, 30L),
        val seeds: List<Long> = listOf(11L, 29L, 47L),
        val includeSensitivity: Boolean = true,
    )

    fun explore(runtimeSpecFile: File, output: File, settings: Settings = Settings()): BalanceExploration {
        val runtimeSpec = json.decodeFromString<WarbandRuntimeSpec>(runtimeSpecFile.readText())
        runtimeSpec.requireValidRevision()
        require(runtimeSpec.recruits.isNotEmpty()) { "runtime specification has no recruits" }
        val environments = runtimeSpec.environmentModel.samples.ifEmpty { STANDARD_ENVIRONMENTS }
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
                            scenario(name, days * TICKS_PER_DAY, runtimeSpec, environment, assumptions, seed),
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
                summaries += runner.run(scenario("sensitivity-aggression-$aggression", 10 * TICKS_PER_DAY, runtimeSpec, baseEnvironment, nominal, 11L, aggression = aggression), false).summary
            }
            listOf(6.0, 18.0, 54.0).forEach { reserve ->
                summaries += runner.run(scenario("sensitivity-reserve-${reserve.toInt()}", 10 * TICKS_PER_DAY, runtimeSpec, baseEnvironment, nominal, 11L, reserve = reserve), false).summary
            }
            listOf(6, 16, 32).forEach { distance ->
                summaries += runner.run(scenario("sensitivity-distance-$distance", 10 * TICKS_PER_DAY, runtimeSpec, baseEnvironment, nominal, 11L, distance = distance), false).summary
            }
            val disengagement = nominal.copy(campaignDamagePerEngagement = 0.0, playerDamagePerEngagement = 0.0, engagementsBeforeDisengage = 1)
            listOf(6_000L, 12_000L, 24_000L).forEach { idle ->
                summaries += runner.run(scenario("sensitivity-idle-$idle", 10 * TICKS_PER_DAY, runtimeSpec, baseEnvironment, disengagement, 11L, rules = runtimeSpec.rules.copy(idleReturnTicks = idle)), false).summary
            }
            listOf(0.0, .05, .15).forEach { rate ->
                val rules = runtimeSpec.rules.copy(warbandLearningRate = rate, captainLearningRate = rate * 2.0, threatLearningRate = rate * 2.0)
                summaries += runner.run(scenario("sensitivity-learning-$rate", 10 * TICKS_PER_DAY, runtimeSpec, baseEnvironment, nominal, 11L, rules = rules), false).summary
            }
            listOf(0, 24, 96).forEach { items ->
                summaries += runner.run(scenario("sensitivity-supply-$items", 10 * TICKS_PER_DAY, runtimeSpec, baseEnvironment, nominal, 11L, supplyItems = items), false).summary
            }
        }

        val exploration = BalanceExploration(runtimeSpec.revision, summaries, findings(summaries, runtimeSpec))
        write(exploration, output)
        return exploration
    }

    internal fun scenario(
        name: String,
        duration: Long,
        runtimeSpec: WarbandRuntimeSpec,
        environment: EnvironmentTraits,
        assumptions: BoundedAssumptions,
        seed: Long,
        aggression: Int = 6,
        reserve: Double = 18.0,
        distance: Int = 12,
        rules: CoreRules = runtimeSpec.rules,
        supplyItems: Int = 24,
    ): ExperimentScenario {
        val preferences = initialPreferences(seed, environment)
        val ledger = runtimeSpec.materials.sortedBy(MaterialDefinition::id).take(3).associateTo(linkedMapOf()) { it.id to 8.0 }
        val warband = WarbandState(
            "warband", "faction", ChunkPosition("minecraft:overworld", 0, 0), rules.capacity(environment).toDouble(), reserve,
            aggression = aggression, environment = environment, preferences = preferences, materialLedger = ledger,
        )
        val usefulResources = listOf<(ResourceDefinition) -> Double>(
            { it.unitsPerItem.sustenance }, { it.unitsPerItem.munitions },
            { it.unitsPerItem.maintenance }, { it.unitsPerItem.recovery },
        ).mapNotNull { value -> runtimeSpec.resources.maxByOrNull { value(it) / it.mass }?.takeIf { value(it) > 0.0 } }.distinctBy { it.itemId }
        if (usefulResources.isNotEmpty() && supplyItems > 0) usefulResources.forEachIndexed { index, resource ->
            warband.stockpile[resource.itemId] = supplyItems / usefulResources.size + if (index < supplyItems % usefulResources.size) 1 else 0
        }
        val target = ChunkPosition("minecraft:overworld", distance, (distance / 2).coerceAtLeast(1))
        val terrain = routeTerrain(warband.rally, target, environment)
        return ExperimentScenario(
            name, duration, 20L,
            CoreSnapshot(
                sequence = seed,
                factions = linkedMapOf("faction" to FactionState("faction", "Exploration", seed.toInt())),
                warbands = linkedMapOf(warband.id to warband),
                officers = linkedMapOf("captain" to OfficerState("captain", "faction", warband.id)),
                territoryRelations = linkedMapOf(
                    "${warband.id}|player" to TerritoryRelationState(warband.id, "player", TerritoryStatus.HOSTILE),
                ),
            ),
            runtimeSpec.copy(rules = rules).withComputedRevision(),
            listOf(PlayerFact("player", target)),
            terrain,
            assumptions,
        )
    }

    private fun initialPreferences(seed: Long, traits: EnvironmentTraits): MutableMap<String, Double> {
        val random = Random(seed)
        val env = traits.bounded()
        return mutableMapOf(
            "durability" to env.mineralPotential + random.nextDouble(-0.15, 0.15),
            "damage" to (1.0 - env.habitability) + random.nextDouble(-0.15, 0.15),
            "mobility" to (1.0 - env.travelFriction) + random.nextDouble(-0.15, 0.15),
            "range" to env.travelFriction + random.nextDouble(-0.15, 0.15),
            "conservation" to env.habitability + random.nextDouble(-0.15, 0.15),
            "exotic" to env.exoticPotential + random.nextDouble(-0.15, 0.15),
        )
    }

    private fun routeTerrain(start: ChunkPosition, target: ChunkPosition, environment: EnvironmentTraits): List<TerrainObservation> {
        val values = linkedMapOf<String, TerrainObservation>()
        fun add(position: ChunkPosition, traits: EnvironmentTraits) { values["${position.x}:${position.z}"] = TerrainObservation(position, traits.bounded()) }
        var cursor = start
        while (cursor != target) {
            val step = CampaignGeometry.stepToward(cursor.x, cursor.z, target.x, target.z)
            cursor = cursor.copy(x = step.first, z = step.second)
            add(cursor, environment.copy(travelFriction = environment.travelFriction + 0.15))
        }
        var x = start.x
        while (x != target.x) { x += if (target.x > x) 1 else -1; add(ChunkPosition(start.dimension, x, start.z), environment.copy(biomass = environment.biomass + 0.2, travelFriction = environment.travelFriction - 0.15)) }
        var z = start.z
        while (z != target.z) { z += if (target.z > z) 1 else -1; add(ChunkPosition(start.dimension, target.x, z), environment.copy(biomass = environment.biomass + 0.2, travelFriction = environment.travelFriction - 0.15)) }
        return values.values.toList()
    }

    private fun findings(summaries: List<ExperimentSummary>, runtimeSpec: WarbandRuntimeSpec): List<BalanceFinding> {
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
            "The worst short-horizon Core scenario assigns ${percent(dominant.dominantRecruitShare)} of members to one recruit; the synthetic-envelope mean is ${percent(early.map { it.dominantRecruitShare }.averageOrZero())}. ${mature.flatMap { it.recruitCounts.keys }.distinct().size} of ${runtimeSpec.recruits.size} runtime-spec recruits appear across mature scenarios, with ${mature.minOfOrNull(ExperimentSummary::distinctRecruits) ?: 0}–${mature.maxOfOrNull(ExperimentSummary::distinctRecruits) ?: 0} distinct recruits per scenario.",
            early.sortedByDescending(ExperimentSummary::dominantRecruitShare).take(3).map(ExperimentSummary::name),
            "Retain the current combination of within-squad marginal utility and bounded, decaying deployment memory.",
            "Too much penalty can force weak recruits despite strong environmental preferences.", "high",
        )

        val leastMaterialVariety = mature.takeIf { runtimeSpec.materials.isNotEmpty() }
            ?.minByOrNull { it.extractedMaterialCounts.size }
        if (leastMaterialVariety != null) findings += BalanceFinding(
            3, "Material and equipment diversity",
            "At least one mature cell extracts only ${leastMaterialVariety.extractedMaterialCounts.size} material type(s) and manufactures ${leastMaterialVariety.manufacturedEquipmentCounts.size} equipment formulation(s).",
            mature.sortedBy { it.extractedMaterialCounts.size }.take(3).map(ExperimentSummary::name),
            "Retain recent extraction frequency as a bounded utility term; compare the observed material floor against actual TCon compatibility before increasing diversity pressure.",
            "Scarcity pressure must not select unaffordable or environmentally impossible materials.", "high",
        )

        val fastest = baseline.maxByOrNull { raidsPerDay(it) }
        val slowest = baseline.filter { it.ticks >= 3 * TICKS_PER_DAY }.minByOrNull { raidsPerDay(it) }
        if (fastest != null && slowest != null) findings += BalanceFinding(
            4, "Pressure cadence",
            "Core cadence ranges from ${decimal(raidsPerDay(slowest))} to ${decimal(raidsPerDay(fastest))} dispatches per 24,000 Core ticks across the synthetic input envelope.",
            listOf(slowest.name, fastest.name),
            "Make cooldown recovery a smooth function of aggression, surviving threat, and recent completed-cycle duration.",
            "Faster recycling can become oppressive when several warbands target one travel corridor.", "medium",
        )

        val equipment = mature.takeIf { runtimeSpec.equipmentPlatforms.isNotEmpty() && runtimeSpec.materials.isNotEmpty() }
            ?.minByOrNull(ExperimentSummary::equipmentCoverage)
        if (equipment != null) {
            val environmentFormulations = mature.groupBy { summary ->
                Regex("^h30d-e([0-9]+)-").find(summary.name)?.groupValues?.get(1) ?: "unknown"
            }.values.map { cells -> cells.flatMap { it.manufacturedEquipmentCounts.keys }.toSet() }
            val union = environmentFormulations.flatten().toSet()
            val shared = environmentFormulations.reduceOrNull(Set<String>::intersect).orEmpty()
            findings += BalanceFinding(
            5, "Equipment expression",
            "The weakest mature Core scenario equips ${percent(equipment.equipmentCoverage)} of dispatched members while retaining ${equipment.armoryItems} armory items. Synthetic environments manufacture ${environmentFormulations.minOf { it.size }}–${environmentFormulations.maxOf { it.size }} formulations each; ${union.size - shared.size} of ${union.size} formulations are environment-specific rather than universal. Across these scenarios the modeled functional mix is ${mature.flatMap { it.armamentActionCounts.entries }.groupingBy { it.key }.fold(0) { total, entry -> total + entry.value }.toSortedMap()}, and mean environment/recruit-weighted armament utility spans ${decimal(mature.minOf { it.meanArmamentUtility })}–${decimal(mature.maxOf { it.meanArmamentUtility })}.",
            mature.sortedBy(ExperimentSummary::equipmentCoverage).take(3).map(ExperimentSummary::name),
            "Keep capability-gain and action-compatibility assignment; tune manufacturing throughput and wear before changing selection utility.",
            "Aggressive stock rotation can erase the identity created by material preferences.", "medium",
        )
        }

        val idle = summaries.filter { it.name.startsWith("sensitivity-idle-") }
        if (idle.isNotEmpty()) findings += BalanceFinding(
            6, "Idle-return aggression feedback",
            "Idle-return sensitivity changes completed campaigns from ${idle.minOf { it.campaignsReturned }} to ${idle.maxOf { it.campaignsReturned }} over ten days; final aggression spans ${idle.flatMap { it.aggressionByWarband.values }.minOrNull()}–${idle.flatMap { it.aggressionByWarband.values }.maxOrNull()}.",
            idle.map(ExperimentSummary::name),
            "Keep the aggression increase on disengaged return, but scale it by idle duration relative to travel time instead of a flat increment.",
            "Scaling too softly makes kiting a permanent suppression strategy; scaling too strongly creates runaway raid budgets.", "medium",
        )
        val supply = summaries.filter {
            it.name.startsWith("sensitivity-supply-") && it.supplyObservationCount > 0 && it.meanSupplySatisfaction != null
        }
        if (runtimeSpec.resources.isNotEmpty() && supply.isNotEmpty()) findings += BalanceFinding(
            7, "Logistics pressure and recoverability",
            "Across supply sensitivity, observed mean segment satisfaction spans ${percent(supply.mapNotNull { it.meanSupplySatisfaction }.min())}–${percent(supply.mapNotNull { it.meanSupplySatisfaction }.max())}. Nominal-aggression cells avoid lethal attrition, while the high-aggression sensitivity sustains ${summaries.filter { it.name == "sensitivity-aggression-18" }.maxOfOrNull { it.attritionLosses } ?: 0} losses and retains ${summaries.filter { it.name == "sensitivity-aggression-18" }.maxOfOrNull { it.recoverableCaches } ?: 0} recoverable caches before withdrawing.",
            supply.map(ExperimentSummary::name) + listOf("sensitivity-aggression-18"),
            "Tune provisioning demand and forage debt together; preserve the grace interval and aggression-scaled retreat threshold.",
            "Over-supplying erases interception and preparation; under-supplying turns distant campaigns into automatic retreats.", "high",
        )
        val routed = baseline.filter { it.campaignsDispatched > 0 }
        if (runtimeSpec.environmentModel.samples.distinct().size >= 2 && routed.map { it.meanRouteChunks }.distinct().size >= 2) findings += BalanceFinding(
            8, "Environmental route expression",
            "Formula-selected campaign routes average ${decimal(routed.map { it.meanRouteChunks }.averageOrZero())} chunks, spanning ${decimal(routed.minOf { it.meanRouteChunks })}–${decimal(routed.maxOf { it.meanRouteChunks })}; route length and supply consumption now respond to the same sampled terrain observations.",
            routed.sortedByDescending { it.meanRouteChunks }.take(3).map(ExperimentSummary::name),
            "Keep noise-biome corridor sampling unloaded-safe and tune forage value against friction rather than adding preferred biome categories.",
            "Too much forage utility produces implausible detours; too little makes environment cosmetic.", "medium",
        )
        if (early.isNotEmpty() && mature.isNotEmpty()) findings += BalanceFinding(
            9, "Unmaterialized warband power growth",
            "Mean peak deployed threat grows from ${decimal(early.map { it.peakCampaignThreat }.averageOrZero())} at three days to ${decimal(mature.map { it.peakCampaignThreat }.averageOrZero())} at thirty days, while mature recruit diversity rises to ${decimal(mature.map { it.distinctRecruits.toDouble() }.averageOrZero())} types per cell.",
            listOfNotNull(early.maxByOrNull { it.peakCampaignThreat }?.name, mature.maxByOrNull { it.peakCampaignThreat }?.name),
            "Retain reserve growth, learned threat, manufacturing, and aggression feedback as independent continuous inputs; avoid a discrete late-game tier switch.",
            "Peak power can grow while cadence falls, so both dimensions must remain visible in balance reports.", "high",
        )
        return findings.sortedBy(BalanceFinding::priority)
    }

    private fun write(exploration: BalanceExploration, output: File) {
        output.mkdirs()
        output.resolve("exploration.json").writeText(json.encodeToString(exploration))
        output.resolve("scope.json").writeText(json.encodeToString(exploration.boundary))
        output.resolve("summaries.csv").writeText(buildString {
            appendLine("name,ticks,dispatched,returned,active,raid_pool,stockpile_items,resources_acquired,resources_consumed,supply_satisfaction,supply_observations,shortage_returns,attrition_losses,recoverable_caches,mean_route_chunks,mean_equipment_durability,distinct_recruits,dominant_share,longest_streak,equipment_coverage,mean_cycle_ticks,preference_drift,model,minecraft_simulation,synthetic_external_observations")
            exploration.summaries.forEach { summary ->
                appendLine(listOf(summary.name, summary.ticks, summary.campaignsDispatched, summary.campaignsReturned, summary.activeCampaigns, summary.raidPool, summary.stockpileItems, summary.resourcesAcquired, summary.resourcesConsumed, summary.meanSupplySatisfaction ?: "", summary.supplyObservationCount, summary.shortageReturns, summary.attritionLosses, summary.recoverableCaches, summary.meanRouteChunks, summary.meanEquipmentDurability, summary.distinctRecruits, summary.dominantRecruitShare, summary.longestRecruitStreak, summary.equipmentCoverage, summary.meanCampaignCycleTicks, summary.preferenceDrift, summary.boundary.model, summary.boundary.minecraftSimulation, summary.boundary.externalObservationsAreSynthetic).joinToString(","))
            }
        })
        output.resolve("balance-notes.md").writeText(buildString {
            appendLine("# Warband Core Scenario Analysis")
            appendLine()
            appendLine("Runtime specification: `${exploration.runtimeSpecRevision}`")
            appendLine()
            appendLine("**Not a Minecraft simulation.** ${exploration.boundary.statement}")
            appendLine()
            appendLine("The report covers ${exploration.summaries.size} deterministic Core scenarios under explicit synthetic observations and assumptions.")
            appendLine()
            appendLine("## Longitudinal overview")
            appendLine()
            appendLine("| Core-tick horizon | Scenarios | Dispatches/24,000 Core ticks | Distinct recruits | Dominant share | Equipment coverage |")
            appendLine("|---:|---:|---:|---:|---:|---:|")
            exploration.summaries.asSequence().mapNotNull { Regex("^h(\\d+)d-").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }.distinct().sorted().forEach { days ->
                val cells = exploration.summaries.filter { it.name.startsWith("h${days}d-") }
                appendLine("| ${days * TICKS_PER_DAY} ticks | ${cells.size} | ${decimal(cells.map(::raidsPerDay).averageOrZero())} | ${decimal(cells.map { it.distinctRecruits.toDouble() }.averageOrZero())} | ${percent(cells.map { it.dominantRecruitShare }.averageOrZero())} | ${percent(cells.map { it.equipmentCoverage }.averageOrZero())} |")
            }
            appendLine()
            appendLine("## One-factor sensitivity")
            appendLine()
            appendLine("| Factor | Cells | Dispatch range | Dominant-share range | Peak-threat range |")
            appendLine("|---|---:|---:|---:|---:|")
            listOf("aggression", "reserve", "distance", "idle", "learning", "supply").forEach { factor ->
                val cells = exploration.summaries.filter {
                    it.name.startsWith("sensitivity-$factor-") && (factor != "supply" || it.supplyObservationCount > 0)
                }
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
                appendLine("Risk: ${finding.risk} Scenario-evidence confidence: **${finding.confidence}**.")
                appendLine()
            }
            appendLine("## Interpretation boundary")
            appendLine()
            appendLine(exploration.boundary.statement)
            appendLine("External frames are authored inputs. Results describe deterministic Warband Core responses to those inputs and make no claim about Minecraft outcomes or probabilities.")
        })
    }

    private fun raidsPerDay(summary: ExperimentSummary) = if (summary.ticks == 0L) 0.0 else summary.campaignsDispatched * TICKS_PER_DAY.toDouble() / summary.ticks
    private fun percent(value: Double) = "%.1f%%".format(value * 100.0)
    private fun decimal(value: Double) = "%.2f".format(value)
    private fun Collection<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()

    companion object {
        const val TICKS_PER_DAY = 24_000L
        val STANDARD_ENVIRONMENTS = listOf(
            EnvironmentTraits(habitability = .8, biomass = .85, mineralPotential = .35, exoticPotential = .2, travelFriction = .25),
            EnvironmentTraits(habitability = .3, biomass = .2, mineralPotential = .9, exoticPotential = .35, travelFriction = .8),
            EnvironmentTraits(habitability = .5, biomass = .45, mineralPotential = .55, exoticPotential = .95, travelFriction = .55),
        )
    }
}
