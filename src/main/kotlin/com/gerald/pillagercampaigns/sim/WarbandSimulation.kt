package com.gerald.pillagercampaigns.sim

import kotlin.math.max
import kotlin.math.min

/** Minecraft-free campaign model. Forge is an adapter which supplies catalogues and observations. */
data class CapabilityVector(
    val durability: Double = 0.0,
    val damage: Double = 0.0,
    val mobility: Double = 0.0,
    val range: Double = 0.0,
    val control: Double = 0.0,
) {
    operator fun plus(other: CapabilityVector) = CapabilityVector(
        durability + other.durability, damage + other.damage, mobility + other.mobility,
        range + other.range, control + other.control,
    )

    fun dot(weights: CapabilityVector): Double =
        durability * weights.durability + damage * weights.damage + mobility * weights.mobility +
            range * weights.range + control * weights.control
}

data class ResourceLedger(val amounts: MutableMap<String, Double> = linkedMapOf()) {
    fun canAfford(cost: Map<String, Double>) = cost.all { (id, amount) -> amount >= 0.0 && amounts.getOrDefault(id, 0.0) + EPSILON >= amount }

    fun consume(cost: Map<String, Double>): Boolean {
        if (!canAfford(cost)) return false
        cost.forEach { (id, amount) -> amounts[id] = (amounts.getOrDefault(id, 0.0) - amount).coerceAtLeast(0.0) }
        return true
    }

    fun add(values: Map<String, Double>) = values.forEach { (id, amount) ->
        require(amount >= 0.0) { "negative resource source $id=$amount" }
        amounts[id] = amounts.getOrDefault(id, 0.0) + amount
    }

    fun copyLedger() = ResourceLedger(amounts.toMutableMap())

    companion object { private const val EPSILON = 1.0e-9 }
}

data class RecruitCandidate(
    val id: String,
    val baseThreat: Double,
    val capabilities: CapabilityVector,
    val environmentalCost: CapabilityVector = CapabilityVector(),
)

data class EquipmentCandidate(
    val id: String,
    val formulation: List<String>,
    val capabilities: CapabilityVector,
    val cost: Map<String, Double>,
)

data class SimulationCatalog(
    val recruits: List<RecruitCandidate>,
    val equipment: List<EquipmentCandidate> = emptyList(),
)

data class MemberManifest(
    val id: String,
    val recruitId: String,
    val threat: Double,
    var healthFraction: Double = 1.0,
    var equipmentId: String? = null,
    var experience: Double = 0.0,
)

data class EquipmentManifest(
    val id: String,
    val candidateId: String,
    val formulation: List<String>,
    val billOfMaterials: Map<String, Double>,
)

enum class JourneyDirection { OUTBOUND, RETURNING }
enum class Materialization { ABSTRACT, PHYSICAL }
enum class TacticalIntent { APPROACH, PROBE, SPREAD, CONCENTRATE, COVER, PURSUE, REGROUP, WITHDRAW }

data class CampaignModel(
    val id: String,
    val targetId: String,
    val members: MutableList<MemberManifest>,
    var distanceFromHomeChunks: Int,
    var targetDistanceChunks: Int,
    var direction: JourneyDirection = JourneyDirection.OUTBOUND,
    var materialization: Materialization = Materialization.ABSTRACT,
    var aggression: Int,
    var lastCombatTick: Long,
    var morale: Double = 1.0,
)

data class WarbandModel(
    val id: String,
    var tick: Long = 0L,
    var aggression: Int = 6,
    val capacity: Double,
    var reserveThreat: Double,
    val environment: CapabilityVector,
    var preferences: CapabilityVector,
    val resources: ResourceLedger = ResourceLedger(),
    val armory: MutableList<EquipmentManifest> = mutableListOf(),
    val campaigns: MutableList<CampaignModel> = mutableListOf(),
    val empiricalThreat: MutableMap<String, Double> = linkedMapOf(),
    var sequence: Long = 0L,
)

data class CombatObservation(
    val campaignDamage: Double,
    val playerDamage: Double,
    val effectiveRange: Double,
    val routeConfidence: Double,
    val cohesion: Double,
    val casualties: Set<String> = emptySet(),
)

sealed interface SimulationCommand {
    data class Advance(val ticks: Long, val extraction: Map<String, Double> = emptyMap()) : SimulationCommand
    data class Manufacture(val count: Int = 1) : SimulationCommand
    data class Dispatch(val targetId: String) : SimulationCommand
    data class Materialize(val campaignId: String) : SimulationCommand
    data class CombatRound(val campaignId: String, val observation: CombatObservation) : SimulationCommand
    data class Return(val campaignId: String, val reason: String) : SimulationCommand
    data class Dematerialize(val campaignId: String) : SimulationCommand
}

