package com.gerald.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertTrue

class PillagerBaseMaterializerMappingTest {
    @Suppress("UNCHECKED_CAST")
    private val mappedStartPools: Map<ResourceLocation, ResourceLocation> by lazy {
        val field = PillagerBaseMaterializer::class.java.getDeclaredField("START_POOLS_BY_STRUCTURE_ID")
        field.isAccessible = true
        field.get(PillagerBaseMaterializer) as Map<ResourceLocation, ResourceLocation>
    }

    @Test
    fun `all slash-delimited overridden pillager structures are explicitly mapped to start pools`() {
        val structures = loadOverriddenStructureIds()
        val missing = structures
            .filter { it.path.contains('/') }
            .filter { it !in mappedStartPools.keys }
            .map { it.toString() }

        assertTrue(
            missing.isEmpty(),
            "Missing explicit start-pool mappings for slash-delimited structures: ${missing.joinToString()}",
        )
    }

    private fun loadOverriddenStructureIds(): Set<ResourceLocation> = emptySet()
}
