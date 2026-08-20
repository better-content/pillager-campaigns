package com.bettercontent.pillagercampaigns.system

import com.bettercontent.pillagercampaigns.PillagerCampaignsConfig
import com.bettercontent.pillagercampaigns.data.PillagerCampaign
import com.bettercontent.pillagercampaigns.data.PillagerFaction
import com.bettercontent.pillagercampaigns.data.PillagerOfficer
import com.bettercontent.pillagercampaigns.data.PillagerWarband
import com.bettercontent.pillagercampaigns.data.PillagerWorldData
import com.bettercontent.pillagercampaigns.data.LostAssetCache
import com.bettercontent.pillagercampaigns.data.PlannedCampaignMember
import com.gerald.warband.core.MemberManifest
import com.gerald.warband.core.CoreEffect
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ProjectileWeaponItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraftforge.registries.ForgeRegistries
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.ceil

object PillagerRuntime {
    const val CAMPAIGN_TAG = "PillagerCampaignId"
    const val OFFICER_TAG = "PillagerOfficerId"
    const val LEADER_TAG = "PillagerOfficerLeader"
    const val FACTION_TAG = "PillagerFactionId"
    const val BOSS_TAG = "PillagerFactionBoss"
    const val SCALE_TAG = "PillagerOfficerScale"
    const val THREAT_TAG = "PillagerThreat"
    const val WARBAND_TAG = "PillagerWarbandId"
    const val ENTITY_TYPE_TAG = "PillagerEntityType"
    const val MANIFEST_ID_TAG = "PillagerManifestId"
    const val GARRISON_ID_TAG = "PillagerGarrisonId"
    const val CARGO_TAG = "PillagerCargo"
    private const val TARGET_TAG = "PillagerTargetPlayer"
    private const val ORIGIN_X_TAG = "PillagerOriginChunkX"
    private const val ORIGIN_Z_TAG = "PillagerOriginChunkZ"
    private const val ANCHOR_X_TAG = "PillagerAnchorX"
    private const val ANCHOR_Y_TAG = "PillagerAnchorY"
    private const val ANCHOR_Z_TAG = "PillagerAnchorZ"
    private const val OFFICER_NAME_TAG = "PillagerOfficerName"
    private const val OFFICER_TITLE_TAG = "PillagerOfficerTitle"
    private val liveOfficerLeaderEntityIds = linkedMapOf<UUID, UUID>()
    private val liveCampaignMemberEntityIds = linkedMapOf<UUID, MutableSet<UUID>>()
    private val dropSlots = EquipmentSlot.values().toList()

    fun resetLiveIndexes() {
        liveOfficerLeaderEntityIds.clear()
        liveCampaignMemberEntityIds.clear()
        SquadRoutePlanner.reset()
    }

    fun registerLiveMob(mob: Mob) {
        val tag = mob.persistentData
        if (tag.hasUUID(CAMPAIGN_TAG)) liveCampaignMemberEntityIds.getOrPut(tag.getUUID(CAMPAIGN_TAG)) { linkedSetOf() }.add(mob.uuid)
        if (tag.hasUUID(OFFICER_TAG) && tag.getBoolean(LEADER_TAG)) liveOfficerLeaderEntityIds[tag.getUUID(OFFICER_TAG)] = mob.uuid
    }

    fun forgetLiveMob(mob: Mob) {
        val tag = mob.persistentData
        if (tag.hasUUID(CAMPAIGN_TAG)) liveCampaignMemberEntityIds[tag.getUUID(CAMPAIGN_TAG)]?.let { members ->
            members.remove(mob.uuid)
            if (members.isEmpty()) liveCampaignMemberEntityIds.remove(tag.getUUID(CAMPAIGN_TAG))
        }
        if (tag.hasUUID(OFFICER_TAG) && tag.getBoolean(LEADER_TAG)) liveOfficerLeaderEntityIds.remove(tag.getUUID(OFFICER_TAG), mob.uuid)
    }

    fun hasLiveOfficerLeader(level: ServerLevel, officerId: UUID): Boolean =
        liveOfficerLeaderEntityIds[officerId]?.let { (level.getEntity(it) as? Mob)?.isAlive } == true

    fun hasLiveCampaignMember(level: ServerLevel, campaignId: UUID): Boolean = countLiveMembers(level, liveCampaignMemberEntityIds[campaignId].orEmpty()) > 0

    fun countLiveMembers(level: ServerLevel, memberIds: Collection<UUID>): Int = memberIds.count { (level.getEntity(it) as? Mob)?.isAlive == true }

