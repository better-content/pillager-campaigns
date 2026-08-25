package com.bettercontent.pillagercampaigns.core

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InvasionDirector private constructor(
    private val state: DirectorSnapshot,
    private val spec: InvasionRuntimeSpec,
) {
    fun transition(frame: DirectorFrame): DirectorTransition {
        require(frame.elapsedTicks >= 0L)
        val events = mutableListOf<DirectorEvent>()
        applyCommands(frame.commands, events)
        applyEffectResults(frame.effectResults, events)
        mergeSurfaces(frame.surfaces)
        state.tick += frame.elapsedTicks
        applyCombat(frame, events)

        val players = frame.players.associateBy(PlayerObservation::playerId)
        players.values.sortedBy(PlayerObservation::playerId).forEach { player -> advancePlayer(player, frame.elapsedTicks, events) }
        state.tracks.values.filter { it.playerId !in players }.forEach { track ->
            track.invasion?.takeIf { it.phase == InvasionPhase.ACTIVE }?.let { invasion ->
                invasion.targetUnavailableTicks += frame.elapsedTicks
                if (invasion.targetUnavailableTicks >= spec.rules.targetUnavailableTicks) requestRetire(track, InvasionOutcome.RETIRED, events)
            }
        }
        return DirectorTransition(events, state.pendingEffects.values.map(::copyEffect))
    }

    fun snapshot(): DirectorSnapshot = copy(state)

    private fun advancePlayer(player: PlayerObservation, elapsed: Long, events: MutableList<DirectorEvent>) {
        val track = state.tracks.getOrPut(player.playerId) { PlayerPressureTrack(player.playerId) }
        if (track.nextDueEligibleTick < 0L) schedule(track, first = true)
        if (player.eligible) track.eligibleTicks += elapsed
        var invasion = track.invasion

        if (invasion == null && player.eligible && player.surfaceEligible &&
            track.eligibleTicks >= track.nextDueEligibleTick - spec.rules.warningSurfaceTicks) {
            beginInvasion(track, player, events)
            return
        }
        invasion ?: return

        when (invasion.phase) {
            InvasionPhase.WARNED, InvasionPhase.APPROACHING, InvasionPhase.READY_TO_MATERIALIZE -> {
                if (player.eligible && player.surfaceEligible && player.physicallyAvailable) {
                    invasion.warningSurfaceTicks += elapsed
                    if (invasion.warningSurfaceTicks >= spec.rules.warningSurfaceTicks &&
                        state.pendingEffects.values.none { it.invasionId == invasion.invasionId && it.kind == EffectKind.WARN }) {
                        invasion.phase = InvasionPhase.APPROACHING
                        val position = player.position
                        if (position != null && state.pendingEffects.values.none {
                                it.invasionId == invasion.invasionId && it.kind == EffectKind.MATERIALIZE
                            }) {
                            val anchor = SurfaceApproach.choose(
                                invasion.observedCells.values, position, spec.rules,
                                stableSeed(state.worldSeed, track.playerId, track.invasionSequence),
                            )
                            if (anchor != null) {
                                invasion.anchor = BlockPoint(position.dimension, anchor.x, anchor.bodyY, anchor.z)
                                invasion.phase = InvasionPhase.READY_TO_MATERIALIZE
                                publish(DirectorEffect("", EffectKind.MATERIALIZE, track.playerId, invasion.invasionId, invasion.anchor, invasion.members))
                                events += event("materialization_requested", invasion.invasionId, "${anchor.x},${anchor.bodyY},${anchor.z}")
                            }
                        }
                    }
                }
            }
            InvasionPhase.ACTIVE -> {
                invasion.targetUnavailableTicks = if (player.eligible && player.physicallyAvailable) 0L else invasion.targetUnavailableTicks + elapsed
                if (invasion.targetUnavailableTicks >= spec.rules.targetUnavailableTicks ||
                    state.tick - invasion.lastCombatTick >= spec.rules.activeIdleTicks) {
                    requestRetire(track, InvasionOutcome.RETIRED, events)
                }
            }
            InvasionPhase.RETIRING -> Unit
        }
    }

    private fun beginInvasion(track: PlayerPressureTrack, player: PlayerObservation, events: MutableList<DirectorEvent>): InvasionState {
        track.invasionSequence += 1L
        val timeTier = (track.eligibleTicks / spec.rules.timeTierTicks).toInt()
        val intensity = (timeTier + track.outcomeAdjustment).coerceIn(0, spec.rules.maximumIntensity)
        val budget = spec.rules.threatBudgets[intensity]
        val invasionId = "invasion:${track.playerId}:${track.invasionSequence}"
        val members = RosterPlanner.plan(spec.recruits, intensity, budget, spec.rules, stableSeed(state.worldSeed, invasionId))
        val invasion = InvasionState(invasionId, track.playerId, intensity, budget, members, lastCombatTick = state.tick)
        track.invasion = invasion
        publish(DirectorEffect("", EffectKind.WARN, track.playerId, invasionId))
        events += event("warned", invasionId, "intensity=$intensity budget=$budget surface=${player.surfaceEligible}")
        return invasion
    }

    private fun applyEffectResults(results: List<EffectResult>, events: MutableList<DirectorEvent>) {
        results.distinctBy(EffectResult::effectId).forEach { result ->
            val effect = state.pendingEffects.remove(result.effectId) ?: return@forEach
            val track = state.tracks[effect.playerId] ?: return@forEach
            val invasion = track.invasion?.takeIf { it.invasionId == effect.invasionId } ?: return@forEach
            when (effect.kind) {
                EffectKind.WARN -> if (result.successful) invasion.phase = InvasionPhase.APPROACHING
                EffectKind.MATERIALIZE -> if (result.successful) {
                    invasion.phase = InvasionPhase.ACTIVE
                    invasion.lastCombatTick = state.tick
                    events += event("materialized", invasion.invasionId, "members=${invasion.members.size}")
                } else {
                    invasion.phase = InvasionPhase.APPROACHING
                    invasion.anchor = null
                    invasion.observedCells.clear()
                    events += event("materialization_failed", invasion.invasionId)
                }
                EffectKind.RETIRE -> if (result.successful) resolve(track, invasion.pendingOutcome ?: InvasionOutcome.RETIRED, events)
            }
        }
    }

    private fun mergeSurfaces(observations: List<SurfaceGridObservation>) {
        observations.forEach { observation ->
            state.tracks[observation.playerId]?.invasion?.let { invasion ->
                observation.cells.forEach { cell -> invasion.observedCells[cellKey(cell.x, cell.z)] = cell }
            }
        }
    }

    private fun applyCombat(frame: DirectorFrame, events: MutableList<DirectorEvent>) {
        frame.combat.map(CombatObservation::invasionId).toSet().forEach { id -> find(id)?.lastCombatTick = state.tick }
        frame.memberDefeats.distinctBy { it.invasionId to it.memberId }.forEach { defeat ->
            val invasion = find(defeat.invasionId) ?: return@forEach
            if (invasion.members.none { it.memberId == defeat.memberId }) return@forEach
            invasion.defeatedMemberIds += defeat.memberId
            invasion.lastCombatTick = state.tick
            if (invasion.defeatedMemberIds.size == invasion.members.size) {
                val track = state.tracks[invasion.targetPlayerId] ?: return@forEach
                resolve(track, InvasionOutcome.CLEARED, events)
            }
        }
        frame.targetDeaths.map(TargetDeathObservation::playerId).toSet().forEach { playerId ->
            val track = state.tracks[playerId] ?: return@forEach
            if (track.invasion?.phase == InvasionPhase.ACTIVE) requestRetire(track, InvasionOutcome.TARGET_DIED, events)
        }
    }

    private fun requestRetire(track: PlayerPressureTrack, outcome: InvasionOutcome, events: MutableList<DirectorEvent>) {
        val invasion = track.invasion ?: return
        if (invasion.phase == InvasionPhase.RETIRING) return
        invasion.phase = InvasionPhase.RETIRING
        invasion.pendingOutcome = outcome
        publish(DirectorEffect("", EffectKind.RETIRE, track.playerId, invasion.invasionId))
        events += event("retire_requested", invasion.invasionId, outcome.name.lowercase())
    }

    private fun resolve(track: PlayerPressureTrack, outcome: InvasionOutcome, events: MutableList<DirectorEvent>) {
        val invasion = track.invasion ?: return
        when (outcome) {
            InvasionOutcome.CLEARED -> track.outcomeAdjustment = (track.outcomeAdjustment + 1).coerceAtMost(2)
            InvasionOutcome.TARGET_DIED -> track.outcomeAdjustment = (track.outcomeAdjustment - 1).coerceAtLeast(-2)
            InvasionOutcome.RETIRED -> track.outcomeAdjustment += -track.outcomeAdjustment.sign()
        }
        track.invasion = null
        state.pendingEffects.entries.removeIf { it.value.invasionId == invasion.invasionId }
        schedule(track, first = false, grace = if (outcome == InvasionOutcome.TARGET_DIED) spec.rules.deathGraceTicks else 0L)
        events += event("resolved", invasion.invasionId, outcome.name.lowercase())
    }

    private fun schedule(track: PlayerPressureTrack, first: Boolean, grace: Long = 0L) {
        val min = if (first) spec.rules.firstWindowMinTicks else spec.rules.repeatWindowMinTicks
        val max = if (first) spec.rules.firstWindowMaxTicks else spec.rules.repeatWindowMaxTicks
        val random = Random(stableSeed(state.worldSeed, track.playerId, track.invasionSequence, if (first) "first" else "repeat"))
        val delay = if (max == min) min else random.nextLong(min, max + 1L)
        track.nextDueEligibleTick = track.eligibleTicks + maxOf(grace, delay)
    }

    private fun applyCommands(commands: List<DirectorCommand>, events: MutableList<DirectorEvent>) {
        commands.forEach { command -> when (command) {
            is DirectorCommand.Force -> state.tracks.getOrPut(command.playerId) { PlayerPressureTrack(command.playerId) }.let {
                it.nextDueEligibleTick = it.eligibleTicks
                events += event("forced", command.playerId)
            }
            is DirectorCommand.Reset -> if (command.playerId == null) {
                state.tracks.clear(); state.pendingEffects.clear(); events += event("reset", "all")
            } else {
                state.tracks.remove(command.playerId); state.pendingEffects.entries.removeIf { it.value.playerId == command.playerId }
                events += event("reset", command.playerId)
            }
        } }
    }

    private fun publish(effect: DirectorEffect) {
        state.effectSequence += 1L
        val id = "effect:${state.effectSequence}"
        state.pendingEffects[id] = effect.copy(effectId = id)
    }

    private fun find(invasionId: String): InvasionState? = state.tracks.values.firstNotNullOfOrNull { it.invasion?.takeIf { invasion -> invasion.invasionId == invasionId } }
    private fun event(type: String, subject: String, detail: String = "") = DirectorEvent(state.tick, type, subject, detail)

    companion object {
        private val JSON = Json { encodeDefaults = true }
        fun create(worldSeed: Long, spec: InvasionRuntimeSpec): InvasionDirector = restore(DirectorSnapshot(worldSeed = worldSeed), spec)
        fun restore(snapshot: DirectorSnapshot, spec: InvasionRuntimeSpec): InvasionDirector {
            spec.requireValid()
            require(snapshot.schemaVersion == DirectorSnapshot.CURRENT_SCHEMA_VERSION)
            return InvasionDirector(copy(snapshot), copy(spec))
        }
        private fun copy(snapshot: DirectorSnapshot): DirectorSnapshot = JSON.decodeFromString(JSON.encodeToString(snapshot))
        private fun copy(spec: InvasionRuntimeSpec): InvasionRuntimeSpec = JSON.decodeFromString(JSON.encodeToString(spec))
        private fun copyEffect(effect: DirectorEffect): DirectorEffect = effect.copy(members = effect.members.toList())
        private fun stableSeed(vararg values: Any): Long = values.fold(-3750763034362895579L) { hash, value ->
            value.toString().fold(hash) { next, character -> (next xor character.code.toLong()) * 1099511628211L }
        }
        private fun Int.sign(): Int = when { this > 0 -> 1; this < 0 -> -1; else -> 0 }
        private fun cellKey(x: Int, z: Int) = "$x:$z"
    }
}

