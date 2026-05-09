package com.gerald.sam.api

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/** Public contracts for Settlements and Movements modules. Implementations must keep world access on the server thread. */
data class SettlementNode(
    val id: UUID,
    val archetypeId: ResourceLocation,
    val dimension: ResourceLocation,
    val targetChunkX: Int,
    val targetChunkZ: Int,
)

data class ResolvedSettlementSite(
    val nodeId: UUID,
    val dimension: ResourceLocation,
    val chunkX: Int,
    val chunkZ: Int,
    val center: BlockPos,
    val footprintRadiusChunks: Int,
    val materializerId: ResourceLocation,
)

data class MovementNode(
    val id: UUID,
    val typeId: ResourceLocation,
    val originSettlementId: UUID,
    val targetSettlementId: UUID?,
    val targetChunkX: Int,
    val targetChunkZ: Int,
)

data class WorkBudget(
    val maxJobs: Int,
    val maxCostUnits: Int,
    val maxMillis: Double,
)

interface SettlementArchetype {
    val id: ResourceLocation
    val defaultFootprintRadiusChunks: Int
}

interface SettlementMaterializer {
    val id: ResourceLocation
    val costClass: MaterializerCostClass
}

enum class MaterializerCostClass {
    SMALL,
    JIGSAW,
    LARGE,
}

interface MovementType {
    val id: ResourceLocation
}

interface SamModule {
    val id: ResourceLocation
    fun settlementArchetypes(): List<SettlementArchetype> = emptyList()
    fun settlementMaterializers(): List<SettlementMaterializer> = emptyList()
    fun movementTypes(): List<MovementType> = emptyList()
}
