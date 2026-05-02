package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.OfficerClass
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.ChatFormatting
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Pillager
import net.minecraft.world.entity.monster.Vindicator
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.util.Random
import java.util.UUID

object PillagerRuntime {
    const val CAMPAIGN_TAG = "PillagerCampaignId"
    const val OFFICER_TAG = "PillagerOfficerId"
    const val LEADER_TAG = "PillagerOfficerLeader"
    const val FACTION_TAG = "PillagerFactionId"
    const val BOSS_TAG = "PillagerFactionBoss"
    const val RANK_TAG = "PillagerOfficerRank"
    const val SCALE_TAG = "PillagerOfficerScale"

    fun materializeFixedSquad(level: ServerLevel, campaign: PillagerCampaign, base: PillagerBase, officerRecord: PillagerOfficer, player: ServerPlayer, x: Double, y: Double, z: Double): Int {
        val random = Random(campaign.loadoutSeed)
        val officer = createOfficerEntity(level, officerRecord.officerClass) ?: return 0
        prepareOfficer(officer, campaign, base, officerRecord, x, y, z, random, campaign.difficultySnapshot)
        level.addFreshEntity(officer)

        var spawned = 1
        val memberCount = CampaignDifficultyRules.memberCountForDifficulty(campaign.difficultySnapshot)
        repeat(memberCount) {
            val memberType = CampaignDifficultyRules.chooseMemberType(campaign.difficultySnapshot, officerRecord.preferenceGraph, random)
            val mob: Mob = when (memberType) {
                "vindicator" -> EntityType.VINDICATOR.create(level)
                else -> EntityType.PILLAGER.create(level)
            } ?: return@repeat
            prepareFollower(mob, campaign, officerRecord, x + level.random.nextDouble() * 3.0 - 1.5, y, z + level.random.nextDouble() * 3.0 - 1.5, random, campaign.difficultySnapshot)
            mob.target = player
            level.addFreshEntity(mob)
            spawned++
        }
        return spawned
    }

    fun keepSquadCohesive(level: ServerLevel, mob: Mob) {
        val tag = mob.persistentData
        if (!tag.hasUUID(CAMPAIGN_TAG) || tag.getBoolean(LEADER_TAG) || mob.target != null) return
        val officerId = tag.getUUID(OFFICER_TAG)
        val officer = level.getEntitiesOfClass(Mob::class.java, mob.boundingBox.inflate(40.0)) { candidate ->
            candidate.persistentData.hasUUID(OFFICER_TAG) &&
                candidate.persistentData.getUUID(OFFICER_TAG) == officerId &&
                candidate.persistentData.getBoolean(LEADER_TAG)
        }.firstOrNull() ?: return
        val dist = mob.distanceToSqr(officer)
        if (dist > 48.0 * 48.0) {
            mob.moveTo(officer.x + 0.5, officer.y, officer.z + 0.5, mob.yRot, mob.xRot)
            return
        }
        mob.navigation.moveTo(officer, 1.15)
    }

