package com.gerald.pillagercampaigns.data

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class PillagerWorldData : SavedData() {
    val factions: MutableMap<UUID, PillagerFaction> = linkedMapOf()
    val warbands: MutableMap<UUID, PillagerWarband> = linkedMapOf()
    val officers: MutableMap<UUID, PillagerOfficer> = linkedMapOf()
    val campaigns: MutableMap<UUID, PillagerCampaign> = linkedMapOf()
    val initializedPlayers: MutableSet<UUID> = linkedSetOf()
    val protectedPlayersUntilTick: MutableMap<UUID, Long> = linkedMapOf()

    var lastDiscoveryTick: Long = 0L
    var lastCampaignTick: Long = 0L

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("schema", SCHEMA_VERSION)
        tag.putLong("lastDiscoveryTick", lastDiscoveryTick)
        tag.putLong("lastCampaignTick", lastCampaignTick)
        tag.put("factions", saveRecordList(factions.values.map { it.save() }))
        tag.put("warbands", saveRecordList(warbands.values.map { it.save() }))
        tag.put("officers", saveRecordList(officers.values.map { it.save() }))
        tag.put("campaigns", saveRecordList(campaigns.values.map { it.save() }))
        tag.put("initializedPlayers", saveUuidList(initializedPlayers))
        tag.put("protectedPlayersUntilTick", saveProtectedPlayers(protectedPlayersUntilTick))
        return tag
    }

    fun markChanged() {
        setDirty()
    }

    fun markPlayerInitialized(playerId: UUID): Boolean {
        val added = initializedPlayers.add(playerId)
        if (added) markChanged()
        return added
    }

    fun protectPlayerUntil(playerId: UUID, untilTick: Long) {
        if ((protectedPlayersUntilTick[playerId] ?: Long.MIN_VALUE) >= untilTick) return
        protectedPlayersUntilTick[playerId] = untilTick
        markChanged()
    }

    fun isPlayerProtected(playerId: UUID, now: Long): Boolean {
        val untilTick = protectedPlayersUntilTick[playerId] ?: return false
        if (now <= untilTick) return true
        protectedPlayersUntilTick.remove(playerId)
        markChanged()
        return false
    }

    companion object {
        private const val KEY = "pillagercampaigns_world"
        private const val SCHEMA_VERSION = 1

        fun get(server: MinecraftServer): PillagerWorldData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::PillagerWorldData, KEY)

        fun load(tag: CompoundTag): PillagerWorldData {
            val data = PillagerWorldData()
            data.lastDiscoveryTick = tag.getLong("lastDiscoveryTick")
            data.lastCampaignTick = tag.getLong("lastCampaignTick")
            loadRecordList(tag, "factions") { PillagerFaction.load(it).let { v -> data.factions[v.id] = v } }
            loadRecordList(tag, "warbands") { PillagerWarband.load(it).let { v -> data.warbands[v.id] = v } }
            loadRecordList(tag, "officers") { PillagerOfficer.load(it).let { v -> data.officers[v.id] = v } }
            loadRecordList(tag, "campaigns") { PillagerCampaign.load(it).let { v -> data.campaigns[v.id] = v } }
            if (tag.contains("initializedPlayers", Tag.TAG_LIST.toInt())) {
                tag.getList("initializedPlayers", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                    val entry = raw as CompoundTag
                    if (entry.hasUUID("id")) data.initializedPlayers += entry.getUUID("id")
                }
            }
            if (tag.contains("protectedPlayersUntilTick", Tag.TAG_LIST.toInt())) {
                tag.getList("protectedPlayersUntilTick", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                    val entry = raw as CompoundTag
                    if (entry.hasUUID("id") && entry.contains("untilTick")) {
                        data.protectedPlayersUntilTick[entry.getUUID("id")] = entry.getLong("untilTick")
                    }
                }
            }
            data.repairReferences()
            return data
        }

        private fun saveUuidList(ids: Collection<UUID>): ListTag = ListTag().also { list ->
            ids.forEach { id ->
                list.add(CompoundTag().also { it.putUUID("id", id) })
            }
        }

        private fun saveProtectedPlayers(players: Map<UUID, Long>): ListTag = ListTag().also { list ->
            players.forEach { (id, untilTick) ->
                list.add(CompoundTag().also {
                    it.putUUID("id", id)
                    it.putLong("untilTick", untilTick)
                })
            }
        }
    }

    private fun repairReferences() {
        val factionIds = factions.keys
        val warbandIds = warbands.keys
        val officerIds = officers.keys
        warbands.entries.removeIf { (_, warband) -> warband.factionId !in factionIds || warband.warlordOfficerId !in officerIds }
        officers.entries.removeIf { (_, officer) -> officer.factionId !in factionIds || officer.homeWarbandId !in warbandIds }
        campaigns.entries.removeIf { (_, campaign) ->
            campaign.factionId !in factionIds || campaign.originWarbandId !in warbandIds || campaign.officerId !in officerIds
        }
    }
}
