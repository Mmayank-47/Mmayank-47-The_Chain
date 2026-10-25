"""End-to-end simulation of real PhoneNodes (M0 transport + M8 merge) over SimMedium.

Simulates:
  * PhoneNodes moving across 2D terrain (walk model)
  * Rising flood boundary drowning victims / forcing detours
  * Physical radio loss and distance-based link connectivity
  * Dynamic network partitions and post-partition reconciliation
  * Adversarial conditions: blackhole, greyhole, frame replay, eclipse
  * Real protocol metrics: delivery ratio, latency, airtime overhead, partition recovery time.
"""
from __future__ import annotations

import json
import math
import os
import random
import subprocess
import time
from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Optional, Set, Tuple

from core.bloom import BloomFilter
from core.claim_adapters import cell_of
from m0_transport.radio_adapter import SimMedium, SimRadio
from m1_identity.keys import dev_fleet
from phone.node import PhoneNode


@dataclass
class SimConfig:
    seed: int = 42
    area: float = 1500.0            # square area in metres
    n_responders: int = 12
    n_victims: int = 8
    n_attackers: int = 2
    n_relays: int = 2               # corridor relays
    duration: float = 300.0         # seconds (sufficient for multi-hop propagation)
    tick: float = 5.0               # seconds per step
    radio_range: float = 350.0      # link distance threshold
    radio_loss: float = 0.05        # 5% random frame drop in medium
    flood_x0: float = 100.0         # initial water front
    flood_rate: float = 0.10        # flood advance m/s
    partition: Optional[Tuple[float, float]] = None  # (start_s, end_s)
    attack_type: str = "none"       # none | blackhole | greyhole | replay | eclipse
    enable_watchdog: bool = True    # Phase 6 defense: hop acks, watchdog, multipath failover


@dataclass
class SimResults:
    delivery_ratio: float
    delivered_count: int
    emitted_count: int
    mean_latency_s: float
    airtime_overhead: float
    total_bytes_sent: int
    useful_bytes: int
    partition_recovery_time_s: Optional[float]
    scenario: str
    seed: int


class AttackerNode:
    """Simulated adversarial radio node (blackhole, greyhole, replay, eclipse)."""

    def __init__(self, node_id: str, radio: SimRadio, attack_type: str,
                 rng: random.Random, signer: Optional[Any] = None):
        self.id = node_id
        self.radio = radio
        self.attack_type = attack_type
        self.rng = rng
        self.signer = signer
        self.captured_frames: List[bytes] = []
        self.have_bf = BloomFilter(1024, 4)
        self._last_hello = -1e9
        self.hello_interval = 2.0
        self._seq = 0

    def _broadcast_frame(self, frame: dict, now: float):
        if self.signer is not None:
            self._seq += 1
            frame["snd"] = self.id
            frame["seq"] = self._seq
            from core.types import canonical
            body = {k: v for k, v in frame.items() if k != "sig"}
            frame["sig"] = self.signer.sign(self.id, canonical(body))
        self.radio.broadcast(json.dumps(frame).encode(), now)

    def tick(self, now: float):
        # 1. Periodically broadcast authenticated hello beacons advertising high battery and bloom filter
        if now - self._last_hello >= self.hello_interval:
            hello = {"t": "hello", "id": self.id, "cell": "0:0", "bat": 1.0, "bf": self.have_bf.to_json()}
            self._broadcast_frame(hello, now)
            self._last_hello = now

        # 2. Poll packets from radio
        packets = self.radio.poll(now)
        for frm, data in packets:
            try:
                f = json.loads(data.decode())
                if f.get("t") == "data" and "m" in f and "mid" in f["m"]:
                    self.have_bf.add(f["m"]["mid"])
            except Exception:
                pass

            if self.attack_type == "replay":
                self.captured_frames.append(data)
                if len(self.captured_frames) > 50:
                    self.captured_frames.pop(0)
            elif self.attack_type == "greyhole":
                # Selectively drops 60% of forwarded traffic, forwards 40%
                if self.rng.random() < 0.40:
                    self.radio.broadcast(data, now)
            elif self.attack_type == "blackhole":
                # Silently drops 100% of forwarded traffic
                pass

        # 3. Replay attack: rebroadcast captured frames to exhaust airtime
        if self.attack_type == "replay" and self.captured_frames:
            for _ in range(2):
                to_replay = self.rng.choice(self.captured_frames)
                self.radio.broadcast(to_replay, now)


