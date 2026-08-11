package com.gerald.pillagercampaigns.data

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import com.gerald.pillagercampaigns.system.EnvironmentTraits
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
    var bannerSeed: Int,
    var rallyChunkX: Int,
    var rallyChunkZ: Int,
    var reserve: Int,
    var capacity: Int = 156,
    var raidPool: Double = 0.0,
    var aggression: Int = 6,
    var environment: EnvironmentTraits = EnvironmentTraits(),
    val preferences: MutableMap<String, Double> = mutableMapOf(),
    val playerRelations: MutableMap<UUID, String> = mutableMapOf(),
    val armory: MutableList<CompoundTag> = mutableListOf(),
    val garrisonThreat: MutableMap<UUID, Double> = mutableMapOf(),
    var lastEconomyTick: Long = 0L,
    var recruitTickDebt: Double = 0.0,
    var mobilizationTickDebt: Double = 0.0,
    var defeated: Boolean,
    var warlordOfficerId: UUID,
    var warlordEntityId: UUID?,
    var nextRaidTick: Long,
    var cooldownUntilTick: Long,
    var lastIntelTick: Long,
    var lastPresenceFailure: PresenceMaterializationResult,
    var lastPresenceAttemptTick: Long = 0L,
    var activeCampaignLimit: Int = 1,
    var rallyPresence: RallyPresenceRecord? = null,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("id", id)
        it.putUUID("factionId", factionId)
        it.putString("dimension", dimension.toString())
        it.putInt("bannerSeed", bannerSeed)
        it.putInt("rallyChunkX", rallyChunkX)
        it.putInt("rallyChunkZ", rallyChunkZ)
        it.putInt("reserve", reserve)
        it.putInt("capacity", capacity)
        it.putDouble("raidPool", raidPool)
        it.putInt("aggression", aggression)
        it.putLong("lastEconomyTick", lastEconomyTick)
        it.putDouble("recruitTickDebt", recruitTickDebt)
        it.putDouble("mobilizationTickDebt", mobilizationTickDebt)
        it.put("environment", CompoundTag().also { env ->
            env.putDouble("habitability", environment.habitability)
            env.putDouble("biomass", environment.biomass)
            env.putDouble("mineralPotential", environment.mineralPotential)
            env.putDouble("exoticPotential", environment.exoticPotential)
            env.putDouble("travelFriction", environment.travelFriction)
        })
        it.put("preferences", CompoundTag().also { prefs -> preferences.forEach(prefs::putDouble) })
        it.put("playerRelations", CompoundTag().also { relations -> playerRelations.forEach { (id, value) -> relations.putString(id.toString(), value) } })
        it.put("armory", saveRecordList(armory))
        it.put("garrisonThreat", CompoundTag().also { values -> garrisonThreat.forEach { (id, threat) -> values.putDouble(id.toString(), threat) } })
        it.putBoolean("defeated", defeated)
        it.putUUID("warlordOfficerId", warlordOfficerId)
        warlordEntityId?.let { entity -> it.putUUID("warlordEntityId", entity) }
        it.putLong("nextRaidTick", nextRaidTick)
        it.putLong("cooldownUntilTick", cooldownUntilTick)
        it.putLong("lastIntelTick", lastIntelTick)
        it.putString("lastPresenceFailure", lastPresenceFailure.name)
        it.putLong("lastPresenceAttemptTick", lastPresenceAttemptTick)
        it.putInt("activeCampaignLimit", activeCampaignLimit)
        rallyPresence?.let { presence -> it.put("rallyPresence", presence.save()) }
    }

    fun rallyBlockPos(y: Int = 64): BlockPos = BlockPos((rallyChunkX shl 4) + 8, y, (rallyChunkZ shl 4) + 8)

    companion object {
        fun load(tag: CompoundTag): PillagerWarband = PillagerWarband(
            id = tag.getUUID("id"),
            factionId = tag.getUUID("factionId"),
            dimension = ResourceLocation.tryParse(tag.getString("dimension")) ?: ResourceLocation.tryParse("minecraft:overworld")!!,
            bannerSeed = if (tag.contains("bannerSeed")) tag.getInt("bannerSeed") else (tag.getUUID("id").mostSignificantBits xor tag.getUUID("id").leastSignificantBits).toInt(),
            rallyChunkX = tag.getInt("rallyChunkX"),
            rallyChunkZ = tag.getInt("rallyChunkZ"),
            reserve = when {
                tag.contains("reserve") -> tag.getInt("reserve")
                tag.contains("strength") -> tag.getInt("strength").coerceAtLeast(1) * 6
                else -> 18
            },
            capacity = if (tag.contains("capacity")) tag.getInt("capacity") else 156,
            raidPool = if (tag.contains("raidPool")) tag.getDouble("raidPool") else 0.0,
            aggression = if (tag.contains("aggression")) tag.getInt("aggression").coerceIn(6, 18) else 6,
            environment = if (tag.contains("environment", Tag.TAG_COMPOUND.toInt())) tag.getCompound("environment").let { env -> EnvironmentTraits(
                env.getDouble("habitability"), env.getDouble("biomass"), env.getDouble("mineralPotential"), env.getDouble("exoticPotential"), env.getDouble("travelFriction"),
            ).bounded() } else EnvironmentTraits(),
            preferences = mutableMapOf<String, Double>().also { values -> if (tag.contains("preferences", Tag.TAG_COMPOUND.toInt())) tag.getCompound("preferences").allKeys.forEach { values[it] = tag.getCompound("preferences").getDouble(it) } },
            playerRelations = mutableMapOf<UUID, String>().also { values -> if (tag.contains("playerRelations", Tag.TAG_COMPOUND.toInt())) tag.getCompound("playerRelations").allKeys.forEach { key -> runCatching { UUID.fromString(key) }.getOrNull()?.let { values[it] = tag.getCompound("playerRelations").getString(key) } } },
            armory = mutableListOf<CompoundTag>().also { values -> if (tag.contains("armory", Tag.TAG_LIST.toInt())) tag.getList("armory", Tag.TAG_COMPOUND.toInt()).forEach { values += (it as CompoundTag).copy() } },
            garrisonThreat = mutableMapOf<UUID, Double>().also { values -> if (tag.contains("garrisonThreat", Tag.TAG_COMPOUND.toInt())) tag.getCompound("garrisonThreat").allKeys.forEach { key -> runCatching { UUID.fromString(key) }.getOrNull()?.let { values[it] = tag.getCompound("garrisonThreat").getDouble(key) } } },
            lastEconomyTick = if (tag.contains("lastEconomyTick")) tag.getLong("lastEconomyTick") else 0L,
            recruitTickDebt = if (tag.contains("recruitTickDebt")) tag.getDouble("recruitTickDebt") else 0.0,
            mobilizationTickDebt = if (tag.contains("mobilizationTickDebt")) tag.getDouble("mobilizationTickDebt") else 0.0,
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
    var state: OfficerState = OfficerState.IDLE,
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
        it.putString("state", state.name)
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
            state = when (tag.getString("state")) {
                "AVAILABLE" -> OfficerState.IDLE
                else -> runCatching { OfficerState.valueOf(tag.getString("state")) }.getOrDefault(OfficerState.IDLE)
            },
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
    var lastCombatTick: Long = 0L,
    var resolvedTick: Long = 0L,
    var committedThreat: Int = 0,
    val pendingEquipment: MutableList<CompoundTag> = mutableListOf(),
    val memberEquipment: MutableMap<UUID, CompoundTag> = mutableMapOf(),
    val memberThreat: MutableMap<UUID, Double> = mutableMapOf(),
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
        it.putLong("lastCombatTick", lastCombatTick)
        it.putLong("resolvedTick", resolvedTick)
        it.putInt("committedThreat", committedThreat)
        it.put("pendingEquipment", saveRecordList(pendingEquipment))
        it.put("memberEquipment", saveRecordList(memberEquipment.map { (id, stack) -> CompoundTag().also { entry -> entry.putUUID("id", id); entry.put("stack", stack.copy()) } }))
        it.put("memberThreat", CompoundTag().also { values -> memberThreat.forEach { (id, threat) -> values.putDouble(id.toString(), threat) } })
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
            lastCombatTick = if (tag.contains("lastCombatTick")) tag.getLong("lastCombatTick") else 0L,
            resolvedTick = if (tag.contains("resolvedTick")) tag.getLong("resolvedTick") else 0L,
            committedThreat = if (tag.contains("committedThreat")) tag.getInt("committedThreat") else 0,
            pendingEquipment = mutableListOf<CompoundTag>().also { values -> if (tag.contains("pendingEquipment", Tag.TAG_LIST.toInt())) tag.getList("pendingEquipment", Tag.TAG_COMPOUND.toInt()).forEach { values += (it as CompoundTag).copy() } },
            memberEquipment = mutableMapOf<UUID, CompoundTag>().also { values -> if (tag.contains("memberEquipment", Tag.TAG_LIST.toInt())) tag.getList("memberEquipment", Tag.TAG_COMPOUND.toInt()).forEach { raw -> (raw as CompoundTag).let { entry -> if (entry.hasUUID("id")) values[entry.getUUID("id")] = entry.getCompound("stack").copy() } } },
            memberThreat = mutableMapOf<UUID, Double>().also { values -> if (tag.contains("memberThreat", Tag.TAG_COMPOUND.toInt())) tag.getCompound("memberThreat").allKeys.forEach { key -> runCatching { UUID.fromString(key) }.getOrNull()?.let { values[it] = tag.getCompound("memberThreat").getDouble(key) } } },
        )
    }
}

fun saveRecordList(tags: Collection<CompoundTag>): ListTag = ListTag().also { list -> tags.forEach { list.add(it) } }

inline fun loadRecordList(root: CompoundTag, key: String, crossinline loader: (CompoundTag) -> Unit) {
    root.getList(key, Tag.TAG_COMPOUND.toInt()).forEach { raw -> loader(raw as CompoundTag) }
}
