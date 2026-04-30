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
    val dropOminousBottleFromLeaders: ForgeConfigSpec.BooleanValue

    init {
        val builder = ForgeConfigSpec.Builder()

        builder.push("scheduler")
        enabled = builder.comment("Enable pack-owned pillager pressure patrols.").define("enabled", true)
        intervalTicks = builder.comment("Ticks between patrol attempts. 1200 is one minute.").defineInRange("interval_ticks", 1200, 20, 240000)
        spawnChance = builder.comment("Chance that each interval attempts a patrol per eligible player.").defineInRange("spawn_chance", 0.95, 0.0, 1.0)
        overworldOnly = builder.define("overworld_only", true)
        allowCreativePlayers = builder.comment("Keep true while tuning so creative playtests still receive patrol pressure.").define("allow_creative_players", true)
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
            listOf(
                "minecraft:vindicator",
                "minecraft:evoker",
                "minecraft:witch",
                "takesapillage:archer",
                "takesapillage:skirmisher",
                "takesapillage:legioner",
                "savage_and_ravage:griefer",
                "savage_and_ravage:executioner",
                "savage_and_ravage:iceologer",
                "savage_and_ravage:trickster",
            ),
            { it is String && it.contains(":") },
        )
        targetPlayerImmediately = builder.define("target_player_immediately", true)
        persistentPatrolMobs = builder.comment("Persistence prevents patrols from vanishing before they find the player; accumulation cap prevents runaway counts.").define("persistent_patrol_mobs", true)
        dropOminousBottleFromLeaders = builder.comment("If Base Raid is loaded, patrol leaders drop its ominous bottle unless another drop already added one.").define("drop_ominous_bottle_from_leaders", true)
        builder.pop()

        SPEC = builder.build()
    }
}
