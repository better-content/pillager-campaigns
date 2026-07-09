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

data class RallyPresenceRecord(
    var state: RallyPresenceState,
    var warlordId: UUID,
    var entityId: UUID? = null,
    var anchorX: Int? = null,
    var anchorY: Int? = null,
    var anchorZ: Int? = null,
    var lastMaterializedTick: Long = 0L,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putString("state", state.name)
        it.putUUID("warlordId", warlordId)
        entityId?.let { entity -> it.putUUID("entityId", entity) }
        anchorX?.let { x -> it.putInt("anchorX", x) }
        anchorY?.let { y -> it.putInt("anchorY", y) }
        anchorZ?.let { z -> it.putInt("anchorZ", z) }
        it.putLong("lastMaterializedTick", lastMaterializedTick)
    }

    companion object {
        fun load(tag: CompoundTag): RallyPresenceRecord = RallyPresenceRecord(
            state = runCatching { RallyPresenceState.valueOf(tag.getString("state")) }.getOrDefault(RallyPresenceState.DORMANT),
            warlordId = if (tag.hasUUID("warlordId")) tag.getUUID("warlordId") else UUID(0L, 0L),
            entityId = if (tag.hasUUID("entityId")) tag.getUUID("entityId") else null,
            anchorX = if (tag.contains("anchorX")) tag.getInt("anchorX") else null,
            anchorY = if (tag.contains("anchorY")) tag.getInt("anchorY") else null,
            anchorZ = if (tag.contains("anchorZ")) tag.getInt("anchorZ") else null,
            lastMaterializedTick = if (tag.contains("lastMaterializedTick")) tag.getLong("lastMaterializedTick") else 0L,
        )
    }
}

