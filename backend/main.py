"""
FlowPilot Backend - FastAPI Application
Learn-once, replay-anywhere voice automation for Android.

This is the main entry point for the FlowPilot backend server.
It provides APIs for:
  - Flow compilation (raw trace -> generalised FlowGraph via Gemini)
  - Voice command matching (Sentence-BERT + ChromaDB)
  - Flow CRUD operations
  - Audio transcription (Whisper)
"""

import os
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from models.database import init_db
from routers import flows, generalise, match

load_dotenv()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown logic."""
    # --- Startup ---
    print("[INFO] FlowPilot Backend starting...")
    init_db()
    print("[OK] Database initialised")
    yield
    # --- Shutdown ---
    print("[INFO] FlowPilot Backend shutting down...")


app = FastAPI(
    title="FlowPilot Backend",
    description="Learn-once, replay-anywhere voice automation API",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS - allow all origins during development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(flows.router)
app.include_router(generalise.router)
app.include_router(match.router)


@app.get("/", tags=["Health"])
async def health_check():
    """Health check endpoint."""
    return {
        "status": "ok",
        "service": "flowpilot-backend",
        "version": "1.0.0",
    }
