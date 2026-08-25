package com.bettercontent.pillagercampaigns

import com.bettercontent.pillagercampaigns.core.InvasionRules
import net.minecraftforge.common.ForgeConfigSpec

object PillagerCampaignsConfig {
    val SPEC: ForgeConfigSpec
    val enabled: ForgeConfigSpec.BooleanValue
    val disableVanillaPatrolSpawning: ForgeConfigSpec.BooleanValue
    val intervalTicks: ForgeConfigSpec.IntValue
    val surfaceSampleBudget: ForgeConfigSpec.IntValue
    val firstWindowMinTicks: ForgeConfigSpec.IntValue
    val firstWindowMaxTicks: ForgeConfigSpec.IntValue
    val repeatWindowMinTicks: ForgeConfigSpec.IntValue
    val repeatWindowMaxTicks: ForgeConfigSpec.IntValue
    val warningSurfaceTicks: ForgeConfigSpec.IntValue
    val deathGraceTicks: ForgeConfigSpec.IntValue
    val timeTierTicks: ForgeConfigSpec.IntValue
    val approachMinimumBlocks: ForgeConfigSpec.IntValue
    val approachMaximumBlocks: ForgeConfigSpec.IntValue
    val maximumSearchExpansions: ForgeConfigSpec.IntValue
    val activeIdleTicks: ForgeConfigSpec.IntValue
    val targetUnavailableTicks: ForgeConfigSpec.IntValue

    init {
        val b = ForgeConfigSpec.Builder()
        b.push("scheduler")
        enabled = b.define("enabled", true)
        disableVanillaPatrolSpawning = b.define("disable_vanilla_patrol_spawning", true)
        intervalTicks = b.defineInRange("interval_ticks", 20, 1, 1_200)
        surfaceSampleBudget = b.comment("Maximum loaded surface columns sampled across all approaching invasions per scheduler tick.")
            .defineInRange("surface_sample_budget", 4_096, 64, 65_536)
        b.pop()

        b.push("cadence")
        firstWindowMinTicks = b.defineInRange("first_window_min_ticks", 24_000, 0, 1_200_000)
        firstWindowMaxTicks = b.defineInRange("first_window_max_ticks", 36_000, 0, 1_200_000)
        repeatWindowMinTicks = b.defineInRange("repeat_window_min_ticks", 24_000, 0, 1_200_000)
        repeatWindowMaxTicks = b.defineInRange("repeat_window_max_ticks", 48_000, 0, 1_200_000)
        warningSurfaceTicks = b.defineInRange("warning_surface_ticks", 600, 0, 24_000)
        deathGraceTicks = b.defineInRange("death_grace_ticks", 24_000, 0, 1_200_000)
        b.pop()

        b.push("scaling")
        timeTierTicks = b.defineInRange("time_tier_ticks", 144_000, 1, 12_000_000)
        b.pop()

        b.push("approach")
        approachMinimumBlocks = b.defineInRange("minimum_blocks", 48, 8, 256)
        approachMaximumBlocks = b.defineInRange("maximum_blocks", 72, 8, 384)
        maximumSearchExpansions = b.defineInRange("maximum_search_expansions", 4_096, 64, 65_536)
        b.pop()

        b.push("cleanup")
        activeIdleTicks = b.defineInRange("active_idle_ticks", 6_000, 200, 144_000)
        targetUnavailableTicks = b.defineInRange("target_unavailable_ticks", 1_200, 20, 24_000)
        b.pop()
        SPEC = b.build()
    }

    fun rules(): InvasionRules {
        val firstMin = minOf(firstWindowMinTicks.get(), firstWindowMaxTicks.get()).toLong()
        val firstMax = maxOf(firstWindowMinTicks.get(), firstWindowMaxTicks.get()).toLong()
        val repeatMin = minOf(repeatWindowMinTicks.get(), repeatWindowMaxTicks.get()).toLong()
        val repeatMax = maxOf(repeatWindowMinTicks.get(), repeatWindowMaxTicks.get()).toLong()
        val approachMin = minOf(approachMinimumBlocks.get(), approachMaximumBlocks.get())
        val approachMax = maxOf(approachMinimumBlocks.get(), approachMaximumBlocks.get())
        return InvasionRules(
            firstWindowMinTicks = firstMin,
            firstWindowMaxTicks = firstMax,
            repeatWindowMinTicks = repeatMin,
            repeatWindowMaxTicks = repeatMax,
            warningSurfaceTicks = warningSurfaceTicks.get().toLong(),
            deathGraceTicks = deathGraceTicks.get().toLong(),
            timeTierTicks = timeTierTicks.get().toLong(),
            approachMinimumBlocks = approachMin,
            approachMaximumBlocks = approachMax,
            maximumSearchExpansions = maximumSearchExpansions.get(),
            activeIdleTicks = activeIdleTicks.get().toLong(),
            targetUnavailableTicks = targetUnavailableTicks.get().toLong(),
        )
    }
}
