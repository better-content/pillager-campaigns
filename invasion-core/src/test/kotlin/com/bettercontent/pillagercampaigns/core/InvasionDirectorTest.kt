package com.bettercontent.pillagercampaigns.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvasionDirectorTest {
    private val rules = InvasionRules(
        firstWindowMinTicks = 100, firstWindowMaxTicks = 100,
        repeatWindowMinTicks = 200, repeatWindowMaxTicks = 200,
        warningSurfaceTicks = 20, deathGraceTicks = 250, timeTierTicks = 1_000,
        approachMinimumBlocks = 2, approachMaximumBlocks = 4,
        maximumSearchExpansions = 128, activeIdleTicks = 100, targetUnavailableTicks = 40,
    )
    private val recruits = listOf(
        RecruitSpec("minecraft:pillager", 2, 0, RecruitRole.RANGED, weight = 4),
        RecruitSpec("minecraft:vindicator", 3, 1, RecruitRole.FRONTLINE, weight = 3),
        RecruitSpec("minecraft:witch", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1),
        RecruitSpec("minecraft:evoker", 6, 4, RecruitRole.ELITE, maximumPerSquad = 1),
    )
    private val spec = InvasionRuntimeSpec.create(rules, recruits)

    @Test fun `runtime specification is canonical and rejects drift`() {
        spec.requireValid()
        assertEquals(spec.computedRevision(), spec.revision)
        assertFailsWith<IllegalArgumentException> { spec.copy(revision = "wrong").requireValid() }
        assertFailsWith<IllegalArgumentException> { InvasionRuntimeSpec.create(rules, emptyList()).requireValid() }
        assertFailsWith<IllegalArgumentException> { InvasionRuntimeSpec.create(rules, recruits + recruits.first()).requireValid() }
    }

    @Test fun `invalid rules fail closed`() {
        assertFailsWith<IllegalArgumentException> { InvasionRules(firstWindowMinTicks = 2, firstWindowMaxTicks = 1) }
        assertFailsWith<IllegalArgumentException> { InvasionRules(threatBudgets = listOf(1)) }
        assertFailsWith<IllegalArgumentException> { InvasionRules(minimumMembers = 9, maximumMembers = 8) }
        assertFailsWith<IllegalArgumentException> { InvasionRules(approachMinimumBlocks = 9, approachMaximumBlocks = 8) }
    }

    @Test fun `warning waits for surface and materialization uses observed approach`() {
        val engine = InvasionDirector.create(9L, spec)
        engine.transition(frame(100, surface = false))
        assertNull(engine.snapshot().tracks.getValue("player").invasion)

        val warned = engine.transition(frame(1, surface = true))
        assertTrue(warned.effects.any { it.kind == EffectKind.WARN })
        val warning = warned.effects.single { it.kind == EffectKind.WARN }
        engine.transition(frame(0, surface = true, results = listOf(EffectResult(warning.effectId))))
        val tooEarly = engine.transition(frame(19, surface = true, surfaces = listOf(openGrid())))
        assertTrue(tooEarly.effects.none { it.kind == EffectKind.MATERIALIZE })
        val ready = engine.transition(frame(1, surface = true, surfaces = listOf(openGrid())))
        val materialize = ready.effects.single { it.kind == EffectKind.MATERIALIZE }
        val anchor = assertNotNull(materialize.anchor)
        assertTrue(maxOf(kotlin.math.abs(anchor.x), kotlin.math.abs(anchor.z)) in 2..4)
    }

    @Test fun `failed materialization retries and a clear raises bounded outcome`() {
        val engine = activeEngine()
        val active = engine.snapshot().tracks.getValue("player").invasion!!
        active.members.forEach { member -> engine.transition(frame(0, defeats = listOf(MemberDefeatObservation(active.invasionId, member.memberId)))) }
        val track = engine.snapshot().tracks.getValue("player")
        assertNull(track.invasion)
        assertEquals(1, track.outcomeAdjustment)
        assertEquals(track.eligibleTicks + 200, track.nextDueEligibleTick)

        repeat(4) {
            engine.transition(frame(200, surface = true))
            val warning = engine.transition(frame(1, surface = true)).effects.firstOrNull { it.kind == EffectKind.WARN }
            if (warning != null) engine.transition(frame(0, results = listOf(EffectResult(warning.effectId))))
            val materialize = engine.transition(frame(20, surfaces = listOf(openGrid()))).effects.firstOrNull { it.kind == EffectKind.MATERIALIZE }
            if (materialize != null) engine.transition(frame(0, results = listOf(EffectResult(materialize.effectId))) )
            val invasion = engine.snapshot().tracks.getValue("player").invasion
            invasion?.members?.forEach { member -> engine.transition(frame(0, defeats = listOf(MemberDefeatObservation(invasion.invasionId, member.memberId)))) }
        }
        assertEquals(2, engine.snapshot().tracks.getValue("player").outcomeAdjustment)
    }

    @Test fun `materialization failure target death and retirement are exact`() {
        val engine = warnedEngine()
        val materialize = engine.transition(frame(20, surfaces = listOf(openGrid()))).effects.single { it.kind == EffectKind.MATERIALIZE }
        engine.transition(frame(0, results = listOf(EffectResult(materialize.effectId, false))))
        assertEquals(InvasionPhase.APPROACHING, engine.snapshot().tracks.getValue("player").invasion!!.phase)
        val retried = engine.transition(frame(0, surfaces = listOf(openGrid()))).effects.single { it.kind == EffectKind.MATERIALIZE }
        engine.transition(frame(0, results = listOf(EffectResult(retried.effectId))))
        val active = engine.snapshot().tracks.getValue("player").invasion!!
        engine.transition(frame(0, deaths = listOf(TargetDeathObservation("player"))))
        val retire = engine.snapshot().pendingEffects.values.single { it.kind == EffectKind.RETIRE }
        assertEquals(InvasionPhase.RETIRING, engine.snapshot().tracks.getValue("player").invasion!!.phase)
        engine.transition(frame(0, results = listOf(EffectResult(retire.effectId))))
        val track = engine.snapshot().tracks.getValue("player")
        assertEquals(-1, track.outcomeAdjustment)
        assertTrue(track.nextDueEligibleTick >= track.eligibleTicks + 250)
        assertNull(track.invasion)
        assertNotEquals(active.invasionId, track.invasion?.invasionId)
    }

    @Test fun `idle and unavailable targets retire while combat refreshes activity`() {
        val idle = activeEngine()
        idle.transition(frame(80, combat = listOf(CombatObservation(idle.snapshot().tracks.getValue("player").invasion!!.invasionId))))
        idle.transition(frame(99))
        assertEquals(InvasionPhase.ACTIVE, idle.snapshot().tracks.getValue("player").invasion!!.phase)
        idle.transition(frame(1))
        assertEquals(InvasionPhase.RETIRING, idle.snapshot().tracks.getValue("player").invasion!!.phase)

        val unavailable = activeEngine()
        unavailable.transition(DirectorFrame(39, players = listOf(PlayerObservation("player", false, false, false))))
        assertEquals(InvasionPhase.ACTIVE, unavailable.snapshot().tracks.getValue("player").invasion!!.phase)
        unavailable.transition(DirectorFrame(1, players = listOf(PlayerObservation("player", false, false, false))))
        assertEquals(InvasionPhase.RETIRING, unavailable.snapshot().tracks.getValue("player").invasion!!.phase)
    }

    @Test fun `force reset restore and defensive copies preserve ownership`() {
        val engine = InvasionDirector.create(4L, spec)
        engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Force("player"))))
        val forced = engine.transition(frame(0, surface = true))
        assertTrue(forced.events.any { it.type == "warned" })
        val snapshot = engine.snapshot()
        snapshot.tracks.clear()
        assertTrue(engine.snapshot().tracks.isNotEmpty())

        val restored = InvasionDirector.restore(engine.snapshot(), spec)
        assertEquals(Json.encodeToString(engine.snapshot()), Json.encodeToString(restored.snapshot()))
        restored.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Reset("player"))))
        assertTrue(restored.snapshot().tracks.isEmpty())
        restored.transition(frame(1))
        restored.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Reset())))
        assertTrue(restored.snapshot().tracks.isEmpty())
    }

    @Test fun `roster planning is deterministic mixed budgeted and capped`() {
        val first = RosterPlanner.plan(recruits, 4, 33, rules, 44L)
        val second = RosterPlanner.plan(recruits, 4, 33, rules, 44L)
        assertEquals(first, second)
        assertTrue(first.size in 3..8)
        assertTrue(first.sumOf(MemberPlan::cost) <= 33)
        assertTrue(first.any { it.recruitId == "minecraft:pillager" })
        assertTrue(first.any { it.recruitId == "minecraft:vindicator" })
        assertTrue(first.count { it.recruitId == "minecraft:witch" } <= 1)
        assertTrue(first.count { it.recruitId == "minecraft:evoker" } <= 1)
        assertFailsWith<IllegalArgumentException> { RosterPlanner.plan(recruits, 0, 2, rules, 1L) }
    }

    @Test fun `surface approach respects solid height model unknowns and budget`() {
        val target = BlockPoint("minecraft:overworld", 0, 64, 0)
        val open = (-4..4).flatMap { x -> (-4..4).map { z -> SurfaceCell(x, 64, z) } }
        val selected = SurfaceApproach.choose(open, target, rules, 1L)
        assertNotNull(selected)
        assertTrue(maxOf(kotlin.math.abs(selected.x), kotlin.math.abs(selected.z)) in 2..4)

        val wall = open.map { if (it.x == 0 && it.z != 4) it.copy(bodyY = 67) else it }
        assertNotNull(SurfaceApproach.choose(wall, target, rules, 2L))
        assertNull(SurfaceApproach.choose(listOf(SurfaceCell(0, 64, 0)), target, rules, 3L))
        assertNull(SurfaceApproach.choose(open.map { it.copy(passable = false) }, target, rules, 3L))
    }

    private fun warnedEngine(): InvasionDirector {
        val engine = InvasionDirector.create(1L, spec)
        val warned = engine.transition(frame(100, surface = true))
        val warning = warned.effects.single { it.kind == EffectKind.WARN }
        engine.transition(frame(0, results = listOf(EffectResult(warning.effectId))))
        return engine
    }

    private fun activeEngine(): InvasionDirector {
        val engine = warnedEngine()
        val materialize = engine.transition(frame(20, surfaces = listOf(openGrid()))).effects.single { it.kind == EffectKind.MATERIALIZE }
        engine.transition(frame(0, results = listOf(EffectResult(materialize.effectId))))
        return engine
    }

    private fun frame(
        ticks: Long,
        surface: Boolean = true,
        results: List<EffectResult> = emptyList(),
        surfaces: List<SurfaceGridObservation> = emptyList(),
        defeats: List<MemberDefeatObservation> = emptyList(),
        combat: List<CombatObservation> = emptyList(),
        deaths: List<TargetDeathObservation> = emptyList(),
    ) = DirectorFrame(
        ticks,
        players = listOf(PlayerObservation("player", true, surface, true, BlockPoint("minecraft:overworld", 0, 64, 0))),
        surfaces = surfaces,
        effectResults = results,
        memberDefeats = defeats,
        combat = combat,
        targetDeaths = deaths,
    )

    private fun openGrid() = SurfaceGridObservation("player", (-4..4).flatMap { x -> (-4..4).map { z -> SurfaceCell(x, 64, z) } })
}