object RosterPlanner {
    fun plan(recruits: List<RecruitSpec>, intensity: Int, budget: Int, rules: InvasionRules, seed: Long): List<MemberPlan> {
        val available = recruits.filter { it.unlockIntensity <= intensity }.sortedBy(RecruitSpec::entityId)
        require(available.isNotEmpty())
        val random = Random(seed)
        val chosen = mutableListOf<RecruitSpec>()
        var remaining = budget

        fun addFrom(role: RecruitRole): Boolean {
            val candidates = available.filter { it.role == role && it.cost <= remaining && chosen.count { prior -> prior.entityId == it.entityId } < it.maximumPerSquad }
            if (candidates.isEmpty()) return false
            val selected = weighted(candidates, random)
            chosen += selected; remaining -= selected.cost
            return true
        }

        addFrom(RecruitRole.RANGED)
        if (intensity >= 1) addFrom(RecruitRole.FRONTLINE)
        while (chosen.size < rules.maximumMembers) {
            val candidates = available.filter { candidate ->
                candidate.cost <= remaining && chosen.count { it.entityId == candidate.entityId } < candidate.maximumPerSquad &&
                    (candidate.role !in setOf(RecruitRole.SUPPORT, RecruitRole.ELITE) || chosen.none { it.role == candidate.role })
            }
            if (candidates.isEmpty()) break
            val selected = weighted(candidates, random)
            chosen += selected; remaining -= selected.cost
        }
        val cheapest = available.minWith(compareBy<RecruitSpec> { it.cost }.thenBy { it.entityId })
        while (chosen.size < rules.minimumMembers && chosen.size < rules.maximumMembers && cheapest.cost <= remaining) {
            chosen += cheapest; remaining -= cheapest.cost
        }
        require(chosen.size >= rules.minimumMembers) { "budget $budget cannot form minimum invasion squad" }
        return chosen.mapIndexed { index, recruit -> MemberPlan("member:${index + 1}", recruit.entityId, recruit.cost) }
    }

