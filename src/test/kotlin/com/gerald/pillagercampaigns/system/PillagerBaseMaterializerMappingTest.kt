package com.gerald.pillagercampaigns.system

import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import kotlin.io.path.Path
import kotlin.io.path.readText
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

    private fun loadOverriddenStructureIds(): Set<ResourceLocation> {
        val files = listOf(
            "src/main/resources/data/minecraft/worldgen/structure_set/pillager_outposts.json",
            "src/compat/resources/data/takesapillage/worldgen/structure_set/pillager_structure.json",
            "src/compat/resources/data/towns_and_towers/worldgen/structure_set/towers.json",
        )
        return files
            .flatMap { file ->
                val root = JsonParser.parseString(Path(file).readText()).asJsonObject
                root.getAsJsonArray("structures").map { entry ->
                    ResourceLocation.tryParse(entry.asJsonObject.get("structure").asString)!!
                }
            }
            .toSet()
    }
}

