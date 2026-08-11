package com.gerald.pillagercampaigns

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
        b.pop()

        b.push("learning")
        warbandLearningRate = b.defineInRange("warband_rate", 0.05, 0.0, 1.0)
        captainLearningRate = b.defineInRange("captain_rate", 0.10, 0.0, 1.0)
        threatLearningRate = b.defineInRange("threat_rate", 0.10, 0.0, 1.0)
        b.pop()
        SPEC = b.build()
    }
}
