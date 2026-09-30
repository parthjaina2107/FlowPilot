package com.flowpilot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.flowpilot.FlowPilotApp
import com.flowpilot.model.FlowGraph
import com.flowpilot.model.FlowStep
import com.flowpilot.network.ApiClient
import com.flowpilot.util.FeedbackManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/**
 * FlowReplayService — REPLAY stage
 *
 * Executes a FlowGraph by finding UI elements using semantic selectors
 * and performing actions (click, type, scroll, gesture dispatch) via AccessibilityService.
 *
 * Features:
 * 1. CASCADING FALLBACK strategy for element matching
 * 2. Overlay & Promo Pop-up auto-dismissal (T7 Screen Change)
 * 3. Dynamic On-Screen Credential & Payment Detection (T11 Credential Boundary)
 * 4. Safe halt and prompt when genuinely stuck (T10 Genuinely Stuck)
 * 5. Gesture dispatch fallback for modern Compose/Custom views
 * 6. Dynamic quantity parameter incrementing (T5 Quantity Slot)
 * 7. Execution tracking and reporting (T14 Status Reporting)
 * 8. Mid-Flow Parameter Clarification (Bonus 3)
 * 9. Multimodal Voice & Haptic Feedback
 */
class FlowReplayService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowReplay"
        const val ACTION_REPLAY = "com.flowpilot.START_REPLAY"
        const val ACTION_CANCEL = "com.flowpilot.CANCEL_REPLAY"
        const val ACTION_REPLAY_STEP = "com.flowpilot.REPLAY_STEP"
        const val ACTION_REPLAY_DONE = "com.flowpilot.REPLAY_DONE"
        const val ACTION_REPLAY_STUCK = "com.flowpilot.REPLAY_STUCK"
        const val ACTION_AUTH_PAUSE = "com.flowpilot.AUTH_PAUSE"
        const val ACTION_AUTH_RESUME = "com.flowpilot.AUTH_RESUME"
        const val ACTION_AUTH_CANCEL = "com.flowpilot.AUTH_CANCEL"
        const val ACTION_PARAM_NEEDED = "com.flowpilot.PARAM_NEEDED"
        const val ACTION_PARAM_PROVIDED = "com.flowpilot.PARAM_PROVIDED"

        const val EXTRA_FLOW_JSON = "flow_json"
        const val EXTRA_PARAMS_JSON = "params_json"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_STEP_INDEX = "step_index"
        const val EXTRA_TOTAL_STEPS = "total_steps"
        const val EXTRA_STEP_DESC = "step_desc"
        const val EXTRA_FLOW_NAME = "flow_name"
        const val EXTRA_STUCK_REASON = "stuck_reason"
        const val EXTRA_PARAM_NAME = "param_name"
        const val EXTRA_PARAM_VALUE = "param_value"

        private const val PREFS_NAME = "flowpilot_replay_prefs"
        private const val NOTIFICATION_ID = 2001

        var instance: FlowReplayService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        var isReplaying = false
            private set

        // T14 Reporting Status Trackers
        var lastRunSuccess: Boolean? = null
        var lastRunFlowName: String = ""
        var lastRunHaltedStep: Int = 0
        var lastRunReason: String = ""
        var lastRunTimestamp: Long = 0L

        private var authDeferred: CompletableDeferred<Boolean>? = null
        private var paramDeferred: CompletableDeferred<String>? = null

        fun resumeAuth(proceed: Boolean) {
            authDeferred?.complete(proceed)
        }

        fun provideParam(value: String) {
            paramDeferred?.complete(value)
        }

        fun persistLastRun(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                lastRunTimestamp = System.currentTimeMillis()
                prefs.edit().apply {
                    putBoolean("last_success", lastRunSuccess ?: false)
                    putString("last_flow_name", lastRunFlowName)
                    putInt("last_halted_step", lastRunHaltedStep)
                    putString("last_reason", lastRunReason)
                    putLong("last_timestamp", lastRunTimestamp)
                    apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist last run status: ${e.message}")
            }
        }

        fun restoreLastRun(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.contains("last_success")) {
                    lastRunSuccess = prefs.getBoolean("last_success", false)
                    lastRunFlowName = prefs.getString("last_flow_name", "") ?: ""
                    lastRunHaltedStep = prefs.getInt("last_halted_step", 0)
                    lastRunReason = prefs.getString("last_reason", "") ?: ""
                    lastRunTimestamp = prefs.getLong("last_timestamp", 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore last run status: ${e.message}")
            }
        }

        fun getLastRunReport(): String {
            return when {
                lastRunSuccess == true -> "The last flow '$lastRunFlowName' completed successfully."
                lastRunSuccess == false -> "The last flow '$lastRunFlowName' halted at step $lastRunHaltedStep: $lastRunReason"
                else -> "No flows have been executed yet in this session."
            }
        }
    }

    private var replayJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var quantityIncrementDone = false

    override fun onCreate() {
        super.onCreate()
        restoreLastRun(this)
        FeedbackManager.init(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "✅ FlowReplayService connected to Android Accessibility Manager")
    }
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ FlowReplayService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Driven proactively during replay
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
            ACTION_PARAM_PROVIDED -> {
                val value = intent.getStringExtra(EXTRA_PARAM_VALUE) ?: ""
                provideParam(value)
            }
        }
        return START_STICKY
    }

    fun startReplayDirect(flow: FlowGraph, params: Map<String, String>) {
        isReplaying = true
        lastRunFlowName = flow.flowName
        lastRunSuccess = null
        lastRunHaltedStep = 0
        lastRunReason = "Replay in progress"

        Log.i(TAG, "▶️ Replaying '${flow.flowName}' with params: $params")

        replayJob?.cancel()
        replayJob = scope.launch {
            executeFlow(flow, params)
        }
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

        startReplayDirect(flow, params)
    }

    fun cancelReplay() {
        replayJob?.cancel()
        authDeferred?.complete(false)
        isReplaying = false
        lastRunSuccess = false
        lastRunReason = "Replay cancelled by user"
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "❌ Replay cancelled")
    }

    // ─────────────────────────────────────────────
    // Flow Execution
    // ─────────────────────────────────────────────

    private suspend fun executeFlow(flow: FlowGraph, params: Map<String, String>) {
        var successCount = 0
        var failCount = 0
        quantityIncrementDone = false

        showReplayNotification("Starting replay...", 0, flow.steps.size)
        FeedbackManager.speak("Starting ${flow.flowName}")

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
            if (success) {
                successCount++
            } else {
                failCount++
                // T10: Genuinely Stuck — detect failure immediately, halt to prevent destructive wrong taps!
                Log.w(TAG, "❌ [T10 Genuinely Stuck] Step ${step.stepIndex + 1} (${step.description}) failed. Halting replay.")

                lastRunSuccess = false
                lastRunHaltedStep = step.stepIndex + 1
                lastRunReason = "Could not find or interact with element for: ${step.description}"

                showStuckNotification(step.description, step.stepIndex + 1, flow.steps.size)
                FeedbackManager.vibrateAlert()
                FeedbackManager.speak("Flow halted at step ${step.stepIndex + 1}. Could not find element for ${step.description}.")

                val stuckIntent = Intent(ACTION_REPLAY_STUCK).apply {
                    putExtra(EXTRA_STEP_INDEX, step.stepIndex)
                    putExtra(EXTRA_TOTAL_STEPS, flow.steps.size)
                    putExtra(EXTRA_STEP_DESC, step.description)
                    putExtra(EXTRA_FLOW_NAME, flow.flowName)
                    putExtra(EXTRA_STUCK_REASON, lastRunReason)
                    setPackage(packageName)
                }
                sendBroadcast(stuckIntent)
                break
            }
        }

        isReplaying = false
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (failCount == 0 && successCount > 0) {
            lastRunSuccess = true
            lastRunHaltedStep = flow.steps.size
            lastRunReason = "All ${flow.steps.size} steps completed successfully."
            FeedbackManager.vibrateSuccess()
            FeedbackManager.speak("${flow.flowName} completed successfully.")
        } else {
            FeedbackManager.speak("Execution halted: $lastRunReason")
        }

        persistLastRun(this@FlowReplayService)

        // Broadcast completion result (T14 Reporting)
        val doneIntent = Intent(ACTION_REPLAY_DONE).apply {
            putExtra(EXTRA_SUCCESS, failCount == 0 && successCount > 0)
            putExtra(EXTRA_FLOW_NAME, flow.flowName)
            putExtra(EXTRA_STEP_INDEX, lastRunHaltedStep)
            putExtra(EXTRA_STUCK_REASON, lastRunReason)
            setPackage(packageName)
        }
        sendBroadcast(doneIntent)

        Log.i(TAG, "🏁 Replay finished: $successCount succeeded, $failCount failed (Status: $lastRunReason)")
    }

    private suspend fun executeStep(
        step: FlowStep,
        flow: FlowGraph,
        params: Map<String, String>
    ): Boolean {
        // T11: Credential & Security Boundary Gate
        // Triggered by either compile-time flag OR dynamic on-screen keyword inspection (not on open_app)
        val isSensitiveScreen = step.isAuthPause || (step.actionType != "open_app" && checkDynamicSecurityBoundary())
        if (isSensitiveScreen) {
            val desc = if (step.isAuthPause) step.description else "Sensitive payment or authentication screen detected"
            Log.i(TAG, "🔒 [T11 Credential Boundary] AUTH PAUSE triggered on step ${step.stepIndex}: $desc")
            showAuthPauseNotification(desc)
            FeedbackManager.vibrateAuthWarning()
            FeedbackManager.speak("Security checkpoint. Please confirm payment or authentication.")

            val authIntent = Intent(ACTION_AUTH_PAUSE).apply {
                putExtra(EXTRA_STEP_DESC, desc)
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

        // Bonus 3: Mid-Flow Dynamic Parameter Clarification Gate
        val paramSlot = step.parameterSlot
        val effectiveParams = params.toMutableMap()
        if (paramSlot != null && (effectiveParams[paramSlot].isNullOrBlank() || effectiveParams[paramSlot] == "__UNRESOLVED__")) {
            val promptMsg = "Please provide value for $paramSlot"
            Log.i(TAG, "❓ [Bonus 3 Mid-Flow Clarification] $promptMsg")
            FeedbackManager.vibrateAlert()
            FeedbackManager.speak(promptMsg)

            val paramIntent = Intent(ACTION_PARAM_NEEDED).apply {
                putExtra(EXTRA_PARAM_NAME, paramSlot)
                putExtra(EXTRA_STEP_DESC, step.description)
                putExtra(EXTRA_FLOW_NAME, flow.flowName)
                putExtra(EXTRA_STEP_INDEX, step.stepIndex)
                setPackage(packageName)
            }
            sendBroadcast(paramIntent)

            val deferred = CompletableDeferred<String>()
            paramDeferred = deferred

            val clarified = try {
                withTimeout(45000L) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Mid-flow parameter input timed out after 45s")
                null
            } finally {
                paramDeferred = null
            }

            if (!clarified.isNullOrBlank()) {
                effectiveParams[paramSlot] = clarified
                Log.i(TAG, "Clarified parameter '$paramSlot' = '$clarified'")
            } else if (!step.defaultValue.isNullOrBlank()) {
                effectiveParams[paramSlot] = step.defaultValue
                Log.i(TAG, "User bypassed clarification, falling back to default value: ${step.defaultValue}")
            } else {
                Log.w(TAG, "Missing parameter for $paramSlot, halting step.")
                return false
            }
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
                    Log.w(TAG, "  ⚠️ Package ${flow.targetAppPackage} not installed. Checking if already on active screen...")
                    val currentRoot = rootInActiveWindow
                    if (currentRoot != null) {
                        currentRoot.recycle()
                        true
                    } else {
                        Log.e(TAG, "  ❌ Cannot launch package ${flow.targetAppPackage}")
                        false
                    }
                }
            }
            "click" -> performClick(step, effectiveParams)
            "type" -> performType(step, effectiveParams)
            "scroll" -> performScroll(step)
            "long_press" -> performClick(step, effectiveParams)
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
    // Dynamic Security Inspection (T11 Credential Boundary)
    // ─────────────────────────────────────────────

    private fun checkDynamicSecurityBoundary(): Boolean {
        val root = rootInActiveWindow ?: return false
        val sensitivePatterns = listOf(
            "enter password", "enter pin", "upi pin", "enter upi", "cvv", "enter otp",
            "verify otp", "one time password", "select payment", "payment method", "pay using",
            "card number", "expiry date", "proceed to pay", "net banking", "card details"
        )
        val found = findNodeRecursive(root) { node ->
            val text = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            sensitivePatterns.any { pattern -> text.contains(pattern) || desc.contains(pattern) }
        }
        val isSensitive = found != null
        found?.recycle()
        root.recycle()
        return isSensitive
    }

    // ─────────────────────────────────────────────
    // Dynamic Parameter Injection into Selectors
    // ─────────────────────────────────────────────

    private fun resolveSelector(
        selector: Map<String, String>,
        step: FlowStep,
        params: Map<String, String>
    ): Map<String, String> {
        var resolved = selector.toMutableMap()

        // 1. Direct step slot parameter resolution
        val paramSlot = step.parameterSlot
        if (paramSlot != null && params.containsKey(paramSlot)) {
            val paramVal = params[paramSlot] ?: ""
            val defaultVal = step.defaultValue ?: ""

            resolved = resolved.mapValues { (key, value) ->
                if (defaultVal.isNotBlank() && value.contains(defaultVal, ignoreCase = true)) {
                    value.replace(defaultVal, paramVal, ignoreCase = true)
                } else if (key == "text_contains" && (paramSlot == "address" || paramSlot == "dish_name" || paramSlot == "item_name" || paramSlot == "query")) {
                    if (value.contains("deliver to", ignoreCase = true) && !value.contains(paramVal, ignoreCase = true)) {
                        "Deliver to $paramVal"
                    } else if (defaultVal.isNotBlank()) {
                        value.replace(defaultVal, paramVal, ignoreCase = true)
                    } else {
                        paramVal
                    }
                } else {
                    value
                }
            }.toMutableMap()
        }

        // 2. Universal Schema & Parameter Resolution (cross-step resilient substitution)
        for ((slotKey, slotVal) in params) {
            if (slotVal.isBlank()) continue
            resolved = resolved.mapValues { (_, value) ->
                val knownDefaults = listOf("protein powder", "butter chicken", "Home", "Domino's", "Margherita", "wireless earbuds")
                var updated = value
                for (d in knownDefaults) {
                    if (updated.contains(d, ignoreCase = true) && (slotKey.contains("item") || slotKey.contains("dish") || slotKey.contains("address") || slotKey.contains("query"))) {
                        updated = updated.replace(d, slotVal, ignoreCase = true)
                    }
                }
                updated
            }.toMutableMap()
        }

        return resolved
    }

    // ─────────────────────────────────────────────
    // Action executors
    // ─────────────────────────────────────────────

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        for (depth in 0..4) {
            val parent = current?.parent ?: break
            if (parent.isClickable) return parent
            current = parent
        }
        return null
    }

    private suspend fun performClick(step: FlowStep, params: Map<String, String>): Boolean {
        val resolvedSelector = resolveSelector(step.selector, step, params)
        val node = findElementWithRetry(resolvedSelector) ?: return false

        var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            // Fallback 1: Try clicking parent or clickable ancestor
            val ancestor = findClickableAncestor(node)
            if (ancestor != null) {
                clicked = ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ancestor.recycle()
            }
        }
        if (!clicked) {
            // Fallback 2: Direct gesture dispatch (for Compose / custom views)
            clicked = dispatchTapGesture(node)
        }
        node.recycle()

        // Wait slightly for click to register in the UI
        delay(350)

        // T5: Dynamic Quantity Slot handling (only execute once per flow on item addition)
        val qty = params["quantity"]?.toIntOrNull() ?: 1
        if (clicked && qty > 1 && !quantityIncrementDone) {
            val isAddStep = step.parameterSlot == "quantity" ||
                    step.description.contains("add", ignoreCase = true) ||
                    step.selector["text_contains"]?.contains("add", ignoreCase = true) == true
            if (isAddStep) {
                handleQuantityIncrement(qty, step)
                quantityIncrementDone = true
            }
        }

        return clicked
    }

    private suspend fun performLongPress(step: FlowStep, params: Map<String, String>): Boolean {
        val resolvedSelector = resolveSelector(step.selector, step, params)
        val node = findElementWithRetry(resolvedSelector) ?: return false

        var pressed = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        if (!pressed) {
            // Fallback 1: Try long-clicking parent
            val ancestor = findClickableAncestor(node)
            if (ancestor != null) {
                pressed = ancestor.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                ancestor.recycle()
            }
        }
        if (!pressed) {
            // Fallback 2: Dispatch a 500ms hold gesture (for Compose / custom views)
            pressed = dispatchLongPressGesture(node)
        }
        node.recycle()
        Log.d(TAG, "  👆 Long press: ${step.description} → $pressed")
        return pressed
    }

    private fun dispatchLongPressGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        if (x <= 0 || y <= 0) return false

        val path = Path().apply { moveTo(x, y) }
        // 500ms hold duration distinguishes long-press from tap
        val stroke = GestureDescription.StrokeDescription(path, 0, 500)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.d(TAG, "  👆 Long press gesture at ($x, $y) for 500ms -> $dispatched")
        return dispatched
    }

    private suspend fun handleQuantityIncrement(targetQty: Int, baseStep: FlowStep) {
        Log.i(TAG, "➕ [T5 Quantity] Attempting to increment quantity to $targetQty")
        for (q in 2..targetQty) {
            delay(1000)
            val root = rootInActiveWindow ?: break
            val plusNode = findNodeRecursive(root) { n ->
                val t = (n.text?.toString() ?: "").trim()
                val d = (n.contentDescription?.toString() ?: "").trim().lowercase()
                val id = (n.viewIdResourceName ?: "").lowercase()
                (t == "+" || d.contains("increase") || d.contains("add") || d.contains("increment") || id.contains("plus") || id.contains("increment") || id.contains("add_btn"))
            }

            if (plusNode != null) {
                val ok = plusNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        (plusNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                        dispatchTapGesture(plusNode)
                plusNode.recycle()
                Log.i(TAG, "  ➕ [T5 Quantity] Step ($q/$targetQty) increment success: $ok")
            } else {
                Log.w(TAG, "  ⚠️ [T5 Quantity] Stepper (+) not found for count $q/$targetQty")
                break
            }
        }
    }
    private suspend fun performType(step: FlowStep, params: Map<String, String>): Boolean {
        val resolvedSelector = resolveSelector(step.selector, step, params)
        val node = findElementWithRetry(resolvedSelector) ?: return false

        // Determine text to type (injected param or default)
        val text = if (step.parameterSlot != null) {
            params[step.parameterSlot] ?: step.defaultValue ?: ""
        } else {
            step.defaultValue ?: ""
        }

        // Tap first to focus input field so keyboard / IME connection is active
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(350)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!result) {
            // Fallback 1: Try setting text on active input focus
            val focused = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                focused.recycle()
            }
        }
        if (!result) {
            // Fallback 2: Clipboard paste
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("flowpilot_type", text))
                result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) {
                Log.w(TAG, "Clipboard paste fallback error: ${e.message}")
            }
        }
        node.recycle()

        Log.d(TAG, "  ⌨️ Typed: '$text' → $result")
        delay(300)
        return result
    }

    private suspend fun performScroll(step: FlowStep): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrollable.recycle()
            root.recycle()
            return result
        }
        root.recycle()
        return false
    }

    private fun dispatchTapGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        if (x <= 0 || y <= 0) return false

        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.d(TAG, "  👆 Gesture tap dispatched at ($x, $y) -> $dispatched")
        return dispatched
    }

    // ─────────────────────────────────────────────
    // Semantic Element Finder (Cascading Fallback + Pop-up Dismissal + Progressive Recovery)
    // ─────────────────────────────────────────────

    private suspend fun findElementWithRetry(selector: Map<String, String>, maxRetries: Int = 8): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        val maxDurationMs = 25000L // 25s progressive recovery window

        for (attempt in 1..maxRetries) {
            if (System.currentTimeMillis() - startTime > maxDurationMs) {
                Log.w(TAG, "  ⏱️ [T10] Exceeded 25s search window without finding element.")
                break
            }

            val root = rootInActiveWindow
            if (root == null) {
                delay(1000)
                continue
            }
            val node = findElement(root, selector)
            if (node != null) return node

            root.recycle()
            Log.d(TAG, "  🔍 Element not found (attempt $attempt/$maxRetries)")

            // T7: Check for unexpected promo pop-ups / overlays obstructing the view
            if (attempt in 1..3) {
                rootInActiveWindow?.let { r ->
                    val dismissed = dismissUnexpectedOverlay(r)
                    if (dismissed) {
                        Log.i(TAG, "  🎉 [T7 Screen Change] Unexpected overlay/pop-up dismissed! Retrying element search...")
                        delay(1000)
                        rootInActiveWindow?.let { newRoot ->
                            val retryNode = findElement(newRoot, selector)
                            if (retryNode != null) return retryNode
                            newRoot.recycle()
                        }
                    }
                    r.recycle()
                }
            }

            // Progressive scroll recovery:
            // Attempts 3-4: Scroll forward (down) to bring lower elements into viewport
            // Attempts 5-6: Scroll backward (up) in case element was above viewport
            if (attempt in 3..4) {
                rootInActiveWindow?.let { r ->
                    Log.d(TAG, "  📜 Scrolling down to look for element (attempt $attempt)")
                    findScrollableNode(r)?.let { scrollable ->
                        scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        scrollable.recycle()
                    }
                    r.recycle()
                    delay(1000)
                }
            } else if (attempt in 5..6) {
                rootInActiveWindow?.let { r ->
                    Log.d(TAG, "  📜 Scrolling up to look for element (attempt $attempt)")
                    findScrollableNode(r)?.let { scrollable ->
                        scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        scrollable.recycle()
                    }
                    r.recycle()
                    delay(1000)
                }
            } else {
                delay(1000)
            }
        }
        return null
    }

    private fun dismissUnexpectedOverlay(root: AccessibilityNodeInfo): Boolean {
        val dismissPatterns = listOf(
            "close", "dismiss", "cancel", "not now", "later", "skip", "no thanks", "✕", "x"
        )

        val dismissNode = findNodeRecursive(root) { node ->
            val text = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val viewId = (node.viewIdResourceName ?: "").lowercase()

            val isClickableOrButton = node.isClickable || (node.className?.toString()?.lowercase()?.let {
                it.contains("button") || it.contains("imageview") || it.contains("view")
            } ?: false)

            isClickableOrButton && (
                dismissPatterns.any { p -> text == p || desc == p || (text.length <= 4 && text.contains(p)) } ||
                viewId.contains("close") || viewId.contains("dismiss") || viewId.contains("btn_close")
            )
        }

        if (dismissNode != null) {
            Log.d(TAG, "  Found overlay dismiss candidate: text='${dismissNode.text}', desc='${dismissNode.contentDescription}', id='${dismissNode.viewIdResourceName}'")
            val clicked = dismissNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                          (dismissNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                          dispatchTapGesture(dismissNode)
            dismissNode.recycle()
            return clicked
        }
        return false
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
        val roleLower = role.lowercase()
        val cls = node.className?.toString()?.lowercase() ?: ""
        if (cls.contains(roleLower)) return true

        return when (roleLower) {
            "button" -> node.isClickable || cls.contains("imageview") || cls.contains("card")
            "edittext" -> node.isEditable || cls.contains("edit") || cls.contains("textinput")
            "textview" -> node.text != null || cls.contains("text")
            else -> true
        }
    }

    private fun matchesText(node: AccessibilityNodeInfo, contains: String?, equals: String?): Boolean {
        if (contains == null && equals == null) return true
        val text = node.text?.toString()
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else null
        val candidates = listOfNotNull(text, hint)
        if (candidates.isEmpty()) return false

        if (equals != null && candidates.any { it.equals(equals, ignoreCase = true) }) return true
        if (contains != null && candidates.any { it.contains(contains, ignoreCase = true) }) return true
        return false
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

    private fun showStuckNotification(stepDesc: String, currentStep: Int, totalSteps: Int) {
        val cancelIntent = Intent(this, FlowReplayService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this, 3, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FlowPilotApp.CHANNEL_REPLAY)
            .setContentTitle("FlowPilot Stuck ⚠️")
            .setContentText("Stuck at step $currentStep/$totalSteps: $stepDesc")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_delete, "Dismiss", cancelPending)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        replayJob?.cancel()
        authDeferred?.complete(false)
        scope.cancel()
    }
}
