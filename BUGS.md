# The Chain - Defect Tracking Log (BUGS.md)

| ID | File | Symptom | Root Cause | Status |
| :--- | :--- | :--- | :--- | :---: |
| **BUG-001** | `frontend/package.json` | `npm install` fails with `ERESOLVE could not resolve peerOptional esbuild@"^0.27.0 \|\| ^0.28.0" from vite@8.3.5` | `frontend/package.json` had `"esbuild": "^0.25.0"` conflicting with Vite 8's peer dependency requirement | **CLOSED** |
| **BUG-002** | `phone/persistence.py` | `AttributeError: 'ReplayWindow' object has no attribute 'bitmap'` when restoring replay windows | `ReplayWindow` in `m0_transport/ingress.py` stores sliding window state in `_seen: Set[int]`, not a bitmap field | **CLOSED** |
| **BUG-003** | `m0_transport/dtn.py` | Potential `TypeError: missing required argument 'now'` if `CarryStore.add` is called without explicit timestamp | `now: float` had no default value in `CarryStore.add` signature | **CLOSED** |
| **BUG-004** | `Makefile` | `compile` target syntax check omitted `gateway` package | Target list in Makefile did not include upcoming `gateway` directory | **CLOSED** |
| **BUG-005** | `gateway/state.py` | `KeyError: slice(None, 3, None)` during `analyze_claim()` | `verdict.parts` is a dictionary, not a sequence; slicing it failed. Used `verdict.top_reasons(3)` method instead | **CLOSED** |
| **BUG-006** | `gateway/state.py` | `TypeError: 'float' object is not subscriptable` in `cell_of(pos[0], pos[1])` | `cell_of` takes a single tuple/list `pos: Tuple[float, float]`, not separate float arguments | **CLOSED** |
| **BUG-007** | `gateway/state.py` | Remote nodes stalled on sequence synchronization after a claim was aborted | Aborting a claim attempted to delete the payload from `CarryStore._c`, breaking the contiguous sequence numbers required by remote `EventStore` anti-entropy logs. Fixed by setting CRDT auction status to `RESOLVED` in the replica book to preserve the hash chain | **CLOSED** |
| **BUG-008** | `frontend/src/components/BottomNav.tsx` | Screen-readers and automated mobile tests (<640px) could not identify non-active navigation tabs | Button text labels were hidden on mobile via `hidden sm:inline-block` without dedicated `id`, `data-tab`, or `aria-label` attributes. Added `id="nav-tab-<key>"`, `data-tab="<key>"`, and `aria-label="<label>"` | **CLOSED** |