    fun ensureBossAtBase(level: ServerLevel, base: PillagerBase, faction: PillagerFaction, bossOfficer: PillagerOfficer) {
        val existing = level.getEntitiesOfClass(Mob::class.java, AABB.ofSize(Vec3.atCenterOf(base.center), 48.0, 24.0, 48.0)) { candidate ->
            candidate.isAlive &&
                candidate.persistentData.hasUUID(OFFICER_TAG) &&
                candidate.persistentData.getUUID(OFFICER_TAG) == bossOfficer.id &&
                candidate.persistentData.getBoolean(BOSS_TAG)
        }
        if (existing.isNotEmpty()) return
        val boss = EntityType.VINDICATOR.create(level) ?: return
        boss.moveTo(base.center.x + 0.5, base.center.y.toDouble(), base.center.z + 0.5, boss.yRot, boss.xRot)
        boss.setPersistenceRequired()
        boss.persistentData.putBoolean(BOSS_TAG, true)
        boss.persistentData.putBoolean(LEADER_TAG, true)
        boss.persistentData.putUUID(OFFICER_TAG, bossOfficer.id)
        boss.persistentData.putUUID(FACTION_TAG, faction.id)
        boss.persistentData.putString(RANK_TAG, bossOfficer.rank.name)
        boss.setItemSlot(EquipmentSlot.HEAD, makeBaseBanner(base.bannerSeed))
        boss.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_AXE))
        applyOfficerVisuals(boss, bossOfficer)
        level.addFreshEntity(boss)
    }

    fun pushOfficerTowardPlayer(level: ServerLevel, mob: Mob) {
        val tag = mob.persistentData
        if (!tag.getBoolean(LEADER_TAG)) return
        val nearest = level.players().minByOrNull { it.distanceToSqr(mob) } ?: return
        mob.target = nearest
        mob.navigation.moveTo(nearest, 1.15)
    }

    fun tickOfficerVisuals(level: ServerLevel, mob: Mob) {
        val tag = mob.persistentData
        if (!tag.hasUUID(OFFICER_TAG)) return
        if (tag.getBoolean(LEADER_TAG)) {
            mob.isCustomNameVisible = true
        }
        val color = colorForOfficer(tag.getUUID(OFFICER_TAG))
        level.sendParticles(
            DustParticleOptions(color.vector, 1.0f),
            mob.x,
            mob.y + 1.4,
            mob.z,
            2,
            0.18,
            0.08,
            0.18,
            0.001,
        )
    }

    private fun prepareOfficer(
        mob: Mob,
        campaign: PillagerCampaign,
        base: PillagerBase,
        officerRecord: PillagerOfficer,
        x: Double,
        y: Double,
        z: Double,
        random: Random,
        difficulty: Int,
    ) {
        mob.moveTo(x, y, z, mob.yRot, mob.xRot)
        mob.setPersistenceRequired()
        mob.persistentData.applyCampaignTags(campaign)
        mob.persistentData.putBoolean(LEADER_TAG, true)
        mob.persistentData.putString(RANK_TAG, officerRecord.rank.name)
        mob.setItemSlot(EquipmentSlot.HEAD, makeBaseBanner(base.bannerSeed))
        equipWeaponByPreference(mob, officerRecord, random, difficulty)
        applyArmorByPreference(mob, officerRecord, random, difficulty)
        applyEnchantmentsByPreference(mob, officerRecord, random, difficulty)
        applyOfficerVisuals(mob, officerRecord)
    }

    private fun prepareFollower(mob: Mob, campaign: PillagerCampaign, officerRecord: PillagerOfficer, x: Double, y: Double, z: Double, random: Random, difficulty: Int) {
        mob.moveTo(x, y, z, mob.yRot, mob.xRot)
        mob.setPersistenceRequired()
        mob.persistentData.applyCampaignTags(campaign)
        mob.persistentData.putBoolean(LEADER_TAG, false)
        equipWeaponByPreference(mob, officerRecord, random, difficulty)
        applyArmorByPreference(mob, officerRecord, random, difficulty)
        applyEnchantmentsByPreference(mob, officerRecord, random, difficulty)
    }

    private fun CompoundTag.applyCampaignTags(campaign: PillagerCampaign) {
        putUUID(CAMPAIGN_TAG, campaign.id)
        putUUID(OFFICER_TAG, campaign.officerId)
        putUUID(FACTION_TAG, campaign.factionId)
    }

    private fun applyOfficerVisuals(entity: LivingEntity, officer: PillagerOfficer) {
        val color = colorForOfficer(officer.id)
        entity.customName = Component.literal("${officer.name} ${officer.title}").withStyle(color.formatting)
        entity.isCustomNameVisible = true
        val scale = 1.2 + (officer.rank.ordinal * 0.1)
        entity.persistentData.putDouble(SCALE_TAG, scale)
    }

    private fun createOfficerEntity(level: ServerLevel, officerClass: OfficerClass): Mob? = when (officerClass) {
        OfficerClass.PILLAGER -> EntityType.PILLAGER.create(level)
        OfficerClass.VINDICATOR -> EntityType.VINDICATOR.create(level)
        OfficerClass.WITCH -> EntityType.WITCH.create(level)
        OfficerClass.EVOKER -> EntityType.EVOKER.create(level)
        OfficerClass.ILLUSIONER -> EntityType.ILLUSIONER.create(level)
    }

    private fun equipWeaponByPreference(mob: Mob, officer: PillagerOfficer, random: Random, difficulty: Int) {
        val allowed = mutableListOf("weapon_crossbow", "weapon_bow", "weapon_sword", "weapon_axe")
        if (difficulty >= 2) allowed += "weapon_trident"
        val chosen = CampaignDifficultyRules.weightedChoice(officer.preferenceGraph, random, allowed)
        val stack = when (chosen) {
            "weapon_bow" -> ItemStack(Items.BOW)
            "weapon_sword" -> ItemStack(tieredSword(difficulty, random))
            "weapon_axe" -> ItemStack(tieredAxe(difficulty, random))
            "weapon_trident" -> ItemStack(Items.TRIDENT)
            else -> ItemStack(Items.CROSSBOW)
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, stack)
        mob.setItemInHand(InteractionHand.MAIN_HAND, stack)
    }

    private fun applyArmorByPreference(mob: Mob, officer: PillagerOfficer, random: Random, difficulty: Int) {
        val piecePlan = mutableListOf<String>()
        repeat(CampaignDifficultyRules.armorPieceCountForTier(difficulty, 3)) { piecePlan += "leather" }
        repeat(CampaignDifficultyRules.armorPieceCountForTier(difficulty, 6)) { piecePlan += "gold" }
        repeat(CampaignDifficultyRules.armorPieceCountForTier(difficulty, 9)) { piecePlan += "iron" }
        repeat(CampaignDifficultyRules.armorPieceCountForTier(difficulty, 12)) { piecePlan += "diamond" }

        piecePlan.forEach { tier ->
            val slot = CampaignDifficultyRules.weightedChoice(officer.preferenceGraph, random, listOf("slot_head", "slot_chest", "slot_legs", "slot_feet"))
            val equipmentSlot = when (slot) {
                "slot_chest" -> EquipmentSlot.CHEST
                "slot_legs" -> EquipmentSlot.LEGS
                "slot_feet" -> EquipmentSlot.FEET
                else -> EquipmentSlot.HEAD
            }
            val piece = armorForTierAndSlot(tier, equipmentSlot)
            if (equipmentSlot == EquipmentSlot.HEAD && mob.getItemBySlot(EquipmentSlot.HEAD).item != Items.AIR && mob.getItemBySlot(EquipmentSlot.HEAD).item.toString().contains("banner")) {
                val fallback = listOf(EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).firstOrNull { mob.getItemBySlot(it).isEmpty } ?: equipmentSlot
                mob.setItemSlot(fallback, piece)
            } else {
                mob.setItemSlot(equipmentSlot, piece)
            }
        }
    }

    private fun applyEnchantmentsByPreference(mob: Mob, officer: PillagerOfficer, random: Random, difficulty: Int) {
        val tier = CampaignDifficultyRules.enchantTierForDifficulty(difficulty)
        if (tier <= 0) return
        val enchantChoices = listOf(
            "enchant_sharpness" to Enchantments.SHARPNESS,
            "enchant_smite" to Enchantments.SMITE,
            "enchant_bane" to Enchantments.BANE_OF_ARTHROPODS,
            "enchant_protection" to Enchantments.ALL_DAMAGE_PROTECTION,
            "enchant_proj_prot" to Enchantments.PROJECTILE_PROTECTION,
            "enchant_blast_prot" to Enchantments.BLAST_PROTECTION,
            "enchant_fire_prot" to Enchantments.FIRE_PROTECTION,
            "enchant_unbreaking" to Enchantments.UNBREAKING,
            "enchant_power" to Enchantments.POWER_ARROWS,
            "enchant_quick_charge" to Enchantments.QUICK_CHARGE,
        )
        val stacks = listOf(
            mob.getItemBySlot(EquipmentSlot.MAINHAND),
            mob.getItemBySlot(EquipmentSlot.HEAD),
            mob.getItemBySlot(EquipmentSlot.CHEST),
            mob.getItemBySlot(EquipmentSlot.LEGS),
            mob.getItemBySlot(EquipmentSlot.FEET),
        ).filter { !it.isEmpty }
        stacks.forEach { stack ->
            val key = CampaignDifficultyRules.weightedChoice(officer.preferenceGraph, random, enchantChoices.map { it.first })
            val enchant = enchantChoices.first { it.first == key }.second
            if (enchant.canEnchant(stack)) stack.enchant(enchant, tier) else stack.enchant(Enchantments.UNBREAKING, tier)
        }
    }

    private fun tieredSword(difficulty: Int, random: Random): Item = when {
        difficulty >= 12 -> pick(random, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.STONE_SWORD)
        difficulty >= 9 -> pick(random, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.STONE_SWORD)
        difficulty >= 6 -> pick(random, Items.GOLDEN_SWORD, Items.STONE_SWORD)
        difficulty >= 2 -> Items.STONE_SWORD
        else -> Items.WOODEN_SWORD
    }

    private fun tieredAxe(difficulty: Int, random: Random): Item = when {
        difficulty >= 12 -> pick(random, Items.DIAMOND_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.STONE_AXE)
        difficulty >= 9 -> pick(random, Items.IRON_AXE, Items.GOLDEN_AXE, Items.STONE_AXE)
        difficulty >= 6 -> pick(random, Items.GOLDEN_AXE, Items.STONE_AXE)
        difficulty >= 2 -> Items.STONE_AXE
        else -> Items.WOODEN_AXE
    }

    private fun pick(random: Random, vararg items: Item): Item = items[random.nextInt(items.size)]

    private fun armorForTierAndSlot(tier: String, slot: EquipmentSlot): ItemStack = when (tier) {
        "diamond" -> when (slot) {
            EquipmentSlot.HEAD -> ItemStack(Items.DIAMOND_HELMET)
            EquipmentSlot.CHEST -> ItemStack(Items.DIAMOND_CHESTPLATE)
            EquipmentSlot.LEGS -> ItemStack(Items.DIAMOND_LEGGINGS)
            else -> ItemStack(Items.DIAMOND_BOOTS)
        }
        "iron" -> when (slot) {
            EquipmentSlot.HEAD -> ItemStack(Items.IRON_HELMET)
            EquipmentSlot.CHEST -> ItemStack(Items.IRON_CHESTPLATE)
            EquipmentSlot.LEGS -> ItemStack(Items.IRON_LEGGINGS)
            else -> ItemStack(Items.IRON_BOOTS)
        }
        "gold" -> when (slot) {
            EquipmentSlot.HEAD -> ItemStack(Items.GOLDEN_HELMET)
            EquipmentSlot.CHEST -> ItemStack(Items.GOLDEN_CHESTPLATE)
            EquipmentSlot.LEGS -> ItemStack(Items.GOLDEN_LEGGINGS)
            else -> ItemStack(Items.GOLDEN_BOOTS)
        }
        else -> when (slot) {
            EquipmentSlot.HEAD -> ItemStack(Items.LEATHER_HELMET)
            EquipmentSlot.CHEST -> ItemStack(Items.LEATHER_CHESTPLATE)
            EquipmentSlot.LEGS -> ItemStack(Items.LEATHER_LEGGINGS)
            else -> ItemStack(Items.LEATHER_BOOTS)
        }
    }

    private fun makeBaseBanner(seed: Int): ItemStack {
        val random = Random(seed.toLong())
        val baseColor = random.nextInt(16)
        val banner = ItemStack(baseBannerItem(baseColor))
        val patterns = listOf("bs", "ts", "ls", "rs", "cs", "ms", "drs", "dls", "cr", "sc", "mc")
        val list = net.minecraft.nbt.ListTag()
        repeat(3 + random.nextInt(3)) {
            val pattern = net.minecraft.nbt.CompoundTag()
            pattern.putString("Pattern", patterns[random.nextInt(patterns.size)])
            pattern.putInt("Color", random.nextInt(16))
            list.add(pattern)
        }
        val blockEntityTag = banner.orCreateTag.getCompound("BlockEntityTag")
        blockEntityTag.put("Patterns", list)
        banner.orCreateTag.put("BlockEntityTag", blockEntityTag)
        return banner
    }

    private fun baseBannerItem(id: Int): Item = when (id and 15) {
        0 -> Items.WHITE_BANNER
        1 -> Items.ORANGE_BANNER
        2 -> Items.MAGENTA_BANNER
        3 -> Items.LIGHT_BLUE_BANNER
        4 -> Items.YELLOW_BANNER
        5 -> Items.LIME_BANNER
        6 -> Items.PINK_BANNER
        7 -> Items.GRAY_BANNER
        8 -> Items.LIGHT_GRAY_BANNER
        9 -> Items.CYAN_BANNER
        10 -> Items.PURPLE_BANNER
        11 -> Items.BLUE_BANNER
        12 -> Items.BROWN_BANNER
        13 -> Items.GREEN_BANNER
        14 -> Items.RED_BANNER
        else -> Items.BLACK_BANNER
    }

    private data class OfficerColor(val formatting: ChatFormatting, val vector: Vector3f)

    private fun colorForOfficer(id: UUID): OfficerColor {
        val palette = listOf(
            OfficerColor(ChatFormatting.RED, Vector3f(1.0f, 0.2f, 0.2f)),
            OfficerColor(ChatFormatting.GOLD, Vector3f(1.0f, 0.75f, 0.1f)),
            OfficerColor(ChatFormatting.YELLOW, Vector3f(0.95f, 0.95f, 0.25f)),
            OfficerColor(ChatFormatting.GREEN, Vector3f(0.2f, 0.9f, 0.2f)),
            OfficerColor(ChatFormatting.AQUA, Vector3f(0.2f, 0.85f, 0.95f)),
            OfficerColor(ChatFormatting.BLUE, Vector3f(0.3f, 0.45f, 1.0f)),
            OfficerColor(ChatFormatting.LIGHT_PURPLE, Vector3f(0.95f, 0.35f, 1.0f)),
            OfficerColor(ChatFormatting.WHITE, Vector3f(0.95f, 0.95f, 0.95f)),
        )
        val idx = ((id.mostSignificantBits xor id.leastSignificantBits).toInt() and Int.MAX_VALUE) % palette.size
        return palette[idx]
    }
}
