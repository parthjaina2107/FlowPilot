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

    # T13: Ambiguity Resolution
    # If the user speaks a broad/ambiguous command like "Order pizza", or if two flows match closely,
    # prompt the user for confirmation rather than guessing silently.
    is_ambiguous = False
    clarification_prompt = None
    ambiguity_options = None

    words = [w for w in cmd_lower.split() if w not in {"a", "an", "the", "please", "can", "you", "to", "for"}]
    if len(candidates) > 1 and (candidates[0]["confidence"] - candidates[1]["confidence"] < 0.08):
        is_ambiguous = True
        ambiguity_options = [c["flow_name"] for c in candidates[:3]]
        clarification_prompt = f"Did you mean '{candidates[0]['flow_name']}' or '{candidates[1]['flow_name']}'?"
    elif len(words) <= 2 and any(k in cmd_lower for k in ["pizza", "food", "order", "search", "play"]):
        is_ambiguous = True
        ambiguity_options = [candidates[0]["flow_name"], "Record new flow"]
        clarification_prompt = f"Found '{candidates[0]['flow_name']}'. Confirm to proceed or record a new flow."

    return MatchResult(
        matched=True,
        flow_id=best["flow_id"],
        flow_name=best["flow_name"],
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
