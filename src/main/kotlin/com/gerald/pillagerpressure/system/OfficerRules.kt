package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.data.*
import java.util.Random

object OfficerGeneRules {
    fun rollReplacement(
        factionMemory: FactionWarMemory,
        predecessor: PillagerOfficer?,
        basePressure: OfficerGeneProfile = OfficerGeneProfile.neutral(35),
        seed: Long,
    ): OfficerGeneProfile {
        val rng = Random(seed xor factionMemory.mutationSeed)
        val learned = factionMemory.learnedPreference().scaled(0.50)
        val inherited = (predecessor?.genes ?: OfficerGeneProfile.neutral()).scaled(0.25)
        val local = basePressure.scaled(0.15)
        val mutation = OfficerGeneProfile(
            rng.nextInt(21) - 10, rng.nextInt(21) - 10, rng.nextInt(21) - 10, rng.nextInt(21) - 10, rng.nextInt(21) - 10,
            rng.nextInt(21) - 10, rng.nextInt(21) - 10, rng.nextInt(21) - 10, rng.nextInt(21) - 10, rng.nextInt(21) - 10,
        )
        return learned.plus(inherited).plus(local).plus(mutation).clamped()
    }

    fun recordOutcome(memory: FactionWarMemory, genes: OfficerGeneProfile, outcomes: Set<OfficerOutcome>): FactionWarMemory {
        val success = outcomes.sumOf { outcomeWeight(it).coerceAtLeast(0) }
        val failure = outcomes.sumOf { (-outcomeWeight(it)).coerceAtLeast(0) }
        if (success > 0) memory.successfulGenes = memory.successfulGenes.plus(genes.scaled(success / 10.0)).clamped()
        if (failure > 0) memory.failedGenes = memory.failedGenes.plus(genes.scaled(failure / 10.0)).clamped()
        memory.generation += 1
        memory.mutationSeed = memory.mutationSeed * 31L + outcomes.fold(17L) { acc, outcome -> acc * 31L + outcome.ordinal }
        return memory
    }

    private fun outcomeWeight(outcome: OfficerOutcome): Int = when (outcome) {
        OfficerOutcome.PLAYER_KILL -> 10
        OfficerOutcome.PLAYER_DAMAGE -> 4
        OfficerOutcome.SCOUT_ESCAPED -> 7
        OfficerOutcome.SQUAD_SURVIVED -> 5
        OfficerOutcome.REACHED_TARGET -> 5
        OfficerOutcome.PLACED_FLAGS -> 6
        OfficerOutcome.DIED_QUICKLY -> -8
        OfficerOutcome.SQUAD_WIPED -> -6
        OfficerOutcome.NO_PLAYER_DAMAGE -> -5
        OfficerOutcome.FAILED_TO_FIND_PLAYER -> -4
        OfficerOutcome.BASE_DESTROYED -> -10
    }
}

object OfficerOutcomeRules {
    fun outcomesFor(officer: PillagerOfficer): Set<OfficerOutcome> {
        val outcomes = linkedSetOf<OfficerOutcome>()
        if (officer.killedPlayers > 0) outcomes += OfficerOutcome.PLAYER_KILL
        if (officer.victories > 0 || officer.killedPlayers > 0) outcomes += OfficerOutcome.PLAYER_DAMAGE
        if (officer.escapedEncounters > 0) outcomes += OfficerOutcome.SCOUT_ESCAPED
        if (officer.victories >= 3) outcomes += OfficerOutcome.SQUAD_SURVIVED
        if (officer.victories >= officer.defeats && officer.victories > 0) outcomes += OfficerOutcome.REACHED_TARGET
        if (officer.defeats >= 5) outcomes += OfficerOutcome.SQUAD_WIPED
        if (officer.defeats > 0 && officer.victories == 0 && officer.killedPlayers == 0) outcomes += OfficerOutcome.DIED_QUICKLY
        if (officer.victories == 0 && officer.killedPlayers == 0) outcomes += OfficerOutcome.NO_PLAYER_DAMAGE
        if (officer.victories == 0 && officer.escapedEncounters == 0) outcomes += OfficerOutcome.FAILED_TO_FIND_PLAYER
        return outcomes
    }
}

