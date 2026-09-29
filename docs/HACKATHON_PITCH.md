# FlowPilot - Samsung PRISM Hackathon Presentation & Pitch Guide

> **Samsung PRISM Generative AI Hackathon - 3rd Edition (2026-27)**  
> **Theme #03: Teachable Voice Automation**  
> **Team Cheesecake - SRM Institute of Science and Technology**

---

## 1. The 30-Second Elevator Pitch

> *"Voice assistants like Bixby, Siri, and Google Assistant are constrained by static APIs — if an app doesn't build a custom integration, voice automation fails.  
> **FlowPilot breaks this barrier.** It is a **learn-once, replay-anywhere** teachable voice automation engine for Android. A user demonstrates an in-app task just once; FlowPilot captures the accessibility interaction tree, uses **Gemini Flash** to abstract it into a parameterised, resilient workflow, indexes trigger phrases into **ChromaDB**, and autonomously replays it on future voice commands — even when app layouts change."*

---

## 2. The 3-Minute Live Demo Script

| Timestamp | Phase | Action / Visual | Speaking Points |
|---|---|---|---|
| **0:00 - 0:30** | **The Hook** | Show failure of native voice assistant on a task (e.g. searching specific local restaurant). | *"Existing assistants hit a wall when apps lack APIs. Users are forced into repetitive manual taps."* |
| **0:30 - 1:15** | **LEARN** | Open FlowPilot -> Tap "Record New Flow" -> Switch to Zomato -> Search paneer -> Add to cart -> Stop. | *"With FlowPilot, we demonstrate the action once. Our background AccessibilityService records UI events, classes, and visible text."* |
| **1:15 - 1:45** | **GENERALISE** | Show backend logs compiling trace via Gemini. | *"Gemini removes accidental clicks, identifies that 'paneer' is a dynamic slot, and produces an abstract semantic FlowGraph with safety auth gates."* |
| **1:45 - 2:30** | **MATCH & REPLAY** | Say: *'FlowPilot, order 2 garlic naans on Zomato'* -> Watch screen autonomously open Zomato, enter naans, and reach checkout! | *"Sentence-BERT matches our voice command with 92% confidence. Gemini extracts the new dish and quantity. The replay engine uses cascading selectors to execute gracefully."* |
| **2:30 - 3:00** | **Impact & Scalability** | Show pre-recorded flows for YouTube, Spotify, WhatsApp. | *"FlowPilot works universally across any Android application without modifying app source code or needing root access."* |

---

## 3. Competitive Comparison

| Feature | Standard Assistants (Bixby/Google) | Traditional Macro Apps (Tasker) | FlowPilot (Ours) |
|---|---|---|---|
| **App Support** | Limited to supported partner APIs | Works on any app | **Universal (any Android app)** |
| **Setup Effort** | Requires developer to write App Action | Complex manual $(x,y)$ scripting | **Single demonstration (zero-code)** |
| **Layout Resilience** | High (API-level) | Zero (breaks on screen resize/redesign) | **High (Cascading Semantic Selectors)** |
| **Natural Voice Query** | Predefined intents only | Rigid keywords | **ChromaDB Vector Matching + Gemini** |
| **Safety & Privacy** | Platform-controlled | None (blind clicks into payment) | **`is_auth_pause` biometric gate** |

---

## 4. Top Judge Questions & How to Answer

### Q1: *"What happens if the app updates its UI layout or changes a button ID?"*
> **Answer:** *"Unlike brittle auto-clickers that rely on resource IDs or pixel coordinates, FlowPilot uses a **4-tier cascading fallback selector**:  
> 1. Exact match (Role + Text + Content Description + ID)  
> 2. Role + Text Contains (e.g., any Button containing 'Add')  
> 3. Text Only match  
> 4. Content Description match with auto-scroll forward.  
> Even if developers rename internal IDs, visible labels and roles remain stable."*

### Q2: *"Is this safe? What prevents unauthorized transactions or credential theft?"*
> **Answer:** *"Security is built into the compilation stage. Gemini automatically identifies sensitive screens (UPI PINs, passwords, OTPs, final payment confirmation) and flags them with `is_auth_pause: true`. During replay, the service halts execution before the sensitive action and requires user biometric/fingerprint authentication before proceeding."*

### Q3: *"Can this run on low-end Android devices?"*
> **Answer:** *"Yes. The on-device Android service is lightweight (pure Kotlin + AccessibilityService). Heavy AI synthesis (Gemini) and vector indexing (ChromaDB) run asynchronously on the server or edge gateway, requiring negligible battery and memory from the mobile device."*
