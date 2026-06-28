package com.gerald.pillagercampaigns.util

import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.OfficerClass
import java.util.UUID
import kotlin.math.abs

object PillagerIdentity {
    private val factionNames = listOf(
        "Blackroot Standard",
        "Red Ash Compact",
        "Broken Bell Host",
        "Crow-Tithe Banner",
        "Iron Bramble Company",
    )

    private val firstNames = listOf("Ghor", "Brakk", "Narl", "Vesh", "Krag", "Rusk", "Mauk", "Drenn")
    private val titles = listOf("the Finder", "the Red Hand", "the Crow-Eye", "the Banner-Biter", "the Longshot")

    fun makeFaction(seed: Long): PillagerFaction {
        val id = UUID.nameUUIDFromBytes("pillagercampaigns:faction:$seed".toByteArray())
        val idx = abs(seed.toInt())
        return PillagerFaction(id = id, name = factionNames[idx % factionNames.size], bannerSeed = idx, bossOfficerId = null)
    }

    fun makeOfficer(
        faction: PillagerFaction,
        homeWarbandId: UUID,
        seed: Long,
        rank: OfficerRank = OfficerRank.CAPTAIN,
        officerClass: OfficerClass = OfficerClass.PILLAGER,
        preferenceGraph: MutableMap<String, Double> = mutableMapOf(),
    ): PillagerOfficer {
        val idx = abs((seed xor homeWarbandId.mostSignificantBits).toInt())
        return PillagerOfficer(
            id = UUID.nameUUIDFromBytes("pillagercampaigns:officer:${faction.id}:$homeWarbandId:$seed".toByteArray()),
            factionId = faction.id,
            homeWarbandId = homeWarbandId,
            name = firstNames[idx % firstNames.size],
            title = titles[(idx / 3) % titles.size],
            rank = rank,
            officerClass = officerClass,
            state = OfficerState.AVAILABLE,
            preferenceGraph = preferenceGraph,
        )
    }
}
