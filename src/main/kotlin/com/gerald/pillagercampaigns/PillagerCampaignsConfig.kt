package com.gerald.pillagercampaigns

import net.minecraftforge.common.ForgeConfigSpec

object PillagerCampaignsConfig {
    val SPEC: ForgeConfigSpec

    val enabled: ForgeConfigSpec.BooleanValue
    val disableVanillaPatrolSpawning: ForgeConfigSpec.BooleanValue
    val baseDiscoveryIntervalTicks: ForgeConfigSpec.IntValue
    val baseDiscoveryRadiusChunks: ForgeConfigSpec.IntValue
    val maxBaseDiscoveriesPerTick: ForgeConfigSpec.IntValue
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
        baseDiscoveryRadiusChunks = b.defineInRange("base_discovery_radius_chunks", 512, 8, 4096)
        maxBaseDiscoveriesPerTick = b.defineInRange("max_base_discoveries_per_tick", 2, 1, 32)
        b.pop()

        b.push("campaigns")
        campaignTickInterval = b.defineInRange("campaign_tick_interval", 20, 20, 200)
        campaignSpeedTicksPerChunk = b.defineInRange("campaign_speed_ticks_per_chunk", 120, 100, 7200) // 10 chunks/min default
        maxCampaignsPerBase = b.defineInRange("max_campaigns_per_base", 1, 0, 8)
        maxCampaignDistanceChunks = b.defineInRange("max_campaign_distance_chunks", 1000, 4, 4096)
        materializeDistanceChunks = b.defineInRange("materialize_distance_chunks", 6, 1, 24)
        structureBaseIds = b.defineListAllowEmpty("structure_base_ids", listOf("minecraft:pillager_outpost")) { it is String && it.contains(":") }
        b.pop()
        SPEC = b.build()
    }
}
