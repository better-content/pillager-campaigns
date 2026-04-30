package com.gerald.pillagerpressure.util

import com.gerald.pillagerpressure.data.PillagerFaction
import com.gerald.pillagerpressure.data.PillagerOfficer
import com.gerald.pillagerpressure.data.OfficerRank
import com.gerald.pillagerpressure.data.OfficerRole
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
    private val colors = listOf(DyeColor.BLACK, DyeColor.RED, DyeColor.GRAY, DyeColor.BROWN, DyeColor.ORANGE, DyeColor.WHITE, DyeColor.BLUE, DyeColor.GREEN, DyeColor.PURPLE)

    fun makeFaction(seed: Long): PillagerFaction {
        val id = UUID.nameUUIDFromBytes("pillagerpressure:faction:$seed".toByteArray())
        val idx = abs(seed.toInt())
        val base = colors[idx % colors.size]
        val accent = colors[(idx / 7 + 3) % colors.size]
        return PillagerFaction(id, factionNames[idx % factionNames.size], base, accent, idx, 1 + idx % 4, 1 + (idx / 3) % 4)
    }

    fun makeOfficer(factionId: UUID, baseId: UUID, seed: Long, role: OfficerRole = OfficerRole.entries[abs(seed.toInt()) % OfficerRole.entries.size]): PillagerOfficer {
        val id = UUID.nameUUIDFromBytes("pillagerpressure:officer:$baseId:$seed".toByteArray())
        val idx = abs((seed xor baseId.mostSignificantBits).toInt())
        return PillagerOfficer(id, names[idx % names.size], titles[(idx / 5) % titles.size], factionId, baseId, OfficerRank.CAPTAIN, role, com.gerald.pillagerpressure.data.OfficerState.ACTIVE, 0, 0, 0, 0)
    }

    fun bannerStack(faction: PillagerFaction): ItemStack {
        val stack = ItemStack(BannerBlock.byColor(faction.baseColor).asItem())
        stack.hoverName = Component.literal(faction.name).withStyle(ChatFormatting.RED)
        val tag = CompoundTag()
        val patterns = ListTag()
        patterns.add(pattern("mr", faction.accentColor))
        patterns.add(pattern("bs", faction.accentColor))
        patterns.add(pattern("hh", faction.baseColor))
        tag.put(BannerBlockEntity.TAG_PATTERNS, patterns)
        net.minecraft.world.item.BlockItem.setBlockEntityData(stack, net.minecraft.world.level.block.entity.BlockEntityType.BANNER, tag)
        return stack
    }

    fun ordersPaper(title: String, lines: List<String>): ItemStack {
        val stack = ItemStack(Items.PAPER)
        stack.hoverName = Component.literal(title).withStyle(ChatFormatting.GOLD)
        val display = stack.getOrCreateTagElement("display")
        val lore = ListTag()
        lines.take(8).forEach { lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(it).withStyle(ChatFormatting.GRAY)))) }
        display.put("Lore", lore)
        return stack
    }

    private fun pattern(hash: String, color: DyeColor): CompoundTag = CompoundTag().also { it.putString("Pattern", hash); it.putInt("Color", color.id) }
}
