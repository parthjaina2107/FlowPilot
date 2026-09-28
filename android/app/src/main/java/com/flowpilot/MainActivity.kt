package com.flowpilot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.flowpilot.service.FlowRecorderService
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────
// Theme colors (Samsung-inspired dark theme)
// ─────────────────────────────────────────────
val SamsungBlue = Color(0xFF1428A0)
val SamsungLightBlue = Color(0xFF4285F4)
val DarkBg = Color(0xFF0D1117)
val CardBg = Color(0xFF161B22)
val SurfaceBg = Color(0xFF21262D)
val AccentGreen = Color(0xFF3FB950)
val AccentOrange = Color(0xFFF0883E)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)

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
            RecordScreen(onBack = { navController.popBackStack() })
        }
        composable("flows") {
            FlowListScreen(onBack = { navController.popBackStack() })
        }
    }
}

// ─────────────────────────────────────────────
// HOME SCREEN
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onRecordClick: () -> Unit, onFlowsClick: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = {
                    Text("FlowPilot", fontWeight = FontWeight.Bold, fontSize = 24.sp)
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Big mic button
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(SamsungLightBlue, SamsungBlue)
                        )
                    )
                    .clickable {
                        Toast.makeText(context, "Voice command — coming soon!", Toast.LENGTH_SHORT).show()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Voice command",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Tap to give a voice command",
                color = TextSecondary,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Record button
            Button(
                onClick = onRecordClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color.Red)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Record New Flow", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // My Flows button
            OutlinedButton(
                onClick = onFlowsClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Icon(Icons.Filled.List, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("My Flows", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Accessibility settings button
            TextButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Enable Accessibility Service", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────
// RECORD SCREEN
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var flowName by remember { mutableStateOf("") }
    var triggerPhrase by remember { mutableStateOf("") }
    var targetPackage by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var actionCount by remember { mutableIntStateOf(0) }

    // Poll action count while recording
    LaunchedEffect(isRecording) {
        while (isRecording) {
            actionCount = FlowRecorderService.actionCount
            kotlinx.coroutines.delay(500)
        }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Record Flow") },
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
            // Step 1: Flow name
            OutlinedTextField(
                value = flowName,
                onValueChange = { flowName = it },
                label = { Text("Flow Name") },
                placeholder = { Text("e.g., Order food from Zomato") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SamsungBlue,
                    unfocusedBorderColor = SurfaceBg,
                    focusedLabelColor = SamsungLightBlue,
                    cursorColor = SamsungLightBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = true
            )

            // Step 2: Trigger phrase
            OutlinedTextField(
                value = triggerPhrase,
                onValueChange = { triggerPhrase = it },
                label = { Text("Voice Trigger Phrase") },
                placeholder = { Text("e.g., Order paneer from Zomato") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SamsungBlue,
                    unfocusedBorderColor = SurfaceBg,
                    focusedLabelColor = SamsungLightBlue,
                    cursorColor = SamsungLightBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = true
            )

            // Step 3: Target package
            OutlinedTextField(
                value = targetPackage,
                onValueChange = { targetPackage = it },
                label = { Text("Target App Package") },
                placeholder = { Text("e.g., com.application.zomato") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SamsungBlue,
                    unfocusedBorderColor = SurfaceBg,
                    focusedLabelColor = SamsungLightBlue,
                    cursorColor = SamsungLightBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isRecording) {
                // Recording status
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔴 Recording...", fontSize = 20.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("$actionCount actions captured", color = TextSecondary, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Switch to the target app and perform your task.\nReturn here when done.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stop button
                Button(
                    onClick = {
                        val stopIntent = Intent(context, FlowRecorderService::class.java).apply {
                            action = FlowRecorderService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                        isRecording = false
                        Toast.makeText(context, "Recording stopped! $actionCount actions captured.", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Recording", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Start button
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
                        isRecording = true
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
                    Text("Start Recording", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// FLOW LIST SCREEN
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowListScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var flows by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load flows from backend
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val result = com.flowpilot.network.ApiClient.api.listFlows()
                flows = result
                loading = false
            } catch (e: Exception) {
                error = e.message
                loading = false
            }
        }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("My Flows") },
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
                        Text("Cannot connect to backend", color = TextPrimary, fontSize = 16.sp)
                        Text(error ?: "", color = TextSecondary, fontSize = 12.sp)
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
                        Text("No flows yet", color = TextPrimary, fontSize = 18.sp)
                        Text("Record your first flow to get started!", color = TextSecondary)
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
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    flow["flow_name"] ?: "Unnamed",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    flow["description"] ?: "",
                                    fontSize = 13.sp,
                                    color = TextSecondary,
                                    maxLines = 2
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "📦 ${flow["target_app_package"] ?: ""}",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