    private fun weighted(candidates: List<RecruitSpec>, random: Random): RecruitSpec {
        var cursor = random.nextInt(candidates.sumOf(RecruitSpec::weight))
        candidates.forEach { candidate -> cursor -= candidate.weight; if (cursor < 0) return candidate }
        return candidates.last()
    }
}

object SurfaceApproach {
    fun choose(cells: Collection<SurfaceCell>, target: BlockPoint, rules: InvasionRules, seed: Long): SurfaceCell? {
        val passable = cells.filter(SurfaceCell::passable).associateBy { it.x to it.z }
        val origins = passable.values.filter { cell ->
            maxOf(abs(cell.x - target.x), abs(cell.z - target.z)) in rules.approachMinimumBlocks..rules.approachMaximumBlocks
        }.sortedWith(
            compareByDescending<SurfaceCell> { maxOf(abs(it.x - target.x), abs(it.z - target.z)) }
                .thenBy { stableRank(it, seed) }.thenBy { it.x }.thenBy { it.z },
        )
        if (origins.isEmpty()) return null
        data class Node(val cell: SurfaceCell, val origin: SurfaceCell)
        val queue = ArrayDeque<Node>()
        val seen = hashSetOf<Pair<Int, Int>>()
        val origin = origins.first()
        seen += origin.x to origin.z
        queue += Node(origin, origin)
        var best = queue.first()
        var expansions = 0
        while (queue.isNotEmpty() && expansions < rules.maximumSearchExpansions) {
            val node = queue.removeFirst(); expansions++
            if (distance(node.cell, target) < distance(best.cell, target)) best = node
            listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).forEach { (dx, dz) ->
                val next = passable[node.cell.x + dx to node.cell.z + dz] ?: return@forEach
                val rise = next.bodyY - node.cell.bodyY
                if (rise > 1 || rise < -2 || !seen.add(next.x to next.z)) return@forEach
                queue += Node(next, node.origin)
            }
        }
        return best.origin
    }

    private fun distance(cell: SurfaceCell, target: BlockPoint) = abs(cell.x - target.x) + abs(cell.z - target.z)
    private fun stableRank(cell: SurfaceCell, seed: Long): Long = (seed xor (cell.x.toLong() shl 32) xor cell.z.toLong()) * -7046029254386353131L
}
