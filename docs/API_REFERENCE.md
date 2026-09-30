# FlowPilot REST API Reference

> For the comprehensive interactive API specification, see **[API_SPEC.md](API_SPEC.md)**.

## Quick Reference

| Method | Path | Function | Payload Summary |
| :--- | :--- | :--- | :--- |
| `GET` | `/` | Health Check | None |
| `GET` | `/api/flows` | List Flows | None |
| `GET` | `/api/flows/{flow_id}` | Flow Details | None |
| `DELETE` | `/api/flows/{flow_id}` | Delete Flow | None |
| `POST` | `/api/generalise/compile` | Compile Trace | `RecordingTrace` JSON |
| `POST` | `/api/match/text` | Match Text | `{"command": "..."}` |
| `POST` | `/api/match/audio` | Match Audio | `multipart/form-data` with `audio` |

Detailed schema models and curl commands are provided in [docs/API_SPEC.md](API_SPEC.md).
