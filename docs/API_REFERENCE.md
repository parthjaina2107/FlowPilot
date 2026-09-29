# FlowPilot - REST API Reference

The FlowPilot backend exposes a high-performance RESTful API built with FastAPI.

**Base URL:** `http://localhost:8000` (or `http://10.0.2.2:8000` from Android Emulator)  
**Interactive Docs:** `http://localhost:8000/docs` (OpenAPI / Swagger UI)

---

## Endpoints Overview

| Method | Path | Summary |
|---|---|---|
| `GET` | `/` | Health check |
| `GET` | `/api/flows/` | List all saved flows (summary) |
| `GET` | `/api/flows/{flow_id}` | Retrieve complete FlowGraph |
| `DELETE` | `/api/flows/{flow_id}` | Delete a flow and its vector index |
| `POST` | `/api/generalise/compile` | Compile raw UI trace into FlowGraph |
| `POST` | `/api/match/text` | Match text voice command to flow |
| `POST` | `/api/match/audio` | Transcribe audio via Whisper & match |

---

## 1. Health Check

### `GET /`
Returns service status and API version.

```bash
curl -X GET http://localhost:8000/
```

**Response (200 OK):**
```json
{
  "status": "ok",
  "service": "flowpilot-backend",
  "version": "1.0.0"
}
```

---

## 2. List Flows

### `GET /api/flows/`
Returns a summary list of all flows stored in the database.

```bash
curl -X GET http://localhost:8000/api/flows/
```

**Response (200 OK):**
```json
[
  {
    "flow_id": "flow-zomato-001",
    "flow_name": "Order Food on Zomato",
    "description": "Searches for a dish, adds to cart, and navigates to checkout on Zomato",
    "target_app_package": "com.application.zomato",
    "created_at": "2026-09-29T10:00:00Z"
  }
]
```

---

## 3. Match Text Command

### `POST /api/match/text`
Finds the best matching flow using ChromaDB vector search and extracts dynamic parameters.

```bash
curl -X POST http://localhost:8000/api/match/text \
  -H "Content-Type: application/json" \
  -d '{"command": "Can you order 2 naans from Zomato?"}'
```

**Response (200 OK):**
```json
{
  "matched": true,
  "flow_id": "flow-zomato-001",
  "flow_name": "Order Food on Zomato",
  "confidence": 0.9186,
  "parameters": {
    "dish_name": "naans",
    "quantity": 2
  },
  "flow_graph": {
    "flow_id": "flow-zomato-001",
    "steps": [ ... ]
  }
}
```

---

## 4. Flow Compilation

### `POST /api/generalise/compile`
Receives a raw `RecordingTrace` from the Android app and invokes Gemini Flash to produce an abstract `FlowGraph`.

```bash
curl -X POST http://localhost:8000/api/generalise/compile \
  -H "Content-Type: application/json" \
  -d '{
    "trace_id": "trace_101",
    "flow_name": "Order Pizza",
    "trigger_phrase": "Order a pizza on Dominos",
    "target_app_package": "com.dominos.order",
    "actions": [
      {
        "timestamp": 1727600000000,
        "action_type": "click",
        "package_name": "com.dominos.order",
        "element_class": "android.widget.EditText",
        "element_text": "Search pizzas"
      }
    ],
    "recorded_at": "2026-09-29T10:00:00Z"
  }'
```
