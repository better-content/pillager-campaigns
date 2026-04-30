package com.gerald.pillagerpressure.data

import com.gerald.pillagerpressure.PillagerPressureMod
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class PillagerWorldData : SavedData() {
    val factions: MutableMap<UUID, PillagerFaction> = linkedMapOf()
    val bases: MutableMap<UUID, PillagerBase> = linkedMapOf()
    val campaigns: MutableMap<UUID, PillagerCampaign> = linkedMapOf()
    val officers: MutableMap<UUID, PillagerOfficer> = linkedMapOf()
    val regions: MutableMap<String, RegionActivity> = linkedMapOf()
    val pendingMarkers: MutableList<PendingFlagMarker> = mutableListOf()
    val engineeredBlocks: MutableList<EngineeredBlockMarker> = mutableListOf()

    var lastCampaignTick: Long = 0L
    var lastBaseScanTick: Long = 0L
    var lastRegionTick: Long = 0L

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("schema", SCHEMA_VERSION)
        tag.putLong("lastCampaignTick", lastCampaignTick)
        tag.putLong("lastBaseScanTick", lastBaseScanTick)
        tag.putLong("lastRegionTick", lastRegionTick)
        tag.put("factions", list(factions.values.map { it.save() }))
        tag.put("bases", list(bases.values.map { it.save() }))
        tag.put("campaigns", list(campaigns.values.map { it.save() }))
        tag.put("officers", list(officers.values.map { it.save() }))
        tag.put("regions", list(regions.values.map { it.save() }))
        tag.put("pendingMarkers", list(pendingMarkers.map { it.save() }))
        tag.put("engineeredBlocks", list(engineeredBlocks.map { it.save() }))
        return tag
    }

    fun markChanged() {
        setDirty()
    }

    companion object {
        private const val KEY = "pillagerpressure_world"
        private const val SCHEMA_VERSION = 1

        fun get(server: MinecraftServer): PillagerWorldData = server.overworld().dataStorage.computeIfAbsent(::load, ::PillagerWorldData, KEY)

        fun load(tag: CompoundTag): PillagerWorldData {
            val data = PillagerWorldData()
            data.lastCampaignTick = tag.getLong("lastCampaignTick")
            data.lastBaseScanTick = tag.getLong("lastBaseScanTick")
            data.lastRegionTick = tag.getLong("lastRegionTick")
            loadList(tag, "factions") { PillagerFaction.load(it).let { faction -> data.factions[faction.id] = faction } }
            loadList(tag, "bases") { PillagerBase.load(it).let { base -> data.bases[base.id] = base } }
            loadList(tag, "campaigns") { PillagerCampaign.load(it).let { campaign -> data.campaigns[campaign.id] = campaign } }
            loadList(tag, "officers") { PillagerOfficer.load(it).let { officer -> data.officers[officer.id] = officer } }
            loadList(tag, "regions") { RegionActivity.load(it).let { region -> data.regions[regionKey(region.key)] = region } }
            loadList(tag, "pendingMarkers") { data.pendingMarkers.add(PendingFlagMarker.load(it)) }
            loadList(tag, "engineeredBlocks") { data.engineeredBlocks.add(EngineeredBlockMarker.load(it)) }
            data.repairReferences()
            return data
        }

        fun regionKey(key: RegionKey): String = "${key.x},${key.z}"

        private fun list(tags: List<CompoundTag>): ListTag = ListTag().also { list -> tags.forEach { list.add(it) } }

        private fun loadList(root: CompoundTag, key: String, loader: (CompoundTag) -> Unit) {
            root.getList(key, Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                runCatching { loader(raw as CompoundTag) }.onFailure { error ->
                    PillagerPressureMod.LOGGER.warn("Skipping corrupt Pillager Pressure saved-data entry in '{}': {}", key, error.message)
                }
            }
        }
    }

    private fun repairReferences() {
        val validFactions = factions.keys
        val validBases = bases.keys
        var changed = false

        val removedBases = bases.entries.removeIf { (_, base) ->
            val bad = base.factionId !in validFactions || (base.parentBaseId != null && base.parentBaseId !in validBases)
            if (bad) PillagerPressureMod.LOGGER.warn("Removing dangling pillager base {}", base.id)
            bad
        }
        changed = changed || removedBases

        val validBasesAfterRepair = bases.keys
        val removedOfficers = officers.entries.removeIf { (_, officer) ->
            val bad = officer.factionId !in validFactions || officer.homeBaseId !in validBasesAfterRepair
            if (bad) PillagerPressureMod.LOGGER.warn("Removing dangling pillager officer {}", officer.id)
            bad
        }
        changed = changed || removedOfficers

        val validOfficers = officers.keys
        val removedCampaigns = campaigns.entries.removeIf { (_, campaign) ->
            val bad = campaign.factionId !in validFactions || campaign.originBaseId !in validBasesAfterRepair || (campaign.officerId != null && campaign.officerId !in validOfficers)
            if (bad) PillagerPressureMod.LOGGER.warn("Removing dangling pillager campaign {}", campaign.id)
            bad
        }
        changed = changed || removedCampaigns

        val removedMarkers = pendingMarkers.removeIf { marker -> marker.factionId !in validFactions || (marker.officerId != null && marker.officerId !in validOfficers) }
        changed = changed || removedMarkers
        if (changed) markChanged()
    }
}
