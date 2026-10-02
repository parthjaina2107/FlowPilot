"""
Speech-to-Text (STT) service - transcribes audio to text.
Multi-engine resilience:
1. Google Web Speech API (via SpeechRecognition - fast, zero GPU/PyTorch dependencies)
2. OpenAI Whisper (local offline model with direct in-memory NumPy float32 decoding - ZERO ffmpeg dependency!)
3. Google Gemini 2.0 Flash (Cloud multimodal STT if GEMINI_API_KEY is available)
"""

from __future__ import annotations

import io
import math
import os
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

# Safe Whisper module cache (lazy-loaded only on demand)
_model = None
_whisper_import_attempted = False
_whisper_module = None

# Safe SpeechRecognition import
_SR_AVAILABLE = False
try:
    import speech_recognition as sr
    _SR_AVAILABLE = True
except ImportError:
    _SR_AVAILABLE = False


@dataclass
class AudioDiagnostic:
    duration_s: float
    sample_rate: int
    channels: int
    bytes_count: int
    sample_count: int
    rms_energy: float
    is_silent: bool
    is_too_short: bool
    format_name: str
    peak_amplitude: float = 0.0
    crest_factor: float = 0.0
    error_code: str | None = None
    error_message: str | None = None


def parse_and_diagnose_audio(audio_bytes: bytes, file_extension: str = "wav") -> tuple[AudioDiagnostic, np.ndarray | None]:
    """
    Parse WAV audio bytes, extract metrics (duration, sample rate, channels, RMS energy, Peak, Crest Factor),
    and return diagnostic information plus a 16kHz float32 NumPy array for Whisper.
    """
    ext = file_extension.lower().lstrip(".")
    bytes_count = len(audio_bytes)

    if bytes_count < 44:
        diag = AudioDiagnostic(
            duration_s=0.0,
            sample_rate=0,
            channels=0,
            bytes_count=bytes_count,
            sample_count=0,
            rms_energy=0.0,
            is_silent=True,
            is_too_short=True,
            format_name=ext,
            error_code="MIC_NO_AUDIO",
            error_message="Microphone audio is empty or missing audio header (bytes < 44)."
        )
        return diag, None

    try:
        with wave.open(io.BytesIO(audio_bytes), "rb") as w:
            channels = w.getnchannels()
            width = w.getsampwidth()
            sample_rate = w.getframerate()
            n_frames = w.getnframes()
            raw_frames = w.readframes(n_frames)

        duration_s = n_frames / float(sample_rate) if sample_rate > 0 else 0.0

        if width == 2:
            samples = np.frombuffer(raw_frames, dtype=np.int16).astype(np.float32)
        elif width == 1:
            samples = (np.frombuffer(raw_frames, dtype=np.uint8).astype(np.float32) - 128.0) * 256.0
        elif width == 4:
            samples = np.frombuffer(raw_frames, dtype=np.int32).astype(np.float32) / 65536.0
        else:
            samples = np.frombuffer(raw_frames, dtype=np.int16).astype(np.float32)

        sample_count = len(samples)
        if sample_count > 0:
            rms = float(np.sqrt(np.mean(samples ** 2)))
            peak = float(np.max(np.abs(samples)))
            crest_factor = float(peak / (rms + 1e-6))
        else:
            rms = 0.0
            peak = 0.0
            crest_factor = 0.0

        # Channel downmix to mono if stereo
        if channels > 1 and sample_count > 0:
            samples = samples.reshape(-1, channels).mean(axis=1)

        # Resample to 16kHz float32 for Whisper if needed
        if sample_rate != 16000 and sample_rate > 0 and len(samples) > 0:
            target_len = int(len(samples) * 16000 / sample_rate)
            samples_resampled = np.interp(
                np.linspace(0, len(samples), target_len, endpoint=False),
                np.arange(len(samples)),
                samples
            )
            whisper_samples = (samples_resampled / 32768.0).astype(np.float32)
        else:
            whisper_samples = (samples / 32768.0).astype(np.float32)

        is_too_short = duration_s < 0.25
        # Multi-factor speech vs silence detection:
        # True digital silence / unrouted mic: rms < 12.0 and peak < 50.0
        # Stationary background noise floor without speech: rms < 35.0 and peak < 120.0 and crest_factor < 2.5
        is_silent = (rms < 12.0 and peak < 50.0) or (rms < 35.0 and peak < 120.0 and crest_factor < 2.5)

        error_code = None
        error_msg = None
        if is_too_short:
            error_code = "AUDIO_TOO_SHORT"
            error_msg = f"Audio duration too short ({duration_s:.2f}s). Please hold the microphone while speaking."
        elif is_silent:
            error_code = "MIC_NO_AUDIO"
            error_msg = f"Audio was captured, but no sound was detected (RMS {rms:.1f}, Peak {int(peak)}). Please speak closer to the microphone."

        diag = AudioDiagnostic(
            duration_s=duration_s,
            sample_rate=sample_rate,
            channels=channels,
            bytes_count=bytes_count,
            sample_count=sample_count,
            rms_energy=rms,
            is_silent=is_silent,
            is_too_short=is_too_short,
            format_name="WAV",
            peak_amplitude=peak,
            crest_factor=crest_factor,
            error_code=error_code,
            error_message=error_msg
        )
        return diag, whisper_samples

    except Exception as e:
        diag = AudioDiagnostic(
            duration_s=0.0,
            sample_rate=16000,
            channels=1,
            bytes_count=bytes_count,
            sample_count=0,
            rms_energy=0.0,
            is_silent=False,
            is_too_short=False,
            format_name=ext,
            error_code="AUDIO_DECODE_ERROR",
            error_message=f"Failed to decode audio file: {e}"
        )
        return diag, None