    fun liveManifestIds(level: ServerLevel, campaign: PillagerCampaign): Set<String> = campaign.squadMemberIds.asSequence()
        .mapNotNull { level.getEntity(it) as? Mob }
        .filter { it.isAlive }
        .map { it.persistentData.getString(MANIFEST_ID_TAG) }
        .filter(String::isNotBlank)
        .toCollection(linkedSetOf())

    fun dismissCampaign(level: ServerLevel, campaignId: UUID, memberIds: Collection<UUID>) {
        memberIds.mapNotNull { level.getEntity(it) as? Mob }.filter { it.persistentData.getUUID(CAMPAIGN_TAG) == campaignId }.forEach(Mob::discard)
        liveCampaignMemberEntityIds.remove(campaignId)
        SquadRoutePlanner.forget(campaignId)
    }

    fun snapshotCampaign(level: ServerLevel, campaign: PillagerCampaign): Int {
        val members = campaign.squadMemberIds.mapNotNull { level.getEntity(it) as? Mob }.filter { it.isAlive }
        if (members.isEmpty()) return 0
        snapshotAndDismiss(level, campaign, members)
        return members.size
    }

    /** Captures live facts into the persisted manifest without applying campaign rules. */
    fun syncLiveCampaignState(level: ServerLevel, campaign: PillagerCampaign) {
        campaign.squadMemberIds.mapNotNull { level.getEntity(it) as? Mob }.filter { it.isAlive }.forEach { mob ->
            val manifestId = mob.persistentData.getString(MANIFEST_ID_TAG)
            val member = campaign.plannedMembers.firstOrNull { it.manifestId == manifestId } ?: return@forEach
            member.healthFraction = (mob.health / mob.maxHealth.coerceAtLeast(1.0f)).toDouble().coerceIn(0.0, 1.0)
            member.cargo.clear()
            val cargo = mob.persistentData.getCompound(CARGO_TAG)
            cargo.allKeys.forEach { id -> cargo.getInt(id).takeIf { it > 0 }?.let { member.cargo[id] = it } }
            member.equipment?.let { original ->
                val stack = mob.getItemBySlot(TinkersArmoryOptimizer.equipmentSlot(ItemStack.of(original)))
                if (!stack.isEmpty) {
                    original.allKeys.toList().forEach(original::remove)
                    original.merge(stack.save(CompoundTag()))
                }
            }
        }
    }

    fun restoreSnapshots(level: ServerLevel, campaign: PillagerCampaign, pos: BlockPos): List<UUID> {
        if (campaign.memberSnapshots.isEmpty()) return emptyList()
        val restored = mutableListOf<Mob>()
        fun rollback(): List<UUID> {
            restored.forEach { mob -> forgetLiveMob(mob); mob.discard() }
            return emptyList()
        }
        for ((index, snapshot) in campaign.memberSnapshots.withIndex()) {
            val parsed = ResourceLocation.tryParse(snapshot.getString("id")) ?: return rollback()
            val type = ForgeRegistries.ENTITY_TYPES.getValue(parsed) ?: return rollback()
            val mob = type.create(level) as? Mob ?: return rollback()
            if (runCatching { mob.load(snapshot.copy()) }.isFailure) { mob.discard(); return rollback() }
            mob.moveTo(pos.x + .5 + index % 3, pos.y.toDouble(), pos.z + .5 + index / 3, mob.yRot, mob.xRot)
            if (!level.noCollision(mob, mob.boundingBox) || !level.addFreshEntity(mob)) { mob.discard(); return rollback() }
            registerLiveMob(mob)
            restored += mob
        }
        campaign.memberSnapshots.clear()
        campaign.squadMemberIds.clear()
        campaign.squadMemberIds += restored.map { it.uuid }
        return campaign.squadMemberIds
    }

    private fun snapshotAndDismiss(level: ServerLevel, campaign: PillagerCampaign, members: List<Mob>) {
        syncLiveCampaignState(level, campaign)
        campaign.memberSnapshots.clear()
        members.forEach { mob ->
            val snapshot = CompoundTag()
            if (mob.save(snapshot)) campaign.memberSnapshots += snapshot
            forgetLiveMob(mob)
            mob.discard()
        }
        liveCampaignMemberEntityIds.remove(campaign.id)
    }

