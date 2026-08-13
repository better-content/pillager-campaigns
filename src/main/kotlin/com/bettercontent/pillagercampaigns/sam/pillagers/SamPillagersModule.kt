package com.bettercontent.pillagercampaigns.sam.pillagers

import com.bettercontent.pillagercampaigns.sam.api.MovementType
import com.bettercontent.pillagercampaigns.sam.api.SamModule
import net.minecraft.resources.ResourceLocation

object SamPillagersModule : SamModule {
    override val id: ResourceLocation = ResourceLocation("sam", "pillagers")

    override fun movementTypes(): List<MovementType> = listOf(PillagerInvasionMovement)
}

object PillagerInvasionMovement : MovementType {
    override val id: ResourceLocation = ResourceLocation("sampillagers", "invasion")
}
