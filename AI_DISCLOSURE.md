# AI Usage Disclosure Form

> **Samsung PRISM Generative AI Hackathon — 3rd Edition (2026–27)**  
> **Theme #03**: Teachable Voice Automation  

---

## 1. Team Details

| Field | Details |
| :--- | :--- |
| **Team Name** | Cheesecake |
| **Project / Product Name** | FlowPilot — Learn-Once, Replay-Anywhere Voice Automation for Android |
| **Organization / Institution** | SRM Institute of Science and Technology (SRMIST), Kattankulathur |
| **Submission Date** | September 30, 2026 |

---

## 2. AI Usage Declaration

- **Did your team use any Artificial Intelligence (AI) in developing this project?**  
  **Yes**

---

## 3. Purpose of AI Usage

| Area | Usage Details |
| :--- | :--- |
| **Idea Generation / Brainstorming** | Evaluated Accessibility Tree abstractions and parameter slot generalization strategies for Android voice assistants. |
| **Code Generation or Assistance** | Assisted in drafting initial boilerplate for Retrofit HTTP clients, FastAPI route scaffolds, and benchmark test harnesses. |
| **UI / UX Design** | Assisted in selecting Material 3 color tokens and Jetpack Compose component styling. |
| **Content Creation** | Architecture diagrams, sequence flows, and comprehensive API documentation specifications. |
| **Data Analysis & Benchmarking** | Generation of the 25-query 5-app evaluation dataset and latency distribution calculations. |
| **Testing / Debugging** | Automated unit tests and FastAPI test client assertions for vector matching and slot compilation. |

---

## 4. Feature Origin Classification

### Feature 1: Generalized Flow Compilation (LLM Flow Synthesis)
- **Origin**: Hybrid (Prompt Engineering + Custom Heuristic Validation)
- **AI Platform / Model**: Google Gemini 2.0 Flash / Gemini 1.5 Flash
- **Description**: Raw UI action traces recorded by the AccessibilityService are fed into Gemini with structured system prompts. The model identifies dynamic parameters (e.g., search queries, quantities, addresses) and emits an abstract JSON `FlowGraph` with regex parameter extractors.
- **Modifications**: Added strict Pydantic schema validation, fallback deterministic compilers for offline use, and token truncation filters.

### Feature 2: Voice Command Semantic Matching
- **Origin**: Hybrid (Pre-trained Foundation Model + Local Vector Index)
- **AI Platform / Model**: Sentence-BERT (`sentence-transformers/all-MiniLM-L6-v2`) + ChromaDB
- **Description**: Natural language voice commands are projected into a 384-dimensional dense semantic embedding space. Cosine similarity against stored flows ensures paraphrase robustness (e.g., "Order a Margherita" ↔ "Get me pizza from Dominos").
- **Modifications**: Added confidence score thresholding (0.75 cutoff) to cleanly trigger negative intent detection (T12) and ambiguity dialogues (T13).

### Feature 3: Voice Speech-to-Text (STT)
- **Origin**: Model Integration + Native Fallback
- **AI Platform / Model**: OpenAI Whisper (Local backend service) & Android Native `SpeechRecognizer`
- **Description**: Transcribes user speech into clean text commands directly on-device or via the backend service.

### Feature 4: Android Accessibility Execution Engine (Replay & Cascading Finder)
- **Origin**: **Self-Generated (100% Student Engineered)**
- **Description**: The core execution engine is custom Kotlin code leveraging Android's `AccessibilityService`. It implements a 4-tier cascading element finder (`resource-id` → `content-description` → `text-matching` → `relative-bounding-box`), dynamic parameter injection, gesture dispatch, and security pauses for payment/OTP screens.

---

## 5. Ethical & Compliance Confirmation

- **AI usage complies with guidelines and policies**: **Yes**
- **No proprietary or copyrighted data misused**: **I Agree**
- **Zero Credential Capture Guarantee**: No passwords, PINs, OTPs, or payment card details are captured, processed, or logged by any AI models or server components.

---

## 6. Declaration & Sign-Off

- **Name of Team Representative**: Parth Jaina
- **Role**: Team Lead / AI & Systems Developer
- **Institution**: SRM Institute of Science and Technology, Kattankulathur
- **Date**: September 30, 2026
