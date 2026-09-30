# AI Usage Disclosure Form

> **Samsung PRISM Generative AI Hackathon — 3rd Edition (2026–27)**
> **Theme #03**: Teachable Voice Automation for Android
> **Official Event**: Samsung PRISM x SRM University GenAI Hackathon 3.0

---

## 1. Team & Project Details

| Field | Details |
| :--- | :--- |
| **Team Name** | Cheesecake |
| **Project / Product Name** | FlowPilot — Learn-Once, Replay-Anywhere Voice Automation for Android |
| **Organization / Institution** | SRM Institute of Science and Technology (SRMIST), Kattankulathur |
| **GitHub Repository** | https://github.com/parthjaina2107/FlowPilot |
| **Submission Date** | September 30, 2026 |
| **Hackathon Theme** | Theme #03 — Teachable Voice Automation |

---

## 2. AI Usage Declaration

- **Did your team use any Artificial Intelligence (AI) tools or models in developing this project?**
  ✅ **Yes**

- **Was any part of the project code, architecture, or documentation generated using AI assistance?**
  ✅ **Yes — all usage is fully disclosed below.**

---

## 3. AI Tools & Platforms Used

| AI Tool / Platform | Version / Model | Usage Category |
| :--- | :--- | :--- |
| **Google Gemini** | Gemini 2.0 Flash / 1.5 Flash | Core feature (Flow Compilation & Slot Extraction) |
| **Sentence-BERT** | `all-MiniLM-L6-v2` (HuggingFace) | Core feature (Semantic Voice Matching) |
| **ChromaDB** | v0.5.x | Vector database for embedding storage |
| **OpenAI Whisper** | Base model (local) | Speech-to-Text transcription |
| **Android SpeechRecognizer** | Android Native API | On-device fallback STT |
| **Google Gemini (coding assistant)** | Gemini 2.5 Pro / Flash | Code scaffolding assistance & documentation |

---

## 4. Purpose of AI Usage

### 4.1 AI as a Core Feature (Product Functionality)

These AI usages are **central to the product** and are what the hackathon evaluates:

| Feature | AI Model Used | What It Does |
| :--- | :--- | :--- |
| **Flow Generalization & Compilation** | Google Gemini 2.0 Flash | Converts raw UI action traces recorded by the AccessibilityService into abstract, parameterized FlowGraph JSON structures with dynamic slot extractors. |
| **Semantic Voice Command Matching** | Sentence-BERT + ChromaDB | Embeds voice commands into 384-dimensional vector space. Finds the closest learned flow using cosine similarity (threshold: 0.75). Enables paraphrase robustness. |
| **Speech-to-Text** | Whisper (backend) + Android SpeechRecognizer (device) | Transcribes spoken commands to text for intent matching. |
| **Slot Parameter Inference** | Google Gemini 2.0 Flash | Identifies dynamic parameters (dish name, quantity, address) and generates regex-based extractors for runtime substitution. |

### 4.2 AI as Development Assistance (Process Support)

These AI usages assisted the **development process** but are not the product itself:

| Area | Usage Details | Human Oversight Applied |
| :--- | :--- | :--- |
| **Idea Generation / Brainstorming** | Evaluated Accessibility Tree abstraction strategies and cascading fallback patterns for Android voice automation. | All design decisions independently validated and selected by the team. |
| **Code Generation / Scaffolding** | Assisted in drafting initial boilerplate for Retrofit clients, FastAPI routes, Pydantic schemas, and benchmark test harnesses. | All generated code reviewed, modified, tested, and integrated manually by the team. |
| **Documentation** | Assisted in drafting architecture diagrams, API specifications, and sequence flow documentation. | All content reviewed and verified for accuracy against the actual implementation. |
| **UI / UX Design** | Suggested Material 3 color tokens and Jetpack Compose component patterns. | Team selected final design choices independently. |
| **Data & Benchmarking** | Assisted in creating the 25-query x 5-app evaluation dataset structure and latency analysis framework. | All benchmark runs executed on real hardware with manual verification. |
| **Testing** | Assisted in drafting unit test assertions for the vector matching API and FastAPI test client setup. | All tests reviewed and verified by the team before inclusion. |

---

## 5. Feature-by-Feature Origin Classification

### Feature 1: Generalized Flow Compilation (LLM-Powered)

- **Origin**: AI-Powered Core Feature
- **AI Platform / Model**: Google Gemini 2.0 Flash
- **Student Contribution**: Prompt engineering, schema design, Pydantic validation layer, offline deterministic fallback compiler, and integration architecture.
- **Description**: Raw UI action traces (`action_type`, `resource_id`, `text`, `content_desc`, `bounds`) recorded by the `FlowRecorderService` are structured and sent to Gemini with a custom system prompt. The model identifies dynamic parameters and emits an abstract `FlowGraph` JSON with named slots and regex extractors.
- **What AI does NOT do**: Gemini does not access the internet during replay, does not store user data, and performs no device actions. It only processes locally collected action traces during the compile step.

---

### Feature 2: Voice Command Semantic Matching

- **Origin**: AI-Powered Core Feature (Pre-trained Foundation Model + Local Inference)
- **AI Platform / Model**: Sentence-BERT (`sentence-transformers/all-MiniLM-L6-v2`) + ChromaDB
- **Student Contribution**: Integration of the embedding pipeline, ChromaDB indexing architecture, confidence threshold tuning (0.75 cutoff), ambiguity resolution routing, and negative intent detection.
- **Description**: Voice commands are encoded into 384-dimensional dense semantic embeddings. Cosine similarity against stored flow embeddings identifies the best match. Confidence thresholds trigger: (a) exact execution, (b) clarification dialog, or (c) "haven't learned this yet" response.

