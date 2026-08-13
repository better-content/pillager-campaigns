package com.bettercontent.pillagercampaigns

import net.minecraftforge.common.ForgeConfigSpec

/** Scalar tuning only. Recruit, biome and material inputs live in reloadable data. */
object PillagerCampaignsConfig {
    val SPEC: ForgeConfigSpec
    val enabled: ForgeConfigSpec.BooleanValue
    val disableVanillaPatrolSpawning: ForgeConfigSpec.BooleanValue
    val schedulerIntervalTicks: ForgeConfigSpec.IntValue
    val workBudgetPerTick: ForgeConfigSpec.IntValue
    val territoryRadiusChunks: ForgeConfigSpec.IntValue
    val warningBandChunks: ForgeConfigSpec.IntValue
    val gridSpacingChunks: ForgeConfigSpec.IntValue
    val gridJitterChunks: ForgeConfigSpec.IntValue
    val spawnChancePercent: ForgeConfigSpec.IntValue
    val minSpawnDistanceChunks: ForgeConfigSpec.IntValue
    val initialReserve: ForgeConfigSpec.IntValue
    val initialAggression: ForgeConfigSpec.IntValue
    val minimumAggression: ForgeConfigSpec.IntValue
    val maximumAggression: ForgeConfigSpec.IntValue
    val idleReturnTicks: ForgeConfigSpec.IntValue
    val respawnProtectionTicks: ForgeConfigSpec.IntValue
    val deathProtectionTicks: ForgeConfigSpec.IntValue
    val resolvedRetentionTicks: ForgeConfigSpec.IntValue
    val materializeDistanceChunks: ForgeConfigSpec.IntValue
    val travelTicksPerChunk: ForgeConfigSpec.IntValue
    val raidCooldownTicks: ForgeConfigSpec.IntValue
    val captainRecoveryTicks: ForgeConfigSpec.IntValue
    val captainSuccessRecoveryTicks: ForgeConfigSpec.IntValue
    val maximumSquadMembers: ForgeConfigSpec.IntValue
    val idleReturnAggressionGrowth: ForgeConfigSpec.IntValue
    val defeatAggressionGrowth: ForgeConfigSpec.IntValue
    val victoryAggressionGrowth: ForgeConfigSpec.IntValue
    val recruitBaseTicksPerThreat: ForgeConfigSpec.DoubleValue
    val recruitHabitabilityPenaltyTicksPerThreat: ForgeConfigSpec.DoubleValue
    val mobilizationBaseTicksPerThreat: ForgeConfigSpec.DoubleValue
    val mobilizationFrictionTicksPerThreat: ForgeConfigSpec.DoubleValue
    val extractionTicksMultiplier: ForgeConfigSpec.DoubleValue
    val sustenancePerThreatChunk: ForgeConfigSpec.DoubleValue
    val munitionsPerRangedThreatChunk: ForgeConfigSpec.DoubleValue
    val maintenancePerEquipmentChunk: ForgeConfigSpec.DoubleValue
    val deficitGraceChunks: ForgeConfigSpec.DoubleValue
    val attritionPerDeficitChunk: ForgeConfigSpec.DoubleValue
    val equipmentWearPerFrictionChunk: ForgeConfigSpec.DoubleValue
    val forageUnitsPerDeficitChunk: ForgeConfigSpec.DoubleValue
    val shortageRetreatBaseChunks: ForgeConfigSpec.DoubleValue
    val shortageAggressionRunwayChunks: ForgeConfigSpec.DoubleValue
    val warbandLearningRate: ForgeConfigSpec.DoubleValue
    val captainLearningRate: ForgeConfigSpec.DoubleValue
    val threatLearningRate: ForgeConfigSpec.DoubleValue

