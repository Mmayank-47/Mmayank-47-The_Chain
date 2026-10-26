#!/usr/bin/env python3
"""Run the Python reference and dump golden vectors for the Kotlin port.

Outputs are produced by executing this tree, not by re-deriving the spec from memory.
Float comparisons on the Kotlin side use an absolute tolerance of 1e-9.
Discrete outputs (ids, strings, ints, categories) are exact.
"""
from __future__ import annotations

import json
import math
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core import crypto  # noqa: E402
from core.bloom import BloomFilter  # noqa: E402
from core.claim import WireClaim  # noqa: E402
from core.crdt import LWWMap  # noqa: E402
from core.ratelimit import TokenBucket  # noqa: E402
from core.types import (  # noqa: E402
    HLC,
    HLClock,
    ClockDrift,
    Message,
    Priority,
    canonical,
    event_priority,
)
from m2_parser.keyword_fallback import parse as kw_parse  # noqa: E402
from m2_parser.schema import urgency_from_features, validate  # noqa: E402
from m3_verify.p_valid import Verifier  # noqa: E402
from m4_hazard.flood_model import FloodModel  # noqa: E402
from m4_hazard.hazard_grid import HazardGrid  # noqa: E402
from m4_hazard.router import RoadGraph, find_route  # noqa: E402
from m5_trust.beta_rep import BetaRep  # noqa: E402
from m5_trust.trust import TrustEngine  # noqa: E402
from m5_trust.vouch_graph import VouchGraph  # noqa: E402
from m6_battery.drain_model import Sample, battery_llr, fit_rate, time_to_silence  # noqa: E402
from m6_battery.relay_select import Peer, energy_needed, p_reach, pick_relays  # noqa: E402
from m7_matching.cbba import Bidder, gossip  # noqa: E402
from m7_matching.features import claim_scores  # noqa: E402
from m7_matching.triage import Category, Features, classify  # noqa: E402
from m7_matching.utility import Claim, Responder, gate, pair_utility  # noqa: E402
from m8_merge.conflict_rules import DispatchBook  # noqa: E402
from m8_merge.event_log import Event  # noqa: E402
from m8_merge.replica import Replica  # noqa: E402
from m9_privacy.disclosure import Case  # noqa: E402
from m10_ux.justify import Facts, justify  # noqa: E402
from m11_redteam.attack_suite import run_all  # noqa: E402

OUT = ROOT / "vectors"


def tag(obj):
    if obj is None:
        return {"t": "null"}
    if isinstance(obj, bool):
        return {"t": "bool", "v": obj}
    if isinstance(obj, int) and not isinstance(obj, bool):
        return {"t": "int", "v": obj}
    if isinstance(obj, float):
        return {"t": "float", "v": obj}
    if isinstance(obj, str):
        return {"t": "str", "v": obj}
    if isinstance(obj, (list, tuple)):
        return {"t": "list", "v": [tag(x) for x in obj]}
    if isinstance(obj, dict):
        return {"t": "map", "v": [[k, tag(v)] for k, v in obj.items()]}
    raise TypeError(type(obj))


