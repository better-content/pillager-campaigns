package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.PillagerPressureConfig
import com.gerald.pillagerpressure.PillagerPressureMod
import com.gerald.pillagerpressure.data.*
import com.gerald.pillagerpressure.util.PillagerIdentity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object PillagerRuntime {
    const val FACTION_TAG = "PillagerPressureFaction"
    const val BASE_TAG = "PillagerPressureBase"
    const val CAMPAIGN_TAG = "PillagerPressureCampaign"
    const val OFFICER_TAG = "PillagerPressureOfficer"

    fun eligible(player: ServerPlayer): Boolean {
        if (PillagerPressureConfig.skipSpectatorPlayers.get() && player.isSpectator) return false
        if (!PillagerPressureConfig.allowCreativePlayers.get() && player.isCreative) return false
        if (PillagerPressureConfig.overworldOnly.get() && player.serverLevel().dimension() != Level.OVERWORLD) return false
        return true
    }

    fun chooseSpecial(level: ServerLevel): String? {
        val candidates = PillagerPressureConfig.specialIllagers.get()
            .mapNotNull { it as? String }
            .filter { ForgeRegistries.ENTITY_TYPES.containsKey(ResourceLocation.parse(it)) }
        if (candidates.isEmpty()) return null
        return candidates[level.random.nextInt(candidates.size)]
    }

    fun spawnSquad(
        level: ServerLevel,
        data: PillagerWorldData,
        pos: BlockPos,
        target: ServerPlayer?,
        base: PillagerBase?,
        faction: PillagerFaction?,
        campaign: PillagerCampaign?,
        officer: PillagerOfficer?,
        pillagers: Int,
        specials: Int,
        leader: Boolean,
    ): Int {
        var spawned = 0
        val patrolTarget = target?.blockPosition() ?: pos.offset(level.random.nextInt(64) - 32, 0, level.random.nextInt(64) - 32)
        if (leader) {
            if (spawnMob(level, "minecraft:pillager", jitter(pos, level, 3), target, patrolTarget, base, faction, campaign, officer, true)) spawned++
        }
        repeat(pillagers.coerceAtLeast(0)) {
            if (spawnMob(level, "minecraft:pillager", jitter(pos, level, 5), target, patrolTarget, base, faction, campaign, officer, false)) spawned++
        }
        repeat(specials.coerceAtLeast(0)) {
            val id = chooseSpecial(level) ?: return@repeat
            if (spawnMob(level, id, jitter(pos, level, 6), target, patrolTarget, base, faction, campaign, officer, false)) spawned++
        }
        if (spawned > 0) data.markChanged()
        return spawned
    }

    fun spawnMob(
        level: ServerLevel,
        entityId: String,
        pos: BlockPos,
        target: ServerPlayer?,
        patrolTarget: BlockPos,
        base: PillagerBase?,
        faction: PillagerFaction?,
        campaign: PillagerCampaign?,
        officer: PillagerOfficer?,
        leader: Boolean,
    ): Boolean {
        val id = ResourceLocation.tryParse(entityId) ?: return false
        val type = ForgeRegistries.ENTITY_TYPES.getValue(id) as? EntityType<*> ?: return false
        val entity = type.create(level) as? Mob ?: return false
        entity.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360.0f, 0.0f)
        runCatching { entity.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null) }
            .onFailure { PillagerPressureMod.LOGGER.debug("finalizeSpawn failed for {} at {}", entityId, pos, it) }
        entity.persistentData.putBoolean(PillagerPressureMod.PATROL_TAG, true)
        faction?.let { entity.persistentData.putUUID(FACTION_TAG, it.id) }
        base?.let { entity.persistentData.putUUID(BASE_TAG, it.id) }
        campaign?.let { entity.persistentData.putUUID(CAMPAIGN_TAG, it.id) }
        officer?.let {
            entity.persistentData.putUUID(OFFICER_TAG, it.id)
            entity.customName = Component.literal(it.displayName())
            entity.isCustomNameVisible = true
        }
        target?.let {
            entity.persistentData.putUUID("BoundToMatterPressureTarget", it.uuid)
            if (PillagerPressureConfig.targetPlayerImmediately.get()) entity.target = it
        }
        if (PillagerPressureConfig.persistentPatrolMobs.get()) entity.setPersistenceRequired()
        if (entity is PatrollingMonster) {
            entity.patrolTarget = patrolTarget
            if (leader) {
                entity.isPatrolLeader = true
                faction?.let { entity.setItemSlot(EquipmentSlot.HEAD, PillagerIdentity.bannerStack(it)) }
            }
        }
        return level.addFreshEntity(entity)
    }

    fun chooseSpawnPos(level: ServerLevel, center: BlockPos): BlockPos? {
        val minRadius = min(PillagerPressureConfig.minRadius.get(), PillagerPressureConfig.maxRadius.get()).coerceAtLeast(8)
        val maxRadius = max(PillagerPressureConfig.minRadius.get(), PillagerPressureConfig.maxRadius.get()).coerceAtLeast(minRadius)
        repeat(PillagerPressureConfig.spawnAttempts.get()) {
            val angle = level.random.nextDouble() * Math.PI * 2.0
            val radius = if (maxRadius == minRadius) minRadius else level.random.nextInt(maxRadius - minRadius + 1) + minRadius
            val x = center.x + (cos(angle) * radius).toInt()
            val z = center.z + (sin(angle) * radius).toInt()
            val probe = BlockPos(x, center.y, z)
            if (!level.hasChunkAt(probe)) return@repeat
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            val pos = BlockPos(x, y, z)
            if (validSpawnSurface(level, pos)) return pos
        }
        return null
    }

    fun validSpawnSurface(level: ServerLevel, pos: BlockPos): Boolean {
        if (pos.y <= level.minBuildHeight + 1 || pos.y >= level.maxBuildHeight - 2) return false
        val state = level.getBlockState(pos)
        val above = level.getBlockState(pos.above())
        val below = level.getBlockState(pos.below())
        if (!isOpen(level, pos, state) || !isOpen(level, pos.above(), above)) return false
        if (below.isAir || below.fluidState.isSource) return false
        if (state.fluidState.isSource || above.fluidState.isSource) return false
        return below.isCollisionShapeFullBlock(level, pos.below())
    }

    fun countActivePatrolMobs(level: ServerLevel, center: BlockPos): Int {
        val radius = PillagerPressureConfig.activeCheckRadius.get().toDouble()
        val box = AABB(center).inflate(radius, 96.0, radius)
        return level.getEntitiesOfClass(Mob::class.java, box) { it.isAlive && it.persistentData.getBoolean(PillagerPressureMod.PATROL_TAG) }.size
    }

    fun placeFactionFlags(level: ServerLevel, faction: PillagerFaction, center: BlockPos, count: Int): Int {
        if (count <= 0) return 0
        var placed = 0
        repeat(count) {
            val angle = level.random.nextDouble() * Math.PI * 2.0
            val r = 2 + level.random.nextInt(6)
            val x = center.x + (cos(angle) * r).toInt()
            val z = center.z + (sin(angle) * r).toInt()
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            val pos = BlockPos(x, y, z)
            if (level.getBlockState(pos).isAir && !level.getBlockState(pos.below()).isAir) {
                level.setBlockAndUpdate(pos, BannerBlock.byColor(faction.baseDyeColor()).defaultBlockState())
                placed++
            }
        }
        return placed
    }

    fun baseMap(level: ServerLevel, base: PillagerBase, faction: PillagerFaction): ItemStack {
        val stack = MapItem.create(level, base.center.x, base.center.z, 2.toByte(), true, true)
        stack.hoverName = Component.literal("Map to ${faction.name}")
        return stack
    }

    private fun isOpen(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean = state.isAir || state.getCollisionShape(level, pos).isEmpty

    private fun jitter(pos: BlockPos, level: ServerLevel, radius: Int): BlockPos {
        val dx = level.random.nextInt(radius * 2 + 1) - radius
        val dz = level.random.nextInt(radius * 2 + 1) - radius
        val x = pos.x + dx
        val z = pos.z + dz
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        val candidate = BlockPos(x, y, z)
        return if (validSpawnSurface(level, candidate)) candidate else pos
    }
}