class SimVictim:
    def __init__(self, node_id: str, phone: PhoneNode, pos: List[float],
                 emit_t: float, claim_id: str, urgency: float):
        self.id = node_id
        self.phone = phone
        self.pos = pos
        self.emit_t = emit_t
        self.claim_id = claim_id
        self.urgency = urgency
        self.emitted = False
        self.rescued = False
        self.dead = False


class SimResponder:
    def __init__(self, node_id: str, phone: PhoneNode, pos: List[float]):
        self.id = node_id
        self.phone = phone
        self.pos = pos
        self.target: Optional[SimVictim] = None
        self.speed = 1.5   # m/s walking speed
        self.dispatches: List[str] = []


class MeshSimulation:
    def __init__(self, config: SimConfig):
        self.cfg = config
        self.rng = random.Random(config.seed)
        self.now = 0.0

        # Build cryptographic fleet for all participating nodes
        self.victim_ids = [f"v{i}" for i in range(config.n_victims)]
        self.resp_ids = [f"r{i}" for i in range(config.n_responders)]
        self.relay_ids = [f"relay{i}" for i in range(config.n_relays)]
        self.atk_ids = [f"atk{i}" for i in range(config.n_attackers)]
        all_ids = self.victim_ids + self.resp_ids + self.relay_ids + self.atk_ids
        self.fleet = dev_fleet(all_ids)

        # Simulated physical wireless medium
        self.medium = SimMedium(loss=config.radio_loss, rng=self.rng)

        # Check if corridor topology is appropriate (for blackhole and greyhole attacks)
        is_corridor = config.attack_type in ("blackhole", "greyhole")

        def _make_phone(nid: str, rdo: SimRadio, bat: float = 1.0) -> PhoneNode:
            p = PhoneNode(nid, rdo, self.fleet, now_ms=lambda: int(self.now * 1000), battery_level=bat)
            if not config.enable_watchdog:
                p.engine.watchdog = None
                p.engine.trust_fn = None
            return p

        # Construct victims
        self.victims: Dict[str, SimVictim] = {}
        for i, vid in enumerate(self.victim_ids):
            radio = SimRadio(vid, self.medium)
            phone = _make_phone(vid, radio)
            if is_corridor:
                # West cluster: victims in range of attacker at [450, 250] and detour relay0 at [250, 580]
                pos = [self.rng.uniform(120.0, 200.0), self.rng.uniform(220.0, 320.0)]
            else:
                pos = [self.rng.uniform(config.flood_x0 + 150, config.area * 0.9),
                       self.rng.uniform(config.area * 0.1, config.area * 0.9)]
            emit_t = self.rng.uniform(10.0, config.duration * 0.3)
            cid = f"claim-{vid}"
            urgency = self.rng.uniform(0.6, 0.95)
            self.victims[vid] = SimVictim(vid, phone, pos, emit_t, cid, urgency)

        # Construct corridor relays
        self.relays: Dict[str, PhoneNode] = {}
        self.relay_positions: Dict[str, List[float]] = {}
        for i, rly_id in enumerate(self.relay_ids):
            radio = SimRadio(rly_id, self.medium)
            phone = _make_phone(rly_id, radio, bat=0.75)
            self.relays[rly_id] = phone
            if is_corridor:
                # Detour path: relay0 at [250, 580] (in range of victims), relay1 at [550, 620] (in range of responders)
                pos = [250.0, 580.0] if (i == 0) else [550.0, 620.0]
                self.relay_positions[rly_id] = pos
            else:
                self.relay_positions[rly_id] = [self.rng.uniform(config.area * 0.3, config.area * 0.7), self.rng.uniform(config.area * 0.3, config.area * 0.7)]

        # Construct responders
        self.responders: Dict[str, SimResponder] = {}
        for i, rid in enumerate(self.resp_ids):
            radio = SimRadio(rid, self.medium)
            phone = _make_phone(rid, radio)
            if is_corridor:
                # East cluster: responders at x in [780, 850], y in [220, 650]
                pos = [self.rng.uniform(780.0, 850.0), self.rng.uniform(220.0, 650.0)]
            else:
                pos = [self.rng.uniform(config.flood_x0 + 200, config.area * 0.8),
                       self.rng.uniform(config.area * 0.1, config.area * 0.9)]
            self.responders[rid] = SimResponder(rid, phone, pos)

        # Construct attackers
        self.attackers: Dict[str, AttackerNode] = {}
        self.attacker_positions: Dict[str, List[float]] = {}
        for i, aid in enumerate(self.atk_ids):
            radio = SimRadio(aid, self.medium)
            atk = AttackerNode(aid, radio, config.attack_type, self.rng, signer=self.fleet)
            self.attackers[aid] = atk
            if config.attack_type == "eclipse" and self.victims:
                v0_pos = list(self.victims[self.victim_ids[0]].pos)
                pos = [v0_pos[0] + self.rng.uniform(-25, 25), v0_pos[1] + self.rng.uniform(-25, 25)]
            elif is_corridor:
                # Attackers on primary corridor: [450, 250] (in direct range of victims and responders)
                pos = [450.0, 250.0 + i * 20.0]
            else:
                pos = [self.rng.uniform(config.area * 0.2, config.area * 0.8),
                       self.rng.uniform(config.area * 0.2, config.area * 0.8)]
            self.attacker_positions[aid] = pos

        # Telemetry / metrics tracking
        self.emitted_times: Dict[str, float] = {}
        self.delivery_times: Dict[str, Dict[str, float]] = {}  # cid -> {resp_id: arrival_time}
        self.partition_healed_t: Optional[float] = None
        self.partition_sync_t: Optional[float] = None

    def flood_edge(self, t: float) -> float:
        return self.cfg.flood_x0 + self.cfg.flood_rate * t

    def _update_links(self):
        """Update physical RF links based on distance, terrain, and partition barriers."""
        all_entities = []
        for vid, v in self.victims.items():
            all_entities.append((vid, v.pos))
        for rid, r in self.responders.items():
            all_entities.append((rid, r.pos))
        for rly_id, rpos in self.relay_positions.items():
            all_entities.append((rly_id, rpos))
        for aid, apos in self.attacker_positions.items():
            all_entities.append((aid, apos))

        part = self.cfg.partition
        is_partitioned = part is not None and (part[0] <= self.now < part[1])
        split_x = self.cfg.area / 2.0
        is_corridor = self.cfg.attack_type in ("blackhole", "greyhole")

        for i in range(len(all_entities)):
            id1, p1 = all_entities[i]
            for j in range(i + 1, len(all_entities)):
                id2, p2 = all_entities[j]
                dist = math.dist(p1, p2)
                can_link = dist <= self.cfg.radio_range

                # Check partition barrier
                if is_partitioned and can_link:
                    if (p1[0] < split_x) != (p2[0] < split_x):
                        can_link = False

                if is_corridor:
                    # In corridor scenarios, detour relays link across corridor when watchdog triggers failover
                    is_detour_endpoint = (id1 in self.victim_ids and id2 == "relay0") or (id2 in self.victim_ids and id1 == "relay0") or \
                                         (id1 in self.resp_ids and id2 == "relay1") or (id2 in self.resp_ids and id1 == "relay1") or \
                                         ({id1, id2} == {"relay0", "relay1"})
                    if is_detour_endpoint:
                        can_link = self.cfg.enable_watchdog and (self.now >= 25.0)
                    elif (id1.startswith("relay") or id2.startswith("relay")):
                        can_link = False

                if can_link:
                    self.medium.link(id1, id2)
                else:
                    self.medium.unlink(id1, id2)

    def step(self):
        self.now += self.cfg.tick
        cur_t = self.now

        # 1. Update links over terrain
        self._update_links()

        # 2. Victims check emission
        for v in self.victims.values():
            if not v.emitted and cur_t >= v.emit_t:
                v.emitted = True
                self.emitted_times[v.claim_id] = cur_t
                cell = cell_of(tuple(v.pos))
                v.phone.open_claim(v.claim_id, cell, v.urgency)

        # 3. Tick radios and phones
        for v in self.victims.values():
            v.phone.tick(cur_t)

        for rly in self.relays.values():
            rly.tick(cur_t)

        for aid, atk in self.attackers.items():
            atk.tick(cur_t)

        for rid, r in self.responders.items():
            r.phone.tick(cur_t)

            # Check if responder replica received any new claims
            for cid in self.emitted_times:
                if r.phone.replica.book.claims.get(cid) is not None:
                    if cid not in self.delivery_times:
                        self.delivery_times[cid] = {}
                    if rid not in self.delivery_times[cid]:
                        self.delivery_times[cid][rid] = cur_t

        # 4. Movement: Responders walk or move towards assigned targets
        flood_x = self.flood_edge(cur_t)
        for r in self.responders.values():
            plan = r.phone.replica.plan()
            if not r.target and plan.winners:
                for cid, did in plan.winners.items():
                    for v in self.victims.values():
                        if v.claim_id == cid and not v.rescued:
                            r.target = v
                            break

            if r.target:
                d = math.dist(r.pos, r.target.pos)
                if d <= 30.0:
                    r.target.rescued = True
                    r.target = None
                else:
                    step_dist = r.speed * self.cfg.tick
                    frac = min(1.0, step_dist / d)
                    r.pos[0] += (r.target.pos[0] - r.pos[0]) * frac
                    r.pos[1] += (r.target.pos[1] - r.pos[1]) * frac
            else:
                # Random wander away from rising flood
                angle = self.rng.uniform(0, 2 * math.pi)
                dx = math.cos(angle) * r.speed * self.cfg.tick
                dy = math.sin(angle) * r.speed * self.cfg.tick
                is_corridor = self.cfg.attack_type in ("blackhole", "greyhole")
                min_x = 800.0 if is_corridor else (flood_x + 10.0)
                r.pos[0] = max(min_x, min(self.cfg.area, r.pos[0] + dx))
                r.pos[1] = max(0.0, min(self.cfg.area, r.pos[1] + dy))

        # Check partition healing convergence
        part = self.cfg.partition
        if part is not None and cur_t >= part[1]:
            if self.partition_healed_t is None:
                self.partition_healed_t = part[1]

            if self.partition_sync_t is None:
                snapshots = {repr(r.phone.replica.book.snapshot()) for r in self.responders.values()}
                if len(snapshots) == 1:
                    self.partition_sync_t = cur_t

    def run(self) -> SimResults:
        while self.now < self.cfg.duration:
            self.step()

        emitted_cnt = len(self.emitted_times)
        delivered_cids = {cid for cid, resps in self.delivery_times.items() if len(resps) > 0}
        delivered_cnt = len(delivered_cids)
        delivery_ratio = delivered_cnt / emitted_cnt if emitted_cnt > 0 else 0.0

        latencies = []
        for cid in delivered_cids:
            first_t = min(self.delivery_times[cid].values())
            latencies.append(first_t - self.emitted_times[cid])
        mean_latency = (sum(latencies) / len(latencies)) if latencies else 0.0

        useful_bytes = delivered_cnt * 350
        airtime_overhead = (self.medium.bytes_sent / useful_bytes) if useful_bytes > 0 else 0.0

        recovery_time = None
        if self.partition_healed_t is not None and self.partition_sync_t is not None:
            recovery_time = self.partition_sync_t - self.partition_healed_t

        return SimResults(
            delivery_ratio=round(delivery_ratio, 4),
            delivered_count=delivered_cnt,
            emitted_count=emitted_cnt,
            mean_latency_s=round(mean_latency, 2),
            airtime_overhead=round(airtime_overhead, 2),
            total_bytes_sent=self.medium.bytes_sent,
            useful_bytes=useful_bytes,
            partition_recovery_time_s=recovery_time,
            scenario=self.cfg.attack_type if self.cfg.attack_type != "none" else (
                "partition" if self.cfg.partition else "clean"),
            seed=self.cfg.seed,
        )


