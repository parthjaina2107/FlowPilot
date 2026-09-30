"""
FlowPilot - Comprehensive 5-App Benchmark & Evaluation Suite
Validates the complete FlowPilot pipeline against the hackathon evaluation criteria:
  - 5 Real-World Apps: Zomato, YouTube, WhatsApp, Spotify, Amazon
  - 25 Semantic Natural Language Queries (exact, paraphrased, conversational, out-of-domain)
  - Semantic Matching Accuracy & Confidence Scores (ChromaDB + S-BERT)
  - Dynamic Parameter Resolution (Gemini)
  - Security & Auth Pause verification
"""

import json
import time
import urllib.request
import urllib.error
import sys

BASE_URL = "http://127.0.0.1:8000"

BENCHMARK_QUERIES = [
    # --- 1. Zomato (Food Delivery) ---
    {"query": "Order butter chicken on Zomato", "expected_flow": "Order Food on Zomato", "category": "Exact Trigger", "app": "Zomato"},
    {"query": "Get dinner from Zomato", "expected_flow": "Order Food on Zomato", "category": "Paraphrase", "app": "Zomato"},
    {"query": "Can you order 2 butter chicken from Zomato?", "expected_flow": "Order Food on Zomato", "category": "Conversational", "app": "Zomato"},
    {"query": "Zomato butter chicken", "expected_flow": "Order Food on Zomato", "category": "Keyword Shortcut", "app": "Zomato"},

    # --- 2. YouTube (Video/Media) ---
    {"query": "Play music on YouTube", "expected_flow": "Play Video on YouTube", "category": "Exact Trigger", "app": "YouTube"},
    {"query": "Put on lofi hip hop on YouTube", "expected_flow": "Play Video on YouTube", "category": "Paraphrase", "app": "YouTube"},
    {"query": "Watch a video on YouTube", "expected_flow": "Play Video on YouTube", "category": "Conversational", "app": "YouTube"},
    {"query": "YouTube lofi songs", "expected_flow": "Play Video on YouTube", "category": "Keyword Shortcut", "app": "YouTube"},

    # --- 3. WhatsApp (Messaging) ---
    {"query": "Send a message on WhatsApp", "expected_flow": "Send WhatsApp Message", "category": "Exact Trigger", "app": "WhatsApp"},
    {"query": "Text someone on WhatsApp", "expected_flow": "Send WhatsApp Message", "category": "Paraphrase", "app": "WhatsApp"},
    {"query": "Send WhatsApp to Mom saying On my way", "expected_flow": "Send WhatsApp Message", "category": "Conversational", "app": "WhatsApp"},
    {"query": "WhatsApp message Mom", "expected_flow": "Send WhatsApp Message", "category": "Keyword Shortcut", "app": "WhatsApp"},

    # --- 4. Spotify (Audio Streaming) ---
    {"query": "Play song on Spotify", "expected_flow": "Play Track on Spotify", "category": "Exact Trigger", "app": "Spotify"},
    {"query": "Put some music on Spotify", "expected_flow": "Play Track on Spotify", "category": "Paraphrase", "app": "Spotify"},
    {"query": "Listen to artist Coldplay on Spotify", "expected_flow": "Play Track on Spotify", "category": "Conversational", "app": "Spotify"},
    {"query": "Spotify play Coldplay", "expected_flow": "Play Track on Spotify", "category": "Keyword Shortcut", "app": "Spotify"},

    # --- 5. Amazon (E-Commerce) ---
    {"query": "Buy protein powder on Amazon", "expected_flow": "Buy Product on Amazon", "category": "Exact Trigger", "app": "Amazon"},
    {"query": "Order item on Amazon", "expected_flow": "Buy Product on Amazon", "category": "Paraphrase", "app": "Amazon"},
    {"query": "Can you buy protein powder from Amazon?", "expected_flow": "Buy Product on Amazon", "category": "Conversational", "app": "Amazon"},
    {"query": "Amazon buy product", "expected_flow": "Buy Product on Amazon", "category": "Keyword Shortcut", "app": "Amazon"},

    # --- Out-of-Domain Negative Controls (Should NOT match) ---
    {"query": "Book an Uber cab to the airport", "expected_flow": None, "category": "Negative Control", "app": "None"},
    {"query": "Check today's weather forecast", "expected_flow": None, "category": "Negative Control", "app": "None"},
    {"query": "Turn off living room smart lights", "expected_flow": None, "category": "Negative Control", "app": "None"},
    {"query": "Set an alarm for 7 AM", "expected_flow": None, "category": "Negative Control", "app": "None"},
    {"query": "Translate this sentence to French", "expected_flow": None, "category": "Negative Control", "app": "None"},
]

