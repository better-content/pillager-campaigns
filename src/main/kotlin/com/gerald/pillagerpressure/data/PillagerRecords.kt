package com.gerald.pillagerpressure.data

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.UUID
import kotlin.math.roundToInt

fun CompoundTag.getRequiredUuidString(key: String): UUID =
    if (contains(key, Tag.TAG_STRING.toInt())) UUID.fromString(getString(key)) else throw IllegalArgumentException("Missing UUID field '$key'")

fun CompoundTag.getOptionalUuidString(key: String): UUID? =
    if (contains(key, Tag.TAG_STRING.toInt())) runCatching { UUID.fromString(getString(key)) }.getOrNull() else null

fun CompoundTag.putUuidString(key: String, value: UUID) = putString(key, value.toString())

inline fun <reified T : Enum<T>> CompoundTag.getEnumString(key: String, fallback: T): T =
    if (contains(key, Tag.TAG_STRING.toInt())) runCatching { enumValueOf<T>(getString(key)) }.getOrDefault(fallback) else fallback

fun CompoundTag.getResourceLocationString(key: String, fallback: ResourceLocation? = null): ResourceLocation? =
    if (contains(key, Tag.TAG_STRING.toInt())) ResourceLocation.tryParse(getString(key)) ?: fallback else fallback

data class OfficerGeneProfile(
    var range: Int,
    var melee: Int,
    var speed: Int,
    var armor: Int,
    var magic: Int,
    var beast: Int,
    var fire: Int,
    var banner: Int,
    var siege: Int,
    var survival: Int,
) {
    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.putInt("range", range); tag.putInt("melee", melee); tag.putInt("speed", speed); tag.putInt("armor", armor); tag.putInt("magic", magic)
        tag.putInt("beast", beast); tag.putInt("fire", fire); tag.putInt("banner", banner); tag.putInt("siege", siege); tag.putInt("survival", survival)
    }

    fun clamped(): OfficerGeneProfile = OfficerGeneProfile(range.clampGene(), melee.clampGene(), speed.clampGene(), armor.clampGene(), magic.clampGene(), beast.clampGene(), fire.clampGene(), banner.clampGene(), siege.clampGene(), survival.clampGene())
    fun scaled(factor: Double): OfficerGeneProfile = OfficerGeneProfile((range * factor).roundToInt(), (melee * factor).roundToInt(), (speed * factor).roundToInt(), (armor * factor).roundToInt(), (magic * factor).roundToInt(), (beast * factor).roundToInt(), (fire * factor).roundToInt(), (banner * factor).roundToInt(), (siege * factor).roundToInt(), (survival * factor).roundToInt())
    fun plus(other: OfficerGeneProfile): OfficerGeneProfile = OfficerGeneProfile(range + other.range, melee + other.melee, speed + other.speed, armor + other.armor, magic + other.magic, beast + other.beast, fire + other.fire, banner + other.banner, siege + other.siege, survival + other.survival)
    fun minus(other: OfficerGeneProfile): OfficerGeneProfile = OfficerGeneProfile(range - other.range, melee - other.melee, speed - other.speed, armor - other.armor, magic - other.magic, beast - other.beast, fire - other.fire, banner - other.banner, siege - other.siege, survival - other.survival)
    fun topGenes(count: Int): List<Pair<String, Int>> = listOf("range" to range, "melee" to melee, "speed" to speed, "armor" to armor, "magic" to magic, "beast" to beast, "fire" to fire, "banner" to banner, "siege" to siege, "survival" to survival).sortedByDescending { it.second }.take(count)

    companion object {
        fun neutral(value: Int = 35): OfficerGeneProfile = OfficerGeneProfile(value, value, value, value, value, value, value, value, value, value)
        fun load(tag: CompoundTag): OfficerGeneProfile = OfficerGeneProfile(tag.getInt("range"), tag.getInt("melee"), tag.getInt("speed"), tag.getInt("armor"), tag.getInt("magic"), tag.getInt("beast"), tag.getInt("fire"), tag.getInt("banner"), tag.getInt("siege"), tag.getInt("survival")).clamped()
    }
}

