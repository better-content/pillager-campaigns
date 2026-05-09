package com.gerald.pillagercampaigns

import net.minecraftforge.common.ForgeConfigSpec

object PillagerCampaignsConfig {
    val SPEC: ForgeConfigSpec

    val enabled: ForgeConfigSpec.BooleanValue
    val disableVanillaPatrolSpawning: ForgeConfigSpec.BooleanValue
    val baseDiscoveryIntervalTicks: ForgeConfigSpec.IntValue
    val baseDiscoveryRadiusChunks: ForgeConfigSpec.IntValue
    val maxBaseDiscoveriesPerTick: ForgeConfigSpec.IntValue
    val maxBaseDiscoveryProbePointsPerPlayer: ForgeConfigSpec.IntValue
    val baseGridSpacingChunks: ForgeConfigSpec.IntValue
    val baseGridJitterChunks: ForgeConfigSpec.IntValue
    val baseSpawnChancePercent: ForgeConfigSpec.IntValue
    val baseMinSpawnDistanceChunks: ForgeConfigSpec.IntValue
    val baseMaterializationSearchRadiusChunks: ForgeConfigSpec.IntValue
    val materializationJobsPerTick: ForgeConfigSpec.IntValue
    val materializationCandidateChecksPerJob: ForgeConfigSpec.IntValue
    val materializationMaxMillisPerTick: ForgeConfigSpec.DoubleValue
    val materializationPlacementCooldownTicks: ForgeConfigSpec.IntValue
    val campaignDispatchBasesPerTick: ForgeConfigSpec.IntValue
    val structureBaseIds: ForgeConfigSpec.ConfigValue<List<out String>>
    val campaignTickInterval: ForgeConfigSpec.IntValue
    val campaignSpeedTicksPerChunk: ForgeConfigSpec.IntValue
    val maxCampaignsPerBase: ForgeConfigSpec.IntValue
    val maxCampaignDistanceChunks: ForgeConfigSpec.IntValue
    val materializeDistanceChunks: ForgeConfigSpec.IntValue

    init {
        val b = ForgeConfigSpec.Builder()
        b.push("scheduler")
        enabled = b.define("enabled", true)
        disableVanillaPatrolSpawning = b.define("disable_vanilla_patrol_spawning", true)
        baseDiscoveryIntervalTicks = b.defineInRange("base_discovery_interval_ticks", 200, 20, 12000)
        baseDiscoveryRadiusChunks = b.defineInRange("base_discovery_radius_chunks", 1000, 8, 4096)
        maxBaseDiscoveriesPerTick = b.defineInRange("max_base_discoveries_per_tick", 2, 1, 32)
        maxBaseDiscoveryProbePointsPerPlayer = b.defineInRange("max_base_discovery_probe_points_per_player", 6, 1, 16)
        baseGridSpacingChunks = b.defineInRange("base_grid_spacing_chunks", 64, 16, 512)
        baseGridJitterChunks = b.defineInRange("base_grid_jitter_chunks", 18, 0, 128)
        baseSpawnChancePercent = b.defineInRange("base_spawn_chance_percent", 35, 1, 100)
        baseMinSpawnDistanceChunks = b.defineInRange("base_min_spawn_distance_chunks", 24, 0, 512)
        baseMaterializationSearchRadiusChunks = b.defineInRange("base_materialization_search_radius_chunks", 8, 1, 32)
        materializationJobsPerTick = b.defineInRange("materialization_jobs_per_tick", 1, 1, 8)
        materializationCandidateChecksPerJob = b.defineInRange("materialization_candidate_checks_per_job", 16, 1, 256)
        materializationMaxMillisPerTick = b.defineInRange("materialization_max_millis_per_tick", 4.0, 0.5, 50.0)
        materializationPlacementCooldownTicks = b.defineInRange("materialization_placement_cooldown_ticks", 200, 20, 12000)
        campaignDispatchBasesPerTick = b.defineInRange("campaign_dispatch_bases_per_tick", 64, 1, 4096)
        b.pop()

        b.push("campaigns")
        campaignTickInterval = b.defineInRange("campaign_tick_interval", 20, 20, 200)
        campaignSpeedTicksPerChunk = b.defineInRange("campaign_speed_ticks_per_chunk", 120, 100, 7200) // 10 chunks/min default
        maxCampaignsPerBase = b.defineInRange("max_campaigns_per_base", 1, 0, 8)
        maxCampaignDistanceChunks = b.defineInRange("max_campaign_distance_chunks", 1000, 4, 4096)
        materializeDistanceChunks = b.defineInRange("materialize_distance_chunks", 6, 1, 24)
        structureBaseIds = b.defineListAllowEmpty(
            "structure_base_ids",
            listOf(
                "minecraft:pillager_outpost",
                "takesapillage:bastille",
                "takesapillage:pillager_camp",
                "towns_and_towers:exclusives/pillager_outpost_classic",
                "towns_and_towers:exclusives/pillager_outpost_iberian",
                "towns_and_towers:exclusives/pillager_outpost_mediterranean",
                "towns_and_towers:exclusives/pillager_outpost_oriental",
                "towns_and_towers:exclusives/pillager_outpost_rustic",
                "towns_and_towers:exclusives/pillager_outpost_swedish",
                "towns_and_towers:exclusives/pillager_outpost_tudor",
                "towns_and_towers:pillager_outpost_badlands",
                "towns_and_towers:pillager_outpost_beach",
                "towns_and_towers:pillager_outpost_birch_forest",
                "towns_and_towers:pillager_outpost_desert",
                "towns_and_towers:pillager_outpost_flower_forest",
                "towns_and_towers:pillager_outpost_forest",
                "towns_and_towers:pillager_outpost_grove",
                "towns_and_towers:pillager_outpost_jungle",
                "towns_and_towers:pillager_outpost_meadow",
                "towns_and_towers:pillager_outpost_mushroom_fields",
                "towns_and_towers:pillager_outpost_ocean",
                "towns_and_towers:pillager_outpost_old_growth_taiga",
                "towns_and_towers:pillager_outpost_savanna",
                "towns_and_towers:pillager_outpost_savanna_plateau",
                "towns_and_towers:pillager_outpost_snowy_beach",
                "towns_and_towers:pillager_outpost_snowy_plains",
                "towns_and_towers:pillager_outpost_snowy_slopes",
                "towns_and_towers:pillager_outpost_snowy_taiga",
                "towns_and_towers:pillager_outpost_sparse_jungle",
                "towns_and_towers:pillager_outpost_sunflower_plains",
                "towns_and_towers:pillager_outpost_swamp",
                "towns_and_towers:pillager_outpost_taiga",
                "towns_and_towers:pillager_outpost_wooded_badlands"
            ),
        ) { it is String && it.contains(":") }
        b.pop()
        SPEC = b.build()
    }
}
