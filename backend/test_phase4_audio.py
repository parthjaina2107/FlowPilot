import io
import random
import struct
import wave
import requests

def test_audio(name, data, filename="test.wav", mime="audio/wav"):
    files = {"audio": (filename, io.BytesIO(data), mime)}
    res = requests.post("http://127.0.0.1:8000/api/match/audio", files=files)
    body = res.json()
    print(f"[{name}] status={res.status_code}")
    print(f"  matched: {body.get('matched')}")
    print(f"  flow_name: {body.get('flow_name')}")
    print(f"  transcribed_text: {body.get('transcribed_text')}")
    print(f"  suggestion: {body.get('suggestion')}")
    print()

print("================ PHASE 4: AUDIO ENDPOINT VERIFICATION ================\n")

# 1. Valid spoken command
with open("test_lofi.wav", "rb") as f:
    test_audio("1. VALID_SPOKEN_COMMAND", f.read())

# 2. Silent audio (1.5s silence)
buf = io.BytesIO()
with wave.open(buf, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(16000)
    w.writeframes(b"\x00" * (16000 * 2 * 1))
test_audio("2. SILENT_AUDIO", buf.getvalue())

# 3. Very short audio (0.1s silence)
buf = io.BytesIO()
with wave.open(buf, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(16000)
    w.writeframes(b"\x00" * 3200)
test_audio("3. VERY_SHORT_AUDIO", buf.getvalue())

# 4. Malformed audio (corrupt bytes)
test_audio("4. MALFORMED_AUDIO", b"RIFF1234WAVEfmt corrupt header data")

# 5. Large audio (5 seconds silence)
buf = io.BytesIO()
with wave.open(buf, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(16000)
    w.writeframes(b"\x00" * (16000 * 2 * 5))
test_audio("5. LARGE_SILENT_AUDIO", buf.getvalue())

# 6. Background noise (random noise, low amplitude)
buf = io.BytesIO()
with wave.open(buf, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(16000)
    frames = bytearray()
    for _ in range(16000):
        val = random.randint(-50, 50)
        frames.extend(struct.pack("<h", val))
    w.writeframes(frames)
test_audio("6. BACKGROUND_NOISE_AUDIO", buf.getvalue())
