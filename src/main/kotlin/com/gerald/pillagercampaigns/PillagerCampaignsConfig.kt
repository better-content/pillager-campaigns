package com.gerald.pillagercampaigns

import net.minecraftforge.common.ForgeConfigSpec

object PillagerCampaignsConfig {
    val SPEC: ForgeConfigSpec

    val enabled: ForgeConfigSpec.BooleanValue
    val disableVanillaPatrolSpawning: ForgeConfigSpec.BooleanValue
    val warbandDiscoveryIntervalTicks: ForgeConfigSpec.IntValue
    val warbandDiscoveryRadiusChunks: ForgeConfigSpec.IntValue
    val warbandRegistrationsPerTick: ForgeConfigSpec.IntValue
    val warbandGridSpacingChunks: ForgeConfigSpec.IntValue
    val warbandGridJitterChunks: ForgeConfigSpec.IntValue
    val warbandSpawnChancePercent: ForgeConfigSpec.IntValue
    val warbandMinSpawnDistanceChunks: ForgeConfigSpec.IntValue
    val campaignDispatchWarbandsPerTick: ForgeConfigSpec.IntValue
    val structureWarbandIds: ForgeConfigSpec.ConfigValue<List<out String>>
    val campaignTickInterval: ForgeConfigSpec.IntValue
    val campaignSpeedTicksPerChunk: ForgeConfigSpec.IntValue
    val maxCampaignsPerWarband: ForgeConfigSpec.IntValue
    val maxCampaignDistanceChunks: ForgeConfigSpec.IntValue
    val materializeDistanceChunks: ForgeConfigSpec.IntValue

    init {
        val b = ForgeConfigSpec.Builder()
        b.push("scheduler")
        enabled = b.define("enabled", true)
        disableVanillaPatrolSpawning = b.define("disable_vanilla_patrol_spawning", true)
        warbandDiscoveryIntervalTicks = b.defineInRange("warband_discovery_interval_ticks", 200, 20, 12000)
        warbandDiscoveryRadiusChunks = b.defineInRange("warband_discovery_radius_chunks", 1000, 8, 4096)
        warbandRegistrationsPerTick = b.defineInRange("warband_registrations_per_tick", 4, 1, 64)
        warbandGridSpacingChunks = b.defineInRange("warband_grid_spacing_chunks", 64, 16, 512)
        warbandGridJitterChunks = b.defineInRange("warband_grid_jitter_chunks", 18, 0, 128)
        warbandSpawnChancePercent = b.defineInRange("warband_spawn_chance_percent", 35, 1, 100)
        warbandMinSpawnDistanceChunks = b.defineInRange("warband_min_spawn_distance_chunks", 24, 0, 512)
        campaignDispatchWarbandsPerTick = b.defineInRange("campaign_dispatch_warbands_per_tick", 64, 1, 4096)
        b.pop()

        b.push("campaigns")
        campaignTickInterval = b.defineInRange("campaign_tick_interval", 20, 20, 200)
        campaignSpeedTicksPerChunk = b.defineInRange("campaign_speed_ticks_per_chunk", 120, 100, 7200) // 10 chunks/min default
        maxCampaignsPerWarband = b.defineInRange("max_campaigns_per_warband", 1, 0, 8)
        maxCampaignDistanceChunks = b.defineInRange("max_campaign_distance_chunks", 1000, 4, 4096)
        materializeDistanceChunks = b.defineInRange("materialize_distance_chunks", 6, 1, 24)
        structureWarbandIds = b.defineListAllowEmpty(
            "structure_warband_ids",
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
