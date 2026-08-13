package com.bettercontent.pillagercampaigns.data

import com.gerald.warband.core.WarbandEngine
import com.gerald.warband.core.WarbandRuntimeSpec
import com.gerald.warband.core.WarbandSnapshot
import com.gerald.warband.core.EnvironmentModelDefinition
import com.gerald.warband.core.WarbandRules
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class PillagerWorldData : SavedData() {
    private var restoredSnapshot: WarbandSnapshot = WarbandSnapshot()
    private var runtimeSpecRevision: String = UNRESOLVED_RUNTIME_SPEC_REVISION
    private var engine: WarbandEngine? = null
    private var attachedRuntimeSpec: WarbandRuntimeSpec? = null
    var minecraftSidecar: MinecraftSidecar = MinecraftSidecar()

    /*
     * Minecraft-native projections rebuilt by WarbandCoreAdapter. They carry
     * entity-facing metadata only and are never serialized as strategic state.
     */
    val factions: MutableMap<UUID, PillagerFaction> = linkedMapOf()
    val warbands: MutableMap<UUID, PillagerWarband> = linkedMapOf()
    val officers: MutableMap<UUID, PillagerOfficer> = linkedMapOf()
    val campaigns: MutableMap<UUID, PillagerCampaign> = linkedMapOf()
    val lastDiscoveryTick: Long get() = snapshot().lastDiscoveryTick
    val lastCampaignTick: Long get() = snapshot().lastCampaignTick
    val coreSequence: Long get() = snapshot().sequence

    /** Attaches the exact decision specification before gameplay can transition. */
    fun attachRuntimeSpec(runtimeSpec: WarbandRuntimeSpec): WarbandEngine {
        runtimeSpec.requireValidRevision()
        if (runtimeSpecRevision != UNRESOLVED_RUNTIME_SPEC_REVISION) {
            require(runtimeSpecRevision == runtimeSpec.revision) {
                "saved runtime-spec revision $runtimeSpecRevision does not match ${runtimeSpec.revision}"
            }
        }
        attachedRuntimeSpec = Json.decodeFromString(Json.encodeToString(runtimeSpec))
        engine?.let {
            require(it.runtimeSpecRevision() == runtimeSpec.revision)
            return it
        }
        runtimeSpecRevision = runtimeSpec.revision
        return WarbandEngine.restore(restoredSnapshot, runtimeSpec).also { engine = it }
    }

    fun requireEngine(): WarbandEngine = checkNotNull(engine) { "Warband runtime specification has not been attached" }

    fun runtimeSpecRevision(): String = runtimeSpecRevision

    fun environmentModel(): EnvironmentModelDefinition =
        checkNotNull(attachedRuntimeSpec) { "Warband runtime specification has not been attached" }.environmentModel

    fun runtimeRules(): WarbandRules =
        checkNotNull(attachedRuntimeSpec) { "Warband runtime specification has not been attached" }.rules

    fun snapshot(): WarbandSnapshot = engine?.snapshot() ?: restoredSnapshot.deepCopy()

    override fun save(tag: CompoundTag): CompoundTag {
        check(runtimeSpecRevision != UNRESOLVED_RUNTIME_SPEC_REVISION) {
            "cannot save Warband state before a complete runtime specification is attached"
        }
        return WarbandCorePersistence.save(
            PersistedWarbandCore(snapshot(), runtimeSpecRevision, minecraftSidecar),
            tag,
        )
    }

    fun markChanged() {
        setDirty()
    }

    fun isPlayerProtected(playerId: UUID, now: Long): Boolean {
        return now <= (snapshot().protectedPlayersUntilTick[playerId.toString()] ?: Long.MIN_VALUE)
    }

    companion object {
        private const val KEY = "pillager_campaigns_world"
        const val UNRESOLVED_RUNTIME_SPEC_REVISION = "unresolved"

        fun get(server: MinecraftServer): PillagerWorldData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::PillagerWorldData, KEY)

        fun load(tag: CompoundTag): PillagerWorldData {
            val persisted = WarbandCorePersistence.load(tag)
            return PillagerWorldData().also { data ->
                data.restoredSnapshot = persisted.snapshot.deepCopy()
                data.runtimeSpecRevision = persisted.runtimeSpecRevision
                data.minecraftSidecar = persisted.sidecar
            }
        }
    }
}

private fun WarbandSnapshot.deepCopy(): WarbandSnapshot =
    Json.decodeFromString(Json.encodeToString(this))
