package com.bettercontent.pillagercampaigns.data

import com.bettercontent.pillagercampaigns.PillagerCampaignsMod
import com.bettercontent.pillagercampaigns.core.DirectorFrame
import com.bettercontent.pillagercampaigns.core.DirectorSnapshot
import com.bettercontent.pillagercampaigns.core.DirectorTransition
import com.bettercontent.pillagercampaigns.core.InvasionDirector
import com.bettercontent.pillagercampaigns.core.InvasionRuntimeSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

class PillagerWorldData private constructor(private var restored: DirectorSnapshot) : SavedData() {
    private var engine: InvasionDirector? = null
    private var runtimeRevision: String = "unresolved"

    fun transition(frame: DirectorFrame, spec: InvasionRuntimeSpec): DirectorTransition {
        val director = engine ?: InvasionDirector.restore(restored, spec).also {
            engine = it
            runtimeRevision = spec.revision
        }
        val result = director.transition(frame)
        restored = director.snapshot()
        setDirty()
        return result
    }

    fun attach(spec: InvasionRuntimeSpec) {
        if (engine == null) {
            engine = InvasionDirector.restore(restored, spec)
            runtimeRevision = spec.revision
        }
    }

    fun snapshot(): DirectorSnapshot = engine?.snapshot() ?: copy(restored)
    fun runtimeRevision(): String = runtimeRevision

    override fun save(tag: CompoundTag): CompoundTag {
        val snapshot = snapshot()
        tag.putInt("schema", DirectorSnapshot.CURRENT_SCHEMA_VERSION)
        tag.putString("runtimeRevision", runtimeRevision)
        tag.putString("snapshot", JSON.encodeToString(snapshot))
        return tag
    }

    companion object {
        private const val KEY = "pillager_campaigns_world"
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }

        fun get(server: MinecraftServer): PillagerWorldData {
            val seed = server.overworld().seed
            return server.overworld().dataStorage.computeIfAbsent(
                { tag -> load(tag, seed) },
                { PillagerWorldData(DirectorSnapshot(worldSeed = seed)) },
                KEY,
            )
        }

        internal fun load(tag: CompoundTag, worldSeed: Long): PillagerWorldData {
            if (tag.getInt("schema") != DirectorSnapshot.CURRENT_SCHEMA_VERSION || !tag.contains("snapshot")) {
                if (!tag.isEmpty) PillagerCampaignsMod.LOGGER.warn("Discarding pre-0.3 Pillager Campaigns strategic state; the invasion director starts fresh")
                return PillagerWorldData(DirectorSnapshot(worldSeed = worldSeed))
            }
            val snapshot = runCatching { JSON.decodeFromString<DirectorSnapshot>(tag.getString("snapshot")) }
                .getOrElse { error ->
                    PillagerCampaignsMod.LOGGER.error("Invalid Pillager Campaigns invasion state; starting fresh", error)
                    DirectorSnapshot(worldSeed = worldSeed)
                }
            return PillagerWorldData(snapshot).also { it.runtimeRevision = tag.getString("runtimeRevision").ifBlank { "unresolved" } }
        }

        private fun copy(snapshot: DirectorSnapshot): DirectorSnapshot = JSON.decodeFromString(JSON.encodeToString(snapshot))
    }
}
