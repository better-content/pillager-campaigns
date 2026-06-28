package com.gerald.sam.api

import net.minecraft.resources.ResourceLocation

interface MovementType {
    val id: ResourceLocation
}

interface SamModule {
    val id: ResourceLocation
    fun movementTypes(): List<MovementType> = emptyList()
}
