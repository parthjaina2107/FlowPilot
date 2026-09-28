"""
Flow CRUD endpoints - list, get, delete saved flows.
"""

from __future__ import annotations

import json

from fastapi import APIRouter, HTTPException

from models.database import delete_flow, delete_trigger_phrases, get_flow, list_flows
from models.schemas import FlowGraph

router = APIRouter(prefix="/api/flows", tags=["Flows"])


@router.get("/")
async def get_all_flows() -> list[dict]:
    """List all saved flows (summary info only)."""
    return list_flows()


@router.get("/{flow_id}")
async def get_flow_by_id(flow_id: str) -> FlowGraph:
    """Retrieve the full FlowGraph for a specific flow."""
    flow_data = get_flow(flow_id)
    if flow_data is None:
        raise HTTPException(status_code=404, detail=f"Flow '{flow_id}' not found")
    return FlowGraph(**flow_data)


@router.delete("/{flow_id}")
async def delete_flow_by_id(flow_id: str) -> dict:
    """Delete a flow and its trigger phrases."""
    deleted = delete_flow(flow_id)
    if not deleted:
        raise HTTPException(status_code=404, detail=f"Flow '{flow_id}' not found")
    # Also remove from ChromaDB
    delete_trigger_phrases(flow_id)
    return {"deleted": True, "flow_id": flow_id}
