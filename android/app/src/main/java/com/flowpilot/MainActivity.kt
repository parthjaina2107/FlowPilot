package com.flowpilot

import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.flowpilot.audio.AudioRecordManager
import com.flowpilot.util.FeedbackManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.flowpilot.model.FlowGraph
import com.flowpilot.model.MatchResult
import com.flowpilot.model.RecordingTrace
import com.flowpilot.network.ApiClient
import com.flowpilot.service.FlowRecorderService
import com.flowpilot.service.FlowReplayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// ─────────────────────────────────────────────
// Theme Colors (Samsung-inspired dark aesthetic)
// ─────────────────────────────────────────────
val SamsungBlue = Color(0xFF1428A0)
val SamsungLightBlue = Color(0xFF4285F4)
val DarkBg = Color(0xFF0D1117)
val CardBg = Color(0xFF161B22)
val SurfaceBg = Color(0xFF21262D)
val AccentGreen = Color(0xFF3FB950)
val AccentOrange = Color(0xFFF0883E)
val AccentPurple = Color(0xFF8957E5)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)

enum class VoiceState {
    IDLE,
    LISTENING,
    PARTIAL_TRANSCRIPT,
    FINAL_TRANSCRIPT,
    MATCHING,
    CONFIRMATION,
    REPLAYING,
    DONE,
    NO_MATCH,
    ERROR
}

enum class VoiceErrorCode {
    NONE,
    MIC_PERMISSION_DENIED,
    MIC_INITIALIZATION_FAILED,
    MIC_NO_AUDIO,
    TRANSCRIPTION_FAILED,
    NETWORK_FAILED
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlowPilotTheme {
                FlowPilotNavigation()
            }
        }
    }
}

@Composable
fun FlowPilotTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = SamsungBlue,
        secondary = SamsungLightBlue,
        background = DarkBg,
        surface = CardBg,
        onPrimary = Color.White,
        onBackground = TextPrimary,
        onSurface = TextPrimary,
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@Composable
fun FlowPilotNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onRecordClick = { navController.navigate("record") },
                onFlowsClick = { navController.navigate("flows") }
            )
        }
        composable("record") {
            RecordScreen(
                onBack = { navController.popBackStack() },
                onFlowCompiled = { navController.navigate("flows") }
            )
        }
        composable("flows") {
            FlowListScreen(onBack = { navController.popBackStack() })
        }
    }
}