def dumps(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"))


def write(name: str, payload) -> None:
    OUT.mkdir(exist_ok=True)
    path = OUT / f"{name}.json"
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {path.relative_to(ROOT)}")


class FixedSigner:
    def __init__(self, secret: bytes):
        self.secret = secret

    def sign(self, node: str, data: bytes) -> str:
        return crypto.mac(self.secret, data)

    def verify(self, node: str, data: bytes, sig: str) -> bool:
        return crypto.mac_ok(self.secret, data, sig)


SECRET = bytes(range(32))
SIGNER = FixedSigner(SECRET)


def core_vectors() -> dict:
    canon = []
    samples = [
        None, True, False, 0, 1, -3, 0.0, 1.0, -1.0, 0.1, 0.3, 10.0, 1.1,
        1e-4, 1e-5, 1e-7, 1e15, 1e16, 1e20, 2.5e20, -2.5e-8,
        1.0000000000000002, "hi", "दवा", 'a"b\\c', "line\n\t", "\u007f",
        {"b": 1, "a": [1, 0.5, None, "x"]},
        {"n": "phone", "s": "phone", "p": [19.1, 72.8], "r": 15.0, "t": 0.0,
         "d": {"kind": "need", "urgency": 0.3}},
    ]
    rng = random.Random(20261026)
    for i in range(80):
        samples.append(rng.uniform(-1e6, 1e6))
    for i, sample in enumerate(samples):
        canon.append({"name": f"c{i}", "tagged": tag(sample), "canon": dumps(sample)})

    hlc = []
    wall = [1_700_000_000_000]
    clock = HLClock("n1", now_ms=lambda: wall[0], max_drift_ms=5 * 60 * 1000)
    rng = random.Random(7)
    for i in range(40):
        wall[0] += rng.randint(0, 8)
        if rng.random() < 0.45:
            out = clock.now().to_json()
            hlc.append({"op": "now", "wall": wall[0], "out": out})
        else:
            remote = [wall[0] + rng.randint(-2000, 4000), rng.randint(0, 6), rng.choice(["n2", "n1", "a"])]
            try:
                out = clock.update(HLC.from_json(remote)).to_json()
                hlc.append({"op": "update", "wall": wall[0], "remote": remote, "out": out})
            except ClockDrift:
                hlc.append({"op": "update", "wall": wall[0], "remote": remote, "err": "drift"})
    hlc.append({"op": "drift_reject", "wall": 0, "remote": [10 * 60 * 1000, 0, "z"], "err": "drift"})

    a, b = LWWMap(), LWWMap()
    a.set("k", "old", HLC(1, 0, "a"))
    a.set("k", "new", HLC(2, 0, "a"))
    a.set("k", "lose", HLC(2, 0, "a"))  # equal HLC does not replace (not greater)
    b.set("k", "tie-node", HLC(2, 0, "b"))
    b.set("gone", "x", HLC(1, 0, "b"))
    b.delete("gone", HLC(3, 0, "b"))
    b.set("onlyb", 7, HLC(1, 1, "b"))
    a.merge(b)
    lww = {"items": sorted((k, v) for k, v in a.items())}

    bf = BloomFilter.for_capacity(100, 0.01)
    items = [f"id-{i}" for i in range(60)]
    for it in items:
        bf.add(it)
    probes = items[:30] + [f"no-{i}" for i in range(30)]
    bloom = {
        "m": bf.m, "k": bf.k, "bits": format(bf.bits, "x"),
        "members": {p: (p in bf) for p in probes},
    }
    rng = random.Random(99)
    bf2 = BloomFilter()
    rand_items = [f"r{rng.randrange(10**6)}" for _ in range(40)]
    for it in rand_items:
        bf2.add(it)
    bloom["default_bits"] = format(bf2.bits, "x")
    bloom["default_members"] = {p: (p in bf2) for p in rand_items + ["missing"]}

    bucket = []
    tb = TokenBucket(1.0, 2.0)
    for now in (0.0, 0.0, 0.0, 1.5, 1.5, 10.0):
        bucket.append({"now": now, "ok": tb.allow(now), "tokens": tb.tokens})

    parsed = {"kind": "need", "capability_tags": ["boat"], "features": [], "quantity": 2,
              "urgency": 0.3, "language": "en", "confidence": 0.55, "source": "keyword"}
    wc = WireClaim.make("phone-a", (19.076, 72.8777), 15.0, 10.0, parsed, SIGNER)
    msg = Message("m9.case", Priority.DISTRESS, "phone-a", {"c": wc.id}, HLC(10, 0, "phone-a"),
                  dest_cell="0:0", ttl_s=3600.0, created=10.0)
    claim = {
        "id": wc.id,
        "sig": wc.sig,
        "canon": dumps(wc.to_json()),
        "verify": wc.verify(SIGNER),
        "bad_sig": wc.verify(FixedSigner(b"nope" + bytes(28))),
        "cell": __import__("core.claim_adapters", fromlist=["cell_of"]).cell_of(wc.pos),
        "need": __import__("core.claim_adapters", fromlist=["need_of"]).need_of(parsed),
        "msg_id": msg.msg_id,
        "wire": dumps(msg.to_wire()),
        "event_priority_claim": int(event_priority("claim")),
        "event_priority_other": int(event_priority("beat")),
        "event_priority_hostile": int(event_priority(None)),
    }
    return {"canonical": canon, "hlc": hlc, "lww": lww, "bloom": bloom, "bucket": bucket, "claim": claim}


def parser_vectors() -> dict:
    texts = [
        "We need 3 boats at Shivaji Nagar urgently",
        "mujhe insulin chahiye shivaji nagar mein",
        "please mujhe dawai chahiye",
        "मुझे दवा चाहिए",
        "मला पाणी पाहिजे",
        "I have 2 boats available near the bridge",
        "hamare paas paani nahi hai",
        "grandfather not breathing and bleeding, we are trapped on the roof",
        "need water",
        "khoon beh raha hai bachao",
        "I have a drone",
        "hamare paas drone hai",
        "2 people trapped, help in 2 hours",
        "",
        "!!! ??? ...",
        "asdf qwer",
        "need a doctor for unconscious child",
        "oxygen cylinder chahiye",
    ]
    rng = random.Random(3)
    vocab = ["need", "boat", "paani", "चाहिए", "trapped", "grandfather", "insulin", "doctor", "nahi", "have"]
    for _ in range(25):
        texts.append(" ".join(rng.choice(vocab) for _ in range(rng.randint(3, 8))))
    cases = []
    for text in texts:
        p = kw_parse(text)
        cases.append({"text": text, "out": p.to_json()})
    urg = []
    for kind, feats in (("need", ["not_breathing"]), ("need", ["child", "trapped"]), ("offer", ["trapped"]),
                        ("unknown", [])):
        urg.append({"kind": kind, "features": feats, "u": urgency_from_features(kind, feats)})
    try:
        validate({"kind": "banana"})
        rejected = False
    except Exception:
        rejected = True
    return {"parses": cases, "urgency": urg, "bad_kind_rejected": rejected}


def verify_vectors() -> dict:
    class C:
        def __init__(self, i, node, src, pos, t):
            self.id, self.node, self.src, self.pos, self.t = i, node, src, pos, t
            self.urgency, self.need, self.battery = 0.5, "medic", 0.8

    v = Verifier()
    claims = [
        C("a", "n1", "n1", (0.0, 0.0), 0.0),
        C("b", "n1", "other", (500.0, 0.0), 1.0),
        C("c", "n2", "n2", (10.0, 10.0), 2.0),
        C("d", "n1", "n1", (0.0, 0.0), 4000.0),
    ]
    out = []
    for c in claims:
        verdict = v.score(c, now=c.t)
        out.append({"id": c.id, "p": verdict.p, "parts": verdict.parts})
    return {"scores": out}


def hazard_vectors() -> dict:
    g = RoadGraph()
    for n, p in {"A": (0.0, 0.0), "B": (200.0, 0.0), "C": (0.0, 200.0), "D": (200.0, 200.0)}.items():
        g.add_node(n, p)
    for a, b in (("A", "B"), ("A", "C"), ("C", "D"), ("D", "B")):
        g.add_edge(a, b)
    plain = find_route(g, "A", "B", HazardGrid().risk_fn())
    grid = HazardGrid()
    grid.report("h", (100.0, 0.0), "hazard", 0.0, 0.9)
    blocked = find_route(g, "A", "B", grid.risk_fn())
    detour = find_route(g, "A", "B", grid.risk_fn())
    flood = FloodModel()
    raised = flood.observe(1.2, 10.0)
    ignored = flood.observe(0.2, 11.0)
    return {
        "plain": None if plain is None else {"path": plain.path, "length": plain.length, "cost": plain.cost,
                                              "p_safe": plain.p_safe, "worst": plain.worst_risk},
        "direct_after_hazard_is_none": blocked is None,
        "detour_none_same_grid": detour is None,
        "risk": grid.risk(grid.cell_of((100.0, 0.0)), 0.0),
        "flood_raised": raised,
        "flood_ignored_lower": ignored,
        "flood_level": flood.level(20.0),
        "p_flooded_under": flood.p_flooded(0.5, 20.0),
        "p_flooded_high": flood.p_flooded(50.0, 20.0),
    }


def trust_vectors() -> dict:
    rep = BetaRep(half_life_s=100.0)
    rep.record("n", "verified_arrival", 0.0)
    rep.record("n", "failed_claim", 50.0, 1.0)
    g = VouchGraph(seeds=["root"])
    g.vouch("root", "h1")
    g.vouch("h1", "h2", 1.0)
    eng = TrustEngine(g, rep)
    return {
        "mean0": rep.mean("n", 0.0),
        "mean200": rep.mean("n", 200.0),
        "std0": rep.std("stranger", 0.0),
        "evidence": rep.evidence("n", 50.0),
        "scores": g.scores(),
        "trust_h1": eng.trust("h1", 0.0),
        "trust_stranger": eng.trust("nobody", 0.0),
    }


def battery_vectors() -> dict:
    samples = [Sample(0.0, 0.9), Sample(100.0, 0.89), Sample(200.0, 0.88)]
    fake = [Sample(t * 300.0, 1.0) for t in range(20)]
    return {
        "rate": fit_rate(samples),
        "tts": time_to_silence(0.5, 3e-5),
        "llr_ok": battery_llr(samples),
        "llr_flat": battery_llr(fake),
        "relays": [p.id for p in pick_relays([Peer("a", 0.9, 8), Peer("b", 0.1, 20), Peer("c", 0.5, 2)], 2)],
        "p_reach": p_reach(0.8, 500.0),
        "energy": energy_needed(500.0),
    }


def matching_vectors() -> dict:
    cats = []
    feats = [
        Features(can_walk=True),
        Features(can_walk=False, breathing=False),
        Features(can_walk=False, breathing=True, resp_rate=34),
        Features(can_walk=False, breathing=True, resp_rate=20, perfusion_ok=False),
        Features(False, True, 20, True, True),
        Features(False, None, None, None, None),
    ]
    for f in feats:
        cats.append(classify(f).value)
    scores = []
    for features, urg, kind in ((["not_breathing"], 0.3, "need"), (["elderly"], 0.4, "need"), ([], 0.3, "offer")):
        cat, u, g = claim_scores(features, urg, kind, 600.0)
        scores.append({"cat": cat.value, "u": u, "g": g})
    bs = {
        "a": Bidder("a", {"t1": 0.9, "t2": 0.2}),
        "b": Bidder("b", {"t1": 0.4, "t2": 0.8}),
        "c": Bidder("c", {"t1": 0.5, "t2": 0.5}),
    }
    rounds = gossip(bs, [("a", "b"), ("b", "c")])
    assign = {i: b.my_task() for i, b in bs.items()}
    c = Claim("c1", (0.0, 0.0), 0.9, 0.8, 0.8, "medic", 0.7)
    r = Responder("r1", (10.0, 0.0), frozenset({"medic"}), 0.9)
    return {
        "categories": cats,
        "claim_scores": scores,
        "cbba_rounds": rounds,
        "cbba_tasks": assign,
        "utility": pair_utility(c, r, 100.0, 0.95, 0.99, 0.0),
        "gate": gate(0.9, 0.95, 0.7, 0.99),
        "gate_low": gate(0.2, 0.95, 0.9, 0.99),
    }


def merge_vectors() -> dict:
    rep = Replica("A", SIGNER, now_ms=lambda: 1_000_000)
    rep.open_claim("claim-1", "1:2", 0.4)
    did = rep.dispatch("claim-1", "A")
    rep.set_state(did, __import__("core.types", fromlist=["DispatchState"]).DispatchState.ACCEPTED)
    rep.heartbeat(did)
    plan = rep.plan(1_000_000)
    other = Replica("B", SIGNER, now_ms=lambda: 1_000_100)
    n = other.ingest([e for log in rep.store.logs.values() for e in log])
    plan_b = other.plan(1_000_100)
    ev = Event.make("A", 0, "", HLC(5, 0, "A"), "claim", {"claim": "z", "cell": "0:0", "urgency": 0.2}, SIGNER)
    forged = Event("A", 0, "", HLC(5, 0, "A"), "claim", {"claim": "z", "cell": "0:0", "urgency": 0.2}, ev.hash, "00")
    store_added = len(other.store.add(forged))
    return {
        "did": did,
        "ingested": n,
        "winners_a": plan.winners,
        "winners_b": plan_b.winners,
        "forged_dropped": store_added == 0,
        "event_hash": ev.hash,
    }


def privacy_ux_vectors() -> tuple:
    case = Case("case-1", (12345.6, 67890.1), 18.0, tags=("insulin", "boat"),
                features=("not_breathing",), location_text="roof of ward 3", quantity=2)
    pub = case.public()
    priv = case.private(0.0)
    facts = Facts(tags=("boat",), dist_m=240.0, p_safe=0.96, p_reach=0.98, battery=0.8,
                  route_via="Ring Rd", verifiers=2, skill="boat", nearest=True, place="Shivaji Nagar")
    texts = {}
    for lang in ("en", "hi", "mr"):
        j = justify(facts, lang)
        texts[lang] = None if j is None else {"text": j.text, "risk": j.risk, "abort": j.abort}
    risky = justify(Facts(tags=("medicine",), dist_m=1500.0, p_safe=0.55, p_reach=0.5, battery=0.2,
                          worst_risk=0.6, gate="dispatch"), "en")
    return (
        {"public": pub, "private_has_pos": "pos" in priv, "public_blob": dumps(pub),
         "exact_in_public": "12345.6" in dumps(pub)},
        {"langs": texts, "risky": None if risky is None else risky.text,
         "hold_is_none": justify(Facts(tags=("boat",), dist_m=10, p_safe=1, p_reach=1, battery=1, gate="hold")) is None},
    )


def main() -> None:
    crypto.set_provider(crypto.DevProvider())
    write("core", core_vectors())
    write("parser", parser_vectors())
    write("verify", verify_vectors())
    write("hazard", hazard_vectors())
    write("trust", trust_vectors())
    write("battery", battery_vectors())
    write("matching", matching_vectors())
    write("merge", merge_vectors())
    privacy, ux = privacy_ux_vectors()
    write("privacy", privacy)
    write("ux", ux)
    red = [{"name": r.name, "undefended": r.undefended, "defended": r.defended, "max_ok": r.max_ok, "ok": r.ok,
            "note": r.note} for r in run_all()]
    write("redteam", {"attacks": red})


if __name__ == "__main__":
    main()
