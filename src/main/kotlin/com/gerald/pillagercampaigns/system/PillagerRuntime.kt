package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.CombatStyle
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.OfficerClass
import com.gerald.pillagercampaigns.data.WarbandArchetype
import com.gerald.pillagercampaigns.data.WarbandRole
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.WallBannerBlock
import net.minecraftforge.registries.ForgeRegistries
import org.joml.Vector3f
import java.util.Random
import java.util.UUID

object PillagerRuntime {
    const val CAMPAIGN_TAG = "PillagerCampaignId"
    const val OFFICER_TAG = "PillagerOfficerId"
    const val LEADER_TAG = "PillagerOfficerLeader"
    const val FACTION_TAG = "PillagerFactionId"
    const val BOSS_TAG = "PillagerFactionBoss"
    const val ANCHOR_X_TAG = "PillagerAnchorX"
    const val ANCHOR_Y_TAG = "PillagerAnchorY"
    const val ANCHOR_Z_TAG = "PillagerAnchorZ"
    const val RANK_TAG = "PillagerOfficerRank"
    const val OFFICER_NAME_TAG = "PillagerOfficerName"
    const val OFFICER_TITLE_TAG = "PillagerOfficerTitle"
    const val SCALE_TAG = "PillagerOfficerScale"

    private val liveOfficerLeaderEntityIds: MutableMap<UUID, UUID> = linkedMapOf()
    private val liveCampaignMemberEntityIds: MutableMap<UUID, MutableSet<UUID>> = linkedMapOf()
    private val rangedEntityIds = setOf(
        "minecraft:pillager",
        "takesapillage:archer",
        "takesapillage:skirmisher",
        "aquamirae:pillagers_patrol",
    )
    private val meleeEntityIds = setOf(
        "minecraft:vindicator",
        "takesapillage:legioner",
        "savage_and_ravage:executioner",
        "savage_and_ravage:griefer",
        "companions:illager_golem",
    )
    private val casterEntityIds = setOf(
        "minecraft:witch",
        "minecraft:evoker",
        "minecraft:illusioner",
        "savage_and_ravage:trickster",
        "savage_and_ravage:iceologer",
    )

    fun resetLiveIndexes() {
        liveOfficerLeaderEntityIds.clear()
        liveCampaignMemberEntityIds.clear()
    }

    fun registerLiveMob(mob: Mob) {
        val tag = mob.persistentData
        if (tag.hasUUID(CAMPAIGN_TAG)) {
            liveCampaignMemberEntityIds.getOrPut(tag.getUUID(CAMPAIGN_TAG)) { linkedSetOf() }.add(mob.uuid)
        }
        if (tag.hasUUID(OFFICER_TAG) && tag.getBoolean(LEADER_TAG)) {
            liveOfficerLeaderEntityIds[tag.getUUID(OFFICER_TAG)] = mob.uuid
        }
    }

    fun forgetLiveMob(mob: Mob) {
        val tag = mob.persistentData
        if (tag.hasUUID(CAMPAIGN_TAG)) {
            val members = liveCampaignMemberEntityIds[tag.getUUID(CAMPAIGN_TAG)]
            members?.remove(mob.uuid)
            if (members.isNullOrEmpty()) {
                liveCampaignMemberEntityIds.remove(tag.getUUID(CAMPAIGN_TAG))
            }
        }
        if (tag.hasUUID(OFFICER_TAG) && tag.getBoolean(LEADER_TAG)) {
            val officerId = tag.getUUID(OFFICER_TAG)
            if (liveOfficerLeaderEntityIds[officerId] == mob.uuid) {
                liveOfficerLeaderEntityIds.remove(officerId)
            }
        }
    }

    fun hasLiveOfficerLeader(level: ServerLevel, officerId: UUID): Boolean {
        val entityId = liveOfficerLeaderEntityIds[officerId] ?: return false
        val mob = level.getEntity(entityId) as? Mob ?: run {
            liveOfficerLeaderEntityIds.remove(officerId)
            return false
        }
        return mob.isAlive &&
            mob.persistentData.hasUUID(OFFICER_TAG) &&
            mob.persistentData.getUUID(OFFICER_TAG) == officerId &&
            mob.persistentData.getBoolean(LEADER_TAG)
    }