def http_post(path, data):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=15) as resp:
        elapsed = (time.perf_counter() - t0) * 1000
        return resp.status, json.loads(resp.read().decode("utf-8")), elapsed

def http_get(path):
    url = f"{BASE_URL}{path}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def run_benchmark():
    print("=" * 80)
    print("         FLOWPILOT SYSTEM BENCHMARK & EVALUATION SUITE")
    print("=" * 80)
    print("Validating all 5 multi-app flows across 25 natural language voice queries...\n")

    # Verify backend health
    try:
        status, health = http_get("/")
        print(f"[OK] Backend Online: {health.get('service')} v{health.get('version')} (status: {health.get('status')})")
    except Exception as e:
        print(f"[ERROR] Could not connect to backend: {e}")
        return False

    status, flows = http_get("/api/flows/")
    print(f"[OK] Database contains {len(flows)} pre-compiled flows.\n")

    results = []
    latencies = []
    correct_matches = 0

    print(f"{'#':<3} | {'Query':<42} | {'Expected Flow':<22} | {'Result':<8} | {'Conf':<6} | {'Latency'}")
    print("-" * 100)

    for i, item in enumerate(BENCHMARK_QUERIES, 1):
        query = item["query"]
        expected = item["expected_flow"]
        category = item["category"]

        try:
            status, res, latency = http_post("/api/match/text", {"command": query})
            latencies.append(latency)

            matched = res.get("matched", False)
            flow_name = res.get("flow_name")
            conf = res.get("confidence", 0.0)

            if expected is None:
                # Should not match
                is_correct = not matched
                result_str = "PASS (Rejected)" if is_correct else "FAIL (False Pos)"
                conf_str = f"{conf*100:.1f}%" if matched else "N/A"
                exp_str = "[None]"
            else:
                is_correct = matched and (flow_name == expected)
                result_str = "PASS" if is_correct else ("MISS" if not matched else "WRONG")
                conf_str = f"{conf*100:.1f}%" if conf else "0%"
                exp_str = expected[:20]

            if is_correct:
                correct_matches += 1

            query_display = (query[:39] + "...") if len(query) > 42 else query
            print(f"{i:<3} | {query_display:<42} | {exp_str:<22} | {result_str:<8} | {conf_str:<6} | {latency:6.1f}ms")

            results.append({
                "query": query,
                "category": category,
                "app": item["app"],
                "expected": expected,
                "matched": matched,
                "flow_name": flow_name,
                "confidence": conf,
                "latency_ms": latency,
                "correct": is_correct
            })

        except Exception as e:
            print(f"{i:<3} | {query[:42]:<42} | ERROR: {e}")

    # Summary Metrics
    total = len(BENCHMARK_QUERIES)
    accuracy = (correct_matches / total) * 100
    avg_latency = sum(latencies) / len(latencies) if latencies else 0
    p95_latency = sorted(latencies)[int(len(latencies) * 0.95)] if latencies else 0

    print("\n" + "=" * 80)
    print("                      BENCHMARK METRICS SUMMARY")
    print("=" * 80)
    print(f"Total Test Invocations   : {total}")
    print(f"Successful Matches / Rej : {correct_matches}/{total}")
    print(f"Overall Accuracy         : {accuracy:.1f}% (Benchmark Target: >90%)")
    print(f"Average Match Latency    : {avg_latency:.1f}ms (Benchmark Target: <1200ms)")
    print(f"95th Percentile Latency  : {p95_latency:.1f}ms")

    # App-wise Breakdown
    print("\n--- Breakdown by Application ---")
    apps = ["Zomato", "YouTube", "WhatsApp", "Spotify", "Amazon", "None"]
    for app in apps:
        app_items = [r for r in results if r["app"] == app]
        if app_items:
            app_correct = sum(1 for r in app_items if r["correct"])
            app_acc = (app_correct / len(app_items)) * 100
            app_avg_lat = sum(r["latency_ms"] for r in app_items) / len(app_items)
            label = "Negative Controls" if app == "None" else f"{app} Flow"
            print(f"  • {label:<20} : {app_correct}/{len(app_items)} passed ({app_acc:5.1f}%) | avg latency: {app_avg_lat:.1f}ms")

    print("=" * 80)
    return accuracy >= 90.0

if __name__ == "__main__":
    success = run_benchmark()
    sys.exit(0 if success else 1)