data class FactionWarMemory(
    var successfulGenes: OfficerGeneProfile = OfficerGeneProfile.neutral(0),
    var failedGenes: OfficerGeneProfile = OfficerGeneProfile.neutral(0),
    var mutationSeed: Long = 0L,
    var generation: Int = 0,
) {
    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.put("success", successfulGenes.save())
        tag.put("failure", failedGenes.save())
        tag.putLong("mutationSeed", mutationSeed)
        tag.putInt("generation", generation)
    }

    fun learnedPreference(): OfficerGeneProfile = successfulGenes.minus(failedGenes.scaled(0.5)).plus(OfficerGeneProfile.neutral(35)).clamped()

    companion object {
        fun load(tag: CompoundTag): FactionWarMemory = FactionWarMemory(
            if (tag.contains("success", Tag.TAG_COMPOUND.toInt())) OfficerGeneProfile.load(tag.getCompound("success")) else OfficerGeneProfile.neutral(0),
            if (tag.contains("failure", Tag.TAG_COMPOUND.toInt())) OfficerGeneProfile.load(tag.getCompound("failure")) else OfficerGeneProfile.neutral(0),
            tag.getLong("mutationSeed"),
            tag.getInt("generation"),
        )
    }
}

data class OfficerLineage(
    var predecessorOfficerId: UUID?,
    var inheritedRank: OfficerRank,
    var inheritedBannerSeed: Int,
    var causeOfSuccession: String,
) {
    fun save(): CompoundTag = CompoundTag().also { tag ->
        predecessorOfficerId?.let { tag.putUuidString("predecessor", it) }
        tag.putString("inheritedRank", inheritedRank.name)
        tag.putInt("inheritedBannerSeed", inheritedBannerSeed)
        tag.putString("cause", causeOfSuccession)
    }

    companion object {
        fun none(rank: OfficerRank = OfficerRank.CAPTAIN, bannerSeed: Int = 0): OfficerLineage = OfficerLineage(null, rank, bannerSeed, "raised from the ranks")
        fun load(tag: CompoundTag, fallbackRank: OfficerRank): OfficerLineage = OfficerLineage(tag.getOptionalUuidString("predecessor"), tag.getEnumString("inheritedRank", fallbackRank), tag.getInt("inheritedBannerSeed"), tag.getString("cause").ifBlank { "raised from the ranks" })
    }
}

private fun Int.clampGene(): Int = coerceIn(0, 100)

data class ChunkRef(val x: Int, val z: Int) {
    fun toChunkPos(): ChunkPos = ChunkPos(x, z)
    fun centerBlock(y: Int = 80): BlockPos = BlockPos(x * 16 + 8, y, z * 16 + 8)
    fun distanceManhattan(other: ChunkRef): Int = kotlin.math.abs(x - other.x) + kotlin.math.abs(z - other.z)
    fun stepToward(target: ChunkRef): ChunkRef {
        val dx = target.x.compareTo(x)
        val dz = target.z.compareTo(z)
        return if (kotlin.math.abs(target.x - x) >= kotlin.math.abs(target.z - z) && dx != 0) ChunkRef(x + dx, z) else ChunkRef(x, z + dz)
    }

    companion object {
        fun of(pos: BlockPos): ChunkRef = ChunkRef(pos.x shr 4, pos.z shr 4)
        fun of(pos: ChunkPos): ChunkRef = ChunkRef(pos.x, pos.z)
        fun load(tag: CompoundTag): ChunkRef = ChunkRef(tag.getInt("x"), tag.getInt("z"))
    }

    fun save(): CompoundTag = CompoundTag().also { it.putInt("x", x); it.putInt("z", z) }
}

data class RegionKey(val x: Int, val z: Int) {
    companion object {
        fun fromChunk(chunk: ChunkRef, regionSizeChunks: Int): RegionKey = RegionKey(Math.floorDiv(chunk.x, regionSizeChunks), Math.floorDiv(chunk.z, regionSizeChunks))
        fun load(tag: CompoundTag): RegionKey = RegionKey(tag.getInt("x"), tag.getInt("z"))
    }
    fun save(): CompoundTag = CompoundTag().also { it.putInt("x", x); it.putInt("z", z) }
}

