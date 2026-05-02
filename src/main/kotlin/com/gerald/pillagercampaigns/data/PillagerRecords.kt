package com.gerald.pillagercampaigns.data

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import java.util.UUID

data class PillagerFaction(
    val id: UUID,
    var name: String,
    var bannerSeed: Int,
    var bossOfficerId: UUID?,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putString("name", name)
        it.putInt("bannerSeed", bannerSeed)
        bossOfficerId?.let { boss -> it.putUUID("bossOfficerId", boss) }
    }

    companion object {
        fun load(tag: CompoundTag): PillagerFaction = PillagerFaction(
            id = tag.getUUID("id"),
            name = tag.getString("name"),
            bannerSeed = tag.getInt("bannerSeed"),
            bossOfficerId = if (tag.hasUUID("bossOfficerId")) tag.getUUID("bossOfficerId") else null,
        )
    }
}

data class PillagerBase(
    val id: UUID,
    val factionId: UUID,
    val dimension: ResourceLocation,
    var bannerSeed: Int,
    var difficulty: Int,
    var defeated: Boolean,
    var chunkX: Int,
    var chunkZ: Int,
    var center: BlockPos,
    var lastSeenTick: Long,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putUUID("factionId", factionId)
        it.putString("dimension", dimension.toString())
        it.putInt("bannerSeed", bannerSeed)
        it.putInt("difficulty", difficulty)
        it.putBoolean("defeated", defeated)
        it.putInt("chunkX", chunkX)
        it.putInt("chunkZ", chunkZ)
        it.putInt("x", center.x)
        it.putInt("y", center.y)
        it.putInt("z", center.z)
        it.putLong("lastSeenTick", lastSeenTick)
    }

    companion object {
        fun load(tag: CompoundTag): PillagerBase = PillagerBase(
            id = tag.getUUID("id"),
            factionId = tag.getUUID("factionId"),
            dimension = ResourceLocation.tryParse(tag.getString("dimension")) ?: ResourceLocation.tryParse("minecraft:overworld")!!,
            bannerSeed = if (tag.contains("bannerSeed")) tag.getInt("bannerSeed") else (tag.getUUID("id").mostSignificantBits xor tag.getUUID("id").leastSignificantBits).toInt(),
            difficulty = if (tag.contains("difficulty")) tag.getInt("difficulty") else 0,
            defeated = if (tag.contains("defeated")) tag.getBoolean("defeated") else false,
            chunkX = tag.getInt("chunkX"),
            chunkZ = tag.getInt("chunkZ"),
            center = BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
            lastSeenTick = tag.getLong("lastSeenTick"),
        )
    }
}

data class PillagerOfficer(
    val id: UUID,
    val factionId: UUID,
    var homeBaseId: UUID,
    var name: String,
    var title: String,
    var rank: OfficerRank,
    var officerClass: OfficerClass,
    var state: OfficerState,
    val preferenceGraph: MutableMap<String, Double>,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putUUID("factionId", factionId)
        it.putUUID("homeBaseId", homeBaseId)
        it.putString("name", name)
        it.putString("title", title)
        it.putString("rank", rank.name)
        it.putString("officerClass", officerClass.name)
        it.putString("state", state.name)
        val prefs = CompoundTag()
        preferenceGraph.forEach { (k, v) -> prefs.putDouble(k, v) }
        it.put("preferenceGraph", prefs)
    }

    companion object {
        fun load(tag: CompoundTag): PillagerOfficer = PillagerOfficer(
            id = tag.getUUID("id"),
            factionId = tag.getUUID("factionId"),
            homeBaseId = tag.getUUID("homeBaseId"),
            name = tag.getString("name"),
            title = tag.getString("title"),
            rank = runCatching { OfficerRank.valueOf(tag.getString("rank")) }.getOrDefault(OfficerRank.CAPTAIN),
            officerClass = runCatching { OfficerClass.valueOf(tag.getString("officerClass")) }.getOrDefault(OfficerClass.PILLAGER),
            state = runCatching { OfficerState.valueOf(tag.getString("state")) }.getOrDefault(OfficerState.AVAILABLE),
            preferenceGraph = mutableMapOf<String, Double>().also { graph ->
                if (tag.contains("preferenceGraph", Tag.TAG_COMPOUND.toInt())) {
                    val prefs = tag.getCompound("preferenceGraph")
                    prefs.allKeys.forEach { key -> graph[key] = prefs.getDouble(key) }
                }
            },
        )
    }
}

data class PillagerCampaign(
    val id: UUID,
    val factionId: UUID,
    val originBaseId: UUID,
    val officerId: UUID,
    val targetPlayerId: UUID,
    var targetDimension: ResourceLocation,
    var currentChunkX: Int,
    var currentChunkZ: Int,
    var targetChunkX: Int,
    var targetChunkZ: Int,
    var difficultySnapshot: Int,
    var loadoutSeed: Long,
    var tickDebt: Int,
    var state: CampaignState,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putUUID("factionId", factionId)
        it.putUUID("originBaseId", originBaseId)
        it.putUUID("officerId", officerId)
        it.putUUID("targetPlayerId", targetPlayerId)
        it.putString("targetDimension", targetDimension.toString())
        it.putInt("currentChunkX", currentChunkX)
        it.putInt("currentChunkZ", currentChunkZ)
        it.putInt("targetChunkX", targetChunkX)
        it.putInt("targetChunkZ", targetChunkZ)
        it.putInt("difficultySnapshot", difficultySnapshot)
        it.putLong("loadoutSeed", loadoutSeed)
        it.putInt("tickDebt", tickDebt)
        it.putString("state", state.name)
    }

    companion object {
        fun load(tag: CompoundTag): PillagerCampaign = PillagerCampaign(
            id = tag.getUUID("id"),
            factionId = tag.getUUID("factionId"),
            originBaseId = tag.getUUID("originBaseId"),
            officerId = tag.getUUID("officerId"),
            targetPlayerId = tag.getUUID("targetPlayerId"),
            targetDimension = ResourceLocation.tryParse(tag.getString("targetDimension")) ?: ResourceLocation.tryParse("minecraft:overworld")!!,
            currentChunkX = tag.getInt("currentChunkX"),
            currentChunkZ = tag.getInt("currentChunkZ"),
            targetChunkX = tag.getInt("targetChunkX"),
            targetChunkZ = tag.getInt("targetChunkZ"),
            difficultySnapshot = if (tag.contains("difficultySnapshot")) tag.getInt("difficultySnapshot") else 0,
            loadoutSeed = if (tag.contains("loadoutSeed")) tag.getLong("loadoutSeed") else tag.getUUID("id").mostSignificantBits xor tag.getUUID("id").leastSignificantBits,
            tickDebt = tag.getInt("tickDebt"),
            state = runCatching { CampaignState.valueOf(tag.getString("state")) }.getOrDefault(CampaignState.TRAVELING),
        )
    }
}

fun saveRecordList(tags: Collection<CompoundTag>): ListTag = ListTag().also { list -> tags.forEach { list.add(it) } }

inline fun loadRecordList(root: CompoundTag, key: String, crossinline loader: (CompoundTag) -> Unit) {
    root.getList(key, Tag.TAG_COMPOUND.toInt()).forEach { raw -> loader(raw as CompoundTag) }
}
