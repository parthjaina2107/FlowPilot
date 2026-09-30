# FlowPilot - Samsung PRISM Hackathon Pitch & Presentation Guide

> **Track**: Teachable Voice Automation (Theme #03)  
> **Institution**: SRM University, Kattankulathur  
> **Team**: Cheesecake  
> **Solution**: FlowPilot — Learn-Once, Replay-Anywhere Voice Automation for Android  
> **Presentation Deck**: [`SRMUniversity_Cheesecake_Submission.pptx`](../SRMUniversity_Cheesecake_Submission.pptx)  

---

## 1. The 30-Second Elevator Hook

> *"Imagine you ask Bixby or Google Assistant to reorder your favorite biryani from Zomato, play a specific lofi track on YouTube, or buy your whey protein on Amazon. Today, the assistant says: 'Sorry, I can't do that yet.'*  
> 
> *Why? Because existing voice assistants depend on millions of dollars of custom partner APIs. If an app doesn't have an integration, the assistant is useless.*  
> 
> *Meet **FlowPilot**. FlowPilot brings teachable voice automation to any Android app without code, APIs, or root access. You demonstrate any task on your screen once. FlowPilot watches, uses Gemini AI to generalize the actions into parameterised workflows, and replays them dynamically on voice command with a 96–100% success rate and zero credential risk."*

---

## 2. Official 5-Minute Unedited Demonstration Script

> **Video Link**: 🔗 **[Official Demo Video on Google Drive](https://drive.google.com/drive/folders/1Rt1xt2NEt5b5ghA3HExw9LYlbmGPr-cC?usp=sharing)**

In strict accordance with the **Samsung PRISM Theme 3 Submission Guidelines**, the evaluation video demonstrates the following 5 parts, unedited and in exact order:

```
[Official Sequence]: (a) Teach Flow ➔ (b) Exact Replay ➔ (c) Paraphrase ➔ (d) Changed Slot ➔ (e) Assistant Asking When Stuck
```

### Part (a): Teaching one flow by voice + taps (0:00 – 1:30)
- **Voice trigger**: Speak *"Order a Margherita pizza from Domino's on Zomato"*.
- **Demonstration**: Open Zomato, search for "Domino's", select "Margherita Pizza", tap "Add to Cart", and stop at the payment checkout screen.
- **Flow Synthesis**: Return to FlowPilot and tap **Stop & Compile**.
  - FlowCompiler (Gemini 2.0 Flash) abstracts raw taps into semantic selectors.
  - Parameter slots (`[dish_name]`, `[quantity]`) are extracted.
  - Screen confirmation shown: *"Learned: Order Margherita pizza from Domino's"*.

### Part (b): Replaying it with the exact utterance (1:30 – 2:15)
- **Voice command**: Repeat verbatim: *"Order a Margherita pizza from Domino's on Zomato"*.
- **Autonomous Execution**: FlowPilot recognizes the command (100% confidence), launches Zomato, searches the item, adds it to the cart, and halts safely with the Auth Pause gate before payment.
- **Pass Verification**: Reaches payment unattended with correct item in cart.

### Part (c): Replaying with a paraphrase (2:15 – 3:00)
- **Paraphrase command 1**: *"Get me a margherita from dominos"*.
- **Paraphrase command 2**: *"I want to order margherita pizza on zomato"*.
- **Semantic Matching**: Sentence-BERT embeds the sentence into ChromaDB; cosine similarity exceeds 92%, triggering the identical flow without re-teaching.

### Part (d): Replaying with a changed slot value (3:00 – 3:45)
- **Slot modification**: *"Order a Farmhouse pizza from Domino's on Zomato"*.
- **Dynamic Slot Injection**: FlowPilot extracts `dish_name = "Farmhouse pizza"`.
- **Execution**: The search field receives "Farmhouse pizza", adds Farmhouse to cart, and reaches checkout.

### Part (e): Assistant asking the user a question when stuck (3:45 – 4:45)
- **Stuck Trigger**: Pre-condition simulated (app language switched to Hindi or logged out).
- **Graceful Halt**: FlowPilot attempts cascading element recovery (<30s). Detecting that critical nodes are missing, it halts without destructive mis-clicks.
- **Interactive Question**: A clear stuck dialog appears on screen and TTS prompts the user:
  > *"FlowPilot is stuck at Step 2: Could not find search bar. Would you like to take over manually or cancel?"*
- User chooses manual takeover or cancellation safely.

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
