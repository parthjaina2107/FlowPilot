package com.flowpilot.service

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.flowpilot.FlowPilotApp
import com.flowpilot.model.FlowGraph
import com.flowpilot.model.FlowStep
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
 * 4. Fail with logging
 */
class FlowReplayService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowReplay"
        const val ACTION_REPLAY = "com.flowpilot.START_REPLAY"
        const val ACTION_CANCEL = "com.flowpilot.CANCEL_REPLAY"
        const val ACTION_REPLAY_DONE = "com.flowpilot.REPLAY_DONE"
        const val EXTRA_FLOW_JSON = "flow_json"
        const val EXTRA_PARAMS_JSON = "params_json"
        const val EXTRA_SUCCESS = "success"
        private const val NOTIFICATION_ID = 2001

        var isReplaying = false
            private set
    }

    private var replayJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ FlowReplayService interrupted")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
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
        }
        return START_STICKY
    }

    private fun startReplay(flowJson: String, paramsJson: String) {
        val json = Json { ignoreUnknownKeys = true }
        val flow = json.decodeFromString<FlowGraph>(flowJson)
        val params: Map<String, String> = json.decodeFromString(paramsJson)

        isReplaying = true
        Log.i(TAG, "▶️ Replaying '${flow.flowName}' with params: $params")

        replayJob = scope.launch {
            executeFlow(flow, params)
        }
    }

    private fun cancelReplay() {
        replayJob?.cancel()
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
                step.stepIndex,
                flow.steps.size
            )

            // Wait before step
            delay(step.waitAfterMs.toLong())

            val success = executeStep(step, flow.targetAppPackage, params)
            if (success) successCount++ else failCount++
        }

        isReplaying = false
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Broadcast result
        val doneIntent = Intent(ACTION_REPLAY_DONE).apply {
            putExtra(EXTRA_SUCCESS, failCount == 0)
            setPackage(packageName)
        }
        sendBroadcast(doneIntent)

        Log.i(TAG, "✅ Replay done: $successCount succeeded, $failCount failed")
    }

    private suspend fun executeStep(step: FlowStep, targetPackage: String, params: Map<String, String>): Boolean {
        return when (step.actionType) {
            "open_app" -> {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    delay(3000) // Wait for app to load
                    true
                } else {
                    Log.e(TAG, "  ❌ Cannot launch $targetPackage")
                    false
                }
            }
            "click" -> performClick(step)
            "type" -> performType(step, params)
            "scroll" -> performScroll(step)
            "long_press" -> performClick(step) // Simplified — use click for now
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
    // Action executors
    // ─────────────────────────────────────────────

    private suspend fun performClick(step: FlowStep): Boolean {
        val node = findElementWithRetry(step.selector) ?: return false
        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!result) {
            // Try clicking parent
            node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        node.recycle()
        return true
    }

    private suspend fun performType(step: FlowStep, params: Map<String, String>): Boolean {
        val node = findElementWithRetry(step.selector) ?: return false

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
            val root = rootInActiveWindow ?: run {
                delay(1000)
                continue
            }
            val node = findElement(root, selector)
            if (node != null) return node

            Log.d(TAG, "  🔍 Element not found (attempt $attempt/$maxRetries)")
            delay(1000)

            // On last retry, try scrolling
            if (attempt == maxRetries - 1) {
                val scrollable = findScrollableNode(root)
                scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(1000)
            }
        }
        Log.e(TAG, "  ❌ Element not found after $maxRetries attempts: $selector")
        return null
    }

    /**
     * Cascading fallback element finder:
     * 1. Exact match (all selector fields)
     * 2. Role + text_contains
     * 3. Text only
     */
    fun findElement(root: AccessibilityNodeInfo, selector: Map<String, String>): AccessibilityNodeInfo? {
        val role = selector["role"]
        val textContains = selector["text_contains"]
        val textEquals = selector["text_equals"]
        val descContains = selector["content_description_contains"]
        val idContains = selector["resource_id_contains"]

        // Strategy 1: Exact match
        findNodeRecursive(root) { node ->
            matchesRole(node, role) &&
            matchesText(node, textContains, textEquals) &&
            matchesDescription(node, descContains) &&
            matchesId(node, idContains)
        }?.let { return it }

        // Strategy 2: Role + text only
        if (role != null && (textContains != null || textEquals != null)) {
            findNodeRecursive(root) { node ->
                matchesRole(node, role) && matchesText(node, textContains, textEquals)
            }?.let { return it }
        }

        // Strategy 3: Text only
        if (textContains != null || textEquals != null) {
            findNodeRecursive(root) { node ->
                matchesText(node, textContains, textEquals)
            }?.let { return it }
        }

        // Strategy 4: Content description only
        if (descContains != null) {
            findNodeRecursive(root) { node ->
                matchesDescription(node, descContains)
            }?.let { return it }
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
    // Notification
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

    override fun onDestroy() {
        super.onDestroy()
        replayJob?.cancel()
        scope.cancel()
    }
}