---

### Feature 3: Speech-to-Text (STT)

- **Origin**: Model Integration + Native Fallback
- **AI Platform / Model**: OpenAI Whisper (local backend) & Android Native `SpeechRecognizer`
- **Student Contribution**: Backend routing logic, fallback switching mechanism, noise filtering pipeline, and integration with the intent matching layer.
- **Description**: Transcribes spoken user commands to text. Whisper runs locally on the backend server for higher accuracy; Android's native `SpeechRecognizer` serves as an on-device low-latency fallback.

---

### Feature 4: Android Accessibility Execution Engine

- **Origin**: ⭐ **100% Self-Engineered (Student-Built) — No AI Used**
- **AI Platform / Model**: None
- **Student Contribution**: Entirely designed and implemented by the team.
- **Description**: The core replay engine is custom Kotlin code using Android's `AccessibilityService` API. Implements:
  1. **4-Tier Cascading Element Finder**: `resource-id` → `content-description` → `text-match` → `relative-bounding-box`
  2. **Dynamic Parameter Injection**: Replaces recorded slot values with new ones at replay time.
  3. **Security Auth Pause Gate**: Dual-layer detection (compile-time Gemini flag + runtime keyword scanner) freezes execution and hands control to the user before any payment, OTP, PIN, or credential screen.
  4. **Overlay Dismissal Heuristic**: Detects unexpected pop-ups and recovers automatically.
  5. **Stuck Recovery & User Notification**: Broadcasts `ACTION_REPLAY_STUCK` if all fallbacks fail within 30 seconds.

---

## 6. Data Usage & Privacy Compliance

| Data Type | Collected? | AI Processing? | Notes |
| :--- | :--- | :--- | :--- |
| **UI Action Traces** | Yes (only during explicit "Teach" mode) | Yes (Gemini compiles structure only) | Contains app element IDs and text — NOT user credentials. |
| **Voice Audio** | Temporarily during recognition | Whisper / SpeechRecognizer | Audio is NOT stored or logged after transcription. |
| **Passwords / PINs / OTPs** | ❌ Never | ❌ Never | Hard security boundary — execution halts before any credential screen. |
| **Payment Card Details** | ❌ Never | ❌ Never | Auth Pause gate prevents any access to payment fields. |
| **Personal User Data** | ❌ No | ❌ No | FlowPilot captures structural UI metadata (node IDs, text), not personal profile information. |

---

## 7. Ethical & Compliance Confirmation

| Statement | Confirmed |
| :--- | :---: |
| AI usage complies with Samsung PRISM hackathon guidelines and policies | ✅ Yes |
| No proprietary, copyrighted, or licensed training data was misused | ✅ Yes |
| All AI-generated code has been reviewed, understood, and verified by the team | ✅ Yes |
| No user passwords, PINs, OTPs, or payment details are captured, processed, or logged | ✅ Yes |
| All AI tools used are commercially available, publicly accessible services | ✅ Yes |
| The core innovation (Accessibility Execution Engine) is entirely student-engineered | ✅ Yes |
| Team members can explain and defend all AI-assisted components during evaluation | ✅ Yes |

---

## 8. AI Contribution vs. Student Innovation Summary

| Component | Primarily AI | Primarily Student-Built | Hybrid |
| :--- | :---: | :---: | :---: |
| Flow Compilation via Gemini | ✅ | | |
| Prompt Engineering & Schema Design | | ✅ | |
| Semantic Voice Matching (SBERT + ChromaDB) | | | ✅ |
| Embedding Pipeline & Threshold Tuning | | ✅ | |
| Speech-to-Text (Whisper Integration) | ✅ | | |
| Android Accessibility Execution Engine | | ✅ | |
| 4-Tier Cascading Element Finder | | ✅ | |
| Security Auth Pause Gate | | ✅ | |
| Overlay Dismissal & Stuck Recovery | | ✅ | |
| Android UI (Jetpack Compose) | | | ✅ |
| Backend FastAPI Architecture | | | ✅ |
| Benchmark Test Suite | | | ✅ |

**Overall Assessment**: The AI models (Gemini, SBERT) power the intelligence layer (semantic understanding and flow generalization). The core Android automation engine — the most technically challenging and novel part of the system — is entirely student-built without AI code generation.

---

## 9. Declaration & Sign-Off

We, the undersigned members of **Team Cheesecake**, declare that all information in this AI Disclosure Form is accurate, complete, and truthful to the best of our knowledge. All AI usage has been transparently declared and complies with the Samsung PRISM Generative AI Hackathon 3rd Edition guidelines.

| Field | Details |
| :--- | :--- |
| **Team Representative Name** | Sanjeev Aryan |
| **Role** | Team Lead / AI & Systems Developer |
| **Institution** | SRM Institute of Science and Technology, Kattankulathur |
| **Email** | aryansanjeev0651@gmail.com |
| **Submission Date** | September 30, 2026 |
| **GitHub Repository** | https://github.com/parthjaina2107/FlowPilot |

---

*This form is submitted as part of the official FlowPilot hackathon submission package.*