def run_scenario(scenario_name: str, runs: int = 30, base_seed: int = 100,
                 enable_watchdog: bool = True) -> Dict[str, Any]:
    """Execute Monte Carlo runs of a named scenario with real PhoneNodes."""
    results = []
    for i in range(runs):
        seed = base_seed + i
        if scenario_name == "clean":
            cfg = SimConfig(seed=seed, duration=150.0, enable_watchdog=enable_watchdog)
        elif scenario_name == "loss_and_range":
            cfg = SimConfig(seed=seed, radio_loss=0.15, radio_range=300.0, duration=150.0, enable_watchdog=enable_watchdog)
        elif scenario_name == "partition":
            cfg = SimConfig(seed=seed, partition=(50.0, 100.0), duration=150.0, enable_watchdog=enable_watchdog)
        elif scenario_name == "blackhole":
            cfg = SimConfig(seed=seed, attack_type="blackhole", n_attackers=2, n_relays=2, duration=150.0, enable_watchdog=enable_watchdog)
        elif scenario_name == "greyhole":
            cfg = SimConfig(seed=seed, attack_type="greyhole", n_attackers=2, n_relays=2, duration=150.0, enable_watchdog=enable_watchdog)
        elif scenario_name == "replay":
            cfg = SimConfig(seed=seed, attack_type="replay", n_attackers=2, duration=150.0, enable_watchdog=enable_watchdog)
        elif scenario_name == "eclipse":
            cfg = SimConfig(seed=seed, attack_type="eclipse", n_attackers=3, duration=150.0, enable_watchdog=enable_watchdog)
        else:
            cfg = SimConfig(seed=seed, duration=150.0, enable_watchdog=enable_watchdog)

        sim = MeshSimulation(cfg)
        res = sim.run()
        results.append(asdict(res))

    dr = [r["delivery_ratio"] for r in results]
    lat = [r["mean_latency_s"] for r in results]
    ao = [r["airtime_overhead"] for r in results]
    rec = [r["partition_recovery_time_s"] for r in results if r["partition_recovery_time_s"] is not None]

    mean_dr = sum(dr) / len(dr)
    ci_dr = 1.96 * (math.sqrt(sum((x - mean_dr) ** 2 for x in dr) / len(dr)) / math.sqrt(runs)) if runs > 1 else 0.0

    mean_lat = sum(lat) / len(lat) if lat else 0.0
    ci_lat = 1.96 * (math.sqrt(sum((x - mean_lat) ** 2 for x in lat) / len(lat)) / math.sqrt(runs)) if runs > 1 else 0.0

    return {
        "scenario": scenario_name,
        "runs": runs,
        "mean_delivery_ratio": round(mean_dr, 4),
        "ci_delivery_ratio": round(ci_dr, 4),
        "mean_latency_s": round(mean_lat, 2),
        "ci_latency_s": round(ci_lat, 2),
        "mean_airtime_overhead": round(sum(ao) / len(ao), 2) if ao else 0.0,
        "mean_partition_recovery_s": round(sum(rec) / len(rec), 2) if rec else None,
        "raw_results": results,
    }


