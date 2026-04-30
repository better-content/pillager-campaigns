package com.gerald.pillagerpressure.util

import com.gerald.pillagerpressure.data.BaseState
import com.gerald.pillagerpressure.data.CampaignState
import com.gerald.pillagerpressure.data.OfficerRole
import com.gerald.pillagerpressure.data.PillagerBase
import com.gerald.pillagerpressure.data.PillagerCampaign
import com.gerald.pillagerpressure.data.PillagerFaction
import com.gerald.pillagerpressure.data.PillagerOfficer
import com.gerald.pillagerpressure.system.OfficerEngineeringRules
import com.gerald.pillagerpressure.system.OfficerLoadoutRules
import com.gerald.pillagerpressure.system.SquadCompositionPressure
import com.gerald.pillagerpressure.system.SquadCompositionRules

/**
 * Pure story/order generator. Does not touch Minecraft runtime classes.
 */
data class OfficerOrders(val title: String, val loreLines: List<String>)

object OfficerOrdersRules {
    const val MAX_LORE_LINES = 10

    fun generate(
        faction: PillagerFaction,
        base: PillagerBase,
        officer: PillagerOfficer?,
        campaign: PillagerCampaign? = null,
    ): OfficerOrders {
        val title = when {
            officer == null -> "Recovered Field Orders"
            campaign != null -> "Orders of ${officer.name}"
            else -> "Officer Orders"
        }

        val lines = mutableListOf<String>()
        lines += "Faction: ${faction.name}"
        lines += "Assigned Base: ${base.center.x}, ${base.center.z}; ${baseStatePhrase(base.state)}"
        lines += "Command: ${officerDirective(officer, campaign)}"
        lines += "Priority: ${priorityLine(base, campaign)}"

        officer?.let {
            lines += "Officer: ${it.displayName()} (${it.doctrine.name.lowercase().replace('_', ' ')})"
            lines += "Engineering: ${OfficerEngineeringRules.talentFor(it).name.lowercase().replace('_', ' ')}"
            lines += "Loadout: ${loadoutLine(it)}"
            lines += "Squad: ${squadLine(it)}"
            if (it.lineage.predecessorOfficerId != null) lines += "Succession: ${it.lineage.causeOfSuccession}"
        } ?: run {
            lines += "Officer: unknown"
        }

        campaign?.let {
            lines += "Campaign: ${campaignStatePhrase(it.state)}; Route: ${it.current.x},${it.current.z} -> ${it.target.x},${it.target.z}"
        }

        return OfficerOrders(title, lines.take(MAX_LORE_LINES))
    }

    private fun loadoutLine(officer: PillagerOfficer): String {
        val loadout = OfficerLoadoutRules.forOfficer(officer)
        val offhand = loadout.offhand?.substringAfter(':') ?: "empty"
        return "${loadout.mainhand.substringAfter(':')} + $offhand"
    }

    private fun squadLine(officer: PillagerOfficer): String {
        val plan = SquadCompositionRules.plan(officer.doctrine, officer.rank, OfficerEngineeringRules.talentFor(officer), SquadCompositionPressure.fromGenes(officer.genes))
        return plan.summary.substringAfter(": ")
    }

    private fun baseStatePhrase(state: BaseState): String = when (state) {
        BaseState.ACTIVE -> "fortified and supplied"
        BaseState.DAMAGED -> "repair and hold"
        BaseState.DESTROYED -> "abandoned"
        BaseState.RECLAIMABLE -> "prepare reclamation"
    }

    private fun campaignStatePhrase(state: CampaignState): String = when (state) {
        CampaignState.SCOUTING -> "scouting the frontier"
        CampaignState.APPROACHING_INTEL -> "moving to reported target"
        CampaignState.SEARCHING -> "search pattern in progress"
        CampaignState.ENGAGING -> "engagement underway"
        CampaignState.RETREATING_WITH_INTEL -> "retreating with intel"
        CampaignState.RETURNING_TO_BASE -> "returning to base"
        CampaignState.EXPANDING -> "securing new ground"
        CampaignState.SUPPLYING -> "resupply operation"
        CampaignState.DISBANDED -> "force scattered"
    }

    private fun officerDirective(officer: PillagerOfficer?, campaign: PillagerCampaign?): String {
        if (officer == null) return "recover banner intelligence"
        if (campaign != null) return campaignStatePhrase(campaign.state)
        return when (officer.role) {
            OfficerRole.SCOUTMASTER -> "track settlements and weak routes"
            OfficerRole.SKIRMISHER -> "probe defenses and withdraw fast"
            OfficerRole.SIEGE_ENGINEER -> "prepare breaching tools"
            OfficerRole.BANNER_BEARER -> "rally squads under the standard"
            OfficerRole.BEAST_HANDLER -> "drive beasts into enemy lines"
            OfficerRole.WITCH_TOUCHED -> "spread fear and curses"
            OfficerRole.HUNTER -> "pursue marked prey"
        }
    }

    private fun priorityLine(base: PillagerBase, campaign: PillagerCampaign?): String {
        if (campaign != null && campaign.state == CampaignState.ENGAGING) return "break resistance"
        if (base.supplies < 20) return "recover supplies"
        if (base.manpower < 20) return "recruit fresh raiders"
        return "pressure nearby villages"
    }
}
