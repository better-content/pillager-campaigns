package com.gerald.pillagercampaigns.util

import com.gerald.pillagercampaigns.data.*
import com.gerald.pillagercampaigns.system.OfficerAffixRules
import com.gerald.pillagercampaigns.system.OfficerDoctrineRules
import com.gerald.pillagercampaigns.system.OfficerGeneRules
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.entity.BannerBlockEntity
import java.util.UUID
import kotlin.math.abs

object PillagerIdentity {
    private val names = listOf("Ghor", "Brakk", "Narl", "Vesh", "Krag", "Rusk", "Mauk", "Drenn", "Skath", "Orvek", "Harr", "Torg")
    private val titles = listOf("the Finder", "the Red Hand", "the Crow-Eye", "the Banner-Biter", "the Gate-Hater", "the Ash Caller", "the Longshot", "the Taxman")
    private val factionNames = listOf("Blackroot Standard", "Red Ash Compact", "Broken Bell Host", "Crow-Tithe Banner", "Iron Bramble Company", "Mudspire Levy", "Hollow Pike Host")
    private val colors = listOf("black", "red", "gray", "brown", "orange", "white", "blue", "green", "purple")

    fun makeFaction(seed: Long): PillagerFaction {
        val id = UUID.nameUUIDFromBytes("pillagercampaigns:faction:$seed".toByteArray())
        val idx = abs(seed.toInt())
        val base = colors[idx % colors.size]
        val accent = colors[(idx / 7 + 3) % colors.size]
        return PillagerFaction(id, factionNames[idx % factionNames.size], base, accent, idx, 1 + idx % 4, 1 + (idx / 3) % 4)
    }

    fun makeOfficer(
        faction: PillagerFaction,
        baseId: UUID,
        seed: Long,
        role: OfficerRole = OfficerRole.entries[abs(seed.toInt()) % OfficerRole.entries.size],
        rank: OfficerRank = OfficerRank.CAPTAIN,
        predecessor: PillagerOfficer? = null,
    ): PillagerOfficer {
        val id = UUID.nameUUIDFromBytes("pillagercampaigns:officer:$baseId:$seed".toByteArray())
        val idx = abs((seed xor baseId.mostSignificantBits).toInt())
        val localPressure = rolePressure(role)
        val genes = OfficerGeneRules.rollReplacement(faction.warMemory, predecessor, localPressure, seed)
        val doctrine = OfficerDoctrineRules.doctrineFor(genes)
        val affixes = OfficerAffixRules.affixesFor(genes, rank, emptySet()).toMutableSet()
        val lineage = if (predecessor != null) {
            OfficerLineage(predecessor.id, rank, faction.patternSeed xor predecessor.lineage.inheritedBannerSeed, "took up ${predecessor.name}'s banner")
        } else {
            OfficerLineage.none(rank, faction.patternSeed)
        }
        return PillagerOfficer(id, names[idx % names.size], titles[(idx / 5) % titles.size], faction.id, baseId, rank, role, OfficerState.ACTIVE, 0, 0, 0, 0, genes, doctrine, affixes, lineage)
    }

    fun makeOfficer(factionId: UUID, baseId: UUID, seed: Long, role: OfficerRole = OfficerRole.entries[abs(seed.toInt()) % OfficerRole.entries.size]): PillagerOfficer {
        val faction = PillagerFaction(factionId, "Unmarked Host", "black", "red", seed.toInt(), 1, 1)
        return makeOfficer(faction, baseId, seed, role)
    }

    private fun rolePressure(role: OfficerRole): OfficerGeneProfile = when (role) {
        OfficerRole.SCOUTMASTER -> OfficerGeneProfile.neutral(20).copy(speed = 85, survival = 80, range = 55)
        OfficerRole.SKIRMISHER -> OfficerGeneProfile.neutral(25).copy(speed = 65, melee = 60, survival = 55)
        OfficerRole.SIEGE_ENGINEER -> OfficerGeneProfile.neutral(20).copy(siege = 90, fire = 55, armor = 50)
        OfficerRole.BANNER_BEARER -> OfficerGeneProfile.neutral(20).copy(banner = 90, armor = 65, survival = 55)
        OfficerRole.BEAST_HANDLER -> OfficerGeneProfile.neutral(20).copy(beast = 90, siege = 60, melee = 50)
        OfficerRole.WITCH_TOUCHED -> OfficerGeneProfile.neutral(20).copy(magic = 90, survival = 70, range = 45)
        OfficerRole.HUNTER -> OfficerGeneProfile.neutral(20).copy(range = 90, speed = 60, survival = 55)
    }

    fun bannerStack(faction: PillagerFaction): ItemStack {
        val stack = ItemStack(BannerBlock.byColor(faction.baseDyeColor()).asItem())
        stack.hoverName = Component.literal(faction.name).withStyle(ChatFormatting.RED)
        val tag = CompoundTag()
        val patterns = ListTag()
        patterns.add(pattern("mr", faction.accentDyeColor()))
        patterns.add(pattern("bs", faction.accentDyeColor()))
        patterns.add(pattern("hh", faction.baseDyeColor()))
        tag.put(BannerBlockEntity.TAG_PATTERNS, patterns)
        net.minecraft.world.item.BlockItem.setBlockEntityData(stack, net.minecraft.world.level.block.entity.BlockEntityType.BANNER, tag)
        return stack
    }

    fun ordersPaper(title: String, lines: List<String>): ItemStack {
        val stack = ItemStack(Items.PAPER)
        stack.hoverName = Component.literal(title).withStyle(ChatFormatting.GOLD)
        val display = stack.getOrCreateTagElement("display")
        val lore = ListTag()
        lines.take(OfficerOrdersRules.MAX_LORE_LINES).forEach { lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(it).withStyle(ChatFormatting.GRAY)))) }
        display.put("Lore", lore)
        return stack
    }

    private fun pattern(hash: String, color: DyeColor): CompoundTag = CompoundTag().also { it.putString("Pattern", hash); it.putInt("Color", color.id) }
}
