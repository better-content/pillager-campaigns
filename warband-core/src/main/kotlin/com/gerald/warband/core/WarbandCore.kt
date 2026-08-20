package com.gerald.warband.core

import java.util.UUID
import kotlin.math.ceil

internal object WarbandCore {
    internal fun transition(
        state: CoreSnapshot,
        frame: CoreFrame,
        catalog: CoreCatalog,
        rules: CoreRules = CoreRules(),
    ): CoreTransition {
        require(frame.elapsedTicks >= 0L) { "elapsed ticks must be nonnegative" }
        validateCatalog(catalog)
        val events = mutableListOf<CoreEvent>()
        val effects = mutableListOf<CoreEffect>()
        state.tick += frame.elapsedTicks
        state.protectedPlayersUntilTick.entries.removeIf { (_, untilTick) -> untilTick < state.tick }
        applyPlayerLifecycle(state, rules, frame.playerLifecycle, events)

        applyEquipmentRealizations(state, frame.equipmentRealizations, events)
        applyNavigationResults(state, frame.navigationResults, events)
        applyAcknowledgements(state, frame.acknowledgements, events)
        applyDiscoveries(state, catalog, rules, frame.players, frame.discoveries, events, effects)
        applyMaterializationSites(state, frame.materializationSites, effects, events)
        applyTerritory(state, rules, frame.territoryContacts, events, effects)
        frame.terrain.forEach { observation -> state.terrain[terrainKey(observation.position)] = observation }
        applyMaterializations(state, frame.materializations, events)
        applyGarrisonResults(state, frame.garrisonResults, events)
        applyGarrisonSnapshots(state, frame.garrisonSnapshots, events)
        applySnapshots(state, frame.snapshots, events)
        applyPositions(state, frame.physicalPositions)
        applyDefeats(state, catalog, frame.defeats, effects, events)
        applyWarlordDefeats(state, frame.warlordDefeats, events)
        val outcomeCampaigns = frame.outcomes.mapTo(hashSetOf(), CampaignOutcomeObservation::campaignId)
        frame.combat.forEach { observeCombat(state, catalog, rules, it, events, effects, decide = it.campaignId !in outcomeCampaigns) }
        frame.outcomes.forEach { observeOutcome(state, rules, it, events, effects) }
        frame.commands.forEach { command ->
            when (command) {
                is BeginReturnCommand -> beginReturn(state, command.campaignId, command.reason, command.aggressionDelta, events, effects)
                is DematerializeCommand -> dematerialize(state, command.campaignId, events)
                is CoreCommand.Manufacture -> repeat(command.count.coerceAtLeast(0)) {
                    state.warbands[command.warbandId]?.let { manufacture(state, it, catalog, rules, events, effects) }
                }
                is ReserveGarrisonCommand -> reserveGarrison(state, catalog, rules, command, events, effects)
                is ResolveGarrisonCommand -> resolveGarrison(state, command, events)
                is CoreCommand.CollapseWarband -> collapseWarband(state, command.warbandId, command.reason, events)
                is CoreCommand.CollapseFaction -> collapseFaction(state, command.factionId, command.reason, events)
                is PromoteSuccessorCommand -> promoteSuccessor(state, rules, command, events)
                is SelectCampaignSuccessorCommand -> selectCampaignSuccessor(
                    state, catalog, rules, command.campaignId, command.excludedMemberIds, events, effects,
                )
                is DelayWarbandCommand -> state.warbands[command.warbandId]?.let {
                    it.nextRaidTick = maxOf(it.nextRaidTick, command.untilTick.coerceAtLeast(state.tick))
                }
                is ResolveCampaignCommand -> state.campaigns[command.campaignId]?.let { campaign ->
                    if (campaign.phase != CampaignPhase.RESOLVED) {
                        campaign.returnReason = command.reason
                        reconcile(state, campaign, rules, events)
                    }
                }
                is CoreCommand.RegisterPlayer -> state.initializedPlayerIds.add(command.playerId)
                is CoreCommand.ProtectPlayer -> {
                    val previous = state.protectedPlayersUntilTick[command.playerId] ?: Long.MIN_VALUE
                    if (command.untilTick > previous) state.protectedPlayersUntilTick[command.playerId] = command.untilTick
                }
                is RecordSchedulerProgressCommand -> {
                    command.discoveryTick?.let { state.lastDiscoveryTick = it.coerceAtLeast(0L) }
                    command.campaignTick?.let { state.lastCampaignTick = it.coerceAtLeast(0L) }
                }
                CoreCommand.ResetWorld -> resetWorld(state)
            }
        }

        advanceEconomies(state, catalog, rules, frame.elapsedTicks, events, effects)
        automaticDispatch(state, catalog, rules, frame.players, events)
        advanceCampaigns(state, catalog, rules, frame.players, frame.elapsedTicks, events, effects)
        applyTacticalIntent(state, catalog, rules, frame.tactical, effects, events)
        pruneResolvedCampaigns(state, rules)
        publishEffects(state, effects)
        validate(state, catalog, rules)
        return CoreTransition(state, events, state.pendingEffects.values.toList())
    }

    private fun resetWorld(state: CoreSnapshot) {
        state.sequence = 0L
        state.effectSequence = 0L
        state.lastDiscoveryTick = 0L
        state.lastCampaignTick = 0L
        state.dispatchCursor = 0
        state.discoveryCursor = 0
        state.factions.clear()
        state.warbands.clear()
        state.officers.clear()
        state.campaigns.clear()
        state.protectedPlayersUntilTick.clear()
        state.terrain.clear()
        state.initializedPlayerIds.clear()
        state.discoveredSiteIds.clear()
        state.territoryRelations.clear()
        state.garrisons.clear()
        state.pendingEffects.clear()
        state.acknowledgedEffectIds.clear()
        state.rewardedDefeatIds.clear()
        state.defeatedWarlordIds.clear()
    }

    private fun applyPlayerLifecycle(
        state: CoreSnapshot,
        rules: CoreRules,
        observations: List<PlayerLifecycleObservation>,
        events: MutableList<CoreEvent>,
    ) {
        observations.forEach { observation ->
            state.initializedPlayerIds += observation.playerId
            val duration = when (observation.kind) {
                PlayerLifecycleKind.JOINED -> 0L
                PlayerLifecycleKind.RESPAWNED -> rules.respawnProtectionTicks
                PlayerLifecycleKind.DIED -> rules.deathProtectionTicks
            }
            if (duration > 0L) {
                state.protectedPlayersUntilTick[observation.playerId] = maxOf(
                    state.protectedPlayersUntilTick[observation.playerId] ?: 0L,
                    state.tick + duration,
                )
            }
            events += event(state, "player_${observation.kind.name.lowercase()}", observation.playerId)
        }
    }