    fun promoteSuccessor(level: ServerLevel, campaignId: UUID, officerId: UUID, manifestId: String): Boolean {
        val successor = liveCampaignMemberEntityIds[campaignId].orEmpty()
            .mapNotNull { level.getEntity(it) as? Mob }
            .firstOrNull {
                it.isAlive && it.persistentData.getString(MANIFEST_ID_TAG) == manifestId &&
                    !it.persistentData.getBoolean(LEADER_TAG)
            } ?: return false
        successor.persistentData.putBoolean(LEADER_TAG, true)
        liveOfficerLeaderEntityIds[officerId] = successor.uuid
        return true
    }

    fun liveThreat(level: ServerLevel, campaign: PillagerCampaign): Double = campaign.memberThreat.entries.sumOf { (id, threat) ->
        if ((level.getEntity(id) as? Mob)?.isAlive == true) threat else 0.0
    }

    fun materializeWarbandSquad(
        level: ServerLevel,
        data: PillagerWorldData,
        warband: PillagerWarband,
        campaign: PillagerCampaign,
        bannerSeed: Int,
        officerRecord: PillagerOfficer,
        player: ServerPlayer,
        effect: CoreEffect,
    ): List<UUID> {
        val manifests = effect.memberManifests.associateBy(MemberManifest::id)
        if (manifests.isEmpty() || effect.memberPlacements.map { it.memberId }.toSet() != manifests.keys) return emptyList()
        val result = mutableListOf<UUID>()
        val occupiedPositions = linkedSetOf<BlockPos>()
        effect.memberPlacements.forEachIndexed { index, placement ->
            val member = manifests.getValue(placement.memberId)
            val type = ResourceLocation.tryParse(member.recruitId)?.let(ForgeRegistries.ENTITY_TYPES::getValue) ?: return@forEachIndexed
            val mob = type.create(level) as? Mob ?: return@forEachIndexed
            val position = placement.position
            if (position.dimension != level.dimension().location().toString() || !level.hasChunk(position.x shr 4, position.z shr 4)) {
                mob.discard(); return@forEachIndexed
            }
            val surfacePos = PillagerSpawnPlacementRules.findMemberSurfacePos(
                level, position.x, position.z, occupiedPositions,
            ) ?: run { mob.discard(); return@forEachIndexed }
            occupiedPositions += surfacePos
            prepareCampaignMob(mob, warband, campaign, officerRecord, member.threat, index == 0,
                surfacePos.x + .5, surfacePos.y.toDouble(), surfacePos.z + .5)
            mob.health = (mob.maxHealth * member.healthFraction.coerceIn(0.0, 1.0).toFloat()).coerceAtLeast(1.0f)
            mob.persistentData.putString(MANIFEST_ID_TAG, member.id)
            mob.persistentData.put(CARGO_TAG, CompoundTag().also { cargo -> member.cargo.forEach(cargo::putInt) })
            if (index == 0) mob.setItemSlot(EquipmentSlot.HEAD, makeBanner(bannerSeed))
            mob.target = player
            val realizedEquipment = member.equipment?.let { equipment ->
                val equipmentTag = data.minecraftSidecar.itemSnapshots[equipment.id]?.singleOrNull()
                    ?: run { mob.discard(); return@forEachIndexed }
                val stack = ItemStack.of(equipmentTag)
                if (stack.isEmpty) { mob.discard(); return@forEachIndexed }
                Triple(stack, equipmentTag, equipment)
            }
            realizedEquipment?.let { (stack, _, _) ->
                mob.setItemSlot(TinkersArmoryOptimizer.equipmentSlot(stack), stack)
            }
            if (level.addFreshEntity(mob)) {
                registerLiveMob(mob)
                result += mob.uuid
                campaign.memberThreat[mob.uuid] = member.threat
                realizedEquipment?.let { (_, equipmentTag, _) ->
                    campaign.memberEquipment[mob.uuid] = equipmentTag.copy()
                }
            } else mob.discard()
        }
        return result
    }

    fun dropCampaignCargo(mob: Mob, campaign: PillagerCampaign) {
        val manifestId = mob.persistentData.getString(MANIFEST_ID_TAG)
        val member = campaign.plannedMembers.firstOrNull { it.manifestId == manifestId } ?: return
        cargoStacks(member.cargo).forEach(mob::spawnAtLocation)
        member.cargo.clear()
    }

    fun cacheAbstractMember(campaign: PillagerCampaign, member: PlannedCampaignMember) {
        val stacks = cargoStacks(member.cargo).mapTo(mutableListOf()) { it.save(CompoundTag()) }
        member.equipment?.copy()?.let(stacks::add)
        member.cargo.clear()
        if (stacks.isNotEmpty()) campaign.lostAssetCaches += LostAssetCache(campaign.currentChunkX, campaign.currentChunkZ, stacks)
    }