data class PlayerIntel(
    val playerUuid: UUID,
    var playerName: String,
    var lastSeenChunk: ChunkRef,
    var lastSeenTick: Long,
    var confidence: Int,
    var sourceOfficerId: UUID?,
) {
    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.putUuidString("player", playerUuid)
        tag.putString("name", playerName)
        tag.put("chunk", lastSeenChunk.save())
        tag.putLong("tick", lastSeenTick)
        tag.putInt("confidence", confidence)
        sourceOfficerId?.let { tag.putUuidString("officer", it) }
    }

    companion object {
        fun load(tag: CompoundTag): PlayerIntel = PlayerIntel(tag.getRequiredUuidString("player"), tag.getString("name"), ChunkRef.load(tag.getCompound("chunk")), tag.getLong("tick"), tag.getInt("confidence"), tag.getOptionalUuidString("officer"))
    }
}

data class PillagerFaction(
    val id: UUID,
    var name: String,
    var baseColor: String,
    var accentColor: String,
    var patternSeed: Int,
    var aggressionBias: Int,
    var expansionBias: Int,
    var warMemory: FactionWarMemory = FactionWarMemory(),
) {
    fun baseDyeColor(): DyeColor = DyeColor.byName(baseColor, DyeColor.BLACK) ?: DyeColor.BLACK
    fun accentDyeColor(): DyeColor = DyeColor.byName(accentColor, DyeColor.RED) ?: DyeColor.RED

    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.putUuidString("id", id)
        tag.putString("name", name)
        tag.putString("baseColor", normalizedColorName(baseColor, "black"))
        tag.putString("accentColor", normalizedColorName(accentColor, "red"))
        tag.putInt("patternSeed", patternSeed)
        tag.putInt("aggressionBias", aggressionBias)
        tag.putInt("expansionBias", expansionBias)
        tag.put("warMemory", warMemory.save())
    }

    companion object {
        fun load(tag: CompoundTag): PillagerFaction = PillagerFaction(
            tag.getRequiredUuidString("id"),
            tag.getString("name").ifBlank { "Unnamed Banner" },
            normalizedColorName(tag.getString("baseColor"), "black"),
            normalizedColorName(tag.getString("accentColor"), "red"),
            tag.getInt("patternSeed"),
            tag.getInt("aggressionBias"),
            tag.getInt("expansionBias"),
            if (tag.contains("warMemory", Tag.TAG_COMPOUND.toInt())) FactionWarMemory.load(tag.getCompound("warMemory")) else FactionWarMemory(),
        )
    }
}

private val vanillaDyeColorNames = setOf("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")
private fun normalizedColorName(raw: String, fallback: String): String = raw.lowercase().let { if (it in vanillaDyeColorNames) it else fallback }

