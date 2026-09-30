"""
Gemini 2.0 Flash integration - compiles raw UI traces into generalised FlowGraphs.
Includes deterministic heuristic fallback compiler for offline resilience (as declared in AI_DISCLOSURE.md).
"""

from __future__ import annotations

import asyncio
import json
import os
import re
from typing import Any

from dotenv import load_dotenv
from google import genai
from google.genai import types

from models.schemas import FlowGraph, RecordingTrace

load_dotenv()

# ---------------------------------------------
# System prompt for the flow compiler
# ---------------------------------------------

FLOW_COMPILER_SYSTEM_PROMPT = """
You are FlowCompiler, an expert system that converts raw Android UI interaction
traces into generalised, reusable automation flows.

INPUT: A JSON array of UIAction objects captured via Android AccessibilityService
during a user demonstration. Each action has: action_type, package_name, element_id,
element_class, element_text, content_description, bounds, typed_text.

YOUR JOB:
1. CLEAN: Remove redundant, duplicate, or navigation-noise actions (e.g., repeated
   scrolls, accidental taps, system UI events).
2. ABSTRACT: Convert specific element references into SEMANTIC SELECTORS that will
   work even if the app updates. Use combinations of:
   - role (button, edittext, textview, imageview, checkbox, etc.)
   - text_contains / text_equals (visible text)
   - content_description_contains
   - class_name
   - resource_id_contains (only as fallback - IDs change across versions)
3. PARAMETERISE: Identify values the user typed or selected that are likely to change
   across invocations. Create named parameter slots for them. Examples:
   - A food item name -> slot "item_name"
   - A contact name -> slot "contact_name"
   - A quantity -> slot "quantity"
   - A message -> slot "message_text"
4. GENERATE TRIGGER PHRASES: Create 3-5 natural language phrases a user might say
   to invoke this flow. Include the original trigger phrase.
5. AUTH DETECTION: Mark any step that involves payment, OTP, password, or biometric
   as is_auth_pause=true so the system pauses for user confirmation.
6. TIMING: Estimate reasonable wait_after_ms for each step (app loading, network
   calls, animations). Default: 1000ms. After app launch: 3000ms. After search: 2000ms.

OUTPUT: Return a valid JSON object matching this schema exactly:
{
  "flow_name": "string",
  "description": "string",
  "trigger_phrases": ["string"],
  "target_app_package": "string",
  "parameter_schema": {
    "param_name": {"type": "string|integer|float", "description": "string", "default": "value"}
  },
  "steps": [
    {
      "step_index": 0,
      "action_type": "click|type|scroll|long_press|wait|open_app",
      "selector": {"role": "string", "text_contains": "string"},
      "parameter_slot": "param_name or null",
      "default_value": "string or null",
      "description": "Human readable description of this step",
      "wait_after_ms": 1000,
      "is_auth_pause": false
    }
  ]
}

RULES:
- Return ONLY valid JSON. No markdown, no explanation, no code fences.
- Every step MUST have a semantic selector. Never use raw coordinates alone.
- Keep flows LINEAR (no conditionals for now).
- Prefer text_contains over exact text_equals for resilience.
- Parameter slots should have clear, descriptive names in snake_case.
"""


# ---------------------------------------------
# Gemini client
# ---------------------------------------------

_client: genai.Client | None = None


def _get_client() -> genai.Client | None:
    """Lazy-initialise the Gemini client."""
    global _client
    if _client is None:
        api_key = os.getenv("GEMINI_API_KEY")
        if not api_key or api_key.strip() == "your_gemini_api_key_here":
            return None
        try:
            _client = genai.Client(api_key=api_key)
        except Exception as e:
            print(f"  [WARN] Could not initialise Gemini client: {e}")
            return None
    return _client


# ---------------------------------------------
# Heuristic Fallback Compiler (Offline & Resilient)
# ---------------------------------------------

def compile_flow_heuristic(trace: RecordingTrace) -> dict[str, Any]:
    """
    Deterministic rule-based compiler fallback for offline use, quota limits,
    or network failure (declared in AI_DISCLOSURE.md).
    """
    steps = []
    param_schema = {}
    step_idx = 0

    # Ensure app launch step exists
    has_open_app = any(a.action_type == "open_app" for a in trace.actions)
    if not has_open_app and trace.target_app_package:
        steps.append({
            "step_index": step_idx,
            "action_type": "open_app",
            "selector": {"role": "app", "package": trace.target_app_package},
            "parameter_slot": None,
            "default_value": None,
            "description": f"Launch {trace.target_app_package}",
            "wait_after_ms": 2500,
            "is_auth_pause": False
        })
        step_idx += 1

    for a in trace.actions:
        cls_lower = a.element_class.lower()
        role = "button" if "button" in cls_lower else ("edittext" if "edit" in cls_lower else "view")
        selector: dict[str, str] = {}

        if a.element_text and len(a.element_text.strip()) > 0:
            selector["text_contains"] = a.element_text.strip()
        if a.content_description and len(a.content_description.strip()) > 0:
            selector["content_description_contains"] = a.content_description.strip()
        if not selector and a.element_id:
            selector["resource_id_contains"] = a.element_id
        if not selector:
            selector["role"] = role

        # Auth & sensitive screen detection (T11)
        text_context = ((a.element_text or "") + " " + (a.content_description or "")).lower()
        is_auth = any(k in text_context for k in ["pay", "place order", "checkout", "otp", "password", "upi", "card"])

        param_slot = None
        default_val = None
        if a.action_type == "type" and a.typed_text:
            param_slot = "query" if "search" in text_context else "item_name"
            default_val = a.typed_text
            param_schema[param_slot] = {
                "type": "string",
                "description": f"Extracted parameter for {param_slot}",
                "default": default_val
            }

        desc = f"Tap '{a.element_text or a.content_description or role}'" if a.action_type == "click" else (
            f"Type '{default_val}'" if a.action_type == "type" else a.action_type.capitalize()
        )

        steps.append({
            "step_index": step_idx,
            "action_type": a.action_type,
            "selector": selector,
            "parameter_slot": param_slot,
            "default_value": default_val or a.typed_text,
            "description": desc,
            "wait_after_ms": 1500 if a.action_type == "type" else 1000,
            "is_auth_pause": is_auth
        })
        step_idx += 1

    return {
        "flow_name": trace.flow_name,
        "description": f"Automated flow for {trace.flow_name} in {trace.target_app_package}",
        "trigger_phrases": [
            trace.trigger_phrase,
            f"Run {trace.flow_name}",
            f"Open and {trace.flow_name}"
        ],
        "target_app_package": trace.target_app_package,
        "parameter_schema": param_schema,
        "steps": steps
    }


