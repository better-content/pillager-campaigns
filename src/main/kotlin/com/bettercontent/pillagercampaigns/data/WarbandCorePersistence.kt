package com.bettercontent.pillagercampaigns.data

import com.gerald.warband.core.WarbandSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Minecraft-only state which realizes canonical Warband Core identities. None
 * of these records may influence a Core transition.
 */
data class MinecraftSidecar(
    val entityIds: MutableMap<String, UUID> = linkedMapOf(),
    val mobSnapshots: MutableMap<String, CompoundTag> = linkedMapOf(),
    val itemSnapshots: MutableMap<String, MutableList<CompoundTag>> = linkedMapOf(),
    val cosmetics: MutableMap<String, CosmeticSidecar> = linkedMapOf(),
    val materializationAttempts: MutableMap<String, MaterializationAttemptSidecar> = linkedMapOf(),
) {
    fun save(): CompoundTag = CompoundTag().also { root ->
        root.putInt(SCHEMA_KEY, SIDECAR_SCHEMA_VERSION)
        root.put("entityIds", saveEntries(entityIds) { value -> putUUID("value", value) })
        root.put("mobSnapshots", saveEntries(mobSnapshots) { value -> put("value", value.copy()) })
        root.put("itemSnapshots", saveEntries(itemSnapshots) { value ->
            put("value", ListTag().also { list -> value.forEach { list.add(it.copy()) } })
        })
        root.put("cosmetics", saveEntries(cosmetics) { value -> put("value", value.save()) })
        root.put("materializationAttempts", saveEntries(materializationAttempts) { value -> put("value", value.save()) })
    }

    companion object {
        private const val SCHEMA_KEY = "schema"
        private const val SIDECAR_SCHEMA_VERSION = 1

        fun load(tag: CompoundTag): MinecraftSidecar {
            val schema = tag.getInt(SCHEMA_KEY)
            require(schema == SIDECAR_SCHEMA_VERSION) {
                "Unsupported Minecraft sidecar schema $schema; expected $SIDECAR_SCHEMA_VERSION"
            }
            return MinecraftSidecar().also { sidecar ->
                loadEntries(tag, "entityIds") { id, entry ->
                    require(entry.hasUUID("value")) { "Sidecar entity '$id' has no UUID" }
                    sidecar.entityIds[id] = entry.getUUID("value")
                }
                loadEntries(tag, "mobSnapshots") { id, entry ->
                    require(entry.contains("value", Tag.TAG_COMPOUND.toInt())) { "Sidecar mob '$id' has no snapshot" }
                    sidecar.mobSnapshots[id] = entry.getCompound("value").copy()
                }
                loadEntries(tag, "itemSnapshots") { id, entry ->
                    require(entry.contains("value", Tag.TAG_LIST.toInt())) { "Sidecar item owner '$id' has no snapshots" }
                    sidecar.itemSnapshots[id] = entry.getList("value", Tag.TAG_COMPOUND.toInt())
                        .mapTo(mutableListOf()) { (it as CompoundTag).copy() }
                }
                loadEntries(tag, "cosmetics") { id, entry ->
                    require(entry.contains("value", Tag.TAG_COMPOUND.toInt())) { "Sidecar cosmetic '$id' has no value" }
                    sidecar.cosmetics[id] = CosmeticSidecar.load(entry.getCompound("value"))
                }
                loadEntries(tag, "materializationAttempts") { id, entry ->
                    require(entry.contains("value", Tag.TAG_COMPOUND.toInt())) { "Sidecar attempt '$id' has no value" }
                    sidecar.materializationAttempts[id] = MaterializationAttemptSidecar.load(entry.getCompound("value"))
                }
            }
        }

        private inline fun <T> saveEntries(values: Map<String, T>, crossinline saveValue: CompoundTag.(T) -> Unit) =
            ListTag().also { list ->
                values.toSortedMap().forEach { (id, value) ->
                    require(id.isNotBlank()) { "Sidecar canonical IDs must not be blank" }
                    list.add(CompoundTag().also { entry ->
                        entry.putString("id", id)
                        entry.saveValue(value)
                    })
                }
            }

        private inline fun loadEntries(
            root: CompoundTag,
            key: String,
            crossinline loadValue: (String, CompoundTag) -> Unit,
        ) {
            if (!root.contains(key, Tag.TAG_LIST.toInt())) return
            val seen = hashSetOf<String>()
            root.getList(key, Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                val entry = raw as CompoundTag
                val id = entry.getString("id")
                require(id.isNotBlank()) { "Sidecar '$key' contains a blank canonical ID" }
                require(seen.add(id)) { "Sidecar '$key' contains duplicate canonical ID '$id'" }
                loadValue(id, entry)
            }
        }
    }
}

data class CosmeticSidecar(
    val name: String? = null,
    val title: String? = null,
    val bannerSeed: Int? = null,
) {
    fun save() = CompoundTag().also { tag ->
        name?.let { tag.putString("name", it) }
        title?.let { tag.putString("title", it) }
        bannerSeed?.let { tag.putInt("bannerSeed", it) }
    }

    companion object {
        fun load(tag: CompoundTag) = CosmeticSidecar(
            name = tag.stringOrNull("name"),
            title = tag.stringOrNull("title"),
            bannerSeed = if (tag.contains("bannerSeed", Tag.TAG_INT.toInt())) tag.getInt("bannerSeed") else null,
        )
    }
}

