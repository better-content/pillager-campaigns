package com.gerald.sam.pillagers

import com.gerald.sam.api.MaterializerCostClass
import com.gerald.sam.api.MovementType
import com.gerald.sam.api.SamModule
import com.gerald.sam.api.SettlementArchetype
import com.gerald.sam.api.SettlementMaterializer
import net.minecraft.resources.ResourceLocation

object SamPillagersModule : SamModule {
    override val id: ResourceLocation = ResourceLocation("sam", "pillagers")

    override fun settlementArchetypes(): List<SettlementArchetype> = listOf(PillagerBaseArchetype)

    override fun settlementMaterializers(): List<SettlementMaterializer> = listOf(PillagerJigsawBaseMaterializer)

    override fun movementTypes(): List<MovementType> = listOf(PillagerInvasionMovement)
}

object PillagerBaseArchetype : SettlementArchetype {
    override val id: ResourceLocation = ResourceLocation("sampillagers", "base")
    override val defaultFootprintRadiusChunks: Int = 3
}

object PillagerJigsawBaseMaterializer : SettlementMaterializer {
    override val id: ResourceLocation = ResourceLocation("sampillagers", "jigsaw_base")
    override val costClass: MaterializerCostClass = MaterializerCostClass.JIGSAW
}

object PillagerInvasionMovement : MovementType {
    override val id: ResourceLocation = ResourceLocation("sampillagers", "invasion")
}
