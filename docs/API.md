# The Chain - Gateway REST & SSE API Specification

The Gateway exposes a unified HTTP and Server-Sent Events (SSE) interface that wraps a live local `PhoneNode` and its simulated peer mesh environment (`SimMedium`). It serves the production frontend single-page application from `/` while serving the `/api` namespace from the same origin to eliminate CORS and mixed-content issues on mobile devices.

All incoming client text is treated as unauthenticated user data and strictly filtered through the M2 parser and ingress sanitization.

---

## 1. Endpoints

### 1.1 `POST /api/claims`
Analyze a raw emergency text report through the full decision pipeline (`M2` parser → `WireClaim` → `M3` verifier → `M5` trust → `M6` battery → `M7` utility gate → `M9` privacy case → `M10` justification).

- **Request Body**:
  ```json
  {
    "text": "Mother needs bp medicine, Shivaji Nagar Lane 4",
    "lang": "en",
    "pos": [150.0, 250.0]
  }
  ```
  - `text` *(string, required, 1..1000 chars)*: Emergency description.
  - `lang` *(string, optional)*: Language code (`"en"`, `"hi"`, `"mr"`). Default: `"en"`.
  - `pos` *(array of 2 floats, optional)*: Client GPS coordinates `[x, y]`. Default: local node position.

- **Response `200 OK`**:
  ```json
  {
    "claim_id": "c_1d3a43f1",
    "parsed": {
      "kind": "claim",
      "capability_tags": ["medic"],
      "features": ["severe_bleeding"],
      "quantity": "2 strips",
      "location_text": "Shivaji Nagar Lane 4",
      "language": "en",
      "confidence": 0.85,
      "urgency": 0.72
    },
    "p_valid": 0.794,
    "top_reasons": [["identity", 0.5]],
    "trust": 0.5,
    "battery": {
      "rate": 0.00003,
      "tts_s": 33333.3,
      "urgency": 0.0001
    },
    "gate": "dispatch",
    "disclosure": {
      "case": {
        "id": "c_1d3a43f1",
        "cell": "0:0",
        "tags": ["medic"]
      },
      "public": {
        "c": "c_1d3a43f1",
        "cell": "0:0",
        "cat": "medical",
        "need": "medic",
        "tag": "medic"
      }
    },
    "justification": {
      "text": "medic needed, 200 m away. Not independently verified yet. Route via ? looks dry (95% safe). Your battery (100%) covers the trip. Turn back if water reaches 15 cm or battery falls below 15%.",
      "risk": "low",
      "abort": "Turn back if water reaches 15 cm or battery falls below 15%.",
      "lines": [
        "medic needed, 200 m away.",
        "Not independently verified yet.",
        "Route via ? looks dry (95% safe).",
        "Your battery (100%) covers the trip.",
        "Turn back if water reaches 15 cm or battery falls below 15%."
      ]
    }
  }
  ```

---

### 1.2 `POST /api/claims/{id}/broadcast`
Commit and broadcast an emergency claim onto the local M8 CRDT event log and M0 DTN transport gossip engine.

- **URL Parameter**: `id` *(string)*: The claim identifier returned by `POST /api/claims`.
- **Response `200 OK`**:
  ```json
  {
    "ok": true,
    "claim_id": "c_1d3a43f1",
    "event_hash": "8b49ed8cf016db7a68a82db45371a0ed47129bb5cc0d278799d7b112b5e737a1",
    "event": {
      "node": "node-me",
      "seq": 0,
      "kind": "claim",
      "data": { "claim": "c_1d3a43f1", "cell": "0:0", "urgency": 0.72 }
    }
  }
  ```

---

### 1.3 `DELETE /api/claims/{id}`
Cancel or abort a broadcasting claim. Emits a state update terminating the claim's lease in the CRDT book.

- **Response `200 OK`**:
  ```json
  {
    "ok": true,
    "claim_id": "c_1d3a43f1",
    "aborted": true
  }
  ```

---

### 1.4 `GET /api/requests`
Fetch incoming distress requests visible in the local phone's CRDT book and gossip cache. Returns coarse privacy-preserving metadata until a responder accepts the assignment.

- **Response `200 OK`**:
  ```json
  {
    "requests": [
      {
        "id": "claim-4a",
        "cell": "CELL-1",
        "urgency": 0.95,
        "age_s": 14.2,
        "hops": 1,
        "winner": null,
        "status": "open",
        "raw_text": "Need medic at underpass, severe bleeding",
        "justification": {
          "text": "medic needed, 200 m away. High urgency.",
          "risk": "medium",
          "abort": "Turn back if water reaches 15 cm"
        },
        "sealed": null
      }
    ]
  }
  ```

