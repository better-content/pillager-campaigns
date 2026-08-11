package com.gerald.pillagercampaigns.data

import com.gerald.warband.core.ChunkPosition
import com.gerald.warband.core.CoreSnapshot
import com.gerald.warband.core.FactionState
import com.gerald.warband.core.WarbandState
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
        val data = PillagerWorldData().apply {
            coreState = state
            coreCatalogRevision = "sha256:test-catalog"
            minecraftSidecar = MinecraftSidecar(
                entityIds = linkedMapOf(memberId to entityId),
                mobSnapshots = linkedMapOf(memberId to mob),
                itemSnapshots = linkedMapOf(memberId to mutableListOf(item)),
                cosmetics = linkedMapOf("core:officer:3" to CosmeticSidecar("Mara", "the Flint-Eyed", 19)),
                materializationAttempts = linkedMapOf(
                    "core:effect:14" to MaterializationAttemptSidecar(UUID.randomUUID(), 8_001L),
                ),
            )
        }

        val tag = data.save(CompoundTag())
        val loaded = PillagerWorldData.load(tag)

        assertEquals(WarbandCorePersistence.SCHEMA_VERSION, tag.getInt("schema"))
        assertEquals("warband-core", tag.getString("format"))
        assertTrue(tag.getByteArray("coreSnapshotJson").toString(Charsets.UTF_8).startsWith("{"))
        assertEquals(8_000L, loaded.coreState.tick)
        assertEquals(35.5, loaded.coreState.warbands.getValue("core:warband:2").reserveThreat)
        assertEquals("sha256:test-catalog", loaded.coreCatalogRevision)
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
        assertTrue(legacyFailure.message.orEmpty().contains("not migrated"))

        val malformed = WarbandCorePersistence.save(
            PersistedWarbandCore(CoreSnapshot(), "catalog", MinecraftSidecar()),
        ).also { it.putByteArray("coreSnapshotJson", byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFailsWith<UnsupportedWarbandCoreSchemaException> { PillagerWorldData.load(malformed) }
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
        val data = PillagerWorldData()

        data.coreState.protectedPlayersUntilTick[playerId.toString()] = 6_000L
        assertTrue(data.isPlayerProtected(playerId, 5_999L))
        assertFalse(data.isPlayerProtected(playerId, 6_001L))
        assertTrue(playerId.toString() in data.coreState.protectedPlayersUntilTick)
    }
}
