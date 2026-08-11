package com.gerald.pillagercampaigns.data

import com.gerald.warband.core.CoreSnapshot
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class PillagerWorldData : SavedData() {
    /** The sole persisted strategic state. */
    var coreState: CoreSnapshot = CoreSnapshot()
    var coreCatalogRevision: String = UNRESOLVED_CATALOG_REVISION
    var minecraftSidecar: MinecraftSidecar = MinecraftSidecar()

    /*
     * Minecraft-native projections rebuilt by WarbandCoreAdapter. They carry
     * entity-facing metadata only and are never serialized as strategic state.
     */
    val factions: MutableMap<UUID, PillagerFaction> = linkedMapOf()
    val warbands: MutableMap<UUID, PillagerWarband> = linkedMapOf()
    val officers: MutableMap<UUID, PillagerOfficer> = linkedMapOf()
    val campaigns: MutableMap<UUID, PillagerCampaign> = linkedMapOf()
    val lastDiscoveryTick: Long get() = coreState.lastDiscoveryTick
    val lastCampaignTick: Long get() = coreState.lastCampaignTick
    val coreSequence: Long get() = coreState.sequence

    override fun save(tag: CompoundTag): CompoundTag {
        return WarbandCorePersistence.save(
            PersistedWarbandCore(coreState, coreCatalogRevision, minecraftSidecar),
            tag,
        )
    }

    fun markChanged() {
        setDirty()
    }

    fun isPlayerProtected(playerId: UUID, now: Long): Boolean {
        return now <= (coreState.protectedPlayersUntilTick[playerId.toString()] ?: Long.MIN_VALUE)
    }

    companion object {
        private const val KEY = "pillagercampaigns_world"
        const val UNRESOLVED_CATALOG_REVISION = "unresolved"

        fun get(server: MinecraftServer): PillagerWorldData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::PillagerWorldData, KEY)

        fun load(tag: CompoundTag): PillagerWorldData {
            val persisted = WarbandCorePersistence.load(tag)
            return PillagerWorldData().also { data ->
                data.coreState = persisted.snapshot
                data.coreCatalogRevision = persisted.catalogRevision
                data.minecraftSidecar = persisted.sidecar
            }
        }
    }
}