---

### 1.5 `POST /api/requests/{id}/respond`
Act on an emergency request (`accept`, `decline`, or `refer`). Accepting triggers M7 dispatch proposal and unlocks M9 stage-2 sealed details (exact location and direct contact).

- **Request Body**:
  ```json
  {
    "action": "accept",
    "reason": "En route with medical kit",
    "referral": null
  }
  ```
- **Response `200 OK`**:
  ```json
  {
    "ok": true,
    "did": "claim-4a|node-me",
    "state": "accepted",
    "sealed_released": {
      "exact_pos": [152.4, 248.8],
      "contact_phone": "+91 98201 54321",
      "victim_name": "Suman Kulkarni",
      "medical_details": "Hypertension (BP), Diabetic"
    }
  }
  ```

---

### 1.6 `GET /api/mesh`
Inspect live local mesh status, radio link neighbors, ingress drop counters, duty cycle utilization, and watchdog reputation evidence.

- **Response `200 OK`**:
  ```json
  {
    "node_id": "node-me",
    "battery": 0.88,
    "cell": "0:0",
    "neighbors": [
      {
        "id": "node-relay-1",
        "battery": 0.75,
        "cell": "0:0",
        "last_seen_s_ago": 1.2,
        "trust": 0.72,
        "hop_estimate": 1,
        "connection_type": "Direct Wi-Fi"
      }
    ],
    "dropped_counters": {
      "frame_unauthenticated": 0,
      "frame_size": 0,
      "rate_limited": 0
    },
    "airtime": {
      "duty_cycle_used": 0.002,
      "duty_cycle_limit": 0.01,
      "total_airtime_s": 0.24,
      "total_tx_bytes": 1420
    },
    "watchdog_penalties": [],
    "forks": [],
    "suspects": []
  }
  ```

---

### 1.7 `GET /api/map`
Coarse hazard map grid, active flood line, safe shelters, medical relief posts, and coarse cells of visible claims and responders. Exact victim coordinates are strictly withheld until accepted.

- **Response `200 OK`**:
  ```json
  {
    "flood_level_m": 0.35,
    "flood_x": 125.0,
    "hazard_cells": [
      { "cell": "CELL-0", "risk": 0.85, "is_water": true, "depth_cm": 35.0 }
    ],
    "entities": [
      { "id": "claim-4a", "type": "distress", "cell": "CELL-1", "coarse_pos": [150.0, 250.0], "urgency": "critical" },
      { "id": "shelter-1", "type": "shelter", "cell": "CELL-2", "coarse_pos": [320.0, 280.0] }
    ]
  }
  ```

---

### 1.8 `GET /api/me` and `PUT /api/me`
Read and update the local emergency identity profile stored on-device via `phone.persistence`. Private cryptographic keys are never exposed over the API.

- **PUT Request**:
  ```json
  {
    "fullName": "Suman Kulkarni",
    "bloodGroup": "O+ (Positive)",
    "chronicConditions": ["Hypertension (BP)", "Diabetic"],
    "emergencyContact": "+91 98201 54321",
    "lowBatterySaver": false
  }
  ```
- **Response `200 OK`**: Updated profile object.

---

### 1.9 `GET /api/events` (Server-Sent Events)
Persistent SSE stream broadcasting real-time protocol updates to the UI.

- **Headers**:
  `Content-Type: text/event-stream`
  `Cache-Control: no-cache`
  `Connection: keep-alive`

- **Stream Events**:
  - `claim_opened`: Emitted when a new claim arrives in the replica book.
  - `dispatch_state`: Emitted when an auction assignment or state transition occurs (`PROPOSED` → `ACCEPTED` → `EN_ROUTE` → `ARRIVED` → `RESOLVED`).
  - `neighbor_up`: New peer beacon authenticated.
  - `neighbor_down`: Peer timed out without beacon.
  - `partition_healed`: Network partition reconciled via anti-entropy exchange.
  - `drop_counter`: Frame dropped by ingress guard or watchdog timeout.
  - `message_delivered`: Message delivered to destination handler.

---

### 1.10 `POST /api/sim/{partition|heal|attack|kill_node}`
Developer control hooks to simulate network dynamics and exercise all frontend states:
- `POST /api/sim/partition` `{"start_x": 500.0}`: Partition the simulated mesh into isolated halves.
- `POST /api/sim/heal`: Reconnect all partitions and trigger anti-entropy sync.
- `POST /api/sim/attack` `{"type": "blackhole"|"greyhole"|"replay"|"sybil"}`: Inject adversarial adversary node.
- `POST /api/sim/kill_node` `{"node_id": "node-relay-1"}`: Simulate sudden peer failure or power loss.
