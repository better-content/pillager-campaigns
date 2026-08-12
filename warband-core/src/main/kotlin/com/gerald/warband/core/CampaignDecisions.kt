package com.gerald.warband.core

enum class ActiveCampaignDecision { CONTINUE, DEFEATED, MORALE_RETURN, IDLE_RETURN }

internal object CampaignDecisions {
    fun activeDecision(
        aliveMembers: Int,
        liveThreat: Double,
        committedThreat: Double,
        conservationPreference: Double,
        aggression: Int,
        now: Long,
        lastCombatTick: Long,
        rules: CoreRules = CoreRules(),
    ): ActiveCampaignDecision = when {
        aliveMembers < 1 -> ActiveCampaignDecision.DEFEATED
        committedThreat > 0.0 && liveThreat / committedThreat <=
            FormulaMath.retreatThreshold(conservationPreference, aggression, rules) -> ActiveCampaignDecision.MORALE_RETURN
        now - lastCombatTick >= rules.idleReturnTicks -> ActiveCampaignDecision.IDLE_RETURN
        else -> ActiveCampaignDecision.CONTINUE
    }
}
