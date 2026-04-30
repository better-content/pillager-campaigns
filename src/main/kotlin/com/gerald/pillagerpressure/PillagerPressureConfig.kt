package com.gerald.pillagerpressure

import net.minecraftforge.common.ForgeConfigSpec

object PillagerPressureConfig {
    val SPEC: ForgeConfigSpec

    val enabled: ForgeConfigSpec.BooleanValue
    val intervalTicks: ForgeConfigSpec.IntValue
    val spawnChance: ForgeConfigSpec.DoubleValue
    val overworldOnly: ForgeConfigSpec.BooleanValue
    val allowCreativePlayers: ForgeConfigSpec.BooleanValue
    val skipSpectatorPlayers: ForgeConfigSpec.BooleanValue
    val disableVanillaPatrolSpawning: ForgeConfigSpec.BooleanValue

    val minRadius: ForgeConfigSpec.IntValue
    val maxRadius: ForgeConfigSpec.IntValue
    val spawnAttempts: ForgeConfigSpec.IntValue
    val maxActiveNearPlayer: ForgeConfigSpec.IntValue
    val activeCheckRadius: ForgeConfigSpec.IntValue

    val spawnLeader: ForgeConfigSpec.BooleanValue
    val minPillagers: ForgeConfigSpec.IntValue
    val maxPillagers: ForgeConfigSpec.IntValue
    val specialChance: ForgeConfigSpec.DoubleValue
    val specialAmount: ForgeConfigSpec.IntValue
    val specialIllagers: ForgeConfigSpec.ConfigValue<List<out String>>

    val targetPlayerImmediately: ForgeConfigSpec.BooleanValue
    val persistentPatrolMobs: ForgeConfigSpec.BooleanValue

    val campaignEnabled: ForgeConfigSpec.BooleanValue
    val regionSizeChunks: ForgeConfigSpec.IntValue
    val staleRegionTicks: ForgeConfigSpec.IntValue
    val baseScanIntervalTicks: ForgeConfigSpec.IntValue
    val campaignTickInterval: ForgeConfigSpec.IntValue
    val campaignSpeedTicksPerChunk: ForgeConfigSpec.IntValue
    val maxCampaignsPerBase: ForgeConfigSpec.IntValue
    val maxSatellitesPerMajorBase: ForgeConfigSpec.IntValue
    val structureBaseIds: ForgeConfigSpec.ConfigValue<List<out String>>
    val replaceNaturalOutpostSpawns: ForgeConfigSpec.BooleanValue
    val deathFlagsPerKill: ForgeConfigSpec.IntValue
    val maxDeathFlagsPerChunk: ForgeConfigSpec.IntValue
    val officerEscapeHealth: ForgeConfigSpec.DoubleValue
    val officerEngineeringEnabled: ForgeConfigSpec.BooleanValue
    val officerEngineeringCooldownTicks: ForgeConfigSpec.IntValue
    val officerEngineeringTtlTicks: ForgeConfigSpec.IntValue
    val officerEngineeringMaxBlocks: ForgeConfigSpec.IntValue

