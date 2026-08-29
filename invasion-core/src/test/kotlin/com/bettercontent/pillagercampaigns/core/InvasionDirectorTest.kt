package com.bettercontent.pillagercampaigns.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvasionDirectorTest {
    private val rules = InvasionRules(
        scoutWindowMinTicks = 100, scoutWindowMaxTicks = 100,
        assaultWindowMinTicks = 1_000, assaultWindowMaxTicks = 1_000,
        assaultWarningSurfaceTicks = 20, deathGraceTicks = 250, timeTierTicks = 1_000,
        waveProgressTimeoutTicks = 50, scoutActiveTicks = 80, assaultActiveTicks = 500,
        activeIdleTicks = 300, targetUnavailableTicks = 40,
        approachMinimumBlocks = 2, approachMaximumBlocks = 4, approachTargetRadiusBlocks = 1,
        maximumSearchExpansions = 128,
    )
    private val roster = listOf(
        RecruitSpec("minecraft:pillager", 2, 0, RecruitRole.RANGED, weight = 4),
        RecruitSpec("takesapillage:archer", 2, 0, RecruitRole.RANGED, optional = true),
        RecruitSpec("takesapillage:skirmisher", 2, 0, RecruitRole.LINE, optional = true),
        RecruitSpec("minecraft:vindicator", 3, 1, RecruitRole.FRONTLINE, weight = 3),
        RecruitSpec("takesapillage:legioner", 3, 1, RecruitRole.FRONTLINE, optional = true),
        RecruitSpec("minecraft:witch", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1),
        RecruitSpec("savage_and_ravage:griefer", 4, 2, RecruitRole.LINE, maximumPerSquad = 2, optional = true),
        RecruitSpec("savage_and_ravage:trickster", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1, optional = true),
        RecruitSpec("savage_and_ravage:executioner", 5, 3, RecruitRole.ELITE, maximumPerSquad = 1, optional = true),
        RecruitSpec("savage_and_ravage:iceologer", 5, 3, RecruitRole.ELITE, maximumPerSquad = 1, optional = true),
        RecruitSpec("minecraft:evoker", 6, 4, RecruitRole.ELITE, maximumPerSquad = 1),
        RecruitSpec("minecraft:ravager", 8, 5, RecruitRole.ELITE, maximumPerSquad = 1),
    )
    private val spec = InvasionRuntimeSpec.create(rules, roster)

    @Test fun `fixed 1024 seed corpus is deterministic and clocks remain in authored bands`() {
        val first = (0L..1_023L).map(::timeline)
        val second = (0L..1_023L).map(::timeline)
        assertEquals(first, second)
        first.forEach { timeline ->
            assertTrue(timeline.scoutDue in 7_200L..14_400L)
            assertTrue(timeline.assaultDue in 72_000L..144_000L)
            assertTrue(timeline.scouts >= 4)
            assertTrue(timeline.assaults >= 1)
        }
    }

    @Test fun `eligible clocks pause offline underground delivery waits and transport label is absent`() {
        val engine = InvasionDirector.create(1, fixedSpec())
        engine.transition(playerFrame(50, eligible = false, surface = false))
        assertEquals(0, engine.snapshot().tracks["player"]!!.eligibleTicks)
        engine.transition(playerFrame(100, surface = false))
        val track = engine.snapshot().tracks["player"]!!
        assertEquals(100, track.eligibleTicks)
        assertNull(track.invasion)
        engine.transition(playerFrame(0, surface = true))
        assertEquals(EncounterKind.SCOUT, track(engine).invasion?.kind)
        val descriptor = PlayerObservation.serializer().descriptor
        assertFalse((0 until descriptor.elementsCount).map(descriptor::getElementName).contains("transport"))
    }

    @Test fun `scouts are silent cohesive bounded and withdraw exactly`() {
        val engine = InvasionDirector.create(2, fixedSpec())
        val started = engine.transition(playerFrame(100))
        assertTrue(started.effects.none { it.kind == EffectKind.WARN })
        val invasion = track(engine).invasion!!
        assertEquals(EncounterKind.SCOUT, invasion.kind)
        assertEquals(3, invasion.members.size)
        assertTrue(invasion.members.all { member ->
            roster.first { it.entityId == member.recruitId }.role in setOf(RecruitRole.LINE, RecruitRole.RANGED)
        })
        materializeCurrent(engine)
        engine.transition(playerFrame(79))
        assertEquals(InvasionPhase.ACTIVE, track(engine).invasion!!.phase)
        val retiring = engine.transition(playerFrame(1))
        assertTrue(retiring.effects.any { it.kind == EffectKind.RETIRE })
    }

    @Test fun `assault warns for surface time then uses three progress-gated waves`() {
        val engine = InvasionDirector.create(3, fixedSpec())
        val forced = engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Force("player", EncounterKind.ASSAULT))))
        assertTrue(forced.events.any { it.type == "forced" })
        val warned = engine.transition(playerFrame(0))
        val warn = warned.effects.single { it.kind == EffectKind.WARN }
        engine.transition(playerFrame(0, results = listOf(EffectResult(warn.effectId))))
        engine.transition(playerFrame(19, surfaces = listOf(grid())))
        assertTrue(engine.snapshot().pendingEffects.values.none { it.kind == EffectKind.MATERIALIZE })
        engine.transition(playerFrame(1, surfaces = listOf(grid())))
        drainWave(engine)
        var invasion = track(engine).invasion!!
        assertEquals(0, invasion.currentWave)
        val half = ceil(invasion.waves[0].members.size / 2.0).toInt()
        engine.transition(playerFrame(0, defeats = invasion.waves[0].members.take(half).map {
            MemberDefeatObservation(invasion.invasionId, it.memberId)
        }))
        engine.transition(playerFrame(1))
        invasion = track(engine).invasion!!
        assertEquals(1, invasion.currentWave)
        engine.transition(playerFrame(0, surfaces = listOf(grid())))
        drainWave(engine)
        engine.transition(playerFrame(50))
        assertEquals(2, track(engine).invasion!!.currentWave)
    }

    @Test fun `group scaling shares one encounter and exact provisional sizes`() {
        assertEquals(listOf(3, 3, 4, 4, 5, 6), (0..5).map {
            EncounterPolicy.memberCount(EncounterKind.SCOUT, it, 1, InvasionRules())
        })
        assertEquals(listOf(8, 8, 9, 10, 11, 12), (0..5).map {
            EncounterPolicy.memberCount(EncounterKind.ASSAULT, it, 1, InvasionRules())
        })
        assertEquals(18, EncounterPolicy.memberCount(EncounterKind.ASSAULT, 5, 2, InvasionRules()))
        assertEquals(24, EncounterPolicy.memberCount(EncounterKind.ASSAULT, 5, 4, InvasionRules()))
        val engine = InvasionDirector.create(4, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("a", EncounterKind.ASSAULT), DirectorCommand.Force("b", EncounterKind.ASSAULT),
        )))
        engine.transition(DirectorFrame(0, players = listOf(observation("a", 0), observation("b", 20))))
        val primaries = engine.snapshot().tracks.values.mapNotNull(PlayerPressureTrack::invasion)
        assertEquals(1, primaries.size)
        assertEquals(listOf("a", "b"), primaries.single().participantPlayerIds)
        assertEquals(12, primaries.single().waves.first().members.size)
    }

    @Test fun `wave rosters obey roles budgets optional eligibility and elite rules`() {
        for (seed in 0L..1_023L) {
            val id = "assault:test:$seed"
            for (intensity in 0..5) for (wave in 0..2) {
                val size = EncounterPolicy.memberCount(EncounterKind.ASSAULT, intensity, 1, rules)
                val plan = RosterPlanner.planWave(roster, EncounterKind.ASSAULT, intensity, wave, size, id, seed)
                assertEquals(EncounterPolicy.waveBudget(size, intensity, wave), plan.budget)
                assertTrue(plan.members.sumOf(MemberPlan::cost) <= plan.budget)
                val chosen = plan.members.map { member -> roster.first { it.entityId == member.recruitId } }
                assertTrue(chosen.count { it.role == RecruitRole.SUPPORT } <= 1)
                assertTrue(chosen.count { it.role == RecruitRole.ELITE } <= 1)
                if (wave == 0) assertTrue(chosen.all { it.role in setOf(RecruitRole.LINE, RecruitRole.RANGED) })
                if (wave == 2 && intensity >= 3) assertTrue(chosen.any { it.role == RecruitRole.ELITE })
                if (intensity < 5) assertTrue(chosen.none { it.entityId == "minecraft:ravager" })
            }
        }
        val fallback = roster.filterNot(RecruitSpec::optional)
        val plan = RosterPlanner.planWave(fallback, EncounterKind.ASSAULT, 5, 2, 12, "fallback", 9)
        assertEquals(12, plan.members.size)
        roster.filter(RecruitSpec::optional).forEach { missing ->
            assertEquals(8, RosterPlanner.planWave(roster - missing, EncounterKind.ASSAULT, 0, 0, 8,
                "missing:${missing.entityId}", 1).members.size)
        }
    }

    @Test fun `packet caps low-health spacing global allowance and round-robin are hard`() {
        val engine = InvasionDirector.create(5, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("a", EncounterKind.ASSAULT), DirectorCommand.Force("b", EncounterKind.ASSAULT),
        )))
        val far = listOf(observation("a", 0, low = true), observation("b", 100))
        var transition = engine.transition(DirectorFrame(0, players = far))
        acknowledge(engine, transition)
        engine.transition(DirectorFrame(20, players = far, surfaces = listOf(grid("a", 0), grid("b", 100))))
        transition = engine.transition(DirectorFrame(0, players = far, liveCampaignMobs = 90, secondSpawnCount = 20))
        assertTrue(transition.effects.filter { it.kind == EffectKind.MATERIALIZE }.sumOf { it.members.size } <= 4)
        acknowledge(engine, transition)
        val served = mutableSetOf<String>()
        repeat(4) {
            transition = engine.transition(DirectorFrame(60, players = far, surfaces = listOf(grid("a", 0), grid("b", 100))))
            transition.effects.filter { it.kind == EffectKind.MATERIALIZE }.forEach {
                assertTrue(it.members.size <= 6)
                assertTrue(it.members.count { member -> roster.first { recruit -> recruit.entityId == member.recruitId }.role == RecruitRole.ELITE } <= 1)
                served += it.playerId
            }
            acknowledge(engine, transition)
        }
        assertEquals(setOf("a", "b"), served)
        assertTrue(track(engine, "a").invasion!!.nextPacketTick >= engine.snapshot().tick)
    }

    @Test fun `downed retirement clears queued work death grace and assault clear adjusts only assaults`() {
        val engine = InvasionDirector.create(6, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Force("player", EncounterKind.ASSAULT))))
        acknowledge(engine, engine.transition(playerFrame(0)))
        engine.transition(playerFrame(20, surfaces = listOf(grid())))
        engine.transition(playerFrame(0, surfaces = listOf(grid())))
        assertTrue(engine.snapshot().pendingEffects.values.any { it.kind == EffectKind.MATERIALIZE })
        val retire = engine.transition(DirectorFrame(0, players = listOf(observation("player", downed = true))))
        assertTrue(retire.effects.none { it.kind == EffectKind.MATERIALIZE })
        acknowledge(engine, retire)
        val track = track(engine)
        assertNull(track.invasion)
        assertEquals(-1, track.outcomeAdjustment)
        assertTrue(track.nextAssaultEligibleTick >= track.eligibleTicks + 250)
    }

    @Test fun `route requires a complete connected loaded observation and rejects partial progress`() {
        val target = BlockPoint("minecraft:overworld", 0, 64, 0)
        val open = (-4..4).map { x -> SurfaceCell(x, 64, 0) }
        assertNotNull(SurfaceApproach.choose(open, target, rules, 1))
        assertNull(SurfaceApproach.choose(open.filter { it.x !in -1..1 }, target, rules, 1))
        assertNull(SurfaceApproach.choose(open.map { if (kotlin.math.abs(it.x) == 1) it.copy(bodyY = 68) else it }, target, rules, 1))
        assertNull(SurfaceApproach.choose(open.map { it.copy(passable = false) }, target, rules, 1))
    }

    @Test fun `restore is exact schema is strict and invalid inputs fail closed`() {
        spec.requireValid()
        assertEquals(spec.revision, spec.computedRevision())
        assertFailsWith<IllegalArgumentException> { spec.copy(revision = "drift").requireValid() }
        assertFailsWith<IllegalArgumentException> { InvasionRuntimeSpec.create(rules, emptyList()).requireValid() }
        assertFailsWith<IllegalArgumentException> { InvasionRules(scoutWindowMinTicks = 2, scoutWindowMaxTicks = 1) }
        val engine = InvasionDirector.create(7, spec)
        engine.transition(playerFrame(50))
        val restored = InvasionDirector.restore(engine.snapshot(), spec)
        assertEquals(Json.encodeToString(engine.snapshot()), Json.encodeToString(restored.snapshot()))
        assertFailsWith<IllegalArgumentException> {
            InvasionDirector.restore(engine.snapshot().copy(schemaVersion = 1), spec)
        }
        engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Reset("player"))))
        assertTrue(engine.snapshot().tracks.isEmpty())
        engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Reset())))
    }

    private data class Timeline(val scoutDue: Long, val assaultDue: Long, val scouts: Int, val assaults: Int)

    private fun timeline(seed: Long): Timeline {
        val corpusRules = InvasionRules(
            scoutWindowMinTicks = 7_200, scoutWindowMaxTicks = 14_400,
            assaultWindowMinTicks = 72_000, assaultWindowMaxTicks = 144_000,
            assaultWarningSurfaceTicks = 0, scoutActiveTicks = 20, activeIdleTicks = 20,
            approachMinimumBlocks = 2, approachMaximumBlocks = 4, approachTargetRadiusBlocks = 1,
        )
        val engine = InvasionDirector.create(seed, InvasionRuntimeSpec.create(corpusRules, roster))
        engine.transition(playerFrame(0))
        val initial = track(engine)
        val scoutDue = initial.nextScoutEligibleTick
        val assaultDue = initial.nextAssaultEligibleTick
        var scouts = 0
        var assaults = 0
        repeat(1_000) {
            var transition = engine.transition(playerFrame(200, surfaces = listOf(grid())))
            scouts += transition.events.count { event -> event.type == "scout_started" }
            assaults += transition.events.count { event -> event.type == "assault_warned" }
            if (transition.effects.isNotEmpty()) {
                transition = engine.transition(playerFrame(0, results = transition.effects.map { EffectResult(it.effectId) }))
                scouts += transition.events.count { event -> event.type == "scout_started" }
                assaults += transition.events.count { event -> event.type == "assault_warned" }
            }
        }
        return Timeline(scoutDue, assaultDue, scouts, assaults)
    }

    private fun fixedSpec() = InvasionRuntimeSpec.create(rules, roster)
    private fun observation(id: String, x: Int = 0, low: Boolean = false, downed: Boolean = false) =
        PlayerObservation(id, true, true, true, BlockPoint("minecraft:overworld", x, 64, 0), low, downed)
    private fun playerFrame(
        ticks: Long, eligible: Boolean = true, surface: Boolean = true,
        results: List<EffectResult> = emptyList(), surfaces: List<SurfaceGridObservation> = emptyList(),
        defeats: List<MemberDefeatObservation> = emptyList(),
    ) = DirectorFrame(ticks, listOf(PlayerObservation("player", eligible, surface, true,
        BlockPoint("minecraft:overworld", 0, 64, 0))), surfaces, results, defeats)
    private fun grid(id: String = "player", center: Int = 0) = SurfaceGridObservation(
        id, (-4..4).map { SurfaceCell(center + it, 64, 0) }, complete = true,
    )
    private fun track(engine: InvasionDirector, id: String = "player") = engine.snapshot().tracks.getValue(id)

    private fun acknowledge(engine: InvasionDirector, transition: DirectorTransition) {
        if (transition.effects.isNotEmpty()) engine.transition(DirectorFrame(0,
            effectResults = transition.effects.map { EffectResult(it.effectId) }))
    }

    private fun materializeCurrent(engine: InvasionDirector) {
        val transition = engine.transition(playerFrame(0, surfaces = listOf(grid())))
        acknowledge(engine, transition)
    }

    private fun drainWave(engine: InvasionDirector) {
        repeat(4) {
            val transition = engine.transition(playerFrame(20, surfaces = listOf(grid())))
            acknowledge(engine, transition)
            val invasion = track(engine).invasion ?: return
            if (invasion.waves[invasion.currentWave].materializedMembers == invasion.waves[invasion.currentWave].members.size) return
        }
    }
}
