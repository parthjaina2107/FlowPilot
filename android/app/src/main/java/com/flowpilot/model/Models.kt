package com.flowpilot.model

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single recorded user action on screen.
 * Captured by AccessibilityService during the LEARN phase.
 */
@Serializable
data class UIAction(
    @SerializedName("timestamp") @SerialName("timestamp")
    val timestamp: Long,

    @SerializedName("action_type") @SerialName("action_type")
    val actionType: String,           // "click", "type", "scroll", "long_press"

    @SerializedName("package_name") @SerialName("package_name")
    val packageName: String,

    @SerializedName("activity_name") @SerialName("activity_name")
    val activityName: String = "",

    @SerializedName("element_id") @SerialName("element_id")
    val elementId: String? = null,     // Android resource-id

    @SerializedName("element_class") @SerialName("element_class")
    val elementClass: String = "",

    @SerializedName("element_text") @SerialName("element_text")
    val elementText: String? = null,

    @SerializedName("content_description") @SerialName("content_description")
    val contentDescription: String? = null,

    @SerializedName("bounds") @SerialName("bounds")
    val bounds: String = "[0,0][0,0]",

    @SerializedName("typed_text") @SerialName("typed_text")
    val typedText: String? = null,

    @SerializedName("scroll_direction") @SerialName("scroll_direction")
    val scrollDirection: String? = null
)

/**
 * Raw recording from one user demonstration.
 * Sent to the backend for generalisation.
 */
@Serializable
data class RecordingTrace(
    @SerializedName("trace_id") @SerialName("trace_id")
    val traceId: String,

    @SerializedName("flow_name") @SerialName("flow_name")
    val flowName: String,

    @SerializedName("trigger_phrase") @SerialName("trigger_phrase")
    val triggerPhrase: String,

    @SerializedName("target_app_package") @SerialName("target_app_package")
    val targetAppPackage: String,

    @SerializedName("actions") @SerialName("actions")
    val actions: List<UIAction>,

    @SerializedName("recorded_at") @SerialName("recorded_at")
    val recordedAt: String
)

/**
 * One step in a generalised, reusable flow.
 */
@Serializable
data class FlowStep(
    @SerializedName("step_index") @SerialName("step_index")
    val stepIndex: Int,

    @SerializedName("action_type") @SerialName("action_type")
    val actionType: String,

    @SerializedName("selector") @SerialName("selector")
    val selector: Map<String, String>,

    @SerializedName("parameter_slot") @SerialName("parameter_slot")
    val parameterSlot: String? = null,

    @SerializedName("default_value") @SerialName("default_value")
    val defaultValue: String? = null,

    @SerializedName("description") @SerialName("description")
    val description: String,

    @SerializedName("wait_after_ms") @SerialName("wait_after_ms")
    val waitAfterMs: Int = 1000,

    @SerializedName("is_auth_pause") @SerialName("is_auth_pause")
    val isAuthPause: Boolean = false
)

/**
 * A generalised, reusable, parameterised flow.
 * Returned by the backend after Gemini compilation.
 */
@Serializable
data class FlowGraph(
    @SerializedName("flow_id") @SerialName("flow_id")
    val flowId: String,

    @SerializedName("flow_name") @SerialName("flow_name")
    val flowName: String,

    @SerializedName("description") @SerialName("description")
    val description: String,

    @SerializedName("trigger_phrases") @SerialName("trigger_phrases")
    val triggerPhrases: List<String>,

    @SerializedName("target_app_package") @SerialName("target_app_package")
    val targetAppPackage: String,

    @SerializedName("parameter_schema") @SerialName("parameter_schema")
    val parameterSchema: Map<String, ParameterDef> = emptyMap(),

    @SerializedName("steps") @SerialName("steps")
    val steps: List<FlowStep>,

    @SerializedName("created_at") @SerialName("created_at")
    val createdAt: String,

    @SerializedName("version") @SerialName("version")
    val version: Int = 1
)

@Serializable
data class ParameterDef(
    @SerializedName("type") @SerialName("type")
    val type: String,

    @SerializedName("description") @SerialName("description")
    val description: String,

    @SerializedName("default") @SerialName("default")
    val default: String? = null
)

/**
 * Response from the /api/match endpoints.
 */
@Serializable
data class MatchResult(
    @SerializedName("matched") @SerialName("matched")
    val matched: Boolean,

    @SerializedName("flow_id") @SerialName("flow_id")
    val flowId: String? = null,

    @SerializedName("flow_name") @SerialName("flow_name")
    val flowName: String? = null,

    @SerializedName("confidence") @SerialName("confidence")
    val confidence: Double? = null,

    @SerializedName("parameters") @SerialName("parameters")
    val parameters: Map<String, String>? = null,

    @SerializedName("flow_graph") @SerialName("flow_graph")
    val flowGraph: FlowGraph? = null,

    @SerializedName("transcribed_text") @SerialName("transcribed_text")
    val transcribedText: String? = null,

    @SerializedName("suggestion") @SerialName("suggestion")
    val suggestion: String? = null,

    @SerializedName("is_ambiguous") @SerialName("is_ambiguous")
    val isAmbiguous: Boolean = false,

    @SerializedName("clarification_prompt") @SerialName("clarification_prompt")
    val clarificationPrompt: String? = null,

    @SerializedName("ambiguity_options") @SerialName("ambiguity_options")
    val ambiguityOptions: List<String>? = null
)
