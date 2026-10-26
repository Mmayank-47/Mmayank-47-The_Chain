# The Chain - Project Progress (PROGRESS.md)

## Phase 0: Baseline & Hygiene Verification (Completed)
- **Codebase Discovery**: Examined architecture across `core/`, `m0_transport/` through `m11_redteam/`, `phone/` (`PhoneNode`), `sim/`, `tests/`, and `frontend/`.
- **Pre-existing Defects Resolved**:
  - `phone/persistence.py`: Fixed `win.bitmap` access by serializing sliding window seen set `_seen: Set[int]`.
  - `m0_transport/dtn.py`: Added default timestamp `now: float = 0.0` to `CarryStore.add()`.
  - `Makefile`: Added `gateway` package to `compile` target and created `gateway-test` target.
  - `frontend/package.json`: Upgraded `esbuild` to `^0.28.0` to satisfy Vite 8 peer dependencies.
- **Master Gate Verification**: `make check` passed with all existing tests green.

---

## Phase 1: Backend Gateway (`gateway/`) (Completed)
- **Contract Specification**: Authored comprehensive REST & SSE API specification in [`docs/API.md`](docs/API.md).
- **Core Implementation**:
  - Pure Python stdlib HTTP server using `http.server.ThreadingHTTPServer`.
  - Thread-safe simulation state in `gateway/state.py` hosting 5 simulated `PhoneNode` instances (`node-me`, `node-relay-1`, `node-resp-1`, `node-neighbor-2`, `node-detour`) over `SimMedium`.
  - Background tick loop (1.0 s) advancing real clock, routing frames, running anti-entropy gossip, and duty-cycle tracking.
  - Full REST endpoints implemented: `/api/claims`, `/api/claims/{id}/broadcast`, `/api/claims/{id}` (abort), `/api/requests`, `/api/requests/{id}/respond`, `/api/mesh`, `/api/map`, `/api/me`, `/api/sim/*`.
  - Server-Sent Events (SSE) `/api/events` delivering low-latency real-time mesh events (`claim_opened`, `dispatch_state`, `neighbor_up/down`, `partition_healed`, `drop_counter`).
  - Production SPA static file hosting with SPA fallback serving `frontend/dist` directly from port 8000.
- **Contract & Integration Tests**:
  - Authored `tests/test_gateway.py` with 8 test cases verifying all endpoints, malformed input rejection, SSE streaming, and multi-node claim propagation.
  - Full test suite passed (317 tests in 60s).

---

## Phase 2: Frontend Integration (Completed)
- **Typed API Client & Real-Time SSE**:
  - Created `frontend/src/api/client.ts` implementing strongly-typed client for all gateway endpoints.
  - Created `frontend/src/api/events.ts` managing SSE connection lifecycle with exponential backoff auto-reconnect.
  - Updated `frontend/src/types.ts` aligning data models with M2 parser, M3 verifier, and M10 rationale outputs.
- **Screen Integration (Zero Mock Data)**:
  - **HelpScreenStep2**: Integrated debounced live call to `POST /api/claims` (M2 NLP parsing, M3 confidence score, M10 rationale). Integrated Web Speech API with fallback message.
  - **HelpScreenStep3**: Replaced `setTimeout` hop timer with live SSE `useMeshEvents` tracking real CRDT dispatch state (`PROPOSED` → `ACCEPTED` → `EN_ROUTE` → `RESOLVED`). Wired Abort button to `DELETE /api/claims/{id}`.
  - **RespondScreen**: Replaced mock requests with live `GET /api/requests`. Connected Accept/Decline/Refer actions. Implemented M9 two-stage privacy disclosure unlocking sealed victim details upon acceptance.
  - **MapScreen**: Connected to live `GET /api/map` rendering real M4 flood waterline, hazard grid cells, and coarse victim cells.
  - **MeshScreen**: Connected to live `GET /api/mesh` rendering real neighbor links, airtime duty cycle, ingress guard drop counters, and interactive Dev Chaos controls (Partition, Heal, Blackhole attack, Kill Node).
  - **MeScreen**: Connected to `GET/PUT /api/me` for profile persistence.
  - **App Header**: Live battery percentage and mesh peer count from `api.getMesh()` and SSE events. Multi-language switcher (EN, HI, MR) passing `lang` query parameter to backend.
- **Build & Quality Gates**:
  - `npm run lint` clean (0 errors, 0 warnings).
  - `npm run build` clean (compiled `dist/` in 277ms).
  - Verified with `grep`: Zero mock arrays or fake network timers remain in the codebase.

---

## Phase 3: Run on Phone & PWA Offline Support (Completed)
- **PWA Capabilities**:
  - Added `frontend/public/manifest.json` for standalone app installation.
  - Added `frontend/public/sw.js` caching core assets offline while bypassing cache for live mesh API and SSE streams.
- **Execution Environments Documented**:
  - Authored [`docs/RUN_ON_PHONE.md`](docs/RUN_ON_PHONE.md) covering Laptop-Hosted (USB via `adb reverse` and local Wi-Fi LAN) and Standalone on-device via Termux.
  - Created `scripts/termux_setup.sh` for one-command standalone Android Termux execution.

---

## Phase 4: Autonomous E2E Browser & Mobile Verification (Completed)
- Authored `scripts/verify_e2e_browser.mjs` using headless Chrome at Android mobile viewport (360x800).
- Successfully executed 9 end-to-end flows:
  1. Navigation & Initial Telemetry Sync (95% battery, 2 direct peers).
  2. Help Step 1 -> Step 2 category selection.
  3. Step 2 live NLP parsing of natural language prompt with real M2/M3 confidence badges.
  4. Step 2 -> Step 3 broadcast via mesh replica.
  5. Step 3 live SSE broadcast tracking and abort capability.
  6. Respond screen live request display, M10 rationale, and M9 sealed detail unlocking upon accept.
  7. Tactical Map live flood waterline and hazard grid cell rendering.
  8. Mesh screen direct radio peers, airtime duty cycle, and Dev Chaos controls (Partition & Heal).
  9. Me screen profile persistence and EN/HI/MR language switching.
- Verified **0 console errors** and saved 12 high-resolution screenshots to artifacts.
