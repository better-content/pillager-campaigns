package com.gerald.pillagercampaigns.data

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class PillagerWorldData : SavedData() {
    val factions: MutableMap<UUID, PillagerFaction> = linkedMapOf()
    val warbands: MutableMap<UUID, PillagerWarband> = linkedMapOf()
    val officers: MutableMap<UUID, PillagerOfficer> = linkedMapOf()
    val campaigns: MutableMap<UUID, PillagerCampaign> = linkedMapOf()

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
        return tag
    }

    fun markChanged() {
        setDirty()
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
            data.repairReferences()
            return data
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