data class NemesisEvent(
    val tick: Long,
    val type: NemesisEventType,
    val playerId: UUID? = null,
    val warbandId: UUID? = null,
    val campaignId: UUID? = null,
    val severity: String? = null,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putLong("tick", tick)
        it.putString("type", type.name)
        playerId?.let { value -> it.putUUID("playerId", value) }
        warbandId?.let { value -> it.putUUID("warbandId", value) }
        campaignId?.let { value -> it.putUUID("campaignId", value) }
        severity?.let { value -> it.putString("severity", value) }
    }

    companion object {
        fun load(tag: CompoundTag): NemesisEvent = NemesisEvent(
            tick = if (tag.contains("tick")) tag.getLong("tick") else 0L,
            type = runCatching { NemesisEventType.valueOf(tag.getString("type")) }.getOrDefault(NemesisEventType.LOST_CAMPAIGN),
            playerId = if (tag.hasUUID("playerId")) tag.getUUID("playerId") else null,
            warbandId = if (tag.hasUUID("warbandId")) tag.getUUID("warbandId") else null,
            campaignId = if (tag.hasUUID("campaignId")) tag.getUUID("campaignId") else null,
            severity = if (tag.contains("severity")) tag.getString("severity") else null,
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
    var rallyPresence: RallyPresenceRecord? = null,
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
        rallyPresence?.let { presence -> it.put("rallyPresence", presence.save()) }
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
            rallyPresence = when {
                tag.contains("rallyPresence", Tag.TAG_COMPOUND.toInt()) -> RallyPresenceRecord.load(tag.getCompound("rallyPresence"))
                tag.hasUUID("warlordOfficerId") -> RallyPresenceRecord(
                    state = if (tag.hasUUID("warlordEntityId")) RallyPresenceState.MATERIALIZED else RallyPresenceState.DORMANT,
                    warlordId = tag.getUUID("warlordOfficerId"),
                    entityId = if (tag.hasUUID("warlordEntityId")) tag.getUUID("warlordEntityId") else null,
                )
                else -> null
            },
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
    var role: OfficerRole = OfficerRole.CAPTAIN,
    var rank: OfficerRank = OfficerRank.CAPTAIN,
    var officerClass: OfficerClass = OfficerClass.PILLAGER,
    var state: OfficerState = OfficerState.IDLE,
    var combatStyle: CombatStyle = CombatStyle.HUNTER,
    val preferenceGraph: MutableMap<String, Double>,
    var kills: Int = 0,
    var deathsInflicted: Int = 0,
    var campaignVictories: Int = 0,
    var campaignDefeats: Int = 0,
    var lastTargetPlayerId: UUID? = null,
    var lastSeenTick: Long = 0L,
    var injuryOrRecoveryUntilTick: Long = 0L,
    var promotionTier: Int = 0,
    val nemesisHistory: MutableList<NemesisEvent> = mutableListOf(),
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putUUID("factionId", factionId)
        it.putUUID("homeWarbandId", homeWarbandId)
        it.putString("name", name)
        it.putString("title", title)
        it.putString("role", role.name)
        it.putString("rank", rank.name)
        it.putString("officerClass", officerClass.name)
        it.putString("state", state.name)
        it.putString("combatStyle", combatStyle.name)
        it.putInt("kills", kills)
        it.putInt("deathsInflicted", deathsInflicted)
        it.putInt("campaignVictories", campaignVictories)
        it.putInt("campaignDefeats", campaignDefeats)
        lastTargetPlayerId?.let { player -> it.putUUID("lastTargetPlayerId", player) }
        it.putLong("lastSeenTick", lastSeenTick)
        it.putLong("injuryOrRecoveryUntilTick", injuryOrRecoveryUntilTick)
        it.putInt("promotionTier", promotionTier)
        val prefs = CompoundTag()
        preferenceGraph.forEach { (k, v) -> prefs.putDouble(k, v) }
        it.put("preferenceGraph", prefs)
        it.put("nemesisHistory", saveRecordList(nemesisHistory.map { event -> event.save() }))
    }

    companion object {
        fun load(tag: CompoundTag): PillagerOfficer = PillagerOfficer(
            id = tag.getUUID("id"),
            factionId = tag.getUUID("factionId"),
            homeWarbandId = tag.getUUID("homeWarbandId"),
            name = tag.getString("name"),
            title = tag.getString("title"),
            role = if (tag.contains("role")) {
                runCatching { OfficerRole.valueOf(tag.getString("role")) }.getOrDefault(OfficerRole.CAPTAIN)
            } else if (tag.getString("rank") == "WARLORD") OfficerRole.WARLORD else OfficerRole.CAPTAIN,
            rank = when (val rawRank = tag.getString("rank")) {
                "WARLORD" -> OfficerRank.DREAD_CAPTAIN
                else -> runCatching { OfficerRank.valueOf(rawRank) }.getOrDefault(OfficerRank.CAPTAIN)
            },
            officerClass = runCatching { OfficerClass.valueOf(tag.getString("officerClass")) }.getOrDefault(OfficerClass.PILLAGER),
            state = when (tag.getString("state")) {
                "AVAILABLE" -> OfficerState.IDLE
                else -> runCatching { OfficerState.valueOf(tag.getString("state")) }.getOrDefault(OfficerState.IDLE)
            },
            combatStyle = if (tag.contains("combatStyle")) {
                runCatching { CombatStyle.valueOf(tag.getString("combatStyle")) }.getOrDefault(CombatStyle.HUNTER)
            } else inferCombatStyle(
                mutableMapOf<String, Double>().also { graph ->
                    if (tag.contains("preferenceGraph", Tag.TAG_COMPOUND.toInt())) {
                        val prefs = tag.getCompound("preferenceGraph")
                        prefs.allKeys.forEach { key -> graph[key] = prefs.getDouble(key) }
                    }
                },
            ),
            preferenceGraph = mutableMapOf<String, Double>().also { graph ->
                if (tag.contains("preferenceGraph", Tag.TAG_COMPOUND.toInt())) {
                    val prefs = tag.getCompound("preferenceGraph")
                    prefs.allKeys.forEach { key -> graph[key] = prefs.getDouble(key) }
                }
            },
            kills = if (tag.contains("kills")) tag.getInt("kills") else 0,
            deathsInflicted = if (tag.contains("deathsInflicted")) tag.getInt("deathsInflicted") else 0,
            campaignVictories = if (tag.contains("campaignVictories")) tag.getInt("campaignVictories") else 0,
            campaignDefeats = if (tag.contains("campaignDefeats")) tag.getInt("campaignDefeats") else 0,
            lastTargetPlayerId = if (tag.hasUUID("lastTargetPlayerId")) tag.getUUID("lastTargetPlayerId") else null,
            lastSeenTick = if (tag.contains("lastSeenTick")) tag.getLong("lastSeenTick") else 0L,
            injuryOrRecoveryUntilTick = if (tag.contains("injuryOrRecoveryUntilTick")) tag.getLong("injuryOrRecoveryUntilTick") else 0L,
            promotionTier = if (tag.contains("promotionTier")) tag.getInt("promotionTier") else 0,
            nemesisHistory = mutableListOf<NemesisEvent>().also { history ->
                if (tag.contains("nemesisHistory", Tag.TAG_LIST.toInt())) {
                    tag.getList("nemesisHistory", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                        history += NemesisEvent.load(raw as CompoundTag)
                    }
                }
            },
        )

        private fun inferCombatStyle(preferenceGraph: Map<String, Double>): CombatStyle {
            val dominant = preferenceGraph.maxByOrNull { it.value }?.key ?: return CombatStyle.HUNTER
            return when {
                dominant.contains("melee", ignoreCase = true) -> CombatStyle.BUTCHER
                dominant.contains("magic", ignoreCase = true) || dominant.contains("caster", ignoreCase = true) -> CombatStyle.HEXER
                dominant.contains("stealth", ignoreCase = true) || dominant.contains("mobility", ignoreCase = true) -> CombatStyle.SABOTEUR
                dominant.contains("ranged", ignoreCase = true) -> CombatStyle.HARRIER
                else -> CombatStyle.HUNTER
            }
        }
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
    var resumeState: CampaignState?,
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
        resumeState?.let { resume -> it.putString("resumeState", resume.name) }
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
            resumeState = if (tag.contains("resumeState")) {
                runCatching { CampaignState.valueOf(tag.getString("resumeState")) }.getOrNull()
            } else null,
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
