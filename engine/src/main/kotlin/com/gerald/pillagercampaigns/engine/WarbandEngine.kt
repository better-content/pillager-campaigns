package com.gerald.pillagercampaigns.engine

import kotlin.math.ceil

object WarbandEngine {
    fun transition(
        state: EngineState,
        frame: EngineFrame,
        catalog: EngineCatalog,
        rules: WarbandRules = WarbandRules(),
    ): TransitionResult {
        require(frame.elapsedTicks >= 0L) { "elapsed ticks must be nonnegative" }
        validateCatalog(catalog)
        val events = mutableListOf<EngineEvent>()
        val effects = mutableListOf<EngineEffect>()
        state.tick += frame.elapsedTicks

        applyMaterializations(state, frame.materializations, events)
        applyPositions(state, frame.physicalPositions)
        frame.combat.forEach { observeCombat(state, catalog, rules, it, events, effects) }
        frame.commands.forEach { command ->
            when (command) {
                is EngineCommand.Dispatch -> dispatch(state, catalog, rules, command.warbandId, command.playerId, events)
                is EngineCommand.BeginReturn -> beginReturn(state, command.campaignId, command.reason, command.aggressionDelta, events, effects)
                is EngineCommand.Dematerialize -> dematerialize(state, command.campaignId, events)
                is EngineCommand.Manufacture -> repeat(command.count.coerceAtLeast(0)) {
                    state.warbands[command.warbandId]?.let { manufacture(state, it, catalog, rules, events) }
                }
            }
        }

        advanceEconomies(state, catalog, rules, frame.elapsedTicks, events)
        automaticDispatch(state, catalog, rules, frame.players, events)
        advanceCampaigns(state, rules, frame.players, frame.elapsedTicks, events, effects)
        validate(state, catalog, rules)
        return TransitionResult(state, events, effects)
    }

    fun chooseRecruit(
        state: EngineState,
        warband: WarbandState,
        officer: OfficerState?,
        catalog: EngineCatalog,
        budget: Double,
        members: List<MemberManifest> = emptyList(),
        rules: WarbandRules = WarbandRules(),
    ): RecruitDefinition? {
        val preferences = rules.effectivePreferences(warband, officer)
        return catalog.recruits.asSequence()
            .filter { observedThreat(warband, it) <= budget + EPSILON }
            .maxWithOrNull(compareBy<RecruitDefinition> { recruitScore(warband, it, preferences) }
                .thenByDescending { deterministicTie(warband.id, it.id, state.sequence + members.size) })
    }