object OfficerDoctrineRules {
    fun doctrineFor(genes: OfficerGeneProfile): OfficerDoctrine {
        val top = genes.topGenes(3).map { it.first }.toSet()
        return when {
            "magic" in top -> OfficerDoctrine.HEXER
            "speed" in top && "survival" in top -> OfficerDoctrine.STALKER
            "range" in top && ("speed" in top || "survival" in top) -> OfficerDoctrine.HUNTER
            "melee" in top && ("armor" in top || "siege" in top) -> OfficerDoctrine.BREAKER
            "banner" in top -> OfficerDoctrine.STANDARD
            "beast" in top -> OfficerDoctrine.BEASTMASTER
            "fire" in top -> OfficerDoctrine.ARSONIST
            "siege" in top -> OfficerDoctrine.SIEGE_CAPTAIN
            "survival" in top -> OfficerDoctrine.SURVIVOR
            else -> OfficerDoctrine.RAIDER
        }
    }
}

object OfficerAffixRules {
    fun affixesFor(genes: OfficerGeneProfile, rank: OfficerRank, outcomes: Set<OfficerOutcome> = emptySet()): Set<OfficerAffix> {
        val candidates = linkedSetOf<OfficerAffix>()
        if (genes.speed + genes.survival >= 130) candidates += OfficerAffix.SWIFT
        if (genes.range + genes.speed >= 125) candidates += OfficerAffix.LONGSHOT
        if (genes.armor + genes.melee >= 125) candidates += OfficerAffix.IRONBOUND
        if (genes.magic + genes.survival >= 120) candidates += OfficerAffix.WITCH_TOUCHED
        if (genes.banner + genes.armor >= 120) candidates += OfficerAffix.BANNERED
        if (genes.fire + genes.siege >= 120) candidates += OfficerAffix.ASHEN
        if (genes.beast + genes.siege >= 120) candidates += OfficerAffix.BEAST_CALLER
        if (OfficerOutcome.PLAYER_KILL in outcomes) candidates += OfficerAffix.GRAVE_MARKED
        return candidates.take(rankAffixSlots(rank)).toSet()
    }

    fun rankAffixSlots(rank: OfficerRank): Int = when (rank) {
        OfficerRank.SCOUT, OfficerRank.CAPTAIN -> 1
        OfficerRank.LIEUTENANT, OfficerRank.WARLORD -> 2
        OfficerRank.BANNERLORD -> 3
    }
}

object OfficerEngineeringRules {
    fun talentFor(officer: PillagerOfficer): OfficerEngineeringTalent = talentFor(officer.genes, officer.doctrine, officer.rank)

    fun talentFor(genes: OfficerGeneProfile, doctrine: OfficerDoctrine, rank: OfficerRank): OfficerEngineeringTalent {
        if (rank == OfficerRank.SCOUT && genes.siege < 70) return OfficerEngineeringTalent.NONE
        if (genes.siege + genes.survival >= 145 || doctrine == OfficerDoctrine.SIEGE_CAPTAIN) return OfficerEngineeringTalent.FIELD_ENGINEER
        if (genes.siege + genes.speed >= 125) return OfficerEngineeringTalent.BRIDGER
        if (genes.siege + genes.armor >= 125 || genes.siege + genes.melee >= 125) return OfficerEngineeringTalent.LADDERMASTER
        return OfficerEngineeringTalent.NONE
    }

    fun canBridge(talent: OfficerEngineeringTalent): Boolean =
        talent == OfficerEngineeringTalent.BRIDGER || talent == OfficerEngineeringTalent.FIELD_ENGINEER

    fun canLadder(talent: OfficerEngineeringTalent): Boolean =
        talent == OfficerEngineeringTalent.LADDERMASTER || talent == OfficerEngineeringTalent.FIELD_ENGINEER
}