    internal fun chooseRecruit(
        state: CoreSnapshot,
        warband: WarbandState,
        officer: OfficerState?,
        catalog: CoreCatalog,
        budget: Double,
        members: List<MemberManifest> = emptyList(),
        rules: CoreRules = CoreRules(),
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

    internal fun recruitScore(
        warband: WarbandState,
        officer: OfficerState?,
        recruit: RecruitDefinition,
        rules: CoreRules = CoreRules(),
    ): Double = recruitScore(warband, recruit, rules.effectivePreferences(warband, officer))

    /**
     * Produces the exact budget gate used by both automatic and adapter-driven
     * dispatch. Readiness expresses aggression while reserving enough room for
     * the preference-selected lead recruit and the cheapest distinct support.
     */
    internal fun raidBudget(
        state: CoreSnapshot,
        warband: WarbandState,
        officer: OfficerState?,
        catalog: CoreCatalog,
        rules: CoreRules = CoreRules(),
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

    internal fun assignmentScore(warband: WarbandState, officer: OfficerState, player: PlayerFact): Int {
        if (!player.eligible ||
            player.position.dimension != warband.rally.dimension) return Int.MIN_VALUE / 4
        val distance = manhattan(warband.rally, player.position)
        val grudge = if (officer.lastTargetPlayerId == player.id) 48 else 0
        val rankBias = (officer.rank - 1).coerceAtLeast(0) * 6
        return grudge + rankBias + officer.victories * 5 - officer.defeats * 3 - distance * 4
    }

    internal fun chooseAssignment(
        state: CoreSnapshot,
        warband: WarbandState,
        players: Collection<PlayerFact>,
        officers: Collection<OfficerState> = state.officers.values,
    ): DispatchAssignment? = officers.asSequence()
        .filter { it.homeWarbandId == warband.id && it.availableAtTick <= state.tick && it.deployedCampaignId == null }
        .flatMap { officer -> players.asSequence().map { player -> DispatchAssignment(officer.id, player.id, assignmentScore(warband, officer, player)) } }
        .filter { it.score > Int.MIN_VALUE / 4 }
        .maxWithOrNull(compareBy<DispatchAssignment> { it.score }.thenByDescending { "${it.officerId}|${it.playerId}" })

    /**
     * Builds the exact member/equipment manifest used by campaign dispatch.
     * Adapters may persist this plan and materialize it later without making a
     * second composition decision.
     */
    internal fun planSquad(
        state: CoreSnapshot,
        warband: WarbandState,
        officer: OfficerState?,
        catalog: CoreCatalog,
        budget: Double,
        rules: CoreRules = CoreRules(),
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

    internal fun chooseMaterial(
        state: CoreSnapshot,
        warband: WarbandState,
        catalog: CoreCatalog,
        rules: CoreRules = CoreRules(),
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
    internal fun armamentMaterialDemand(warband: WarbandState, materialId: String, catalog: CoreCatalog): Double =
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

    internal fun chooseEquipment(
        state: CoreSnapshot,
        warband: WarbandState,
        catalog: CoreCatalog,
        rules: CoreRules = CoreRules(),
    ): EquipmentDefinition? {
        val candidates = catalog.equipment + catalog.equipmentPlatforms.mapNotNull { platform ->
            formulateEquipment(state, warband, catalog.materials, platform, rules)
        }
        return candidates.asSequence()
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
    }

    private fun formulateEquipment(
        state: CoreSnapshot,
        warband: WarbandState,
        materials: List<MaterialDefinition>,
        platform: EquipmentPlatformDefinition,
        rules: CoreRules,
    ): EquipmentDefinition? {
        val available = warband.materialLedger.toMutableMap()
        val formulation = mutableListOf<String>()
        val costs = linkedMapOf<String, Double>()
        var capabilities = platform.baseCapabilities
        platform.components.forEachIndexed { index, component ->
            val material = choosePartMaterial(
                state, warband, materials, component.compatibleMaterialIds, available,
                component.requiredUnits, index, rules,
            ) ?: return null
            formulation += material.id
            costs[material.id] = costs.getOrDefault(material.id, 0.0) + component.requiredUnits
            available[material.id] = available.getOrDefault(material.id, 0.0) - component.requiredUnits
            capabilities += material.capabilities.componentTimes(component.capabilityScale) * component.requiredUnits
        }
        capabilities = capabilities.componentTimes(CapabilityVector(
            platform.aggregationParameters["durabilityScale"] ?: 1.0,
            platform.aggregationParameters["damageScale"] ?: 1.0,
            platform.aggregationParameters["mobilityScale"] ?: 1.0,
            platform.aggregationParameters["rangeScale"] ?: 1.0,
            platform.aggregationParameters["controlScale"] ?: 1.0,
        )) + CapabilityVector(
            platform.aggregationParameters["durabilityOffset"] ?: 0.0,
            platform.aggregationParameters["damageOffset"] ?: 0.0,
            platform.aggregationParameters["mobilityOffset"] ?: 0.0,
            platform.aggregationParameters["rangeOffset"] ?: 0.0,
            platform.aggregationParameters["controlOffset"] ?: 0.0,
        )
        return EquipmentDefinition(platform.id, formulation, capabilities, costs, platform.supportedActions)
    }

    private fun CapabilityVector.componentTimes(other: CapabilityVector) = CapabilityVector(
        durability * other.durability,
        damage * other.damage,
        mobility * other.mobility,
        range * other.range,
        control * other.control,
    )

    internal fun choosePartMaterial(
        state: CoreSnapshot,
        warband: WarbandState,
        materials: Collection<MaterialDefinition>,
        compatibleIds: Set<String>,
        available: Map<String, Double>,
        requiredUnits: Double,
        salt: Int,
        rules: CoreRules = CoreRules(),
    ): MaterialDefinition? = materials.asSequence()
        .filter { it.id in compatibleIds && available.getOrDefault(it.id, 0.0) + EPSILON >= requiredUnits }
        .maxWithOrNull(compareBy<MaterialDefinition> { material ->
            rules.capabilityUtility(material.capabilities, rules.armamentPreferences(warband, null)) +
                material.tier * warband.environment.bounded().exoticPotential * 0.10 -
                EcologyMath.repetitionPenalty(warband.selectionMemory.materials, material.id, rules.diversityWeight)
        }.thenByDescending { deterministicTie(warband.id, it.id, state.sequence + salt) })

    internal fun chooseTacticalPosition(
        positions: Collection<TacticalPosition>,
        capabilities: CapabilityVector,
        preferences: CapabilityVector,
        cohesionRadius: Double,
    ): TacticalPosition? = positions.asSequence()
        .filter(TacticalPosition::reachable)
        .maxWithOrNull(compareBy<TacticalPosition> {
            EcologyMath.tacticalScore(it, capabilities, preferences, cohesionRadius)
        }.thenByDescending(TacticalPosition::id))

    internal fun validate(state: CoreSnapshot, catalog: CoreCatalog, rules: CoreRules = CoreRules()) {
        require(state.tick >= 0L && state.sequence >= 0L)
        state.warbands.values.forEach { warband ->
            require(warband.reserveThreat >= -EPSILON && warband.raidPool >= -EPSILON && warband.garrisonThreat >= -EPSILON)
            require(warband.capacity > 0.0 && warband.aggression in rules.minimumAggression..rules.maximumAggression)
            require(warband.materialLedger.values.all { it.isFinite() && it >= -EPSILON })
            require(warband.stockpile.values.all { it >= 0 })
            require(warband.preferences.values.all(Double::isFinite))
        }
        val memberIds = state.campaigns.values.flatMap { it.members }.map { it.id } +
            state.garrisons.values.filter { it.phase != GarrisonPhase.RESOLVED }.flatMap { it.members }.map { it.id }
        require(memberIds.distinct().size == memberIds.size) { "duplicate deployed member identity" }
        val equipmentIds = state.warbands.values.flatMap { it.armory }.map { it.id } +
            state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }
                .flatMap { it.members }.mapNotNull { it.equipment?.id } +
            state.garrisons.values.filter { it.phase != GarrisonPhase.RESOLVED }
                .flatMap { it.members }.mapNotNull { it.equipment?.id }
        require(equipmentIds.distinct().size == equipmentIds.size) { "duplicate equipment identity" }
        val targets = state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }.map { it.targetPlayerId }
        require(targets.distinct().size == targets.size) { "multiple unresolved campaigns target one player" }
        state.campaigns.values.forEach { campaign ->
            val officer = state.officers[campaign.officerId]
            if (campaign.phase == CampaignPhase.RESOLVED) {
                require(officer?.deployedCampaignId != campaign.id) { "resolved campaign ${campaign.id} still owns its officer" }
            } else {
                require(officer != null) { "campaign ${campaign.id} references unknown officer ${campaign.officerId}" }
                require(officer.deployedCampaignId == campaign.id) { "unresolved campaign ${campaign.id} does not own its officer" }
            }
        }
        state.officers.values.forEach { officer ->
            officer.deployedCampaignId?.let { campaignId ->
                val campaign = state.campaigns[campaignId]
                require(campaign != null && campaign.phase != CampaignPhase.RESOLVED && campaign.officerId == officer.id) {
                    "officer ${officer.id} references non-live campaign $campaignId"
                }
            }
        }
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
        state.garrisons.values.forEach { garrison ->
            require(state.warbands.containsKey(garrison.warbandId)) { "unknown garrison warband ${garrison.warbandId}" }
            require(garrison.physicalMemberIds.all { id -> garrison.members.any { it.id == id } })
        }
        require(state.pendingEffects.all { (id, effect) -> id.isNotBlank() && id == effect.effectId })
        require(state.pendingEffects.keys.none(state.acknowledgedEffectIds::contains))
        state.territoryRelations.values.forEach { relation ->
            require(state.warbands.containsKey(relation.warbandId))
            require(relation.protectedUntilTick >= 0L)
        }
    }

    private fun applyAcknowledgements(
        state: CoreSnapshot,
        acknowledgements: List<EffectAcknowledgement>,
        events: MutableList<CoreEvent>,
    ) {
        acknowledgements.forEach { acknowledgement ->
            if (acknowledgement.effectId in state.acknowledgedEffectIds) return@forEach
            val effect = state.pendingEffects[acknowledgement.effectId] ?: return@forEach
            require(effect.kind in setOf(
                EffectKind.WARN_PLAYER, EffectKind.REWARD_PLAYER,
                EffectKind.PROMOTE_SUCCESSOR, EffectKind.MATERIALIZE_WARLORD,
            )) { "effect ${effect.effectId} requires a typed result" }
            if (!acknowledgement.successful) {
                events += event(
                    state, "effect_attempt_failed", acknowledgement.effectId,
                    acknowledgement.detail.ifBlank { effect.kind.name.lowercase() },
                )
                return@forEach
            }
            state.pendingEffects.remove(acknowledgement.effectId)
            state.acknowledgedEffectIds += acknowledgement.effectId
            events += event(
                state,
                "effect_acknowledged",
                acknowledgement.effectId,
                acknowledgement.detail.ifBlank { effect.kind.name.lowercase() },
            )
        }
    }

    private fun applyEquipmentRealizations(
        state: CoreSnapshot,
        results: List<EquipmentRealizationResult>,
        events: MutableList<CoreEvent>,
    ) {
        results.forEach resultLoop@{ result ->
            if (result.effectId in state.acknowledgedEffectIds) return@resultLoop
            val effect = state.pendingEffects[result.effectId]
                ?.takeIf { it.kind == EffectKind.REALIZE_EQUIPMENT }
                ?: return@resultLoop
            val manifest = effect.equipmentManifest?.takeIf { it.id == result.equipmentId } ?: return@resultLoop
            val warband = effect.warbandId?.let(state.warbands::get) ?: return@resultLoop
            val stored = warband.armory.firstOrNull { it.id == manifest.id } ?: return@resultLoop
            state.pendingEffects.remove(result.effectId)
            state.acknowledgedEffectIds += result.effectId
            if (result.successful) {
                result.measuredCapabilities?.takeIf(CapabilityVector::finite)?.let { measured ->
                    val index = warband.armory.indexOf(stored)
                    warband.armory[index] = stored.copy(capabilities = measured)
                }
                events += event(state, "equipment_realized", manifest.id, result.detail)
            } else {
                warband.armory.remove(stored)
                stored.billOfMaterials.forEach { (materialId, amount) ->
                    warband.materialLedger[materialId] = warband.materialLedger.getOrDefault(materialId, 0.0) + amount
                }
                events += event(state, "equipment_realization_failed", manifest.id, result.detail)
            }
        }
    }

    private fun applyNavigationResults(
        state: CoreSnapshot,
        results: List<NavigationResult>,
        events: MutableList<CoreEvent>,
    ) {
        results.forEach resultLoop@{ result ->
            if (result.effectId in state.acknowledgedEffectIds) return@resultLoop
            val effect = state.pendingEffects[result.effectId]
                ?.takeIf { it.kind == EffectKind.NAVIGATE && it.campaignId == result.campaignId }
                ?: return@resultLoop
            require(effect.memberIds == listOf(result.memberId)) { "navigation result member does not match effect" }
            state.pendingEffects.remove(effect.effectId)
            state.acknowledgedEffectIds += effect.effectId
            events += event(
                state, "navigation_${result.status.name.lowercase()}", result.campaignId,
                result.detail.ifBlank { effect.tacticalPositionId.orEmpty() },
            )
        }
    }

    private fun acknowledgeEffect(
        state: CoreSnapshot,
        effectId: String,
        expectedKind: EffectKind,
        events: MutableList<CoreEvent>,
        campaignId: String? = null,
        garrisonId: String? = null,
    ): Boolean {
        if (effectId in state.acknowledgedEffectIds) return false
        val effect = state.pendingEffects[effectId] ?: return false
        require(effect.kind == expectedKind) { "effect $effectId has kind ${effect.kind}, expected $expectedKind" }
        require(campaignId == null || effect.campaignId == campaignId) { "effect $effectId belongs to another campaign" }
        require(garrisonId == null || effect.garrisonId == garrisonId) { "effect $effectId belongs to another garrison" }
        state.pendingEffects.remove(effectId)
        state.acknowledgedEffectIds += effectId
        events += event(state, "effect_acknowledged", effectId)
        return true
    }

    private fun publishEffects(state: CoreSnapshot, requested: List<CoreEffect>) {
        requested.forEach { candidate ->
            val duplicate = state.pendingEffects.values.any { pending ->
                pending.kind == candidate.kind && pending.warbandId == candidate.warbandId &&
                    pending.campaignId == candidate.campaignId && pending.garrisonId == candidate.garrisonId &&
                    pending.playerId == candidate.playerId && pending.tacticalPositionId == candidate.tacticalPositionId &&
                    pending.position == candidate.position && pending.memberIds == candidate.memberIds &&
                    pending.blockPosition == candidate.blockPosition &&
                    pending.memberPlacements == candidate.memberPlacements &&
                    pending.itemId == candidate.itemId && pending.count == candidate.count &&
                    pending.equipmentManifest == candidate.equipmentManifest && pending.memberManifest == candidate.memberManifest &&
                    pending.memberManifests == candidate.memberManifests
            }
            if (!duplicate) {
                val effect = freezeEffect(candidate).copy(effectId = nextEffectId(state))
                state.pendingEffects[effect.effectId] = effect
            }
        }
    }

    private fun freezeEffect(effect: CoreEffect): CoreEffect = effect.copy(
        memberIds = effect.memberIds.toList(),
        equipmentManifest = effect.equipmentManifest?.let(::copyEquipment),
        memberManifest = effect.memberManifest?.let(::copyMember),
        memberManifests = effect.memberManifests.map(::copyMember),
        memberPlacements = effect.memberPlacements.toList(),
    )

    private fun copyMember(member: MemberManifest): MemberManifest = member.copy(
        equipment = member.equipment?.let(::copyEquipment),
        cargo = member.cargo.toMutableMap(),
    )

    private fun copyEquipment(equipment: EquipmentManifest): EquipmentManifest = equipment.copy(
        formulation = equipment.formulation.toList(),
        billOfMaterials = equipment.billOfMaterials.toMap(),
        supportedActions = equipment.supportedActions.toSet(),
    )

    private fun applyDiscoveries(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        players: List<PlayerFact>,
        observations: List<WarbandDiscoveryObservation>,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        if (observations.isEmpty()) return
        if (state.lastDiscoveryTick > 0L && state.tick - state.lastDiscoveryTick < rules.discoveryIntervalTicks) return
        val ordered = observations.sortedWith(compareBy<WarbandDiscoveryObservation> { it.coveragePlayerId == null }
            .thenBy { it.rally.dimension }
            .thenBy { it.cellX ?: it.rally.x }.thenBy { it.cellZ ?: it.rally.z }.thenBy { it.siteId })
        val work = minOf(rules.discoveryWorkBudget.coerceAtLeast(0), ordered.size)
        val coverage = ordered.filter { it.coveragePlayerId != null }.take(work)
        val procedural = ordered.filter { it.coveragePlayerId == null }
        val proceduralWork = (work - coverage.size).coerceAtMost(procedural.size)
        val start = if (procedural.isEmpty()) 0 else Math.floorMod(state.discoveryCursor, procedural.size)
        val scheduled = coverage + (0 until proceduralWork).map { procedural[(start + it) % procedural.size] }
        state.discoveryCursor = if (procedural.isEmpty()) 0 else (start + proceduralWork) % procedural.size
        scheduled.forEach { observation ->
            require((observation.cellX == null) == (observation.cellZ == null))
            require(observation.siteId.isNotBlank() || observation.cellX != null)
            val siteKey = if (observation.cellX != null) {
                "${observation.rally.dimension}|${observation.worldSeed}|${observation.cellX}|${observation.cellZ}"
            } else observation.siteId
            val exactSite = observation.siteCandidates.sortedWith(compareBy<BlockPosition> { it.dimension }
                .thenBy { it.x }.thenBy { it.y }.thenBy { it.z }).firstOrNull()
            val baseRally = exactSite?.let { ChunkPosition(it.dimension, it.x shr 4, it.z shr 4) } ?: observation.rally
            val seed = deterministicTie(
                siteKey, baseRally.dimension,
                observation.worldSeed xor (baseRally.x.toLong() * 31L + baseRally.z),
            )
            val rally = baseRally
            val coveragePlayer = observation.coveragePlayerId?.let { playerId ->
                players.firstOrNull { it.id == playerId && it.eligible && it.physicallyAvailable }
            }
            if (observation.coveragePlayerId != null && coveragePlayer == null) return@forEach
            if (coveragePlayer != null && state.warbands.values.any {
                    !it.defeated && it.rally.dimension == coveragePlayer.position.dimension &&
                        manhattan(it.rally, coveragePlayer.position) <= rules.maximumDispatchDistanceChunks
                }) return@forEach
            if (state.warbands.values.any {
                    it.rally.dimension == rally.dimension &&
                        manhattan(it.rally, rally) < rules.discoveryMinimumSpacingChunks
                }) return@forEach
            if (players.isNotEmpty() && players.none {
                    it.position.dimension == rally.dimension &&
                        manhattan(it.position, rally) in
                            rules.discoveryMinimumPlayerDistanceChunks..rules.discoveryMaximumDistanceChunks
                }) return@forEach
            val chance = rules.discoveryChance.coerceIn(0.0, 1.0)
            val roll = (deterministicTie(siteKey, "discovery", observation.worldSeed).ushr(11).toDouble() /
                (1L shl 53).toDouble()).coerceIn(0.0, 1.0)
            if (roll > chance && coveragePlayer == null) return@forEach
            if (!state.discoveredSiteIds.add(siteKey)) return@forEach
            val factionId = nextId(state, "faction")
            val warbandId = nextId(state, "warband")
            val officerId = nextId(state, "officer")
            require(factionId.isNotBlank() && warbandId.isNotBlank() && officerId.isNotBlank())
            require(factionId !in state.factions && warbandId !in state.warbands && officerId !in state.officers) {
                "discovery supplied an existing canonical identity"
            }
            val environment = observation.environment.bounded()
            state.factions[factionId] = FactionState(factionId, "Faction-${seed.toULong().toString(36).take(8)}", seed.toInt())
            val initialThreat = maxOf(
                rules.discoveryInitialThreat(environment),
                catalog.recruits.minOfOrNull(RecruitDefinition::baseThreat) ?: 0.0,
            )
            val warband = WarbandState(
                warbandId,
                factionId,
                rally,
                rules.capacity(environment).toDouble(),
                initialThreat,
                aggression = rules.initialAggression.coerceIn(rules.minimumAggression, rules.maximumAggression),
                environment = environment,
                preferences = FormulaMath.initialPreferences(seed, environment),
                activeCampaignLimit = rules.defaultActiveCampaignLimit.coerceAtLeast(1),
                nextRaidTick = state.tick + rules.raidCooldownTicks,
            )
            state.warbands[warbandId] = warband
            state.officers[officerId] = OfficerState(
                officerId,
                factionId,
                warbandId,
                FormulaMath.initialPreferences(seed xor -7046029254386353131L, environment),
            )
            val recruit = chooseRecruit(state, warband, state.officers[officerId], catalog, warband.reserveThreat, rules = rules)
            if (recruit != null) {
                val threat = observedThreat(warband, recruit)
                val warlord = MemberManifest(nextId(state, "member"), recruit.id, threat)
                warband.reserveThreat = (warband.reserveThreat - threat).coerceAtLeast(0.0)
                warband.warlord = warlord
                effects += CoreEffect(
                    kind = EffectKind.MATERIALIZE_WARLORD,
                    warbandId = warband.id,
                    position = warband.rally,
                    blockPosition = exactSite,
                    memberIds = listOf(warlord.id),
                    memberManifest = warlord,
                    memberPlacements = exactSite?.let { listOf(MemberPlacement(warlord.id, it)) }.orEmpty(),
                )
            }
            reserveGarrison(
                state, catalog, rules,
                ReserveGarrisonCommand(warband.id, warband.rally, blockPosition = exactSite),
                events, effects,
            )
            events += event(state, "warband_discovered", warbandId, siteKey)
        }
        state.lastDiscoveryTick = state.tick.coerceAtLeast(1L)
    }

    private fun applyTerritory(
        state: CoreSnapshot,
        rules: CoreRules,
        contacts: List<TerritoryContactObservation>,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        contacts.forEach { contact ->
            require(contact.distanceChunks.isFinite() && contact.distanceChunks >= 0.0)
            val warband = state.warbands[contact.warbandId] ?: return@forEach
            val key = "${contact.warbandId}|${contact.playerId}"
            val previous = state.territoryRelations[key]?.status ?: TerritoryStatus.UNCONTACTED
            val boundary = (rules.territoryRadiusChunks - rules.territoryWarningBandChunks).coerceAtLeast(0)
            val next = when {
                previous == TerritoryStatus.HOSTILE || contact.attacked || contact.distanceChunks < boundary -> TerritoryStatus.HOSTILE
                contact.distanceChunks <= rules.territoryRadiusChunks -> TerritoryStatus.WARNED
                else -> TerritoryStatus.UNCONTACTED
            }
            state.initializedPlayerIds += contact.playerId
            val protection = maxOf(
                state.protectedPlayersUntilTick[contact.playerId] ?: 0L,
                state.territoryRelations[key]?.protectedUntilTick ?: 0L,
            )
            state.territoryRelations[key] = TerritoryRelationState(
                warband.id, contact.playerId, next, protection,
            )
            if (next != previous && next != TerritoryStatus.UNCONTACTED) {
                effects += CoreEffect(
                    EffectKind.WARN_PLAYER, warbandId = warband.id, playerId = contact.playerId,
                    memberIds = listOf(next.name.lowercase()),
                )
                events += event(state, "territory_${next.name.lowercase()}", warband.id, contact.playerId)
            }
        }
    }

    private fun applyMaterializationSites(
        state: CoreSnapshot,
        observations: List<MaterializationSiteObservation>,
        effects: MutableList<CoreEffect>,
        events: MutableList<CoreEvent>,
    ) {
        observations.forEach { observation ->
            val campaign = state.campaigns[observation.campaignId]
                ?.takeIf { it.phase == CampaignPhase.READY_TO_MATERIALIZE && !it.physical }
                ?: return@forEach
            if (state.pendingEffects.values.any {
                    it.kind == EffectKind.MATERIALIZE && it.campaignId == campaign.id
                }) return@forEach
            val anchor = observation.candidates.asSequence()
                .filter { it.dimension == campaign.position.dimension }
                .sortedWith(compareBy<BlockPosition> { it.x }.thenBy { it.y }.thenBy { it.z })
                .firstOrNull() ?: return@forEach
            effects += CoreEffect(
                kind = EffectKind.MATERIALIZE,
                warbandId = campaign.warbandId,
                campaignId = campaign.id,
                playerId = campaign.targetPlayerId,
                position = ChunkPosition(anchor.dimension, anchor.x shr 4, anchor.z shr 4),
                blockPosition = anchor,
                memberIds = campaign.members.map(MemberManifest::id),
                memberManifests = campaign.members,
                memberPlacements = campaign.members.mapIndexed { index, member ->
                    MemberPlacement(member.id, anchor.copy(x = anchor.x + index % 3 - 1, z = anchor.z + index / 3 + 1))
                },
            )
            events += event(state, "materialization_site_selected", campaign.id, "${anchor.x},${anchor.y},${anchor.z}")
        }
    }

    private fun reserveGarrison(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        command: ReserveGarrisonCommand,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        val warband = state.warbands[command.warbandId]?.takeUnless(WarbandState::defeated) ?: return
        command.desiredThreat?.let { require(it.isFinite() && it >= 0.0) }
        val minimumThreat = catalog.recruits.minOfOrNull { observedThreat(warband, it) } ?: return
        val desiredThreat = command.desiredThreat ?: rules.garrisonThreatTarget(warband, minimumThreat)
        val budget = minOf(desiredThreat, warband.reserveThreat)
        if (budget <= EPSILON) return
        val plan = planSquad(state, warband, null, catalog, budget, rules)
        if (plan.members.isEmpty()) return
        warband.reserveThreat -= plan.committedThreat
        warband.garrisonThreat += plan.committedThreat
        val id = nextId(state, "garrison")
        state.garrisons[id] = GarrisonState(id, warband.id, command.position, plan.members.toMutableList())
        effects += CoreEffect(
            EffectKind.MATERIALIZE_GARRISON,
            warbandId = warband.id,
            position = command.position,
            blockPosition = command.blockPosition,
            memberIds = plan.members.map(MemberManifest::id),
            memberManifests = plan.members,
            memberPlacements = command.blockPosition?.let { anchor ->
                plan.members.mapIndexed { index, member ->
                    MemberPlacement(member.id, anchor.copy(x = anchor.x + index % 3 - 1, z = anchor.z + index / 3 + 1))
                }
            }.orEmpty(),
            garrisonId = id,
        )
        events += event(state, "garrison_reserved", id, "threat=${plan.committedThreat}")
    }

    private fun applyGarrisonResults(
        state: CoreSnapshot,
        results: List<GarrisonResult>,
        events: MutableList<CoreEvent>,
    ) {
        results.forEach resultLoop@{ result ->
            if (!acknowledgeEffect(state, result.effectId, EffectKind.MATERIALIZE_GARRISON, events, garrisonId = result.garrisonId)) return@resultLoop
            val garrison = state.garrisons[result.garrisonId] ?: return@resultLoop
            if (garrison.phase != GarrisonPhase.RESERVED) return@resultLoop
            val warband = state.warbands[garrison.warbandId] ?: return@resultLoop
            if (!result.success) {
                garrison.members.toList().forEach { restoreUndeployedMember(warband, it, toReserve = true) }
                warband.garrisonThreat = (warband.garrisonThreat - garrison.members.sumOf(MemberManifest::threat)).coerceAtLeast(0.0)
                garrison.members.clear()
                garrison.phase = GarrisonPhase.RESOLVED
                events += event(state, "garrison_materialization_failed", garrison.id)
                return@resultLoop
            }
            val successfulIds = result.physicalMemberIds.ifEmpty { garrison.members.mapTo(linkedSetOf(), MemberManifest::id) }
            require(successfulIds.all { id -> garrison.members.any { it.id == id } }) { "unknown physical garrison member" }
            val failed = garrison.members.filter { it.id !in successfulIds }
            failed.forEach { member ->
                restoreUndeployedMember(warband, member, toReserve = true)
                warband.garrisonThreat = (warband.garrisonThreat - member.threat).coerceAtLeast(0.0)
            }
            garrison.members.removeAll(failed.toSet())
            garrison.physicalMemberIds.clear()
            garrison.physicalMemberIds += successfulIds
            garrison.phase = GarrisonPhase.ACTIVE
            events += event(state, "garrison_materialized", garrison.id, "members=${garrison.members.size}")
        }
    }

    private fun resolveGarrison(
        state: CoreSnapshot,
        command: ResolveGarrisonCommand,
        events: MutableList<CoreEvent>,
    ) {
        val garrison = state.garrisons[command.garrisonId] ?: return
        if (garrison.phase == GarrisonPhase.RESOLVED) return
        val warband = state.warbands[garrison.warbandId] ?: return
        require(command.survivingMemberIds.all { id -> garrison.members.any { it.id == id } })
        val committed = garrison.members.sumOf(MemberManifest::threat)
        garrison.members.filter { it.id in command.survivingMemberIds }.forEach { restoreUndeployedMember(warband, it, toReserve = true) }
        warband.garrisonThreat = (warband.garrisonThreat - committed).coerceAtLeast(0.0)
        garrison.members.clear()
        garrison.physicalMemberIds.clear()
        garrison.phase = GarrisonPhase.RESOLVED
        events += event(state, "garrison_resolved", garrison.id, "survivors=${command.survivingMemberIds.size}")
    }

    private fun applyGarrisonSnapshots(
        state: CoreSnapshot,
        results: List<GarrisonSnapshotResult>,
        events: MutableList<CoreEvent>,
    ) {
        results.forEach resultLoop@{ result ->
            if (result.effectId != null && !acknowledgeEffect(
                    state, result.effectId, EffectKind.CAPTURE_SNAPSHOTS, events, garrisonId = result.garrisonId,
                )) return@resultLoop
            val garrison = state.garrisons[result.garrisonId]?.takeIf { it.phase == GarrisonPhase.ACTIVE } ?: return@resultLoop
            val warband = state.warbands[garrison.warbandId] ?: return@resultLoop
            val existing = garrison.members.associateBy(MemberManifest::id)
            val snapshots = result.members.associateBy(MemberSnapshot::memberId)
            require(snapshots.keys.all(existing::containsKey)) { "unknown physical garrison member" }
            val casualties = garrison.members.filter { it.id !in snapshots || snapshots.getValue(it.id).healthFraction <= EPSILON }
            warband.garrisonThreat = (warband.garrisonThreat - casualties.sumOf(MemberManifest::threat)).coerceAtLeast(0.0)
            garrison.members.removeAll(casualties.toSet())
            garrison.members.forEach { member ->
                val snapshot = snapshots.getValue(member.id)
                member.healthFraction = snapshot.healthFraction.coerceIn(0.0, 1.0)
                member.experience = snapshot.experience.coerceAtLeast(0.0)
                member.equipment = snapshot.equipment
                member.cargo.clear()
                snapshot.cargo.filterValues { it > 0 }.forEach(member.cargo::put)
            }
            garrison.physicalMemberIds.retainAll(garrison.members.mapTo(hashSetOf(), MemberManifest::id))
            if (garrison.members.isEmpty()) garrison.phase = GarrisonPhase.RESOLVED
            events += event(state, "garrison_snapshots_applied", garrison.id, "members=${garrison.members.size}")
        }
    }

    private fun collapseWarband(
        state: CoreSnapshot,
        warbandId: String,
        reason: String,
        events: MutableList<CoreEvent>,
    ) {
        val warband = state.warbands[warbandId] ?: return
        if (warband.defeated) return
        warband.defeated = true
        warband.reserveThreat = 0.0
        warband.raidPool = 0.0
        warband.garrisonThreat = 0.0
        state.campaigns.values.filter { it.warbandId == warbandId && it.phase != CampaignPhase.RESOLVED }.forEach { campaign ->
            campaign.members.toList().forEach { cacheMember(state, campaign, it) }
            campaign.members.clear()
            campaign.physicalMemberIds.clear()
            campaign.physical = false
            campaign.phase = CampaignPhase.RESOLVED
            campaign.resolvedAtTick = state.tick
            campaign.returnReason = reason
            state.officers[campaign.officerId]?.deployedCampaignId = null
        }
        state.garrisons.values.filter { it.warbandId == warbandId && it.phase != GarrisonPhase.RESOLVED }.forEach { garrison ->
            garrison.members.clear()
            garrison.physicalMemberIds.clear()
            garrison.phase = GarrisonPhase.RESOLVED
        }
        state.officers.values.filter { it.homeWarbandId == warbandId }.forEach { officer ->
            officer.availableAtTick = Long.MAX_VALUE
            officer.deployedCampaignId = null
        }
        val cancelled = state.pendingEffects.filterValues { it.warbandId == warbandId }.keys
        cancelled.forEach { state.pendingEffects.remove(it) }
        events += event(state, "warband_collapsed", warband.id, reason)
    }

    private fun collapseFaction(
        state: CoreSnapshot,
        factionId: String,
        reason: String,
        events: MutableList<CoreEvent>,
    ) {
        if (factionId !in state.factions) return
        state.warbands.values.filter { it.factionId == factionId }.forEach { collapseWarband(state, it.id, reason, events) }
        state.officers.values.filter { it.factionId == factionId }.forEach {
            it.availableAtTick = Long.MAX_VALUE
            it.deployedCampaignId = null
        }
        events += event(state, "faction_collapsed", factionId, reason)
    }

    private fun applyDefeats(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        observations: List<DefeatObservation>,
        effects: MutableList<CoreEffect>,
        events: MutableList<CoreEvent>,
    ) {
        observations.distinctBy { "${it.campaignId}|${it.memberId}" }.forEach { observation ->
            require(observation.authority.isFinite() && observation.authority >= 0.0)
            val defeatId = "${observation.campaignId}|${observation.memberId}"
            if (defeatId in state.rewardedDefeatIds) return@forEach
            val campaign = state.campaigns[observation.campaignId] ?: return@forEach
            val member = campaign.members.firstOrNull { it.id == observation.memberId } ?: return@forEach
            val desiredValue = member.threat * (1.0 + observation.authority.coerceAtMost(3.0))
            val denomination = catalog.rewards.filter { it.value <= desiredValue + EPSILON }.maxByOrNull(RewardDefinition::value)
                ?: catalog.rewards.minByOrNull(RewardDefinition::value)
                ?: return@forEach
            state.rewardedDefeatIds += defeatId
            val count = ceil(desiredValue / denomination.value).toInt().coerceIn(1, denomination.maximumStackSize)
            effects += CoreEffect(
                kind = EffectKind.REWARD_PLAYER,
                warbandId = campaign.warbandId,
                campaignId = campaign.id,
                playerId = observation.playerId,
                memberIds = listOf(member.id),
                itemId = denomination.itemId,
                count = count,
            )
            events += event(state, "reward_selected", member.id, "${denomination.itemId}:$count")
        }
    }

    private fun applyWarlordDefeats(
        state: CoreSnapshot,
        observations: List<WarlordDefeatObservation>,
        events: MutableList<CoreEvent>,
    ) {
        observations.forEach { observation ->
            val warband = state.warbands[observation.warbandId] ?: return@forEach
            val warlord = warband.warlord?.takeIf { it.id == observation.memberId } ?: return@forEach
            if (!state.defeatedWarlordIds.add(warlord.id)) return@forEach
            events += event(state, "warlord_defeated", warband.id, observation.playerId.orEmpty())
            collapseFaction(state, warband.factionId, "warlord_defeated", events)
        }
    }

    private fun promoteSuccessor(
        state: CoreSnapshot,
        rules: CoreRules,
        command: PromoteSuccessorCommand,
        events: MutableList<CoreEvent>,
    ) {
        val warband = state.warbands[command.warbandId] ?: return
        val candidates = state.officers.values.filter {
            it.homeWarbandId == warband.id && it.id != command.fallenOfficerId &&
                it.availableAtTick != Long.MAX_VALUE && it.deployedCampaignId == null
        }
        val successor = candidates.maxWithOrNull(compareBy<OfficerState> {
            it.rank + it.victories * rules.successorVictoryWeight + it.defeats * rules.successorDefeatWeight
        }.thenByDescending(OfficerState::id)) ?: OfficerState(
            nextId(state, "officer"), warband.factionId, warband.id, warband.preferences.toMutableMap(),
        ).also { state.officers[it.id] = it }
        successor.rank += 1
        successor.availableAtTick = state.tick
        events += event(state, "successor_promoted", successor.id, command.fallenOfficerId.orEmpty())
    }

    private fun selectCampaignSuccessor(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        campaignId: String,
        excludedMemberIds: Set<String>,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        val campaign = state.campaigns[campaignId]?.takeIf { it.phase == CampaignPhase.ACTIVE } ?: return
        val warband = state.warbands[campaign.warbandId] ?: return
        val preferences = rules.effectivePreferences(warband, state.officers[campaign.officerId])
        val successor = campaign.members.filter { it.id !in excludedMemberIds }.maxWithOrNull(compareBy<MemberManifest> { member ->
            val recruit = catalog.recruits.firstOrNull { it.id == member.recruitId }
            val capabilities = (recruit?.capabilities ?: CapabilityVector()) +
                (member.equipment?.capabilities ?: CapabilityVector())
            member.threat * 0.25 + member.healthFraction * 2.0 + member.experience + capabilities.dot(preferences)
        }.thenByDescending(MemberManifest::id))
        campaign.leaderMemberId = successor?.id
        successor ?: return
        effects += CoreEffect(
            EffectKind.PROMOTE_SUCCESSOR,
            warbandId = warband.id,
            campaignId = campaign.id,
            memberIds = listOf(successor.id),
            memberManifest = successor,
        )
        events += event(state, "campaign_successor_selected", campaign.id, successor.id)
    }

    private fun applyTacticalIntent(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        observations: List<TacticalObservation>,
        effects: MutableList<CoreEffect>,
        events: MutableList<CoreEvent>,
    ) {
        observations.forEach { observation ->
            val campaign = state.campaigns[observation.campaignId]?.takeIf { it.phase == CampaignPhase.ACTIVE } ?: return@forEach
            val warband = state.warbands[campaign.warbandId] ?: return@forEach
            val officer = state.officers[campaign.officerId]
            val preferences = rules.effectivePreferences(warband, officer)
            val capabilities = campaign.members.map { member ->
                val recruit = catalog.recruits.firstOrNull { it.id == member.recruitId }?.capabilities ?: CapabilityVector()
                (recruit + (member.equipment?.capabilities ?: CapabilityVector())) * member.healthFraction
            }.reduceOrNull(CapabilityVector::plus)?.times(1.0 / campaign.members.size.coerceAtLeast(1)) ?: CapabilityVector()
            val selected = chooseTacticalPosition(
                observation.positions, capabilities, preferences, observation.cohesionRadius,
            ) ?: return@forEach
            campaign.physicalMemberIds.sorted().forEach memberLoop@{ memberId ->
                if (state.pendingEffects.values.any {
                        it.kind == EffectKind.NAVIGATE && it.campaignId == campaign.id && it.memberIds == listOf(memberId)
                    }) return@memberLoop
                effects += CoreEffect(
                    EffectKind.NAVIGATE,
                    warbandId = warband.id,
                    campaignId = campaign.id,
                    playerId = campaign.targetPlayerId,
                    position = selected.position,
                    blockPosition = selected.blockPosition,
                    memberIds = listOf(memberId),
                    tacticalPositionId = selected.id,
                )
            }
            events += event(state, "tactical_intent_selected", campaign.id, selected.id)
        }
    }

    private fun restoreUndeployedMember(warband: WarbandState, member: MemberManifest, toReserve: Boolean = false) {
        if (toReserve) warband.reserveThreat += member.threat else warband.raidPool += member.threat
        member.cargo.forEach { (id, count) -> warband.stockpile[id] = warband.stockpile.getOrDefault(id, 0) + count }
        member.cargo.clear()
        member.equipment?.takeIf { it.durabilityFraction > EPSILON }?.let(warband.armory::add)
        member.equipment = null
    }

    private fun advanceEconomies(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        elapsed: Long,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        var remaining = elapsed
        var simulationTick = state.tick - elapsed
        while (remaining > 0L) {
            val slice = minOf(20L, remaining)
            simulationTick += slice
            state.warbands.values.forEach {
                EcologyMath.decay(it.selectionMemory, simulationTick, rules.selectionMemoryHalfLifeTicks)
            }
            advanceEconomySlice(state, catalog, rules, slice, events, effects)
            remaining -= slice
        }
    }

    private fun advanceEconomySlice(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        elapsed: Long,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
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
                    manufacture(state, warband, catalog, rules, events, effects)
                }
            }

            warband.extractionTickDebt += elapsed
            val extractionTicks = rules.extractionTicks(warband.environment)
            while (warband.extractionTickDebt + EPSILON >= extractionTicks) {
                warband.extractionTickDebt -= extractionTicks
                extract(state, warband, catalog, events)
                if (warband.armory.size < rules.desiredArmoryItems(warband, catalog.recruits)) {
                    manufacture(state, warband, catalog, rules, events, effects)
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

    private fun extract(state: CoreSnapshot, warband: WarbandState, catalog: CoreCatalog, events: MutableList<CoreEvent>) {
        val selected = chooseMaterial(state, warband, catalog) ?: return
        warband.materialLedger[selected.id] = warband.materialLedger.getOrDefault(selected.id, 0.0) + 1.0
        warband.selectionMemory.materials[selected.id] = warband.selectionMemory.materials.getOrDefault(selected.id, 0.0) + 1.0
        events += event(state, "extracted", warband.id, selected.id)
        acquireEnvironmentalResource(state, warband, catalog, warband.environment, warband.stockpile, events)
    }

    private fun manufacture(
        state: CoreSnapshot,
        warband: WarbandState,
        catalog: CoreCatalog,
        rules: CoreRules,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        if (warband.armory.size >= rules.maximumArmoryItems.coerceAtLeast(0)) return
        val selected = chooseEquipment(state, warband, catalog) ?: return
        selected.cost.forEach { (id, amount) -> warband.materialLedger[id] = (warband.materialLedger.getOrDefault(id, 0.0) - amount).coerceAtLeast(0.0) }
        val manifest = EquipmentManifest(nextId(state, "equipment"), selected.id, selected.formulation, selected.cost, selected.capabilities, selected.actions)
        warband.armory += manifest
        warband.selectionMemory.equipment[selected.id] = warband.selectionMemory.equipment.getOrDefault(selected.id, 0.0) + 1.0
        effects += CoreEffect(
            kind = EffectKind.REALIZE_EQUIPMENT,
            warbandId = warband.id,
            itemId = selected.id,
            equipmentManifest = manifest.copy(
                formulation = manifest.formulation.toList(),
                billOfMaterials = manifest.billOfMaterials.toMap(),
                supportedActions = manifest.supportedActions.toSet(),
            ),
        )
        events += event(state, "manufactured", manifest.id, selected.id)
    }

    private fun automaticDispatch(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        players: List<PlayerFact>,
        events: MutableList<CoreEvent>,
    ) {
        val interval = rules.dispatchIntervalTicks.coerceAtLeast(1L)
        val scheduledTick = state.tick / interval * interval
        val initialObservedDispatch = state.tick == 0L && state.lastCampaignTick == 0L && players.isNotEmpty()
        if (!initialObservedDispatch && scheduledTick <= state.lastCampaignTick) return
        state.lastCampaignTick = scheduledTick
        val targeted = state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }.mapTo(mutableSetOf()) { it.targetPlayerId }
        val ordered = state.warbands.values.sortedBy { it.id }
        if (ordered.isEmpty()) {
            state.dispatchCursor = 0
            return
        }
        val start = Math.floorMod(state.dispatchCursor, ordered.size)
        val work = minOf(rules.dispatchWorkBudget.coerceAtLeast(0), ordered.size)
        val scheduled = (0 until work).map { ordered[(start + it) % ordered.size] }
        state.dispatchCursor = (start + work) % ordered.size
        scheduled.forEach { warband ->
            if (warband.defeated || state.tick < warband.nextRaidTick) return@forEach
            if (state.campaigns.values.count { it.warbandId == warband.id && it.phase != CampaignPhase.RESOLVED } >= warband.activeCampaignLimit) return@forEach
            val reachable = players.asSequence()
                .filter { it.id !in targeted && it.physicallyAvailable }
                .filter { manhattan(warband.rally, it.position) <= rules.maximumDispatchDistanceChunks }
                .map { player ->
                    val relation = state.territoryRelations["${warband.id}|${player.id}"]
                    val protectedUntil = maxOf(
                        state.protectedPlayersUntilTick[player.id] ?: 0L,
                        relation?.protectedUntilTick ?: 0L,
                    )
                    player.copy(
                        eligible = player.eligible && player.gameModeEligible && protectedUntil <= state.tick,
                    )
                }.filter(PlayerFact::eligible)
                .toList()
            val preferredRelation = reachable.maxOfOrNull { player ->
                when (state.territoryRelations["${warband.id}|${player.id}"]?.status ?: TerritoryStatus.UNCONTACTED) {
                    TerritoryStatus.HOSTILE -> 2
                    TerritoryStatus.WARNED -> 1
                    TerritoryStatus.UNCONTACTED -> 0
                }
            } ?: return@forEach
            val candidates = reachable.filter { player ->
                when (state.territoryRelations["${warband.id}|${player.id}"]?.status ?: TerritoryStatus.UNCONTACTED) {
                    TerritoryStatus.HOSTILE -> 2
                    TerritoryStatus.WARNED -> 1
                    TerritoryStatus.UNCONTACTED -> 0
                } == preferredRelation
            }
            val assignment = chooseAssignment(state, warband, candidates) ?: return@forEach
            val player = candidates.first { it.id == assignment.playerId }
            if (dispatch(state, catalog, rules, warband.id, player.id, events, player.position, assignment.officerId)) targeted += player.id
        }
    }

    private fun dispatch(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        warbandId: String,
        playerId: String,
        events: MutableList<CoreEvent>,
        target: ChunkPosition? = null,
        officerId: String? = null,
        campaignId: String? = null,
    ): Boolean {
        if (state.campaigns.values.any { it.phase != CampaignPhase.RESOLVED && it.targetPlayerId == playerId }) return false
        val warband = state.warbands[warbandId] ?: return false
        val officer = officerId?.let(state.officers::get)?.takeIf {
            it.homeWarbandId == warband.id && it.availableAtTick <= state.tick && it.deployedCampaignId == null
        } ?: state.officers.values.filter {
            it.homeWarbandId == warband.id && it.availableAtTick <= state.tick && it.deployedCampaignId == null
        }.minByOrNull { it.id }
        if (officer == null) return false
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
        val resolvedCampaignId = campaignId ?: nextId(state, "campaign")
        require(resolvedCampaignId.isNotBlank() && resolvedCampaignId !in state.campaigns) { "duplicate campaign identity" }
        state.campaigns[resolvedCampaignId] = CampaignState(
            resolvedCampaignId, warband.id, officer.id, playerId, warband.rally,
            destination, members, lastCombatTick = state.tick, route = route,
            leaderMemberId = members.firstOrNull()?.id,
        )
        officer.lastTargetPlayerId = playerId
        officer.deployedCampaignId = resolvedCampaignId
        warband.nextRaidTick = state.tick + rules.raidCooldownTicks
        events += event(state, "dispatched", resolvedCampaignId, "target=$playerId threat=$committed")
        return true
    }

    private fun advanceCampaigns(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        players: List<PlayerFact>,
        elapsed: Long,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        val resolved = mutableListOf<CampaignState>()
        state.campaigns.values.filter { it.phase != CampaignPhase.RESOLVED }.forEach { campaign ->
            val warband = state.warbands[campaign.warbandId] ?: return@forEach
            val player = players.firstOrNull { it.id == campaign.targetPlayerId }
            if (player != null) campaign.target = player.position
            val relationProtection = state.territoryRelations["${warband.id}|${campaign.targetPlayerId}"]?.protectedUntilTick ?: 0L
            val playerProtected = maxOf(
                state.protectedPlayersUntilTick[campaign.targetPlayerId] ?: 0L, relationProtection,
            ) > state.tick
            if (player != null && (!player.eligible || !player.gameModeEligible || !player.physicallyAvailable || playerProtected) &&
                campaign.phase != CampaignPhase.RETURNING) {
                beginReturn(state, campaign.id, "target_ineligible", 0, events, effects)
            }
            if (campaign.phase == CampaignPhase.ACTIVE) {
                val liveThreat = campaign.members.sumOf { it.threat * it.healthFraction }
                val committed = campaign.members.sumOf(MemberManifest::threat)
                val conservation = state.officers[campaign.officerId]?.preferences?.get("conservation") ?: 0.5
                when (CampaignDecisions.activeDecision(campaign.members.size, liveThreat, committed, conservation, warband.aggression, state.tick, campaign.lastCombatTick, rules)) {
                    ActiveCampaignDecision.DEFEATED, ActiveCampaignDecision.MORALE_RETURN ->
                        beginDefeatReturn(state, campaign, rules, "morale", events, effects)
                    ActiveCampaignDecision.IDLE_RETURN -> beginReturn(
                        state, campaign.id, "idle", rules.idleReturnAggressionGrowth, events, effects,
                    )
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
                    campaign.resolvedAtTick = state.tick
                    warband.aggression = (warband.aggression + 1).coerceIn(rules.minimumAggression, rules.maximumAggression)
                    state.officers[campaign.officerId]?.let { officer ->
                        officer.defeats += 1
                        officer.availableAtTick = state.tick + rules.captainRecoveryTicks
                    }
                    campaign.returnReason = "supply_attrition"
                    releaseCampaignOwnership(state, campaign)
                    events += event(state, "campaign_lost_to_attrition", campaign.id)
                    break
                }
                if (campaign.phase == CampaignPhase.RETURNING && campaign.position == warband.rally) {
                    resolved += campaign
                    break
                }
                if (campaign.phase == CampaignPhase.OUTBOUND && manhattan(campaign.position, campaign.target) <= rules.materializeDistanceChunks) {
                    campaign.phase = CampaignPhase.READY_TO_MATERIALIZE
                    events += event(state, "materialization_site_requested", campaign.id)
                    break
                }
            }
        }
        resolved.forEach { campaign -> reconcile(state, campaign, rules, events) }
    }

    private fun observeCombat(
        state: CoreSnapshot,
        catalog: CoreCatalog,
        rules: CoreRules,
        observation: CombatObservation,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
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
        if (campaign.leaderMemberId != null && dead.any { it.id == campaign.leaderMemberId }) {
            selectCampaignSuccessor(
                state, catalog, rules, campaign.id,
                dead.mapTo(linkedSetOf(), MemberManifest::id), events, effects,
            )
            if (campaign.leaderMemberId == null) {
                state.officers[campaign.officerId]?.let { officer ->
                    officer.defeats += 1
                    officer.availableAtTick = Long.MAX_VALUE
                }
                beginReturn(state, campaign.id, "captain_killed", 1, events, effects)
            }
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

    private fun applyMaterializations(state: CoreSnapshot, results: List<MaterializationResult>, events: MutableList<CoreEvent>) {
        results.forEach resultLoop@{ result ->
            if (!acknowledgeEffect(state, result.effectId, EffectKind.MATERIALIZE, events, campaignId = result.campaignId)) return@resultLoop
            val campaign = state.campaigns[result.campaignId] ?: return@resultLoop
            if (campaign.phase != CampaignPhase.READY_TO_MATERIALIZE && campaign.phase != CampaignPhase.MATERIALIZING) return@resultLoop
            if (!result.success) {
                if (result.attemptedMemberIds.isNotEmpty()) {
                    require(result.attemptedMemberIds.all { id -> campaign.members.any { it.id == id } }) {
                        "unknown attempted campaign member"
                    }
                    val warband = state.warbands[campaign.warbandId] ?: return@resultLoop
                    val failed = campaign.members.filter { it.id in result.attemptedMemberIds }
                    failed.forEach { restoreUndeployedMember(warband, it) }
                    campaign.members.removeAll(failed.toSet())
                }
                campaign.phase = if (campaign.members.isEmpty()) CampaignPhase.RESOLVED else CampaignPhase.OUTBOUND
                campaign.physical = false
                campaign.physicalMemberIds.clear()
                if (campaign.phase == CampaignPhase.RESOLVED) {
                    campaign.resolvedAtTick = state.tick
                    campaign.returnReason = "materialization_exhausted"
                    releaseCampaignOwnership(state, campaign)
                }
                events += event(state, "materialization_failed", campaign.id)
                return@resultLoop
            }
            val warband = state.warbands[campaign.warbandId] ?: return@resultLoop
            // Empty preserves the pre-protocol meaning of `success=true`: every member spawned.
            val successfulIds = result.physicalMemberIds.ifEmpty { campaign.members.mapTo(linkedSetOf(), MemberManifest::id) }
            require(successfulIds.all { id -> campaign.members.any { it.id == id } }) { "unknown physical campaign member" }
            val failed = campaign.members.filter { it.id !in successfulIds }
            failed.forEach { restoreUndeployedMember(warband, it) }
            campaign.members.removeAll(failed.toSet())
            campaign.physicalMemberIds.clear()
            campaign.physicalMemberIds += successfulIds
            campaign.phase = CampaignPhase.ACTIVE
            campaign.physical = true
            events += event(state, if (result.success) "materialized" else "materialization_failed", campaign.id)
        }
    }

    private fun applySnapshots(state: CoreSnapshot, results: List<CampaignSnapshotResult>, events: MutableList<CoreEvent>) {
        results.forEach snapshotLoop@{ result ->
            if (result.effectId != null && !acknowledgeEffect(
                    state, result.effectId, EffectKind.CAPTURE_SNAPSHOTS, events, campaignId = result.campaignId,
                )) return@snapshotLoop
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
            campaign.physicalMemberIds.clear()
            if (!returning) campaign.phase = CampaignPhase.READY_TO_MATERIALIZE
            events += event(state, "snapshots_applied", campaign.id, "members=${campaign.members.size}")
            events += event(state, "dematerialized", campaign.id)
        }
    }

    private fun observeOutcome(
        state: CoreSnapshot,
        rules: CoreRules,
        observation: CampaignOutcomeObservation,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        val campaign = state.campaigns[observation.campaignId] ?: return
        val officer = state.officers[campaign.officerId]
        when (observation.outcome) {
            CampaignOutcomeKind.CAPTAIN_VICTORY -> {
                officer?.let { it.victories += 1; it.availableAtTick = state.tick + rules.captainSuccessRecoveryTicks }
                beginReturn(state, campaign.id, observation.reason, rules.victoryAggressionGrowth, events, effects)
            }
            CampaignOutcomeKind.SURVIVING_DEFEAT -> {
                officer?.let { it.defeats += 1; it.availableAtTick = state.tick + rules.captainRecoveryTicks }
                beginReturn(state, campaign.id, observation.reason, rules.defeatAggressionGrowth, events, effects)
            }
            CampaignOutcomeKind.CAPTAIN_KILLED -> {
                officer?.let { it.defeats += 1; it.availableAtTick = Long.MAX_VALUE }
                beginReturn(state, campaign.id, observation.reason, rules.defeatAggressionGrowth, events, effects)
            }
            CampaignOutcomeKind.ABORTED -> beginReturn(state, campaign.id, observation.reason, 0, events, effects)
            CampaignOutcomeKind.WARBAND_COLLAPSE -> {
                collapseWarband(state, campaign.warbandId, observation.reason, events)
            }
        }
        officer?.let(::updateOfficerStanding)
        events += event(state, "campaign_outcome_observed", campaign.id, observation.outcome.name.lowercase())
    }

    private fun beginDefeatReturn(
        state: CoreSnapshot,
        campaign: CampaignState,
        rules: CoreRules,
        reason: String,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        state.officers[campaign.officerId]?.let { officer ->
            officer.defeats += 1
            officer.availableAtTick = state.tick + rules.captainRecoveryTicks
            updateOfficerStanding(officer)
        }
        beginReturn(state, campaign.id, reason, 1, events, effects)
    }

    private fun applyPositions(state: CoreSnapshot, positions: List<PositionObservation>) {
        positions.forEach { observation -> state.campaigns[observation.campaignId]?.position = observation.position }
    }

    private fun updateOfficerStanding(officer: OfficerState) {
        val standing = (officer.victories - officer.defeats * 0.75).coerceAtLeast(0.0)
        officer.rank = (1.0 + kotlin.math.sqrt(standing)).toInt().coerceAtLeast(1)
    }

    private fun beginReturn(
        state: CoreSnapshot,
        campaignId: String,
        reason: String,
        aggressionDelta: Int,
        events: MutableList<CoreEvent>,
        effects: MutableList<CoreEffect>,
    ) {
        val campaign = state.campaigns[campaignId] ?: return
        if (campaign.phase == CampaignPhase.RETURNING || campaign.phase == CampaignPhase.RESOLVED) return
        val physical = campaign.physical || campaign.phase == CampaignPhase.ACTIVE || campaign.phase == CampaignPhase.MATERIALIZING
        campaign.phase = CampaignPhase.RETURNING
        campaign.returnReason = reason
        campaign.returnAggressionDelta = aggressionDelta
        events += event(state, "return_started", campaign.id, reason)
        if (physical) effects += CoreEffect(EffectKind.CAPTURE_SNAPSHOTS, campaign.warbandId, campaign.id, memberIds = campaign.members.map { it.id })
    }

    private fun dematerialize(state: CoreSnapshot, campaignId: String, events: MutableList<CoreEvent>) {
        val campaign = state.campaigns[campaignId] ?: return
        if (campaign.phase == CampaignPhase.RETURNING) {
            campaign.physical = false
            events += event(state, "dematerialized", campaign.id)
        }
    }

    private fun reconcile(state: CoreSnapshot, campaign: CampaignState, rules: CoreRules, events: MutableList<CoreEvent>) {
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
        campaign.resolvedAtTick = state.tick
        campaign.physicalMemberIds.clear()
        state.officers[campaign.officerId]?.let { officer ->
            officer.availableAtTick = maxOf(officer.availableAtTick, state.tick)
        }
        releaseCampaignOwnership(state, campaign)
        events += event(state, "returned", campaign.id, "threat=$returned")
    }

    private fun releaseCampaignOwnership(state: CoreSnapshot, campaign: CampaignState) {
        state.officers[campaign.officerId]?.takeIf { it.deployedCampaignId == campaign.id }?.deployedCampaignId = null
        val obsoleteKinds = setOf(
            EffectKind.MATERIALIZE, EffectKind.PROBE_ROUTE, EffectKind.CAPTURE_SNAPSHOTS,
            EffectKind.RESTORE_SNAPSHOTS, EffectKind.NAVIGATE, EffectKind.REMOVE_ENTITIES, EffectKind.DEMATERIALIZE,
        )
        state.pendingEffects.entries.removeIf { (_, effect) ->
            effect.campaignId == campaign.id && effect.kind in obsoleteKinds
        }
    }

    private fun pruneResolvedCampaigns(state: CoreSnapshot, rules: CoreRules) {
        val retention = rules.resolvedRetentionTicks.coerceAtLeast(0L)
        state.campaigns.entries.removeIf { (_, campaign) ->
            campaign.phase == CampaignPhase.RESOLVED && campaign.resolvedAtTick > 0L &&
                state.tick - campaign.resolvedAtTick >= retention
        }
    }

    private fun validateCatalog(catalog: CoreCatalog) {
        require(catalog.revision.isNotBlank())
        require(catalog.recruits.map { it.id }.distinct().size == catalog.recruits.size)
        require(catalog.recruits.all { it.id.isNotBlank() && it.baseThreat > 0.0 && it.capabilities.finite() })
        require(catalog.materials.all { it.id.isNotBlank() && it.tier > 0 && it.extractionCost >= 0.0 })
        require(catalog.equipment.all { it.id.isNotBlank() && it.cost.values.all { value -> value >= 0.0 } })
        require(catalog.equipmentPlatforms.map { it.id }.distinct().size == catalog.equipmentPlatforms.size)
        require(catalog.environmentSamples.all { it == it.bounded() })
        require(catalog.resources.map { it.itemId }.distinct().size == catalog.resources.size)
        require(catalog.resources.all {
            it.itemId.isNotBlank() && it.mass > 0.0 && it.environmentalAvailability.isFinite() &&
                it.environmentalAvailability >= 0.0 && it.maximumStackSize > 0 && it.unitsPerItem.finite() &&
                listOf(it.unitsPerItem.sustenance, it.unitsPerItem.munitions, it.unitsPerItem.maintenance, it.unitsPerItem.recovery).all { value -> value >= 0.0 }
        })
        require(catalog.rewards.map { it.itemId }.distinct().size == catalog.rewards.size)
        require(catalog.rewards.all {
            it.itemId.isNotBlank() && it.value.isFinite() && it.value > 0.0 && it.maximumStackSize > 0
        })
    }

    private fun segmentTravelTicks(campaign: CampaignState, rules: CoreRules): Long =
        (rules.travelTicksPerChunk * (2.0 - campaign.supplySatisfaction.coerceIn(0.0, 1.0))).toLong().coerceAtLeast(1L)

    private fun applySegmentLogistics(
        state: CoreSnapshot,
        campaign: CampaignState,
        warband: WarbandState,
        catalog: CoreCatalog,
        rules: CoreRules,
        events: MutableList<CoreEvent>,
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
        state: CoreSnapshot,
        warband: WarbandState,
        catalog: CoreCatalog,
        environment: EnvironmentTraits,
        destination: MutableMap<String, Int>,
        events: MutableList<CoreEvent>,
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
        catalog: CoreCatalog,
        rules: CoreRules,
        routeChunks: Int,
        events: MutableList<CoreEvent>,
        state: CoreSnapshot,
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

    private fun cacheMember(state: CoreSnapshot, campaign: CampaignState, member: MemberManifest) {
        if (member.cargo.isEmpty() && member.equipment == null) return
        campaign.lostCaches += LostCache(
            nextId(state, "cache"), campaign.position, member.cargo.toMutableMap(),
            member.equipment?.takeIf { it.durabilityFraction > EPSILON }?.let { mutableListOf(it) } ?: mutableListOf(),
        )
        member.cargo.clear()
        member.equipment = null
    }

    private fun planRoute(state: CoreSnapshot, warband: WarbandState, target: ChunkPosition, rules: CoreRules): List<ChunkPosition> {
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

    private fun normalizedAggression(warband: WarbandState, rules: CoreRules): Double =
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

    private fun nextId(state: CoreSnapshot, kind: String): String {
        val sequence = state.sequence++
        var most = deterministicTie("warband-core", kind, sequence)
        var least = deterministicTie(kind, "canonical-id", sequence xor -7046029254386353131L)
        most = (most and -0xF001L) or 0x5000L
        least = (least and 0x3fffffffffffffffL) or Long.MIN_VALUE
        return UUID(most, least).toString()
    }

    private fun nextEffectId(state: CoreSnapshot): String {
        val sequence = state.effectSequence++
        var most = deterministicTie("warband-core-effect", "effect", sequence)
        var least = deterministicTie("effect", "durable-outbox", sequence xor -7046029254386353131L)
        most = (most and -0xF001L) or 0x5000L
        least = (least and 0x3fffffffffffffffL) or Long.MIN_VALUE
        return UUID(most, least).toString()
    }

    private fun event(state: CoreSnapshot, type: String, subject: String, detail: String = "") =
        CoreEvent(state.tick, type, subject, detail)

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
