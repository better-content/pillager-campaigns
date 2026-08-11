package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.PlannedCampaignMember
import com.gerald.pillagercampaigns.data.CampaignRouteStep
import com.gerald.pillagercampaigns.data.LostAssetCache
import com.gerald.pillagercampaigns.engine.RecruitDefinition
import com.gerald.pillagercampaigns.engine.CapabilityVector
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
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
import java.util.Random
import java.util.UUID
import kotlin.math.ceil

object PillagerRuntime {
    internal data class PlannedCampaignManifest(
        val members: List<PlannedCampaignMember>,
        val route: List<CampaignRouteStep>,
    )
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
    const val CARGO_TAG = "PillagerCargo"
    private const val TARGET_TAG = "PillagerTargetPlayer"
    private const val ORIGIN_X_TAG = "PillagerOriginChunkX"
    private const val ORIGIN_Z_TAG = "PillagerOriginChunkZ"
    private const val ANCHOR_X_TAG = "PillagerAnchorX"
    private const val ANCHOR_Y_TAG = "PillagerAnchorY"
    private const val ANCHOR_Z_TAG = "PillagerAnchorZ"
    private const val OFFICER_NAME_TAG = "PillagerOfficerName"
    private const val OFFICER_TITLE_TAG = "PillagerOfficerTitle"
    private const val TACTIC_DURABILITY_TAG = "PillagerTacticDurability"
    private const val TACTIC_DAMAGE_TAG = "PillagerTacticDamage"
    private const val TACTIC_MOBILITY_TAG = "PillagerTacticMobility"
    private const val TACTIC_RANGE_TAG = "PillagerTacticRange"
    private const val TACTIC_CONTROL_TAG = "PillagerTacticControl"
    private val recruitTag = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation("pillagercampaigns", "recruits"))
    private val liveOfficerLeaderEntityIds = linkedMapOf<UUID, UUID>()
    private val liveCampaignMemberEntityIds = linkedMapOf<UUID, MutableSet<UUID>>()
    private val dropSlots = EquipmentSlot.values().toList()

    data class CoinRewardPlan(val itemId: String, val count: Int)
    enum class CoinRewardRole { FOLLOWER, CAPTAIN, WARLORD }
    enum class WithdrawalProgress { PHYSICAL, DEMATERIALIZED, ARRIVED }

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

    fun withdrawTowardHome(level: ServerLevel, campaign: PillagerCampaign, warband: PillagerWarband): WithdrawalProgress {
        if (campaign.memberSnapshots.isNotEmpty()) return WithdrawalProgress.DEMATERIALIZED
        val members = campaign.squadMemberIds.mapNotNull { level.getEntity(it) as? Mob }.filter { it.isAlive }
        if (members.isEmpty()) return if (campaign.memberThreat.isEmpty()) WithdrawalProgress.ARRIVED else WithdrawalProgress.DEMATERIALIZED
        val leader = members.firstOrNull { it.persistentData.getBoolean(LEADER_TAG) } ?: members.maxByOrNull { it.persistentData.getDouble(THREAT_TAG) }!!
        val current = leader.chunkPosition()
        campaign.currentChunkX = current.x
        campaign.currentChunkZ = current.z
        if (current.x == warband.rallyChunkX && current.z == warband.rallyChunkZ) {
            snapshotAndDismiss(level, campaign, members)
            return WithdrawalProgress.ARRIVED
        }
        val next = CampaignMath.stepToward(current.x, current.z, warband.rallyChunkX, warband.rallyChunkZ)
        val targetX = when {
            next.first > current.x -> (current.x shl 4) + 15.25
            next.first < current.x -> (current.x shl 4) + 0.75
            else -> (current.x shl 4) + 8.0
        }
        val targetZ = when {
            next.second > current.z -> (current.z shl 4) + 15.25
            next.second < current.z -> (current.z shl 4) + 0.75
            else -> (current.z shl 4) + 8.0
        }
        members.forEach { mob -> mob.target = null; mob.navigation.moveTo(targetX, mob.y, targetZ, 1.15) }
        if (!level.hasChunk(next.first, next.second) && members.all { it.distanceToSqr(targetX, it.y, targetZ) <= 3.0 * 3.0 }) {
            snapshotAndDismiss(level, campaign, members)
            campaign.currentChunkX = next.first
            campaign.currentChunkZ = next.second
            return WithdrawalProgress.DEMATERIALIZED
        }
        return WithdrawalProgress.PHYSICAL
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
        campaign.memberSnapshots.clear()
        members.forEach { mob ->
            val snapshot = CompoundTag()
            if (mob.save(snapshot)) campaign.memberSnapshots += snapshot
            forgetLiveMob(mob)
            mob.discard()
        }
        liveCampaignMemberEntityIds.remove(campaign.id)
    }

    fun promoteSuccessor(level: ServerLevel, campaignId: UUID, officerId: UUID): Boolean {
        val successor = liveCampaignMemberEntityIds[campaignId].orEmpty()
            .mapNotNull { level.getEntity(it) as? Mob }
            .filter { it.isAlive && !it.persistentData.getBoolean(LEADER_TAG) }
            .maxByOrNull { it.persistentData.getDouble(THREAT_TAG) + it.health / it.maxHealth.coerceAtLeast(1f) }
            ?: return false
        successor.persistentData.putBoolean(LEADER_TAG, true)
        liveOfficerLeaderEntityIds[officerId] = successor.uuid
        return true
    }

    fun liveThreat(level: ServerLevel, campaign: PillagerCampaign): Double = campaign.memberThreat.entries.sumOf { (id, threat) ->
        if ((level.getEntity(id) as? Mob)?.isAlive == true) threat else 0.0
    }

    fun minimumRecruitThreat(level: ServerLevel, warband: PillagerWarband): Double? = recruitDefinitions(level, warband)
        .minOfOrNull(RecruitDefinition::baseThreat)

    internal fun recruitDefinitions(level: ServerLevel, warband: PillagerWarband): List<RecruitDefinition> =
        ForgeRegistries.ENTITY_TYPES.tags()?.getTag(recruitTag)?.toList().orEmpty().mapNotNull { type ->
            val mob = type.create(level) as? Mob ?: return@mapNotNull null
            try {
                val id = ForgeRegistries.ENTITY_TYPES.getKey(type)?.toString() ?: return@mapNotNull null
                val measured = empiricalThreat(mob) * WarbandFormulaData.threatCorrections.getOrDefault(id, 1.0)
                val threat = warband.empiricalThreat.getOrDefault(id, measured).coerceAtLeast(1.0)
                PillagerEngineBridge.recruitDefinition(id, threat, mob)
            } finally {
                mob.discard()
            }
        }.sortedBy(RecruitDefinition::id)

    internal fun planCampaignSquad(
        level: ServerLevel,
        warband: PillagerWarband,
        officer: PillagerOfficer,
        budget: Double,
        sequence: Long,
        recruits: List<RecruitDefinition> = recruitDefinitions(level, warband),
    ): List<PlannedCampaignMember> {
        val armory = warband.armory.toList()
        val plan = PillagerEngineBridge.planSquad(
            warband, officer.preferenceGraph, budget, recruits, armory, sequence,
        )
        plan.mapNotNull(PillagerEngineBridge.PlannedLiveMember::equipmentIndex).toSet().sortedDescending()
            .forEach { index -> if (index in warband.armory.indices) warband.armory.removeAt(index) }
        return plan.map { member ->
            PlannedCampaignMember(
                ResourceLocation.tryParse(member.recruitId) ?: error("engine selected invalid recruit id ${member.recruitId}"),
                member.threat,
                member.equipmentIndex?.let { armory.getOrNull(it)?.copy() },
                member.cargo.toMutableMap(),
                member.manifestId,
                member.healthFraction,
            )
        }
    }

    internal fun planCampaignManifest(
        level: ServerLevel,
        warband: PillagerWarband,
        officer: PillagerOfficer,
        target: ServerPlayer,
        sequence: Long,
        now: Long,
        recruits: List<RecruitDefinition> = recruitDefinitions(level, warband),
    ): PlannedCampaignManifest? {
        val armory = warband.armory.toList()
        val plan = PillagerEngineBridge.planCampaign(
            level, warband, officer.preferenceGraph, recruits, armory, target.uuid,
            target.chunkPosition().x, target.chunkPosition().z, now, sequence,
        ) ?: return null
        plan.members.mapNotNull(PillagerEngineBridge.PlannedLiveMember::equipmentIndex).toSet().sortedDescending()
            .forEach { index -> if (index in warband.armory.indices) warband.armory.removeAt(index) }
        return PlannedCampaignManifest(
            plan.members.map { member ->
                PlannedCampaignMember(
                    ResourceLocation.tryParse(member.recruitId) ?: error("engine selected invalid recruit id ${member.recruitId}"),
                    member.threat,
                    member.equipmentIndex?.let { armory.getOrNull(it)?.copy() },
                    member.cargo.toMutableMap(), member.manifestId, member.healthFraction,
                )
            },
            plan.route.map { CampaignRouteStep(it.x, it.z) },
        )
    }

    fun materializeWarbandSquad(
        level: ServerLevel,
        warband: PillagerWarband,
        campaign: PillagerCampaign,
        bannerSeed: Int,
        officerRecord: PillagerOfficer,
        player: ServerPlayer,
        x: Double,
        y: Double,
        z: Double,
    ): List<UUID> {
        val random = Random(campaign.loadoutSeed)
        if (campaign.plannedMembers.isEmpty()) {
            campaign.plannedMembers += planCampaignSquad(level, warband, officerRecord, campaign.committedThreat, campaign.loadoutSeed)
        }
        val result = mutableListOf<UUID>()
        campaign.plannedMembers.forEachIndexed { index, member ->
            val type = ForgeRegistries.ENTITY_TYPES.getValue(member.recruitId) ?: return@forEachIndexed
            val mob = type.create(level) as? Mob ?: return@forEachIndexed
            prepareCampaignMob(mob, warband, campaign, officerRecord, member.threat, index == 0, x + random.nextDouble() * 4.0 - 2.0, y, z + random.nextDouble() * 4.0 - 2.0)
            mob.health = (mob.maxHealth * member.healthFraction.coerceIn(0.0, 1.0).toFloat()).coerceAtLeast(1.0f)
            mob.persistentData.putString(MANIFEST_ID_TAG, member.manifestId)
            mob.persistentData.put(CARGO_TAG, CompoundTag().also { cargo -> member.cargo.forEach(cargo::putInt) })
            if (index == 0) mob.setItemSlot(EquipmentSlot.HEAD, makeBanner(bannerSeed))
            mob.target = player
            if (level.addFreshEntity(mob)) {
                registerLiveMob(mob)
                result += mob.uuid
                campaign.memberThreat[mob.uuid] = member.threat
                member.equipment?.let { equipmentTag ->
                    val stack = ItemStack.of(equipmentTag)
                    mob.setItemSlot(TinkersArmoryOptimizer.equipmentSlot(stack), stack)
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

    fun refundCampaignCargo(warband: PillagerWarband, campaign: PillagerCampaign) {
        campaign.plannedMembers.forEach { member ->
            member.cargo.forEach { (id, count) -> warband.stockpile[id] = warband.stockpile.getOrDefault(id, 0) + count }
            member.cargo.clear()
        }
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

    fun materializeWarlord(level: ServerLevel, warband: PillagerWarband, faction: PillagerFaction, warlord: PillagerOfficer, x: Double, y: Double, z: Double): UUID? {
        warband.warlordEntityId?.let { id -> (level.getEntity(id) as? Mob)?.takeIf { it.isAlive }?.let { return it.uuid } }
        val choice = chooseFormulaMob(level, warband, warlord.preferenceGraph, Double.MAX_VALUE, Random(warband.id.mostSignificantBits xor warband.id.leastSignificantBits)) ?: return null
        val boss = choice.first
        boss.moveTo(x, y, z, boss.yRot, boss.xRot)
        boss.setPersistenceRequired()
        boss.persistentData.putBoolean(BOSS_TAG, true)
        boss.persistentData.putBoolean(LEADER_TAG, true)
        boss.persistentData.putUUID(OFFICER_TAG, warlord.id)
        boss.persistentData.putUUID(FACTION_TAG, faction.id)
        boss.persistentData.putInt(ANCHOR_X_TAG, x.toInt())
        boss.persistentData.putInt(ANCHOR_Y_TAG, y.toInt())
        boss.persistentData.putInt(ANCHOR_Z_TAG, z.toInt())
        boss.persistentData.putDouble(THREAT_TAG, choice.second)
        boss.setItemSlot(EquipmentSlot.HEAD, makeBanner(faction.bannerSeed))
        applyOfficerVisuals(boss, warlord)
        tuneWarlord(boss, warband)
        guaranteeEquipmentDrops(boss)
        if (!level.noCollision(boss, boss.boundingBox) || !level.addFreshEntity(boss)) return null
        registerLiveMob(boss)
        ensureGarrison(level, warband, faction, warlord, x, y, z)
        return boss.uuid
    }

    private fun ensureGarrison(level: ServerLevel, warband: PillagerWarband, faction: PillagerFaction, warlord: PillagerOfficer, x: Double, y: Double, z: Double) {
        val desired = FormulaicWarbandRules.escortCount(warband.reserve - warband.raidPool)
        val random = Random(warband.id.leastSignificantBits)
        repeat((desired - warband.garrisonThreat.size).coerceAtLeast(0)) { index ->
            val choice = chooseFormulaMob(level, warband, warlord.preferenceGraph, warband.reserve.toDouble(), random) ?: return@repeat
            val cost = ceil(choice.second).toInt().coerceAtLeast(1)
            if (cost > warband.reserve) return@repeat
            val mob = choice.first
            mob.moveTo(x + (index % 3) - 1.0, y, z + (index / 3) + 1.0, mob.yRot, mob.xRot)
            mob.setPersistenceRequired()
            mob.persistentData.putUUID(OFFICER_TAG, warlord.id)
            mob.persistentData.putUUID(FACTION_TAG, faction.id)
            mob.persistentData.putUUID(WARBAND_TAG, warband.id)
            mob.persistentData.putDouble(THREAT_TAG, choice.second)
            guaranteeEquipmentDrops(mob)
            if (level.addFreshEntity(mob)) {
                warband.reserve -= cost
                warband.garrisonThreat[mob.uuid] = choice.second
            }
        }
    }

    private fun chooseFormulaMob(level: ServerLevel, warband: PillagerWarband, preferences: Map<String, Double>, budget: Double, random: Random): Pair<Mob, Double>? {
        val entries = ForgeRegistries.ENTITY_TYPES.tags()?.getTag(recruitTag)?.toList().orEmpty()
        val candidates = entries.mapNotNull { type ->
            val mob = type.create(level) as? Mob ?: return@mapNotNull null
            val id = ForgeRegistries.ENTITY_TYPES.getKey(type)?.toString() ?: return@mapNotNull null
            val measured = empiricalThreat(mob) * WarbandFormulaData.threatCorrections.getOrDefault(id, 1.0)
            val threat = warband.empiricalThreat.getOrDefault(id, measured).coerceAtLeast(1.0)
            if (threat > budget && budget != Double.MAX_VALUE) { mob.discard(); return@mapNotNull null }
            PillagerEngineBridge.LiveRecruit(mob, PillagerEngineBridge.recruitDefinition(id, threat, mob))
        }
        val chosen = PillagerEngineBridge.chooseRecruit(warband, preferences, budget, candidates, random.nextLong()) ?: return null
        candidates.asSequence().filter { it !== chosen }.forEach { it.mob.discard() }
        return chosen.mob to chosen.definition.baseThreat
    }

    private fun empiricalThreat(mob: Mob): Double = (
        mob.getAttributeValue(Attributes.MAX_HEALTH) / 10.0 +
            mob.getAttributeValue(Attributes.ATTACK_DAMAGE) +
            mob.getAttributeValue(Attributes.ARMOR) / 4.0 +
            mob.getAttributeValue(Attributes.FOLLOW_RANGE) / 32.0
        ).coerceAtLeast(1.0)

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
        val preferences = officer.preferenceGraph + warband.preferences
        mob.persistentData.putDouble(TACTIC_DURABILITY_TAG, preferences["durability"] ?: 0.0)
        mob.persistentData.putDouble(TACTIC_DAMAGE_TAG, preferences["damage"] ?: 0.0)
        mob.persistentData.putDouble(TACTIC_MOBILITY_TAG, preferences["mobility"] ?: 0.0)
        mob.persistentData.putDouble(TACTIC_RANGE_TAG, preferences["range"] ?: 0.0)
        mob.persistentData.putDouble(TACTIC_CONTROL_TAG, preferences["control"] ?: 0.0)
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
        val measured = PillagerEngineBridge.recruitDefinition(
            tag.getString(ENTITY_TYPE_TAG), tag.getDouble(THREAT_TAG).coerceAtLeast(1.0), mob,
        ).capabilities
        val preferences = CapabilityVector(
            tag.getDouble(TACTIC_DURABILITY_TAG), tag.getDouble(TACTIC_DAMAGE_TAG), tag.getDouble(TACTIC_MOBILITY_TAG),
            tag.getDouble(TACTIC_RANGE_TAG), tag.getDouble(TACTIC_CONTROL_TAG),
        )
        SquadRoutePlanner.pursue(level, mob, tag.getUUID(CAMPAIGN_TAG), target, policy.weaponRange, measured, preferences, policy.cohesionRadius)
    }

    fun holdBossAtAnchor(mob: Mob) {
        val tag = mob.persistentData
        if (!tag.getBoolean(BOSS_TAG)) return
        val x = tag.getInt(ANCHOR_X_TAG) + 0.5
        val y = tag.getInt(ANCHOR_Y_TAG).toDouble()
        val z = tag.getInt(ANCHOR_Z_TAG) + 0.5
        if (mob.distanceToSqr(x, y, z) > 48.0 * 48.0) mob.navigation.moveTo(x, y, z, 1.15)
    }

    private fun tuneWarlord(mob: Mob, warband: PillagerWarband) {
        val scale = 1.0 + warband.aggression / 18.0
        mob.getAttribute(Attributes.MAX_HEALTH)?.baseValue = mob.getAttributeValue(Attributes.MAX_HEALTH) * scale
        mob.getAttribute(Attributes.ARMOR)?.baseValue = mob.getAttributeValue(Attributes.ARMOR) + warband.environment.mineralPotential * 8.0
        mob.health = mob.maxHealth
        mob.persistentData.putDouble(SCALE_TAG, 1.25 + warband.aggression / 40.0)
    }

    private fun policyFor(mob: Mob): RaidAiPolicy = raidAi {
        assignedTarget()
        territoryBoundary()
        nativeAttacks()
        actualWeaponRange(if (mob.mainHandItem.item is ProjectileWeaponItem) 15.0 else 2.5)
        cohesion(24.0)
        formulaicSuccessor()
        returnAfterIdle(FormulaicWarbandRules.IDLE_RETURN_TICKS)
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

    fun campaignCoinDropsForMob(mob: Mob, data: PillagerWorldData): List<ItemStack> {
        val threat = ceil(mob.persistentData.getDouble(THREAT_TAG)).toInt().coerceAtLeast(1)
        val role = when {
            mob.persistentData.getBoolean(BOSS_TAG) -> CoinRewardRole.WARLORD
            mob.persistentData.getBoolean(LEADER_TAG) -> CoinRewardRole.CAPTAIN
            else -> CoinRewardRole.FOLLOWER
        }
        return coinRewardPlan(threat, role).mapNotNull { plan -> ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(plan.itemId) ?: return@mapNotNull null)?.let { ItemStack(it, plan.count) } }
    }

    internal fun coinRewardPlan(threat: Int, role: CoinRewardRole): List<CoinRewardPlan> {
        val multiplier = when (role) { CoinRewardRole.FOLLOWER -> 1; CoinRewardRole.CAPTAIN -> 2; CoinRewardRole.WARLORD -> 4 }
        val tier = (threat / 4).coerceIn(0, 6)
        val ids = listOf("createdeco:copper_coin", "createdeco:zinc_coin", "createdeco:iron_coin", "createdeco:industrial_iron_coin", "createdeco:brass_coin", "createdeco:gold_coin", "createdeco:netherite_coin")
        return listOf(CoinRewardPlan(ids[tier], (multiplier + threat / 3).coerceAtLeast(1)))
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
