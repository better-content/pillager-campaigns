package com.gerald.pillagercampaigns.runner

import com.gerald.warband.core.CoreCatalog
import com.gerald.warband.core.CoreEffect
import com.gerald.warband.core.CoreEvent
import com.gerald.warband.core.CoreFrame
import com.gerald.warband.core.CoreSnapshot
import com.gerald.warband.core.WarbandCore
import com.gerald.warband.core.CoreRules
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A complete, runtime-neutral record of the inputs and outputs at every Core boundary. */
@Serializable
data class WarbandTrace(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val catalogRevision: String,
    val initialState: CoreSnapshot,
    val initialStateHash: String,
    val catalog: CoreCatalog,
    val rules: CoreRules,
    val steps: List<WarbandTraceStep>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class WarbandTraceStep(
    val index: Int,
    val frame: CoreFrame,
    val events: List<CoreEvent>,
    val effects: List<CoreEffect>,
    val postStateHash: String,
)

data class TraceReplayResult(val stepCount: Int, val finalStateHash: String)

class TraceDivergenceException(
    val stepIndex: Int,
    val component: String,
    expected: Any,
    actual: Any,
) : IllegalStateException(
    "trace divergence at step $stepIndex in $component: expected=$expected actual=$actual",
)

class WarbandTraceCodec(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false },
) {
    fun write(trace: WarbandTrace, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(trace))
    }

    fun read(file: File): WarbandTrace = json.decodeFromString(file.readText())

    fun replay(trace: WarbandTrace): TraceReplayResult {
        require(trace.schemaVersion == WarbandTrace.CURRENT_SCHEMA_VERSION) {
            "unsupported trace schema ${trace.schemaVersion}"
        }
        require(trace.catalogRevision == trace.catalog.revision) {
            "trace catalog revision ${trace.catalogRevision} does not match embedded catalog ${trace.catalog.revision}"
        }
        val initialHash = stateHash(trace.initialState)
        if (initialHash != trace.initialStateHash) {
            throw TraceDivergenceException(-1, "initialStateHash", trace.initialStateHash, initialHash)
        }
        val state = cloneState(trace.initialState)
        trace.steps.forEachIndexed { expectedIndex, step ->
            if (step.index != expectedIndex) {
                throw TraceDivergenceException(expectedIndex, "index", expectedIndex, step.index)
            }
            val result = WarbandCore.transition(state, step.frame, trace.catalog, trace.rules)
            if (result.events != step.events) {
                throw TraceDivergenceException(step.index, "events", step.events, result.events)
            }
            if (result.effects != step.effects) {
                throw TraceDivergenceException(step.index, "effects", step.effects, result.effects)
            }
            val actualHash = stateHash(result.state)
            if (actualHash != step.postStateHash) {
                throw TraceDivergenceException(step.index, "postStateHash", step.postStateHash, actualHash)
            }
        }
        return TraceReplayResult(trace.steps.size, stateHash(state))
    }

    fun stateHash(state: CoreSnapshot): String {
        val element = json.parseToJsonElement(json.encodeToString(state))
        val canonical = canonicalJson(element)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun cloneState(state: CoreSnapshot): CoreSnapshot = json.decodeFromString(json.encodeToString(state))

    fun cloneFrame(frame: CoreFrame): CoreFrame = json.decodeFromString(json.encodeToString(frame))

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                json.encodeToString(key) + ":" + canonicalJson(value)
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
        else -> element.toString()
    }
}
