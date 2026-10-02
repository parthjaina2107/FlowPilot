"""
Generalise endpoint - receives a raw RecordingTrace from the Android app
and returns a compiled, generalised FlowGraph (via Gemini 2.0 Flash).
"""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from models.database import index_trigger_phrases, save_flow
from models.schemas import FlowGraph, FlowStep, RecordingTrace
from services.gemini_service import compile_flow

router = APIRouter(prefix="/api/generalise", tags=["Generalise"])


@router.post("/compile", response_model=FlowGraph)
async def compile_trace(trace: RecordingTrace) -> FlowGraph:
    """
    Compile a raw RecordingTrace into a generalised FlowGraph.

    Steps:
    1. Validate the trace has enough actions
    2. Send to Gemini 2.0 Flash for compilation
    3. Generate IDs and timestamps
    4. Save to SQLite + index triggers in ChromaDB
    5. Return the FlowGraph
    """
    # --- Phase 9: Logging requirement ---
    first_action_str = f"{trace.actions[0].action_type} on '{trace.actions[0].element_text or trace.actions[0].content_description or trace.actions[0].element_class}'" if trace.actions else "None"
    last_action_str = f"{trace.actions[-1].action_type} on '{trace.actions[-1].element_text or trace.actions[-1].content_description or trace.actions[-1].element_class}'" if trace.actions else "None"
    print(
        f"  [COMPILE_TRACE]\n"
        f"    flow_name: '{trace.flow_name}'\n"
        f"    trigger: '{trace.trigger_phrase}'\n"
        f"    target_package: '{trace.target_app_package}'\n"
        f"    actions_count: {len(trace.actions)}\n"
        f"    first_action: {first_action_str}\n"
        f"    last_action: {last_action_str}"
    )

    # --- Validation ---
    if len(trace.actions) < 2:
        print(f"  [COMPILE_400] Validation failed: actions.count={len(trace.actions)} < 2")
        raise HTTPException(
            status_code=400,
            detail="Recording trace must have at least 2 actions. Please perform at least two actions and try again.",
        )

    # --- Compile via Gemini (with heuristic fallback) ---
    try:
        raw_flow = await compile_flow(trace)
        print("  [OK] Flow compiled successfully")
    except Exception as e:
        import traceback
        traceback.print_exc()
        print(f"  [COMPILE_ERROR] Compilation failed: {e}. Trying heuristic fallback...")
        try:
            from services.gemini_service import compile_flow_heuristic
            raw_flow = compile_flow_heuristic(trace)
            print("  [OK] Heuristic fallback compilation succeeded")
        except Exception as e2:
            print(f"  [COMPILE_502] Both Gemini and heuristic compilation failed: {e2}")
            raise HTTPException(
                status_code=502,
                detail=f"Flow compilation service is unavailable: {str(e)}",
            )

    # --- Build the FlowGraph ---
    flow_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc).isoformat()

    # Ensure steps have proper indices
    steps = raw_flow.get("steps", [])
    for i, step in enumerate(steps):
        step["step_index"] = i

    flow_graph = FlowGraph(
        flow_id=flow_id,
        flow_name=raw_flow.get("flow_name", trace.flow_name),
        description=raw_flow.get("description", ""),
        trigger_phrases=raw_flow.get("trigger_phrases", [trace.trigger_phrase]),
        target_app_package=raw_flow.get("target_app_package", trace.target_app_package),
        parameter_schema=raw_flow.get("parameter_schema", {}),
        steps=[FlowStep(**s) for s in steps],
        created_at=now,
        version=1,
    )

    # --- Persist ---
    flow_json = flow_graph.model_dump_json()
    save_flow(
        flow_id=flow_graph.flow_id,
        flow_name=flow_graph.flow_name,
        description=flow_graph.description,
        target_app_package=flow_graph.target_app_package,
        flow_json=flow_json,
        created_at=flow_graph.created_at,
    )

    # Index trigger phrases for vector search
    index_trigger_phrases(
        flow_id=flow_graph.flow_id,
        flow_name=flow_graph.flow_name,
        phrases=flow_graph.trigger_phrases,
    )

    print(
        f"  [SAVED] Saved flow '{flow_graph.flow_name}' "
        f"({len(flow_graph.steps)} steps, "
        f"{len(flow_graph.trigger_phrases)} triggers, "
        f"{len(flow_graph.parameter_schema)} params)"
    )

    return flow_graph
