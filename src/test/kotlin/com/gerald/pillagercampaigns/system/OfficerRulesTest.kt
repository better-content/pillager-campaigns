package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficerRulesTest {
    @Test
    fun replacementRollBlendsMemoryPredecessorLocalPressureAndMutationWithinBounds() {
        val memory = FactionWarMemory(
            successfulGenes = OfficerGeneProfile.neutral(10).copy(range = 90, speed = 80),
            failedGenes = OfficerGeneProfile.neutral(0).copy(melee = 70),
            mutationSeed = 123L,
            generation = 2,
        )
        val predecessor = officer().also { it.genes = OfficerGeneProfile.neutral(20).copy(speed = 95, survival = 85) }
        val local = OfficerGeneProfile.neutral(30).copy(siege = 90)

        val rolled = OfficerGeneRules.rollReplacement(memory, predecessor, local, seed = 99L)

        assertTrue(rolled.range in 0..100)
        assertTrue(rolled.speed > rolled.melee)
        assertTrue(rolled.survival > 25)
    }

    @Test
    fun outcomeRecordingPushesSuccessAndFailureMemoryAndAdvancesGeneration() {
        val memory = FactionWarMemory(mutationSeed = 4L)
        val genes = OfficerGeneProfile.neutral(20).copy(speed = 80, range = 70)

        OfficerGeneRules.recordOutcome(memory, genes, setOf(OfficerOutcome.PLAYER_KILL, OfficerOutcome.SCOUT_ESCAPED, OfficerOutcome.SQUAD_WIPED))

        assertEquals(1, memory.generation)
        assertTrue(memory.successfulGenes.speed > 0)
        assertTrue(memory.failedGenes.speed > 0)
        assertTrue(memory.mutationSeed != 4L)
    }

    @Test
    fun outcomeRulesEmitSuccessAndFailureSignals() {
        val officer = officer().also {
            it.victories = 3
            it.defeats = 5
            it.killedPlayers = 1
            it.escapedEncounters = 1
        }

        val outcomes = OfficerOutcomeRules.outcomesFor(officer)

        assertTrue(OfficerOutcome.PLAYER_KILL in outcomes)
        assertTrue(OfficerOutcome.PLAYER_DAMAGE in outcomes)
        assertTrue(OfficerOutcome.SCOUT_ESCAPED in outcomes)
        assertTrue(OfficerOutcome.SQUAD_SURVIVED in outcomes)
        assertTrue(OfficerOutcome.SQUAD_WIPED in outcomes)
    }

    @Test
    fun doctrineDerivesFromDominantGeneShape() {
        assertEquals(OfficerDoctrine.STALKER, OfficerDoctrineRules.doctrineFor(OfficerGeneProfile.neutral(5).copy(speed = 90, survival = 80)))
        assertEquals(OfficerDoctrine.HUNTER, OfficerDoctrineRules.doctrineFor(OfficerGeneProfile.neutral(5).copy(range = 90, speed = 70)))
        assertEquals(OfficerDoctrine.BREAKER, OfficerDoctrineRules.doctrineFor(OfficerGeneProfile.neutral(5).copy(melee = 90, armor = 80)))
        assertEquals(OfficerDoctrine.HEXER, OfficerDoctrineRules.doctrineFor(OfficerGeneProfile.neutral(5).copy(magic = 95, survival = 40)))
        assertEquals(OfficerDoctrine.BEASTMASTER, OfficerDoctrineRules.doctrineFor(OfficerGeneProfile.neutral(5).copy(beast = 95, siege = 50)))
    }

    @Test
    fun affixRulesUseGenesRankSlotsAndDeathHistory() {
        val genes = OfficerGeneProfile.neutral(10).copy(speed = 90, survival = 80, range = 90, armor = 90, melee = 90)
        val captainAffixes = OfficerAffixRules.affixesFor(genes, OfficerRank.CAPTAIN, setOf(OfficerOutcome.PLAYER_KILL))
        val bannerlordAffixes = OfficerAffixRules.affixesFor(genes, OfficerRank.BANNERLORD, setOf(OfficerOutcome.PLAYER_KILL))

        assertEquals(1, captainAffixes.size)
        assertEquals(3, bannerlordAffixes.size)
        assertTrue(OfficerAffix.SWIFT in bannerlordAffixes)
    }

    @Test
    fun engineeringTalentComesFromOfficerGenesDoctrineAndRank() {
        assertEquals(
            OfficerEngineeringTalent.NONE,
            OfficerEngineeringRules.talentFor(OfficerGeneProfile.neutral(20).copy(siege = 60, speed = 80), OfficerDoctrine.RAIDER, OfficerRank.SCOUT),
        )
        assertEquals(
            OfficerEngineeringTalent.BRIDGER,
            OfficerEngineeringRules.talentFor(OfficerGeneProfile.neutral(20).copy(siege = 70, speed = 60), OfficerDoctrine.RAIDER, OfficerRank.CAPTAIN),
        )
        assertEquals(
            OfficerEngineeringTalent.LADDERMASTER,
            OfficerEngineeringRules.talentFor(OfficerGeneProfile.neutral(20).copy(siege = 70, armor = 60), OfficerDoctrine.RAIDER, OfficerRank.CAPTAIN),
        )
        assertEquals(
            OfficerEngineeringTalent.FIELD_ENGINEER,
            OfficerEngineeringRules.talentFor(OfficerGeneProfile.neutral(20).copy(siege = 95, survival = 60), OfficerDoctrine.RAIDER, OfficerRank.LIEUTENANT),
        )
        assertTrue(OfficerEngineeringRules.canBridge(OfficerEngineeringTalent.FIELD_ENGINEER))
        assertTrue(OfficerEngineeringRules.canLadder(OfficerEngineeringTalent.FIELD_ENGINEER))
    }

    private fun officer() = PillagerOfficer(
        id = UUID.randomUUID(),
        name = "Krag",
        title = "the Gray",
        factionId = UUID.randomUUID(),
        homeBaseId = UUID.randomUUID(),
        rank = OfficerRank.CAPTAIN,
        role = OfficerRole.SCOUTMASTER,
        state = OfficerState.ACTIVE,
        victories = 0,
        defeats = 0,
        killedPlayers = 0,
        escapedEncounters = 0,
    )
}