sealed interface SimulationEvent {
    data class Extracted(val resources: Map<String, Double>) : SimulationEvent
    data class Manufactured(val equipmentId: String, val candidateId: String, val cost: Map<String, Double>) : SimulationEvent
    data class Dispatched(val campaignId: String, val memberIds: List<String>, val threat: Double) : SimulationEvent
    data class MaterializationChanged(val campaignId: String, val state: Materialization) : SimulationEvent
    data class IntentChosen(val campaignId: String, val intent: TacticalIntent, val score: Double) : SimulationEvent
    data class MemberLost(val campaignId: String, val memberId: String, val equipmentId: String?) : SimulationEvent
    data class LearningUpdated(val preferences: CapabilityVector) : SimulationEvent
    data class ReturnStarted(val campaignId: String, val reason: String) : SimulationEvent
    data class Returned(val campaignId: String, val threat: Double, val equipment: Int) : SimulationEvent
}

data class SimulationResult(val state: WarbandModel, val events: List<SimulationEvent>)

object WarbandSimulation {
    private const val IDLE_RETURN_TICKS = 12_000L
    private const val TRAVEL_TICKS_PER_CHUNK = 120L

    fun step(state: WarbandModel, catalog: SimulationCatalog, command: SimulationCommand): SimulationResult {
        val events = mutableListOf<SimulationEvent>()
        when (command) {
            is SimulationCommand.Advance -> advance(state, command, events)
            is SimulationCommand.Manufacture -> repeat(command.count.coerceAtLeast(0)) { manufacture(state, catalog, events) }
            is SimulationCommand.Dispatch -> dispatch(state, catalog, command.targetId, events)
            is SimulationCommand.Materialize -> campaign(state, command.campaignId)?.let {
                it.materialization = Materialization.PHYSICAL
                events += SimulationEvent.MaterializationChanged(it.id, it.materialization)
            }
            is SimulationCommand.CombatRound -> combat(state, catalog, command, events)
            is SimulationCommand.Return -> campaign(state, command.campaignId)?.let { beginReturn(it, command.reason, events) }
            is SimulationCommand.Dematerialize -> campaign(state, command.campaignId)?.let {
                it.materialization = Materialization.ABSTRACT
                events += SimulationEvent.MaterializationChanged(it.id, it.materialization)
            }
        }
        validate(state, catalog)
        return SimulationResult(state, events)
    }

    fun minimumDeployableThreat(state: WarbandModel, catalog: SimulationCatalog): Double? =
        catalog.recruits.minOfOrNull { observedThreat(state, it) }

    fun chooseRecruit(state: WarbandModel, catalog: SimulationCatalog, remainingThreat: Double, squad: List<MemberManifest>): RecruitCandidate? {
        val current = squad.mapNotNull { member -> catalog.recruits.firstOrNull { it.id == member.recruitId } }
            .fold(CapabilityVector(), { total, candidate -> total + candidate.capabilities })
        return catalog.recruits.asSequence()
            .filter { observedThreat(state, it) <= remainingThreat + 1.0e-9 }
            .maxWithOrNull(compareBy<RecruitCandidate> {
                recruitScore(state, it, current, squad.size)
            }.thenByDescending { deterministicTie(state.id, it.id, state.sequence) })
    }

    fun chooseIntent(campaign: CampaignModel, observation: CombatObservation): Pair<TacticalIntent, Double> {
        val casualtyRatio = observation.casualties.size.toDouble() / campaign.members.size.coerceAtLeast(1)
        val scores = linkedMapOf(
            TacticalIntent.APPROACH to observation.routeConfidence + campaign.aggression / 18.0,
            TacticalIntent.PROBE to 1.0 - observation.routeConfidence,
            TacticalIntent.SPREAD to observation.playerDamage / (observation.campaignDamage + observation.playerDamage + 1.0),
            TacticalIntent.CONCENTRATE to observation.campaignDamage / (observation.playerDamage + 1.0),
            TacticalIntent.COVER to observation.effectiveRange / 16.0,
            TacticalIntent.PURSUE to campaign.aggression / 12.0 + observation.campaignDamage / 10.0,
            TacticalIntent.REGROUP to (1.0 - observation.cohesion) + casualtyRatio,
            TacticalIntent.WITHDRAW to (1.0 - campaign.morale) + casualtyRatio * 2.0,
        )
        return scores.maxWithOrNull(compareBy<Map.Entry<TacticalIntent, Double>> { it.value }.thenByDescending { it.key.ordinal })!!
            .let { it.key to it.value }
    }

