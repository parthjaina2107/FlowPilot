"""
Sentence-BERT matching service - finds the best flow for a voice command
using ChromaDB vector search.
"""

from __future__ import annotations

from models.database import get_flow, query_triggers

# ---------------------------------------------
# Constants
# ---------------------------------------------

# ChromaDB uses cosine distance (0 = identical, 2 = opposite).
# A distance of 0.45 ~ cosine similarity of 0.55.
MAX_DISTANCE_THRESHOLD = 0.45


async def find_matching_flow(command: str, top_k: int = 3) -> list[dict]:
    """
    Match a voice command to stored flows via ChromaDB.

    Returns a list of matches sorted by confidence (best first):
      [{"flow_id": "...", "flow_name": "...", "confidence": 0.87, "phrase": "..."}]
    """
    raw_matches = query_triggers(command, top_k=top_k)

    results: list[dict] = []
    seen_flow_ids: set[str] = set()

    for match in raw_matches:
        fid = match["flow_id"]
        distance = match["distance"]

        # Skip duplicates (same flow matched via different phrases)
        if fid in seen_flow_ids:
            continue
        seen_flow_ids.add(fid)

        # Skip low-confidence matches
        if distance > MAX_DISTANCE_THRESHOLD:
            continue

        # Convert cosine distance -> similarity score (0-1)
        confidence = round(1.0 - distance, 4)

        results.append(
            {
                "flow_id": fid,
                "flow_name": match["flow_name"],
                "confidence": confidence,
                "matched_phrase": match["phrase"],
            }
        )

    # Sort by confidence descending
    results.sort(key=lambda x: x["confidence"], reverse=True)
    return results


async def get_best_match(command: str) -> dict | None:
    """
    Find the single best matching flow for a command.
    Returns None if no match exceeds the confidence threshold.
    """
    matches = await find_matching_flow(command, top_k=3)
    if not matches:
        return None

    best = matches[0]
    # Load the full flow graph
    flow_data = get_flow(best["flow_id"])
    if flow_data is None:
        return None

    best["flow_graph"] = flow_data
    return best
