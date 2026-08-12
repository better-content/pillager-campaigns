package com.gerald.warband.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class WarbandEngineTest {
    private fun spec(): WarbandRuntimeSpec = WarbandRuntimeSpec.create(
        rules = CoreRules(),
        recruits = listOf(
            RecruitDefinition("z-recruit", 6.0, CapabilityVector(damage = 1.0), supportedEquipmentActions = setOf("melee")),
            RecruitDefinition("a-recruit", 5.0, CapabilityVector(mobility = 1.0), supportedEquipmentActions = setOf("ranged")),
        ),
        resources = listOf(
            ResourceDefinition("z-resource", ResourceVector(sustenance = 2.0)),
            ResourceDefinition("a-resource", ResourceVector(maintenance = 1.0)),
        ),
        equipmentPlatforms = listOf(EquipmentPlatformDefinition(
            "tool", "mainhand", setOf("melee"),
            listOf(EquipmentComponentDefinition("head", "head", setOf("iron"), 1.0)),
            baseCapabilities = CapabilityVector(damage = 1.0),
            aggregationParameters = mapOf("damageScale" to 1.0),
        )),
        materials = listOf(MaterialDefinition("iron", 2, 4.0, CapabilityVector(durability = 1.0))),
        environmentModel = EnvironmentModelDefinition(
            samples = listOf(EnvironmentTraits()),
            parameters = mapOf("baseline" to 0.5),
            traitWeights = mapOf("cold" to EnvironmentTraits(travelFriction = 0.2)),
        ),
        rewards = listOf(RewardDefinition("reward", 1.0)),
    )

    @Test fun `runtime spec canonicalizes every decision input and rejects drift`() {
        val spec = spec()
        spec.requireValidRevision()
        assertEquals(listOf("a-recruit", "z-recruit"), spec.recruits.map { it.id })
        assertEquals(listOf("a-resource", "z-resource"), spec.resources.map { it.itemId })
        assertEquals(spec.revision, spec.computedRevision())
        assertFailsWith<IllegalArgumentException> { spec.copy(revision = "tampered").requireValidRevision() }
        assertFailsWith<IllegalArgumentException> { spec.copy(schemaVersion = 99).requireValidRevision() }
        assertFailsWith<IllegalArgumentException> {
            WarbandRuntimeSpec.create(CoreRules(), emptyList()).requireValidRevision()
        }
        assertFailsWith<IllegalArgumentException> {
            WarbandRuntimeSpec.create(
                CoreRules(), listOf(RecruitDefinition("recruit", 1.0, CapabilityVector())),
                equipmentPlatforms = listOf(EquipmentPlatformDefinition(
                    "bad", components = listOf(EquipmentComponentDefinition("head", "head", setOf("missing"), 1.0)),
                )),
            ).requireValidRevision()
        }
    }

    @Test fun `engine owns state and returns copied snapshots and durable effects`() {
        val spec = spec()
        val member = MemberManifest("member", "a-recruit", 5.0, cargo = linkedMapOf("ration" to 1))
        val equipment = EquipmentManifest(
            "equipment", "tool", listOf("iron"), mapOf("iron" to 1.0),
            CapabilityVector(damage = 1.0), setOf("melee"),
        )
        member.equipment = equipment
        val campaign = CampaignState(
            "campaign", "warband", "captain", "player",
            ChunkPosition("overworld", 1, 1), ChunkPosition("overworld", 1, 1),
            members = mutableListOf(member),
            phase = CampaignPhase.ACTIVE,
            physical = true,
            physicalMemberIds = linkedSetOf(member.id),
        )
        val source = CoreSnapshot(
            tick = 10,
            factions = linkedMapOf("faction" to FactionState("faction", "Faction", 1)),
            warbands = linkedMapOf("warband" to WarbandState(
                "warband", "faction", ChunkPosition("overworld", 0, 0), 100.0, 20.0,
            )),
            officers = linkedMapOf("captain" to OfficerState("captain", "faction", "warband", deployedCampaignId = campaign.id)),
            campaigns = linkedMapOf(campaign.id to campaign),
            pendingEffects = linkedMapOf("durable" to CoreEffect(
                EffectKind.MATERIALIZE,
                campaignId = campaign.id,
                memberIds = listOf(member.id),
                effectId = "durable",
                equipmentManifest = equipment,
                memberManifest = member,
                memberManifests = listOf(member),
                memberPlacements = listOf(MemberPlacement(member.id, BlockPosition("overworld", 16, 64, 16))),
            )),
        )
        val engine = WarbandEngine.restore(source, spec)
        source.tick = 999
        assertEquals(10, engine.snapshot().tick)
        val exposed = engine.snapshot()
        exposed.tick = 500
        assertEquals(10, engine.snapshot().tick)
        assertEquals(spec.revision, engine.runtimeSpecRevision())

        val result = engine.transition(CoreFrame(0L, tactical = listOf(TacticalObservation(
            campaign.id,
            listOf(TacticalPosition("position", campaign.position, 1.0, 1.0, cover = 1.0)),
        ))))
        val effect = result.effects.single { it.kind == EffectKind.NAVIGATE }
        assertEquals(listOf(member.id), effect.memberIds)
        assertNotSame(engine.snapshot().pendingEffects.getValue(effect.effectId), effect)
        val repeated = engine.transition(CoreFrame(0L, tactical = listOf(TacticalObservation(
            campaign.id,
            listOf(TacticalPosition("position", campaign.position, 1.0, 1.0, cover = 1.0)),
        ))))
        assertEquals(1, repeated.effects.count { it.kind == EffectKind.NAVIGATE })
        val durable = result.effects.single { it.effectId == "durable" }
        durable.memberManifest!!.cargo["ration"] = 99
        assertEquals(1, engine.snapshot().pendingEffects.getValue("durable").memberManifest!!.cargo["ration"])
        assertTrue(WarbandEngine.create(spec).snapshot().warbands.isEmpty())
    }

    @Test fun `restore assigns deterministic retention timestamp to legacy resolved campaigns`() {
        val resolved = CampaignState(
            "resolved", "warband", "captain", "player",
            ChunkPosition("overworld", 0, 0), ChunkPosition("overworld", 0, 0),
            members = mutableListOf(),
            phase = CampaignPhase.RESOLVED,
        )
        val snapshot = CoreSnapshot(tick = 42, campaigns = linkedMapOf(resolved.id to resolved))
        assertEquals(42, WarbandEngine.restore(snapshot, spec()).snapshot().campaigns.getValue(resolved.id).resolvedAtTick)
    }
}
