package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.PillagerBase
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import java.util.UUID

internal class PillagerSettlementChunkIndex {
    private val materializedByChunk = mutableMapOf<ResourceLocation, MutableMap<Long, MutableSet<UUID>>>()
    private val materializedBaseIds = mutableListOf<UUID>()

    val materializedIds: List<UUID>
        get() = materializedBaseIds.toList()

    fun clear() {
        materializedByChunk.clear()
        materializedBaseIds.clear()
    }

    fun index(base: PillagerBase) {
        if (base.defeated || base.state != BaseState.MATERIALIZED) return
        val dimensionIndex = materializedByChunk.getOrPut(base.dimension) { mutableMapOf() }
        dimensionIndex.getOrPut(ChunkPos.asLong(base.chunkX, base.chunkZ)) { mutableSetOf() } += base.id
        if (base.id !in materializedBaseIds) materializedBaseIds += base.id
    }

    fun idsAt(dimension: ResourceLocation, chunkX: Int, chunkZ: Int): Set<UUID> =
        materializedByChunk[dimension]?.get(ChunkPos.asLong(chunkX, chunkZ))?.toSet() ?: emptySet()
}