    fun hasLiveCampaignMember(level: ServerLevel, campaignId: UUID): Boolean {
        val members = liveCampaignMemberEntityIds[campaignId] ?: return false
        val iterator = members.iterator()
        while (iterator.hasNext()) {
            val entityId = iterator.next()
            val mob = level.getEntity(entityId) as? Mob
            if (mob != null && mob.isAlive && mob.persistentData.hasUUID(CAMPAIGN_TAG) && mob.persistentData.getUUID(CAMPAIGN_TAG) == campaignId) {
                return true
            }
            iterator.remove()
        }
        if (members.isEmpty()) {
            liveCampaignMemberEntityIds.remove(campaignId)
        }
        return false
    }

    fun countLiveMembers(level: ServerLevel, memberIds: Collection<UUID>): Int {
        if (memberIds.isEmpty()) return 0
        var count = 0
        memberIds.forEach { entityId ->
            val mob = level.getEntity(entityId) as? Mob
            if (mob != null && mob.isAlive) {
                count++
            }
        }
        return count
    }

    fun dismissCampaign(level: ServerLevel, campaignId: UUID, memberIds: Collection<UUID>) {
        memberIds.forEach { entityId ->
            val mob = level.getEntity(entityId) as? Mob ?: return@forEach
            if (mob.persistentData.hasUUID(CAMPAIGN_TAG) && mob.persistentData.getUUID(CAMPAIGN_TAG) == campaignId) {
                forgetLiveMob(mob)
                mob.discard()
            }
        }
        liveCampaignMemberEntityIds.remove(campaignId)
    }

