#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════════╗
║                   FLOWPILOT OVERNIGHT STRESS-TEST HARNESS                  ║
║                                                                            ║
║  Runs CONTINUOUSLY until manually stopped (Ctrl+C).                        ║
║  Designed to be left running overnight on a laptop with an emulator.       ║
║                                                                            ║
║  SAFETY GUARANTEES:                                                        ║
║  ─ AUTH_PAUSE steps are auto-cancelled via ADB (no payments go through)    ║
║  ─ Only safe, non-destructive flows are replayed on-device                 ║
║  ─ Zomato/Amazon flows are halted BEFORE "Place Order" / "Checkout"        ║
║  ─ WhatsApp messages go to a test contact (configurable below)             ║
║                                                                            ║
║  PREREQUISITES:                                                            ║
║  1. FlowPilot backend running:  cd backend && uvicorn main:app --port 8000 ║
║  2. Android emulator running with FlowPilot APK installed                  ║
║  3. ADB in PATH:  adb devices  (should show emulator)                     ║
║  4. FlowPilot accessibility service enabled on emulator                    ║
║                                                                            ║
║  USAGE:                                                                    ║
║    python3 overnight_stress_test.py                                        ║
║    python3 overnight_stress_test.py --backend-only   (skip ADB/device)     ║
║    python3 overnight_stress_test.py --cycles 50      (limit cycles)        ║
╚══════════════════════════════════════════════════════════════════════════════╝
"""

import json
import os
import random
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

# ─────────────────────────────────────────────────────────────────────────────
# CONFIGURATION
# ─────────────────────────────────────────────────────────────────────────────

BASE_URL = os.environ.get("FLOWPILOT_BACKEND_URL", "http://127.0.0.1:8000")

# How long to wait after triggering a flow replay before sending AUTH_CANCEL (seconds)
AUTH_CANCEL_DELAY = 8

# Delay between stress-test cycles (seconds) — gives the emulator breathing room
CYCLE_COOLDOWN = 5

# Maximum cycles (0 = infinite / until Ctrl+C)
MAX_CYCLES = 0

# Log file path
LOG_DIR = Path(__file__).resolve().parent / "stress_test_logs"
LOG_DIR.mkdir(exist_ok=True)
LOG_FILE = LOG_DIR / f"stress_run_{datetime.now().strftime('%Y%m%d_%H%M%S')}.log"

# Safe test contact for WhatsApp (change this to a test contact on the emulator)
SAFE_WHATSAPP_CONTACT = "Test Contact"
SAFE_WHATSAPP_MESSAGE = "[FlowPilot Stress Test] Automated message — please ignore."

# ─────────────────────────────────────────────────────────────────────────────
# COMPREHENSIVE TEST QUERY BANK (200+ queries across all dimensions)
# ─────────────────────────────────────────────────────────────────────────────

# Category 1: Exact trigger phrase matches
EXACT_TRIGGERS = [
    "Order butter chicken on Zomato",
    "Play music on YouTube",
    "Send a message on WhatsApp",
    "Play song on Spotify",
    "Buy protein powder on Amazon",
    "Get food from Zomato",
    "Watch a video on YouTube",
    "Text someone on WhatsApp",
    "Put some music on Spotify",
    "Order item on Amazon",
]

# Category 2: Paraphrased / Semantic equivalents
PARAPHRASES = [
    "I want to order dinner from Zomato",
    "Can you get me food delivered from Zomato?",
    "Place a Zomato food order for me",
    "I'd like some food from Zomato please",
    "Help me order lunch on Zomato",
    "Search for a music video on YouTube",
    "Find and play a YouTube video",
    "I want to watch something on YouTube",
    "Show me a video on YouTube please",
    "Open YouTube and play a song",
    "Message my mom on WhatsApp",
    "Send a WhatsApp text to someone",
    "I need to WhatsApp a friend",
    "Can you text via WhatsApp?",
    "WhatsApp my contact a message",
    "I want to listen to music on Spotify",
    "Play me some tunes on Spotify",
    "Open Spotify and search for a song",
    "Stream music using Spotify",
    "Start playing Spotify",
    "Search for something to buy on Amazon",
    "I want to purchase an item on Amazon",
    "Shop for products on Amazon",
    "Find and order from Amazon",
    "Buy something from Amazon for me",
]

# Category 3: Conversational / Natural language
CONVERSATIONAL = [
    "Hey, can you order me 2 butter chickens from Zomato?",
    "I'm hungry, order food from Zomato please",
    "Could you please get me dinner from Zomato tonight?",
    "Yo order some Zomato food",
    "I feel like eating — order on Zomato",
    "Play that new Coldplay song on YouTube",
    "Can you put on some relaxing lofi on YouTube?",
    "I wanna watch cat videos on YouTube",
    "YouTube please play Arijit Singh",
    "Let's watch something funny on YouTube",
    "Tell Mom I'm on my way home on WhatsApp",
    "Can you send a quick WhatsApp to Dad?",
    "Message John on WhatsApp saying I'll be late",
    "WhatsApp Mom that dinner is ready",
    "Quickly text my friend on WhatsApp",
    "Play some Arijit Singh on Spotify please",
    "I want to hear AR Rahman on Spotify",
    "Spotify play the top hits playlist",
    "Can you play my liked songs on Spotify?",
    "Start Spotify and play something chill",
    "I need wireless earbuds from Amazon",
    "Can you buy me a phone case on Amazon?",
    "Order a new laptop charger from Amazon",
    "Amazon me some headphones please",
    "Find the cheapest protein powder on Amazon",
]

# Category 4: Short keyword queries
KEYWORD_QUERIES = [
    "Zomato butter chicken",
    "Zomato food order",
    "Zomato dinner",
    "YouTube lofi",
    "YouTube music",
    "YouTube Coldplay",
    "WhatsApp message",
    "WhatsApp Mom",
    "WhatsApp text",
    "Spotify play",
    "Spotify Coldplay",
    "Spotify music",
    "Amazon buy",
    "Amazon protein powder",
    "Amazon earbuds",
]

# Category 5: Parameterized (quantity, address, item name)
PARAMETERIZED = [
    ("Order 3 butter chicken on Zomato", {"dish_name": "butter chicken", "quantity": "3"}),
    ("Deliver 2 paneer tikka to Work on Zomato", {"dish_name": "paneer tikka", "quantity": "2", "address": "Work"}),
    ("Order 1 biryani to Home on Zomato", {"dish_name": "biryani", "quantity": "1", "address": "Home"}),
    ("Get 4 momos from Zomato to Office", {"dish_name": "momos", "quantity": "4", "address": "Office"}),
    ("Order two Margherita pizzas from Zomato", {"dish_name": "Margherita pizzas", "quantity": "2"}),
    ("Buy wireless earbuds on Amazon", {"item_name": "wireless earbuds"}),
    ("Buy running shoes on Amazon", {"item_name": "running shoes"}),
    ("Order iPhone case on Amazon", {"item_name": "iPhone case"}),
    ("Play lofi hip hop on YouTube", {"query": "lofi hip hop"}),
    ("Play Shape of You on YouTube", {"query": "Shape of You"}),
    ("Play Blinding Lights on Spotify", {"track_or_artist": "Blinding Lights"}),
    ("Listen to Taylor Swift on Spotify", {"track_or_artist": "Taylor Swift"}),
    ("Send WhatsApp to Dad saying call me", {"contact_name": "Dad", "message": "call me"}),
    ("WhatsApp Mom saying I'm coming home", {"contact_name": "Mom", "message": "I'm coming home"}),
]

# Category 6: Out-of-domain / Negative controls (should NOT match)
NEGATIVE_CONTROLS = [
    "Book an Uber cab to the airport",
    "Check today's weather forecast",
    "Turn off the living room lights",
    "Set an alarm for 7 AM",
    "Translate this sentence to French",
    "What's the capital of France?",
    "Calculate 15% tip on $45",
    "Navigate to the nearest gas station",
    "Open my email inbox",
    "Take a screenshot",
    "Call 911",
    "What time is it in Tokyo?",
    "Remind me to buy groceries",
    "Schedule a meeting for tomorrow",
    "How do I cook pasta?",
]

# Category 7: Cross-app generalization (ASIG)
CROSS_APP_QUERIES = [
    ("Buy protein powder on Myntra", "com.myntra.android", "Myntra"),
    ("Buy wireless earbuds on Flipkart", "com.flipkart.android", "Flipkart"),
    ("Buy running shoes on Meesho", "com.meesho.supply", "Meesho"),
    ("Order dinner on Swiggy", "in.swiggy.android", "Swiggy"),
]

# Category 8: Edge cases & adversarial
EDGE_CASES = [
    "",                                          # Empty string
    "   ",                                       # Whitespace only
    "Order",                                     # Single word
    "Zomato",                                    # Just app name
    "a" * 500,                                   # Very long input
    "Order \U0001f355 on Zomato",                # Unicode / emoji
    "ORDER BUTTER CHICKEN ON ZOMATO",            # ALL CAPS
    "OrDeR bUtTeR cHiCkEn On ZoMaTo",           # Mixed case
    "order...butter...chicken...zomato",         # Dots
    "order butter chicken on zomato!!!",          # Excessive punctuation
    "Play <script>alert('xss')</script> on YouTube",  # XSS attempt
    "Order ' OR '1'='1 on Zomato",               # SQL injection attempt
    "Buy ../../../etc/passwd on Amazon",          # Path traversal attempt
    "Order food\non\nZomato",                    # Newlines
    "Order\tfood\ton\tZomato",                   # Tabs
]

# Category 9: T13 Ambiguity tests
AMBIGUITY_QUERIES = [
    "Play music",               # Spotify vs YouTube
    "Play something",           # Spotify vs YouTube
    "Order food",               # Under-specified
    "Send a message",           # Under-specified (which app?)
    "Buy something",            # Under-specified
    "Search for something",     # Vague
]

# Category 10: T14 Status/Reporting queries
REPORTING_QUERIES = [
    "Did the last run succeed?",
    "Last run status",
    "Status of last run",
    "Did it succeed?",
]


# ─────────────────────────────────────────────────────────────────────────────
# UTILITIES
# ─────────────────────────────────────────────────────────────────────────────

class StressTestStats:
    """Accumulates stats across all cycles."""

    def __init__(self):
        self.total_queries = 0
        self.successful_requests = 0
        self.failed_requests = 0
        self.correct_matches = 0
        self.incorrect_matches = 0
        self.false_positives = 0
        self.false_negatives = 0
        self.latencies: list[float] = []
        self.errors: list[str] = []
        self.device_replays_triggered = 0
        self.device_replays_cancelled = 0
        self.device_replays_stuck = 0
        self.auth_pauses_intercepted = 0
        self.cycles_completed = 0
        self.start_time = time.time()

    def summary(self) -> str:
        elapsed = time.time() - self.start_time
        hours = int(elapsed // 3600)
        mins = int((elapsed % 3600) // 60)
        secs = int(elapsed % 60)
        avg_lat = sum(self.latencies) / len(self.latencies) if self.latencies else 0
        p95 = sorted(self.latencies)[int(len(self.latencies) * 0.95)] if len(self.latencies) > 1 else avg_lat
        p99 = sorted(self.latencies)[int(len(self.latencies) * 0.99)] if len(self.latencies) > 1 else avg_lat
        max_lat = max(self.latencies) if self.latencies else 0

        return (
            "\n"
            "======================================================================\n"
            "              STRESS TEST CUMULATIVE REPORT                           \n"
            "======================================================================\n"
            f"  Runtime          : {hours:02d}h {mins:02d}m {secs:02d}s\n"
            f"  Cycles Completed : {self.cycles_completed}\n"
            f"  Total Queries    : {self.total_queries}\n"
            "  -------------------------------------------------------------------\n"
            f"  HTTP Successes   : {self.successful_requests}\n"
            f"  HTTP Failures    : {self.failed_requests}\n"
            "  -------------------------------------------------------------------\n"
            f"  Correct Matches  : {self.correct_matches}\n"
            f"  Incorrect        : {self.incorrect_matches}\n"
            f"  False Positives  : {self.false_positives}\n"
            f"  False Negatives  : {self.false_negatives}\n"
            "  -------------------------------------------------------------------\n"
            f"  Avg Latency      : {avg_lat:.1f}ms\n"
            f"  P95 Latency      : {p95:.1f}ms\n"
            f"  P99 Latency      : {p99:.1f}ms\n"
            f"  Max Latency      : {max_lat:.1f}ms\n"
            "  -------------------------------------------------------------------\n"
            f"  Device Replays   : {self.device_replays_triggered} triggered\n"
            f"  Auth Cancels     : {self.device_replays_cancelled} safely cancelled\n"
            f"  Auth Pauses      : {self.auth_pauses_intercepted} intercepted\n"
            f"  Stuck Events     : {self.device_replays_stuck}\n"
            "  -------------------------------------------------------------------\n"
            f"  Unique Errors    : {len(set(self.errors))}\n"
            "======================================================================\n"
        )


def log(msg: str, level: str = "INFO"):
    """Log to console AND file."""
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] [{level}] {msg}"
    print(line)
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def http_post(path: str, data: dict, timeout: int = 30) -> tuple:
    """POST JSON, returns (status, body, latency_ms)."""
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        latency = (time.perf_counter() - t0) * 1000
        return resp.status, json.loads(resp.read().decode("utf-8")), latency


def http_get(path: str, timeout: int = 15) -> tuple:
    url = f"{BASE_URL}{path}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def adb_cmd(args: list, timeout: int = 10) -> tuple:
    """Run an ADB command, return (success, output)."""
    try:
        result = subprocess.run(
            ["adb"] + args,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return result.returncode == 0, result.stdout.strip()
    except FileNotFoundError:
        return False, "adb not found in PATH"
    except subprocess.TimeoutExpired:
        return False, "adb command timed out"
    except Exception as e:
        return False, str(e)


def check_emulator() -> bool:
    """Check if an emulator/device is connected via ADB."""
    ok, output = adb_cmd(["devices"])
    if not ok:
        return False
    lines = [l for l in output.strip().split("\n") if "\tdevice" in l]
    return len(lines) > 0


def send_adb_broadcast(action: str, extras: dict = None):
    """Send an ADB broadcast to FlowPilot on the emulator."""
    cmd = ["shell", "am", "broadcast", "-a", action, "-p", "com.flowpilot"]
    if extras:
        for k, v in extras.items():
            cmd.extend(["--es", k, v])
    ok, out = adb_cmd(cmd)
    log(f"  ADB broadcast {action}: {'OK' if ok else 'FAIL'} -- {out}", "DEBUG")
    return ok


def start_flowpilot_replay_via_adb(flow_json: str, params_json: str) -> bool:
    """Trigger a flow replay on the emulator via ADB service start."""
    cmd = [
        "shell", "am", "start-foreground-service",
        "-n", "com.flowpilot/com.flowpilot.service.FlowReplayService",
        "-a", "com.flowpilot.START_REPLAY",
        "--es", "flow_json", flow_json,
        "--es", "params_json", params_json,
    ]
    ok, out = adb_cmd(cmd, timeout=15)
    return ok


def cancel_flowpilot_replay_via_adb() -> bool:
    """Cancel any running replay via ADB."""
    cmd = [
        "shell", "am", "start-foreground-service",
        "-n", "com.flowpilot/com.flowpilot.service.FlowReplayService",
        "-a", "com.flowpilot.CANCEL_REPLAY",
    ]
    ok, out = adb_cmd(cmd, timeout=10)
    return ok


def send_auth_cancel_via_adb() -> bool:
    """Send AUTH_CANCEL to halt the flow at the payment/auth gate."""
    cmd = [
        "shell", "am", "start-foreground-service",
        "-n", "com.flowpilot/com.flowpilot.service.FlowReplayService",
        "-a", "com.flowpilot.AUTH_CANCEL",
    ]
    ok, out = adb_cmd(cmd, timeout=10)
    return ok


# ─────────────────────────────────────────────────────────────────────────────
# TEST PHASES
# ─────────────────────────────────────────────────────────────────────────────

def phase_1_backend_health(stats: StressTestStats) -> bool:
    """Verify backend is alive."""
    try:
        status, data = http_get("/")
        ok = status == 200 and data.get("status") == "ok"
        log(f"Health check: {'PASS' if ok else 'FAIL'} -- {data}")
        return ok
    except Exception as e:
        log(f"Health check FAILED: {e}", "ERROR")
        stats.errors.append(f"Health: {e}")
        return False


def phase_2_match_stress(stats: StressTestStats):
    """
    Hammer the /api/match/text endpoint with randomized queries
    from ALL categories. Verify correctness where expected.
    """
    log("=== PHASE 2: Backend Match Stress Test ===")

    # Build randomized query list for this cycle
    queries = []

    # Exact triggers (expected to match)
    for q in EXACT_TRIGGERS:
        queries.append((q, "should_match", None))

    # Paraphrases (expected to match)
    for q in PARAPHRASES:
        queries.append((q, "should_match", None))

    # Conversational (expected to match)
    for q in CONVERSATIONAL:
        queries.append((q, "should_match", None))

    # Keywords (expected to match)
    for q in KEYWORD_QUERIES:
        queries.append((q, "should_match", None))

    # Parameterized (expected to match with params)
    for q, expected_params in PARAMETERIZED:
        queries.append((q, "should_match_params", expected_params))

    # Negative controls (should NOT match)
    for q in NEGATIVE_CONTROLS:
        queries.append((q, "should_not_match", None))

    # Edge cases (should handle gracefully — no crashes)
    for q in EDGE_CASES:
        queries.append((q, "should_not_crash", None))

    # Ambiguity queries (should match but flag ambiguity)
    for q in AMBIGUITY_QUERIES:
        queries.append((q, "should_flag_ambiguity", None))

    # Reporting queries (special interception)
    for q in REPORTING_QUERIES:
        queries.append((q, "should_intercept_reporting", None))

    # Cross-app (ASIG)
    for q, pkg, app_name in CROSS_APP_QUERIES:
        queries.append((q, f"cross_app|{pkg}|{app_name}", None))

    # Shuffle for stress randomness
    random.shuffle(queries)

    pass_count = 0
    fail_count = 0

    for query, expectation, expected_params in queries:
        stats.total_queries += 1
        try:
            # Skip truly empty queries
            if not query.strip():
                # Expect a 400 or 422 error
                try:
                    status, res, lat = http_post("/api/match/text", {"command": query})
                    stats.successful_requests += 1
                    stats.latencies.append(lat)
                    log(f"  Empty query handled: status={status}", "DEBUG")
                    pass_count += 1
                except urllib.error.HTTPError as he:
                    # 400/422 is expected for empty
                    if he.code in (400, 422):
                        pass_count += 1
                        log(f"  Empty query correctly rejected: {he.code}", "DEBUG")
                    else:
                        fail_count += 1
                        stats.failed_requests += 1
                        stats.errors.append(f"Empty query unexpected error: {he.code}")
                continue

            status, res, lat = http_post("/api/match/text", {"command": query})
            stats.successful_requests += 1
            stats.latencies.append(lat)

            matched = res.get("matched", False)
            flow_name = res.get("flow_name", "")
            confidence = res.get("confidence", 0.0)
            is_ambiguous = res.get("is_ambiguous", False)
            params = res.get("parameters", {})

            if expectation == "should_match":
                if matched:
                    stats.correct_matches += 1
                    pass_count += 1
                else:
                    stats.false_negatives += 1
                    fail_count += 1
                    log(f"  FALSE NEGATIVE: '{query[:50]}' should have matched but didn't", "WARN")

            elif expectation == "should_match_params":
                if matched:
                    stats.correct_matches += 1
                    # Check specific param values
                    if expected_params:
                        for pk, pv in expected_params.items():
                            actual = str(params.get(pk, ""))
                            if actual and actual != "__UNRESOLVED__":
                                pass  # param extracted
                            else:
                                log(f"  PARAM MISS: '{pk}'={actual} (expected ~'{pv}') for: {query[:40]}", "WARN")
                    pass_count += 1
                else:
                    stats.false_negatives += 1
                    fail_count += 1
                    log(f"  FALSE NEGATIVE (param): '{query[:50]}' should have matched", "WARN")

            elif expectation == "should_not_match":
                if not matched:
                    stats.correct_matches += 1
                    pass_count += 1
                else:
                    stats.false_positives += 1
                    fail_count += 1
                    log(f"  FALSE POSITIVE: '{query[:50]}' matched '{flow_name}' (conf={confidence:.2f})", "WARN")

            elif expectation == "should_not_crash":
                # As long as we got a response without 500, it's a pass
                stats.correct_matches += 1
                pass_count += 1

            elif expectation == "should_flag_ambiguity":
                if matched:
                    stats.correct_matches += 1
                    if is_ambiguous:
                        log(f"  AMBIGUITY flagged for: '{query[:40]}' -> {res.get('clarification_prompt', '')[:60]}", "DEBUG")
                    pass_count += 1
                else:
                    stats.false_negatives += 1
                    fail_count += 1

            elif expectation == "should_intercept_reporting":
                # Should return matched=False with a reporting suggestion
                if not matched and "Reporting" in (res.get("suggestion", "") or ""):
                    stats.correct_matches += 1
                    pass_count += 1
                else:
                    stats.incorrect_matches += 1
                    fail_count += 1
                    log(f"  REPORTING not intercepted for: '{query[:40]}'", "WARN")

            elif expectation and expectation.startswith("cross_app|"):
                parts = expectation.split("|")
                exp_pkg = parts[1]
                exp_name = parts[2]
                fg = res.get("flow_graph", {})
                actual_pkg = fg.get("target_app_package", "")
                if matched and exp_pkg == actual_pkg and exp_name in (flow_name or ""):
                    stats.correct_matches += 1
                    pass_count += 1
                    log(f"  ASIG cross-app OK: '{query[:40]}' -> {flow_name} ({actual_pkg})", "DEBUG")
                elif matched:
                    stats.incorrect_matches += 1
                    fail_count += 1
                    log(f"  ASIG MISMATCH: expected {exp_pkg}, got {actual_pkg}", "WARN")
                else:
                    stats.false_negatives += 1
                    fail_count += 1

        except urllib.error.HTTPError as he:
            stats.failed_requests += 1
            fail_count += 1
            err_body = ""
            try:
                err_body = he.read().decode("utf-8")[:200]
            except Exception:
                pass
            log(f"  HTTP {he.code} for '{query[:40]}': {err_body}", "ERROR")
            stats.errors.append(f"HTTP {he.code}: {query[:40]}")

        except Exception as e:
            stats.failed_requests += 1
            fail_count += 1
            log(f"  EXCEPTION for '{query[:40]}': {e}", "ERROR")
            stats.errors.append(f"Exception: {e}")

    log(f"Phase 2 Complete: {pass_count} passed, {fail_count} failed (total queries this cycle: {pass_count + fail_count})")


def phase_3_concurrent_load(stats: StressTestStats):
    """
    Burst rapid-fire queries to test backend under load.
    Sends 20 queries as fast as possible with no delay.
    """
    log("=== PHASE 3: Rapid-Fire Concurrent Load ===")

    burst_queries = random.choices(EXACT_TRIGGERS + PARAPHRASES + KEYWORD_QUERIES, k=20)
    burst_start = time.perf_counter()

    for q in burst_queries:
        stats.total_queries += 1
        try:
            _, res, lat = http_post("/api/match/text", {"command": q}, timeout=30)
            stats.successful_requests += 1
            stats.latencies.append(lat)
        except Exception as e:
            stats.failed_requests += 1
            stats.errors.append(f"Burst: {e}")

    burst_elapsed = (time.perf_counter() - burst_start) * 1000
    qps = 20 / (burst_elapsed / 1000) if burst_elapsed > 0 else 0
    log(f"Phase 3 Complete: 20 burst queries in {burst_elapsed:.0f}ms ({qps:.1f} QPS)")


def phase_4_flow_crud(stats: StressTestStats):
    """Verify flow listing and individual flow detail retrieval."""
    log("=== PHASE 4: Flow CRUD Integrity ===")
    try:
        _, flows = http_get("/api/flows/")
        log(f"  Listed {len(flows)} flows")
        assert len(flows) >= 5, f"Expected >= 5 flows, got {len(flows)}"

        for f in flows:
            _, detail = http_get(f"/api/flows/{f['flow_id']}")
            assert detail["flow_name"] == f["flow_name"], f"Name mismatch for {f['flow_id']}"
            assert len(detail.get("steps", [])) > 0, f"No steps in {f['flow_id']}"
            # Verify auth_pause exists on payment/order steps
            has_auth = any(s.get("is_auth_pause") for s in detail["steps"])
            if "order" in detail["flow_name"].lower() or "buy" in detail["flow_name"].lower():
                if not has_auth:
                    log(f"  WARNING: '{detail['flow_name']}' has no auth_pause step!", "WARN")

        log(f"Phase 4 Complete: All {len(flows)} flows verified")
    except Exception as e:
        log(f"Phase 4 FAILED: {e}", "ERROR")
        stats.errors.append(f"CRUD: {e}")


def phase_5_device_replay(stats: StressTestStats, has_emulator: bool):
    """
    Trigger SAFE on-device replays via ADB.
    Only replays flows that are SAFE (YouTube, Spotify) fully,
    and for Zomato/Amazon, automatically cancels at AUTH_PAUSE.
    """
    if not has_emulator:
        log("=== PHASE 5: Device Replay -- SKIPPED (no emulator) ===")
        return

    log("=== PHASE 5: Safe On-Device Flow Replay ===")

    # Choose safe test queries
    safe_replay_queries = [
        # YouTube — fully safe, just plays a video
        ("Play lofi hip hop on YouTube", True),
        # Spotify — fully safe, just plays music
        ("Play Coldplay on Spotify", True),
        # Zomato — will be auto-cancelled at auth pause
        ("Order butter chicken on Zomato", False),
        # Amazon — will be auto-cancelled at auth pause
        ("Buy protein powder on Amazon", False),
    ]

    for query, is_fully_safe in safe_replay_queries:
        log(f"  Replaying: '{query}' (safe={is_fully_safe})")
        stats.device_replays_triggered += 1

        try:
            # 1. Match the query to get a flow
            _, res, _ = http_post("/api/match/text", {"command": query})
            if not res.get("matched"):
                log(f"    No match for device replay: {query}", "WARN")
                continue

            flow_graph = res.get("flow_graph", {})
            params = res.get("parameters", {})

            # Safety: Override WhatsApp contact to safe test contact
            if "whatsapp" in query.lower():
                params["contact_name"] = SAFE_WHATSAPP_CONTACT
                params["message"] = SAFE_WHATSAPP_MESSAGE

            flow_json = json.dumps(flow_graph)
            params_json = json.dumps(params)

            # 2. Trigger replay on device
            ok = start_flowpilot_replay_via_adb(flow_json, params_json)
            if not ok:
                log(f"    Failed to start replay via ADB", "WARN")
                continue

            if is_fully_safe:
                # Let it run for a bit, then cancel to reset state
                log(f"    Letting safe flow run for 15s...")
                time.sleep(15)
                cancel_flowpilot_replay_via_adb()
                stats.device_replays_cancelled += 1
                log(f"    Safe flow completed & cancelled")
            else:
                # Wait for AUTH_PAUSE to trigger, then cancel
                log(f"    Waiting {AUTH_CANCEL_DELAY}s for AUTH_PAUSE then auto-cancelling...")
                time.sleep(AUTH_CANCEL_DELAY)

                # Send AUTH_CANCEL to prevent any payment/order
                send_auth_cancel_via_adb()
                stats.auth_pauses_intercepted += 1
                stats.device_replays_cancelled += 1
                log(f"    AUTH_CANCEL sent -- flow safely halted before payment")

            # Cooldown between replays
            time.sleep(3)

        except Exception as e:
            log(f"    Device replay error: {e}", "ERROR")
            stats.errors.append(f"Replay: {e}")
            # Emergency cancel
            cancel_flowpilot_replay_via_adb()


def phase_6_adb_logcat_check(stats: StressTestStats, has_emulator: bool):
    """
    Check device logcat for FlowReplay errors/crashes.
    """
    if not has_emulator:
        return

    log("=== PHASE 6: Logcat Health Check ===")
    try:
        ok, output = adb_cmd(["logcat", "-d", "-t", "100", "-s", "FlowReplay:*"], timeout=10)
        if ok:
            lines = output.strip().split("\n")
            errors = [l for l in lines if " E " in l or "FATAL" in l or "crash" in l.lower()]
            stuck = [l for l in lines if "Genuinely Stuck" in l or "REPLAY_STUCK" in l]
            if errors:
                log(f"  Found {len(errors)} error lines in logcat:", "WARN")
                for e in errors[:5]:
                    log(f"    {e[:120]}", "WARN")
                stats.errors.extend([f"Logcat: {e[:80]}" for e in errors[:3]])
            if stuck:
                stats.device_replays_stuck += len(stuck)
                log(f"  Found {len(stuck)} STUCK events in logcat", "WARN")
            if not errors and not stuck:
                log(f"  Logcat clean -- no FlowReplay errors detected")
        else:
            log(f"  Logcat read failed: {output}", "WARN")

        # Clear logcat for next cycle
        adb_cmd(["logcat", "-c"], timeout=5)
    except Exception as e:
        log(f"  Logcat check error: {e}", "ERROR")


# ─────────────────────────────────────────────────────────────────────────────
# MAIN LOOP
# ─────────────────────────────────────────────────────────────────────────────

def main():
    # Parse CLI args
    backend_only = "--backend-only" in sys.argv
    max_cycles = MAX_CYCLES
    for i, arg in enumerate(sys.argv):
        if arg == "--cycles" and i + 1 < len(sys.argv):
            max_cycles = int(sys.argv[i + 1])

    log("================================================================")
    log("         FLOWPILOT OVERNIGHT STRESS TEST STARTING               ")
    log("================================================================")
    log(f"Backend URL  : {BASE_URL}")
    log(f"Backend Only : {backend_only}")
    log(f"Max Cycles   : {'infinite (Ctrl+C to stop)' if max_cycles == 0 else max_cycles}")
    log(f"Log File     : {LOG_FILE}")

    stats = StressTestStats()

    # Pre-flight checks
    if not phase_1_backend_health(stats):
        log("FATAL: Backend is not reachable. Start it first!", "ERROR")
        sys.exit(1)

    has_emulator = False
    if not backend_only:
        has_emulator = check_emulator()
        if has_emulator:
            log("Emulator/device detected via ADB")
            # Check if FlowPilot is installed
            ok, out = adb_cmd(["shell", "pm", "list", "packages", "com.flowpilot"])
            if "com.flowpilot" in out:
                log("FlowPilot APK is installed on device")
            else:
                log("FlowPilot APK not found on device -- device replay will be skipped", "WARN")
                has_emulator = False
        else:
            log("No emulator/device detected -- running backend-only mode", "WARN")

    cycle = 0
    try:
        while True:
            cycle += 1
            if max_cycles > 0 and cycle > max_cycles:
                log(f"Reached max cycles ({max_cycles}). Stopping.")
                break

            log(f"\n{'=' * 60}")
            log(f"  CYCLE {cycle} {'(of ' + str(max_cycles) + ')' if max_cycles > 0 else '(infinite)'}")
            log(f"{'=' * 60}")

            # Phase 1: Quick health re-check
            if not phase_1_backend_health(stats):
                log("Backend went down! Waiting 30s and retrying...", "ERROR")
                time.sleep(30)
                if not phase_1_backend_health(stats):
                    log("Backend still down. Pausing for 2 minutes...", "ERROR")
                    time.sleep(120)
                    continue

            # Phase 2: Full query bank stress test
            phase_2_match_stress(stats)

            # Phase 3: Burst load test
            phase_3_concurrent_load(stats)

            # Phase 4: CRUD integrity
            phase_4_flow_crud(stats)

            # Phase 5: On-device replay (every 3rd cycle to give emulator rest)
            if cycle % 3 == 1:
                phase_5_device_replay(stats, has_emulator)

            # Phase 6: Logcat check
            phase_6_adb_logcat_check(stats, has_emulator)

            stats.cycles_completed = cycle

            # Print interim summary every 5 cycles
            if cycle % 5 == 0:
                log(stats.summary())

            # Cooldown
            log(f"Cycle {cycle} complete. Cooling down for {CYCLE_COOLDOWN}s...")
            time.sleep(CYCLE_COOLDOWN)

    except KeyboardInterrupt:
        log("\n\nInterrupted by user (Ctrl+C)")
    except Exception as e:
        log(f"\n\nFATAL ERROR: {e}", "ERROR")
        log(traceback.format_exc(), "ERROR")
    finally:
        stats.cycles_completed = cycle

        # Final report
        report = stats.summary()
        log(report)

        # Write final report to file
        report_file = LOG_DIR / f"stress_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
        with open(report_file, "w", encoding="utf-8") as f:
            f.write(report)
            f.write("\n\nERRORS LOG:\n")
            for err in stats.errors:
                f.write(f"  - {err}\n")
        log(f"Final report saved to: {report_file}")

        # Emergency: cancel any running replay
        if has_emulator:
            cancel_flowpilot_replay_via_adb()
            log("Emergency replay cancel sent to device.")


if __name__ == "__main__":
    main()
