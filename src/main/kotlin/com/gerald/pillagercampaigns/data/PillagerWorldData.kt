package com.gerald.pillagercampaigns.data

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class PillagerWorldData : SavedData() {
    val factions: MutableMap<UUID, PillagerFaction> = linkedMapOf()
    val bases: MutableMap<UUID, PillagerBase> = linkedMapOf()
    val officers: MutableMap<UUID, PillagerOfficer> = linkedMapOf()
    val campaigns: MutableMap<UUID, PillagerCampaign> = linkedMapOf()

    var lastDiscoveryTick: Long = 0L
    var lastCampaignTick: Long = 0L

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("schema", SCHEMA_VERSION)
        tag.putLong("lastDiscoveryTick", lastDiscoveryTick)
        tag.putLong("lastCampaignTick", lastCampaignTick)
        tag.put("factions", saveRecordList(factions.values.map { it.save() }))
        tag.put("bases", saveRecordList(bases.values.map { it.save() }))
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
            loadRecordList(tag, "bases") { PillagerBase.load(it).let { v -> data.bases[v.id] = v } }
            loadRecordList(tag, "officers") { PillagerOfficer.load(it).let { v -> data.officers[v.id] = v } }
            loadRecordList(tag, "campaigns") { PillagerCampaign.load(it).let { v -> data.campaigns[v.id] = v } }
            data.repairReferences()
            return data
        }
    }

    private fun repairReferences() {
        val factionIds = factions.keys
        val baseIds = bases.keys
        val officerIds = officers.keys
        bases.entries.removeIf { (_, base) -> base.factionId !in factionIds }
        officers.entries.removeIf { (_, officer) -> officer.factionId !in factionIds || officer.homeBaseId !in baseIds }
        campaigns.entries.removeIf { (_, campaign) ->
            campaign.factionId !in factionIds || campaign.originBaseId !in baseIds || campaign.officerId !in officerIds
        }
    }
}