    fun releaseLoadedCaches(level: ServerLevel, campaign: PillagerCampaign): Int {
        var released = 0
        val iterator = campaign.lostAssetCaches.iterator()
        while (iterator.hasNext()) {
            val cache = iterator.next()
            if (!level.hasChunk(cache.chunkX, cache.chunkZ)) continue
            val x = (cache.chunkX shl 4) + 8
            val z = (cache.chunkZ shl 4) + 8
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            cache.stacks.map(ItemStack::of).filterNot(ItemStack::isEmpty).forEach { stack ->
                level.addFreshEntity(ItemEntity(level, x + .5, y + .5, z + .5, stack))
                released += stack.count
            }
            iterator.remove()
        }
        return released
    }

    private fun cargoStacks(cargo: Map<String, Int>): List<ItemStack> = cargo.entries.flatMap { (id, total) ->
        val item = ResourceLocation.tryParse(id)?.let(ForgeRegistries.ITEMS::getValue) ?: return@flatMap emptyList()
        val maximum = ItemStack(item).maxStackSize.coerceAtLeast(1)
        buildList {
            var remaining = total.coerceAtLeast(0)
            while (remaining > 0) {
                val count = minOf(remaining, maximum)
                add(ItemStack(item, count))
                remaining -= count
            }
        }
    }

    fun materializeWarlord(
        level: ServerLevel,
        warband: PillagerWarband,
        faction: PillagerFaction,
        warlord: PillagerOfficer,
        member: MemberManifest,
        x: Double,
        y: Double,
        z: Double,
    ): UUID? {
        warband.warlordEntityId?.let { id -> (level.getEntity(id) as? Mob)?.takeIf { it.isAlive }?.let { return it.uuid } }
        val recruitId = ResourceLocation.tryParse(member.recruitId) ?: return null
        val boss = ForgeRegistries.ENTITY_TYPES.getValue(recruitId)?.create(level) as? Mob ?: return null
        boss.moveTo(x, y, z, boss.yRot, boss.xRot)
        boss.setPersistenceRequired()
        boss.persistentData.putBoolean(BOSS_TAG, true)
        boss.persistentData.putBoolean(LEADER_TAG, true)
        boss.persistentData.putUUID(OFFICER_TAG, warlord.id)
        boss.persistentData.putUUID(FACTION_TAG, faction.id)
        boss.persistentData.putUUID(WARBAND_TAG, warband.id)
        boss.persistentData.putInt(ANCHOR_X_TAG, x.toInt())
        boss.persistentData.putInt(ANCHOR_Y_TAG, y.toInt())
        boss.persistentData.putInt(ANCHOR_Z_TAG, z.toInt())
        boss.persistentData.putDouble(THREAT_TAG, member.threat)
        boss.persistentData.putString(MANIFEST_ID_TAG, member.id)
        member.equipment?.let { equipment ->
            val saved = PillagerWorldData.get(level.server).minecraftSidecar.itemSnapshots[equipment.id]?.singleOrNull() ?: return null
            val stack = ItemStack.of(saved)
            if (stack.isEmpty) return null
            boss.setItemSlot(TinkersArmoryOptimizer.equipmentSlot(stack), stack)
        }
        boss.setItemSlot(EquipmentSlot.HEAD, makeBanner(faction.bannerSeed))
        applyOfficerVisuals(boss, warlord)
        guaranteeEquipmentDrops(boss)
        if (!level.noCollision(boss, boss.boundingBox) || !level.addFreshEntity(boss)) return null
        registerLiveMob(boss)
        return boss.uuid
    }

