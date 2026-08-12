package com.gerald.pillagercampaigns.data

import com.gerald.warband.core.ChunkPosition
import com.gerald.warband.core.CoreSnapshot
import com.gerald.warband.core.FactionState
import com.gerald.warband.core.WarbandState
import com.gerald.warband.core.CoreRules
import com.gerald.warband.core.WarbandRuntimeSpec
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PillagerWorldDataRepairTest {
    @Test
    fun `canonical core snapshot and sidecar round trip through world NBT`() {
        val memberId = "core:member:12"
        val entityId = UUID.randomUUID()
        val mob = CompoundTag().also {
            it.putString("id", "minecraft:pillager")
            it.putFloat("Health", 17.5f)
        }
        val item = CompoundTag().also {
            it.putString("id", "tconstruct:crossbow")
            it.putInt("Damage", 31)
        }
        val state = CoreSnapshot(
            tick = 8_000L,
            sequence = 13L,
            factions = linkedMapOf("core:faction:1" to FactionState("core:faction:1", "Ash Banner", 7)),
            warbands = linkedMapOf(
                "core:warband:2" to WarbandState(
                    "core:warband:2", "core:faction:1", ChunkPosition("minecraft:overworld", 4, -2),
                    capacity = 156.0, reserveThreat = 35.5,
                ),
            ),
            protectedPlayersUntilTick = linkedMapOf(UUID.randomUUID().toString() to 9_000L),
        )
        val sidecar = MinecraftSidecar(
                entityIds = linkedMapOf(memberId to entityId),
                mobSnapshots = linkedMapOf(memberId to mob),
                itemSnapshots = linkedMapOf(memberId to mutableListOf(item)),
                cosmetics = linkedMapOf("core:officer:3" to CosmeticSidecar("Mara", "the Flint-Eyed", 19)),
                materializationAttempts = linkedMapOf(
                    "core:effect:14" to MaterializationAttemptSidecar(UUID.randomUUID(), 8_001L),
                ),
            )

        val tag = WarbandCorePersistence.save(PersistedWarbandCore(state, "warband-runtime-sha256:test", sidecar))
        val loaded = PillagerWorldData.load(tag)

        assertEquals(WarbandCorePersistence.SCHEMA_VERSION, tag.getInt("schema"))
        assertEquals("warband-core", tag.getString("format"))
        assertTrue(tag.getByteArray("coreSnapshotJson").toString(Charsets.UTF_8).startsWith("{"))
        assertEquals(8_000L, loaded.snapshot().tick)
        assertEquals(35.5, loaded.snapshot().warbands.getValue("core:warband:2").reserveThreat)
        assertEquals(entityId, loaded.minecraftSidecar.entityIds[memberId])
        assertEquals(mob, loaded.minecraftSidecar.mobSnapshots[memberId])
        assertEquals(item, loaded.minecraftSidecar.itemSnapshots.getValue(memberId).single())
        assertEquals("the Flint-Eyed", loaded.minecraftSidecar.cosmetics["core:officer:3"]?.title)
        assertNotSame(mob, loaded.minecraftSidecar.mobSnapshots[memberId])
        assertFalse(tag.contains("factions"))
        assertFalse(tag.contains("campaigns"))
    }

    @Test
    fun `legacy and malformed strategic saves are rejected rather than migrated`() {
        val legacy = CompoundTag().also {
            it.putInt("schema", 4)
            it.put("warbands", ListTag())
        }
        val legacyFailure = assertFailsWith<UnsupportedWarbandCoreSchemaException> {
            PillagerWorldData.load(legacy)
        }
        assertTrue(legacyFailure.message.orEmpty().contains("Older strategic saves"))

        val malformed = WarbandCorePersistence.save(
            PersistedWarbandCore(CoreSnapshot(), "catalog", MinecraftSidecar()),
        ).also { it.putByteArray("coreSnapshotJson", byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFailsWith<UnsupportedWarbandCoreSchemaException> { PillagerWorldData.load(malformed) }
    }

    @Test
    fun `schema five snapshot deterministically adopts first complete runtime spec`() {
        val tag = WarbandCorePersistence.save(
            PersistedWarbandCore(CoreSnapshot(), "warband-runtime-sha256:placeholder", MinecraftSidecar()),
        ).also {
            it.putInt("schema", 5)
            it.putString("catalogRevision", "pillager-campaigns-live-v1")
            it.remove("runtimeSpecRevision")
        }
        val data = PillagerWorldData.load(tag)
        assertEquals(PillagerWorldData.UNRESOLVED_RUNTIME_SPEC_REVISION, data.runtimeSpecRevision())
        val spec = WarbandRuntimeSpec.create(
            CoreRules(),
            listOf(com.gerald.warband.core.RecruitDefinition("test:recruit", 1.0, com.gerald.warband.core.CapabilityVector())),
        )
        data.attachRuntimeSpec(spec)
        assertEquals(spec.revision, data.runtimeSpecRevision())
    }

    @Test
    fun `sidecar rejects duplicate canonical identities`() {
        val duplicate = CompoundTag().also { sidecar ->
            sidecar.putInt("schema", 1)
            sidecar.put("entityIds", ListTag().also { entries ->
                repeat(2) {
                    entries.add(CompoundTag().also { entry ->
                        entry.putString("id", "core:member:1")
                        entry.putUUID("value", UUID.randomUUID())
                    })
                }
            })
        }
        val failure = assertFailsWith<IllegalArgumentException> { MinecraftSidecar.load(duplicate) }
        assertTrue(failure.message.orEmpty().contains("duplicate canonical ID"))
    }

    @Test
    fun `player protection is stored only in core state`() {
        val playerId = UUID.randomUUID()
        val state = CoreSnapshot(protectedPlayersUntilTick = linkedMapOf(playerId.toString() to 6_000L))
        val data = PillagerWorldData.load(WarbandCorePersistence.save(
            PersistedWarbandCore(state, "warband-runtime-sha256:test", MinecraftSidecar()),
        ))
        assertTrue(data.isPlayerProtected(playerId, 5_999L))
        assertFalse(data.isPlayerProtected(playerId, 6_001L))
        assertTrue(playerId.toString() in data.snapshot().protectedPlayersUntilTick)
    }
}
