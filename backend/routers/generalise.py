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
    # --- Validation ---
    if len(trace.actions) < 2:
        raise HTTPException(
            status_code=400,
            detail="Recording trace must have at least 2 actions.",
        )

    # --- Compile via Gemini ---
    try:
        print(f"  [COMPILE] Compiling flow '{trace.flow_name}' ({len(trace.actions)} actions)...")
        raw_flow = await compile_flow(trace)
        print("  [OK] Gemini compiled successfully")
    except Exception as e:
        raise HTTPException(
            status_code=502,
            detail=f"Gemini compilation failed: {str(e)}",
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
