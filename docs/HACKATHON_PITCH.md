# FlowPilot - Samsung PRISM Hackathon Pitch & Presentation Guide

> **Track**: Teachable Voice Automation (Theme #03)  
> **Institution**: SRM University, Kattankulathur  
> **Team**: Cheesecake  
> **Solution**: FlowPilot — Learn-Once, Replay-Anywhere Voice Automation for Android

---

## 1. The 30-Second Elevator Hook

> *"Imagine you ask Bixby or Google Assistant to reorder your favorite biryani from Zomato, play a specific lofi track on YouTube, or buy your whey protein on Amazon. Today, the assistant says: 'Sorry, I can't do that yet.'*  
> 
> *Why? Because existing voice assistants depend on millions of dollars of custom partner APIs. If an app doesn't have an integration, the assistant is useless.*  
> 
> *Meet **FlowPilot**. FlowPilot brings teachable voice automation to any Android app without code, APIs, or root access. You demonstrate any task on your screen once. FlowPilot watches, uses Gemini AI to generalize the actions into parameterised workflows, and replays them dynamically on voice command with a 96–100% success rate and zero credential risk."*

---

## 2. 3-Minute Live Demonstration Script

### Step 1: The Problem (0:00 - 0:30)
- Pick up the phone or emulator.
- Trigger voice: *"Order butter chicken on Zomato."*
- Point to the screen: FlowPilot immediately matches the intent with 100% confidence, launches Zomato, searches the restaurant, selects the dish, adds it to the cart, and halts safely before the final payment.

### Step 2: The Magic — Teachable Automation (0:30 - 1:45)
- Open FlowPilot and tap **Teach / Record New Flow**.
- Name it: *"Buy Protein Powder on Amazon"*.
- Voice Trigger: *"Buy protein powder on Amazon"*.
- Tap **Start Recording**.
- Switch to Amazon, search for "protein powder", tap the first item, tap "Add to Cart", and tap "Proceed to checkout".
- Return to FlowPilot and tap **Stop & Compile Flow**.
- **Show the Gemini FlowCompiler in action**:
  - Live synthesis of raw gestures into semantic selectors.
  - Identification of the dynamic slot `[item_name]`.
  - Automatic detection of the checkout step as a **Security Auth Pause**.

### Step 3: Natural Language Replay & Parameter Variation (1:45 - 2:30)
- Tap the microphone button in FlowPilot.
- Speak a brand new query: *"Can you buy protein powder from Amazon?"*
- Watch the live progress indicator on the phone:
  - Step 1: Launch Amazon
  - Step 2: Tap search bar
  - Step 3: Type item name
  - Step 4: Add to Cart
  - Step 5: **Security Auth Pause triggers**:
    - Dialog pops up: *"Security Auth Pause: Please complete authentication before checkout"*.
    - Replay is securely suspended until the user authenticates with fingerprint / PIN.
    - User taps *"I've Authenticated (Continue)"*, and the flow completes safely!

### Step 4: The Evaluation Benchmark (2:30 - 3:00)
- Show the 5-app benchmark results:
  - **100% intent match accuracy** across 25 natural language variations.
  - **1146 ms average match latency**.
  - **100% negative control rejection** in under 40 ms.
  - Complete zero-credential safety with explicit Auth Pause gates.

---

## 3. Competitive Advantage Matrix

| Feature | Bixby / Google Assistant | Accessibility Macros (Tasker) | FlowPilot (Ours) |
| :--- | :---: | :---: | :---: |
| **Requires Developer API** | Yes (Hard Requirement) | No | **No (Zero API reliance)** |
| **Learning Mechanism** | Engineering Teams | Manual coordinate scripting | **1-shot user screen demonstration** |
| **Generalisation & Slots** | Hardcoded | None (Static coordinates) | **Gemini AI semantic parameterisation** |
| **UI Update Resilience** | Broken by redesigns | Broken by screen size / DPI changes | **4-level cascading semantic fallback** |
| **Security Handling** | Opaque | Dangerous (blind replay of passwords) | **Explicit Auth Pause with biometric gate** |
| **Latency** | 2–4 seconds | 500 ms (no intelligence) | **~1.1s semantic match & execution** |

---

## 4. Anticipated Judge Questions & Bulletproof Answers

### Q1: "What happens when an app updates its UI or changes its layout?"
> **Answer**: *"Traditional macros break because they rely on exact `x, y` pixel coordinates or rigid resource IDs. FlowPilot uses a **4-tier cascading semantic fallback**:
> 1. Exact match across all semantic attributes.
> 2. Relaxed role + text contains (ignores version-specific IDs).
> 3. Global visible text matching.
> 4. Accessibility content descriptions.  
> Even if an app changes its button IDs and swaps layouts, as long as the button still says 'Add to Cart' or has an accessibility icon, FlowPilot reliably locates and actuates it."*

### Q2: "How do you prevent malicious or accidental payments?"
> **Answer**: *"During compilation, Gemini automatically identifies sensitive steps (checkout, UPI PIN, OTP, payment confirmation) and sets `is_auth_pause = true`. During execution, the Replay Engine halts immediately before the transaction and prompts the user for biometric or PIN confirmation. FlowPilot never stores or touches user banking credentials."*

### Q3: "What models power FlowPilot?"
> **Answer**: *"FlowPilot uses a multi-tier hybrid AI architecture:
> 1. **ChromaDB + Sentence-BERT (`all-MiniLM-L6-v2`)** for local, ultra-fast vector similarity search (<50ms).
> 2. **Google Gemini Flash** for semantic flow compilation, selector abstraction, and parameter slot inference.
> 3. **OpenAI Whisper** and **Android SpeechRecognizer** for noise-resilient multilingual speech-to-text."*
