package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.PillagerPressureConfig
import com.gerald.pillagerpressure.PillagerPressureMod
import com.gerald.pillagerpressure.data.*
import com.gerald.pillagerpressure.util.PillagerIdentity
import net.minecraft.core.BlockPos
import net.minecraft.ChatFormatting
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LadderBlock
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
    const val OBJECTIVE_KIND_TAG = "PillagerPressureObjective"
    const val OBJECTIVE_X_TAG = "PillagerPressureObjectiveX"
    const val OBJECTIVE_Y_TAG = "PillagerPressureObjectiveY"
    const val OBJECTIVE_Z_TAG = "PillagerPressureObjectiveZ"
    const val ENGINEER_NEXT_TICK_TAG = "PillagerPressureEngineerNextTick"
    const val ENGINEER_PLACED_COUNT_TAG = "PillagerPressureEngineerPlaced"
    const val SQUAD_LEADER_TAG = "PillagerPressureSquadLeader"

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
        val objective = PillagerObjectiveRules.objectiveFor(campaign, target, pos)
        var leaderEntity: Mob? = null
        if (leader) {
            leaderEntity = createMob(level, "minecraft:pillager", jitter(pos, level, 3), target, objective, base, faction, campaign, officer, true, null)
            if (leaderEntity != null && level.addFreshEntity(leaderEntity)) spawned++
        }
        val leaderId = leaderEntity?.uuid
        val manifest = squadManifest(level, officer, pillagers, specials)
        manifest.forEach { (entityId, count) ->
            repeat(count.coerceAtLeast(0)) {
                if (spawnMob(level, entityId, jitter(pos, level, 6), target, objective, base, faction, campaign, officer, false, leaderId)) spawned++
            }
        }
        if (spawned > 0) data.markChanged()
        return spawned
    }

    fun spawnMob(
        level: ServerLevel,
        entityId: String,
        pos: BlockPos,
        target: ServerPlayer?,
        objective: PillagerObjectiveRules.Objective,
        base: PillagerBase?,
        faction: PillagerFaction?,
        campaign: PillagerCampaign?,
        officer: PillagerOfficer?,
        leader: Boolean,
        squadLeaderId: java.util.UUID? = null,
    ): Boolean {
        val entity = createMob(level, entityId, pos, target, objective, base, faction, campaign, officer, leader, squadLeaderId) ?: return false
        return level.addFreshEntity(entity)
    }

    private fun createMob(
        level: ServerLevel,
        entityId: String,
        pos: BlockPos,
        target: ServerPlayer?,
        objective: PillagerObjectiveRules.Objective,
        base: PillagerBase?,
        faction: PillagerFaction?,
        campaign: PillagerCampaign?,
        officer: PillagerOfficer?,
        leader: Boolean,
        squadLeaderId: java.util.UUID?,
    ): Mob? {
        val id = ResourceLocation.tryParse(entityId) ?: return null
        val type = ForgeRegistries.ENTITY_TYPES.getValue(id) ?: return null
        val entity = type.create(level) as? Mob ?: return null
        entity.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360.0f, 0.0f)
        runCatching { entity.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null) }
            .onFailure { PillagerPressureMod.LOGGER.debug("finalizeSpawn failed for {} at {}", entityId, pos, it) }
        entity.persistentData.putBoolean(PillagerPressureMod.PATROL_TAG, true)
        faction?.let { entity.persistentData.putUUID(FACTION_TAG, it.id) }
        base?.let { entity.persistentData.putUUID(BASE_TAG, it.id) }
        campaign?.let { entity.persistentData.putUUID(CAMPAIGN_TAG, it.id) }
        entity.persistentData.putString(OBJECTIVE_KIND_TAG, objective.kind)
        entity.persistentData.putInt(OBJECTIVE_X_TAG, objective.pos.x)
        entity.persistentData.putInt(OBJECTIVE_Y_TAG, objective.pos.y)
        entity.persistentData.putInt(OBJECTIVE_Z_TAG, objective.pos.z)
        if (leader) officer?.let {
            entity.persistentData.putUUID(OFFICER_TAG, it.id)
            entity.customName = Component.literal(it.displayName()).withStyle(nameColor(it))
            entity.isCustomNameVisible = true
            applyOfficerSignal(level, entity, it, faction)
        }
        if (!leader && squadLeaderId != null) entity.persistentData.putUUID(SQUAD_LEADER_TAG, squadLeaderId)
        target?.let {
            entity.persistentData.putUUID("BoundToMatterPressureTarget", it.uuid)
            if (PillagerPressureConfig.targetPlayerImmediately.get()) entity.target = it
        }
        if (PillagerPressureConfig.persistentPatrolMobs.get()) entity.setPersistenceRequired()
        if (entity is PatrollingMonster) {
            entity.patrolTarget = objective.pos
        }
        return entity
    }

    private fun squadManifest(level: ServerLevel, officer: PillagerOfficer?, pillagers: Int, specials: Int): Map<String, Int> {
        if (officer == null) {
            val fallback = linkedMapOf(SquadCompositionRules.PILLAGER to pillagers.coerceAtLeast(0))
            repeat(specials.coerceAtLeast(0)) { chooseSpecial(level)?.let { fallback[it] = (fallback[it] ?: 0) + 1 } }
            return fallback
        }
        val planned = SquadCompositionRules.plan(
            doctrine = officer.doctrine,
            rank = officer.rank,
            engineeringTalent = OfficerEngineeringRules.talentFor(officer),
            pressure = SquadCompositionPressure.fromGenes(officer.genes),
        )
        return SquadCompositionRules.fallbackManifest(planned.manifest, availableEntityIds()).manifest
    }

    private fun availableEntityIds(): Set<String> = ForgeRegistries.ENTITY_TYPES.keys.map { it.toString() }.toSet()

    private fun applyOfficerSignal(level: ServerLevel, entity: Mob, officer: PillagerOfficer, faction: PillagerFaction?) {
        val rankHealth = when (officer.rank) {
            OfficerRank.SCOUT -> 1.0
            OfficerRank.CAPTAIN -> 1.25
            OfficerRank.LIEUTENANT -> 1.5
            OfficerRank.WARLORD -> 1.9
            OfficerRank.BANNERLORD -> 2.2
        }
        entity.getAttribute(Attributes.MAX_HEALTH)?.let {
            it.baseValue *= rankHealth
            entity.health = entity.maxHealth
        }
        applyOfficerLoadout(entity, officer, faction)
        if (officer.rank == OfficerRank.WARLORD || officer.rank == OfficerRank.BANNERLORD) entity.setGlowingTag(true)

        officer.affixes.forEach { affix ->
            when (affix) {
                OfficerAffix.SWIFT -> {
                    entity.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 60 * 20, 2, true, true))
                    entity.getAttribute(Attributes.MOVEMENT_SPEED)?.let { it.baseValue *= 1.45 }
                    level.sendParticles(ParticleTypes.CLOUD, entity.x, entity.y + 1.0, entity.z, 16, 0.4, 0.7, 0.4, 0.05)
                }
                OfficerAffix.LONGSHOT -> {
                    level.sendParticles(ParticleTypes.CRIT, entity.x, entity.y + 1.2, entity.z, 18, 0.5, 0.5, 0.5, 0.08)
                }
                OfficerAffix.IRONBOUND -> {
                    entity.addEffect(MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 60 * 20, 1, true, true))
                    entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.let { it.baseValue = (it.baseValue + 0.6).coerceAtMost(1.0) }
                    level.sendParticles(ParticleTypes.ASH, entity.x, entity.y + 1.0, entity.z, 20, 0.5, 0.8, 0.5, 0.02)
                }
                OfficerAffix.WITCH_TOUCHED -> {
                    entity.addEffect(MobEffectInstance(MobEffects.REGENERATION, 20 * 60 * 20, 0, true, true))
                    level.sendParticles(ParticleTypes.WITCH, entity.x, entity.y + 1.0, entity.z, 24, 0.5, 0.9, 0.5, 0.05)
                }
                OfficerAffix.BANNERED -> {
                    faction?.let { entity.setItemSlot(EquipmentSlot.HEAD, PillagerIdentity.bannerStack(it)) }
                    entity.addEffect(MobEffectInstance(MobEffects.GLOWING, 20 * 60 * 20, 0, true, false))
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, entity.x, entity.y + 1.5, entity.z, 12, 0.5, 0.5, 0.5, 0.02)
                }
                OfficerAffix.ASHEN -> {
                    entity.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60 * 20, 0, true, true))
                    level.sendParticles(ParticleTypes.FLAME, entity.x, entity.y + 1.0, entity.z, 18, 0.5, 0.8, 0.5, 0.03)
                    level.sendParticles(ParticleTypes.SMOKE, entity.x, entity.y + 1.0, entity.z, 18, 0.5, 0.8, 0.5, 0.02)
                }
                OfficerAffix.BEAST_CALLER -> {
                    level.sendParticles(ParticleTypes.ANGRY_VILLAGER, entity.x, entity.y + 1.4, entity.z, 10, 0.5, 0.5, 0.5, 0.02)
                }
                OfficerAffix.GRAVE_MARKED -> {
                    entity.addEffect(MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 60 * 20, 0, true, true))
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, entity.x, entity.y + 1.0, entity.z, 24, 0.5, 0.8, 0.5, 0.02)
                }
            }
        }
    }

    private fun applyOfficerLoadout(entity: Mob, officer: PillagerOfficer, faction: PillagerFaction?) {
        val loadout = OfficerLoadoutRules.forOfficer(officer)
        itemStack(loadout.armor.helmet)?.let { entity.setItemSlot(EquipmentSlot.HEAD, it) }
        itemStack(loadout.armor.chestplate)?.let { entity.setItemSlot(EquipmentSlot.CHEST, it) }
        itemStack(loadout.armor.leggings)?.let { entity.setItemSlot(EquipmentSlot.LEGS, it) }
        itemStack(loadout.armor.boots)?.let { entity.setItemSlot(EquipmentSlot.FEET, it) }
        itemStack(loadout.mainhand)?.let { entity.setItemSlot(EquipmentSlot.MAINHAND, it) }
        val offhand = if (loadout.offhand?.endsWith("_banner") == true && faction != null) PillagerIdentity.bannerStack(faction) else loadout.offhand?.let { itemStack(it) }
        offhand?.let { entity.setItemSlot(EquipmentSlot.OFFHAND, it) }
        if (offhand?.item !is net.minecraft.world.item.BannerItem) faction?.let { entity.setItemSlot(EquipmentSlot.HEAD, PillagerIdentity.bannerStack(it)) }
    }

    private fun itemStack(itemId: String): ItemStack? {
        val id = ResourceLocation.tryParse(itemId) ?: return null
        val item = ForgeRegistries.ITEMS.getValue(id) ?: return null
        if (item == Items.AIR) return null
        return ItemStack(item)
    }

    private fun nameColor(officer: PillagerOfficer): ChatFormatting = when {
        OfficerAffix.GRAVE_MARKED in officer.affixes -> ChatFormatting.DARK_RED
        OfficerAffix.WITCH_TOUCHED in officer.affixes -> ChatFormatting.DARK_PURPLE
        OfficerAffix.SWIFT in officer.affixes -> ChatFormatting.AQUA
        OfficerAffix.IRONBOUND in officer.affixes -> ChatFormatting.GRAY
        OfficerAffix.BANNERED in officer.affixes -> ChatFormatting.GOLD
        officer.rank == OfficerRank.BANNERLORD -> ChatFormatting.RED
        officer.rank == OfficerRank.WARLORD -> ChatFormatting.DARK_RED
        else -> ChatFormatting.YELLOW
    }

    fun chooseSpawnPos(level: ServerLevel, center: BlockPos): BlockPos? {
        val minRadius = min(PillagerPressureConfig.minRadius.get(), PillagerPressureConfig.maxRadius.get()).coerceAtLeast(8)
        val maxRadius = max(PillagerPressureConfig.minRadius.get(), PillagerPressureConfig.maxRadius.get()).coerceAtLeast(minRadius)
        return PillagerSpawnPlacementRules.chooseFarthest(
            center = center,
            minRadius = minRadius,
            maxRadius = maxRadius,
            isLoaded = { probe -> level.hasChunkAt(probe) },
            isValid = { probe ->
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.x, probe.z)
                validSpawnSurface(level, BlockPos(probe.x, y, probe.z))
            },
        )?.let { probe ->
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.x, probe.z)
            BlockPos(probe.x, y, probe.z)
        }
    }

    fun chooseForcedSpawnPos(level: ServerLevel, center: BlockPos): BlockPos? =
        PillagerSpawnPlacementRules.chooseForced(
            center = center,
            normalMinRadius = min(PillagerPressureConfig.minRadius.get(), PillagerPressureConfig.maxRadius.get()).coerceAtLeast(8),
            normalMaxRadius = max(PillagerPressureConfig.minRadius.get(), PillagerPressureConfig.maxRadius.get()).coerceAtLeast(8),
            fallbackMinRadius = 8,
            fallbackMaxRadius = 32,
            isLoaded = { probe -> level.hasChunkAt(probe) },
            isValid = { probe ->
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.x, probe.z)
                validSpawnSurface(level, BlockPos(probe.x, y, probe.z))
            },
        )?.let { probe ->
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.x, probe.z)
            BlockPos(probe.x, y, probe.z)
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

    fun pullFollowerTowardOfficer(level: ServerLevel, mob: Mob): Boolean {
        val tag = mob.persistentData
        if (!tag.hasUUID(SQUAD_LEADER_TAG) || tag.hasUUID(OFFICER_TAG)) return false
        val leaderId = tag.getUUID(SQUAD_LEADER_TAG)
        val box = AABB(mob.blockPosition()).inflate(64.0, 32.0, 64.0)
        val leader = level.getEntitiesOfClass(Mob::class.java, box) { it.uuid == leaderId && it.isAlive }.firstOrNull() ?: return false
        val dist = mob.distanceToSqr(leader)
        if (mob.target == null) mob.target = leader.target
        if (SquadCohesionRules.shouldPullToLeader(dist)) {
            mob.navigation.moveTo(leader, SquadCohesionRules.moveSpeed(dist))
            return true
        }
        return false
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

    fun tryOfficerEngineering(level: ServerLevel, data: PillagerWorldData, mob: Mob): Boolean {
        if (!PillagerPressureConfig.officerEngineeringEnabled.get()) return false
        val tag = mob.persistentData
        if (!tag.hasUUID(OFFICER_TAG)) return false
        val now = level.gameTime
        if (tag.getLong(ENGINEER_NEXT_TICK_TAG) > now) return false
        if (tag.getInt(ENGINEER_PLACED_COUNT_TAG) >= PillagerPressureConfig.officerEngineeringMaxBlocks.get()) return false
        val officer = data.officers[tag.getUUID(OFFICER_TAG)] ?: return false
        val talent = OfficerEngineeringRules.talentFor(officer)
        if (talent == OfficerEngineeringTalent.NONE) return false
        val objective = objectiveFromTag(tag) ?: return false
        val direction = directionToward(mob.blockPosition(), objective) ?: return false
        val placed = tryBridge(level, data, mob.blockPosition(), direction, talent, now) ||
            tryLadder(level, data, mob.blockPosition(), direction, talent, now)
        tag.putLong(ENGINEER_NEXT_TICK_TAG, now + PillagerPressureConfig.officerEngineeringCooldownTicks.get())
        if (placed) tag.putInt(ENGINEER_PLACED_COUNT_TAG, tag.getInt(ENGINEER_PLACED_COUNT_TAG) + 1)
        return placed
    }

    fun cleanupEngineeredBlocks(level: ServerLevel, data: PillagerWorldData): Int {
        val ttl = PillagerPressureConfig.officerEngineeringTtlTicks.get().toLong()
        val now = level.gameTime
        var removed = 0
        val iter = data.engineeredBlocks.iterator()
        while (iter.hasNext()) {
            val marker = iter.next()
            if (marker.dimension != level.dimension().location()) continue
            if (now - marker.placedTick < ttl) continue
            if (!level.hasChunkAt(marker.pos)) {
                if (++marker.attempts > 20) iter.remove()
                continue
            }
            val currentState = level.getBlockState(marker.pos)
            val currentId = ForgeRegistries.BLOCKS.getKey(currentState.block)
            if (currentId == marker.blockId && blockStateSnapshot(currentState) == marker.blockState) {
                level.setBlockAndUpdate(marker.pos, Blocks.AIR.defaultBlockState())
                removed++
            }
            iter.remove()
        }
        if (removed > 0) data.markChanged()
        return removed
    }

    private fun isOpen(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean = state.isAir || state.getCollisionShape(level, pos).isEmpty

    private fun objectiveFromTag(tag: net.minecraft.nbt.CompoundTag): BlockPos? {
        if (!tag.contains(OBJECTIVE_X_TAG) || !tag.contains(OBJECTIVE_Y_TAG) || !tag.contains(OBJECTIVE_Z_TAG)) return null
        return BlockPos(tag.getInt(OBJECTIVE_X_TAG), tag.getInt(OBJECTIVE_Y_TAG), tag.getInt(OBJECTIVE_Z_TAG))
    }

    private fun directionToward(from: BlockPos, to: BlockPos): Direction? {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return when {
            kotlin.math.abs(dx) >= kotlin.math.abs(dz) && dx > 2 -> Direction.EAST
            kotlin.math.abs(dx) >= kotlin.math.abs(dz) && dx < -2 -> Direction.WEST
            dz > 2 -> Direction.SOUTH
            dz < -2 -> Direction.NORTH
            else -> null
        }
    }

    private fun tryBridge(level: ServerLevel, data: PillagerWorldData, origin: BlockPos, direction: Direction, talent: OfficerEngineeringTalent, now: Long): Boolean {
        if (!OfficerEngineeringRules.canBridge(talent)) return false
        val forward = origin.relative(direction)
        val bridge = forward.below()
        if (!level.hasChunkAt(bridge)) return false
        if (!level.getBlockState(forward).isAir || !level.getBlockState(forward.above()).isAir) return false
        if (!level.getBlockState(bridge).isAir) return false
        return placeEngineeredBlock(level, data, bridge, Blocks.SCAFFOLDING.defaultBlockState(), now)
    }

    private fun tryLadder(level: ServerLevel, data: PillagerWorldData, origin: BlockPos, direction: Direction, talent: OfficerEngineeringTalent, now: Long): Boolean {
        if (!OfficerEngineeringRules.canLadder(talent)) return false
        val support = origin.relative(direction).above()
        val ladder = origin.above()
        if (!level.hasChunkAt(ladder)) return false
        if (!level.getBlockState(ladder).isAir) return false
        if (!level.getBlockState(support).isCollisionShapeFullBlock(level, support)) return false
        val state = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, direction.opposite)
        if (!state.canSurvive(level, ladder)) return false
        return placeEngineeredBlock(level, data, ladder, state, now)
    }

    private fun placeEngineeredBlock(level: ServerLevel, data: PillagerWorldData, pos: BlockPos, state: BlockState, now: Long): Boolean {
        if (!level.getBlockState(pos).isAir) return false
        level.setBlockAndUpdate(pos, state)
        ForgeRegistries.BLOCKS.getKey(state.block)?.let { id ->
            data.engineeredBlocks.add(EngineeredBlockMarker(level.dimension().location(), pos.immutable(), id, blockStateSnapshot(state), now, 0))
        }
        data.markChanged()
        return true
    }

    private fun blockStateSnapshot(state: BlockState): String {
        val id = ForgeRegistries.BLOCKS.getKey(state.block).toString()
        val properties = state.values.entries
            .sortedBy { it.key.name }
            .joinToString(",") { (property, value) -> "${property.name}=${valueName(property, value)}" }
        return if (properties.isBlank()) id else "$id[$properties]"
    }

    private fun <T : Comparable<T>> valueName(property: net.minecraft.world.level.block.state.properties.Property<T>, value: Comparable<*>): String =
        @Suppress("UNCHECKED_CAST")
        property.getName(value as T)

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