    fun validate(state: WarbandModel, catalog: SimulationCatalog) {
        require(state.reserveThreat >= -1.0e-9) { "negative reserve threat" }
        require(state.resources.amounts.values.all { it >= -1.0e-9 && it.isFinite() }) { "invalid resource ledger" }
        require(state.campaigns.map { it.targetId }.distinct().size == state.campaigns.size) { "multiple campaigns target one player" }
        val memberIds = state.campaigns.flatMap { it.members }.map { it.id }
        require(memberIds.distinct().size == memberIds.size) { "duplicate deployed member identity" }
        val equipmentIds = state.armory.map { it.id } + state.campaigns.flatMap { it.members }.mapNotNull { it.equipmentId }
        require(equipmentIds.distinct().size == equipmentIds.size) { "duplicate equipment identity" }
        state.campaigns.flatMap { it.members }.forEach {
            require(it.recruitId in catalog.recruits.map(RecruitCandidate::id)) { "unknown recruit ${it.recruitId}" }
            require(it.healthFraction in 0.0..1.0 && it.threat > 0.0) { "invalid member ${it.id}" }
        }
    }

    private fun advance(state: WarbandModel, command: SimulationCommand.Advance, events: MutableList<SimulationEvent>) {
        require(command.ticks >= 0L)
        state.tick += command.ticks
        if (command.extraction.isNotEmpty()) {
            val produced = command.extraction.mapValues { (_, rate) -> max(0.0, rate) * command.ticks / 20.0 }
            state.resources.add(produced)
            events += SimulationEvent.Extracted(produced)
        }
        val returned = mutableListOf<CampaignModel>()
        state.campaigns.forEach { active ->
            if (active.materialization == Materialization.PHYSICAL && state.tick - active.lastCombatTick >= IDLE_RETURN_TICKS && active.direction != JourneyDirection.RETURNING) {
                beginReturn(active, "idle", events)
            }
            if (active.materialization == Materialization.ABSTRACT) {
                val chunks = (command.ticks / TRAVEL_TICKS_PER_CHUNK).toInt()
                if (active.direction == JourneyDirection.OUTBOUND) active.targetDistanceChunks = (active.targetDistanceChunks - chunks).coerceAtLeast(0)
                else active.distanceFromHomeChunks = (active.distanceFromHomeChunks - chunks).coerceAtLeast(0)
                if (active.direction == JourneyDirection.RETURNING && active.distanceFromHomeChunks == 0) returned += active
            }
        }
        returned.forEach { returning ->
            val equipment = returning.members.mapNotNull { member -> member.equipmentId?.let { id -> id to member } }
            returning.members.forEach { state.reserveThreat += it.threat * it.healthFraction.coerceIn(0.0, 1.0) }
            equipment.forEach { (id, member) -> state.armory += EquipmentManifest(id, member.equipmentId ?: id, emptyList(), emptyMap()) }
            state.aggression = (state.aggression + 1).coerceAtMost(18)
            state.campaigns.remove(returning)
            events += SimulationEvent.Returned(returning.id, returning.members.sumOf { it.threat * it.healthFraction }, equipment.size)
        }
    }

    private fun manufacture(state: WarbandModel, catalog: SimulationCatalog, events: MutableList<SimulationEvent>) {
        val selected = catalog.equipment.asSequence().filter { state.resources.canAfford(it.cost) }.maxWithOrNull(
            compareBy<EquipmentCandidate> { it.capabilities.dot(state.preferences) / (it.cost.values.sum() + 1.0) }
                .thenByDescending { deterministicTie(state.id, it.id, state.sequence) },
        ) ?: return
        if (!state.resources.consume(selected.cost)) return
        val id = "${state.id}:equipment:${state.sequence++}"
        state.armory += EquipmentManifest(id, selected.id, selected.formulation, selected.cost.toMap())
        events += SimulationEvent.Manufactured(id, selected.id, selected.cost)
    }

    private fun dispatch(state: WarbandModel, catalog: SimulationCatalog, targetId: String, events: MutableList<SimulationEvent>) {
        if (state.campaigns.any { it.targetId == targetId }) return
        val minimum = minimumDeployableThreat(state, catalog) ?: return
        val budget = min(state.reserveThreat, max(state.aggression.toDouble(), minimum))
        if (state.reserveThreat + 1.0e-9 < minimum) return
        val members = mutableListOf<MemberManifest>()
        var remaining = budget
        while (members.size < 24) {
            val selected = chooseRecruit(state, catalog, remaining, members) ?: break
            val threat = observedThreat(state, selected)
            val member = MemberManifest("${state.id}:member:${state.sequence++}", selected.id, threat)
            state.armory.removeFirstOrNull()?.let { equipment -> member.equipmentId = equipment.id }
            members += member
            remaining -= threat
        }
        if (members.isEmpty()) return
        val committed = members.sumOf(MemberManifest::threat)
        state.reserveThreat -= committed
        val id = "${state.id}:campaign:${state.sequence++}"
        state.campaigns += CampaignModel(id, targetId, members, 8, 8, aggression = state.aggression, lastCombatTick = state.tick)
        events += SimulationEvent.Dispatched(id, members.map(MemberManifest::id), committed)
    }

