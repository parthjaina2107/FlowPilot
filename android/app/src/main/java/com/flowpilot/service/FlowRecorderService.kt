package com.flowpilot.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.flowpilot.FlowPilotApp
import com.flowpilot.model.UIAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * FlowRecorderService — LEARN stage
 *
 * An AccessibilityService that records user interactions across any Android app.
 * Captures clicks, text input, scrolls, and long presses as structured UIAction objects.
 *
 * Control via broadcast intents:
 *   - com.flowpilot.START_RECORDING (extras: flow_name, trigger_phrase, target_package)
 *   - com.flowpilot.STOP_RECORDING
 *
 * When recording stops, saves the trace as JSON and broadcasts RECORDING_COMPLETE.
 */
class FlowRecorderService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowRecorder"

        // Intent actions
        const val ACTION_START = "com.flowpilot.START_RECORDING"
        const val ACTION_STOP = "com.flowpilot.STOP_RECORDING"
        const val ACTION_COMPLETE = "com.flowpilot.RECORDING_COMPLETE"

        // Extras
        const val EXTRA_FLOW_NAME = "flow_name"
        const val EXTRA_TRIGGER_PHRASE = "trigger_phrase"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_TRACE_PATH = "trace_path"

        // Packages to ignore (system UI, launchers, phone dialers, incoming call screens)
        private val IGNORED_PACKAGES = setOf(
            "com.flowpilot",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.google.android.dialer",
            "com.android.phone",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.android.server.telecom"
        )

        // Notification
        private const val NOTIFICATION_ID = 1001

        // Debounce text changes (ms)
        private const val TEXT_DEBOUNCE_MS = 500L

        // State
        var isRecording = false
            private set
        var actionCount = 0
            private set
    }

    // Recording state
    private val recordedActions = mutableListOf<UIAction>()
    private var flowName = ""
    private var triggerPhrase = ""
    private var targetPackage = ""
    private var recordingStartTime = 0L

    // Text debouncing
    private val handler = Handler(Looper.getMainLooper())
    private var pendingTextAction: UIAction? = null
    private val textDebounceRunnable = Runnable {
        pendingTextAction?.let {
            recordedActions.add(it)
            actionCount = recordedActions.size
            Log.d(TAG, "  📝 Text committed: '${it.typedText}'")
        }
        pendingTextAction = null
    }

    // ─────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ FlowRecorderService connected")
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ FlowRecorderService interrupted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                flowName = intent.getStringExtra(EXTRA_FLOW_NAME) ?: "Unnamed Flow",
                triggerPhrase = intent.getStringExtra(EXTRA_TRIGGER_PHRASE) ?: "",
                targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: "",
            )
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    // ─────────────────────────────────────────────
    // Recording control
    // ─────────────────────────────────────────────

    private fun startRecording(flowName: String, triggerPhrase: String, targetPackage: String) {
        this.flowName = flowName
        this.triggerPhrase = triggerPhrase
        this.targetPackage = targetPackage
        this.recordedActions.clear()
        actionCount = 0
        this.recordingStartTime = System.currentTimeMillis()
        isRecording = true

        showRecordingNotification()
        Log.i(TAG, "🔴 Recording started: '$flowName' in $targetPackage")
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        // Flush any pending text action
        handler.removeCallbacks(textDebounceRunnable)
        pendingTextAction?.let {
            recordedActions.add(it)
            pendingTextAction = null
        }

        // Save trace
        val tracePath = saveTrace()

        // Broadcast completion
        val completeIntent = Intent(ACTION_COMPLETE).apply {
            putExtra(EXTRA_TRACE_PATH, tracePath)
            putExtra(EXTRA_FLOW_NAME, flowName)
            setPackage(packageName)
        }
        sendBroadcast(completeIntent)

        // Remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.i(TAG, "⏹ Recording stopped: ${recordedActions.size} actions captured → $tracePath")
    }

    // ─────────────────────────────────────────────
    // Event capture
    // ─────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecording || event == null) return

        val pkg = event.packageName?.toString() ?: return
        // Ignore our own app, system UI, and phone call/dialer screens (Bonus 1: Prune non-required touches)
        if (pkg in IGNORED_PACKAGES) {
            Log.d(TAG, "  🚫 [Bonus 1 Pruning] Ignored event in system/call package: $pkg")
            return
        }

        // Infer target package on first app interaction if not pre-specified
        if (targetPackage.isBlank()) {
            targetPackage = pkg
            Log.i(TAG, "  🎯 Inferred target package: $targetPackage")
        } else if (pkg != targetPackage) {
            Log.d(TAG, "  🚫 [Bonus 1 Pruning] Dropped out-of-target action in $pkg (target is $targetPackage)")
            return
        }

        val source = event.source ?: return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> captureClick(source, pkg)
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> captureLongClick(source, pkg)
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> captureTextChange(event, source, pkg)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> captureScroll(source, pkg)
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // Track activity changes (useful for flow context)
                    Log.d(TAG, "  🪟 Window: ${event.className} in $pkg")
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun captureClick(source: AccessibilityNodeInfo, pkg: String) {
        val action = buildUIAction("click", source, pkg)
        recordedActions.add(action)
        actionCount = recordedActions.size
        Log.d(TAG, "  👆 Click: '${action.elementText}' [${action.elementClass}]")
    }

    private fun captureLongClick(source: AccessibilityNodeInfo, pkg: String) {
        val action = buildUIAction("long_press", source, pkg)
        recordedActions.add(action)
        actionCount = recordedActions.size
        Log.d(TAG, "  👆 Long press: '${action.elementText}'")
    }

    private fun captureTextChange(event: AccessibilityEvent, source: AccessibilityNodeInfo, pkg: String) {
        // Debounce — only keep the final text after the user pauses
        val typedText = event.text?.joinToString("") ?: return
        val action = buildUIAction("type", source, pkg).copy(typedText = typedText)

        handler.removeCallbacks(textDebounceRunnable)
        pendingTextAction = action
        handler.postDelayed(textDebounceRunnable, TEXT_DEBOUNCE_MS)
    }

    private fun captureScroll(source: AccessibilityNodeInfo, pkg: String) {
        val action = buildUIAction("scroll", source, pkg).copy(scrollDirection = "down")
        recordedActions.add(action)
        actionCount = recordedActions.size
        Log.d(TAG, "  📜 Scroll in ${action.elementClass}")
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun buildUIAction(actionType: String, source: AccessibilityNodeInfo, pkg: String): UIAction {
        val rect = Rect()
        source.getBoundsInScreen(rect)

        return UIAction(
            timestamp = System.currentTimeMillis(),
            actionType = actionType,
            packageName = pkg,
            activityName = "",
            elementId = source.viewIdResourceName,
            elementClass = source.className?.toString() ?: "",
            elementText = source.text?.toString(),
            contentDescription = source.contentDescription?.toString(),
            bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
            typedText = null,
            scrollDirection = null
        )
    }

    private fun saveTrace(): String {
        val traceId = "trace_${System.currentTimeMillis()}"
        val trace = com.flowpilot.model.RecordingTrace(
            traceId = traceId,
            flowName = flowName,
            triggerPhrase = triggerPhrase,
            targetAppPackage = targetPackage,
            actions = recordedActions.toList(),
            recordedAt = java.time.Instant.now().toString()
        )

        val json = Json { prettyPrint = true }
        val jsonStr = json.encodeToString(trace)

        val dir = File(filesDir, "traces")
        dir.mkdirs()
        val file = File(dir, "$traceId.json")
        file.writeText(jsonStr)

        return file.absolutePath
    }

    private fun showRecordingNotification() {
        val stopIntent = Intent(this, FlowRecorderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FlowPilotApp.CHANNEL_RECORDING)
            .setContentTitle("FlowPilot Recording")
            .setContentText("Recording '$flowName'... Tap Stop when done.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