def _get_whisper_model():
    """Load Whisper 'base' model once and cache it if available."""
    global _model, _whisper_import_attempted, _whisper_module
    if _model is not None:
        return _model

    if not _whisper_import_attempted:
        _whisper_import_attempted = True
        try:
            import whisper
            _whisper_module = whisper
        except (ImportError, OSError, Exception) as e:
            print(f"  [INFO] Local Whisper not loaded ({e}). SpeechRecognition & Gemini active.")
            _whisper_module = None

    if _whisper_module is None:
        return None

    try:
        print("  [INFO] Loading Whisper 'base' model...")
        _model = _whisper_module.load_model("base")
        print("  [OK] Whisper model loaded")
    except Exception as e:
        print(f"  [WARN] Failed to initialize Whisper model: {e}")
        _model = None

    return _model


async def _transcribe_with_speech_recognition(audio_bytes: bytes, file_extension: str) -> tuple[str | None, str | None]:
    """
    Attempt transcription using SpeechRecognition (Google Web Speech API).
    Returns (transcribed_text, error_detail).
    """
    if not _SR_AVAILABLE:
        return None, "SpeechRecognition library not installed"

    try:
        recognizer = sr.Recognizer()
        recognizer.energy_threshold = 200
        recognizer.dynamic_energy_threshold = True

        with tempfile.NamedTemporaryFile(suffix=f".{file_extension}", delete=False) as tmp:
            tmp.write(audio_bytes)
            tmp_path = tmp.name

        try:
            with sr.AudioFile(tmp_path) as source:
                audio_data = recognizer.record(source)
            text = recognizer.recognize_google(audio_data, language="en-US")
            if text and text.strip():
                print(f"  [STT:Google] Transcribed: '{text.strip()}'")
                return text.strip(), None
        except sr.UnknownValueError:
            print("  [STT:Google] No speech detected in audio file.")
            return None, "No recognizable speech detected by Google Web Speech"
        except sr.RequestError as e:
            print(f"  [STT:Google] API request failed: {e}")
            return None, f"Google Web Speech network error: {e}"
        except Exception as e:
            print(f"  [STT:Google] Audio reading error: {e}")
            return None, f"Google Web Speech audio read error: {e}"
        finally:
            Path(tmp_path).unlink(missing_ok=True)
    except Exception as e:
        print(f"  [STT:Google] Error: {e}")
        return None, str(e)

    return None, "Unknown error in Google Web Speech"


async def _transcribe_with_whisper_numpy(whisper_samples: np.ndarray | None) -> tuple[str | None, str | None]:
    """
    Attempt transcription using local Whisper model with direct float32 in-memory NumPy array.
    Zero ffmpeg subprocess requirement.
    """
    if whisper_samples is None or len(whisper_samples) == 0:
        return None, "No audio samples provided for Whisper"

    model = _get_whisper_model()
    if model is None:
        return None, "Whisper model could not be initialized"

    try:
        result = model.transcribe(whisper_samples, fp16=False, language="en")
        text = result.get("text", "").strip()
        if text:
            print(f"  [STT:Whisper] Transcribed: '{text}'")
            return text, None
        return None, "Whisper transcribed empty string"
    except Exception as e:
        print(f"  [STT:Whisper] Error: {e}")
        return None, f"Whisper transcription exception: {e}"


async def _transcribe_with_gemini(audio_bytes: bytes, file_extension: str) -> tuple[str | None, str | None]:
    """Attempt transcription using Google Gemini Multimodal Audio."""
    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key or api_key == "your_gemini_api_key_here":
        return None, "GEMINI_API_KEY not configured"

    try:
        from google import genai
        from google.genai import types

        client = genai.Client(api_key=api_key)
        mime_type = "audio/wav" if file_extension == "wav" else f"audio/{file_extension}"

        prompt = (
            "Listen to this audio clip and transcribe the user's spoken voice command verbatim. "
            "Return ONLY the transcribed text. Do not add quotes, commentary, or punctuation explanations."
        )

        response = client.models.generate_content(
            model="gemini-2.0-flash",
            contents=[
                types.Part.from_bytes(data=audio_bytes, mime_type=mime_type),
                prompt
            ]
        )
        if response.text and response.text.strip():
            text = response.text.strip()
            print(f"  [STT:Gemini] Transcribed: '{text}'")
            return text, None
        return None, "Gemini returned empty text"
    except Exception as e:
        print(f"  [STT:Gemini] Transcription failed: {e}")
        return None, f"Gemini API error: {e}"