// ─────────────────────────────────────────────
// HOME SCREEN (Voice Command & Automation Hub)
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onRecordClick: () -> Unit, onFlowsClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
    var voiceErrorCode by remember { mutableStateOf(VoiceErrorCode.NONE) }
    var partialTranscript by remember { mutableStateOf("") }
    var finalTranscript by remember { mutableStateOf("") }
    var recognizedText by remember { mutableStateOf("") }
    var matchResult by remember { mutableStateOf<MatchResult?>(null) }
    var statusMessage by remember { mutableStateOf("Tap mic to speak a command") }
    var autoRunCountdown by remember { mutableIntStateOf(0) }
    var isDebugPanelExpanded by remember { mutableStateOf(false) }

    // Live Replay Progress State
    var replayFlowName by remember { mutableStateOf("") }
    var replayCurrentStep by remember { mutableIntStateOf(0) }
    var replayTotalSteps by remember { mutableIntStateOf(0) }
    var replayStepDesc by remember { mutableStateOf("") }

    // Auth Pause State (T11)
    var showAuthPauseDialog by remember { mutableStateOf(false) }
    var authPauseStepDesc by remember { mutableStateOf("") }
    var authPauseFlowName by remember { mutableStateOf("") }

    // Genuinely Stuck Dialog State (T10)
    var showStuckDialog by remember { mutableStateOf(false) }
    var stuckStepDesc by remember { mutableStateOf("") }
    var stuckFlowName by remember { mutableStateOf("") }
    var stuckReason by remember { mutableStateOf("") }

    // Ambiguity Clarification Dialog State (T13)
    var showAmbiguityDialog by remember { mutableStateOf(false) }
    var ambiguityPrompt by remember { mutableStateOf("") }
    var ambiguityOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingAmbiguousMatch by remember { mutableStateOf<MatchResult?>(null) }

    // Mid-Flow Parameter Clarification State (Bonus 3)
    var showParamNeededDialog by remember { mutableStateOf(false) }
    var neededParamName by remember { mutableStateOf("") }
    var neededParamStepDesc by remember { mutableStateOf("") }
    var neededParamFlowName by remember { mutableStateOf("") }
    var inputParamValue by remember { mutableStateOf("") }

    // Server Config Dialog State
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember { mutableStateOf(ApiClient.getBaseUrl()) }

    // Text To Speech Engine (Voice Agent Persona)
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var speechEngine: TextToSpeech? = null
        speechEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speechEngine?.language = java.util.Locale.US
            }
        }
        tts = speechEngine
        onDispose {
            try {
                speechEngine?.stop()
                speechEngine?.shutdown()
            } catch (ignored: Exception) {}
        }
    }

    fun speak(phrase: String) {
        try {
            tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "flowpilot_voice")
        } catch (e: Exception) {
            Log.w("FlowPilot", "TTS error: ${e.message}")
        }
    }

    // Speech Recognizer & Direct Audio Recorder instances
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    val audioRecorder = remember { AudioRecordManager(context) }

    // Check permission on launch
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Log.i("FlowPilot", "[VOICE] RECORD_AUDIO permission = GRANTED")
        }
    }

    // Permission launcher for audio recording
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("FlowPilot", "[VOICE] RECORD_AUDIO permission = GRANTED")
            voiceErrorCode = VoiceErrorCode.NONE
        } else {
            voiceErrorCode = VoiceErrorCode.MIC_PERMISSION_DENIED
            statusMessage = "Microphone permission is required."
            Toast.makeText(context, "Microphone permission required for voice commands", Toast.LENGTH_SHORT).show()
        }
    }

    fun startReplayForMatch(match: MatchResult) {
        val flow = match.flowGraph ?: return
        voiceState = VoiceState.REPLAYING
        replayFlowName = match.flowName ?: flow.flowName
        replayTotalSteps = flow.steps.size
        replayCurrentStep = 0
        speak("Executing $replayFlowName")
        FeedbackManager.speak("Executing $replayFlowName")

        val flowParams = match.parameters ?: emptyMap()
        val replayService = FlowReplayService.instance
        if (replayService != null) {
            replayService.startReplayDirect(flow, flowParams)
        } else {
            val replayIntent = Intent(context, FlowReplayService::class.java).apply {
                action = FlowReplayService.ACTION_REPLAY
                putExtra(FlowReplayService.EXTRA_FLOW_JSON, ApiClient.gson.toJson(flow))
                putExtra(FlowReplayService.EXTRA_PARAMS_JSON, ApiClient.gson.toJson(flowParams))
            }
            try {
                context.startService(replayIntent)
            } catch (e: Exception) {
                Log.e("FlowPilot", "Failed to start service", e)
            }
        }
    }

    // Function to process matched voice command
    fun executeVoiceCommand(text: String) {
        recognizedText = text
        finalTranscript = text

        // T14 Reporting: Check if user asks about status of previous run
        val lower = text.lowercase().trim()
        if (lower.contains("did the last run succeed") || lower.contains("last run status") || lower.contains("did it succeed") || lower.contains("status of last run")) {
            val lastSuccess = FlowReplayService.lastRunSuccess
            val lastFlow = FlowReplayService.lastRunFlowName
            val lastStep = FlowReplayService.lastRunHaltedStep
            val lastReason = FlowReplayService.lastRunReason

            voiceState = if (lastSuccess == true) VoiceState.DONE else VoiceState.ERROR
            val report = when (lastSuccess) {
                true -> "Last run '$lastFlow' SUCCEEDED! All $lastStep steps finished successfully 🎉"
                false -> "Last run '$lastFlow' STOPPED at step $lastStep: $lastReason"
                else -> "No flow has been executed yet in this session."
            }
            statusMessage = report
            speak(report)
            FeedbackManager.speak(report)
            return
        }

        voiceState = VoiceState.MATCHING
        statusMessage = "Understanding '$text'..."

        // Call backend matching endpoint
        scope.launch {
            try {
                val res = ApiClient.api.matchTextCommand(mapOf("command" to text))
                matchResult = res

                if (res.matched && res.flowGraph != null) {
                    // T13: Ambiguity Resolution — ask or confirm before executing
                    if (res.isAmbiguous) {
                        val prompt = res.clarificationPrompt ?: "Clarification needed"
                        statusMessage = prompt
                        ambiguityPrompt = prompt
                        ambiguityOptions = res.ambiguityOptions ?: listOf(res.flowName ?: "Confirm")
                        pendingAmbiguousMatch = res
                        showAmbiguityDialog = true
                        speak(prompt)
                        FeedbackManager.speak(prompt)
                        return@launch
                    }

                    voiceState = VoiceState.CONFIRMATION
                    statusMessage = "Matched '${res.flowName}' (${((res.confidence ?: 0.9) * 100).toInt()}% confidence)"
                    speak("Matched ${res.flowName}")
                    FeedbackManager.speak("Matched ${res.flowName}")

                    // 3-second auto-start countdown
                    autoRunCountdown = 3
                    while (autoRunCountdown > 0 && voiceState == VoiceState.CONFIRMATION) {
                        delay(1000)
                        autoRunCountdown--
                    }
                    if (voiceState == VoiceState.CONFIRMATION && autoRunCountdown == 0) {
                        startReplayForMatch(res)
                    }
                } else {
                    voiceState = VoiceState.NO_MATCH
                    val msg = res.suggestion ?: "No matching flow found for '$text'."
                    statusMessage = msg
                    speak(msg)
                    FeedbackManager.speak(msg)
                }
            } catch (e: Exception) {
                Log.e("FlowPilot", "Match error", e)
                voiceState = VoiceState.ERROR
                voiceErrorCode = VoiceErrorCode.NETWORK_FAILED
                statusMessage = "Failed to reach backend: ${e.localizedMessage}"
                speak("Could not reach backend server.")
                FeedbackManager.speak("Could not reach backend server.")
            }
        }
    }

    // Function to process recorded audio file via backend STT (Whisper / Google STT / Gemini)
    fun executeAudioCommand(audioFile: File) {
        if (!audioFile.exists() || audioFile.length() <= 44) {
            voiceState = VoiceState.ERROR
            voiceErrorCode = VoiceErrorCode.MIC_NO_AUDIO
            statusMessage = "Microphone started, but no audio was detected."
            speak("No audio detected.")
            return
        }

        voiceState = VoiceState.MATCHING
        statusMessage = "Understanding voice command..."

        scope.launch {
            try {
                Log.i("FlowPilot", "[VOICE] Uploading audio (${audioFile.length()} bytes)")
                val reqBody = audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("audio", audioFile.name, reqBody)
                val res = ApiClient.api.matchAudioCommand(part)
                matchResult = res
                val heardText = res.transcribedText?.trim() ?: ""
                finalTranscript = heardText
                recognizedText = heardText

                Log.i("FlowPilot", "[VOICE] HTTP 200, Transcript=\"$heardText\"")

                if (heardText.isNotBlank()) {
                    Log.i("FlowPilot", "🎙️ STT Transcribed: '$heardText'")
                }

                if (res.matched && res.flowGraph != null) {
                    if (res.isAmbiguous) {
                        val prompt = res.clarificationPrompt ?: "Clarification needed"
                        statusMessage = prompt
                        ambiguityPrompt = prompt
                        ambiguityOptions = res.ambiguityOptions ?: listOf(res.flowName ?: "Confirm")
                        pendingAmbiguousMatch = res
                        showAmbiguityDialog = true
                        speak(prompt)
                        FeedbackManager.speak(prompt)
                        return@launch
                    }

                    voiceState = VoiceState.CONFIRMATION
                    statusMessage = "Matched '${res.flowName}' (${((res.confidence ?: 0.9) * 100).toInt()}% confidence)"
                    speak("Matched ${res.flowName}")
                    FeedbackManager.speak("Matched ${res.flowName}")

                    // 3-second auto-start countdown
                    autoRunCountdown = 3
                    while (autoRunCountdown > 0 && voiceState == VoiceState.CONFIRMATION) {
                        delay(1000)
                        autoRunCountdown--
                    }
                    if (voiceState == VoiceState.CONFIRMATION && autoRunCountdown == 0) {
                        startReplayForMatch(res)
                    }
                } else {
                    if (heardText.isBlank()) {
                        voiceState = VoiceState.ERROR
                        voiceErrorCode = VoiceErrorCode.TRANSCRIPTION_FAILED
                        val msg = res.suggestion ?: "Audio was captured but transcription failed. Please try again."
                        statusMessage = msg
                        speak(msg)
                    } else {
                        voiceState = VoiceState.NO_MATCH
                        val msg = res.suggestion ?: "No matching flow for '$heardText'"
                        statusMessage = msg
                        speak(msg)
                    }
                }
            } catch (e: Exception) {
                Log.e("FlowPilot", "[VOICE] Audio transcription error", e)
                voiceState = VoiceState.ERROR
                voiceErrorCode = VoiceErrorCode.NETWORK_FAILED
                statusMessage = "Backend transcription service is unavailable: ${e.localizedMessage}"
                speak("Backend service unavailable.")
            }
        }
    }

    // Stop recording and process audio via backend STT
    fun finishListeningAndProcess() {
        if (voiceState != VoiceState.LISTENING && voiceState != VoiceState.PARTIAL_TRANSCRIPT) return

        try {
            speechRecognizer?.stopListening()
        } catch (ignored: Exception) {}

        val wav = audioRecorder.stopRecording()
        if (finalTranscript.isNotBlank()) {
            executeVoiceCommand(finalTranscript)
        } else if (wav != null && wav.exists() && wav.length() > 44) {
            executeAudioCommand(wav)
        } else {
            voiceState = VoiceState.ERROR
            voiceErrorCode = VoiceErrorCode.MIC_NO_AUDIO
            statusMessage = "Microphone started, but no audio was detected."
            speak("No audio detected.")
        }
    }

    // System speech recognizer dialog launcher
    val speechIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!spoken.isNullOrEmpty()) {
                executeVoiceCommand(spoken[0])
            } else {
                voiceState = VoiceState.IDLE
                statusMessage = "No speech detected. Tap mic or type below."
            }
        } else {
            voiceState = VoiceState.IDLE
            statusMessage = "Tap mic, choose a quick prompt, or type below."
        }
    }

    // Function to start voice recognition via high-fidelity AudioRecord and Multi-Engine STT
    fun startListening() {
        if (!FlowReplayService.isRunning) {
            Toast.makeText(context, "⚠️ Please enable FlowPilot in Accessibility Settings first!", Toast.LENGTH_LONG).show()
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceErrorCode = VoiceErrorCode.MIC_PERMISSION_DENIED
            statusMessage = "Microphone permission is required."
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        Log.i("FlowPilot", "[VOICE] RECORD_AUDIO permission = GRANTED")

        voiceState = VoiceState.LISTENING
        voiceErrorCode = VoiceErrorCode.NONE
        partialTranscript = ""
        finalTranscript = ""
        recognizedText = ""
        autoRunCountdown = 0
        matchResult = null
        statusMessage = "Listening... Speak now 🎙️"
        rmsLevel = 0f

        // Start direct audio recording with chunk streaming and multi-factor tracking
        val started = audioRecorder.startRecording(
            audioSource = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            onRmsUpdate = { rms ->
                rmsLevel = rms
            },
            onSilenceDetected = {
                finishListeningAndProcess()
            },
            maxDurationMs = 7000L,
            onMaxDurationReached = {
                finishListeningAndProcess()
            },
            onChunkAvailable = { chunkWav ->
                // Background chunk transcription for live partial transcript
                scope.launch {
                    try {
                        val reqBody = chunkWav.asRequestBody("audio/wav".toMediaTypeOrNull())
                        val part = MultipartBody.Part.createFormData("audio", chunkWav.name, reqBody)
                        val res = ApiClient.api.matchAudioPartial(part)
                        val partial = (res["partial_transcript"] as? String)?.trim() ?: ""
                        if (partial.isNotBlank() && (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PARTIAL_TRANSCRIPT)) {
                            partialTranscript = partial
                            voiceState = VoiceState.PARTIAL_TRANSCRIPT
                        }
                    } catch (ignored: Exception) {}
                }
            }
        )

        if (!started) {
            voiceState = VoiceState.ERROR
            voiceErrorCode = VoiceErrorCode.MIC_INITIALIZATION_FAILED
            statusMessage = "Microphone initialization failed."
            return
        }
    }

    // Register BroadcastReceiver for Replay events (Step Progress, Auth Pause, Done)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    FlowReplayService.ACTION_REPLAY_STEP -> {
                        replayCurrentStep = intent.getIntExtra(FlowReplayService.EXTRA_STEP_INDEX, 0) + 1
                        replayTotalSteps = intent.getIntExtra(FlowReplayService.EXTRA_TOTAL_STEPS, 1)
                        replayStepDesc = intent.getStringExtra(FlowReplayService.EXTRA_STEP_DESC) ?: ""
                        replayFlowName = intent.getStringExtra(FlowReplayService.EXTRA_FLOW_NAME) ?: ""
                        voiceState = VoiceState.REPLAYING
                        statusMessage = "Executing step $replayCurrentStep/$replayTotalSteps: $replayStepDesc"
                    }
                    FlowReplayService.ACTION_AUTH_PAUSE -> {
                        authPauseStepDesc = intent.getStringExtra(FlowReplayService.EXTRA_STEP_DESC) ?: "Security verification"
                        authPauseFlowName = intent.getStringExtra(FlowReplayService.EXTRA_FLOW_NAME) ?: ""
                        showAuthPauseDialog = true
                        speak("Security verification required. Please verify on screen.")
                    }
                    FlowReplayService.ACTION_REPLAY_DONE -> {
                        val success = intent.getBooleanExtra(FlowReplayService.EXTRA_SUCCESS, false)
                        voiceState = if (success) VoiceState.DONE else VoiceState.ERROR
                        statusMessage = if (success) "Flow automation completed successfully! 🎉" else "Replay encountered an issue."
                        if (success) speak("Flow completed successfully!") else speak("Automation stopped.")
                    }
                    FlowReplayService.ACTION_REPLAY_STUCK -> {
                        stuckStepDesc = intent.getStringExtra(FlowReplayService.EXTRA_STEP_DESC) ?: "Unknown step"
                        stuckFlowName = intent.getStringExtra(FlowReplayService.EXTRA_FLOW_NAME) ?: ""
                        stuckReason = intent.getStringExtra(FlowReplayService.EXTRA_STUCK_REASON) ?: "Target UI element not found"
                        voiceState = VoiceState.ERROR
                        statusMessage = "FlowPilot got stuck at step $replayCurrentStep: $stuckStepDesc"
                        showStuckDialog = true
                        speak("Automation stopped. $stuckReason")
                    }
                    FlowReplayService.ACTION_PARAM_NEEDED -> {
                        neededParamName = intent.getStringExtra(FlowReplayService.EXTRA_PARAM_NAME) ?: "parameter"
                        neededParamStepDesc = intent.getStringExtra(FlowReplayService.EXTRA_STEP_DESC) ?: ""
                        neededParamFlowName = intent.getStringExtra(FlowReplayService.EXTRA_FLOW_NAME) ?: ""
                        inputParamValue = ""
                        showParamNeededDialog = true
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(FlowReplayService.ACTION_REPLAY_STEP)
            addAction(FlowReplayService.ACTION_AUTH_PAUSE)
            addAction(FlowReplayService.ACTION_REPLAY_DONE)
            addAction(FlowReplayService.ACTION_REPLAY_STUCK)
            addAction(FlowReplayService.ACTION_PARAM_NEEDED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: Exception) {}
            audioRecorder.stopRecording()
            speechRecognizer?.destroy()
        }
    }

    // Mic Pulse Animation
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PARTIAL_TRANSCRIPT) 1.25f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    var textCommandInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("FlowPilot", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SamsungBlue.copy(alpha = 0.3f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SamsungLightBlue.copy(alpha = 0.5f))
                        ) {
                            Text(
                                "AI AUTOMATION",
                                color = SamsungLightBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showServerConfigDialog = true }) {
                        Icon(Icons.Filled.Dns, contentDescription = "Server Config", tint = SamsungLightBlue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBg,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top: Status Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBg)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        statusMessage,
                        color = when (voiceState) {
                            VoiceState.LISTENING, VoiceState.PARTIAL_TRANSCRIPT -> SamsungLightBlue
                            VoiceState.MATCHING -> AccentOrange
                            VoiceState.CONFIRMATION, VoiceState.REPLAYING, VoiceState.DONE -> AccentGreen
                            VoiceState.NO_MATCH, VoiceState.ERROR -> AccentOrange
                            else -> TextSecondary
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    // Live Audio Level Visualizer (Phase 10)
                    if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PARTIAL_TRANSCRIPT) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "MIC INPUT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SamsungLightBlue
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val normalizedLevel = ((rmsLevel - 10f) / 35f).coerceIn(0.05f, 1f)
                            LinearProgressIndicator(
                                progress = { normalizedLevel },
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = if (normalizedLevel > 0.35f) AccentGreen else SamsungLightBlue,
                                trackColor = SurfaceBg
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${rmsLevel.toInt()} dB",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                        }
                    }

                    // Live Partial Transcript Section
                    if (voiceState == VoiceState.PARTIAL_TRANSCRIPT && partialTranscript.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SamsungBlue.copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SamsungLightBlue.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "LIVE TRANSCRIPT (PARTIAL)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SamsungLightBlue
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "\"$partialTranscript\"",
                                    color = TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Voice Energy: ${rmsLevel.toInt()} dB",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else if (recognizedText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "\"$recognizedText\"",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Phase 18: Inline Try Again Button on Error
                    if (voiceState == VoiceState.ERROR) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { startListening() },
                            colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Try Again", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Progress indicator for REPLAY
                    if (voiceState == VoiceState.REPLAYING && replayTotalSteps > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (replayCurrentStep.toFloat() / replayTotalSteps.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentGreen,
                            trackColor = SurfaceBg
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$replayFlowName — Step $replayCurrentStep of $replayTotalSteps",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Accessibility Warning Banner
            if (FlowReplayService.instance == null) {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AccentOrange.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentOrange.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = AccentOrange)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Accessibility Service Disabled",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                            Text(
                                "FlowPilot needs Accessibility to automate taps and typing on your screen.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Enable", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Center Area: Matched Flow Confirmation Card OR Mic Button
            if (voiceState == VoiceState.CONFIRMATION && matchResult != null && matchResult!!.flowGraph != null) {
                val flow = matchResult!!.flowGraph!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SamsungLightBlue.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = SamsungLightBlue, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Flow Matched", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextPrimary)
                            Spacer(modifier = Modifier.weight(1f))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AccentGreen.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen)
                            ) {
                                Text(
                                    "${((matchResult!!.confidence ?: 0.9) * 100).toInt()}% confidence",
                                    color = AccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "Command: \"${finalTranscript.ifBlank { recognizedText }}\"",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            matchResult!!.flowName ?: flow.flowName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = SamsungLightBlue
                        )
                        Text(
                            flow.description,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )

                        if (!matchResult!!.parameters.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Parameters / Slots:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
                            matchResult!!.parameters!!.forEach { (k, v) ->
                                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                                    Text("• $k: ", color = TextSecondary, fontSize = 13.sp)
                                    Text(v, color = AccentOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("App: ${flow.targetAppPackage}", fontSize = 11.sp, color = TextSecondary)

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    voiceState = VoiceState.IDLE
                                    autoRunCountdown = 0
                                    matchResult = null
                                    statusMessage = "Flow cancelled."
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel")
                            }

                            Button(
                                onClick = {
                                    autoRunCountdown = 0
                                    startReplayForMatch(matchResult!!)
                                },
                                modifier = Modifier.weight(1.5f),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (autoRunCountdown > 0) "Run Flow (${autoRunCountdown}s)" else "Run Flow",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                // Main Interactive Mic Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer pulsing glow
                        if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PARTIAL_TRANSCRIPT) {
                            Box(
                                modifier = Modifier
                                    .size(150.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(SamsungLightBlue.copy(alpha = 0.25f))
                            )
                        }

                        // Inner Main Button
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = when (voiceState) {
                                            VoiceState.LISTENING, VoiceState.PARTIAL_TRANSCRIPT -> listOf(Color(0xFFEA4335), Color(0xFFB31412))
                                            VoiceState.MATCHING -> listOf(AccentOrange, Color(0xFFC05621))
                                            VoiceState.REPLAYING -> listOf(AccentGreen, Color(0xFF238636))
                                            else -> listOf(SamsungLightBlue, SamsungBlue)
                                        }
                                    )
                                )
                                .clickable {
                                    if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PARTIAL_TRANSCRIPT) {
                                        finishListeningAndProcess()
                                    } else {
                                        startListening()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (voiceState) {
                                    VoiceState.LISTENING, VoiceState.PARTIAL_TRANSCRIPT -> Icons.Filled.Mic
                                    VoiceState.MATCHING -> Icons.Filled.HourglassTop
                                    VoiceState.REPLAYING -> Icons.Filled.PlayArrow
                                    VoiceState.DONE -> Icons.Filled.Check
                                    else -> Icons.Filled.Mic
                                },
                                contentDescription = "Voice command",
                                tint = Color.White,
                                modifier = Modifier.size(50.dp)
                            )
                        }
                    }

                    if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PARTIAL_TRANSCRIPT) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { finishListeningAndProcess() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Speaking", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Quick Voice Prompts & Text Command Bar
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Quick Voice Prompts",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val promptList = listOf(
                        "▶️ Play lofi on YouTube" to "Play lofi hip hop on YouTube",
                        "🍔 Order food on Zomato" to "Order butter chicken on Zomato",
                        "📦 Buy on Amazon" to "Buy protein powder on Amazon",
                        "🎵 Play on Spotify" to "Play Bohemian Rhapsody on Spotify",
                        "💬 WhatsApp Mom" to "Send WhatsApp message to Mom"
                    )
                    items(promptList) { (label, command) ->
                        SuggestionChip(
                            onClick = { executeVoiceCommand(command) },
                            label = { Text(label, fontSize = 12.sp, color = TextPrimary) },
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = CardBg),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = SurfaceBg
                            )
                        )
                    }
                }

                // Text Voice Command Input bar
                OutlinedTextField(
                    value = textCommandInput,
                    onValueChange = { textCommandInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Or type voice command...", color = TextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CardBg,
                        unfocusedContainerColor = CardBg,
                        focusedBorderColor = SamsungLightBlue,
                        unfocusedBorderColor = SurfaceBg,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (textCommandInput.isNotBlank()) {
                                    val cmd = textCommandInput.trim()
                                    textCommandInput = ""
                                    executeVoiceCommand(cmd)
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = "Run", tint = SamsungLightBlue)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (textCommandInput.isNotBlank()) {
                                val cmd = textCommandInput.trim()
                                textCommandInput = ""
                                executeVoiceCommand(cmd)
                            }
                        }
                    )
                )
            }

            // Phase 19: Collapsible Development Debug Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isDebugPanelExpanded = !isDebugPanelExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BugReport, contentDescription = null, tint = SamsungLightBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Development Debug Panel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                        Icon(
                            if (isDebugPanelExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    }

                    if (isDebugPanelExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = SurfaceBg)
                        Spacer(modifier = Modifier.height(10.dp))

                        val metrics = audioRecorder.lastMetrics
                        Text("🎙️ VOICE DIAGNOSTICS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SamsungLightBlue)
                        Text("• State: ${voiceState.name} | Error: ${voiceErrorCode.name}", fontSize = 11.sp, color = TextSecondary)
                        Text("• Source: ${metrics?.audioSource ?: "VOICE_RECOGNITION"} | 16kHz Mono", fontSize = 11.sp, color = TextSecondary)
                        Text("• Samples: ${metrics?.sampleCount ?: 0} (Non-zero: ${metrics?.nonZeroSamples ?: 0})", fontSize = 11.sp, color = TextSecondary)
                        Text("• Min/Max: [${metrics?.minSample ?: 0}, ${metrics?.maxSample ?: 0}] | Peak: ${metrics?.peakAmplitude ?: 0}", fontSize = 11.sp, color = TextSecondary)
                        Text("• RMS: ${String.format(java.util.Locale.US, "%.1f", metrics?.rmsEnergy ?: 0.0)} | Live Meter: ${rmsLevel.toInt()} dB", fontSize = 11.sp, color = TextSecondary)
                        Text("• Duration: ${audioRecorder.recordedDurationMs} ms | WAV: ${metrics?.wavFileSize ?: audioRecorder.recordedBytesCount} B", fontSize = 11.sp, color = TextSecondary)
                        Text("• Partial: \"${partialTranscript.ifBlank { "none" }}\"", fontSize = 11.sp, color = TextSecondary)
                        Text("• Final: \"${finalTranscript.ifBlank { "none" }}\"", fontSize = 11.sp, color = TextSecondary)

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("🎯 MATCH DIAGNOSTICS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                        Text("• Flow ID: ${matchResult?.flowId ?: "none"}", fontSize = 11.sp, color = TextSecondary)
                        Text("• Confidence: ${matchResult?.confidence?.let { "${(it * 100).toInt()}%" } ?: "N/A"}", fontSize = 11.sp, color = TextSecondary)
                        Text("• Slots: ${matchResult?.parameters ?: "none"}", fontSize = 11.sp, color = TextSecondary)

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("⚡ REPLAY DIAGNOSTICS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentOrange)
                        Text("• Service Connected: ${FlowReplayService.instance != null}", fontSize = 11.sp, color = TextSecondary)
                        Text("• Step: $replayCurrentStep of $replayTotalSteps ($replayStepDesc)", fontSize = 11.sp, color = TextSecondary)
                        Text("• Last Run: ${FlowReplayService.getLastRunReport()}", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }

            // Bottom Actions: Record New Flow & My Flows
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onRecordClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color.Red)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Teach / Record New Flow", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onFlowsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBg)
                ) {
                    Icon(Icons.Filled.List, contentDescription = null, tint = SamsungLightBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("My Flows (Saved Routines)", fontSize = 16.sp)
                }

                TextButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Accessibility Settings", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

    // Security Auth Pause Dialog (PPT Slide 6 & 10)
    if (showAuthPauseDialog) {
        AlertDialog(
            onDismissRequest = {
                showAuthPauseDialog = false
                FlowReplayService.resumeAuth(false)
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = AccentOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Security Auth Pause", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "The automation '$authPauseFlowName' has reached a protected step requiring your verification:",
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            authPauseStepDesc,
                            color = AccentOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Please authenticate with Fingerprint / PIN on your device, then tap Continue.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAuthPauseDialog = false
                        FlowReplayService.resumeAuth(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("I've Authenticated (Continue)")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showAuthPauseDialog = false
                        FlowReplayService.resumeAuth(false)
                    }
                ) {
                    Text("Cancel Flow")
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // T10: Genuinely Stuck Dialog — asks the user what to do when an unexpected screen or failure occurs
    if (showStuckDialog) {
        AlertDialog(
            onDismissRequest = {
                showStuckDialog = false
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = AccentOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("FlowPilot is Stuck", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "Automation '$stuckFlowName' stopped safely to prevent wrong taps:",
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "$stuckStepDesc\nReason: $stuckReason",
                            color = AccentOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "App language change, account logout, or UI obstruction detected. How would you like to proceed?",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStuckDialog = false
                        statusMessage = "Manual takeover active. You can now complete the task."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("I'll Take Over (Manual)")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showStuckDialog = false
                        FlowReplayService.instance?.cancelReplay() ?: run {
                            val cancelIntent = Intent(context, FlowReplayService::class.java).apply {
                                action = FlowReplayService.ACTION_CANCEL
                            }
                            try {
                                context.startService(cancelIntent)
                            } catch (e: Exception) {
                                Log.e("FlowPilot", "Failed to cancel service", e)
                            }
                        }
                        statusMessage = "Flow cancelled after being stuck."
                    }
                ) {
                    Text("Cancel Flow")
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // T13: Ambiguity Resolution Dialog — confirms or clarifies when intent/slots are broad or multiple flows match
    if (showAmbiguityDialog && pendingAmbiguousMatch != null) {
        val currentMatch = pendingAmbiguousMatch!!
        AlertDialog(
            onDismissRequest = {
                showAmbiguityDialog = false
                pendingAmbiguousMatch = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.HelpOutline, contentDescription = null, tint = SamsungLightBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clarification Needed", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        ambiguityPrompt,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ambiguityOptions.forEach { opt ->
                        Button(
                            onClick = {
                                showAmbiguityDialog = false
                                pendingAmbiguousMatch = null
                                if (currentMatch.flowGraph != null) {
                                    voiceState = VoiceState.REPLAYING
                                    replayFlowName = currentMatch.flowName ?: ""
                                    replayTotalSteps = currentMatch.flowGraph.steps.size
                                    replayCurrentStep = 0

                                    speak("Executing ${currentMatch.flowName ?: "flow"}")
                                    val replayService = FlowReplayService.instance
                                    val flowParams = currentMatch.parameters ?: emptyMap()
                                    if (replayService != null) {
                                        replayService.startReplayDirect(currentMatch.flowGraph, flowParams)
                                    } else {
                                        try {
                                            val replayIntent = Intent(context, FlowReplayService::class.java).apply {
                                                action = FlowReplayService.ACTION_REPLAY
                                                putExtra(FlowReplayService.EXTRA_FLOW_JSON, ApiClient.gson.toJson(currentMatch.flowGraph))
                                                putExtra(FlowReplayService.EXTRA_PARAMS_JSON, ApiClient.gson.toJson(flowParams))
                                            }
                                            context.startService(replayIntent)
                                        } catch (e: Exception) {
                                            statusMessage = "Please enable FlowPilot Accessibility Service in Android Settings"
                                            speak("Please enable FlowPilot in Accessibility settings first.")
                                            voiceState = VoiceState.IDLE
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(opt, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showAmbiguityDialog = false
                        pendingAmbiguousMatch = null
                        statusMessage = "Clarification cancelled."
                    }
                ) {
                    Text("Cancel")
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Bonus 3: Mid-Flow Dynamic Parameter Clarification Dialog
    if (showParamNeededDialog) {
        AlertDialog(
            onDismissRequest = {
                showParamNeededDialog = false
                val intent = Intent(context, FlowReplayService::class.java).apply {
                    action = FlowReplayService.ACTION_PARAM_PROVIDED
                    putExtra(FlowReplayService.EXTRA_PARAM_VALUE, "")
                }
                context.startService(intent)
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = SamsungLightBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Parameter Needed", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "Flow '$neededParamFlowName' needs '$neededParamName' to continue with '$neededParamStepDesc':",
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputParamValue,
                        onValueChange = { inputParamValue = it },
                        label = { Text("Enter $neededParamName") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showParamNeededDialog = false
                        val intent = Intent(context, FlowReplayService::class.java).apply {
                            action = FlowReplayService.ACTION_PARAM_PROVIDED
                            putExtra(FlowReplayService.EXTRA_PARAM_VALUE, inputParamValue)
                        }
                        context.startService(intent)
                        inputParamValue = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue)
                ) {
                    Text("Submit")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showParamNeededDialog = false
                        val intent = Intent(context, FlowReplayService::class.java).apply {
                            action = FlowReplayService.ACTION_PARAM_PROVIDED
                            putExtra(FlowReplayService.EXTRA_PARAM_VALUE, "")
                        }
                        context.startService(intent)
                        inputParamValue = ""
                    }
                ) {
                    Text("Skip / Cancel")
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showServerConfigDialog) {
        AlertDialog(
            onDismissRequest = { showServerConfigDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Dns, contentDescription = null, tint = SamsungLightBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Backend Server IP", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "Set backend server URL for API communication:",
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• Emulator default: http://10.0.2.2:8000/\n• USB ADB Reverse: http://127.0.0.1:8000/\n• Wi-Fi LAN: http://<laptop-ip>:8000/",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        ApiClient.setBaseUrl(serverUrlInput)
                        showServerConfigDialog = false
                        Toast.makeText(context, "Server URL updated: $serverUrlInput", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue)
                ) {
                    Text("Save & Apply")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showServerConfigDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ─────────────────────────────────────────────
// RECORD SCREEN (LEARN → GENERALISE Pipeline)
// ─────────────────────────────────────────────

enum class RecordState {
    IDLE,
    RECORDING,
    COMPILING,
    COMPILED,
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onBack: () -> Unit, onFlowCompiled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var flowName by remember { mutableStateOf("") }
    var triggerPhrase by remember { mutableStateOf("") }
    var targetPackage by remember { mutableStateOf("") }
    var recordState by remember { mutableStateOf(RecordState.IDLE) }
    var actionCount by remember { mutableIntStateOf(0) }
    var compilationStatus by remember { mutableStateOf("") }
    var compiledFlow by remember { mutableStateOf<FlowGraph?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    // Poll action count while recording
    LaunchedEffect(recordState) {
        while (recordState == RecordState.RECORDING) {
            actionCount = FlowRecorderService.actionCount
            delay(400)
        }
    }

    // BroadcastReceiver for RECORDING_COMPLETE -> triggers GEMINI FlowCompiler
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == FlowRecorderService.ACTION_COMPLETE) {
                    val tracePath = intent.getStringExtra(FlowRecorderService.EXTRA_TRACE_PATH) ?: return
                    Log.i("FlowPilot", "Trace captured at $tracePath. Starting compilation...")

                    recordState = RecordState.COMPILING
                    compilationStatus = "Reading captured demonstration..."

                    scope.launch {
                        try {
                            delay(500)
                            val traceFile = File(tracePath)
                            if (!traceFile.exists()) {
                                recordState = RecordState.ERROR
                                errorMessage = "Trace file not found at $tracePath"
                                return@launch
                            }

                            compilationStatus = "Abstracting UI selectors & parameterising slots via Gemini AI..."
                            val jsonText = traceFile.readText()
                            val trace = ApiClient.gson.fromJson(jsonText, RecordingTrace::class.java)

                            if (trace.actions.size < 2) {
                                recordState = RecordState.ERROR
                                errorMessage = "⚠️ Recording must contain at least 2 interactions to form a valid flow (captured ${trace.actions.size} action)."
                                FeedbackManager.speak("Recording must contain at least two interactions.")
                                return@launch
                            }

                            val resultFlow = ApiClient.api.compileTrace(trace)
                            compiledFlow = resultFlow
                            recordState = RecordState.COMPILED
                            compilationStatus = "Compiled '${resultFlow.flowName}' into ${resultFlow.steps.size} generalised steps!"
                        } catch (e: Exception) {
                            Log.e("FlowPilot", "Compilation error", e)
                            recordState = RecordState.ERROR
                            errorMessage = "Compilation failed: Backend server offline or error (${e.localizedMessage})"
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(FlowRecorderService.ACTION_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: Exception) {}
        }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Teach FlowPilot (Record)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBg,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (recordState == RecordState.COMPILING) {
                // Compiling with Gemini AI view
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SamsungLightBlue.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = SamsungLightBlue, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Gemini FlowCompiler Active", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            compilationStatus,
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (recordState == RecordState.COMPILED && compiledFlow != null) {
                // Compilation Success View
                val flow = compiledFlow!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Flow Generalised!", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(flow.flowName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = SamsungLightBlue)
                        Text(flow.description, fontSize = 13.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("⚡ Steps: ${flow.steps.size} generalised steps", fontSize = 13.sp, color = TextPrimary)
                        Text("🗣️ Triggers: ${flow.triggerPhrases.joinToString(", ")}", fontSize = 12.sp, color = TextSecondary)
                        if (flow.parameterSchema.isNotEmpty()) {
                            Text("🧩 Parameters: ${flow.parameterSchema.keys.joinToString(", ")}", fontSize = 12.sp, color = AccentOrange)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onFlowCompiled,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("View in My Flows")
                        }
                    }
                }
            } else {
                // Input form
                OutlinedTextField(
                    value = flowName,
                    onValueChange = { flowName = it },
                    label = { Text("Flow Name") },
                    placeholder = { Text("e.g., Buy Protein Powder on Amazon") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SamsungBlue,
                        unfocusedBorderColor = SurfaceBg,
                        focusedLabelColor = SamsungLightBlue,
                        cursorColor = SamsungLightBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true,
                    enabled = recordState != RecordState.RECORDING
                )

                OutlinedTextField(
                    value = triggerPhrase,
                    onValueChange = { triggerPhrase = it },
                    label = { Text("Natural Voice Trigger") },
                    placeholder = { Text("e.g., Order protein powder on Amazon") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SamsungBlue,
                        unfocusedBorderColor = SurfaceBg,
                        focusedLabelColor = SamsungLightBlue,
                        cursorColor = SamsungLightBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true,
                    enabled = recordState != RecordState.RECORDING
                )

                OutlinedTextField(
                    value = targetPackage,
                    onValueChange = { targetPackage = it },
                    label = { Text("Target App Package (Optional)") },
                    placeholder = { Text("e.g., in.amazon.mShop.android.shopping") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SamsungBlue,
                        unfocusedBorderColor = SurfaceBg,
                        focusedLabelColor = SamsungLightBlue,
                        cursorColor = SamsungLightBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true,
                    enabled = recordState != RecordState.RECORDING
                )

                if (recordState == RecordState.RECORDING) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("🔴 RECORDING IN PROGRESS", fontSize = 16.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("$actionCount interactions captured", color = TextPrimary, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Switch to the app, perform the actions you want FlowPilot to learn, then come back and tap Stop.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val stopIntent = Intent(context, FlowRecorderService::class.java).apply {
                                action = FlowRecorderService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop & Compile Flow", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = {
                            if (flowName.isBlank()) {
                                Toast.makeText(context, "Please enter a flow name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val startIntent = Intent(context, FlowRecorderService::class.java).apply {
                                action = FlowRecorderService.ACTION_START
                                putExtra(FlowRecorderService.EXTRA_FLOW_NAME, flowName)
                                putExtra(FlowRecorderService.EXTRA_TRIGGER_PHRASE, triggerPhrase)
                                putExtra(FlowRecorderService.EXTRA_TARGET_PACKAGE, targetPackage)
                            }
                            context.startService(startIntent)
                            recordState = RecordState.RECORDING
                            actionCount = 0
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Recording Demonstration", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (recordState == RecordState.ERROR) {
                    Text(errorMessage, color = Color.Red, fontSize = 13.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// FLOW LIST SCREEN (Browse, Inspect & Test Replay)
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var flows by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshFlows() {
        scope.launch {
            loading = true
            try {
                val result = ApiClient.api.listFlows()
                flows = result
                loading = false
            } catch (e: Exception) {
                error = e.localizedMessage
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshFlows()
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("My Flows (${flows.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshFlows() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBg,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SamsungBlue)
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Backend connection failed", color = TextPrimary, fontSize = 16.sp)
                        Text(error ?: "", color = TextSecondary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { refreshFlows() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            flows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Inbox, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No flows recorded yet", color = TextPrimary, fontSize = 18.sp)
                        Text("Teach FlowPilot a task to see it here!", color = TextSecondary)
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(flows) { flow ->
                        val flowId = flow["flow_id"] ?: ""
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBg)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        flow["flow_name"] ?: "Unnamed Flow",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    ApiClient.api.deleteFlow(flowId)
                                                    refreshFlows()
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete", tint = TextSecondary)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    flow["description"] ?: "",
                                    fontSize = 13.sp,
                                    color = TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "📦 ${flow["target_app_package"] ?: ""}",
                                        fontSize = 11.sp,
                                        color = SamsungLightBlue
                                    )

                                    // Quick Test Replay Button
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val fullFlow = ApiClient.api.getFlow(flowId)
                                                    val replayService = FlowReplayService.instance
                                                    if (replayService != null) {
                                                        replayService.startReplayDirect(fullFlow, emptyMap())
                                                        Toast.makeText(context, "Replaying '${fullFlow.flowName}'...", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val replayIntent = Intent(context, FlowReplayService::class.java).apply {
                                                            action = FlowReplayService.ACTION_REPLAY
                                                            putExtra(FlowReplayService.EXTRA_FLOW_JSON, ApiClient.gson.toJson(fullFlow))
                                                            putExtra(FlowReplayService.EXTRA_PARAMS_JSON, "{}")
                                                        }
                                                        context.startService(replayIntent)
                                                        Toast.makeText(context, "Replaying '${fullFlow.flowName}'...", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Could not replay: ${e.message}. Please enable Accessibility Service!", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Replay", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
