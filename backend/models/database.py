"""
Database layer - SQLite for flow storage, ChromaDB for vector search.
"""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

import chromadb

# ---------------------------------------------
# Paths
# ---------------------------------------------

_DB_DIR = Path(__file__).resolve().parent.parent / "data"
_SQLITE_PATH = _DB_DIR / "flowpilot.db"
_CHROMA_PATH = _DB_DIR / "chromadb"

# ---------------------------------------------
# SQLite
# ---------------------------------------------

_conn: sqlite3.Connection | None = None


def _get_conn() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        _DB_DIR.mkdir(parents=True, exist_ok=True)
        _conn = sqlite3.connect(str(_SQLITE_PATH), check_same_thread=False)
        _conn.row_factory = sqlite3.Row
    return _conn


def init_db() -> None:
    """Create tables if they don't exist."""
    conn = _get_conn()
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS flows (
            flow_id          TEXT PRIMARY KEY,
            flow_name        TEXT NOT NULL,
            description      TEXT NOT NULL,
            target_app_package TEXT NOT NULL,
            flow_json        TEXT NOT NULL,
            created_at       TEXT NOT NULL
        )
        """
    )
    conn.commit()
    print(f"  [DB] SQLite database at {_SQLITE_PATH}")
    print(f"  [DB] ChromaDB at {_CHROMA_PATH}")


def save_flow(flow_id: str, flow_name: str, description: str,
              target_app_package: str, flow_json: str, created_at: str) -> None:
    """Insert or replace a flow in SQLite."""
    conn = _get_conn()
    conn.execute(
        """
        INSERT OR REPLACE INTO flows
            (flow_id, flow_name, description, target_app_package, flow_json, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (flow_id, flow_name, description, target_app_package, flow_json, created_at),
    )
    conn.commit()


def get_flow(flow_id: str) -> dict | None:
    """Retrieve a flow by ID. Returns the full FlowGraph dict or None."""
    conn = _get_conn()
    row = conn.execute(
        "SELECT flow_json FROM flows WHERE flow_id = ?", (flow_id,)
    ).fetchone()
    if row is None:
        return None
    return json.loads(row["flow_json"])


def list_flows() -> list[dict]:
    """Return summary info for all flows."""
    conn = _get_conn()
    rows = conn.execute(
        "SELECT flow_id, flow_name, description, target_app_package, created_at FROM flows ORDER BY created_at DESC"
    ).fetchall()
    return [dict(r) for r in rows]


def delete_flow(flow_id: str) -> bool:
    """Delete a flow. Returns True if a row was deleted."""
    conn = _get_conn()
    cursor = conn.execute("DELETE FROM flows WHERE flow_id = ?", (flow_id,))
    conn.commit()
    return cursor.rowcount > 0


# ---------------------------------------------
# ChromaDB
# ---------------------------------------------

_chroma_client: chromadb.PersistentClient | None = None
_trigger_collection: chromadb.Collection | None = None


def _get_chroma_collection() -> chromadb.Collection:
    """Get or create the 'flow_triggers' collection."""
    global _chroma_client, _trigger_collection
    if _trigger_collection is None:
        _DB_DIR.mkdir(parents=True, exist_ok=True)
        _chroma_client = chromadb.PersistentClient(path=str(_CHROMA_PATH))
        _trigger_collection = _chroma_client.get_or_create_collection(
            name="flow_triggers",
            metadata={"hnsw:space": "cosine"},
        )
    return _trigger_collection


def index_trigger_phrases(flow_id: str, flow_name: str, phrases: list[str]) -> None:
    """Add trigger phrases to ChromaDB for vector search."""
    collection = _get_chroma_collection()
    ids = [f"{flow_id}__{i}" for i in range(len(phrases))]
    metadatas = [{"flow_id": flow_id, "flow_name": flow_name}] * len(phrases)
    collection.upsert(ids=ids, documents=phrases, metadatas=metadatas)


def query_triggers(command: str, top_k: int = 5) -> list[dict]:
    """
    Query ChromaDB with a voice command text.
    Returns list of {"flow_id", "flow_name", "phrase", "distance"}.
    """
    collection = _get_chroma_collection()
    count = collection.count()
    if count == 0:
        return []
    actual_k = min(top_k, count)
    results = collection.query(query_texts=[command], n_results=actual_k)
    matches = []
    for i in range(len(results["ids"][0])):
        matches.append(
            {
                "flow_id": results["metadatas"][0][i]["flow_id"],
                "flow_name": results["metadatas"][0][i]["flow_name"],
                "phrase": results["documents"][0][i],
                "distance": results["distances"][0][i],
            }
        )
    return matches


def delete_trigger_phrases(flow_id: str) -> None:
    """Remove all trigger phrases for a given flow from ChromaDB."""
    collection = _get_chroma_collection()
    # ChromaDB doesn't support direct prefix deletion, so query first
    results = collection.get(where={"flow_id": flow_id})
    if results["ids"]:
        collection.delete(ids=results["ids"])