    init {
        val builder = ForgeConfigSpec.Builder()

        builder.push("scheduler")
        enabled = builder.comment("Enable pack-owned pillager pressure patrols.").define("enabled", true)
        intervalTicks = builder.comment("Ticks between fallback patrol attempts. Campaigns use their own scheduler.").defineInRange("interval_ticks", 1200, 20, 240000)
        spawnChance = builder.comment("Chance that each fallback interval attempts a patrol per eligible player.").defineInRange("spawn_chance", 0.95, 0.0, 1.0)
        overworldOnly = builder.define("overworld_only", true)
        allowCreativePlayers = builder.comment("Keep true while tuning so creative playtests still receive pressure.").define("allow_creative_players", true)
        skipSpectatorPlayers = builder.define("skip_spectator_players", true)
        disableVanillaPatrolSpawning = builder.comment("Set doPatrolSpawning=false on server start so this mod is the sole patrol scheduler.").define("disable_vanilla_patrol_spawning", true)
        builder.pop()

        builder.push("placement")
        minRadius = builder.defineInRange("min_radius", 32, 8, 192)
        maxRadius = builder.defineInRange("max_radius", 64, 8, 256)
        spawnAttempts = builder.comment("Surface placement attempts before the patrol attempt is skipped.").defineInRange("spawn_attempts", 24, 1, 128)
        maxActiveNearPlayer = builder.comment("Stops runaway accumulation of pressure mobs around a player.").defineInRange("max_active_near_player", 48, 0, 256)
        activeCheckRadius = builder.defineInRange("active_check_radius", 128, 16, 512)
        builder.pop()

        builder.push("patrol")
        spawnLeader = builder.define("spawn_leader", true)
        minPillagers = builder.defineInRange("min_pillagers", 4, 0, 40)
        maxPillagers = builder.defineInRange("max_pillagers", 7, 0, 40)
        specialChance = builder.defineInRange("special_chance", 0.75, 0.0, 1.0)
        specialAmount = builder.defineInRange("special_amount", 2, 0, 40)
        specialIllagers = builder.comment("Missing entity ids are skipped, so optional illager mods can be removed safely.").defineListAllowEmpty(
            "special_illagers",
            listOf("minecraft:vindicator", "minecraft:evoker", "minecraft:witch", "takesapillage:archer", "takesapillage:skirmisher", "takesapillage:legioner", "savage_and_ravage:griefer", "savage_and_ravage:executioner", "savage_and_ravage:iceologer", "savage_and_ravage:trickster"),
            { it is String && it.contains(":") },
        )
        targetPlayerImmediately = builder.define("target_player_immediately", true)
        persistentPatrolMobs = builder.comment("Persistence prevents campaign mobs from vanishing before they matter; accumulation caps prevent runaway counts.").define("persistent_patrol_mobs", true)
        builder.pop()

        builder.push("campaigns")
        campaignEnabled = builder.define("enabled", true)
        regionSizeChunks = builder.defineInRange("region_size_chunks", 8, 4, 32)
        staleRegionTicks = builder.comment("Regions older than this may receive satellite camps. Default is 5 Minecraft days.").defineInRange("stale_region_ticks", 120000, 1200, 2400000)
        baseScanIntervalTicks = builder.defineInRange("base_scan_interval_ticks", 200, 20, 6000)
        campaignTickInterval = builder.defineInRange("campaign_tick_interval", 100, 20, 6000)
        campaignSpeedTicksPerChunk = builder.defineInRange("campaign_speed_ticks_per_chunk", 80, 10, 6000)
        maxCampaignsPerBase = builder.defineInRange("max_campaigns_per_base", 4, 0, 32)
        maxSatellitesPerMajorBase = builder.defineInRange("max_satellites_per_major_base", 4, 0, 24)
        replaceNaturalOutpostSpawns = builder.comment("Cancel natural raider spawns inside registered bases and spend base economy for garrisons instead.").define("replace_natural_outpost_spawns", true)
        structureBaseIds = builder.comment("Structure ids treated as major pillager bases. Modded structures can be added here after registry confirmation.").defineListAllowEmpty("structure_base_ids", listOf("minecraft:pillager_outpost"), { it is String && it.contains(":") })
        deathFlagsPerKill = builder.defineInRange("death_flags_per_kill", 12, 0, 32)
        maxDeathFlagsPerChunk = builder.defineInRange("max_death_flags_per_chunk", 24, 0, 64)
        officerEscapeHealth = builder.comment("Named officers below this health fraction try to escape/collapse to campaign state.").defineInRange("officer_escape_health", 0.22, 0.0, 1.0)
        officerEngineeringEnabled = builder.comment("Named officers may place temporary invasion blocks to bridge gaps or ladder walls. They never break or replace solid blocks.").define("officer_engineering_enabled", true)
        officerEngineeringCooldownTicks = builder.defineInRange("officer_engineering_cooldown_ticks", 30, 5, 1200)
        officerEngineeringTtlTicks = builder.comment("Temporary officer-placed blocks are removed after this many ticks once their chunk is loaded again.").defineInRange("officer_engineering_ttl_ticks", 6000, 200, 72000)
        officerEngineeringMaxBlocks = builder.comment("Per spawned officer cap for temporary engineering placements.").defineInRange("officer_engineering_max_blocks", 24, 0, 128)
        builder.pop()

        SPEC = builder.build()
    }
}