# ---------------------------------------------
# Flow compilation
# ---------------------------------------------

MAX_RETRIES = 2


async def compile_flow(trace: RecordingTrace) -> dict[str, Any]:
    """
    Send a raw RecordingTrace to Gemini 2.0 Flash and receive
    a generalised flow definition. Falls back gracefully to heuristic compiler.
    """
    client = _get_client()
    if client is None:
        print("  [INFO] GEMINI_API_KEY not configured. Using deterministic heuristic compiler fallback.")
        return compile_flow_heuristic(trace)

    # Build prompt
    actions_json = json.dumps(
        [action.model_dump() for action in trace.actions], indent=2
    )
    user_prompt = (
        f"Here is the raw interaction trace for a flow named '{trace.flow_name}' "
        f"recorded in {trace.target_app_package}. "
        f"The user's voice trigger was: '{trace.trigger_phrase}'.\n\n"
        f"Raw trace ({len(trace.actions)} actions):\n{actions_json}\n\n"
        f"Compile this into a generalised FlowGraph."
    )

    FALLBACK_MODELS = [
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-2.0-flash-lite",
    ]

    for model_name in FALLBACK_MODELS:
        for attempt in range(1, MAX_RETRIES + 1):
            try:
                response = client.models.generate_content(
                    model=model_name,
                    contents=user_prompt,
                    config=types.GenerateContentConfig(
                        system_instruction=FLOW_COMPILER_SYSTEM_PROMPT,
                        response_mime_type="application/json",
                        temperature=0.2,
                        max_output_tokens=4096,
                    ),
                )

                raw_text = response.text.strip()
                if raw_text.startswith("```"):
                    raw_text = raw_text.split("\n", 1)[1]
                if raw_text.endswith("```"):
                    raw_text = raw_text.rsplit("```", 1)[0]

                flow_dict = json.loads(raw_text)
                return flow_dict

            except json.JSONDecodeError:
                print(f"  [WARN] Attempt {attempt}/{MAX_RETRIES} ({model_name}): invalid JSON from Gemini")
            except Exception as e:
                print(f"  [WARN] Attempt {attempt}/{MAX_RETRIES} ({model_name}): Gemini API error: {e}")

    print("  [WARN] All Gemini models failed or unavailable. Falling back to heuristic compiler.")
    return compile_flow_heuristic(trace)


async def extract_parameters(command: str, parameter_schema: dict) -> dict[str, Any]:
    """
    Extract parameter values from a voice command using fast heuristics and async Gemini.
    """
    if not parameter_schema:
        return {}

    defaults = {
        name: schema.get("default", "")
        for name, schema in parameter_schema.items()
    }
    extracted = dict(defaults)

    # 1. Fast regex extraction (e.g. integer quantities like "2 pizzas" -> quantity = 2)
    for name, schema in parameter_schema.items():
        if schema.get("type") == "integer":
            match = re.search(r"\b(\d+)\b", command)
            if match:
                try:
                    extracted[name] = int(match.group(1))
                except ValueError:
                    pass

    # 2. Async Gemini extraction
    client = _get_client()
    if client is not None:
        try:
            prompt = (
                f"Given this voice command: '{command}'\n"
                f"And these flow parameters: {json.dumps(parameter_schema, indent=2)}\n\n"
                f"Extract the parameter values from the command. "
                f"Use default values for any parameters not mentioned in the command.\n"
                f"Return ONLY a JSON object mapping parameter names to their values."
            )

            models_to_try = [
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite",
                "gemini-1.5-flash",
            ]

            raw_text = None
            for m in models_to_try:
                try:
                    response = await asyncio.wait_for(
                        client.aio.models.generate_content(
                            model=m,
                            contents=prompt,
                            config=types.GenerateContentConfig(
                                response_mime_type="application/json",
                                temperature=0.1,
                                max_output_tokens=256,
                            ),
                        ),
                        timeout=2.0,
                    )
                    raw_text = response.text.strip()
                    if raw_text:
                        break
                except Exception:
                    continue

            if raw_text:
                if raw_text.startswith("```"):
                    raw_text = raw_text.split("\n", 1)[1]
                if raw_text.endswith("```"):
                    raw_text = raw_text.rsplit("```", 1)[0]

                gemini_extracted = json.loads(raw_text)
                for k, v in gemini_extracted.items():
                    if k in parameter_schema:
                        extracted[k] = v

        except Exception as e:
            print(f"  [WARN] Parameter extraction fallback: {e}")

    return {k: str(v) for k, v in extracted.items()}
