package com.gerald.warband.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

typealias WarbandSnapshot = CoreSnapshot
typealias WarbandFrame = CoreFrame
typealias WarbandEffect = CoreEffect
typealias WarbandEvent = CoreEvent

data class WarbandTransition(
    val events: List<WarbandEvent>,
    /** Complete durable outbox, including effects emitted by earlier frames. */
    val effects: List<WarbandEffect>,
)

/** The only state-owning gameplay entry point exposed to adapters and runners. */
class WarbandEngine private constructor(
    private val state: CoreSnapshot,
    private val runtimeSpec: WarbandRuntimeSpec,
    private val catalog: CoreCatalog,
) {
    fun transition(frame: WarbandFrame): WarbandTransition {
        val result = WarbandCore.transition(state, frame, catalog, runtimeSpec.rules)
        return WarbandTransition(result.events.toList(), result.effects.map(::copyEffect))
    }

    /** Returns a serialization round-trip copy; callers never receive engine-owned objects. */
    fun snapshot(): WarbandSnapshot = deepCopy(state)

    fun runtimeSpecRevision(): String = runtimeSpec.revision

    companion object {
        private val SNAPSHOT_JSON = Json { encodeDefaults = true }

        fun create(runtimeSpec: WarbandRuntimeSpec): WarbandEngine =
            restore(CoreSnapshot(), runtimeSpec)

        fun restore(snapshot: WarbandSnapshot, runtimeSpec: WarbandRuntimeSpec): WarbandEngine {
            runtimeSpec.requireValidRevision()
            val ownedSpec = deepCopy(runtimeSpec)
            ownedSpec.requireValidRevision()
            val catalog = ownedSpec.toCoreCatalog()
            val state = deepCopy(snapshot)
            state.campaigns.values.filter { it.phase == CampaignPhase.RESOLVED && it.resolvedAtTick <= 0L }
                .forEach { it.resolvedAtTick = state.tick }
            WarbandCore.validate(state, catalog, ownedSpec.rules)
            return WarbandEngine(state, ownedSpec, catalog)
        }

        private fun deepCopy(snapshot: CoreSnapshot): CoreSnapshot =
            SNAPSHOT_JSON.decodeFromString(SNAPSHOT_JSON.encodeToString(snapshot))

        private fun deepCopy(runtimeSpec: WarbandRuntimeSpec): WarbandRuntimeSpec =
            SNAPSHOT_JSON.decodeFromString(SNAPSHOT_JSON.encodeToString(runtimeSpec))

        private fun copyEffect(effect: CoreEffect): CoreEffect = effect.copy(
            memberIds = effect.memberIds.toList(),
            equipmentManifest = effect.equipmentManifest?.copy(
                formulation = effect.equipmentManifest.formulation.toList(),
                billOfMaterials = effect.equipmentManifest.billOfMaterials.toMap(),
                supportedActions = effect.equipmentManifest.supportedActions.toSet(),
            ),
            memberManifest = effect.memberManifest?.copy(
                equipment = effect.memberManifest.equipment?.copy(
                    formulation = effect.memberManifest.equipment!!.formulation.toList(),
                    billOfMaterials = effect.memberManifest.equipment!!.billOfMaterials.toMap(),
                    supportedActions = effect.memberManifest.equipment!!.supportedActions.toSet(),
                ),
                cargo = effect.memberManifest.cargo.toMutableMap(),
            ),
            memberManifests = effect.memberManifests.map(::copyMember),
            memberPlacements = effect.memberPlacements.toList(),
        )

        private fun copyMember(member: MemberManifest): MemberManifest = member.copy(
            equipment = member.equipment?.copy(
                formulation = member.equipment!!.formulation.toList(),
                billOfMaterials = member.equipment!!.billOfMaterials.toMap(),
                supportedActions = member.equipment!!.supportedActions.toSet(),
            ),
            cargo = member.cargo.toMutableMap(),
        )
    }
}

private fun WarbandRuntimeSpec.toCoreCatalog(): CoreCatalog = CoreCatalog(
    revision = revision,
    recruits = recruits,
    materials = materials,
    equipment = emptyList(),
    environmentSamples = environmentModel.samples,
    resources = resources,
    rewards = rewards,
    equipmentPlatforms = equipmentPlatforms,
)
