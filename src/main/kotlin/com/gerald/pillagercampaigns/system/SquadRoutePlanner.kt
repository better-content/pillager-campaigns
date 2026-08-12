package com.gerald.pillagercampaigns.system

import com.gerald.warband.core.ChunkPosition
import com.gerald.warband.core.BlockPosition
import com.gerald.warband.core.EffectKind
import com.gerald.warband.core.CoreFrame
import com.gerald.warband.core.NavigationResult
import com.gerald.warband.core.NavigationStatus
import com.gerald.warband.core.TacticalObservation
import com.gerald.warband.core.TacticalPosition
import com.gerald.pillagercampaigns.data.PillagerWorldData
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One bounded real-navigation probe per squad interval; plans never inspect unloaded chunks. */
object SquadRoutePlanner {
    private const val PROBE_INTERVAL = 20L
    private const val PLAN_TTL = 100L
    private const val STUCK_TICKS = 80L
    private const val APPROACH_COUNT = 12

    private data class Plan(
        var targetChunkX: Int = Int.MIN_VALUE,
        var targetChunkZ: Int = Int.MIN_VALUE,
        var probeIndex: Int = 0,
        val positions: MutableMap<String, TacticalPosition> = linkedMapOf(),
        var lastProbeTick: Long = Long.MIN_VALUE,
        var lastProgressTick: Long = 0L,
        var lastX: Double = 0.0,
        var lastZ: Double = 0.0,
    )

    private val plans = linkedMapOf<UUID, Plan>()

    fun reset() = plans.clear()

    fun pursue(
        level: ServerLevel,
        mob: Mob,
        campaignId: UUID,
        target: Player,
        weaponRange: Double,
        cohesionRadius: Double,
    ) {
        mob.target = target
        if (mob.distanceToSqr(target) <= weaponRange * weaponRange) return
        val now = level.gameTime
        val plan = plans.getOrPut(campaignId) { Plan(lastProgressTick = now, lastX = mob.x, lastZ = mob.z) }
        val moved = (mob.x - plan.lastX) * (mob.x - plan.lastX) + (mob.z - plan.lastZ) * (mob.z - plan.lastZ)
        if (moved >= 2.0 * 2.0) {
            plan.lastProgressTick = now
            plan.lastX = mob.x
            plan.lastZ = mob.z
        } else if (now - plan.lastProgressTick >= STUCK_TICKS) {
            plan.positions.clear()
            plan.lastProgressTick = now
            plan.probeIndex++
        }
        if (plan.targetChunkX != target.chunkPosition().x || plan.targetChunkZ != target.chunkPosition().z || now - plan.lastProbeTick >= PLAN_TTL) {
            plan.targetChunkX = target.chunkPosition().x
            plan.targetChunkZ = target.chunkPosition().z
            plan.positions.clear()
        }
        if (now - plan.lastProbeTick >= PROBE_INTERVAL) {
            plan.lastProbeTick = now
            probe(level, mob, campaignId, target, plan, cohesionRadius)
        }
    }

    fun forget(campaignId: UUID) { plans.remove(campaignId) }

    private fun probe(
        level: ServerLevel,
        mob: Mob,
        campaignId: UUID,
        target: Player,
        plan: Plan,
        cohesionRadius: Double,
    ) {
        val index = Math.floorMod(plan.probeIndex++, APPROACH_COUNT)
        val phase = ((target.uuid.mostSignificantBits xor target.uuid.leastSignificantBits) and 0xffffL) / 65536.0 * 2.0 * PI
        val angle = phase + index * (2.0 * PI / APPROACH_COUNT)
        val radius = (6.0 + mob.distanceTo(target) * 0.25).coerceIn(6.0, 16.0)
        val x = (target.x + cos(angle) * radius).toInt()
        val z = (target.z + sin(angle) * radius).toInt()
        if (!level.hasChunk(x shr 4, z shr 4)) return
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        val candidate = BlockPos(x, y, z)
        val path = mob.navigation.createPath(candidate, 0)
        val reachable = path?.canReach() == true
        val cover = listOf(candidate.north(), candidate.south(), candidate.east(), candidate.west())
            .count { level.getBlockState(it).isCollisionShapeFullBlock(level, it) } / 4.0
        val directionX = candidate.x + 0.5 - target.x
        val directionZ = candidate.z + 0.5 - target.z
        val length = kotlin.math.sqrt(directionX * directionX + directionZ * directionZ).coerceAtLeast(1.0e-6)
        val flank = ((1.0 - (target.lookAngle.x * directionX + target.lookAngle.z * directionZ) / length) * 0.5).coerceIn(0.0, 1.0)
        val nearestAlly = level.getEntitiesOfClass(
            Mob::class.java,
            net.minecraft.world.phys.AABB(candidate).inflate(cohesionRadius.coerceAtLeast(4.0)),
        ) { ally ->
            ally !== mob && ally.persistentData.hasUUID(PillagerRuntime.CAMPAIGN_TAG) &&
                ally.persistentData.getUUID(PillagerRuntime.CAMPAIGN_TAG) == campaignId
        }.minOfOrNull { kotlin.math.sqrt(it.blockPosition().distSqr(candidate).toDouble()) } ?: cohesionRadius * 0.5
        val tactical = TacticalPosition(
            "$x:$y:$z", ChunkPosition(level.dimension().location().toString(), x shr 4, z shr 4),
            path?.nodeCount?.toDouble() ?: Double.MAX_VALUE, kotlin.math.sqrt(candidate.distSqr(target.blockPosition()).toDouble()),
            ((y - target.y) / 8.0).coerceIn(-1.0, 1.0), cover, flank, nearestAlly,
            reachable = reachable,
            blockPosition = BlockPosition(level.dimension().location().toString(), x, y, z),
        )
        plan.positions[tactical.id] = tactical
        val data = PillagerWorldData.get(level.server)
        val transition = WarbandCoreAdapter.transition(
            data,
            CoreFrame(
                elapsedTicks = 0L,
                tactical = listOf(TacticalObservation(campaignId.toString(), plan.positions.values.toList(), cohesionRadius)),
            ),
            level.server,
        )
        val manifestId = mob.persistentData.getString(PillagerRuntime.MANIFEST_ID_TAG)
        val effect = transition.effects.firstOrNull {
            it.kind == EffectKind.NAVIGATE && it.campaignId == campaignId.toString() && manifestId in it.memberIds
        } ?: return
        val intent = effect.blockPosition
        val selected = intent?.takeIf { it.dimension == level.dimension().location().toString() }
            ?.let { BlockPos(it.x, it.y, it.z) }
        val loaded = selected != null && level.hasChunk(selected.x shr 4, selected.z shr 4)
        val accepted = loaded && mob.navigation.moveTo(selected!!.x + .5, selected.y.toDouble(), selected.z + .5, 1.15)
        val status = when {
            selected == null -> NavigationStatus.STALE
            !loaded -> NavigationStatus.UNREACHABLE
            accepted -> NavigationStatus.ACCEPTED
            else -> NavigationStatus.FAILED
        }
        WarbandCoreAdapter.transition(
            data,
            CoreFrame(
                elapsedTicks = 0L,
                navigationResults = listOf(NavigationResult(
                    effect.effectId, campaignId.toString(), manifestId, status, status.name.lowercase(),
                )),
            ),
            level.server,
        )
    }
}
