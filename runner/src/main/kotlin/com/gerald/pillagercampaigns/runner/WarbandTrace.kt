package com.gerald.pillagercampaigns.runner

import com.gerald.warband.core.WarbandEffect
import com.gerald.warband.core.WarbandEngine
import com.gerald.warband.core.WarbandEvent
import com.gerald.warband.core.WarbandFrame
import com.gerald.warband.core.WarbandRuntimeSpec
import com.gerald.warband.core.WarbandSnapshot
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
    val runtimeSpecRevision: String,
    val initialState: WarbandSnapshot,
    val initialStateHash: String,
    val runtimeSpec: WarbandRuntimeSpec,
    val steps: List<WarbandTraceStep>,
    val boundary: ExperimentBoundary = ExperimentBoundary(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}

@Serializable
data class WarbandTraceStep(
    val index: Int,
    val frame: WarbandFrame,
    val events: List<WarbandEvent>,
    val effects: List<WarbandEffect>,
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
        trace.runtimeSpec.requireValidRevision()
        require(trace.runtimeSpecRevision == trace.runtimeSpec.revision) {
            "trace runtime-spec revision ${trace.runtimeSpecRevision} does not match embedded spec ${trace.runtimeSpec.revision}"
        }
        val initialHash = stateHash(trace.initialState)
        if (initialHash != trace.initialStateHash) {
            throw TraceDivergenceException(-1, "initialStateHash", trace.initialStateHash, initialHash)
        }
        val engine = WarbandEngine.restore(trace.initialState, trace.runtimeSpec)
        trace.steps.forEachIndexed { expectedIndex, step ->
            if (step.index != expectedIndex) {
                throw TraceDivergenceException(expectedIndex, "index", expectedIndex, step.index)
            }
            val result = engine.transition(step.frame)
            if (result.events != step.events) {
                throw TraceDivergenceException(step.index, "events", step.events, result.events)
            }
            if (result.effects != step.effects) {
                throw TraceDivergenceException(step.index, "effects", step.effects, result.effects)
            }
            val actualHash = stateHash(engine.snapshot())
            if (actualHash != step.postStateHash) {
                throw TraceDivergenceException(step.index, "postStateHash", step.postStateHash, actualHash)
            }
        }
        return TraceReplayResult(trace.steps.size, stateHash(engine.snapshot()))
    }

    fun stateHash(state: WarbandSnapshot): String {
        val element = json.parseToJsonElement(json.encodeToString(state))
        val canonical = canonicalJson(element)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun cloneState(state: WarbandSnapshot): WarbandSnapshot = json.decodeFromString(json.encodeToString(state))

    fun cloneFrame(frame: WarbandFrame): WarbandFrame = json.decodeFromString(json.encodeToString(frame))

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                json.encodeToString(key) + ":" + canonicalJson(value)
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
        else -> element.toString()
    }
}
