"""PhoneNode: unified phone stack wiring M0 transport, Dispatcher, M8 replica, and M2-M10 chain."""
from __future__ import annotations

import math
import os
import time
from typing import Any, Callable, Dict, List, Optional, Tuple

from core import crypto
from core.claim import WireClaim
from core.claim_adapters import M3Claim, cell_of, to_m3, to_m7
from core.crypto import Signer
from core.dispatcher import Dispatcher
from core.types import HLC, Message, Priority
from m0_transport.gossip import GossipEngine
from m0_transport.ingress import IngressGuard
from m0_transport.node import MeshNode
from m0_transport.radio_adapter import Radio
from m0_transport.watchdog import Watchdog
from m2_parser.keyword_fallback import parse
from m3_verify.p_valid import Verdict, Verifier
from m5_trust.trust import TrustEngine
from m6_battery.drain_model import Sample, fit_rate, time_to_silence
from m6_battery.urgency import urgency as battery_urgency
from m7_matching.utility import Claim as M7Claim, gate
from m8_merge import bridge
from m8_merge.anti_entropy import AntiEntropy
from m8_merge.event_log import Event
from m8_merge.replica import Replica
from m9_privacy.disclosure import Case
from m10_ux.justify import Facts, justify, verifiers_from_parts
from m10_ux.responder_app import ResponderApp