async def transcribe_diagnostic(audio_bytes: bytes, file_extension: str = "wav") -> dict[str, Any]:
    """
    Full diagnostic transcription pipeline.
    Returns:
      {
        "text": str,
        "engine": str | None,
        "duration_s": float,
        "sample_rate": int,
        "channels": int,
        "rms_energy": float,
        "bytes_count": int,
        "error_code": str | None,
        "user_message": str,
        "diagnostic_log": str
      }
    """
    ext = file_extension.lower().lstrip(".")
    if not ext:
        ext = "wav"

    diag, whisper_samples = parse_and_diagnose_audio(audio_bytes, ext)

    log_line = (
        f"[VOICE] bytes={diag.bytes_count}, duration={diag.duration_s:.2f}s, "
        f"sampleRate={diag.sample_rate}, channels={diag.channels}, rms={diag.rms_energy:.1f}"
    )

    # If audio is empty, too short, or silent:
    if diag.error_code:
        print(f"  {log_line} -> {diag.error_code}: {diag.error_message}")
        return {
            "text": "",
            "engine": None,
            "duration_s": diag.duration_s,
            "sample_rate": diag.sample_rate,
            "channels": diag.channels,
            "rms_energy": diag.rms_energy,
            "bytes_count": diag.bytes_count,
            "error_code": diag.error_code,
            "user_message": diag.error_message,
            "diagnostic_log": log_line
        }

    # Engine 1: SpeechRecognition (Google Web Speech API)
    google_text, google_err = await _transcribe_with_speech_recognition(audio_bytes, ext)
    if google_text:
        print(f"  {log_line} -> STT:Google success: '{google_text}'")
        return {
            "text": google_text,
            "engine": "GoogleWebSpeech",
            "duration_s": diag.duration_s,
            "sample_rate": diag.sample_rate,
            "channels": diag.channels,
            "rms_energy": diag.rms_energy,
            "bytes_count": diag.bytes_count,
            "error_code": None,
            "user_message": "",
            "diagnostic_log": f"{log_line} -> GoogleWebSpeech: '{google_text}'"
        }

    # Engine 2: Local Whisper (in-memory NumPy array, zero ffmpeg)
    whisper_text, whisper_err = await _transcribe_with_whisper_numpy(whisper_samples)
    if whisper_text:
        print(f"  {log_line} -> STT:Whisper success: '{whisper_text}'")
        return {
            "text": whisper_text,
            "engine": "WhisperLocal",
            "duration_s": diag.duration_s,
            "sample_rate": diag.sample_rate,
            "channels": diag.channels,
            "rms_energy": diag.rms_energy,
            "bytes_count": diag.bytes_count,
            "error_code": None,
            "user_message": "",
            "diagnostic_log": f"{log_line} -> WhisperLocal: '{whisper_text}'"
        }

    # Engine 3: Gemini 2.0 Flash Audio
    gemini_text, gemini_err = await _transcribe_with_gemini(audio_bytes, ext)
    if gemini_text:
        print(f"  {log_line} -> STT:Gemini success: '{gemini_text}'")
        return {
            "text": gemini_text,
            "engine": "GeminiFlash",
            "duration_s": diag.duration_s,
            "sample_rate": diag.sample_rate,
            "channels": diag.channels,
            "rms_energy": diag.rms_energy,
            "bytes_count": diag.bytes_count,
            "error_code": None,
            "user_message": "",
            "diagnostic_log": f"{log_line} -> GeminiFlash: '{gemini_text}'"
        }

    # All engines failed
    print(f"  {log_line} -> ALL_ENGINES_FAILED (Google: {google_err}, Whisper: {whisper_err}, Gemini: {gemini_err})")
    return {
        "text": "",
        "engine": None,
        "duration_s": diag.duration_s,
        "sample_rate": diag.sample_rate,
        "channels": diag.channels,
        "rms_energy": diag.rms_energy,
        "bytes_count": diag.bytes_count,
        "error_code": "TRANSCRIPTION_FAILED",
        "user_message": "Audio was captured, but speech could not be transcribed. Please try again.",
        "diagnostic_log": f"{log_line} -> ALL_ENGINES_FAILED"
    }


async def transcribe(audio_bytes: bytes, file_extension: str = "wav") -> str:
    """Backwards-compatible transcribe function."""
    res = await transcribe_diagnostic(audio_bytes, file_extension)
    return res["text"]
