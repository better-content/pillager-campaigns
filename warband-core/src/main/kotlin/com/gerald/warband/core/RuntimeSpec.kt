package com.gerald.warband.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A raw, registry-independent description of one equipment component slot. */
@Serializable
data class EquipmentComponentDefinition(
    val id: String,
    val statKind: String,
    val compatibleMaterialIds: Set<String>,
    val requiredUnits: Double,
    val capabilityScale: CapabilityVector = CapabilityVector(1.0, 1.0, 1.0, 1.0, 1.0),
)

/** A platform is inert content data. Core alone combines it with material supply. */
@Serializable
data class EquipmentPlatformDefinition(
    val id: String,
    val equipmentSlot: String? = null,
    val supportedActions: Set<String> = emptySet(),
    val components: List<EquipmentComponentDefinition>,
    val baseCapabilities: CapabilityVector = CapabilityVector(),
    val aggregationParameters: Map<String, Double> = emptyMap(),
)

@Serializable
data class EnvironmentModelDefinition(
    val samples: List<EnvironmentTraits> = emptyList(),
    val parameters: Map<String, Double> = emptyMap(),
    val traitWeights: Map<String, EnvironmentTraits> = emptyMap(),
    val sampleRadius: Int = 3,
    val sampleStrideChunks: Int = 2,
)

/**
 * Complete, versioned input to the engine. Its revision hashes every serialized
 * decision input except the revision field itself.
 */