class PhoneNode:
    """A full node on the mesh network.

    Combines:
      * M0 transport (MeshNode, IngressGuard, GossipEngine)
      * Core message Dispatcher (kind routing)
      * M8 Replica + bridge (signed conflict-free replication)
      * AntiEntropy (vector-clock sync & backfill)
      * M2 Claim Parser, M3 Verifier, M5 Trust Engine, M6 Battery, M7 Matching, M9 Privacy, M10 UX
    """

    def __init__(self, node_id: str, radio: Radio, signer: Signer, *,
                 now_ms: Optional[Callable[[], int]] = None,
                 guard: Optional[IngressGuard] = None,
                 dispatcher: Optional[Dispatcher] = None,
                 replica: Optional[Replica] = None,
                 engine: Optional[GossipEngine] = None,
                 trust_engine: Optional[TrustEngine] = None,
                 verifier: Optional[Verifier] = None,
                 battery_level: float = 1.0,
                 battery_drain_rate: float = 3e-5,
                 persistence_path: Optional[str] = None,
                 **mesh_kw):
        self.id = node_id
        self.radio = radio
        self.signer = signer
        self.now_ms = now_ms or (lambda: int(time.time() * 1000))
        self.dispatcher = dispatcher or Dispatcher()
        self.replica = replica or Replica(node_id, signer, now_ms=self.now_ms)
        self.guard = guard or IngressGuard(signer=signer)
        self.battery_level = battery_level
        self.battery_drain_rate = battery_drain_rate
        self.battery_samples: List[Sample] = [Sample(0.0, battery_level)]
        self.persistence_path = persistence_path

        # Higher layer services
        self.trust_engine = trust_engine or TrustEngine()
        self.verifier = verifier or Verifier()
        self.app = ResponderApp(node_id)
        self.pending_claims: Dict[str, Any] = {}

        # Watchdog for forwarding verification, hop acks, and multipath reroute
        self.watchdog = Watchdog(self.trust_engine.rep, ack_timeout_s=6.0)
        if engine is None:
            self.engine = GossipEngine(node_id, watchdog=self.watchdog,
                                       trust_fn=lambda n, now: self.trust_engine.trust(n, now))
        else:
            self.engine = engine
            if self.engine.watchdog is None:
                self.engine.watchdog = self.watchdog
            if self.engine.trust_fn is None:
                self.engine.trust_fn = lambda n, now: self.trust_engine.trust(n, now)

        # M0 transport node
        self.node = MeshNode(node_id, radio, self.engine,
                             get_battery=lambda: self.battery_level,
                             guard=self.guard, signer=signer, **mesh_kw)

        # Wire M8 replica to MeshNode via dispatcher
        bridge.attach(self.replica, self.node, dispatcher=self.dispatcher)

        # Wire anti-entropy exchange
        self.ae = AntiEntropy(self.replica)
        self.node.frame_handlers["ae.sum"] = self._on_ae_sum
        self.node.frame_handlers["ae.ev"] = self._on_ae_ev

        # Restore from persistent state if path provided and exists
        if persistence_path and os.path.exists(persistence_path):
            from .persistence import NodePersistence
            NodePersistence.restore(self, persistence_path)

    def save_state(self, path: Optional[str] = None):
        target = path or self.persistence_path
        if target:
            from .persistence import NodePersistence
            NodePersistence.save(self, target)

    # ---- anti-entropy hooks ------------------------------------------------------------------
    def _on_ae_sum(self, frm: str, frame: dict, now: float):
        batch = self.ae.on_summary(frm, frame, now)
        if batch:
            self.node._send_frame(frm, batch, now)

    def _on_ae_ev(self, frm: str, frame: dict, now: float):
        self.ae.on_events(frm, frame, now)

    # ---- M8 convenience delegations ----------------------------------------------------------
    def emit(self, kind: str, data: dict) -> Event:
        return self.replica.emit(kind, data)

    def open_claim(self, claim_id: str, cell: str, urgency: float) -> Event:
        return self.replica.open_claim(claim_id, cell, urgency)

    def originate(self, msg: Message, now: float) -> bool:
        return self.node.originate(msg, now)

    # ---- 3-method policy interface (p_valid, observe, tick) -----------------------------------
    def p_valid(self, claim: Any) -> float:
        """M3 verification score for the claim."""
        return self.verifier.p_valid(claim)

    def observe(self, claim: Any) -> None:
        """Feed an observed claim into verification ledger and local pool."""
        self.verifier.observe(claim)
        cid = getattr(claim, "id", None) or str(claim)
        self.pending_claims[cid] = claim

    def tick(self, now_or_world: Any) -> Any:
        """Polymorphic tick: accepts either a float timestamp for M0 transport tick,
        or a World object for the simulation policy interface."""
        if isinstance(now_or_world, (int, float)):
            self.node.tick(float(now_or_world))
            return None
        # World policy tick
        world = now_or_world
        return self._policy_tick(world)

    def _policy_tick(self, w: Any) -> list:
        from sim.world import Dispatch
        out = []
        for comp in w.components():
            idle = [w.nodes[i] for i in comp
                    if getattr(w.nodes[i], "role", None) == "responder"
                    and getattr(w.nodes[i], "target", None) is None
                    and getattr(w.nodes[i], "status", None) == "ok"]
            for cid, c in list(self.pending_claims.items()):
                p = self.p_valid(c)
                if p >= 0.6 and idle:
                    r = min(idle, key=lambda resp: math.dist(resp.pos, c.pos))
                    idle.remove(r)
                    del self.pending_claims[cid]
                    out.append(Dispatch(r.id, c, p))
        return out

    # ---- Full chain pipeline: parse -> verify -> trust -> battery -> match -> disclosure -> justify
    def run_chain(self, raw_text: str, pos: Tuple[float, float], now: float = 0.0,
                  radius_m: float = 20.0, p_route_safe: float = 0.95,
                  p_reach: float = 0.98, route_len: float = 200.0,
                  lang: str = "en") -> Dict[str, Any]:
        """Execute full claim processing chain and return an auditable trace dict."""
        trace: Dict[str, Any] = {}

        # 1. M2: Zero-shot, code-mixed parser (text -> ParsedClaim)
        parsed = parse(raw_text)
        trace["m2_parsed"] = parsed

        # 2. WireClaim wrapping parsed claim with cryptographic signature
        wc = WireClaim.make(self.id, pos, radius_m, now, parsed.to_json(), self.signer)
        trace["wire_claim"] = wc

        # 3. M3: Verifier scoring in log-odds space
        m3_c = to_m3(wc, battery=self.battery_level)
        verdict = self.verifier.score(m3_c, now)
        trace["m3_verdict"] = verdict

        # 4. M5: Trust engine lookup on claimant
        trust_score = self.trust_engine.trust(wc.node, now)
        trace["m5_trust"] = trust_score

        # 5. M6: Battery telemetry, time-to-silence, urgency
        rate = fit_rate(self.battery_samples, default=self.battery_drain_rate)
        tts_s = time_to_silence(self.battery_level, rate)
        bat_urgency = battery_urgency(tts_s)
        trace["m6_battery"] = {"rate": rate, "tts_s": tts_s, "urgency": bat_urgency}

        # 6. M7: Matching claim, pair utility, gate decision
        m7_c = to_m7(wc, verdict.p, trust=trust_score, battery_tts_s=tts_s)
        gate_decision = gate(m7_c.p_valid, p_route_safe, m7_c.trust, p_reach)
        trace["m7_match"] = {"claim": m7_c, "gate": gate_decision}

        # 7. M9: Two-stage disclosure Case (coarse public metadata vs sealed private details)
        case = Case(wc.id, pos=wc.pos, radius_m=wc.radius, tags=parsed.capability_tags,
                    features=parsed.features, location_text=parsed.location_text)
        public_meta = case.public()
        trace["m9_disclosure"] = {"case": case, "public": public_meta}

        # 8. M10: Template-grounded justification
        verifiers_count = verifiers_from_parts(verdict.parts)
        facts = Facts(tags=public_meta["tag"] and (public_meta["tag"],) or (),
                      dist_m=route_len, p_safe=p_route_safe, p_reach=p_reach,
                      battery=self.battery_level, verifiers=verifiers_count,
                      gate=gate_decision, skill=public_meta.get("need"))
        justification = justify(facts, lang=lang)
        trace["m10_justification"] = justification

        # 9. Responder App Prompt presentation
        if justification is not None:
            prompt = self.app.show(case.id, justification, now)
            trace["m10_prompt"] = prompt

        return trace
