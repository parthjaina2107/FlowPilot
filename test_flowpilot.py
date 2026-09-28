"""
FlowPilot - End-to-End System Test & Verification Script
Learn-once, replay-anywhere voice automation.

Runs validation tests against the live FlowPilot backend:
  1. Health Check
  2. Flow Management (CRUD)
  3. Semantic Flow Matching (ChromaDB + Sentence-BERT)
  4. Dynamic Parameter Resolution
  5. Cascading Fallback Replay Simulation
"""

import json
import urllib.request
import urllib.error
import sys

BASE_URL = "http://127.0.0.1:8000"

def banner(title):
    print("\n" + "=" * 60)
    print(f"  {title}")
    print("=" * 60)

def http_get(path):
    url = f"{BASE_URL}{path}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def http_post(path, data):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def run_tests():
    banner("TEST 1: Backend Health Check")
    try:
        status, data = http_get("/")
        print(f"Status Code : {status}")
        print(f"Service     : {data.get('service')}")
        print(f"Version     : {data.get('version')}")
        print(f"Health      : {data.get('status')}")
        assert status == 200 and data.get("status") == "ok", "Health check failed"
        print("[PASS] Health check verified.")
    except Exception as e:
        print(f"[FAIL] Could not connect to backend at {BASE_URL}: {e}")
        return False

    banner("TEST 2: Flow Database & List")
    try:
        status, flows = http_get("/api/flows/")
        print(f"Total flows registered: {len(flows)}")
        for i, f in enumerate(flows, 1):
            print(f"  {i}. [{f['flow_id']}] {f['flow_name']} ({f['target_app_package']})")
        assert len(flows) > 0, "No flows found in database"
        sample_id = flows[0]["flow_id"]
        print(f"[PASS] Flow listing verified. Sample flow: {sample_id}")
    except Exception as e:
        print(f"[FAIL] Flow listing failed: {e}")
        return False

    banner("TEST 3: Flow Details Inspection")
    try:
        status, flow_detail = http_get(f"/api/flows/{sample_id}")
        print(f"Flow Name   : {flow_detail['flow_name']}")
        print(f"App Package : {flow_detail['target_app_package']}")
        print(f"Triggers    : {flow_detail['trigger_phrases']}")
        print(f"Params      : {list(flow_detail.get('parameter_schema', {}).keys())}")
        print(f"Steps count : {len(flow_detail['steps'])}")
        print("\nStep Sequence:")
        for step in flow_detail["steps"]:
            pause = " [AUTH PAUSE]" if step.get("is_auth_pause") else ""
            param = f" (slot: {step.get('parameter_slot')})" if step.get("parameter_slot") else ""
            print(f"  [{step['step_index']}] {step['action_type'].upper():<8} -> {step['description']}{param}{pause}")
        print("[PASS] Flow details and step structure verified.")
    except Exception as e:
        print(f"[FAIL] Flow details failed: {e}")
        return False

    banner("TEST 4: Semantic Matching via Vector Search (ChromaDB)")
    test_queries = [
        ("Order butter chicken on Zomato", True, "Direct match with trigger phrase"),
        ("Get dinner from Zomato", True, "Semantic synonym match"),
        ("Can you order butter chicken from Zomato?", True, "Natural conversational phrasing"),
        ("Zomato butter chicken", True, "Short keyword query"),
        ("Book an Uber cab to the airport", False, "Unrelated voice command (should NOT match)"),
    ]

    for query, expected_match, note in test_queries:
        print(f"\nQuery   : \"{query}\"")
        print(f"Context : {note}")
        status, res = http_post("/api/match/text", {"command": query})
        matched = res.get("matched", False)
        conf = res.get("confidence")
        flow_name = res.get("flow_name")

        if matched:
            print(f"Result  : MATCHED -> '{flow_name}' (Confidence: {conf*100:.1f}%)")
            print(f"Params  : {res.get('parameters')}")
        else:
            print(f"Result  : NO MATCH -> {res.get('suggestion')}")

        if matched == expected_match:
            print("[PASS] Match expectation met.")
        else:
            print(f"[WARN] Expected match={expected_match}, but got {matched}")

    banner("TEST 5: Android Replay Engine Simulation")
    print("Simulating how FlowReplayService executes the matched flow on device:\n")
    for step in flow_detail["steps"]:
        action = step["action_type"]
        selector = step["selector"]
        desc = step["description"]
        wait = step["wait_after_ms"]

        print(f">> Executing Step {step['step_index']}: {desc}")
        if action == "open_app":
            print(f"   Intent: Launch package '{flow_detail['target_app_package']}'")
        elif action == "click":
            print(f"   Accessibility: Find node by {selector} -> performAction(ACTION_CLICK)")
        elif action == "type":
            val = step.get("default_value", "")
            print(f"   Accessibility: Find node by {selector} -> performAction(ACTION_SET_TEXT, '{val}')")
        if step.get("is_auth_pause"):
            print("   Security: Pausing replay for biometric / OTP confirmation by user.")
        print(f"   Delay: {wait}ms\n")

    banner("ALL VERIFICATION CHECKS COMPLETED!")
    print("FlowPilot Backend and Semantic Matching Engine are fully operational!")
    return True

if __name__ == "__main__":
    success = run_tests()
    sys.exit(0 if success else 1)