@Serializable
data class WarbandRuntimeSpec(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: String,
    val rules: CoreRules,
    val recruits: List<RecruitDefinition>,
    val resources: List<ResourceDefinition> = emptyList(),
    val equipmentPlatforms: List<EquipmentPlatformDefinition> = emptyList(),
    val materials: List<MaterialDefinition> = emptyList(),
    val environmentModel: EnvironmentModelDefinition = EnvironmentModelDefinition(),
    val rewards: List<RewardDefinition> = emptyList(),
) {
    fun computedRevision(): String {
        val canonical = REVISION_JSON.encodeToString(copy(revision = ""))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "warband-runtime-sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun requireValidRevision() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "unsupported runtime specification schema $schemaVersion (expected $CURRENT_SCHEMA_VERSION)"
        }
        require(revision == computedRevision()) { "warband runtime specification revision mismatch" }
        require(recruits.isNotEmpty()) { "runtime specification must define at least one recruit" }
        require(recruits.map(RecruitDefinition::id).distinct().size == recruits.size) { "duplicate recruit identity" }
        require(materials.map(MaterialDefinition::id).distinct().size == materials.size) { "duplicate material identity" }
        require(equipmentPlatforms.map(EquipmentPlatformDefinition::id).distinct().size == equipmentPlatforms.size) {
            "duplicate equipment platform identity"
        }
        val materialIds = materials.mapTo(hashSetOf(), MaterialDefinition::id)
        equipmentPlatforms.forEach { platform ->
            require(platform.id.isNotBlank() && platform.components.isNotEmpty()) { "invalid equipment platform ${platform.id}" }
            platform.components.forEach { component ->
                require(component.id.isNotBlank() && component.statKind.isNotBlank() &&
                    component.requiredUnits.isFinite() && component.requiredUnits > 0.0)
                require(component.compatibleMaterialIds.isNotEmpty() && component.compatibleMaterialIds.all(materialIds::contains)) {
                    "equipment component ${component.id} references unavailable material"
                }
            }
        }
        require(rules.minimumAggression <= rules.initialAggression && rules.initialAggression <= rules.maximumAggression)
        require(rules.respawnProtectionTicks >= 0L && rules.deathProtectionTicks >= 0L && rules.resolvedRetentionTicks >= 0L)
        require(rules.dispatchIntervalTicks >= 0L && rules.dispatchWorkBudget >= 0 && rules.maximumDispatchDistanceChunks >= 0)
        require(rules.discoveryWorkBudget >= 0)
        require(rules.defaultActiveCampaignLimit > 0 && rules.maximumSquadMembers > 0 && rules.maximumArmoryItems >= 0)
        require(rules.travelTicksPerChunk > 0L && rules.raidCooldownTicks >= 0L &&
            rules.captainRecoveryTicks >= 0L && rules.captainSuccessRecoveryTicks >= 0L)
        require(rules.recruitBaseTicksPerThreat > 0.0 && rules.recruitHabitabilityPenaltyTicksPerThreat >= 0.0 &&
            rules.mobilizationBaseTicksPerThreat > 0.0 && rules.mobilizationFrictionTicksPerThreat >= 0.0 &&
            rules.extractionTicksMultiplier >= 0.0)
        require(rules.sustenancePerThreatChunk >= 0.0 && rules.munitionsPerRangedThreatChunk >= 0.0 &&
            rules.maintenancePerEquipmentChunk >= 0.0 && rules.deficitGraceChunks >= 0.0 &&
            rules.attritionPerDeficitChunk in 0.0..1.0 && rules.equipmentWearPerFrictionChunk in 0.0..1.0 &&
            rules.forageUnitsPerDeficitChunk >= 0.0 && rules.shortageRetreatBaseChunks >= 0.0 &&
            rules.shortageAggressionRunwayChunks >= 0.0)
        require(rules.discoveryIntervalTicks >= 0L && rules.discoveryGridSpacingChunks > 0 && rules.discoveryMinimumSpacingChunks >= 0 &&
            rules.discoveryGridJitterChunks >= 0 && rules.discoveryMinimumPlayerDistanceChunks >= 0 &&
            rules.discoveryMaximumDistanceChunks >= rules.discoveryMinimumPlayerDistanceChunks &&
            rules.discoveryChance in 0.0..1.0)
        require(environmentModel.sampleRadius >= 0 && environmentModel.sampleStrideChunks > 0)
        require(rules.territoryRadiusChunks >= 0 && rules.territoryWarningBandChunks in 0..rules.territoryRadiusChunks)
    }

    fun withComputedRevision(): WarbandRuntimeSpec = copy(revision = "").let { it.copy(revision = it.computedRevision()) }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        private val REVISION_JSON = Json {
            encodeDefaults = true
        }

        fun create(
            rules: CoreRules,
            recruits: List<RecruitDefinition>,
            resources: List<ResourceDefinition> = emptyList(),
            equipmentPlatforms: List<EquipmentPlatformDefinition> = emptyList(),
            materials: List<MaterialDefinition> = emptyList(),
            environmentModel: EnvironmentModelDefinition = EnvironmentModelDefinition(),
            rewards: List<RewardDefinition> = emptyList(),
        ): WarbandRuntimeSpec = WarbandRuntimeSpec(
            revision = "",
            rules = rules,
            recruits = recruits.sortedBy(RecruitDefinition::id).map {
                it.copy(supportedEquipmentActions = it.supportedEquipmentActions.toSortedSet())
            },
            resources = resources.sortedBy(ResourceDefinition::itemId),
            equipmentPlatforms = equipmentPlatforms.sortedBy(EquipmentPlatformDefinition::id).map { platform ->
                platform.copy(
                    supportedActions = platform.supportedActions.toSortedSet(),
                    components = platform.components.map { component ->
                        component.copy(compatibleMaterialIds = component.compatibleMaterialIds.toSortedSet())
                    },
                    aggregationParameters = platform.aggregationParameters.toSortedMap(),
                )
            },
            materials = materials.sortedBy(MaterialDefinition::id),
            environmentModel = environmentModel.copy(
                parameters = environmentModel.parameters.toSortedMap(),
                traitWeights = environmentModel.traitWeights.toSortedMap(),
            ),
            rewards = rewards.sortedBy(RewardDefinition::itemId),
        ).withComputedRevision()
    }
}

typealias WarbandRules = CoreRules
