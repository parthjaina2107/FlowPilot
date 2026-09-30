# FlowPilot Backend API Specification

> **Base URL (Local)**: `http://127.0.0.1:8000`  
> **Base URL (Android Emulator)**: `http://10.0.2.2:8000`  
> **Protocol**: HTTP/1.1 REST + JSON (multipart/form-data for audio)  
> **Content-Type**: `application/json`

---

## 1. Endpoints Overview

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/` | Health check & service status |
| `GET` | `/api/flows` | List all registered flows (summary view) |
| `GET` | `/api/flows/{flow_id}` | Retrieve complete `FlowGraph` by unique ID |
| `DELETE` | `/api/flows/{flow_id}` | Delete a flow and its ChromaDB trigger vectors |
| `POST` | `/api/generalise/compile` | Compile raw `RecordingTrace` into generalized `FlowGraph` |
| `POST` | `/api/match/text` | Match text voice command to flow + extract parameters |
| `POST` | `/api/match/audio` | Transcribe voice audio via Whisper and match flow |

---

## 2. API Reference & Examples

### 1. Health Check
```http
GET /
```
**Response (200 OK):**
```json
{
  "service": "flowpilot-backend",
  "version": "1.0.0",
  "status": "ok"
}
```

---

### 2. List Saved Flows
```http
GET /api/flows
```
**Response (200 OK):**
```json
[
  {
    "flow_id": "flow-amazon-005",
    "flow_name": "Buy Product on Amazon",
    "description": "Searches for an item on Amazon, selects the first result, adds to cart, and proceeds to checkout",
    "target_app_package": "in.amazon.mShop.android.shopping",
    "created_at": "2026-09-30T10:00:00Z"
  }
]
```

---

### 3. Get Flow Details
```http
GET /api/flows/{flow_id}
```
**Response (200 OK):**
```json
{
  "flow_id": "flow-zomato-001",
  "flow_name": "Order Food on Zomato",
  "description": "Searches for a dish, adds to cart, and navigates to checkout on Zomato",
  "trigger_phrases": [
    "Order butter chicken on Zomato",
    "Get food from Zomato",
    "Order dinner from Zomato",
    "Zomato food order"
  ],
  "target_app_package": "com.application.zomato",
  "parameter_schema": {
    "dish_name": {
      "type": "string",
      "description": "Dish to order",
      "default": "butter chicken"
    },
    "quantity": {
      "type": "integer",
      "description": "Quantity",
      "default": 1
    }
  },
  "steps": [
    {
      "step_index": 0,
      "action_type": "open_app",
      "selector": { "role": "app", "package": "com.application.zomato" },
      "parameter_slot": null,
      "default_value": null,
      "description": "Launch Zomato",
      "wait_after_ms": 2500,
      "is_auth_pause": false
    },
    {
      "step_index": 5,
      "action_type": "click",
      "selector": { "role": "button", "text_contains": "Place Order" },
      "parameter_slot": null,
      "default_value": null,
      "description": "Place order",
      "wait_after_ms": 1000,
      "is_auth_pause": true
    }
  ],
  "created_at": "2026-09-29T10:00:00Z",
  "version": 1
}
```

---

### 4. Delete Flow
```http
DELETE /api/flows/{flow_id}
```
**Response (200 OK):**
```json
{
  "deleted": true,
  "flow_id": "flow-zomato-001"
}
```

---

### 5. Compile Recording Trace (LEARN → GENERALISE)
```http
POST /api/generalise/compile
Content-Type: application/json
```
**Request Body (`RecordingTrace`):**
```json
{
  "trace_id": "trace_1740001234",
  "flow_name": "Search Book on Amazon",
  "trigger_phrase": "Search for atomic habits on Amazon",
  "target_app_package": "in.amazon.mShop.android.shopping",
  "actions": [
    {
      "timestamp": 1740001234000,
      "action_type": "click",
      "package_name": "in.amazon.mShop.android.shopping",
      "element_id": "in.amazon.mShop.android.shopping:id/rs_search_src_text",
      "element_class": "android.widget.EditText",
      "element_text": "Search Amazon.in",
      "bounds": "[48,120][1032,240]"
    },
    {
      "timestamp": 1740001236000,
      "action_type": "type",
      "package_name": "in.amazon.mShop.android.shopping",
      "element_class": "android.widget.EditText",
      "typed_text": "Atomic Habits"
    }
  ],
  "recorded_at": "2026-09-30T10:00:00Z"
}
```
**Response (200 OK):** Returns compiled `FlowGraph` with synthesized semantic selectors, parameters, and trigger phrases.

---

### 6. Semantic Flow Matching (Text Command)
```http
POST /api/match/text
Content-Type: application/json
```
**Request Body:**
```json
{
  "command": "Can you order 2 butter chicken from Zomato?"
}
```
**Response (200 OK - Match Found):**
```json
{
  "matched": true,
  "flow_id": "flow-zomato-001",
  "flow_name": "Order Food on Zomato",
  "confidence": 0.919,
  "parameters": {
    "dish_name": "butter chicken",
    "quantity": 2
  },
  "flow_graph": { "...": "full FlowGraph object" },
  "transcribed_text": "Can you order 2 butter chicken from Zomato?",
  "suggestion": null
}
```
**Response (200 OK - Out-of-Domain Rejection):**
```json
{
  "matched": false,
  "flow_id": null,
  "flow_name": null,
  "confidence": null,
  "parameters": null,
  "flow_graph": null,
  "transcribed_text": null,
  "suggestion": "No matching flow found. Try recording a new flow first."
}
```

---

### 7. Audio Command Matching
```http
POST /api/match/audio
Content-Type: multipart/form-data
```
**Form Data**:
- `audio`: binary audio file (WAV / MP3 / M4A / 3GP)

**Response (200 OK)**: Transcribes speech using Whisper, then routes to semantic matching engine and returns `MatchResult`.