data class MaterializationAttemptSidecar(
    val attemptId: UUID,
    val lastAttemptTick: Long,
) {
    fun save() = CompoundTag().also { tag ->
        tag.putUUID("attemptId", attemptId)
        tag.putLong("lastAttemptTick", lastAttemptTick)
    }

    companion object {
        fun load(tag: CompoundTag): MaterializationAttemptSidecar {
            require(tag.hasUUID("attemptId")) { "Materialization attempt has no attemptId" }
            return MaterializationAttemptSidecar(tag.getUUID("attemptId"), tag.getLong("lastAttemptTick"))
        }
    }
}

data class PersistedWarbandCore(
    val snapshot: WarbandSnapshot,
    val runtimeSpecRevision: String,
    val sidecar: MinecraftSidecar,
)

class UnsupportedWarbandCoreSchemaException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Strict, deterministic NBT envelope for the runtime-independent snapshot. */
@OptIn(ExperimentalSerializationApi::class)
object WarbandCorePersistence {
    const val SCHEMA_VERSION = 6
    const val FORMAT = "warband-core"

    private const val SCHEMA_KEY = "schema"
    private const val FORMAT_KEY = "format"
    private const val RUNTIME_SPEC_REVISION_KEY = "runtimeSpecRevision"
    private const val LEGACY_CATALOG_REVISION_KEY = "catalogRevision"
    private const val SNAPSHOT_KEY = "coreSnapshotJson"
    private const val SIDECAR_KEY = "minecraftSidecar"

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    fun save(value: PersistedWarbandCore, tag: CompoundTag = CompoundTag()): CompoundTag {
        require(value.runtimeSpecRevision.isNotBlank()) { "Warband runtime-spec revision must not be blank" }
        val encoded = json.encodeToString(value.snapshot).toByteArray(StandardCharsets.UTF_8)
        tag.putInt(SCHEMA_KEY, SCHEMA_VERSION)
        tag.putString(FORMAT_KEY, FORMAT)
        tag.putString(RUNTIME_SPEC_REVISION_KEY, value.runtimeSpecRevision)
        tag.putByteArray(SNAPSHOT_KEY, encoded)
        tag.put(SIDECAR_KEY, value.sidecar.save())
        return tag
    }

    fun load(tag: CompoundTag): PersistedWarbandCore {
        val schema = if (tag.contains(SCHEMA_KEY, Tag.TAG_INT.toInt())) tag.getInt(SCHEMA_KEY) else null
        if (schema !in setOf(5, SCHEMA_VERSION) || tag.getString(FORMAT_KEY) != FORMAT) {
            throw UnsupportedWarbandCoreSchemaException(
                "Unsupported Pillager Campaigns save schema ${schema ?: "missing"}; expected $FORMAT schema 5 or $SCHEMA_VERSION. Older strategic saves are not migrated.",
            )
        }
        val persistedRevision = tag.getString(if (schema == 5) LEGACY_CATALOG_REVISION_KEY else RUNTIME_SPEC_REVISION_KEY)
        if (persistedRevision.isBlank()) throw UnsupportedWarbandCoreSchemaException("Warband Core save has no runtime-spec revision")
        if (schema == SCHEMA_VERSION && persistedRevision == PillagerWorldData.UNRESOLVED_RUNTIME_SPEC_REVISION) {
            throw UnsupportedWarbandCoreSchemaException("Warband Core save has an unresolved runtime-spec revision")
        }
        // Schema 5 could only identify its partial live catalog, not the complete
        // decision specification. Its one supported migration adopts the first
        // validated complete spec attached by the current runtime.
        val revision = if (schema == 5) PillagerWorldData.UNRESOLVED_RUNTIME_SPEC_REVISION else persistedRevision
        if (!tag.contains(SNAPSHOT_KEY, Tag.TAG_BYTE_ARRAY.toInt())) {
            throw UnsupportedWarbandCoreSchemaException("Warband Core save has no UTF-8 snapshot payload")
        }
        if (!tag.contains(SIDECAR_KEY, Tag.TAG_COMPOUND.toInt())) {
            throw UnsupportedWarbandCoreSchemaException("Warband Core save has no Minecraft sidecar")
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(tag.getByteArray(SNAPSHOT_KEY)))
                .toString()
        } catch (error: Exception) {
            throw UnsupportedWarbandCoreSchemaException("Warband Core snapshot is not valid UTF-8", error)
        }
        val snapshot = try {
            json.decodeFromString<WarbandSnapshot>(text)
        } catch (error: SerializationException) {
            throw UnsupportedWarbandCoreSchemaException("Warband Core snapshot JSON is invalid", error)
        }
        val sidecar = try {
            MinecraftSidecar.load(tag.getCompound(SIDECAR_KEY))
        } catch (error: IllegalArgumentException) {
            throw UnsupportedWarbandCoreSchemaException("Minecraft sidecar is invalid: ${error.message}", error)
        }
        return PersistedWarbandCore(snapshot, revision, sidecar)
    }
}

private fun CompoundTag.stringOrNull(key: String) =
    if (contains(key, Tag.TAG_STRING.toInt())) getString(key) else null
