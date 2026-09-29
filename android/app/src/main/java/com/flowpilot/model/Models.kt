package com.flowpilot.model

import kotlinx.serialization.Serializable

/**
 * A single recorded user action on screen.
 * Captured by AccessibilityService during the LEARN phase.
 */
@Serializable
data class UIAction(
    val timestamp: Long,
    val actionType: String,           // "click", "type", "scroll", "long_press"
    val packageName: String,
    val activityName: String = "",
    val elementId: String? = null,     // Android resource-id
    val elementClass: String = "",
    val elementText: String? = null,
    val contentDescription: String? = null,
    val bounds: String = "[0,0][0,0]",
    val typedText: String? = null,
    val scrollDirection: String? = null
)

/**
 * Raw recording from one user demonstration.
 * Sent to the backend for generalisation.
 */
@Serializable
data class RecordingTrace(
    val traceId: String,
    val flowName: String,
    val triggerPhrase: String,
    val targetAppPackage: String,
    val actions: List<UIAction>,
    val recordedAt: String
)

/**
 * One step in a generalised, reusable flow.
 */
@Serializable
data class FlowStep(
    val stepIndex: Int,
    val actionType: String,
    val selector: Map<String, String>,
    val parameterSlot: String? = null,
    val defaultValue: String? = null,
    val description: String,
    val waitAfterMs: Int = 1000,
    val isAuthPause: Boolean = false
)

/**
 * A generalised, reusable, parameterised flow.
 * Returned by the backend after Gemini compilation.
 */
@Serializable
data class FlowGraph(
    val flowId: String,
    val flowName: String,
    val description: String,
    val triggerPhrases: List<String>,
    val targetAppPackage: String,
    val parameterSchema: Map<String, ParameterDef> = emptyMap(),
    val steps: List<FlowStep>,
    val createdAt: String,
    val version: Int = 1
)

@Serializable
data class ParameterDef(
    val type: String,
    val description: String,
    val default: String? = null
)

/**
 * Response from the /api/match endpoints.
 */
@Serializable
data class MatchResult(
    val matched: Boolean,
    val flowId: String? = null,
    val flowName: String? = null,
    val confidence: Double? = null,
    val parameters: Map<String, String>? = null,
    val flowGraph: FlowGraph? = null,
    val transcribedText: String? = null,
    val suggestion: String? = null
)
