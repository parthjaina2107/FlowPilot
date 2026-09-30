package com.flowpilot.service

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.flowpilot.FlowPilotApp
import com.flowpilot.model.FlowGraph
import com.flowpilot.model.FlowStep
import com.flowpilot.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/**
 * FlowReplayService — REPLAY stage
 *
 * Executes a FlowGraph by finding UI elements using semantic selectors
 * and performing actions (click, type, scroll) via AccessibilityService.
 *
 * Uses a CASCADING FALLBACK strategy for element matching:
 * 1. Exact match (all selector fields)
 * 2. Relaxed text (role + text_contains)
 * 3. Relaxed role (text only)
 * 4. Content description match
 */
class FlowReplayService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowReplay"
        const val ACTION_REPLAY = "com.flowpilot.START_REPLAY"
        const val ACTION_CANCEL = "com.flowpilot.CANCEL_REPLAY"
        const val ACTION_REPLAY_STEP = "com.flowpilot.REPLAY_STEP"
        const val ACTION_REPLAY_DONE = "com.flowpilot.REPLAY_DONE"
        const val ACTION_AUTH_PAUSE = "com.flowpilot.AUTH_PAUSE"
        const val ACTION_AUTH_RESUME = "com.flowpilot.AUTH_RESUME"
        const val ACTION_AUTH_CANCEL = "com.flowpilot.AUTH_CANCEL"

        const val EXTRA_FLOW_JSON = "flow_json"
        const val EXTRA_PARAMS_JSON = "params_json"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_STEP_INDEX = "step_index"
        const val EXTRA_TOTAL_STEPS = "total_steps"
        const val EXTRA_STEP_DESC = "step_desc"
        const val EXTRA_FLOW_NAME = "flow_name"

        private const val NOTIFICATION_ID = 2001

        var isReplaying = false
            private set

        private var authDeferred: CompletableDeferred<Boolean>? = null

        fun resumeAuth(proceed: Boolean) {
            authDeferred?.complete(proceed)
        }
    }

    private var replayJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ FlowReplayService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used during replay — we drive actions proactively
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPLAY -> {
                val flowJson = intent.getStringExtra(EXTRA_FLOW_JSON) ?: return START_NOT_STICKY
                val paramsJson = intent.getStringExtra(EXTRA_PARAMS_JSON) ?: "{}"
                startReplay(flowJson, paramsJson)
            }
            ACTION_CANCEL -> cancelReplay()
            ACTION_AUTH_RESUME -> resumeAuth(true)
            ACTION_AUTH_CANCEL -> resumeAuth(false)
        }
        return START_STICKY
    }

    private fun startReplay(flowJson: String, paramsJson: String) {
        val flow = try {
            ApiClient.gson.fromJson(flowJson, FlowGraph::class.java)
        } catch (e: Exception) {
            Json { ignoreUnknownKeys = true }.decodeFromString<FlowGraph>(flowJson)
        }

        val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
        val params: Map<String, String> = try {
            ApiClient.gson.fromJson(paramsJson, type) ?: emptyMap()
        } catch (e: Exception) {
            Json { ignoreUnknownKeys = true }.decodeFromString(paramsJson)
        }

        isReplaying = true
        Log.i(TAG, "▶️ Replaying '${flow.flowName}' with params: $params")

        replayJob = scope.launch {
            executeFlow(flow, params)
        }
    }

    private fun cancelReplay() {
        replayJob?.cancel()
        authDeferred?.complete(false)
        isReplaying = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "❌ Replay cancelled")
    }

    // ─────────────────────────────────────────────
    // Flow Execution
    // ─────────────────────────────────────────────

    private suspend fun executeFlow(flow: FlowGraph, params: Map<String, String>) {
        var successCount = 0
        var failCount = 0

        showReplayNotification("Starting replay...", 0, flow.steps.size)

        for (step in flow.steps) {
            if (!isReplaying) break

            showReplayNotification(
                "Step ${step.stepIndex + 1}/${flow.steps.size}: ${step.description}",
                step.stepIndex + 1,
                flow.steps.size
            )

            // Broadcast step execution for UI updates
            val stepIntent = Intent(ACTION_REPLAY_STEP).apply {
                putExtra(EXTRA_STEP_INDEX, step.stepIndex)
                putExtra(EXTRA_TOTAL_STEPS, flow.steps.size)
                putExtra(EXTRA_STEP_DESC, step.description)
                putExtra(EXTRA_FLOW_NAME, flow.flowName)
                setPackage(packageName)
            }
            sendBroadcast(stepIntent)

            // Wait before step
            delay(step.waitAfterMs.toLong())

            val success = executeStep(step, flow, params)
            if (success) successCount++ else failCount++
        }

        isReplaying = false
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Broadcast completion result
        val doneIntent = Intent(ACTION_REPLAY_DONE).apply {
            putExtra(EXTRA_SUCCESS, failCount == 0 && successCount > 0)
            putExtra(EXTRA_FLOW_NAME, flow.flowName)
            setPackage(packageName)
        }
        sendBroadcast(doneIntent)

        Log.i(TAG, "✅ Replay done: $successCount succeeded, $failCount failed")
    }

    private suspend fun executeStep(
        step: FlowStep,
        flow: FlowGraph,
        params: Map<String, String>
    ): Boolean {
        // Handle Security / Auth Pause (PPT Slide 6 & 10)
        if (step.isAuthPause) {
            Log.i(TAG, "🔒 AUTH PAUSE triggered on step ${step.stepIndex}: ${step.description}")
            showAuthPauseNotification(step.description)

            val authIntent = Intent(ACTION_AUTH_PAUSE).apply {
                putExtra(EXTRA_STEP_DESC, step.description)
                putExtra(EXTRA_FLOW_NAME, flow.flowName)
                putExtra(EXTRA_STEP_INDEX, step.stepIndex)
                setPackage(packageName)
            }
            sendBroadcast(authIntent)

            val deferred = CompletableDeferred<Boolean>()
            authDeferred = deferred

            val confirmed = try {
                withTimeout(60000L) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Auth pause timed out after 60s")
                false
            } finally {
                authDeferred = null
            }

            if (!confirmed) {
                Log.i(TAG, "User cancelled or auth timed out during auth pause.")
                return false
            }

            Log.i(TAG, "User completed authentication. Resuming replay.")
            delay(1500)
        }

        return when (step.actionType) {
            "open_app" -> {
                val launchIntent = packageManager.getLaunchIntentForPackage(flow.targetAppPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    delay(3000) // Wait for app to load
                    true
                } else {
                    Log.e(TAG, "  ❌ Cannot launch ${flow.targetAppPackage}")
                    false
                }
            }
            "click" -> performClick(step, params)
            "type" -> performType(step, params)
            "scroll" -> performScroll(step)
            "long_press" -> performClick(step, params)
            "wait" -> {
                delay(step.waitAfterMs.toLong())
                true
            }
            else -> {
                Log.w(TAG, "  ⚠️ Unknown action: ${step.actionType}")
                false
            }
        }
    }

    // ─────────────────────────────────────────────
    // Dynamic Parameter Injection into Selectors
    // ─────────────────────────────────────────────

    private fun resolveSelector(
        selector: Map<String, String>,
        step: FlowStep,
        params: Map<String, String>
    ): Map<String, String> {
        val paramSlot = step.parameterSlot ?: return selector
        val paramVal = params[paramSlot] ?: return selector
        val defaultVal = step.defaultValue
        if (defaultVal.isNullOrBlank()) return selector

        return selector.mapValues { (_, value) ->
            if (value.contains(defaultVal, ignoreCase = true)) {
                value.replace(defaultVal, paramVal, ignoreCase = true)
            } else {
                value
            }
        }
    }

    // ─────────────────────────────────────────────
    // Action executors
    // ─────────────────────────────────────────────

    private suspend fun performClick(step: FlowStep, params: Map<String, String>): Boolean {
        val resolvedSelector = resolveSelector(step.selector, step, params)
        val node = findElementWithRetry(resolvedSelector) ?: return false
        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!result) {
            // Try clicking parent
            node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        node.recycle()
        return true
    }

    private suspend fun performType(step: FlowStep, params: Map<String, String>): Boolean {
        val resolvedSelector = resolveSelector(step.selector, step, params)
        val node = findElementWithRetry(resolvedSelector) ?: return false

        // Determine text to type
        val text = if (step.parameterSlot != null) {
            params[step.parameterSlot] ?: step.defaultValue ?: ""
        } else {
            step.defaultValue ?: ""
        }

        // Focus and set text
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()

        Log.d(TAG, "  ⌨️ Typed: '$text' → $result")
        return result
    }

    private suspend fun performScroll(step: FlowStep): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrollable.recycle()
            return result
        }
        return false
    }

    // ─────────────────────────────────────────────
    // Semantic Element Finder (Cascading Fallback)
    // ─────────────────────────────────────────────

    private suspend fun findElementWithRetry(selector: Map<String, String>, maxRetries: Int = 3): AccessibilityNodeInfo? {
        for (attempt in 1..maxRetries) {
            val root = rootInActiveWindow
            if (root == null) {
                delay(1000)
                continue
            }
            val node = findElement(root, selector)
            if (node != null) return node

            Log.d(TAG, "  🔍 Element not found (attempt $attempt/$maxRetries)")
            delay(1000)

            // On last retry, try scrolling to bring element into view
            if (attempt == maxRetries - 1) {
                rootInActiveWindow?.let { r ->
                    findScrollableNode(r)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(1000)
                }
            }
        }
        return null
    }

    private fun findElement(root: AccessibilityNodeInfo, selector: Map<String, String>): AccessibilityNodeInfo? {
        val role = selector["role"]
        val textContains = selector["text_contains"]
        val textEquals = selector["text_equals"]
        val descContains = selector["content_description_contains"]
        val idContains = selector["resource_id_contains"]

        // Level 1: Exact match (all specified criteria)
        findNodeRecursive(root) { node ->
            matchesRole(node, role) &&
            matchesText(node, textContains, textEquals) &&
            matchesDescription(node, descContains) &&
            matchesId(node, idContains)
        }?.let { return it }

        // Level 2: Relaxed text (role + text_contains only)
        if (textContains != null || textEquals != null) {
            findNodeRecursive(root) { node ->
                matchesRole(node, role) && matchesText(node, textContains, textEquals)
            }?.let {
                Log.d(TAG, "  ⚡ Level 2 fallback match: role=$role, text=$textContains")
                return it
            }
        }

        // Level 3: Text only (ignore role)
        if (textContains != null || textEquals != null) {
            findNodeRecursive(root) { node ->
                matchesText(node, textContains, textEquals)
            }?.let {
                Log.d(TAG, "  ⚡ Level 3 fallback match: text=$textContains")
                return it
            }
        }

        // Level 4: Content description only
        if (descContains != null) {
            findNodeRecursive(root) { node ->
                matchesDescription(node, descContains)
            }?.let {
                Log.d(TAG, "  ⚡ Level 4 fallback match: desc=$descContains")
                return it
            }
        }

        return null
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeRecursive(child, predicate)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    // ─────────────────────────────────────────────
    // Matching helpers
    // ─────────────────────────────────────────────

    private fun matchesRole(node: AccessibilityNodeInfo, role: String?): Boolean {
        if (role == null) return true
        val cls = node.className?.toString()?.lowercase() ?: return false
        return cls.contains(role.lowercase())
    }

    private fun matchesText(node: AccessibilityNodeInfo, contains: String?, equals: String?): Boolean {
        val text = node.text?.toString() ?: return (contains == null && equals == null)
        if (equals != null && text.equals(equals, ignoreCase = true)) return true
        if (contains != null && text.contains(contains, ignoreCase = true)) return true
        return contains == null && equals == null
    }

    private fun matchesDescription(node: AccessibilityNodeInfo, contains: String?): Boolean {
        if (contains == null) return true
        val desc = node.contentDescription?.toString() ?: return false
        return desc.contains(contains, ignoreCase = true)
    }

    private fun matchesId(node: AccessibilityNodeInfo, contains: String?): Boolean {
        if (contains == null) return true
        val id = node.viewIdResourceName ?: return false
        return id.contains(contains, ignoreCase = true)
    }

    // ─────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────

    private fun showReplayNotification(text: String, current: Int, total: Int) {
        val cancelIntent = Intent(this, FlowReplayService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FlowPilotApp.CHANNEL_REPLAY)
            .setContentTitle("FlowPilot Replay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setProgress(total, current, false)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelPending)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showAuthPauseNotification(stepDesc: String) {
        val resumeIntent = Intent(this, FlowReplayService::class.java).apply {
            action = ACTION_AUTH_RESUME
        }
        val resumePending = PendingIntent.getService(
            this, 1, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, FlowReplayService::class.java).apply {
            action = ACTION_AUTH_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this, 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FlowPilotApp.CHANNEL_REPLAY)
            .setContentTitle("FlowPilot Security Pause 🔒")
            .setContentText("Complete authentication: $stepDesc")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_play, "I've Done It (Continue)", resumePending)
            .addAction(android.R.drawable.ic_delete, "Cancel Flow", cancelPending)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        replayJob?.cancel()
        authDeferred?.complete(false)
        scope.cancel()
    }
}