    fun materializeGarrison(
        level: ServerLevel,
        data: PillagerWorldData,
        warband: PillagerWarband,
        faction: PillagerFaction,
        warlord: PillagerOfficer,
        effect: CoreEffect,
    ): Set<String> {
        val garrisonId = effect.garrisonId ?: return emptySet()
        val placements = effect.memberPlacements.associateBy { it.memberId }
        val spawned = linkedSetOf<String>()
        effect.memberManifests.forEach memberLoop@{ member ->
            data.minecraftSidecar.entityIds[member.id]?.let { entityId ->
                if ((level.getEntity(entityId) as? Mob)?.isAlive == true) {
                    spawned += member.id
                    return@memberLoop
                }
                data.minecraftSidecar.entityIds.remove(member.id)
            }
            val position = placements[member.id]?.position ?: return@memberLoop
            if (position.dimension != level.dimension().location().toString() || !level.hasChunk(position.x shr 4, position.z shr 4)) return@memberLoop
            val recruitId = ResourceLocation.tryParse(member.recruitId) ?: return@memberLoop
            val mob = ForgeRegistries.ENTITY_TYPES.getValue(recruitId)?.create(level) as? Mob ?: return@memberLoop
            mob.moveTo(position.x + .5, position.y.toDouble(), position.z + .5, mob.yRot, mob.xRot)
            mob.setPersistenceRequired()
            mob.persistentData.putUUID(OFFICER_TAG, warlord.id)
            mob.persistentData.putUUID(FACTION_TAG, faction.id)
            mob.persistentData.putUUID(WARBAND_TAG, warband.id)
            mob.persistentData.putString(GARRISON_ID_TAG, garrisonId)
            mob.persistentData.putString(MANIFEST_ID_TAG, member.id)
            mob.persistentData.putDouble(THREAT_TAG, member.threat)
            member.equipment?.let { equipment ->
                val saved = data.minecraftSidecar.itemSnapshots[equipment.id]?.singleOrNull()
                if (saved == null) { mob.discard(); return@memberLoop }
                val stack = ItemStack.of(saved)
                if (stack.isEmpty) { mob.discard(); return@memberLoop }
                mob.setItemSlot(TinkersArmoryOptimizer.equipmentSlot(stack), stack)
            }
            guaranteeEquipmentDrops(mob)
            if (level.addFreshEntity(mob)) {
                registerLiveMob(mob)
                data.minecraftSidecar.entityIds[member.id] = mob.uuid
                spawned += member.id
            } else mob.discard()
        }
        return spawned
    }

    private fun prepareCampaignMob(mob: Mob, warband: PillagerWarband, campaign: PillagerCampaign, officer: PillagerOfficer, threat: Double, leader: Boolean, x: Double, y: Double, z: Double) {
        mob.moveTo(x, y, z, mob.yRot, mob.xRot)
        mob.setPersistenceRequired()
        mob.persistentData.putUUID(CAMPAIGN_TAG, campaign.id)
        mob.persistentData.putUUID(OFFICER_TAG, campaign.officerId)
        mob.persistentData.putUUID(FACTION_TAG, campaign.factionId)
        mob.persistentData.putUUID(TARGET_TAG, campaign.targetPlayerId)
        mob.persistentData.putBoolean(LEADER_TAG, leader)
        mob.persistentData.putInt(ORIGIN_X_TAG, warband.rallyChunkX)
        mob.persistentData.putInt(ORIGIN_Z_TAG, warband.rallyChunkZ)
        mob.persistentData.putDouble(THREAT_TAG, threat)
        ForgeRegistries.ENTITY_TYPES.getKey(mob.type)?.let { mob.persistentData.putString(ENTITY_TYPE_TAG, it.toString()) }
        if (leader) applyOfficerVisuals(mob, officer)
        guaranteeEquipmentDrops(mob)
    }

    fun keepSquadCohesive(level: ServerLevel, mob: Mob) {
        val tag = mob.persistentData
        if (!tag.hasUUID(CAMPAIGN_TAG) || tag.getBoolean(LEADER_TAG) || mob.target != null) return
        val leaderId = liveOfficerLeaderEntityIds[tag.getUUID(OFFICER_TAG)] ?: return
        val leader = level.getEntity(leaderId) as? Mob ?: return
        val policy = policyFor(mob)
        if (mob.distanceToSqr(leader) > policy.cohesionRadius * policy.cohesionRadius) mob.navigation.moveTo(leader, 1.15)
    }

    fun pushOfficerTowardPlayer(level: ServerLevel, mob: Mob) {
        val tag = mob.persistentData
        if (!tag.getBoolean(LEADER_TAG) || tag.getBoolean(BOSS_TAG) || !tag.hasUUID(TARGET_TAG)) return
        if (!WarbandTerritoryRules.contains(tag.getInt(ORIGIN_X_TAG), tag.getInt(ORIGIN_Z_TAG), mob.chunkPosition().x, mob.chunkPosition().z)) {
            mob.target = null
            mob.navigation.stop()
            return
        }
        val target = level.getPlayerByUUID(tag.getUUID(TARGET_TAG)) ?: return
        val policy = policyFor(mob)
        SquadRoutePlanner.pursue(level, mob, tag.getUUID(CAMPAIGN_TAG), target, policy.weaponRange, policy.cohesionRadius)
    }

