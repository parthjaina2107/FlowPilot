"""
Gemini 2.0 Flash integration - compiles raw UI traces into generalised FlowGraphs.
"""

from __future__ import annotations

import json
import os
from typing import Any

from google import genai
from google.genai import types

from models.schemas import FlowGraph, RecordingTrace

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


def _get_client() -> genai.Client:
    """Lazy-initialise the Gemini client."""
    global _client
    if _client is None:
        api_key = os.getenv("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError(
                "GEMINI_API_KEY not set. Add it to backend/.env"
            )
        _client = genai.Client(api_key=api_key)
    return _client


# ---------------------------------------------
# Flow compilation
# ---------------------------------------------

MAX_RETRIES = 3


async def compile_flow(trace: RecordingTrace) -> dict[str, Any]:
    """
    Send a raw RecordingTrace to Gemini 2.0 Flash and receive
    a generalised flow definition (dict ready to become a FlowGraph).

    Returns the raw dict (caller adds flow_id, created_at, version).
    """
    client = _get_client()

    # Build the user prompt
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

    last_error: Exception | None = None

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            response = client.models.generate_content(
                model="gemini-2.0-flash",
                contents=user_prompt,
                config=types.GenerateContentConfig(
                    system_instruction=FLOW_COMPILER_SYSTEM_PROMPT,
                    response_mime_type="application/json",
                    temperature=0.2,
                    max_output_tokens=4096,
                ),
            )

            raw_text = response.text.strip()

            # Strip markdown fences if Gemini wraps them anyway
            if raw_text.startswith("```"):
                raw_text = raw_text.split("\n", 1)[1]
            if raw_text.endswith("```"):
                raw_text = raw_text.rsplit("```", 1)[0]

            flow_dict = json.loads(raw_text)
            return flow_dict

        except json.JSONDecodeError as e:
            last_error = e
            print(
                f"  [WARN] Attempt {attempt}/{MAX_RETRIES}: "
                f"Gemini returned invalid JSON - retrying..."
            )
            # Append correction to the prompt
            user_prompt += (
                "\n\nYour previous response was not valid JSON. "
                "Return ONLY a JSON object, no other text."
            )
        except Exception as e:
            last_error = e
            print(
                f"  [WARN] Attempt {attempt}/{MAX_RETRIES}: "
                f"Gemini API error: {e}"
            )

    raise RuntimeError(
        f"Failed to compile flow after {MAX_RETRIES} attempts. "
        f"Last error: {last_error}"
    )


async def extract_parameters(command: str, parameter_schema: dict) -> dict[str, Any]:
    """
    Use Gemini to extract parameter values from a voice command.

    Args:
        command: The user's voice command, e.g. "Order 2 naans from Zomato"
        parameter_schema: The flow's parameter schema with types and defaults.

    Returns:
        Dict of param_name -> extracted value.
    """
    if not parameter_schema:
        return {}

    defaults = {
        name: schema.get("default", "")
        for name, schema in parameter_schema.items()
    }

    try:
        client = _get_client()

        prompt = (
            f"Given this voice command: '{command}'\n"
            f"And these flow parameters: {json.dumps(parameter_schema, indent=2)}\n\n"
            f"Extract the parameter values from the command. "
            f"Use default values for any parameters not mentioned in the command.\n"
            f"Return ONLY a JSON object mapping parameter names to their values."
        )

        response = client.models.generate_content(
            model="gemini-2.0-flash",
            contents=prompt,
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                temperature=0.1,
                max_output_tokens=512,
            ),
        )

        raw_text = response.text.strip()
        if raw_text.startswith("```"):
            raw_text = raw_text.split("\n", 1)[1]
        if raw_text.endswith("```"):
            raw_text = raw_text.rsplit("```", 1)[0]

        extracted = json.loads(raw_text)
        # Merge with defaults for any missing params
        for k, v in defaults.items():
            if k not in extracted:
                extracted[k] = v
        return extracted

    except Exception as e:
        print(f"  [WARN] Parameter extraction fallback to defaults: {e}")
        return defaults
