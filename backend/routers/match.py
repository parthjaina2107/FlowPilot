"""
Match endpoints - match voice commands (text or audio) to stored flows
and extract parameters.
"""

from __future__ import annotations

from fastapi import APIRouter, File, HTTPException, UploadFile

from models.schemas import FlowGraph, MatchRequest, MatchResult
from services.gemini_service import extract_parameters
from services.sbert_service import get_best_match
from services.whisper_service import transcribe

router = APIRouter(prefix="/api/match", tags=["Match"])


@router.post("/text", response_model=MatchResult)
async def match_text_command(req: MatchRequest) -> MatchResult:
    """
    Match a text voice command to a stored flow.

    1. Find the best matching flow via Sentence-BERT + ChromaDB
    2. Extract parameters from the command via Gemini
    3. Return the match result with the full FlowGraph
    """
    command = req.command.strip()
    if not command:
        raise HTTPException(status_code=400, detail="Command cannot be empty.")

    # --- Find matching flow ---
    best = await get_best_match(command)
    if best is None:
        return MatchResult(
            matched=False,
            suggestion="No matching flow found. Try recording a new flow first.",
        )

    # --- Extract parameters ---
    flow_graph = FlowGraph(**best["flow_graph"])
    params = await extract_parameters(command, flow_graph.parameter_schema)

    return MatchResult(
        matched=True,
        flow_id=best["flow_id"],
        flow_name=best["flow_name"],
        confidence=best["confidence"],
        parameters=params,
        flow_graph=flow_graph,
        transcribed_text=command,
    )


@router.post("/audio", response_model=MatchResult)
async def match_audio_command(audio: UploadFile = File(...)) -> MatchResult:
    """
    Match an audio voice command to a stored flow.

    1. Transcribe audio via Whisper
    2. Find the best matching flow via Sentence-BERT + ChromaDB
    3. Extract parameters from the command via Gemini
    4. Return the match result
    """
    # --- Read and transcribe audio ---
    audio_bytes = await audio.read()
    if not audio_bytes:
        raise HTTPException(status_code=400, detail="Audio file is empty.")

    # Determine file extension from filename
    ext = "wav"
    if audio.filename:
        ext = audio.filename.rsplit(".", 1)[-1] if "." in audio.filename else "wav"

    command = await transcribe(audio_bytes, file_extension=ext)
    if not command:
        return MatchResult(
            matched=False,
            transcribed_text="",
            suggestion="Could not transcribe audio. Please try again.",
        )

    print(f"  [AUDIO] Transcribed: '{command}'")

    # --- Find matching flow ---
    best = await get_best_match(command)
    if best is None:
        return MatchResult(
            matched=False,
            transcribed_text=command,
            suggestion=f"Heard: '{command}' - but no matching flow found.",
        )

    # --- Extract parameters ---
    flow_graph = FlowGraph(**best["flow_graph"])
    params = await extract_parameters(command, flow_graph.parameter_schema)

    return MatchResult(
        matched=True,
        flow_id=best["flow_id"],
        flow_name=best["flow_name"],
        confidence=best["confidence"],
        parameters=params,
        flow_graph=flow_graph,
        transcribed_text=command,
    )