data class PillagerBase(
    val id: UUID,
    var factionId: UUID,
    var parentBaseId: UUID?,
    var type: BaseType,
    var dimension: ResourceLocation,
    var structureId: ResourceLocation?,
    var center: BlockPos,
    var chunk: ChunkRef,
    var bounds: BoundingBox?,
    var state: BaseState,
    var manpower: Int,
    var supplies: Int,
    var morale: Int,
    var aggression: Int,
    var loyalty: Int,
    var influence: Int,
    var lastValidatedTick: Long,
    val intel: MutableList<PlayerIntel> = mutableListOf(),
) {
    fun maxManpower(): Int = if (type == BaseType.MAJOR) 80 else 28
    fun maxSupplies(): Int = if (type == BaseType.MAJOR) 160 else 54
    fun isActive(): Boolean = state == BaseState.ACTIVE || state == BaseState.DAMAGED

    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.putUuidString("id", id); tag.putUuidString("faction", factionId); parentBaseId?.let { tag.putUuidString("parent", it) }
        tag.putString("type", type.name); tag.putString("dimension", dimension.toString()); structureId?.let { tag.putString("structure", it.toString()) }
        tag.putInt("cx", center.x); tag.putInt("cy", center.y); tag.putInt("cz", center.z); tag.put("chunk", chunk.save())
        bounds?.let { b -> tag.putIntArray("bounds", intArrayOf(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) }
        tag.putString("state", state.name); tag.putInt("manpower", manpower); tag.putInt("supplies", supplies); tag.putInt("morale", morale)
        tag.putInt("aggression", aggression); tag.putInt("loyalty", loyalty); tag.putInt("influence", influence); tag.putLong("lastValidated", lastValidatedTick)
        val list = ListTag(); intel.forEach { list.add(it.save()) }; tag.put("intel", list)
    }

    companion object {
        fun load(tag: CompoundTag): PillagerBase {
            val bounds = if (tag.contains("bounds", Tag.TAG_INT_ARRAY.toInt())) tag.getIntArray("bounds").let { raw -> if (raw.size >= 6) BoundingBox(raw[0], raw[1], raw[2], raw[3], raw[4], raw[5]) else null } else null
            val base = PillagerBase(
                tag.getRequiredUuidString("id"), tag.getRequiredUuidString("faction"), tag.getOptionalUuidString("parent"), tag.getEnumString("type", BaseType.MAJOR),
                tag.getResourceLocationString("dimension", ResourceLocation("minecraft", "overworld"))!!, tag.getResourceLocationString("structure"),
                BlockPos(tag.getInt("cx"), tag.getInt("cy"), tag.getInt("cz")), ChunkRef.load(tag.getCompound("chunk")), bounds,
                tag.getEnumString("state", BaseState.ACTIVE), tag.getInt("manpower"), tag.getInt("supplies"), tag.getInt("morale"), tag.getInt("aggression"), tag.getInt("loyalty"), tag.getInt("influence"), tag.getLong("lastValidated"),
            )
            tag.getList("intel", Tag.TAG_COMPOUND.toInt()).forEach { raw -> runCatching { base.intel.add(PlayerIntel.load(raw as CompoundTag)) } }
            return base
        }
    }
}

data class PillagerOfficer(
    val id: UUID,
    var name: String,
    var title: String,
    var factionId: UUID,
    var homeBaseId: UUID,
    var rank: OfficerRank,
    var role: OfficerRole,
    var state: OfficerState,
    var victories: Int,
    var defeats: Int,
    var killedPlayers: Int,
    var escapedEncounters: Int,
    var genes: OfficerGeneProfile = OfficerGeneProfile.neutral(),
    var doctrine: OfficerDoctrine = OfficerDoctrine.RAIDER,
    val affixes: MutableSet<OfficerAffix> = mutableSetOf(),
    var lineage: OfficerLineage = OfficerLineage.none(),
    val traits: MutableSet<String> = mutableSetOf(),
    val grudges: MutableMap<UUID, Int> = mutableMapOf(),
) {
    fun displayName(): String = "${rank.name.lowercase().replaceFirstChar { it.uppercase() }} $name $title"

    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.putUuidString("id", id); tag.putString("name", name); tag.putString("title", title); tag.putUuidString("faction", factionId); tag.putUuidString("home", homeBaseId)
        tag.putString("rank", rank.name); tag.putString("role", role.name); tag.putString("state", state.name); tag.putInt("victories", victories); tag.putInt("defeats", defeats); tag.putInt("kills", killedPlayers); tag.putInt("escapes", escapedEncounters)
        tag.put("genes", genes.save()); tag.putString("doctrine", doctrine.name); tag.put("lineage", lineage.save())
        val affixList = ListTag(); affixes.forEach { affixList.add(StringTag.valueOf(it.name)) }; tag.put("affixes", affixList)
        val traitList = ListTag(); traits.forEach { traitList.add(StringTag.valueOf(it)) }; tag.put("traits", traitList)
        val grudgeList = ListTag(); grudges.forEach { (id, value) -> grudgeList.add(CompoundTag().also { it.putUuidString("player", id); it.putInt("value", value) }) }; tag.put("grudges", grudgeList)
    }

    companion object {
        fun load(tag: CompoundTag): PillagerOfficer {
            val rank = tag.getEnumString("rank", OfficerRank.CAPTAIN)
            val officer = PillagerOfficer(
                tag.getRequiredUuidString("id"), tag.getString("name").ifBlank { "Nameless" }, tag.getString("title").ifBlank { "the Unproven" }, tag.getRequiredUuidString("faction"), tag.getRequiredUuidString("home"),
                rank, tag.getEnumString("role", OfficerRole.SKIRMISHER), tag.getEnumString("state", OfficerState.ACTIVE), tag.getInt("victories"), tag.getInt("defeats"), tag.getInt("kills"), tag.getInt("escapes"),
                if (tag.contains("genes", Tag.TAG_COMPOUND.toInt())) OfficerGeneProfile.load(tag.getCompound("genes")) else OfficerGeneProfile.neutral(),
                tag.getEnumString("doctrine", OfficerDoctrine.RAIDER), mutableSetOf(), if (tag.contains("lineage", Tag.TAG_COMPOUND.toInt())) OfficerLineage.load(tag.getCompound("lineage"), rank) else OfficerLineage.none(rank),
            )
            tag.getList("affixes", Tag.TAG_STRING.toInt()).forEach { raw -> runCatching { officer.affixes.add(OfficerAffix.valueOf(raw.asString)) } }
            tag.getList("traits", Tag.TAG_STRING.toInt()).forEach { officer.traits.add(it.asString) }
            tag.getList("grudges", Tag.TAG_COMPOUND.toInt()).forEach { raw -> val grudge = raw as CompoundTag; grudge.getOptionalUuidString("player")?.let { officer.grudges[it] = grudge.getInt("value") } }
            return officer
        }
    }
}

