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
    Transcribe audio bytes to text using Whisper with Gemini 2.0 Flash Audio fallback.

    Args:
        audio_bytes: Raw audio data.
        file_extension: File extension (wav, mp3, m4a, etc.)

    Returns:
        Transcribed text string.
    """
    # 1. Try Whisper if available
    if _WHISPER_AVAILABLE:
        try:
            model = _get_model()
            with tempfile.NamedTemporaryFile(
                suffix=f".{file_extension}", delete=False
            ) as tmp:
                tmp.write(audio_bytes)
                tmp_path = tmp.name

            try:
                result = model.transcribe(tmp_path, language="en")
                text = (result.get("text") or "").strip()
                if text:
                    print(f"  [OK] Whisper transcribed: '{text}'")
                    return text
            finally:
                Path(tmp_path).unlink(missing_ok=True)
        except Exception as e:
            print(f"  [WARN] Whisper transcription failed: {e}. Falling back to Gemini 2.0 Flash Audio.")

    # 2. Resilient Cloud Fallback via Gemini 2.0 Flash Audio (Multimodal STT)
    try:
        from services.gemini_service import _get_client
        client = _get_client()
        if client:
            mime = "audio/wav"
            clean_ext = file_extension.lower().lstrip(".")
            if clean_ext in ["m4a", "mp4", "aac"]:
                mime = "audio/mp4"
            elif clean_ext == "mp3":
                mime = "audio/mp3"
            elif clean_ext == "ogg":
                mime = "audio/ogg"

            from google.genai import types
            part = types.Part.from_bytes(data=audio_bytes, mime_type=mime)
            prompt = "Transcribe the spoken command in this audio verbatim. Output ONLY the transcribed text string without any commentary, quotes, or markdown."
            response = await client.aio.models.generate_content(
                model="gemini-2.0-flash",
                contents=[part, prompt]
            )
            text = (response.text or "").strip()
            print(f"  [OK] Gemini 2.0 Flash Audio transcribed: '{text}'")
            return text
    except Exception as e:
        print(f"  [ERROR] Gemini audio transcription fallback failed: {e}")

    return ""