    private fun combat(state: WarbandModel, catalog: SimulationCatalog, command: SimulationCommand.CombatRound, events: MutableList<SimulationEvent>) {
        val active = campaign(state, command.campaignId) ?: return
        active.lastCombatTick = state.tick
        val observation = command.observation
        val dead = active.members.filter { it.id in observation.casualties }
        dead.forEach {
            active.members.remove(it)
            events += SimulationEvent.MemberLost(active.id, it.id, it.equipmentId)
        }
        val damageRatio = observation.playerDamage / (observation.playerDamage + observation.campaignDamage + 1.0)
        active.members.forEach { it.healthFraction = (it.healthFraction - damageRatio / active.members.size.coerceAtLeast(1)).coerceIn(0.0, 1.0) }
        active.morale = (observation.cohesion * observation.routeConfidence * (1.0 - damageRatio) * (active.members.size + 1.0) / (active.members.size + dead.size + 1.0)).coerceIn(0.0, 1.0)
        val intent = chooseIntent(active, observation)
        events += SimulationEvent.IntentChosen(active.id, intent.first, intent.second)
        val contribution = CapabilityVector(
            durability = if (observation.playerDamage > observation.campaignDamage) 0.15 else -0.04,
            damage = if (observation.campaignDamage <= observation.playerDamage) 0.10 else -0.03,
            mobility = if (observation.routeConfidence < 0.5) 0.18 else -0.02,
            range = if (observation.effectiveRange > 8.0) 0.14 else -0.02,
            control = if (observation.cohesion < 0.5) 0.12 else -0.02,
        )
        state.preferences += contribution
        active.members.groupBy { it.recruitId }.forEach { (id, members) ->
            val current = state.empiricalThreat[id] ?: catalog.recruits.firstOrNull { it.id == id }?.baseThreat ?: return@forEach
            // Threat is the cost of risking this body, not a reward for underperforming.
            // Taking substantially more damage than it deals therefore raises its
            // observed cost and makes functional alternatives more attractive.
            val observed = current * (0.5 + observation.playerDamage / (observation.campaignDamage + 1.0))
            state.empiricalThreat[id] = current + 0.10 * (observed.coerceAtLeast(1.0) - current)
        }
        events += SimulationEvent.LearningUpdated(state.preferences)
        if (intent.first == TacticalIntent.WITHDRAW || active.members.isEmpty()) beginReturn(active, "morale", events)
    }

    private fun beginReturn(campaign: CampaignModel, reason: String, events: MutableList<SimulationEvent>) {
        if (campaign.direction == JourneyDirection.RETURNING) return
        campaign.direction = JourneyDirection.RETURNING
        events += SimulationEvent.ReturnStarted(campaign.id, reason)
    }

    private fun recruitScore(state: WarbandModel, candidate: RecruitCandidate, current: CapabilityVector, squadSize: Int): Double {
        val environmentalPenalty = candidate.environmentalCost.dot(state.environment)
        val marginal = CapabilityVector(
            candidate.capabilities.durability / (1.0 + current.durability),
            candidate.capabilities.damage / (1.0 + current.damage),
            candidate.capabilities.mobility / (1.0 + current.mobility),
            candidate.capabilities.range / (1.0 + current.range),
            candidate.capabilities.control / (1.0 + current.control),
        )
        return marginal.dot(state.preferences) / observedThreat(state, candidate) - environmentalPenalty - squadSize * 1.0e-8
    }

    private fun observedThreat(state: WarbandModel, candidate: RecruitCandidate) =
        state.empiricalThreat[candidate.id]?.coerceAtLeast(1.0) ?: candidate.baseThreat.coerceAtLeast(1.0)

    private fun campaign(state: WarbandModel, id: String) = state.campaigns.firstOrNull { it.id == id }

    private fun deterministicTie(warbandId: String, candidateId: String, sequence: Long): Long {
        var value = 0xcbf29ce484222325UL.toLong()
        "$warbandId|$candidateId|$sequence".forEach { char -> value = (value xor char.code.toLong()) * 0x100000001b3L }
        return value
    }
}
