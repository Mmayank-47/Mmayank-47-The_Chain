"""GatewayState: in-memory mesh coordination, background tick thread, and SSE event hub."""
from __future__ import annotations

import json
import math
import os
import queue
import random
import threading
import time
from typing import Any, Callable, Dict, List, Optional, Tuple

from core.claim_adapters import cell_of
from core.crypto import Signer
from core.types import DispatchState, Priority
from m0_transport.radio_adapter import SimMedium, SimRadio
from m1_identity.keys import dev_fleet
from phone.node import PhoneNode
from phone.persistence import NodePersistence


class GatewayState:
    """Coordinates the simulated mesh nodes, handles background ticks, and manages SSE broadcasts."""

    def __init__(self, seed: int = 42, profile_path: str = "profile.json",
                 persistence_dir: str = "data_nodes"):
        self.seed = seed
        self.rng = random.Random(seed)
        self.now = 1000.0  # Starting time offset for simulation
        self.profile_path = profile_path
        self.persistence_dir = persistence_dir
        os.makedirs(self.persistence_dir, exist_ok=True)

        # Threading and synchronization
        self._lock = threading.RLock()
        self._running = False
        self._tick_thread: Optional[threading.Thread] = None
        self._sse_subscribers: List[queue.Queue] = []

        # Medium and fleet keys
        self.node_ids = ["node-me", "node-relay-1", "node-resp-1", "node-neighbor-2", "node-detour"]
        self.fleet = dev_fleet(self.node_ids + ["atk0"])
        self.medium = SimMedium(loss=0.01, latency_s=0.01, rng=self.rng)

        # Positions
        self.positions: Dict[str, List[float]] = {
            "node-me": [150.0, 250.0],
            "node-neighbor-2": [170.0, 220.0],
            "node-relay-1": [250.0, 270.0],
            "node-detour": [220.0, 400.0],
            "node-resp-1": [350.0, 300.0],
        }

        # Construct PhoneNodes
        self.nodes: Dict[str, PhoneNode] = {}
        for nid in self.node_ids:
            radio = SimRadio(nid, self.medium)
            bat = 0.95 if nid == "node-me" else (0.75 if "relay" in nid else 0.88)
            p = PhoneNode(nid, radio, self.fleet, now_ms=lambda: int(self.now * 1000), battery_level=bat)
            self.nodes[nid] = p

        self.node_me = self.nodes["node-me"]
        self.node_resp = self.nodes["node-resp-1"]

        # Link default network topology
        self._link_default_topology()

        # Attacker state
        self.attacker_node: Optional[Any] = None
        self.attacker_type: str = "none"

        # Caches
        self.claims_cache: Dict[str, Any] = {}
        self.sealed_envelopes: Dict[str, Any] = {}

        # Flood hazard state
        self.flood_level_m = 0.35
        self.flood_x = 125.0
        self.hazard_cells = self._generate_hazard_cells()

        # Profile state
        self.profile = self._load_profile()

        # Seed initial neighbor discovery
        self._seed_beacons()

    def _link_default_topology(self):
        links = [
            ("node-me", "node-relay-1"),
            ("node-me", "node-neighbor-2"),
            ("node-neighbor-2", "node-detour"),
            ("node-relay-1", "node-resp-1"),
            ("node-relay-1", "node-detour"),
            ("node-detour", "node-resp-1"),
        ]
        for a, b in links:
            self.medium.link(a, b)

    def _seed_beacons(self):
        """Initial exchange so neighbors are visible immediately."""
        with self._lock:
            for _ in range(3):
                self.now += 1.0
                for p in self.nodes.values():
                    p.node.tick(self.now)

    def _load_profile(self) -> dict:
        default = {
            "fullName": "Suman Kulkarni",
            "phoneMeshId": "node-me",
            "bloodGroup": "O+ (Positive)",
            "chronicConditions": ["Hypertension (BP)", "Diabetic"],
            "emergencyContact": "+91 98201 54321",
            "lowBatterySaver": False
        }
        if os.path.exists(self.profile_path):
            try:
                with open(self.profile_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    default.update(data)
            except Exception:
                pass
        return default

    def _save_profile(self):
        tmp = f"{self.profile_path}.tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.profile, f, indent=2)
        os.replace(tmp, self.profile_path)

    def _generate_hazard_cells(self) -> list:
        cells = []
        for x_idx in range(4):
            for y_idx in range(4):
                cell_id = f"CELL-{x_idx}:{y_idx}"
                cell_x = x_idx * 100.0 + 50.0
                is_water = cell_x < self.flood_x + 30.0
                depth = max(0.0, round((self.flood_x + 50.0 - cell_x) * 0.4, 1)) if is_water else 0.0
                risk = min(1.0, round(depth / 50.0, 2)) if is_water else round(0.1 + (x_idx * 0.05), 2)
                cells.append({
                    "cell": cell_id,
                    "risk": risk,
                    "is_water": is_water,
                    "depth_cm": depth
                })
        return cells

    # ---- Lifecycle and Tick Loop -------------------------------------------------------------
    def start(self):
        with self._lock:
            if self._running:
                return
            self._running = True
            self._tick_thread = threading.Thread(target=self._run_loop, name="GatewayTickThread", daemon=True)
            self._tick_thread.start()

    def stop(self):
        with self._lock:
            self._running = False
        if self._tick_thread and self._tick_thread.is_alive():
            self._tick_thread.join(timeout=2.0)

    def _run_loop(self):
        last_t = time.time()
        while self._running:
            now_real = time.time()
            dt = now_real - last_t
            last_t = now_real
            # Run tick
            self.tick(dt=max(0.1, min(dt, 2.0)))
            time.sleep(1.0)

    def tick(self, dt: float = 1.0):
        with self._lock:
            self.now += dt
            # Slowly advance flood
            self.flood_x += 0.05 * dt
            self.flood_level_m += 0.0001 * dt

            # Tick PhoneNodes
            for node in self.nodes.values():
                node.tick(self.now)

            # Tick AttackerNode if active
            if self.attacker_node:
                try:
                    self.attacker_node.tick(self.now)
                except Exception:
                    pass

            # Exchange anti-entropy periodically
            if int(self.now) % 3 == 0:
                for a_id, b_id in [("node-me", "node-relay-1"), ("node-relay-1", "node-resp-1")]:
                    a_node, b_node = self.nodes.get(a_id), self.nodes.get(b_id)
                    if a_node and b_node and b_id in self.medium.links[a_id]:
                        summary = a_node.ae.request(b_id, self.now)
                        if summary:
                            a_node.node._send_frame(b_id, summary, self.now)

            # Check for new drop events to emit SSE
            if self.node_me.guard.dropped:
                total_drops = sum(self.node_me.guard.dropped.values())
                if total_drops > getattr(self, "_last_drops", 0):
                    self._last_drops = total_drops
                    self.emit_sse("drop_counter", dict(self.node_me.guard.dropped))

    # ---- SSE Subscription & Dispatch ---------------------------------------------------------
    def subscribe_sse(self) -> queue.Queue:
        q = queue.Queue(maxsize=100)
        with self._lock:
            self._sse_subscribers.append(q)
        return q

    def unsubscribe_sse(self, q: queue.Queue):
        with self._lock:
            if q in self._sse_subscribers:
                self._sse_subscribers.remove(q)

    def emit_sse(self, event_type: str, data: Any):
        payload = f"event: {event_type}\ndata: {json.dumps(data)}\n\n"
        with self._lock:
            dead = []
            for q in self._sse_subscribers:
                try:
                    q.put_nowait(payload)
                except queue.Full:
                    dead.append(q)
            for d in dead:
                if d in self._sse_subscribers:
                    self._sse_subscribers.remove(d)

    # ---- API Business Logic ------------------------------------------------------------------
    def analyze_claim(self, raw_text: str, lang: str = "en", pos: Optional[List[float]] = None) -> dict:
        with self._lock:
            client_pos = pos or self.positions["node-me"]
            trace = self.node_me.run_chain(raw_text, tuple(client_pos), now=self.now, lang=lang)
            wc = trace["wire_claim"]
            cid = wc.id
            self.claims_cache[cid] = trace

            # Stage-2 sealed details (M9 privacy)
            self.sealed_envelopes[cid] = {
                "exact_pos": list(client_pos),
                "contact_phone": self.profile.get("emergencyContact", "+91 98201 54321"),
                "victim_name": self.profile.get("fullName", "Emergency User"),
                "medical_details": ", ".join(self.profile.get("chronicConditions", ["Asthma"])),
            }

            parsed = trace["m2_parsed"]
            verdict = trace["m3_verdict"]
            just = trace.get("m10_justification")
            top_reasons = verdict.top_reasons(3)
            if not top_reasons:
                top_reasons = [("identity", 0.5)]

            return {
                "claim_id": cid,
                "parsed": {
                    "kind": parsed.kind,
                    "capability_tags": parsed.capability_tags,
                    "features": parsed.features,
                    "quantity": parsed.quantity,
                    "location_text": parsed.location_text,
                    "language": parsed.language,
                    "confidence": parsed.confidence,
                    "urgency": parsed.urgency,
                },
                "p_valid": round(verdict.p, 3),
                "top_reasons": top_reasons,
                "trust": round(trace["m5_trust"], 3),
                "battery": trace["m6_battery"],
                "gate": trace["m7_match"]["gate"],
                "disclosure": {
                    "case": {
                        "id": cid,
                        "cell": cell_of(client_pos),
                        "tags": parsed.capability_tags,
                    },
                    "public": trace["m9_disclosure"]["public"],
                },
                "justification": {
                    "text": just.text if just else "Assistance requested.",
                    "risk": just.risk if just else "low",
                    "abort": just.abort if just else "Turn back if unsafe.",
                    "lines": just.lines if just else ["Assistance requested."],
                }
            }

    def broadcast_claim(self, claim_id: str) -> dict:
        with self._lock:
            trace = self.claims_cache.get(claim_id)
            if trace:
                parsed = trace["m2_parsed"]
                pos = trace["wire_claim"].pos
                cell = cell_of(pos)
                urgency = parsed.urgency or 0.8
            else:
                cell = "0:0"
                urgency = 0.8

            ev = self.node_me.open_claim(claim_id, cell, urgency)

            # Also ensure neighbor nodes observe the claim
            for n in self.nodes.values():
                if n.id != self.node_me.id:
                    n.observe(trace["wire_claim"] if trace else claim_id)

            self.emit_sse("claim_opened", {
                "claim_id": claim_id,
                "cell": cell,
                "urgency": urgency,
            })

            return {
                "ok": True,
                "claim_id": claim_id,
                "event_hash": ev.hash,
                "event": ev.to_json(),
            }

    def abort_claim(self, claim_id: str) -> dict:
        with self._lock:
            did = f"{claim_id}|{self.node_me.id}"
            self.node_me.replica.dispatch(claim_id, self.node_me.id)
            ev = self.node_me.replica.set_state(did, DispatchState.RESOLVED)

            self.emit_sse("dispatch_state", {
                "did": did,
                "state": "RESOLVED",
                "claim_id": claim_id,
                "aborted": True,
            })

            return {
                "ok": True,
                "claim_id": claim_id,
                "aborted": True,
            }

    def get_requests(self) -> dict:
        with self._lock:
            plan = self.node_me.replica.book.plan(int(self.now * 1000))
            out = []

            for claim_id, claim_info in self.node_me.replica.book.claims.items():
                rec = None
                winner_did = plan.winners.get(claim_id)
                for r in self.node_me.replica.book.recs.values():
                    if r.claim == claim_id:
                        rec = r
                        break

                status = "open"
                if rec:
                    status = rec.state.name.lower()

                # Stage-2 sealed private details are released ONLY if accepted
                is_accepted = (rec is not None and rec.state >= DispatchState.ACCEPTED)
                sealed = self.sealed_envelopes.get(claim_id) if is_accepted else None

                cached = self.claims_cache.get(claim_id)
                if cached:
                    wc = cached.get("wire_claim")
                    raw_text = getattr(cached.get("m2_parsed"), "location_text", "Emergency Request")
                    just = cached.get("m10_justification")
                    just_dict = {
                        "text": just.text if just else "Assistance requested.",
                        "risk": just.risk if just else "low",
                        "abort": just.abort if just else "Turn back if unsafe.",
                    }
                else:
                    raw_text = f"Emergency request in cell {claim_info.get('cell', '0:0')}"
                    just_dict = {
                        "text": "Assistance needed in area.",
                        "risk": "medium",
                        "abort": "Turn back if water exceeds safety threshold.",
                    }

                created_ms = (rec.created.wall if (rec and rec.created) else int(self.now * 1000))
                age_s = round(max(0.0, (int(self.now * 1000) - created_ms) / 1000.0), 1)

                out.append({
                    "id": claim_id,
                    "cell": claim_info.get("cell", "0:0"),
                    "urgency": claim_info.get("urgency", 0.7),
                    "age_s": age_s,
                    "hops": 1,
                    "winner": winner_did,
                    "status": status,
                    "raw_text": raw_text,
                    "justification": just_dict,
                    "sealed": sealed,
                })

            return {"requests": out}

    def respond_request(self, claim_id: str, action: str, reason: Optional[str] = None,
                        referral: Optional[str] = None) -> dict:
        with self._lock:
            did = f"{claim_id}|{self.node_me.id}"
            if action == "accept":
                self.node_me.replica.dispatch(claim_id, self.node_me.id)
                self.node_me.replica.set_state(did, DispatchState.ACCEPTED)

                sealed = self.sealed_envelopes.get(claim_id)
                if not sealed:
                    sealed = {
                        "exact_pos": [152.4, 248.8],
                        "contact_phone": "+91 98201 54321",
                        "victim_name": "Suman Kulkarni",
                        "medical_details": "Hypertension (BP), Diabetic"
                    }
                    self.sealed_envelopes[claim_id] = sealed

                self.emit_sse("dispatch_state", {
                    "did": did,
                    "state": "ACCEPTED",
                    "claim_id": claim_id,
                })
                return {
                    "ok": True,
                    "did": did,
                    "state": "accepted",
                    "sealed_released": sealed,
                }
            elif action == "decline":
                self.emit_sse("dispatch_state", {
                    "did": did,
                    "state": "DECLINED",
                    "claim_id": claim_id,
                    "reason": reason,
                })
                return {"ok": True, "did": did, "state": "declined"}
            elif action == "refer":
                self.emit_sse("dispatch_state", {
                    "did": did,
                    "state": "REFERRED",
                    "claim_id": claim_id,
                    "referral": referral,
                })
                return {"ok": True, "did": did, "state": "referred", "referral": referral}
            else:
                raise ValueError(f"Unknown action {action}")

    def get_mesh(self) -> dict:
        with self._lock:
            neighbors = []
            for nid, ninfo in self.node_me.node.neighbors.items():
                hops = 1
                conn_type = "Direct Wi-Fi" if nid in ("node-relay-1", "node-neighbor-2") else "BLE Mesh"
                last_seen_ago = round(max(0.0, self.now - ninfo.last_seen), 1)
                trust = round(self.node_me.trust_engine.trust(nid, self.now), 2)
                neighbors.append({
                    "id": nid,
                    "battery": round(ninfo.battery, 2),
                    "cell": ninfo.cell or "0:0",
                    "last_seen_s_ago": last_seen_ago,
                    "trust": trust,
                    "hop_estimate": hops,
                    "connection_type": conn_type,
                })

            dropped = dict(self.node_me.guard.dropped)
            for k in ("frame_unauthenticated", "frame_size", "rate_limited"):
                dropped.setdefault(k, 0)

            return {
                "node_id": self.node_me.id,
                "battery": round(self.node_me.battery_level, 2),
                "cell": cell_of(self.positions["node-me"]),
                "neighbors": neighbors,
                "dropped_counters": dropped,
                "airtime": {
                    "duty_cycle_used": 0.002,
                    "duty_cycle_limit": 0.01,
                    "total_airtime_s": 0.24,
                    "total_tx_bytes": self.medium.bytes_sent,
                },
                "watchdog_penalties": [],
                "forks": [],
                "suspects": [self.attacker_node.id] if self.attacker_node else [],
            }

    def get_map(self) -> dict:
        with self._lock:
            entities = [
                {"id": "shelter-1", "type": "safe_shelter", "cell": "CELL-1:1",
                 "coarse_pos": [320.0, 280.0], "title": "Shivaji Nagar Shelter"},
                {"id": "medical-1", "type": "medical_post", "cell": "CELL-1:0",
                 "coarse_pos": [200.0, 420.0], "title": "Relief Clinic Post"},
            ]

            # Coarse victim claims (exact GPS coordinates never disclosed here!)
            for cid, claim_info in self.node_me.replica.book.claims.items():
                pos = self.positions.get("node-me", [150.0, 250.0])
                # Round to cell coordinates
                coarse_x = round(pos[0] / 50.0) * 50.0
                coarse_y = round(pos[1] / 50.0) * 50.0
                entities.append({
                    "id": cid,
                    "type": "distress_call",
                    "cell": claim_info.get("cell", "0:0"),
                    "coarse_pos": [coarse_x, coarse_y],
                    "urgency": "critical" if claim_info.get("urgency", 0) > 0.8 else "high",
                    "title": f"Distress in {claim_info.get('cell', 'area')}",
                })

            return {
                "flood_level_m": round(self.flood_level_m, 2),
                "flood_x": round(self.flood_x, 1),
                "hazard_cells": self._generate_hazard_cells(),
                "entities": entities,
            }

    def get_profile(self) -> dict:
        with self._lock:
            return dict(self.profile)

    def update_profile(self, new_data: dict) -> dict:
        with self._lock:
            allowed = ("fullName", "bloodGroup", "chronicConditions", "emergencyContact", "lowBatterySaver")
            for k in allowed:
                if k in new_data:
                    self.profile[k] = new_data[k]
            self._save_profile()
            return dict(self.profile)

    # ---- Dev Simulation Controls -------------------------------------------------------------
    def sim_partition(self, start_x: float = 300.0) -> dict:
        with self._lock:
            # Cut links across x boundary
            self.medium.unlink("node-relay-1", "node-resp-1")
            self.medium.unlink("node-detour", "node-resp-1")
            self.emit_sse("neighbor_down", {"node_id": "node-resp-1", "reason": "partition"})
            return {"ok": True, "partitioned": True}

    def sim_heal(self) -> dict:
        with self._lock:
            self._link_default_topology()
            # Run exchange
            for a_id, b_id in [("node-relay-1", "node-resp-1"), ("node-detour", "node-resp-1")]:
                a_node, b_node = self.nodes[a_id], self.nodes[b_id]
                s = a_node.ae.request(b_id, self.now)
                if s:
                    a_node.node._send_frame(b_id, s, self.now)
            self.emit_sse("partition_healed", {"time": self.now})
            return {"ok": True, "healed": True}

    def sim_attack(self, attack_type: str) -> dict:
        with self._lock:
            from sim.mesh_sim import AttackerNode
            self.attacker_type = attack_type
            radio = SimRadio("atk0", self.medium)
            self.attacker_node = AttackerNode("atk0", radio, attack_type, self.rng, signer=self.fleet)
            self.medium.link("atk0", "node-me")
            self.medium.link("atk0", "node-relay-1")
            self.medium.link("atk0", "node-resp-1")
            self.emit_sse("neighbor_up", {"node_id": "atk0", "cell": "0:0", "suspect": True})
            return {"ok": True, "attack": attack_type}

    def sim_kill_node(self, node_id: str) -> dict:
        with self._lock:
            if node_id in self.nodes:
                self.medium.isolate(node_id)
                self.emit_sse("neighbor_down", {"node_id": node_id, "reason": "killed"})
                return {"ok": True, "killed": node_id}
            return {"ok": False, "error": f"Unknown node {node_id}"}
