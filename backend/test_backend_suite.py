import sys
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
import requests

BASE = "http://127.0.0.1:8000"

def test_all():
    print("--- 1. Testing GET / (Health) ---")
    r = requests.get(f"{BASE}/")
    assert r.status_code == 200, f"Health check failed: {r.status_code}"
    print("  ✓ Health OK:", r.json())

    print("\n--- 2. Testing GET /api/flows ---")
    r = requests.get(f"{BASE}/api/flows")
    assert r.status_code == 200, f"Flows list failed: {r.status_code}"
    flows = r.json()
    print(f"  ✓ Found {len(flows)} seeded flows:")
    for f in flows:
        print(f"    - {f.get('flow_name')} ({f.get('target_app_package')})")

    print("\n--- 3. Testing POST /api/match/text (Test 1: Voice match) ---")
    r = requests.post(f"{BASE}/api/match/text", json={"command": "Order two naans from Zomato"})
    assert r.status_code == 200
    res = r.json()
    print("  ✓ Matched:", res.get("matched"))
    print("  ✓ Flow name:", res.get("flow_name"))
    print("  ✓ Slots:", res.get("parameters"))
    print("  ✓ Confidence:", res.get("confidence"))

    print("\n--- 4. Testing POST /api/match/text (Test 2: Paraphrase) ---")
    r = requests.post(f"{BASE}/api/match/text", json={"command": "Get me two naans from Zomato"})
    assert r.status_code == 200
    res = r.json()
    print("  ✓ Paraphrase Matched:", res.get("matched"), "Flow:", res.get("flow_name"))

    print("\n--- 5. Testing POST /api/match/text (Test 3: Changed slots) ---")
    r1 = requests.post(f"{BASE}/api/match/text", json={"command": "Order a Margherita pizza from Zomato"})
    r2 = requests.post(f"{BASE}/api/match/text", json={"command": "Order a Farmhouse pizza from Zomato"})
    assert r1.status_code == 200 and r2.status_code == 200
    p1 = r1.json().get("parameters", {})
    p2 = r2.json().get("parameters", {})
    print("  ✓ Command 1 slot (dish_name):", p1.get("dish_name"))
    print("  ✓ Command 2 slot (dish_name):", p2.get("dish_name"))

    print("\n--- 6. Testing POST /api/match/text (Test 4: YouTube) ---")
    r = requests.post(f"{BASE}/api/match/text", json={"command": "Play lofi music on YouTube"})
    assert r.status_code == 200
    res = r.json()
    print("  ✓ YouTube Matched:", res.get("matched"), "Flow:", res.get("flow_name"))
    print("  ✓ Query slot:", res.get("parameters"))

    print("\n--- 7. Testing POST /api/match/text (Test 5: WhatsApp) ---")
    r = requests.post(f"{BASE}/api/match/text", json={"command": "Send Dad a message saying I'm running late"})
    assert r.status_code == 200
    res = r.json()
    print("  ✓ WhatsApp Matched:", res.get("matched"), "Flow:", res.get("flow_name"))
    print("  ✓ Slots:", res.get("parameters"))

    print("\n--- 8. Testing POST /api/match/text (Unknown intent) ---")
    r = requests.post(f"{BASE}/api/match/text", json={"command": "Book a cab to the airport"})
    assert r.status_code == 200
    res = r.json()
    print("  ✓ Unknown Intent Matched:", res.get("matched"))
    print("  ✓ Suggestion:", res.get("suggestion"))

    print("\n--- 9. Testing POST /api/generalise/compile (Validation: < 2 actions) ---")
    r = requests.post(f"{BASE}/api/generalise/compile", json={
        "trace_id": "test_short",
        "flow_name": "Test",
        "trigger_phrase": "Test",
        "target_app_package": "com.test",
        "actions": [
            {"action_type": "click", "element_bounds": [0,0,10,10], "timestamp": 123}
        ]
    })
    print("  ✓ Single action status code:", r.status_code)

    print("\n================ ALL BACKEND API TESTS COMPLETED ================")

if __name__ == "__main__":
    test_all()