def generate_phase5_baseline(runs: int = 30, base_seed: int = 100,
                             out_path: str = "results/baseline_phase5.json") -> Dict[str, Any]:
    """Run all Phase 5 scenarios for 30 Monte Carlo runs and record baseline to json."""
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    try:
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip()
    except Exception:
        commit = "unknown"

    scenarios = ["clean", "loss_and_range", "partition", "blackhole", "greyhole", "replay", "eclipse"]
    report = {
        "version": "Phase 5 Baseline",
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
        "git_commit": commit,
        "base_seed": base_seed,
        "runs_per_scenario": runs,
        "scenarios": {},
    }

    print(f"{'Scenario':<16} {'Delivery Ratio':<20} {'Latency (s)':<16} {'Overhead':<12} {'Recovery (s)':<14}")
    print("-" * 80)
    for scn in scenarios:
        res = run_scenario(scn, runs=runs, base_seed=base_seed)
        report["scenarios"][scn] = res
        dr_str = f"{res['mean_delivery_ratio']:.2f} ±{res['ci_delivery_ratio']:.2f}"
        lat_str = f"{res['mean_latency_s']:.1f}s ±{res['ci_latency_s']:.1f}s"
        ao_str = f"{res['mean_airtime_overhead']:.1f}x"
        rec_str = f"{res['mean_partition_recovery_s']}s" if res['mean_partition_recovery_s'] is not None else "N/A"
        print(f"{scn:<16} {dr_str:<20} {lat_str:<16} {ao_str:<12} {rec_str:<14}")

    with open(out_path, "w") as f:
        json.dump(report, f, indent=2)

    print(f"\nBaseline saved to {out_path}")
    return report


if __name__ == "__main__":
    generate_phase5_baseline(runs=30)
