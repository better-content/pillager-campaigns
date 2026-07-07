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
    var bossEntityId: UUID? = null,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putString("name", name)
        it.putInt("bannerSeed", bannerSeed)
        bossOfficerId?.let { boss -> it.putUUID("bossOfficerId", boss) }
        bossEntityId?.let { boss -> it.putUUID("bossEntityId", boss) }
    }

    companion object {
        fun load(tag: CompoundTag): PillagerFaction = PillagerFaction(
            id = tag.getUUID("id"),
            name = tag.getString("name"),
            bannerSeed = tag.getInt("bannerSeed"),
            bossOfficerId = if (tag.hasUUID("bossOfficerId")) tag.getUUID("bossOfficerId") else null,
            bossEntityId = if (tag.hasUUID("bossEntityId")) tag.getUUID("bossEntityId") else null,
        )
    }
}

data class PillagerWarband(
    val id: UUID,
    val factionId: UUID,
    val dimension: ResourceLocation,
    var structureId: ResourceLocation,
    var bannerSeed: Int,
    var rallyChunkX: Int,
    var rallyChunkZ: Int,
    var strength: Int,
    var defeated: Boolean,
    var warlordOfficerId: UUID,
    var warlordEntityId: UUID?,
    var nextRaidTick: Long,
    var cooldownUntilTick: Long,
    var lastIntelTick: Long,
    var lastPresenceFailure: PresenceMaterializationResult,
    var lastPresenceAttemptTick: Long = 0L,
    var activeCampaignLimit: Int = 1,
    var archetype: WarbandArchetype = WarbandArchetype.SKIRMISHER,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putUUID("factionId", factionId)
        it.putString("dimension", dimension.toString())
        it.putString("structureId", structureId.toString())
        it.putInt("bannerSeed", bannerSeed)
        it.putInt("rallyChunkX", rallyChunkX)
        it.putInt("rallyChunkZ", rallyChunkZ)
        it.putInt("strength", strength)
        it.putBoolean("defeated", defeated)
        it.putUUID("warlordOfficerId", warlordOfficerId)
        warlordEntityId?.let { entity -> it.putUUID("warlordEntityId", entity) }
        it.putLong("nextRaidTick", nextRaidTick)
        it.putLong("cooldownUntilTick", cooldownUntilTick)
        it.putLong("lastIntelTick", lastIntelTick)
        it.putString("lastPresenceFailure", lastPresenceFailure.name)
        it.putLong("lastPresenceAttemptTick", lastPresenceAttemptTick)
        it.putInt("activeCampaignLimit", activeCampaignLimit)
        it.putString("archetype", archetype.name)
    }

    fun rallyBlockPos(y: Int = 64): BlockPos = BlockPos((rallyChunkX shl 4) + 8, y, (rallyChunkZ shl 4) + 8)

    companion object {
        fun load(tag: CompoundTag): PillagerWarband = PillagerWarband(
            id = tag.getUUID("id"),
            factionId = tag.getUUID("factionId"),
            dimension = ResourceLocation.tryParse(tag.getString("dimension")) ?: ResourceLocation.tryParse("minecraft:overworld")!!,
            structureId = ResourceLocation.tryParse(tag.getString("structureId")) ?: ResourceLocation.tryParse("minecraft:pillager_outpost")!!,
            bannerSeed = if (tag.contains("bannerSeed")) tag.getInt("bannerSeed") else (tag.getUUID("id").mostSignificantBits xor tag.getUUID("id").leastSignificantBits).toInt(),
            rallyChunkX = tag.getInt("rallyChunkX"),
            rallyChunkZ = tag.getInt("rallyChunkZ"),
            strength = if (tag.contains("strength")) tag.getInt("strength") else 1,
            defeated = if (tag.contains("defeated")) tag.getBoolean("defeated") else false,
            warlordOfficerId = tag.getUUID("warlordOfficerId"),
            warlordEntityId = if (tag.hasUUID("warlordEntityId")) tag.getUUID("warlordEntityId") else null,
            nextRaidTick = if (tag.contains("nextRaidTick")) tag.getLong("nextRaidTick") else 0L,
            cooldownUntilTick = if (tag.contains("cooldownUntilTick")) tag.getLong("cooldownUntilTick") else 0L,
            lastIntelTick = if (tag.contains("lastIntelTick")) tag.getLong("lastIntelTick") else 0L,
            lastPresenceFailure = if (tag.contains("lastPresenceFailure")) {
                runCatching { PresenceMaterializationResult.valueOf(tag.getString("lastPresenceFailure")) }.getOrDefault(PresenceMaterializationResult.SUCCESS)
            } else PresenceMaterializationResult.SUCCESS,
            lastPresenceAttemptTick = if (tag.contains("lastPresenceAttemptTick")) tag.getLong("lastPresenceAttemptTick") else 0L,
            activeCampaignLimit = if (tag.contains("activeCampaignLimit")) tag.getInt("activeCampaignLimit") else 1,
            archetype = if (tag.contains("archetype")) {
                runCatching { WarbandArchetype.valueOf(tag.getString("archetype")) }.getOrDefault(archetypeForId(tag.getUUID("id")))
            } else archetypeForId(tag.getUUID("id")),
        )

        private fun archetypeForId(id: UUID): WarbandArchetype {
            val values = WarbandArchetype.entries
            val idx = Math.floorMod((id.mostSignificantBits xor id.leastSignificantBits).toInt(), values.size)
            return values[idx]
        }
    }
}

data class PillagerOfficer(
    val id: UUID,
    val factionId: UUID,
    var homeWarbandId: UUID,
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
        it.putUUID("homeWarbandId", homeWarbandId)
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
            homeWarbandId = tag.getUUID("homeWarbandId"),
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
    val originWarbandId: UUID,
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
    var materializeAttemptId: UUID?,
    var materializingUntilTick: Long,
    var squadMemberIds: MutableList<UUID>,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putUUID("factionId", factionId)
        it.putUUID("originWarbandId", originWarbandId)
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
        materializeAttemptId?.let { attempt -> it.putUUID("materializeAttemptId", attempt) }
        it.putLong("materializingUntilTick", materializingUntilTick)
        val members = ListTag()
        squadMemberIds.forEach { member ->
            val entry = CompoundTag()
            entry.putUUID("id", member)
            members.add(entry)
        }
        it.put("squadMemberIds", members)
    }

    companion object {
        fun load(tag: CompoundTag): PillagerCampaign = PillagerCampaign(
            id = tag.getUUID("id"),
            factionId = tag.getUUID("factionId"),
            originWarbandId = tag.getUUID("originWarbandId"),
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
            materializeAttemptId = if (tag.hasUUID("materializeAttemptId")) tag.getUUID("materializeAttemptId") else null,
            materializingUntilTick = if (tag.contains("materializingUntilTick")) tag.getLong("materializingUntilTick") else 0L,
            squadMemberIds = mutableListOf<UUID>().also { ids ->
                if (tag.contains("squadMemberIds", Tag.TAG_LIST.toInt())) {
                    tag.getList("squadMemberIds", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                        val entry = raw as CompoundTag
                        if (entry.hasUUID("id")) ids += entry.getUUID("id")
                    }
                }
            },
        )
    }
}

fun saveRecordList(tags: Collection<CompoundTag>): ListTag = ListTag().also { list -> tags.forEach { list.add(it) } }

inline fun loadRecordList(root: CompoundTag, key: String, crossinline loader: (CompoundTag) -> Unit) {
    root.getList(key, Tag.TAG_COMPOUND.toInt()).forEach { raw -> loader(raw as CompoundTag) }
}