    fun validate(state: EngineState, catalog: EngineCatalog, rules: WarbandRules = WarbandRules()) {
        require(state.tick >= 0L && state.sequence >= 0L)
        state.warbands.values.forEach { warband ->
            require(warband.reserveThreat >= -EPSILON && warband.raidPool >= -EPSILON && warband.garrisonThreat >= -EPSILON)
            require(warband.capacity > 0.0 && warband.aggression in rules.minimumAggression..rules.maximumAggression)
            require(warband.materialLedger.values.all { it.isFinite() && it >= -EPSILON })
            require(warband.preferences.values.all(Double::isFinite))
        }
        val memberIds = state.campaigns.values.flatMap { it.members }.map { it.id }
        require(memberIds.distinct().size == memberIds.size) { "duplicate deployed member identity" }
        val equipmentIds = state.warbands.values.flatMap { it.armory }.map { it.id } +
            state.campaigns.values.flatMap { it.members }.mapNotNull { it.equipment?.id }
        require(equipmentIds.distinct().size == equipmentIds.size) { "duplicate equipment identity" }
        val targets = state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }.map { it.targetPlayerId }
        require(targets.distinct().size == targets.size) { "multiple unresolved campaigns target one player" }
        state.campaigns.values.flatMap { it.members }.forEach { member ->
            require(catalog.recruits.any { it.id == member.recruitId }) { "unknown recruit ${member.recruitId}" }
            require(member.threat > 0.0 && member.healthFraction in 0.0..1.0)
        }
    }

    private fun advanceEconomies(
        state: EngineState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        elapsed: Long,
        events: MutableList<EngineEvent>,
    ) {
        var remaining = elapsed
        while (remaining > 0L) {
            val slice = minOf(20L, remaining)
            advanceEconomySlice(state, catalog, rules, slice, events)
            remaining -= slice
        }
    }

    private fun advanceEconomySlice(
        state: EngineState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        elapsed: Long,
        events: MutableList<EngineEvent>,
    ) {
        state.warbands.values.filterNot(WarbandState::defeated).forEach { warband ->
            val deployed = state.campaigns.values.filter { it.warbandId == warband.id && it.phase != CampaignPhase.RESOLVED }
                .sumOf { campaign -> campaign.members.sumOf { it.threat * it.healthFraction } }
            warband.recruitTickDebt += elapsed
            val recruitTicks = rules.recruitTicksPerThreat(warband.environment)
            while (warband.recruitTickDebt + EPSILON >= recruitTicks &&
                warband.reserveThreat + warband.raidPool + warband.garrisonThreat + deployed < warband.capacity) {
                warband.recruitTickDebt -= recruitTicks
                warband.reserveThreat += 1.0
                events += event(state, "recruited", warband.id, "threat=1")
                manufacture(state, warband, catalog, rules, events)
            }

            warband.extractionTickDebt += elapsed
            val extractionTicks = rules.extractionTicks(warband.environment)
            while (warband.extractionTickDebt + EPSILON >= extractionTicks) {
                warband.extractionTickDebt -= extractionTicks
                extract(state, warband, catalog, events)
            }

            warband.mobilizationTickDebt += elapsed
            val mobilizationTicks = rules.mobilizationTicksPerThreat(warband.environment)
            while (warband.mobilizationTickDebt + EPSILON >= mobilizationTicks && warband.reserveThreat >= 1.0) {
                warband.mobilizationTickDebt -= mobilizationTicks
                warband.reserveThreat -= 1.0
                warband.raidPool += 1.0
                events += event(state, "mobilized", warband.id, "threat=1")
            }
        }
    }

    private fun extract(state: EngineState, warband: WarbandState, catalog: EngineCatalog, events: MutableList<EngineEvent>) {
        val available = warband.reserveThreat *
            (0.5 + warband.environment.mineralPotential + warband.environment.exoticPotential)
        val selected = catalog.materials.asSequence().filter { it.extractionCost <= available + EPSILON }
            .maxWithOrNull(compareBy<MaterialDefinition> {
                it.tier * (warband.environment.mineralPotential + warband.environment.exoticPotential * it.tier) +
                    it.capabilities.dot(preferences(warband))
            }.thenByDescending { deterministicTie(warband.id, it.id, state.sequence) }) ?: return
        warband.materialLedger[selected.id] = warband.materialLedger.getOrDefault(selected.id, 0.0) + 1.0
        events += event(state, "extracted", warband.id, selected.id)
    }

    private fun manufacture(
        state: EngineState,
        warband: WarbandState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        events: MutableList<EngineEvent>,
    ) {
        val selected = catalog.equipment.asSequence()
            .filter { definition -> definition.cost.all { (id, amount) -> warband.materialLedger.getOrDefault(id, 0.0) + EPSILON >= amount } }
            .maxWithOrNull(compareBy<EquipmentDefinition> { definition ->
                definition.capabilities.dot(preferences(warband)) / (definition.cost.values.sum() + 1.0)
            }.thenByDescending { deterministicTie(warband.id, it.id, state.sequence) }) ?: return
        selected.cost.forEach { (id, amount) -> warband.materialLedger[id] = (warband.materialLedger.getOrDefault(id, 0.0) - amount).coerceAtLeast(0.0) }
        val manifest = EquipmentManifest(nextId(state, "equipment"), selected.id, selected.formulation, selected.cost, selected.capabilities, selected.actions)
        warband.armory += manifest
        events += event(state, "manufactured", manifest.id, selected.id)
    }

    private fun automaticDispatch(
        state: EngineState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        players: List<PlayerFact>,
        events: MutableList<EngineEvent>,
    ) {
        val targeted = state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }.mapTo(mutableSetOf()) { it.targetPlayerId }
        state.warbands.values.sortedBy { it.id }.forEach { warband ->
            if (warband.defeated || state.tick < warband.nextRaidTick) return@forEach
            if (state.campaigns.values.count { it.warbandId == warband.id && it.phase != CampaignPhase.RESOLVED } >= warband.activeCampaignLimit) return@forEach
            val player = players.asSequence().filter { it.eligible && !it.protected && it.id !in targeted && warband.id in it.hostileWarbands }
                .filter { it.position.dimension == warband.rally.dimension }
                .minWithOrNull(compareBy<PlayerFact> { manhattan(warband.rally, it.position) }.thenBy { it.id }) ?: return@forEach
            if (dispatch(state, catalog, rules, warband.id, player.id, events, player.position)) targeted += player.id
        }
    }

    private fun dispatch(
        state: EngineState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        warbandId: String,
        playerId: String,
        events: MutableList<EngineEvent>,
        target: ChunkPosition? = null,
    ): Boolean {
        if (state.campaigns.values.any { it.phase != CampaignPhase.RESOLVED && it.targetPlayerId == playerId }) return false
        val warband = state.warbands[warbandId] ?: return false
        val minimum = catalog.recruits.minOfOrNull { observedThreat(warband, it) } ?: return false
        val budget = rules.raidBudget(warband, minimum)
        if (budget + EPSILON < minimum) return false
        val officer = state.officers.values.filter { it.homeWarbandId == warband.id && it.availableAtTick <= state.tick }
            .minByOrNull { it.id }
        val members = mutableListOf<MemberManifest>()
        var remaining = budget
        while (members.size < rules.maximumSquadMembers) {
            val recruit = chooseRecruit(state, warband, officer, catalog, remaining, members, rules) ?: break
            val threat = observedThreat(warband, recruit)
            val equipment = warband.armory.indexOfFirst { item ->
                item.supportedActions.isEmpty() || recruit.supportedEquipmentActions.isEmpty() ||
                    item.supportedActions.any(recruit.supportedEquipmentActions::contains)
            }.takeIf { it >= 0 }?.let(warband.armory::removeAt)
            members += MemberManifest(nextId(state, "member"), recruit.id, threat, equipment = equipment)
            remaining -= threat
        }
        if (members.isEmpty()) return false
        val committed = members.sumOf(MemberManifest::threat)
        warband.raidPool -= committed
        val campaignId = nextId(state, "campaign")
        state.campaigns[campaignId] = CampaignState(
            campaignId, warband.id, officer?.id ?: "", playerId, warband.rally,
            target ?: warband.rally, members, lastCombatTick = state.tick,
        )
        warband.nextRaidTick = state.tick + rules.raidCooldownTicks
        events += event(state, "dispatched", campaignId, "target=$playerId threat=$committed")
        return true
    }

    private fun advanceCampaigns(
        state: EngineState,
        rules: WarbandRules,
        players: List<PlayerFact>,
        elapsed: Long,
        events: MutableList<EngineEvent>,
        effects: MutableList<EngineEffect>,
    ) {
        val resolved = mutableListOf<CampaignState>()
        state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }.forEach { campaign ->
            val warband = state.warbands[campaign.warbandId] ?: return@forEach
            val player = players.firstOrNull { it.id == campaign.targetPlayerId }
            if (player != null) campaign.target = player.position
            if (player != null && (!player.eligible || player.protected) && campaign.phase != CampaignPhase.RETURNING) {
                beginReturn(state, campaign.id, "target_ineligible", 0, events, effects)
            }
            if (campaign.phase == CampaignPhase.ACTIVE && state.tick - campaign.lastCombatTick >= rules.idleReturnTicks) {
                beginReturn(state, campaign.id, "idle", 1, events, effects)
            }
            if (campaign.phase == CampaignPhase.ACTIVE || campaign.phase == CampaignPhase.MATERIALIZING || campaign.physical) return@forEach

            campaign.travelTickDebt += elapsed
            while (campaign.travelTickDebt >= rules.travelTicksPerChunk) {
                campaign.travelTickDebt -= rules.travelTicksPerChunk
                val destination = if (campaign.phase == CampaignPhase.RETURNING) warband.rally else campaign.target
                campaign.position = stepToward(campaign.position, destination)
                if (campaign.phase == CampaignPhase.RETURNING && campaign.position == warband.rally) {
                    resolved += campaign
                    break
                }
                if (campaign.phase == CampaignPhase.OUTBOUND && manhattan(campaign.position, campaign.target) <= rules.materializeDistanceChunks) {
                    campaign.phase = CampaignPhase.READY_TO_MATERIALIZE
                    effects += EngineEffect(EffectKind.MATERIALIZE, warband.id, campaign.id, campaign.targetPlayerId, campaign.position, campaign.members.map { it.id })
                    events += event(state, "materialization_requested", campaign.id)
                    break
                }
            }
        }
        resolved.forEach { campaign -> reconcile(state, campaign, events) }
    }

    private fun observeCombat(
        state: EngineState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        observation: CombatObservation,
        events: MutableList<EngineEvent>,
        effects: MutableList<EngineEffect>,
    ) {
        val campaign = state.campaigns[observation.campaignId] ?: return
        if (campaign.phase != CampaignPhase.ACTIVE) return
        val warband = state.warbands[campaign.warbandId] ?: return
        campaign.lastCombatTick = state.tick
        val dead = campaign.members.filter { it.id in observation.casualties }
        dead.forEach { campaign.members.remove(it); events += event(state, "member_lost", it.id, campaign.id) }
        val damageRatio = observation.playerDamage / (observation.playerDamage + observation.campaignDamage + 1.0)
        campaign.members.forEach { member -> member.healthFraction = (member.healthFraction - damageRatio / campaign.members.size.coerceAtLeast(1)).coerceIn(0.0, 1.0) }
        val contribution = mapOf(
            "durability" to if (observation.playerDamage > observation.campaignDamage) 1.0 else -0.4,
            "damage" to if (observation.campaignDamage <= observation.playerDamage) 1.0 else -0.3,
            "mobility" to if (observation.routeConfidence < 0.5) 1.0 else -0.2,
            "range" to if (observation.effectiveRange > 8.0) 1.0 else -0.2,
            "control" to if (observation.cohesion < 0.5) 1.0 else -0.2,
        )
        contribution.forEach { (key, value) ->
            warband.preferences[key] = warband.preferences.getOrDefault(key, 0.0) + rules.warbandLearningRate * value
            state.officers[campaign.officerId]?.let { officer -> officer.preferences[key] = officer.preferences.getOrDefault(key, 0.0) + rules.captainLearningRate * value }
        }
        campaign.members.groupBy(MemberManifest::recruitId).forEach { (id, members) ->
            val base = catalog.recruits.firstOrNull { it.id == id }?.baseThreat ?: return@forEach
            val current = warband.empiricalThreat[id] ?: base
            val observed = (current + observation.playerDamage / members.size.coerceAtLeast(1) / 5.0 - observation.campaignDamage / 20.0).coerceAtLeast(1.0)
            warband.empiricalThreat[id] = current + rules.threatLearningRate * (observed - current)
        }
        events += event(state, "combat_observed", campaign.id)
        val liveThreat = campaign.members.sumOf { it.threat * it.healthFraction }
        val committed = campaign.members.sumOf(MemberManifest::threat) + dead.sumOf(MemberManifest::threat)
        if (campaign.members.isEmpty() || committed > 0.0 && liveThreat / committed <= 0.35) {
            beginReturn(state, campaign.id, "morale", 0, events, effects)
        }
    }

    private fun applyMaterializations(state: EngineState, results: List<MaterializationResult>, events: MutableList<EngineEvent>) {
        results.forEach { result ->
            val campaign = state.campaigns[result.campaignId] ?: return@forEach
            if (campaign.phase != CampaignPhase.READY_TO_MATERIALIZE && campaign.phase != CampaignPhase.MATERIALIZING) return@forEach
            campaign.phase = if (result.success) CampaignPhase.ACTIVE else CampaignPhase.OUTBOUND
            campaign.physical = result.success
            events += event(state, if (result.success) "materialized" else "materialization_failed", campaign.id)
        }
    }

    private fun applyPositions(state: EngineState, positions: List<PositionObservation>) {
        positions.forEach { observation -> state.campaigns[observation.campaignId]?.position = observation.position }
    }

    private fun beginReturn(
        state: EngineState,
        campaignId: String,
        reason: String,
        aggressionDelta: Int,
        events: MutableList<EngineEvent>,
        effects: MutableList<EngineEffect>,
    ) {
        val campaign = state.campaigns[campaignId] ?: return
        if (campaign.phase == CampaignPhase.RETURNING || campaign.phase == CampaignPhase.RESOLVED) return
        val physical = campaign.physical || campaign.phase == CampaignPhase.ACTIVE || campaign.phase == CampaignPhase.MATERIALIZING
        campaign.phase = CampaignPhase.RETURNING
        campaign.returnReason = reason
        campaign.returnAggressionDelta = aggressionDelta
        events += event(state, "return_started", campaign.id, reason)
        if (physical) effects += EngineEffect(EffectKind.CAPTURE_SNAPSHOTS, campaign.warbandId, campaign.id, memberIds = campaign.members.map { it.id })
    }

    private fun dematerialize(state: EngineState, campaignId: String, events: MutableList<EngineEvent>) {
        val campaign = state.campaigns[campaignId] ?: return
        if (campaign.phase == CampaignPhase.RETURNING) {
            campaign.physical = false
            events += event(state, "dematerialized", campaign.id)
        }
    }

    private fun reconcile(state: EngineState, campaign: CampaignState, events: MutableList<EngineEvent>) {
        val warband = state.warbands[campaign.warbandId] ?: return
        val returned = campaign.members.sumOf { it.threat * it.healthFraction }
        warband.raidPool = (warband.raidPool + returned).coerceAtMost(warband.capacity)
        campaign.members.mapNotNull(MemberManifest::equipment).forEach(warband.armory::add)
        warband.aggression = (warband.aggression + campaign.returnAggressionDelta).coerceIn(6, 18)
        campaign.phase = CampaignPhase.RESOLVED
        state.officers[campaign.officerId]?.availableAtTick = state.tick
        events += event(state, "returned", campaign.id, "threat=$returned")
    }

    private fun validateCatalog(catalog: EngineCatalog) {
        require(catalog.revision.isNotBlank())
        require(catalog.recruits.map { it.id }.distinct().size == catalog.recruits.size)
        require(catalog.recruits.all { it.id.isNotBlank() && it.baseThreat > 0.0 && it.capabilities.finite() })
        require(catalog.materials.all { it.id.isNotBlank() && it.tier > 0 && it.extractionCost >= 0.0 })
        require(catalog.equipment.all { it.id.isNotBlank() && it.cost.values.all { value -> value >= 0.0 } })
    }

    private fun observedThreat(warband: WarbandState, recruit: RecruitDefinition) =
        warband.empiricalThreat[recruit.id]?.coerceAtLeast(1.0) ?: recruit.baseThreat.coerceAtLeast(1.0)

    private fun recruitScore(warband: WarbandState, recruit: RecruitDefinition, preferences: CapabilityVector): Double =
        recruit.capabilities.dot(preferences) / observedThreat(warband, recruit) -
            recruit.environmentalCost.dot(environmentVector(warband.environment))

    private fun preferences(warband: WarbandState) = CapabilityVector(
        warband.preferences["durability"] ?: 0.0,
        warband.preferences["damage"] ?: 0.0,
        warband.preferences["mobility"] ?: 0.0,
        warband.preferences["range"] ?: 0.0,
        warband.preferences["control"] ?: 0.0,
    )

    private fun environmentVector(environment: EnvironmentTraits) = CapabilityVector(
        durability = 1.0 - environment.habitability,
        damage = 0.0,
        mobility = environment.travelFriction,
        range = environment.travelFriction,
        control = 1.0 - environment.biomass,
    )

    private fun nextId(state: EngineState, kind: String) = "engine:$kind:${state.sequence++}"

    private fun event(state: EngineState, type: String, subject: String, detail: String = "") =
        EngineEvent(state.tick, type, subject, detail)

    private fun stepToward(from: ChunkPosition, to: ChunkPosition): ChunkPosition {
        if (from.dimension != to.dimension) return from
        return when {
            from.x != to.x -> from.copy(x = from.x + if (to.x > from.x) 1 else -1)
            from.z != to.z -> from.copy(z = from.z + if (to.z > from.z) 1 else -1)
            else -> from
        }
    }

    private fun manhattan(a: ChunkPosition, b: ChunkPosition): Int =
        if (a.dimension != b.dimension) Int.MAX_VALUE else kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.z - b.z)

    private fun deterministicTie(owner: String, candidate: String, sequence: Long): Long {
        var value = 0xcbf29ce484222325UL.toLong()
        "$owner|$candidate|$sequence".forEach { value = (value xor it.code.toLong()) * 0x100000001b3L }
        return value
    }

    private const val EPSILON = 1.0e-9
}
