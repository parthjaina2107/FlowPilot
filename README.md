# 🚀 FlowPilot

**Learn-once, replay-anywhere voice automation for Android.**

> Samsung PRISM Generative AI Hackathon — 3rd Edition (2026–27)  
> Theme #03: Teachable Voice Automation  
> Team Cheesecake · SRM University, Kattankulathur

---

## What is FlowPilot?

FlowPilot lets users **demonstrate a task once** inside any Android app, and the system automatically generalises it into a **reusable, parameterised flow** that can be triggered by natural voice commands.

**Example:** Record yourself ordering paneer on Zomato → later say "Get me 2 naans from Zomato" and FlowPilot replays the flow with the new parameters.

## Architecture

```
4-Stage Pipeline: LEARN → GENERALISE → MATCH → REPLAY

┌─────────────┐    ┌──────────────────┐    ┌───────────────┐    ┌───────────────┐
│   LEARN     │───▶│   GENERALISE     │───▶│    MATCH      │───▶│    REPLAY     │
│             │    │                  │    │               │    │               │
│ Accessibility│    │ Gemini 2.0 Flash │    │ Sentence-BERT │    │ Semantic      │
│ Service      │    │ Flow Compiler    │    │ + ChromaDB    │    │ Element       │
│ captures UI  │    │ creates abstract │    │ matches voice │    │ Finder        │
│ tree+actions │    │ FlowGraph with   │    │ commands to   │    │ executes      │
│              │    │ parameter slots  │    │ stored flows  │    │ adaptively    │
└─────────────┘    └──────────────────┘    └───────────────┘    └───────────────┘
```

## Tech Stack

| Layer | Technologies |
|-------|-------------|
| **Core AI** | Gemini 2.0 Flash (flow synthesis) · Whisper (STT) · Sentence-BERT (matching) |
| **Android** | AccessibilityService · Kotlin · Jetpack Compose · Material 3 |
| **Backend** | FastAPI · SQLite · ChromaDB · Docker |

## Documentation

* 📐 **[Technical Architecture & Pipeline Specification](docs/ARCHITECTURE.md)**: Deep dive into the 4-stage pipeline, Accessibility tree capture, and cascading element fallback.
* 🏆 **[Samsung PRISM Pitch & Demo Guide](docs/HACKATHON_PITCH.md)**: 30-second hook, 3-minute live demo script, competitive advantage matrix, and judge Q&A.
* 📡 **[REST API Reference](docs/API_REFERENCE.md)**: Endpoints, request/response models, and curl examples.

## Quick Start

### Backend

```bash
cd backend

# Option 1: Docker
docker-compose up

# Option 2: Local
echo "GEMINI_API_KEY=your_key" > .env
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### Android

1. Open `android/` in Android Studio
2. Update `BACKEND_URL` in `app/build.gradle.kts`:
   - **Emulator**: `http://10.0.2.2:8000/` (default)
   - **Physical Device via USB**: Run `adb reverse tcp:8000 tcp:8000` and use `http://127.0.0.1:8000/`
   - **Physical Device via Wi-Fi**: Use host machine's LAN IP (e.g. `http://192.168.x.x:8000/`)
3. Build and install on device or emulator
4. Enable FlowPilot in **Settings → Accessibility → Installed Apps → FlowPilot** (turn ON)
5. Grant Microphone permission for voice commands

### Demo

1. **Record:** Enter flow name → Start Recording → Switch to target app → Perform task → Return and Stop
2. **Replay:** Tap mic → Say a command (e.g. *"Order butter chicken on Zomato"*) → FlowPilot matches and replays with new parameters
3. **Verify:** Run automated end-to-end tests via `python test_flowpilot.py`

## Project Structure

```
flowpilot/
├── android/                  # Kotlin + Jetpack Compose app
│   └── app/src/main/java/com/flowpilot/
│       ├── service/          # AccessibilityService (Record + Replay)
│       ├── network/          # Retrofit API client
│       ├── model/            # Data classes
│       └── MainActivity.kt   # Compose UI
├── backend/                  # Python FastAPI server
│   ├── routers/              # API endpoints
│   ├── services/             # Gemini, SBERT, Whisper
│   └── models/               # Pydantic schemas + DB
├── docker-compose.yml
└── README.md
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/` | Health check |
| `GET` | `/api/flows` | List all flows |
| `GET` | `/api/flows/{id}` | Get flow details |
| `DELETE` | `/api/flows/{id}` | Delete a flow |
| `POST` | `/api/generalise/compile` | Compile trace → FlowGraph |
| `POST` | `/api/match/text` | Match text command → flow |
| `POST` | `/api/match/audio` | Match audio command → flow |


## License

Built for Samsung PRISM Generative AI Hackathon 2026–27.