    fun holdBossAtAnchor(mob: Mob) {
        val tag = mob.persistentData
        if (!tag.getBoolean(BOSS_TAG)) return
        val x = tag.getInt(ANCHOR_X_TAG) + 0.5
        val y = tag.getInt(ANCHOR_Y_TAG).toDouble()
        val z = tag.getInt(ANCHOR_Z_TAG) + 0.5
        if (mob.distanceToSqr(x, y, z) > 48.0 * 48.0) mob.navigation.moveTo(x, y, z, 1.15)
    }

    private fun policyFor(mob: Mob): RaidAiPolicy = raidAi {
        assignedTarget()
        territoryBoundary()
        nativeAttacks()
        actualWeaponRange(if (mob.mainHandItem.item is ProjectileWeaponItem) 15.0 else 2.5)
        cohesion(24.0)
        formulaicSuccessor()
        returnAfterIdle(PillagerCampaignsConfig.idleReturnTicks.get().toLong())
    }

    private fun applyOfficerVisuals(mob: Mob, officer: PillagerOfficer) {
        mob.persistentData.putString(OFFICER_NAME_TAG, officer.name)
        mob.persistentData.putString(OFFICER_TITLE_TAG, officer.title)
        mob.customName = Component.literal("${officer.name} ${officer.title}").withStyle(colorForOfficer(officer.id).formatting)
        mob.isCustomNameVisible = true
    }

    fun syncOfficerVisuals(mob: Mob) {
        val tag = mob.persistentData
        if (!tag.hasUUID(OFFICER_TAG)) return
        val display = listOf(tag.getString(OFFICER_NAME_TAG), tag.getString(OFFICER_TITLE_TAG)).filter(String::isNotBlank).joinToString(" ")
        if (display.isNotBlank()) mob.customName = Component.literal(display).withStyle(colorForOfficer(tag.getUUID(OFFICER_TAG)).formatting)
    }

    fun tickOfficerVisuals(level: ServerLevel, mob: Mob) {
        if (!mob.persistentData.hasUUID(OFFICER_TAG)) return
        val color = colorForOfficer(mob.persistentData.getUUID(OFFICER_TAG))
        level.sendParticles(DustParticleOptions(color.vector, 1.0f), mob.x, mob.y + 1.4, mob.z, 2, .18, .08, .18, .001)
    }

    fun placeFactionDeathBanner(level: ServerLevel, pos: BlockPos, bannerSeed: Int) {
        level.addFreshEntity(ItemEntity(level, pos.x + .5, pos.y + .5, pos.z + .5, makeBanner(bannerSeed)))
    }

    private fun makeBanner(seed: Int): ItemStack {
        val banners = listOf(Items.WHITE_BANNER, Items.ORANGE_BANNER, Items.MAGENTA_BANNER, Items.LIGHT_BLUE_BANNER, Items.YELLOW_BANNER, Items.LIME_BANNER, Items.PINK_BANNER, Items.GRAY_BANNER, Items.LIGHT_GRAY_BANNER, Items.CYAN_BANNER, Items.PURPLE_BANNER, Items.BLUE_BANNER, Items.BROWN_BANNER, Items.GREEN_BANNER, Items.RED_BANNER, Items.BLACK_BANNER)
        return ItemStack(banners[Math.floorMod(seed, banners.size)])
    }

    private fun guaranteeEquipmentDrops(mob: Mob) = dropSlots.forEach { mob.setDropChance(it, 1.0f) }
    internal fun guaranteedDropSlots(): List<EquipmentSlot> = dropSlots

    private data class OfficerColor(val formatting: ChatFormatting, val vector: Vector3f)
    private fun colorForOfficer(id: UUID): OfficerColor {
        val colors = listOf(
            OfficerColor(ChatFormatting.RED, Vector3f(1f, .2f, .2f)), OfficerColor(ChatFormatting.GOLD, Vector3f(1f, .75f, .1f)),
            OfficerColor(ChatFormatting.GREEN, Vector3f(.2f, .9f, .2f)), OfficerColor(ChatFormatting.AQUA, Vector3f(.2f, .85f, .95f)),
            OfficerColor(ChatFormatting.LIGHT_PURPLE, Vector3f(.95f, .35f, 1f)), OfficerColor(ChatFormatting.WHITE, Vector3f(.95f, .95f, .95f)),
        )
        return colors[Math.floorMod((id.mostSignificantBits xor id.leastSignificantBits).toInt(), colors.size)]
    }
}
