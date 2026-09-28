"""
Whisper STT service - transcribes audio to text.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

try:
    import whisper
    _WHISPER_AVAILABLE = True
except ImportError:
    _WHISPER_AVAILABLE = False
    print("  [WARN] Whisper not installed. Audio transcription disabled.")
    print("         Install with: pip install openai-whisper")

# ---------------------------------------------
# Model loading (cached in memory)
# ---------------------------------------------

_model = None


def _get_model():
    """Load Whisper 'base' model once and cache it."""
    global _model
    if not _WHISPER_AVAILABLE:
        raise RuntimeError(
            "Whisper is not installed. Install with: pip install openai-whisper"
        )
    if _model is None:
        print("  [INFO] Loading Whisper 'base' model...")
        _model = whisper.load_model("base")
        print("  [OK] Whisper model loaded")
    return _model


# ---------------------------------------------
# Transcription
# ---------------------------------------------


async def transcribe(audio_bytes: bytes, file_extension: str = "wav") -> str:
    """
    Transcribe audio bytes to text using Whisper.

    Args:
        audio_bytes: Raw audio data.
        file_extension: File extension (wav, mp3, m4a, etc.)

    Returns:
        Transcribed text string.
    """
    model = _get_model()

    # Write to a temp file (Whisper needs a file path)
    with tempfile.NamedTemporaryFile(
        suffix=f".{file_extension}", delete=False
    ) as tmp:
        tmp.write(audio_bytes)
        tmp_path = tmp.name

    try:
        result = model.transcribe(tmp_path, language="en")
        return result["text"].strip()
    finally:
        # Clean up temp file
        Path(tmp_path).unlink(missing_ok=True)