    fun placeFactionDeathBanner(level: ServerLevel, deathPos: BlockPos, bannerSeed: Int): BlockPos? {
        val banner = makeBaseBanner(bannerSeed)
        val block = (Block.byItem(banner.item) as? BannerBlock) ?: (Blocks.WHITE_BANNER as BannerBlock)
        val candidates = listOf(
            deathPos,
            deathPos.above(),
            deathPos.north(),
            deathPos.south(),
            deathPos.east(),
            deathPos.west(),
            deathPos.north().above(),
            deathPos.south().above(),
            deathPos.east().above(),
            deathPos.west().above(),
        )
        for (pos in candidates) {
            if (!level.getBlockState(pos).canBeReplaced()) continue
            val below = pos.below()
            if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                val state = block.defaultBlockState().setValue(BannerBlock.ROTATION, 0)
                if (level.setBlock(pos, state, Block.UPDATE_ALL)) {
                    return pos
                }
            }
        }
        for (pos in candidates) {
            if (!level.getBlockState(pos).canBeReplaced()) continue
            for (direction in Direction.Plane.HORIZONTAL) {
                val anchor = pos.relative(direction.opposite)
                if (!level.getBlockState(anchor).isFaceSturdy(level, anchor, direction)) continue
                val wallBlock = wallBannerFor(block)
                val state = wallBlock.defaultBlockState().setValue(WallBannerBlock.FACING, direction)
                if (level.setBlock(pos, state, Block.UPDATE_ALL)) {
                    return pos
                }
            }
        }
        return null
    }

    private fun wallBannerFor(block: BannerBlock) = when (block) {
        Blocks.WHITE_BANNER -> Blocks.WHITE_WALL_BANNER
        Blocks.ORANGE_BANNER -> Blocks.ORANGE_WALL_BANNER
        Blocks.MAGENTA_BANNER -> Blocks.MAGENTA_WALL_BANNER
        Blocks.LIGHT_BLUE_BANNER -> Blocks.LIGHT_BLUE_WALL_BANNER
        Blocks.YELLOW_BANNER -> Blocks.YELLOW_WALL_BANNER
        Blocks.LIME_BANNER -> Blocks.LIME_WALL_BANNER
        Blocks.PINK_BANNER -> Blocks.PINK_WALL_BANNER
        Blocks.GRAY_BANNER -> Blocks.GRAY_WALL_BANNER
        Blocks.LIGHT_GRAY_BANNER -> Blocks.LIGHT_GRAY_WALL_BANNER
        Blocks.CYAN_BANNER -> Blocks.CYAN_WALL_BANNER
        Blocks.PURPLE_BANNER -> Blocks.PURPLE_WALL_BANNER
        Blocks.BLUE_BANNER -> Blocks.BLUE_WALL_BANNER
        Blocks.BROWN_BANNER -> Blocks.BROWN_WALL_BANNER
        Blocks.GREEN_BANNER -> Blocks.GREEN_WALL_BANNER
        Blocks.RED_BANNER -> Blocks.RED_WALL_BANNER
        else -> Blocks.BLACK_WALL_BANNER
    }

    fun materializeWarbandSquad(level: ServerLevel, warband: PillagerWarband, campaign: PillagerCampaign, bannerSeed: Int, officerRecord: PillagerOfficer, player: ServerPlayer, x: Double, y: Double, z: Double): List<UUID> {
        val random = Random(campaign.loadoutSeed)
        val officer = createArchetypeMob(level, warband.archetype, WarbandRole.CAPTAIN, campaign.difficultySnapshot, random) ?: createOfficerEntity(level, officerRecord.officerClass)
        officer ?: return emptyList()
        prepareOfficer(officer, warband, campaign, bannerSeed, officerRecord, x, y, z, random, campaign.difficultySnapshot)
        level.addFreshEntity(officer)
        registerLiveMob(officer)
        val spawnedIds = mutableListOf<UUID>()
        spawnedIds += officer.uuid

        val memberCount = CampaignDifficultyRules.memberCountForDifficulty(campaign.difficultySnapshot)
        repeat(memberCount) { index ->
            val role = chooseFollowerRole(officerRecord, campaign.difficultySnapshot, index, memberCount, random)
            val mob: Mob = createArchetypeMob(level, warband.archetype, role, campaign.difficultySnapshot, random) ?: EntityType.PILLAGER.create(level) ?: return@repeat
            prepareFollower(mob, warband, campaign, role, x + random.nextDouble() * 3.0 - 1.5, y, z + random.nextDouble() * 3.0 - 1.5, random, campaign.difficultySnapshot)
            mob.target = player
            level.addFreshEntity(mob)
            registerLiveMob(mob)
            spawnedIds += mob.uuid
        }
        return spawnedIds
    }

    fun materializeWarlord(level: ServerLevel, warband: PillagerWarband, faction: PillagerFaction, warlord: PillagerOfficer, x: Double, y: Double, z: Double): UUID? {
        warband.warlordEntityId?.let { cachedId ->
            val cached = level.getEntity(cachedId) as? Mob
            if (cached != null &&
                cached.isAlive &&
                cached.persistentData.hasUUID(OFFICER_TAG) &&
                cached.persistentData.getUUID(OFFICER_TAG) == warlord.id &&
                cached.persistentData.getBoolean(BOSS_TAG)
            ) {
                registerLiveMob(cached)
                return cached.uuid
            }
        }
        if (hasLiveOfficerLeader(level, warlord.id)) return liveOfficerLeaderEntityIds[warlord.id]
        val random = Random(warband.id.mostSignificantBits xor warband.id.leastSignificantBits)
        val boss = createArchetypeMob(level, warband.archetype, WarbandRole.WARLORD, random) ?: createOfficerEntity(level, warlord.officerClass) ?: EntityType.VINDICATOR.create(level) ?: return null
        boss.moveTo(x, y, z, boss.yRot, boss.xRot)
        boss.setPersistenceRequired()
        boss.persistentData.putBoolean(BOSS_TAG, true)
        boss.persistentData.putBoolean(LEADER_TAG, true)
        boss.persistentData.putUUID(OFFICER_TAG, warlord.id)
        boss.persistentData.putUUID(FACTION_TAG, faction.id)
        boss.persistentData.putString(RANK_TAG, warlord.rank.name)
        boss.persistentData.putInt(ANCHOR_X_TAG, x.toInt())
        boss.persistentData.putInt(ANCHOR_Y_TAG, y.toInt())
        boss.persistentData.putInt(ANCHOR_Z_TAG, z.toInt())
        boss.setItemSlot(EquipmentSlot.HEAD, makeBaseBanner(faction.bannerSeed))
        applyArchetypeEquipment(boss, warband.archetype, WarbandRole.WARLORD, random, difficulty = 16)
        applyOfficerVisuals(boss, warlord)
        applyWarlordTuning(boss)
        level.addFreshEntity(boss)
        registerLiveMob(boss)
        syncOfficerVisuals(boss)
        return boss.uuid
    }

    fun keepSquadCohesive(level: ServerLevel, mob: Mob) {
        val tag = mob.persistentData
        if (!tag.hasUUID(CAMPAIGN_TAG) || tag.getBoolean(LEADER_TAG) || mob.target != null) return
        val officerId = tag.getUUID(OFFICER_TAG)
        val officerEntityId = liveOfficerLeaderEntityIds[officerId] ?: return
        val officer = level.getEntity(officerEntityId) as? Mob ?: return
        val dist = mob.distanceToSqr(officer)
        if (dist > 48.0 * 48.0) {
            mob.moveTo(officer.x + 0.5, officer.y, officer.z + 0.5, mob.yRot, mob.xRot)
            return
        }
        mob.navigation.moveTo(officer, 1.15)
    }

    fun pushOfficerTowardPlayer(level: ServerLevel, mob: Mob) {
        val tag = mob.persistentData
        if (!tag.getBoolean(LEADER_TAG)) return
        if (tag.getBoolean(BOSS_TAG)) return
        val nearest = level.players().minByOrNull { it.distanceToSqr(mob) } ?: return
        mob.target = nearest
        mob.navigation.moveTo(nearest, 1.15)
    }

    fun holdBossAtAnchor(mob: Mob) {
        val tag = mob.persistentData
        if (!tag.getBoolean(BOSS_TAG)) return
        mob.target = null
        mob.navigation.stop()
        if (mob.deltaMovement.lengthSqr() > 0.0) {
            mob.deltaMovement = mob.deltaMovement.multiply(0.0, 0.0, 0.0)
        }
        if (!tag.contains(ANCHOR_X_TAG) || !tag.contains(ANCHOR_Y_TAG) || !tag.contains(ANCHOR_Z_TAG)) return
        val anchorX = tag.getInt(ANCHOR_X_TAG) + 0.5
        val anchorY = tag.getInt(ANCHOR_Y_TAG).toDouble()
        val anchorZ = tag.getInt(ANCHOR_Z_TAG) + 0.5
        if (mob.distanceToSqr(anchorX, anchorY, anchorZ) > 0.0625) {
            mob.moveTo(anchorX, anchorY, anchorZ, mob.yRot, mob.xRot)
        }
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
        warband: PillagerWarband,
        campaign: PillagerCampaign,
        bannerSeed: Int,
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
        mob.setItemSlot(EquipmentSlot.HEAD, makeBaseBanner(bannerSeed))
        applyArchetypeEquipment(mob, warband.archetype, WarbandRole.CAPTAIN, random, difficulty)
        applyOfficerVisuals(mob, officerRecord)
        equipWeaponByPreference(mob, officerRecord, random, difficulty)
        applyArmorByPreference(mob, officerRecord, random, difficulty)
        applyEnchantmentsByPreference(mob, officerRecord, random, difficulty)
    }

    private fun prepareFollower(mob: Mob, warband: PillagerWarband, campaign: PillagerCampaign, role: WarbandRole, x: Double, y: Double, z: Double, random: Random, difficulty: Int) {
        mob.moveTo(x, y, z, mob.yRot, mob.xRot)
        mob.setPersistenceRequired()
        mob.persistentData.applyCampaignTags(campaign)
        mob.persistentData.putBoolean(LEADER_TAG, false)
        applyArchetypeEquipment(mob, warband.archetype, role, random, difficulty)
    }

    private fun CompoundTag.applyCampaignTags(campaign: PillagerCampaign) {
        putUUID(CAMPAIGN_TAG, campaign.id)
        putUUID(OFFICER_TAG, campaign.officerId)
        putUUID(FACTION_TAG, campaign.factionId)
    }

    private fun applyOfficerVisuals(entity: LivingEntity, officer: PillagerOfficer) {
        val color = colorForOfficer(officer.id)
        entity.persistentData.putString(OFFICER_NAME_TAG, officer.name)
        entity.persistentData.putString(OFFICER_TITLE_TAG, officer.title)
        entity.customName = Component.literal("${officer.name} ${officer.title}").withStyle(color.formatting)
        entity.isCustomNameVisible = true
        val scale = 1.2 + (officer.rank.ordinal * 0.1)
        entity.persistentData.putDouble(SCALE_TAG, scale)
    }

    fun syncOfficerVisuals(entity: LivingEntity) {
        val tag = entity.persistentData
        if (!tag.hasUUID(OFFICER_TAG)) return
        val name = tag.getString(OFFICER_NAME_TAG)
        val title = tag.getString(OFFICER_TITLE_TAG)
        if (name.isBlank() && title.isBlank()) return
        val color = colorForOfficer(tag.getUUID(OFFICER_TAG))
        val display = if (title.isBlank()) name else "$name $title"
        entity.customName = Component.literal(display).withStyle(color.formatting)
        entity.isCustomNameVisible = true
    }

    private fun createOfficerEntity(level: ServerLevel, officerClass: OfficerClass): Mob? = when (officerClass) {
        OfficerClass.PILLAGER -> EntityType.PILLAGER.create(level)
        OfficerClass.VINDICATOR -> EntityType.VINDICATOR.create(level)
        OfficerClass.WITCH -> EntityType.WITCH.create(level)
        OfficerClass.EVOKER -> EntityType.EVOKER.create(level)
        OfficerClass.ILLUSIONER -> EntityType.ILLUSIONER.create(level)
    }

    private fun chooseFollowerRole(officer: PillagerOfficer, difficulty: Int, memberIndex: Int, memberCount: Int, random: Random): WarbandRole {
        val base = PillagerWarbandArchetypeRules.chooseFollowerRole(difficulty, memberIndex, memberCount, random)
        return when (officer.combatStyle) {
            CombatStyle.HUNTER -> base
            CombatStyle.HARRIER -> if (memberIndex == memberCount - 1 || random.nextInt(3) == 0) WarbandRole.SPECIALIST else base
            CombatStyle.BUTCHER -> WarbandRole.LINE
            CombatStyle.HEXER -> if (difficulty >= 4 && random.nextBoolean()) WarbandRole.SPECIALIST else base
            CombatStyle.SABOTEUR -> if (random.nextInt(4) <= 1) WarbandRole.SPECIALIST else base
        }
    }

    private fun createArchetypeMob(level: ServerLevel, archetype: WarbandArchetype, role: WarbandRole, random: Random): Mob? {
        val id = PillagerWarbandArchetypeRules.chooseMob(archetype, role, difficulty = 16, random)
        return createMobById(level, id) ?: createMobById(level, fallbackMobId(archetype, role))
    }

    private fun createArchetypeMob(level: ServerLevel, archetype: WarbandArchetype, role: WarbandRole, difficulty: Int, random: Random): Mob? {
        val id = PillagerWarbandArchetypeRules.chooseMob(archetype, role, difficulty, random)
        return createMobById(level, id) ?: createMobById(level, fallbackMobId(archetype, role))
    }

    private fun createMobById(level: ServerLevel, id: ResourceLocation): Mob? {
        val entityType = ForgeRegistries.ENTITY_TYPES.getValue(id) ?: return null
        return entityType.create(level) as? Mob
    }

    private fun fallbackMobId(archetype: WarbandArchetype, role: WarbandRole): ResourceLocation {
        val id = when {
            archetype == WarbandArchetype.BLACKGUARD -> "minecraft:vindicator"
            archetype == WarbandArchetype.HEX && role != WarbandRole.LINE -> "minecraft:witch"
            archetype == WarbandArchetype.HEX -> "minecraft:pillager"
            else -> "minecraft:pillager"
        }
        return ResourceLocation.tryParse(id) ?: ResourceLocation("minecraft", "pillager")
    }

    private fun applyArchetypeEquipment(mob: Mob, archetype: WarbandArchetype, role: WarbandRole, random: Random, difficulty: Int) {
        val rules = PillagerWarbandArchetypeRules.rules(archetype, role)
        val weaponFamily = resolveWeaponFamily(ForgeRegistries.ENTITY_TYPES.getKey(mob.type), rules.weaponFamily)
        equipWeaponFamily(mob, weaponFamily, role, difficulty, random)
        applyArmorProfile(mob, rules.armorProfile)
        applyRoleEnchantments(mob, weaponFamily, role, difficulty)
    }

    internal fun resolveWeaponFamily(
        entityId: ResourceLocation?,
        requested: PillagerWarbandArchetypeRules.WeaponFamily,
    ): PillagerWarbandArchetypeRules.WeaponFamily {
        val supported = supportedWeaponFamily(entityId) ?: return requested
        return if (supported == requested) requested else supported
    }

    internal fun supportedWeaponFamily(entityId: ResourceLocation?): PillagerWarbandArchetypeRules.WeaponFamily? {
        val key = entityId?.toString() ?: return null
        return when (key) {
            in rangedEntityIds -> PillagerWarbandArchetypeRules.WeaponFamily.RANGED
            in meleeEntityIds -> PillagerWarbandArchetypeRules.WeaponFamily.MELEE
            in casterEntityIds -> PillagerWarbandArchetypeRules.WeaponFamily.CASTER
            else -> null
        }
    }

    private fun equipWeaponFamily(mob: Mob, family: PillagerWarbandArchetypeRules.WeaponFamily, role: WarbandRole, difficulty: Int, random: Random) {
        val stack = when (family) {
            PillagerWarbandArchetypeRules.WeaponFamily.RANGED -> ItemStack(if (random.nextBoolean()) Items.CROSSBOW else Items.BOW)
            PillagerWarbandArchetypeRules.WeaponFamily.MELEE -> ItemStack(if (random.nextBoolean()) tieredAxe(difficulty, random) else tieredSword(difficulty, random))
            PillagerWarbandArchetypeRules.WeaponFamily.CASTER -> if (role == WarbandRole.WARLORD) ItemStack(Items.NETHERITE_SWORD) else ItemStack.EMPTY
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, stack)
        mob.setItemInHand(InteractionHand.MAIN_HAND, stack)
        if (family == PillagerWarbandArchetypeRules.WeaponFamily.CASTER && role == WarbandRole.WARLORD) {
            mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.TOTEM_OF_UNDYING))
        }
    }

    private fun applyArmorProfile(mob: Mob, profile: PillagerWarbandArchetypeRules.ArmorProfile) {
        val pieces = when (profile) {
            PillagerWarbandArchetypeRules.ArmorProfile.LIGHT -> mapOf(
                EquipmentSlot.CHEST to Items.LEATHER_CHESTPLATE,
                EquipmentSlot.FEET to Items.LEATHER_BOOTS,
            )
            PillagerWarbandArchetypeRules.ArmorProfile.MEDIUM -> mapOf(
                EquipmentSlot.CHEST to Items.IRON_CHESTPLATE,
                EquipmentSlot.LEGS to Items.CHAINMAIL_LEGGINGS,
                EquipmentSlot.FEET to Items.IRON_BOOTS,
            )
            PillagerWarbandArchetypeRules.ArmorProfile.HEAVY -> mapOf(
                EquipmentSlot.CHEST to Items.IRON_CHESTPLATE,
                EquipmentSlot.LEGS to Items.IRON_LEGGINGS,
                EquipmentSlot.FEET to Items.IRON_BOOTS,
            )
            PillagerWarbandArchetypeRules.ArmorProfile.WARLORD -> mapOf(
                EquipmentSlot.CHEST to Items.NETHERITE_CHESTPLATE,
                EquipmentSlot.LEGS to Items.NETHERITE_LEGGINGS,
                EquipmentSlot.FEET to Items.NETHERITE_BOOTS,
            )
        }
        pieces.forEach { (slot, item) -> mob.setItemSlot(slot, ItemStack(item)) }
    }

    private fun applyRoleEnchantments(mob: Mob, family: PillagerWarbandArchetypeRules.WeaponFamily, role: WarbandRole, difficulty: Int) {
        val tier = if (role == WarbandRole.WARLORD) 4 else CampaignDifficultyRules.enchantTierForDifficulty(difficulty)
        if (tier <= 0) return
        val weapon = mob.getItemBySlot(EquipmentSlot.MAINHAND)
        if (!weapon.isEmpty) {
            when (family) {
                PillagerWarbandArchetypeRules.WeaponFamily.RANGED -> {
                    if (Enchantments.POWER_ARROWS.canEnchant(weapon)) weapon.enchant(Enchantments.POWER_ARROWS, tier)
                    if (Enchantments.QUICK_CHARGE.canEnchant(weapon)) weapon.enchant(Enchantments.QUICK_CHARGE, tier.coerceAtMost(3))
                }
                PillagerWarbandArchetypeRules.WeaponFamily.MELEE -> weapon.enchant(Enchantments.SHARPNESS, tier)
                PillagerWarbandArchetypeRules.WeaponFamily.CASTER -> weapon.enchant(Enchantments.UNBREAKING, tier)
            }
        }
        listOf(EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).forEach { slot ->
            val stack = mob.getItemBySlot(slot)
            if (!stack.isEmpty) {
                stack.enchant(Enchantments.ALL_DAMAGE_PROTECTION, tier.coerceAtMost(4))
                stack.enchant(Enchantments.UNBREAKING, tier.coerceAtMost(3))
            }
        }
    }

    private fun applyWarlordTuning(mob: Mob) {
        mob.getAttribute(Attributes.MAX_HEALTH)?.baseValue = 220.0
        mob.getAttribute(Attributes.ARMOR)?.baseValue = 24.0
        mob.getAttribute(Attributes.ARMOR_TOUGHNESS)?.baseValue = 10.0
        mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.baseValue = 0.85
        mob.health = mob.maxHealth
        mob.persistentData.putDouble(SCALE_TAG, 1.95)
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
