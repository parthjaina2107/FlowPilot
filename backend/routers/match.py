"""
Match endpoints - match voice commands (text or audio) to stored flows
and extract parameters.
"""

from __future__ import annotations

from fastapi import APIRouter, File, HTTPException, UploadFile

from models.schemas import FlowGraph, MatchRequest, MatchResult
from services.gemini_service import extract_parameters
from services.sbert_service import get_best_match_with_candidates
from services.whisper_service import transcribe

router = APIRouter(prefix="/api/match", tags=["Match"])


async def _resolve_match(command: str) -> MatchResult:
    """Internal helper to match text, detect ambiguity (T13), and extract slots."""
    cmd_lower = command.lower().strip()

    # T14: Execution Reporting Query Interception
    if any(q in cmd_lower for q in ["did the last run succeed", "last run status", "status of last run", "did it succeed"]):
        return MatchResult(
            matched=False,
            transcribed_text=command,
            suggestion="Reporting query received: Please check the FlowPilot status banner or query ReplayService directly.",
        )

    # Find candidate flows via S-BERT + ChromaDB
    best, candidates = await get_best_match_with_candidates(command)
    if best is None:
        return MatchResult(
            matched=False,
            transcribed_text=command,
            suggestion=f"I haven't learned how to do '{command}' yet. Would you like to teach me?",
        )

    # Extract dynamic parameters
    flow_graph = FlowGraph(**best["flow_graph"])
    params = await extract_parameters(command, flow_graph.parameter_schema)
    params = {str(k): str(v) for k, v in params.items()}

    # Bonus 2: Cross-App & Similar UI Generalization (ASIG Runtime Adapter)
    cross_app_target = None
    app_alias_map = {
        "myntra": ("com.myntra.android", "Myntra", "Search"),
        "flipkart": ("com.flipkart.android", "Flipkart", "Search"),
        "meesho": ("com.meesho.supply", "Meesho", "Search"),
        "swiggy": ("in.swiggy.android", "Swiggy", "Search"),
    }
    for alias, (pkg, app_name, search_placeholder) in app_alias_map.items():
        if alias in cmd_lower:
            cross_app_target = (pkg, app_name, search_placeholder)
            break

    if cross_app_target and flow_graph.target_app_package != cross_app_target[0]:
        target_pkg, target_name, placeholder = cross_app_target
        is_ecom_transfer = "amazon" in flow_graph.target_app_package and target_name in ["Myntra", "Flipkart", "Meesho"]
        is_food_transfer = "zomato" in flow_graph.target_app_package and target_name in ["Swiggy"]

        if is_ecom_transfer or is_food_transfer:
            flow_graph.target_app_package = target_pkg
            flow_graph.flow_name = f"{flow_graph.flow_name} → {target_name} (Cross-App ASIG)"
            for step in flow_graph.steps:
                if step.action_type == "open_app":
                    step.selector["package"] = target_pkg
                    step.description = f"Launch {target_name}"
                elif step.step_index == 1 and "search" in step.description.lower():
                    step.selector = {"role": "edittext", "text_contains": placeholder}
                    step.description = f"Tap {target_name} search bar"

    # T13: Ambiguity Resolution (multi-candidate conflict, under-specified commands, or borderline confidence)
    is_ambiguous = False
    clarification_prompt = None
    ambiguity_options = None

    # Condition 1: Multiple matching flows with close confidence scores
    if len(candidates) > 1 and (candidates[0]["confidence"] - candidates[1]["confidence"] < 0.08):
        is_ambiguous = True
        ambiguity_options = [c["flow_name"] for c in candidates[:3]]
        clarification_prompt = f"Did you mean '{candidates[0]['flow_name']}' or '{candidates[1]['flow_name']}'?"

    # Condition 2: Borderline / moderate confidence (< 0.75 threshold)
    elif best["confidence"] < 0.75:
        is_ambiguous = True
        ambiguity_options = [f"Yes, run {best['flow_name']}", "No, cancel"]
        clarification_prompt = f"Found '{best['flow_name']}' with {int(best['confidence'] * 100)}% confidence. Confirm to proceed?"

    # Condition 3: Under-specified command where schema has required parameters but command is generic
    elif flow_graph.parameter_schema:
        content_words = [w for w in cmd_lower.split() if w not in {"a", "an", "the", "please", "can", "you", "to", "for", "on", "in", "from", "and"}]
        unmentioned_params = []
        for param_name, schema in flow_graph.parameter_schema.items():
            default_val = str(schema.get("default", "")).lower()
            val = str(params.get(param_name, "")).lower()
            if val and val == default_val and val not in cmd_lower:
                unmentioned_params.append(param_name)

        if len(content_words) <= 2 and unmentioned_params:
            is_ambiguous = True
            first_missing = unmentioned_params[0].replace('_', ' ')
            ambiguity_options = [f"Use default ({params.get(unmentioned_params[0])})", "Clarify parameter", "Record new flow"]
            clarification_prompt = f"Matched '{best['flow_name']}'. What {first_missing} would you like?"

    return MatchResult(
        matched=True,
        flow_id=best["flow_id"],
        flow_name=flow_graph.flow_name,
        confidence=best["confidence"],
        parameters=params,
        flow_graph=flow_graph,
        transcribed_text=command,
        is_ambiguous=is_ambiguous,
        clarification_prompt=clarification_prompt,
        ambiguity_options=ambiguity_options,
    )


@router.post("/text", response_model=MatchResult)
async def match_text_command(req: MatchRequest) -> MatchResult:
    """
    Match a text voice command to a stored flow.
    1. Find the best matching flow via Sentence-BERT + ChromaDB
    2. Detect ambiguity or reporting queries (T13, T14)
    3. Extract parameters from the command via Gemini
    4. Return the match result with the full FlowGraph
    """
    command = req.command.strip()
    if not command:
        raise HTTPException(status_code=400, detail="Command cannot be empty.")

    return await _resolve_match(command)


@router.post("/audio", response_model=MatchResult)
async def match_audio_command(audio: UploadFile = File(...)) -> MatchResult:
    """
    Match an audio voice command to a stored flow.
    1. Transcribe audio via Whisper
    2. Find the best matching flow via Sentence-BERT + ChromaDB
    3. Detect ambiguity or reporting queries (T13, T14)
    4. Extract parameters from the command via Gemini
    5. Return the match result
    """
    audio_bytes = await audio.read()
    if not audio_bytes:
        raise HTTPException(status_code=400, detail="Audio file is empty.")

    ext = "wav"
    if audio.filename and "." in audio.filename:
        ext = audio.filename.rsplit(".", 1)[-1]

    command = await transcribe(audio_bytes, file_extension=ext)
    if not command:
        return MatchResult(
            matched=False,
            transcribed_text="",
            suggestion="Could not transcribe audio. Please try again.",
        )

    print(f"  [AUDIO] Transcribed: '{command}'")
    return await _resolve_match(command)
