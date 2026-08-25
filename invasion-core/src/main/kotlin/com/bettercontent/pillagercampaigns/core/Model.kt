package com.bettercontent.pillagercampaigns.core

import kotlinx.serialization.Serializable

@Serializable
data class InvasionRules(
    val firstWindowMinTicks: Long = 24_000L,
    val firstWindowMaxTicks: Long = 36_000L,
    val repeatWindowMinTicks: Long = 24_000L,
    val repeatWindowMaxTicks: Long = 48_000L,
    val warningSurfaceTicks: Long = 600L,
    val deathGraceTicks: Long = 24_000L,
    val timeTierTicks: Long = 144_000L,
    val maximumIntensity: Int = 5,
    val threatBudgets: List<Int> = listOf(8, 11, 15, 20, 26, 33),
    val minimumMembers: Int = 3,
    val maximumMembers: Int = 8,
    val approachMinimumBlocks: Int = 48,
    val approachMaximumBlocks: Int = 72,
    val maximumSearchExpansions: Int = 4_096,
    val activeIdleTicks: Long = 6_000L,
    val targetUnavailableTicks: Long = 1_200L,
) {
    init {
        require(firstWindowMinTicks in 0..firstWindowMaxTicks)
        require(repeatWindowMinTicks in 0..repeatWindowMaxTicks)
        require(warningSurfaceTicks >= 0L && deathGraceTicks >= 0L && timeTierTicks > 0L)
        require(maximumIntensity >= 0 && threatBudgets.size == maximumIntensity + 1 && threatBudgets.all { it > 0 })
        require(minimumMembers in 1..maximumMembers)
        require(approachMinimumBlocks in 1..approachMaximumBlocks && maximumSearchExpansions > 0)
        require(activeIdleTicks > 0L && targetUnavailableTicks > 0L)
    }
}

@Serializable enum class RecruitRole { LINE, RANGED, FRONTLINE, SUPPORT, ELITE }

@Serializable
data class RecruitSpec(
    val entityId: String,
    val cost: Int,
    val unlockIntensity: Int,
    val role: RecruitRole,
    val weight: Int = 1,
    val maximumPerSquad: Int = 8,
    val optional: Boolean = false,
)

@Serializable data class BlockPoint(val dimension: String, val x: Int, val y: Int, val z: Int)

@Serializable
data class SurfaceCell(
    val x: Int,
    val bodyY: Int,
    val z: Int,
    val passable: Boolean = true,
)

@Serializable data class SurfaceGridObservation(val playerId: String, val cells: List<SurfaceCell>)

@Serializable
data class PlayerObservation(
    val playerId: String,
    val eligible: Boolean,
    val surfaceEligible: Boolean,
    val physicallyAvailable: Boolean,
    val position: BlockPoint? = null,
)

@Serializable enum class InvasionPhase { WARNED, APPROACHING, READY_TO_MATERIALIZE, ACTIVE, RETIRING }
@Serializable enum class InvasionOutcome { CLEARED, TARGET_DIED, RETIRED }

@Serializable data class MemberPlan(val memberId: String, val recruitId: String, val cost: Int)

@Serializable
data class InvasionState(
    val invasionId: String,
    val targetPlayerId: String,
    val intensity: Int,
    val threatBudget: Int,
    val members: List<MemberPlan>,
    var phase: InvasionPhase = InvasionPhase.WARNED,
    var warningSurfaceTicks: Long = 0L,
    var lastCombatTick: Long = 0L,
    var targetUnavailableTicks: Long = 0L,
    val defeatedMemberIds: MutableSet<String> = linkedSetOf(),
    val observedCells: MutableMap<String, SurfaceCell> = linkedMapOf(),
    var anchor: BlockPoint? = null,
    var pendingOutcome: InvasionOutcome? = null,
)

@Serializable
data class PlayerPressureTrack(
    val playerId: String,
    var eligibleTicks: Long = 0L,
    var nextDueEligibleTick: Long = -1L,
    var invasionSequence: Long = 0L,
    var outcomeAdjustment: Int = 0,
    var invasion: InvasionState? = null,
)

@Serializable
data class DirectorSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val worldSeed: Long,
    var tick: Long = 0L,
    var effectSequence: Long = 0L,
    val tracks: MutableMap<String, PlayerPressureTrack> = linkedMapOf(),
    val pendingEffects: MutableMap<String, DirectorEffect> = linkedMapOf(),
) {
    companion object { const val CURRENT_SCHEMA_VERSION: Int = 1 }
}

@Serializable enum class EffectKind { WARN, MATERIALIZE, RETIRE }

@Serializable
data class DirectorEffect(
    val effectId: String,
    val kind: EffectKind,
    val playerId: String,
    val invasionId: String,
    val anchor: BlockPoint? = null,
    val members: List<MemberPlan> = emptyList(),
)

@Serializable data class EffectResult(val effectId: String, val successful: Boolean = true)
@Serializable data class MemberDefeatObservation(val invasionId: String, val memberId: String)
@Serializable data class CombatObservation(val invasionId: String)
@Serializable data class TargetDeathObservation(val playerId: String)

@Serializable
sealed interface DirectorCommand {
    @Serializable data class Force(val playerId: String) : DirectorCommand
    @Serializable data class Reset(val playerId: String? = null) : DirectorCommand
}

@Serializable
data class DirectorFrame(
    val elapsedTicks: Long,
    val players: List<PlayerObservation> = emptyList(),
    val surfaces: List<SurfaceGridObservation> = emptyList(),
    val effectResults: List<EffectResult> = emptyList(),
    val memberDefeats: List<MemberDefeatObservation> = emptyList(),
    val combat: List<CombatObservation> = emptyList(),
    val targetDeaths: List<TargetDeathObservation> = emptyList(),
    val commands: List<DirectorCommand> = emptyList(),
)

data class DirectorTransition(val events: List<DirectorEvent>, val effects: List<DirectorEffect>)
@Serializable data class DirectorEvent(val tick: Long, val type: String, val subjectId: String, val detail: String = "")