    init {
        val b = ForgeConfigSpec.Builder()
        b.push("scheduler")
        enabled = b.define("enabled", true)
        disableVanillaPatrolSpawning = b.define("disable_vanilla_patrol_spawning", true)
        schedulerIntervalTicks = b.defineInRange("interval_ticks", 20, 1, 1200)
        workBudgetPerTick = b.defineInRange("work_budget_per_tick", 64, 1, 4096)
        b.pop()

        b.push("territory")
        territoryRadiusChunks = b.defineInRange("radius_chunks", 32, 8, 32)
        warningBandChunks = b.defineInRange("warning_band_chunks", 4, 1, 12)
        gridSpacingChunks = b.defineInRange("grid_spacing_chunks", 64, 16, 512)
        gridJitterChunks = b.defineInRange("grid_jitter_chunks", 18, 0, 128)
        spawnChancePercent = b.defineInRange("spawn_chance_percent", 35, 1, 100)
        minSpawnDistanceChunks = b.defineInRange("min_spawn_distance_chunks", 24, 0, 512)
        b.pop()

        b.push("economy")
        initialReserve = b.defineInRange("initial_reserve", 18, 0, 10000)
        initialAggression = b.defineInRange("initial_aggression", 6, 0, 1000)
        minimumAggression = b.defineInRange("minimum_aggression", 6, 0, 1000)
        maximumAggression = b.defineInRange("maximum_aggression", 18, 1, 1000)
        b.pop()

        b.push("campaigns")
        idleReturnTicks = b.defineInRange("idle_return_ticks", 12_000, 200, 144_000)
        respawnProtectionTicks = b.defineInRange("respawn_protection_ticks", 6_000, 0, 144_000)
        deathProtectionTicks = b.defineInRange("death_protection_ticks", 24_000, 0, 288_000)
        resolvedRetentionTicks = b.defineInRange("resolved_retention_ticks", 24_000, 200, 288_000)
        materializeDistanceChunks = b.defineInRange("materialize_distance_chunks", 6, 1, 24)
        travelTicksPerChunk = b.defineInRange("travel_ticks_per_chunk", 120, 1, 24_000)
        raidCooldownTicks = b.defineInRange("raid_cooldown_ticks", 24_000, 0, 288_000)
        captainRecoveryTicks = b.defineInRange("captain_recovery_ticks", 6_000, 0, 288_000)
        captainSuccessRecoveryTicks = b.defineInRange("captain_success_recovery_ticks", 2_400, 0, 288_000)
        maximumSquadMembers = b.defineInRange("maximum_squad_members", 6, 2, 24)
        idleReturnAggressionGrowth = b.defineInRange("idle_aggression_growth", 1, -100, 100)
        defeatAggressionGrowth = b.defineInRange("defeat_aggression_growth", 1, -100, 100)
        victoryAggressionGrowth = b.defineInRange("victory_aggression_growth", -1, -100, 100)
        b.pop()

        b.push("strategic_economy")
        recruitBaseTicksPerThreat = b.defineInRange("recruit_base_ticks_per_threat", 1_260.0, 1.0, 1_000_000.0)
        recruitHabitabilityPenaltyTicksPerThreat = b.defineInRange("recruit_habitability_penalty_ticks_per_threat", 1_680.0, 0.0, 1_000_000.0)
        mobilizationBaseTicksPerThreat = b.defineInRange("mobilization_base_ticks_per_threat", 240.0, 1.0, 1_000_000.0)
        mobilizationFrictionTicksPerThreat = b.defineInRange("mobilization_friction_ticks_per_threat", 480.0, 0.0, 1_000_000.0)
        extractionTicksMultiplier = b.defineInRange("extraction_ticks_multiplier", 0.5, 0.0, 100.0)
        b.pop()

        b.push("logistics")
        sustenancePerThreatChunk = b.defineInRange("sustenance_per_threat_chunk", 0.018, 0.0, 100.0)
        munitionsPerRangedThreatChunk = b.defineInRange("munitions_per_ranged_threat_chunk", 0.012, 0.0, 100.0)
        maintenancePerEquipmentChunk = b.defineInRange("maintenance_per_equipment_chunk", 0.025, 0.0, 100.0)
        deficitGraceChunks = b.defineInRange("deficit_grace_chunks", 3.0, 0.0, 10_000.0)
        attritionPerDeficitChunk = b.defineInRange("attrition_per_deficit_chunk", 0.035, 0.0, 1.0)
        equipmentWearPerFrictionChunk = b.defineInRange("equipment_wear_per_friction_chunk", 0.004, 0.0, 1.0)
        forageUnitsPerDeficitChunk = b.defineInRange("forage_units_per_deficit_chunk", 0.75, 0.0, 1_000.0)
        shortageRetreatBaseChunks = b.defineInRange("shortage_retreat_base_chunks", 6.0, 0.0, 10_000.0)
        shortageAggressionRunwayChunks = b.defineInRange("shortage_aggression_runway_chunks", 18.0, 0.0, 10_000.0)
        b.pop()

        b.push("learning")
        warbandLearningRate = b.defineInRange("warband_rate", 0.05, 0.0, 1.0)
        captainLearningRate = b.defineInRange("captain_rate", 0.10, 0.0, 1.0)
        threatLearningRate = b.defineInRange("threat_rate", 0.10, 0.0, 1.0)
        b.pop()
        SPEC = b.build()
    }
}
