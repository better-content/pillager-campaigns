package com.bettercontent.pillagercampaigns.system

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFalse

class WorldgenDisableResourceTest {
    @Test
    fun `pillager structure set overrides are removed so natural placement is not disabled`() {
        val files = listOf(
            "src/main/resources/data/minecraft/worldgen/structure_set/pillager_outposts.json",
            "src/compat/resources/data/takesapillage/worldgen/structure_set/pillager_structure.json",
            "src/compat/resources/data/towns_and_towers/worldgen/structure_set/towers.json",
        )

        files.forEach { file ->
            assertFalse(Path(file).exists(), "$file should not override natural pillager structure placement")
        }
    }

    @Test
    fun `vanilla pillager biome tag override is removed`() {
        assertFalse(
            Path("src/main/resources/data/minecraft/tags/worldgen/biome/has_structure/pillager_outpost.json").exists(),
            "vanilla pillager outpost biome tags should not be replaced",
        )
    }
}
