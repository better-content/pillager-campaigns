package com.gerald.pillagercampaigns.system

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller

data class TraitDelta(val habitability: Double = 0.0, val biomass: Double = 0.0, val mineral: Double = 0.0, val exotic: Double = 0.0, val friction: Double = 0.0)

object WarbandFormulaData : SimpleJsonResourceReloadListener(GsonBuilder().create(), "warband") {
    @Volatile var traitWeights: Map<String, TraitDelta> = defaults()
        private set
    @Volatile var threatCorrections: Map<String, Double> = emptyMap()
        private set
    @Volatile var ingredientWeights: Map<String, Double> = emptyMap()
        private set

    override fun apply(entries: MutableMap<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        entries.entries.firstOrNull { it.key.namespace == "pillagercampaigns" && it.key.path == "biome_traits" }?.value?.asJsonObject?.getAsJsonObject("weights")?.let { root ->
            traitWeights = root.entrySet().associate { (name, raw) -> name to raw.asJsonObject.let { obj -> TraitDelta(
                obj.number("habitability"), obj.number("biomass"), obj.number("mineral_potential"), obj.number("exotic_potential"), obj.number("travel_friction"),
            ) } }
        }
        threatCorrections = numericObject(entries, "opaque_threat_corrections", "corrections")
        ingredientWeights = numericObject(entries, "ingredient_resource_weights", "weights")
    }

    private fun numericObject(entries: Map<ResourceLocation, JsonElement>, path: String, member: String): Map<String, Double> =
        entries.entries.firstOrNull { it.key.namespace == "pillagercampaigns" && it.key.path == path }?.value?.asJsonObject?.getAsJsonObject(member)
            ?.entrySet()?.associate { it.key to it.value.asDouble }.orEmpty()

    private fun com.google.gson.JsonObject.number(key: String): Double = get(key)?.asDouble ?: 0.0

    private fun defaults() = linkedMapOf(
        "forest_or_jungle" to TraitDelta(.20, .40, -.10, .05, .15),
        "plains_or_savanna" to TraitDelta(.25, .20, friction = -.20),
        "wet_or_swamp" to TraitDelta(.10, .30, -.10, .25, .35),
        "mountain_peak_or_hill" to TraitDelta(-.20, -.10, .40, .10, .40),
        "ocean_river_or_coast" to TraitDelta(-.15, .05, exotic = .15, friction = .45),
        "dry_desert_or_badlands" to TraitDelta(-.30, -.35, .25, .10, .10),
        "cold_or_snow" to TraitDelta(-.20, -.15, .10, .20, .20),
        "magical_or_rare" to TraitDelta(exotic = .40),
    )
}