data class PillagerCampaign(
    val id: UUID,
    var factionId: UUID,
    var originBaseId: UUID,
    var officerId: UUID?,
    var state: CampaignState,
    var current: ChunkRef,
    var target: ChunkRef,
    var speedTicksPerChunk: Int,
    var tickDebt: Int,
    var pillagers: Int,
    var specials: Int,
    var createdTick: Long,
    var lastMaterializedTick: Long,
) {
    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.putUuidString("id", id); tag.putUuidString("faction", factionId); tag.putUuidString("origin", originBaseId); officerId?.let { tag.putUuidString("officer", it) }
        tag.putString("state", state.name); tag.put("current", current.save()); tag.put("target", target.save()); tag.putInt("speed", speedTicksPerChunk); tag.putInt("debt", tickDebt)
        tag.putInt("pillagers", pillagers); tag.putInt("specials", specials); tag.putLong("created", createdTick); tag.putLong("materialized", lastMaterializedTick)
    }

    companion object {
        fun load(tag: CompoundTag): PillagerCampaign = PillagerCampaign(tag.getRequiredUuidString("id"), tag.getRequiredUuidString("faction"), tag.getRequiredUuidString("origin"), tag.getOptionalUuidString("officer"), tag.getEnumString("state", CampaignState.DISBANDED), ChunkRef.load(tag.getCompound("current")), ChunkRef.load(tag.getCompound("target")), tag.getInt("speed").coerceAtLeast(1), tag.getInt("debt"), tag.getInt("pillagers"), tag.getInt("specials"), tag.getLong("created"), tag.getLong("materialized"))
    }
}

data class RegionActivity(var key: RegionKey, var lastPlayerActiveTick: Long) {
    fun save(): CompoundTag = key.save().also { it.putLong("last", lastPlayerActiveTick) }
    companion object { fun load(tag: CompoundTag): RegionActivity = RegionActivity(RegionKey.load(tag), tag.getLong("last")) }
}

data class PendingFlagMarker(val factionId: UUID, val officerId: UUID?, val dimension: ResourceLocation, val pos: BlockPos, val createdTick: Long, var attempts: Int) {
    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.putUuidString("faction", factionId); officerId?.let { tag.putUuidString("officer", it) }; tag.putString("dimension", dimension.toString())
        tag.putInt("x", pos.x); tag.putInt("y", pos.y); tag.putInt("z", pos.z); tag.putLong("created", createdTick); tag.putInt("attempts", attempts)
    }

    companion object {
        fun load(tag: CompoundTag): PendingFlagMarker = PendingFlagMarker(tag.getRequiredUuidString("faction"), tag.getOptionalUuidString("officer"), tag.getResourceLocationString("dimension", ResourceLocation("minecraft", "overworld"))!!, BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")), tag.getLong("created"), tag.getInt("attempts"))
    }
}
