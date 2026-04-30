package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.PillagerPressureConfig
import com.gerald.pillagerpressure.data.*
import com.gerald.pillagerpressure.util.PillagerIdentity
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.structure.StructureStart
import java.util.UUID
import kotlin.math.abs

object PillagerBaseService {
    fun scanChunk(level: ServerLevel, chunk: ChunkAccess, data: PillagerWorldData): Int {
        if (PillagerPressureConfig.overworldOnly.get() && level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return 0
        val allowed = PillagerPressureConfig.structureBaseIds.get().mapNotNull { ResourceLocation.tryParse(it as String) }.toSet()
        if (allowed.isEmpty()) return 0
        val registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        var added = 0
        chunk.allStarts.forEach { (structure, start) ->
            if (!start.isValid) return@forEach
            val id = registry.getKey(structure) ?: return@forEach
            if (id !in allowed) return@forEach
            if (registerBase(level, data, id, start) != null) added++
        }
        return added
    }

    fun registerBase(level: ServerLevel, data: PillagerWorldData, structureId: ResourceLocation, start: StructureStart): PillagerBase? {
        val center = start.boundingBox.center
        val stable = "${level.dimension().location()}:$structureId:${start.chunkPos.x},${start.chunkPos.z}"
        val id = UUID.nameUUIDFromBytes(stable.toByteArray())
        val existing = data.bases[id]
        if (existing != null) {
            existing.lastValidatedTick = level.gameTime
            existing.bounds = start.boundingBox
            data.markChanged()
            return null
        }
        val faction = factionForNewMajorBase(data, level.seed xor id.mostSignificantBits xor id.leastSignificantBits)
        val base = PillagerBase(
            id = id,
            factionId = faction.id,
            parentBaseId = null,
            type = BaseType.MAJOR,
            dimension = level.dimension().location(),
            structureId = structureId,
            center = center,
            chunk = ChunkRef.of(ChunkPos(center)),
            bounds = start.boundingBox,
            state = BaseState.ACTIVE,
            manpower = 72,
            supplies = 140,
            morale = 80,
            aggression = 20,
            loyalty = 100,
            influence = 80,
            lastValidatedTick = level.gameTime,
        )
        data.bases[base.id] = base
        if (data.officers.values.none { it.homeBaseId == base.id }) {
            val officer = PillagerIdentity.makeOfficer(faction.id, base.id, level.seed xor base.id.leastSignificantBits)
            data.officers[officer.id] = officer
        }
        data.markChanged()
        return base
    }

    fun baseAt(level: ServerLevel, data: PillagerWorldData, pos: BlockPos): PillagerBase? {
        val dim = level.dimension().location()
        return data.bases.values.firstOrNull { base -> base.dimension == dim && base.isActive() && (base.bounds?.isInside(pos) == true || base.center.distSqr(pos) < 96.0 * 96.0) }
    }

    fun nearestActiveBase(level: ServerLevel, data: PillagerWorldData, pos: BlockPos): PillagerBase? {
        val dim = level.dimension().location()
        return data.bases.values.filter { it.dimension == dim && it.isActive() }.minByOrNull { it.center.distSqr(pos) }
    }

    fun officerForBase(data: PillagerWorldData, base: PillagerBase): PillagerOfficer {
        val existing = data.officers.values.firstOrNull { it.homeBaseId == base.id && it.state != OfficerState.DEAD }
        if (existing != null) return existing
        val officer = PillagerIdentity.makeOfficer(base.factionId, base.id, base.id.leastSignificantBits xor data.officers.size.toLong())
        data.officers[officer.id] = officer
        data.markChanged()
        return officer
    }

    fun factionForNewMajorBase(data: PillagerWorldData, seed: Long): PillagerFaction {
        val faction = PillagerIdentity.makeFaction(seed)
        val existing = data.factions.values.firstOrNull { it.name == faction.name && it.baseColor == faction.baseColor }
        if (existing != null) return existing
        data.factions[faction.id] = faction
        data.markChanged()
        return faction
    }

    fun tickEconomy(data: PillagerWorldData) {
        data.bases.values.filter { it.isActive() }.forEach { base ->
            val manpowerGain = if (base.type == BaseType.MAJOR) 2 else 1
            val supplyGain = if (base.type == BaseType.MAJOR) 4 else 1
            base.manpower = (base.manpower + manpowerGain).coerceAtMost(base.maxManpower())
            base.supplies = (base.supplies + supplyGain).coerceAtMost(base.maxSupplies())
            base.morale = (base.morale + 1).coerceAtMost(100)
        }
        data.markChanged()
    }

    fun createSatellite(level: ServerLevel, data: PillagerWorldData, parent: PillagerBase, target: ChunkRef): PillagerBase? {
        val existingCount = data.bases.values.count { it.parentBaseId == parent.id && it.state != BaseState.DESTROYED }
        if (existingCount >= PillagerPressureConfig.maxSatellitesPerMajorBase.get()) return null
        val id = UUID.nameUUIDFromBytes("satellite:${parent.id}:${target.x}:${target.z}".toByteArray())
        if (data.bases.containsKey(id)) return null
        val center = target.centerBlock(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.x * 16 + 8, target.z * 16 + 8))
        val base = PillagerBase(id, parent.factionId, parent.id, BaseType.SATELLITE, level.dimension().location(), null, center, target, null, BaseState.ACTIVE, 20, 42, 60, 12, 80, 30, level.gameTime)
        data.bases[id] = base
        data.markChanged()
        return base
    }
}
