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
        "SELECT flow_id, flow_name, description, target_app_package, flow_json, created_at FROM flows ORDER BY created_at DESC"
    ).fetchall()
    result = []
    for r in rows:
        d = {
            "flow_id": r["flow_id"],
            "flow_name": r["flow_name"],
            "description": r["description"],
            "target_app_package": r["target_app_package"],
            "created_at": r["created_at"],
        }
        try:
            fj = json.loads(r["flow_json"])
            d["trigger_phrases"] = ", ".join(fj.get("trigger_phrases", []))
        except Exception:
            d["trigger_phrases"] = ""
        result.append(d)
    return result


def delete_flow(flow_id: str) -> bool:
    """Delete a flow. Returns True if a row was deleted."""
    conn = _get_conn()
    cursor = conn.execute("DELETE FROM flows WHERE flow_id = ?", (flow_id,))
    conn.commit()
    return cursor.rowcount > 0


# ---------------------------------------------
# ---------------------------------------------
# ChromaDB (with SQLite fallback if orjson/chromadb DLLs blocked by Windows AppLocker)
# ---------------------------------------------

_CHROMA_AVAILABLE = False
_chroma_client = None
_trigger_collection = None

try:
    import chromadb
    _CHROMA_AVAILABLE = True
except (ImportError, OSError, Exception) as e:
    _CHROMA_AVAILABLE = False
    print(f"  [INFO] ChromaDB unavailable ({e}). Using SQLite trigger matching engine.")


def _init_trigger_table(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS trigger_phrases (
            id TEXT PRIMARY KEY,
            flow_id TEXT NOT NULL,
            flow_name TEXT NOT NULL,
            phrase TEXT NOT NULL
        )
        """
    )
    conn.commit()


def _get_chroma_collection():
    """Get or create the 'flow_triggers' collection if ChromaDB is available."""
    global _chroma_client, _trigger_collection
    if not _CHROMA_AVAILABLE:
        return None
    if _trigger_collection is None:
        try:
            _DB_DIR.mkdir(parents=True, exist_ok=True)
            _chroma_client = chromadb.PersistentClient(path=str(_CHROMA_PATH))
            _trigger_collection = _chroma_client.get_or_create_collection(
                name="flow_triggers",
                metadata={"hnsw:space": "cosine"},
            )
        except Exception as e:
            print(f"  [WARN] Failed to initialize ChromaDB collection: {e}")
            _trigger_collection = None
    return _trigger_collection


def index_trigger_phrases(flow_id: str, flow_name: str, phrases: list[str]) -> None:
    """Add trigger phrases to ChromaDB (and SQLite) for search."""
    conn = _get_conn()
    _init_trigger_table(conn)

    # 1. Always store in SQLite
    for i, phrase in enumerate(phrases):
        pid = f"{flow_id}__{i}"
        conn.execute(
            """
            INSERT OR REPLACE INTO trigger_phrases (id, flow_id, flow_name, phrase)
            VALUES (?, ?, ?, ?)
            """,
            (pid, flow_id, flow_name, phrase),
        )
    conn.commit()

    # 2. Store in ChromaDB if available
    collection = _get_chroma_collection()
    if collection is not None:
        try:
            ids = [f"{flow_id}__{i}" for i in range(len(phrases))]
            metadatas = [{"flow_id": flow_id, "flow_name": flow_name}] * len(phrases)
            collection.upsert(ids=ids, documents=phrases, metadatas=metadatas)
        except Exception as e:
            print(f"  [WARN] ChromaDB upsert failed: {e}")


def query_triggers(command: str, top_k: int = 5) -> list[dict]:
    """
    Query triggers with a voice command text.
    Uses ChromaDB if operational, otherwise falls back to robust token+sequence similarity over SQLite.
    Returns list of {"flow_id", "flow_name", "phrase", "distance"}.
    """
    collection = _get_chroma_collection()
    if collection is not None:
        try:
            count = collection.count()
            if count > 0:
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
        except Exception as e:
            print(f"  [WARN] ChromaDB query failed ({e}); using SQLite fallback.")

    # SQLite Fallback Matching Engine
    import difflib
    conn = _get_conn()
    _init_trigger_table(conn)
    rows = conn.execute("SELECT flow_id, flow_name, phrase FROM trigger_phrases").fetchall()
    if not rows:
        return []

    cmd_clean = command.lower().strip()
    cmd_words = set(cmd_clean.split())

    scored = []
    for r in rows:
        phrase_clean = r["phrase"].lower().strip()
        phrase_words = set(phrase_clean.split())

        # Exact match bonus
        if cmd_clean == phrase_clean:
            score = 1.0
        elif phrase_clean in cmd_clean or cmd_clean in phrase_clean:
            score = 0.92
        else:
            # Token Jaccard overlap
            intersection = len(cmd_words & phrase_words)
            union = len(cmd_words | phrase_words)
            jaccard = (intersection / union) if union > 0 else 0.0

            # Sequence similarity
            seq_ratio = difflib.SequenceMatcher(None, cmd_clean, phrase_clean).ratio()
            score = (0.55 * seq_ratio) + (0.45 * jaccard)

        # Distance is (1.0 - score) to match cosine distance semantics
        distance = round(max(0.0, 1.0 - score), 4)
        scored.append({
            "flow_id": r["flow_id"],
            "flow_name": r["flow_name"],
            "phrase": r["phrase"],
            "distance": distance
        })

    scored.sort(key=lambda x: x["distance"])
    return scored[:top_k]


def delete_trigger_phrases(flow_id: str) -> None:
    """Remove all trigger phrases for a given flow."""
    conn = _get_conn()
    conn.execute("DELETE FROM trigger_phrases WHERE flow_id = ?", (flow_id,))
    conn.commit()

    collection = _get_chroma_collection()
    if collection is not None:
        try:
            results = collection.get(where={"flow_id": flow_id})
            if results["ids"]:
                collection.delete(ids=results["ids"])
        except Exception as e:
            print(f"  [WARN] ChromaDB delete failed: {e}")
