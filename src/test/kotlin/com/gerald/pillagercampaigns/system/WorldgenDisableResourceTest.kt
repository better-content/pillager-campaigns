package com.gerald.pillagercampaigns.system

import com.google.gson.JsonParser
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldgenDisableResourceTest {
    @Test
    fun `all overridden pillager structure sets have zero natural placement frequency`() {
        val files = listOf(
            "src/main/resources/data/minecraft/worldgen/structure_set/pillager_outposts.json",
            "src/compat/resources/data/takesapillage/worldgen/structure_set/pillager_structure.json",
            "src/compat/resources/data/towns_and_towers/worldgen/structure_set/towers.json",
        )

        files.forEach { file ->
            val root = JsonParser.parseString(Path(file).readText()).asJsonObject
            val placement = root.getAsJsonObject("placement")
            assertEquals("minecraft:random_spread", placement.get("type").asString, file)
            assertEquals(0.0, placement.get("frequency").asDouble, 0.0, file)
            assertTrue(root.getAsJsonArray("structures").size() > 0, file)
        }
    }

    @Test
    fun `towns and towers override keeps every configured pillager structure available for SAM materializers`() {
        val root = JsonParser.parseString(Path("src/compat/resources/data/towns_and_towers/worldgen/structure_set/towers.json").readText()).asJsonObject
        val structures = root.getAsJsonArray("structures").map { it.asJsonObject.get("structure").asString }.toSet()

        listOf(
            "towns_and_towers:pillager_outpost_badlands",
            "towns_and_towers:pillager_outpost_beach",
            "towns_and_towers:pillager_outpost_birch_forest",
            "towns_and_towers:pillager_outpost_desert",
            "towns_and_towers:pillager_outpost_flower_forest",
            "towns_and_towers:pillager_outpost_forest",
            "towns_and_towers:pillager_outpost_grove",
            "towns_and_towers:pillager_outpost_jungle",
            "towns_and_towers:pillager_outpost_meadow",
            "towns_and_towers:pillager_outpost_mushroom_fields",
            "towns_and_towers:pillager_outpost_ocean",
            "towns_and_towers:pillager_outpost_old_growth_taiga",
            "towns_and_towers:pillager_outpost_savanna",
            "towns_and_towers:pillager_outpost_savanna_plateau",
            "towns_and_towers:pillager_outpost_snowy_beach",
            "towns_and_towers:pillager_outpost_snowy_plains",
            "towns_and_towers:pillager_outpost_snowy_slopes",
            "towns_and_towers:pillager_outpost_snowy_taiga",
            "towns_and_towers:pillager_outpost_sparse_jungle",
            "towns_and_towers:pillager_outpost_sunflower_plains",
            "towns_and_towers:pillager_outpost_swamp",
            "towns_and_towers:pillager_outpost_taiga",
            "towns_and_towers:pillager_outpost_wooded_badlands",
            "towns_and_towers:exclusives/pillager_outpost_classic",
            "towns_and_towers:exclusives/pillager_outpost_iberian",
            "towns_and_towers:exclusives/pillager_outpost_mediterranean",
            "towns_and_towers:exclusives/pillager_outpost_oriental",
            "towns_and_towers:exclusives/pillager_outpost_rustic",
            "towns_and_towers:exclusives/pillager_outpost_swedish",
            "towns_and_towers:exclusives/pillager_outpost_tudor",
        ).forEach { expected ->
            assertTrue(expected in structures, "missing $expected")
        }
    }
}
