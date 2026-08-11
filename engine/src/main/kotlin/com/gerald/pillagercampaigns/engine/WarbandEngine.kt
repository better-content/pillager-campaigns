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

        frame.terrain.forEach { observation -> state.terrain[terrainKey(observation.position)] = observation }
        applyMaterializations(state, frame.materializations, events)
        applySnapshots(state, frame.snapshots, events)
        applyPositions(state, frame.physicalPositions)
        val outcomeCampaigns = frame.outcomes.mapTo(hashSetOf(), CampaignOutcomeObservation::campaignId)
        frame.combat.forEach { observeCombat(state, catalog, rules, it, events, effects, decide = it.campaignId !in outcomeCampaigns) }
        frame.outcomes.forEach { observeOutcome(state, rules, it, events, effects) }
        frame.commands.forEach { command ->
            when (command) {
                is EngineCommand.Dispatch -> dispatch(state, catalog, rules, command.warbandId, command.playerId, events, officerId = command.officerId)
                is EngineCommand.BeginReturn -> beginReturn(state, command.campaignId, command.reason, command.aggressionDelta, events, effects)
                is EngineCommand.Dematerialize -> dematerialize(state, command.campaignId, events)
                is EngineCommand.Manufacture -> repeat(command.count.coerceAtLeast(0)) {
                    state.warbands[command.warbandId]?.let { manufacture(state, it, catalog, events) }
                }
            }
        }

        if (frame.advanceEconomy) advanceEconomies(state, catalog, rules, frame.elapsedTicks, events)
        if (frame.allowAutomaticDispatch) automaticDispatch(state, catalog, rules, frame.players, events)
        advanceCampaigns(state, catalog, rules, frame.players, frame.elapsedTicks, events, effects)
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
            .maxWithOrNull(compareBy<RecruitDefinition> {
                marginalRecruitScore(recruitScore(warband, it, preferences), members.count { member -> member.recruitId == it.id }) -
                    EcologyMath.repetitionPenalty(warband.selectionMemory.recruits, it.id, rules.diversityWeight)
            }
                .thenByDescending { deterministicTie(warband.id, it.id, state.sequence + members.size) })
    }

    fun recruitScore(
        warband: WarbandState,
        officer: OfficerState?,
        recruit: RecruitDefinition,
        rules: WarbandRules = WarbandRules(),
    ): Double = recruitScore(warband, recruit, rules.effectivePreferences(warband, officer))

    /**
     * Produces the exact budget gate used by both automatic and adapter-driven
     * dispatch. Readiness expresses aggression while reserving enough room for
     * the preference-selected lead recruit and the cheapest distinct support.
     */
    fun raidBudget(
        state: EngineState,
        warband: WarbandState,
        officer: OfficerState?,
        catalog: EngineCatalog,
        rules: WarbandRules = WarbandRules(),
    ): Double {
        val minimum = catalog.recruits.minOfOrNull { observedThreat(warband, it) } ?: return 0.0
        val lead = chooseRecruit(state, warband, officer, catalog, Double.MAX_VALUE, rules = rules) ?: return 0.0
        val supportThreat = if (rules.maximumSquadMembers > 1) catalog.recruits.asSequence()
            .filter { it.id != lead.id }
            .minOfOrNull { observedThreat(warband, it) } ?: 0.0 else 0.0
        val desired = maxOf(
            rules.aggressionRaidThreat(warband, minimum),
            observedThreat(warband, lead) + supportThreat,
        )
        return if (warband.raidPool + EPSILON >= desired) desired else 0.0
    }

    fun assignmentScore(warband: WarbandState, officer: OfficerState, player: PlayerFact): Int {
        if (!player.eligible || player.protected || warband.id !in player.hostileWarbands ||
            player.position.dimension != warband.rally.dimension) return Int.MIN_VALUE / 4
        val distance = manhattan(warband.rally, player.position)
        val grudge = if (officer.lastTargetPlayerId == player.id) 48 else 0
        val rankBias = (officer.rank - 1).coerceAtLeast(0) * 6
        return grudge + rankBias + officer.victories * 5 - officer.defeats * 3 - distance * 4
    }

    fun chooseAssignment(
        state: EngineState,
        warband: WarbandState,
        players: Collection<PlayerFact>,
        officers: Collection<OfficerState> = state.officers.values,
    ): DispatchAssignment? = officers.asSequence()
        .filter { it.homeWarbandId == warband.id && it.availableAtTick <= state.tick }
        .flatMap { officer -> players.asSequence().map { player -> DispatchAssignment(officer.id, player.id, assignmentScore(warband, officer, player)) } }
        .filter { it.score > Int.MIN_VALUE / 4 }
        .maxWithOrNull(compareBy<DispatchAssignment> { it.score }.thenByDescending { "${it.officerId}|${it.playerId}" })

    /**
     * Builds the exact member/equipment manifest used by campaign dispatch.
     * Adapters may persist this plan and materialize it later without making a
     * second composition decision.
     */
    fun planSquad(
        state: EngineState,
        warband: WarbandState,
        officer: OfficerState?,
        catalog: EngineCatalog,
        budget: Double,
        rules: WarbandRules = WarbandRules(),
    ): SquadPlan {
        val members = mutableListOf<MemberManifest>()
        var remaining = budget.coerceAtLeast(0.0)
        val preferences = rules.armamentPreferences(warband, officer)
        val assignedActions = linkedMapOf<String, Int>()
        while (members.size < rules.maximumSquadMembers) {
            val recruit = chooseRecruit(state, warband, officer, catalog, remaining, members, rules) ?: break
            val threat = observedThreat(warband, recruit)
            val equipmentIndex = warband.armory.withIndex().asSequence()
                .filter { (_, item) -> rules.equipmentSupportsRecruit(item, recruit) }
                .maxWithOrNull(compareBy<IndexedValue<EquipmentManifest>> { (_, item) ->
                    rules.capabilityUtility(item.capabilities, preferences) +
                        rules.capabilityUtility(item.capabilities, recruit.capabilities) * 0.25 +
                        item.durabilityFraction * (0.1 + preferences.durability.coerceAtLeast(0.0)) +
                        item.supportedActions.sumOf { action -> 1.25 / (1.0 + assignedActions.getOrDefault(action, 0)) }
                }.thenByDescending { it.value.id })?.index
            val equipment = equipmentIndex?.let(warband.armory::removeAt)
            equipment?.supportedActions?.forEach { action -> assignedActions[action] = assignedActions.getOrDefault(action, 0) + 1 }
            members += MemberManifest(nextId(state, "member"), recruit.id, threat, equipment = equipment)
            warband.selectionMemory.recruits[recruit.id] = warband.selectionMemory.recruits.getOrDefault(recruit.id, 0.0) + 1.0
            remaining -= threat
        }
        return SquadPlan(members, members.sumOf(MemberManifest::threat))
    }

    fun chooseMaterial(
        state: EngineState,
        warband: WarbandState,
        catalog: EngineCatalog,
        rules: WarbandRules = WarbandRules(),
    ): MaterialDefinition? {
        val available = (warband.reserveThreat + warband.raidPool + warband.garrisonThreat) *
            (0.5 + warband.environment.mineralPotential + warband.environment.exoticPotential)
        val accessible = catalog.materials.filter { it.extractionCost <= available + EPSILON }
        val armamentRelevant = accessible.filter { armamentMaterialDemand(warband, it.id, catalog) > EPSILON }
        return (armamentRelevant.ifEmpty { accessible }).asSequence()
            .maxWithOrNull(compareBy<MaterialDefinition> {
                it.tier * (warband.environment.mineralPotential + warband.environment.exoticPotential * it.tier) +
                    rules.capabilityUtility(it.capabilities, rules.armamentPreferences(warband, null)) -
                    EcologyMath.repetitionPenalty(warband.selectionMemory.materials, it.id, rules.diversityWeight) +
                    armamentMaterialDemand(warband, it.id, catalog) * 0.10
            }.thenByDescending { deterministicTie(warband.id, it.id, state.sequence) })
    }

    /** Marginal progress one extracted unit makes toward functional armament. */
    fun armamentMaterialDemand(warband: WarbandState, materialId: String, catalog: EngineCatalog): Double =
        catalog.equipment.mapNotNull { definition ->
            val required = definition.cost[materialId]?.takeIf { it > EPSILON } ?: return@mapNotNull null
            val available = warband.materialLedger.getOrDefault(materialId, 0.0)
            val progress = ((available + 1.0) / required).coerceAtMost(1.0) - (available / required).coerceAtMost(1.0)
            if (progress <= EPSILON) return@mapNotNull null
            val otherCosts = definition.cost.filterKeys { it != materialId }
            val otherReadiness = if (otherCosts.isEmpty()) 1.0 else otherCosts.entries.map { (id, amount) ->
                (warband.materialLedger.getOrDefault(id, 0.0) / amount.coerceAtLeast(EPSILON)).coerceIn(0.0, 1.0)
            }.average()
            val functionalNeed = definition.actions.map { action ->
                1.0 / (1.0 + warband.armory.count { action in it.supportedActions })
            }.average().takeIf(Double::isFinite) ?: 0.5
            progress * (0.5 + otherReadiness) * (1.0 + functionalNeed)
        }.average().takeIf(Double::isFinite) ?: 0.0

    fun chooseEquipment(
        state: EngineState,
        warband: WarbandState,
        catalog: EngineCatalog,
        rules: WarbandRules = WarbandRules(),
    ): EquipmentDefinition? =
        catalog.equipment.asSequence()
            .filter { definition -> definition.cost.all { (id, amount) -> warband.materialLedger.getOrDefault(id, 0.0) + EPSILON >= amount } }
            .maxWithOrNull(compareBy<EquipmentDefinition> { definition ->
                // Preserve live baseline semantics: affordability is exact, then the
                // functional maximum wins without an invented cost-efficiency curve.
                val stockCoverage = warband.armory.count { it.definitionId == definition.id }.toDouble() /
                    warband.armory.size.coerceAtLeast(1)
                rules.capabilityUtility(definition.capabilities, rules.armamentPreferences(warband, null)) +
                    definition.actions.sumOf { action ->
                        0.75 / (1.0 + warband.armory.count { action in it.supportedActions })
                    } -
                    EcologyMath.repetitionPenalty(warband.selectionMemory.equipment, definition.id, rules.diversityWeight) -
                    stockCoverage * rules.diversityWeight
            }.thenByDescending { deterministicTie(warband.id, it.id, state.sequence) })

    fun choosePartMaterial(
        state: EngineState,
        warband: WarbandState,
        materials: Collection<MaterialDefinition>,
        compatibleIds: Set<String>,
        available: Map<String, Double>,
        requiredUnits: Double,
        salt: Int,
        rules: WarbandRules = WarbandRules(),
    ): MaterialDefinition? = materials.asSequence()
        .filter { it.id in compatibleIds && available.getOrDefault(it.id, 0.0) + EPSILON >= requiredUnits }
        .maxWithOrNull(compareBy<MaterialDefinition> { material ->
            rules.capabilityUtility(material.capabilities, rules.armamentPreferences(warband, null)) +
                material.tier * warband.environment.bounded().exoticPotential * 0.10 -
                EcologyMath.repetitionPenalty(warband.selectionMemory.materials, material.id, rules.diversityWeight)
        }.thenByDescending { deterministicTie(warband.id, it.id, state.sequence + salt) })

    fun validate(state: EngineState, catalog: EngineCatalog, rules: WarbandRules = WarbandRules()) {
        require(state.tick >= 0L && state.sequence >= 0L)
        state.warbands.values.forEach { warband ->
            require(warband.reserveThreat >= -EPSILON && warband.raidPool >= -EPSILON && warband.garrisonThreat >= -EPSILON)
            require(warband.capacity > 0.0 && warband.aggression in rules.minimumAggression..rules.maximumAggression)
            require(warband.materialLedger.values.all { it.isFinite() && it >= -EPSILON })
            require(warband.stockpile.values.all { it >= 0 })
            require(warband.preferences.values.all(Double::isFinite))
        }
        val memberIds = state.campaigns.values.flatMap { it.members }.map { it.id }
        require(memberIds.distinct().size == memberIds.size) { "duplicate deployed member identity" }
        val equipmentIds = state.warbands.values.flatMap { it.armory }.map { it.id } +
            state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }
                .flatMap { it.members }.mapNotNull { it.equipment?.id }
        require(equipmentIds.distinct().size == equipmentIds.size) { "duplicate equipment identity" }
        val targets = state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }.map { it.targetPlayerId }
        require(targets.distinct().size == targets.size) { "multiple unresolved campaigns target one player" }
        state.campaigns.values.flatMap { it.members }.forEach { member ->
            require(catalog.recruits.any { it.id == member.recruitId }) { "unknown recruit ${member.recruitId}" }
            require(member.threat > 0.0 && member.healthFraction in 0.0..1.0)
            require(member.cargo.values.all { it >= 0 })
            require(member.equipment?.durabilityFraction?.let { it in 0.0..1.0 } != false)
        }
        state.campaigns.values.flatMap { it.lostCaches }.forEach { cache ->
            require(cache.cargo.values.all { it >= 0 })
            require(cache.equipment.all { it.durabilityFraction in 0.0..1.0 })
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
        var simulationTick = state.tick - elapsed
        while (remaining > 0L) {
            val slice = minOf(20L, remaining)
            simulationTick += slice
            state.warbands.values.forEach {
                EcologyMath.decay(it.selectionMemory, simulationTick, rules.selectionMemoryHalfLifeTicks)
            }
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
                if (warband.armory.size < rules.desiredArmoryItems(warband, catalog.recruits)) {
                    manufacture(state, warband, catalog, events)
                }
            }

            warband.extractionTickDebt += elapsed
            val extractionTicks = rules.extractionTicks(warband.environment)
            while (warband.extractionTickDebt + EPSILON >= extractionTicks) {
                warband.extractionTickDebt -= extractionTicks
                extract(state, warband, catalog, events)
                if (warband.armory.size < rules.desiredArmoryItems(warband, catalog.recruits)) {
                    manufacture(state, warband, catalog, events)
                }
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
        val selected = chooseMaterial(state, warband, catalog) ?: return
        warband.materialLedger[selected.id] = warband.materialLedger.getOrDefault(selected.id, 0.0) + 1.0
        warband.selectionMemory.materials[selected.id] = warband.selectionMemory.materials.getOrDefault(selected.id, 0.0) + 1.0
        events += event(state, "extracted", warband.id, selected.id)
        acquireEnvironmentalResource(state, warband, catalog, warband.environment, warband.stockpile, events)
    }

    private fun manufacture(
        state: EngineState,
        warband: WarbandState,
        catalog: EngineCatalog,
        events: MutableList<EngineEvent>,
    ) {
        val selected = chooseEquipment(state, warband, catalog) ?: return
        selected.cost.forEach { (id, amount) -> warband.materialLedger[id] = (warband.materialLedger.getOrDefault(id, 0.0) - amount).coerceAtLeast(0.0) }
        val manifest = EquipmentManifest(nextId(state, "equipment"), selected.id, selected.formulation, selected.cost, selected.capabilities, selected.actions)
        warband.armory += manifest
        warband.selectionMemory.equipment[selected.id] = warband.selectionMemory.equipment.getOrDefault(selected.id, 0.0) + 1.0
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
            val candidates = players.filter { it.id !in targeted }
            val assignment = chooseAssignment(state, warband, candidates) ?: return@forEach
            val player = candidates.first { it.id == assignment.playerId }
            if (dispatch(state, catalog, rules, warband.id, player.id, events, player.position, assignment.officerId)) targeted += player.id
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
        officerId: String? = null,
    ): Boolean {
        if (state.campaigns.values.any { it.phase != CampaignPhase.RESOLVED && it.targetPlayerId == playerId }) return false
        val warband = state.warbands[warbandId] ?: return false
        val officer = officerId?.let(state.officers::get)?.takeIf { it.homeWarbandId == warband.id && it.availableAtTick <= state.tick }
            ?: state.officers.values.filter { it.homeWarbandId == warband.id && it.availableAtTick <= state.tick }.minByOrNull { it.id }
        val budget = raidBudget(state, warband, officer, catalog, rules)
        if (budget <= EPSILON) return false
        val plan = planSquad(state, warband, officer, catalog, budget, rules)
        if (plan.members.isEmpty()) return false
        val members = plan.members.toMutableList()
        val committed = plan.committedThreat
        val destination = target ?: warband.rally
        val route = planRoute(state, warband, destination, rules).toMutableList()
        provisionCampaign(members, warband, catalog, rules, route.size.coerceAtLeast(1), events, state)
        warband.raidPool -= committed
        val campaignId = nextId(state, "campaign")
        state.campaigns[campaignId] = CampaignState(
            campaignId, warband.id, officer?.id ?: "", playerId, warband.rally,
            destination, members, lastCombatTick = state.tick, route = route,
        )
        officer?.lastTargetPlayerId = playerId
        warband.nextRaidTick = state.tick + rules.raidCooldownTicks
        events += event(state, "dispatched", campaignId, "target=$playerId threat=$committed")
        return true
    }

    private fun advanceCampaigns(
        state: EngineState,
        catalog: EngineCatalog,
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
            if (campaign.phase == CampaignPhase.ACTIVE) {
                val liveThreat = campaign.members.sumOf { it.threat * it.healthFraction }
                val committed = campaign.members.sumOf(MemberManifest::threat)
                val conservation = state.officers[campaign.officerId]?.preferences?.get("conservation") ?: 0.5
                when (CampaignDecisions.activeDecision(campaign.members.size, liveThreat, committed, conservation, warband.aggression, state.tick, campaign.lastCombatTick, rules)) {
                    ActiveCampaignDecision.DEFEATED, ActiveCampaignDecision.MORALE_RETURN ->
                        beginDefeatReturn(state, campaign, rules, "morale", events, effects)
                    ActiveCampaignDecision.IDLE_RETURN -> beginReturn(state, campaign.id, "idle", 1, events, effects)
                    ActiveCampaignDecision.CONTINUE -> Unit
                }
            }
            if (campaign.phase == CampaignPhase.ACTIVE || campaign.phase == CampaignPhase.MATERIALIZING || campaign.physical) return@forEach

            campaign.travelTickDebt += elapsed
            while (campaign.travelTickDebt >= segmentTravelTicks(campaign, rules)) {
                campaign.travelTickDebt -= segmentTravelTicks(campaign, rules)
                val destination = if (campaign.phase == CampaignPhase.RETURNING) warband.rally else campaign.target
                campaign.position = if (campaign.phase != CampaignPhase.RETURNING && campaign.routeIndex < campaign.route.size) {
                    campaign.route[campaign.routeIndex++]
                } else stepToward(campaign.position, destination)
                applySegmentLogistics(state, campaign, warband, catalog, rules, events)
                if (campaign.members.isEmpty()) {
                    campaign.phase = CampaignPhase.RESOLVED
                    warband.aggression = (warband.aggression + 1).coerceIn(rules.minimumAggression, rules.maximumAggression)
                    state.officers[campaign.officerId]?.let { officer ->
                        officer.defeats += 1
                        officer.availableAtTick = state.tick + rules.captainRecoveryTicks
                    }
                    campaign.returnReason = "supply_attrition"
                    events += event(state, "campaign_lost_to_attrition", campaign.id)
                    break
                }
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
        resolved.forEach { campaign -> reconcile(state, campaign, rules, events) }
    }

    private fun observeCombat(
        state: EngineState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        observation: CombatObservation,
        events: MutableList<EngineEvent>,
        effects: MutableList<EngineEffect>,
        decide: Boolean = true,
    ) {
        val campaign = state.campaigns[observation.campaignId] ?: return
        if (campaign.phase != CampaignPhase.ACTIVE) return
        val warband = state.warbands[campaign.warbandId] ?: return
        campaign.lastCombatTick = state.tick
        val dead = campaign.members.filter { it.id in observation.casualties }
        dead.forEach {
            cacheMember(state, campaign, it)
            campaign.members.remove(it)
            events += event(state, "member_lost", it.id, campaign.id)
        }
        if (observation.applyHealthDamage) {
            val damageRatio = observation.playerDamage / (observation.playerDamage + observation.campaignDamage + 1.0)
            campaign.members.forEach { member -> member.healthFraction = (member.healthFraction - damageRatio / campaign.members.size.coerceAtLeast(1)).coerceIn(0.0, 1.0) }
            campaign.members.mapNotNull(MemberManifest::equipment).forEach { equipment ->
                equipment.durabilityFraction = (equipment.durabilityFraction - damageRatio * 0.08).coerceAtLeast(0.0)
            }
        }
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
        if (!decide) return
        val liveThreat = campaign.members.sumOf { it.threat * it.healthFraction }
        val committed = campaign.members.sumOf(MemberManifest::threat) + dead.sumOf(MemberManifest::threat)
        val conservation = state.officers[campaign.officerId]?.preferences?.get("conservation") ?: 0.5
        when (CampaignDecisions.activeDecision(campaign.members.size, liveThreat, committed, conservation, warband.aggression, state.tick, campaign.lastCombatTick, rules)) {
            ActiveCampaignDecision.DEFEATED, ActiveCampaignDecision.MORALE_RETURN ->
                beginDefeatReturn(state, campaign, rules, "morale", events, effects)
            ActiveCampaignDecision.IDLE_RETURN -> beginReturn(state, campaign.id, "idle", 1, events, effects)
            ActiveCampaignDecision.CONTINUE -> Unit
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

    private fun applySnapshots(state: EngineState, results: List<CampaignSnapshotResult>, events: MutableList<EngineEvent>) {
        results.forEach snapshotLoop@{ result ->
            val campaign = state.campaigns[result.campaignId] ?: return@snapshotLoop
            if (campaign.phase != CampaignPhase.RETURNING && campaign.phase != CampaignPhase.ACTIVE &&
                campaign.phase != CampaignPhase.MATERIALIZING) return@snapshotLoop
            val returning = campaign.phase == CampaignPhase.RETURNING
            val existing = campaign.members.associateBy(MemberManifest::id)
            campaign.members.clear()
            result.members.forEach memberLoop@{ snapshot ->
                val member = existing[snapshot.memberId] ?: return@memberLoop
                member.healthFraction = snapshot.healthFraction.coerceIn(0.0, 1.0)
                member.experience = snapshot.experience.coerceAtLeast(0.0)
                member.equipment = snapshot.equipment
                member.cargo.clear()
                snapshot.cargo.filterValues { it > 0 }.forEach(member.cargo::put)
                if (member.healthFraction > EPSILON) campaign.members += member
                else cacheMember(state, campaign, member)
            }
            campaign.position = result.position
            campaign.physical = false
            if (!returning) campaign.phase = CampaignPhase.READY_TO_MATERIALIZE
            events += event(state, "snapshots_applied", campaign.id, "members=${campaign.members.size}")
            events += event(state, "dematerialized", campaign.id)
        }
    }

    private fun observeOutcome(
        state: EngineState,
        rules: WarbandRules,
        observation: CampaignOutcomeObservation,
        events: MutableList<EngineEvent>,
        effects: MutableList<EngineEffect>,
    ) {
        val campaign = state.campaigns[observation.campaignId] ?: return
        val officer = state.officers[campaign.officerId]
        when (observation.outcome) {
            CampaignOutcomeKind.CAPTAIN_VICTORY -> {
                officer?.let { it.victories += 1; it.availableAtTick = state.tick + rules.captainSuccessRecoveryTicks }
                beginReturn(state, campaign.id, observation.reason, -1, events, effects)
            }
            CampaignOutcomeKind.SURVIVING_DEFEAT -> {
                officer?.let { it.defeats += 1; it.availableAtTick = state.tick + rules.captainRecoveryTicks }
                beginReturn(state, campaign.id, observation.reason, 1, events, effects)
            }
            CampaignOutcomeKind.CAPTAIN_KILLED -> {
                officer?.let { it.defeats += 1; it.availableAtTick = Long.MAX_VALUE }
                beginReturn(state, campaign.id, observation.reason, 1, events, effects)
            }
            CampaignOutcomeKind.ABORTED -> beginReturn(state, campaign.id, observation.reason, 0, events, effects)
            CampaignOutcomeKind.WARBAND_COLLAPSE -> {
                state.warbands[campaign.warbandId]?.let { warband ->
                    warband.defeated = true
                    warband.reserveThreat = 0.0
                    warband.raidPool = 0.0
                }
                campaign.members.toList().forEach { cacheMember(state, campaign, it) }
                campaign.members.clear()
                campaign.phase = CampaignPhase.RESOLVED
                campaign.physical = false
                events += event(state, "campaign_resolved", campaign.id, observation.reason)
            }
        }
        events += event(state, "campaign_outcome_observed", campaign.id, observation.outcome.name.lowercase())
    }

    private fun beginDefeatReturn(
        state: EngineState,
        campaign: CampaignState,
        rules: WarbandRules,
        reason: String,
        events: MutableList<EngineEvent>,
        effects: MutableList<EngineEffect>,
    ) {
        state.officers[campaign.officerId]?.let { officer ->
            officer.defeats += 1
            officer.availableAtTick = state.tick + rules.captainRecoveryTicks
        }
        beginReturn(state, campaign.id, reason, 1, events, effects)
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

    private fun reconcile(state: EngineState, campaign: CampaignState, rules: WarbandRules, events: MutableList<EngineEvent>) {
        val warband = state.warbands[campaign.warbandId] ?: return
        val returned = campaign.members.sumOf { it.threat * it.healthFraction }
        warband.raidPool = (warband.raidPool + returned).coerceAtMost(warband.capacity)
        campaign.members.forEach { member ->
            member.cargo.forEach { (id, count) -> warband.stockpile[id] = warband.stockpile.getOrDefault(id, 0) + count }
            member.cargo.clear()
            member.equipment?.takeIf { it.durabilityFraction > EPSILON }?.let(warband.armory::add)
        }
        warband.aggression = (warband.aggression + campaign.returnAggressionDelta)
            .coerceIn(rules.minimumAggression, rules.maximumAggression)
        campaign.phase = CampaignPhase.RESOLVED
        state.officers[campaign.officerId]?.let { officer -> officer.availableAtTick = maxOf(officer.availableAtTick, state.tick) }
        events += event(state, "returned", campaign.id, "threat=$returned")
    }

    private fun validateCatalog(catalog: EngineCatalog) {
        require(catalog.revision.isNotBlank())
        require(catalog.recruits.map { it.id }.distinct().size == catalog.recruits.size)
        require(catalog.recruits.all { it.id.isNotBlank() && it.baseThreat > 0.0 && it.capabilities.finite() })
        require(catalog.materials.all { it.id.isNotBlank() && it.tier > 0 && it.extractionCost >= 0.0 })
        require(catalog.equipment.all { it.id.isNotBlank() && it.cost.values.all { value -> value >= 0.0 } })
        require(catalog.environmentSamples.all { it == it.bounded() })
        require(catalog.resources.map { it.itemId }.distinct().size == catalog.resources.size)
        require(catalog.resources.all {
            it.itemId.isNotBlank() && it.mass > 0.0 && it.environmentalAvailability.isFinite() &&
                it.environmentalAvailability >= 0.0 && it.maximumStackSize > 0 && it.unitsPerItem.finite() &&
                listOf(it.unitsPerItem.sustenance, it.unitsPerItem.munitions, it.unitsPerItem.maintenance, it.unitsPerItem.recovery).all { value -> value >= 0.0 }
        })
    }

    private fun segmentTravelTicks(campaign: CampaignState, rules: WarbandRules): Long =
        (rules.travelTicksPerChunk * (2.0 - campaign.supplySatisfaction.coerceIn(0.0, 1.0))).toLong().coerceAtLeast(1L)

    private fun applySegmentLogistics(
        state: EngineState,
        campaign: CampaignState,
        warband: WarbandState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        events: MutableList<EngineEvent>,
    ) {
        if (campaign.members.isEmpty() || catalog.resources.isEmpty()) return
        val environment = state.terrain[terrainKey(campaign.position)]?.traits ?: warband.environment
        val livingThreat = campaign.members.sumOf { it.threat * it.healthFraction }
        val rangedThreat = campaign.members.filter { member ->
            member.equipment?.supportedActions?.contains("ranged") == true ||
                catalog.recruits.firstOrNull { it.id == member.recruitId }?.supportedEquipmentActions?.contains("ranged") == true
        }.sumOf { it.threat * it.healthFraction }
        val equipped = campaign.members.count { it.equipment != null }
        val demand = EcologyMath.segmentDemand(
            livingThreat, rangedThreat, equipped, campaign.members.sumOf { 1.0 - it.healthFraction }, environment, rules,
        )
        if (campaign.deficitExposure > 0.0) {
            campaign.forageDebt += rules.forageUnitsPerDeficitChunk * campaign.deficitExposure.coerceAtMost(1.0) *
                (1.0 - normalizedAggression(warband, rules) * 0.5)
            while (campaign.forageDebt >= 1.0) {
                campaign.forageDebt -= 1.0
                val cargo = campaign.members.minByOrNull { it.cargo.values.sum() }?.cargo ?: break
                acquireEnvironmentalResource(state, warband, catalog, environment, cargo, events, campaign.id, demand)
            }
        }
        val consumption = EcologyMath.consumeCargo(campaign.members.map(MemberManifest::cargo), catalog.resources.associateBy(ResourceDefinition::itemId), demand)
        consumption.items.forEach { (id, count) ->
            events += event(state, "resource_consumed", campaign.id, "$id=$count")
        }
        val remaining = consumption.remaining
        campaign.supplySatisfaction = EcologyMath.supplySatisfaction(demand, remaining)
        campaign.deficitExposure = (campaign.deficitExposure + 1.0 - campaign.supplySatisfaction).coerceAtLeast(0.0)
        campaign.members.mapNotNull(MemberManifest::equipment).forEach { equipment ->
            equipment.durabilityFraction = (equipment.durabilityFraction - EcologyMath.equipmentWear(environment, campaign.supplySatisfaction, rules)).coerceAtLeast(0.0)
        }
        val loss = EcologyMath.attritionLoss(environment, campaign.supplySatisfaction, campaign.deficitExposure, rules)
        if (loss > EPSILON) {
            val dead = campaign.members.onEach { it.healthFraction = (it.healthFraction - loss).coerceAtLeast(0.0) }
                .filter { it.healthFraction <= EPSILON }
            dead.forEach { member ->
                cacheMember(state, campaign, member)
                campaign.members.remove(member)
                events += event(state, "member_lost_to_attrition", member.id, campaign.id)
            }
        }
        if (campaign.phase == CampaignPhase.OUTBOUND && EcologyMath.shouldRetreatFromShortage(campaign.deficitExposure, warband.aggression, rules)) {
            campaign.phase = CampaignPhase.RETURNING
            campaign.returnReason = "supply_shortage"
            events += event(state, "return_started", campaign.id, "supply_shortage")
        }
        events += event(state, "logistics_segment", campaign.id, "satisfaction=${campaign.supplySatisfaction}")
    }

    private fun acquireEnvironmentalResource(
        state: EngineState,
        warband: WarbandState,
        catalog: EngineCatalog,
        environment: EnvironmentTraits,
        destination: MutableMap<String, Int>,
        events: MutableList<EngineEvent>,
        subject: String = warband.id,
        need: ResourceVector = ResourceVector(1.0, 1.0, 1.0, 1.0),
    ) {
        val selected = EcologyMath.chooseEnvironmentalResource(catalog.resources, environment, destination, need) {
            deterministicTie(warband.id, it, state.sequence)
        } ?: return
        destination[selected.itemId] = destination.getOrDefault(selected.itemId, 0) + 1
        events += event(state, "resource_acquired", subject, selected.itemId)
    }

    private fun provisionCampaign(
        members: List<MemberManifest>,
        warband: WarbandState,
        catalog: EngineCatalog,
        rules: WarbandRules,
        routeChunks: Int,
        events: MutableList<EngineEvent>,
        state: EngineState,
    ) {
        if (members.isEmpty() || catalog.resources.isEmpty()) return
        val totalThreat = members.sumOf(MemberManifest::threat)
        val rangedThreat = members.filter { member ->
            member.equipment?.supportedActions?.contains("ranged") == true ||
                catalog.recruits.firstOrNull { it.id == member.recruitId }?.supportedEquipmentActions?.contains("ranged") == true
        }.sumOf(MemberManifest::threat)
        val perSegment = EcologyMath.segmentDemand(
            totalThreat, rangedThreat, members.count { it.equipment != null }, 0.0, warband.environment, rules,
        )
        val segments = routeChunks.coerceAtLeast(1) * 2
        var totalRemaining = ResourceVector()
        val definitions = catalog.resources.associateBy(ResourceDefinition::itemId)
        repeat(segments) {
            var remaining = perSegment
            while (remaining.sum() > EPSILON) {
                val selected = warband.stockpile.asSequence().filter { it.value > 0 }.mapNotNull { definitions[it.key] }
                    .filter { definition -> members.any { it.cargo.getOrDefault(definition.itemId, 0) < definition.maximumStackSize } }
                    .maxWithOrNull(compareBy<ResourceDefinition> { it.unitsPerItem.dot(remaining) / it.mass }.thenByDescending { it.itemId }) ?: break
                if (selected.unitsPerItem.dot(remaining) <= EPSILON) break
                val carrier = members.filter { it.cargo.getOrDefault(selected.itemId, 0) < selected.maximumStackSize }
                    .minByOrNull { member -> member.cargo.entries.sumOf { (id, count) -> (definitions[id]?.mass ?: 1.0) * count } } ?: break
                warband.stockpile[selected.itemId] = warband.stockpile.getValue(selected.itemId) - 1
                if (warband.stockpile.getValue(selected.itemId) == 0) warband.stockpile.remove(selected.itemId)
                carrier.cargo[selected.itemId] = carrier.cargo.getOrDefault(selected.itemId, 0) + 1
                remaining = (remaining - selected.unitsPerItem).positive()
            }
            totalRemaining += remaining
        }
        val desired = perSegment * segments.toDouble()
        events += event(state, "campaign_provisioned", warband.id, "satisfaction=${EcologyMath.supplySatisfaction(desired, totalRemaining)}")
    }

    private fun cacheMember(state: EngineState, campaign: CampaignState, member: MemberManifest) {
        if (member.cargo.isEmpty() && member.equipment == null) return
        campaign.lostCaches += LostCache(
            nextId(state, "cache"), campaign.position, member.cargo.toMutableMap(),
            member.equipment?.takeIf { it.durabilityFraction > EPSILON }?.let { mutableListOf(it) } ?: mutableListOf(),
        )
        member.cargo.clear()
        member.equipment = null
    }

    private fun planRoute(state: EngineState, warband: WarbandState, target: ChunkPosition, rules: WarbandRules): List<ChunkPosition> {
        if (target.dimension != warband.rally.dimension) return emptyList()
        fun observations(points: List<ChunkPosition>) = points.map { point ->
            state.terrain[terrainKey(point)] ?: TerrainObservation(point, warband.environment)
        }
        val direct = mutableListOf<ChunkPosition>()
        var cursor = warband.rally
        while (cursor != target) { cursor = stepToward(cursor, target); direct += cursor }
        val xFirst = mutableListOf<ChunkPosition>().also { values ->
            var x = warband.rally.x
            while (x != target.x) { x += if (target.x > x) 1 else -1; values += ChunkPosition(target.dimension, x, warband.rally.z) }
            var z = warband.rally.z
            while (z != target.z) { z += if (target.z > z) 1 else -1; values += ChunkPosition(target.dimension, target.x, z) }
        }
        val zFirst = mutableListOf<ChunkPosition>().also { values ->
            var z = warband.rally.z
            while (z != target.z) { z += if (target.z > z) 1 else -1; values += ChunkPosition(target.dimension, warband.rally.x, z) }
            var x = warband.rally.x
            while (x != target.x) { x += if (target.x > x) 1 else -1; values += ChunkPosition(target.dimension, x, target.z) }
        }
        return EcologyMath.chooseRoute(listOf(observations(direct), observations(xFirst), observations(zFirst)), preferences(warband), warband.aggression, rules)
    }

    private fun normalizedAggression(warband: WarbandState, rules: WarbandRules): Double =
        ((warband.aggression - rules.minimumAggression).toDouble() /
            (rules.maximumAggression - rules.minimumAggression).coerceAtLeast(1)).coerceIn(0.0, 1.0)

    private fun terrainKey(position: ChunkPosition) = "${position.dimension}:${position.x}:${position.z}"

    private fun observedThreat(warband: WarbandState, recruit: RecruitDefinition) =
        warband.empiricalThreat[recruit.id]?.coerceAtLeast(1.0) ?: recruit.baseThreat.coerceAtLeast(1.0)

    private fun recruitScore(warband: WarbandState, recruit: RecruitDefinition, preferences: CapabilityVector): Double =
        recruit.capabilities.dot(preferences) / observedThreat(warband, recruit) -
            recruit.environmentalCost.dot(environmentVector(warband.environment))

    private fun marginalRecruitScore(score: Double, matchingMembers: Int): Double =
        if (score >= 0.0) score / (matchingMembers + 1.0) else score * (matchingMembers + 1.0)

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
        val next = CampaignGeometry.stepToward(from.x, from.z, to.x, to.z)
        return from.copy(x = next.first, z = next.second)
    }

    private fun manhattan(a: ChunkPosition, b: ChunkPosition): Int =
        if (a.dimension != b.dimension) Int.MAX_VALUE else CampaignGeometry.manhattan(a.x, a.z, b.x, b.z)

    private fun deterministicTie(owner: String, candidate: String, sequence: Long): Long {
        var value = 0xcbf29ce484222325UL.toLong()
        "$owner|$candidate|$sequence".forEach { value = (value xor it.code.toLong()) * 0x100000001b3L }
        return value
    }

    private const val EPSILON = 1.0e-9
}
