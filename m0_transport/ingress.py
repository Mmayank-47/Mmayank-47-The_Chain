"""M0 ingress guard: everything that arrives off the air is hostile until it passes here.

What it enforces (all before the gossip engine or any higher layer sees the message):
  * Kind allowlist. Unknown kinds are dropped.
  * Priority is DERIVED from the kind, never read from the wire. A sender cannot promote chatter
    to DISTRESS to jump the outbox queue and evict honest traffic from the bounded CarryStore.
  * TTL is clamped to (0, max_ttl_s]; `created` can not be in the future (a future `created`
    would make a message immortal); origin / dest_cell are bounded strings; payload is a dict
    and the whole wire form is size-capped.
  * Hello beacons: the Bloom summary must have a sane shape (bit count, hash count, hex length),
    otherwise `int(hex, 16)` on an attacker-sized string is a memory/CPU bomb. Cell and battery
    fields are type-checked and clamped.
  * Raw frame size is capped before JSON parsing.
  * Optional per-sender budget via any object with allow(node, now, cost) -> bool
    (m1_identity.ratelimit.IdentityLimiter in production). The sender is the radio neighbour, the
    only identity M0 can attribute a frame to; claimed `origin` is not trusted.
  * Anti-entropy frames (`ae.sum`, `ae.ev`) are the only frame types besides hello / data that
    cross the guard: ae_frame() checks type, size, container shape and the sender budget.
  * Hazards and vouches have no message kind of their own: they are M8 events riding m8.event.

Drops are counted per reason in `guard.dropped` (feeds the D5 metrics).
"""
from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass
from typing import Any, Callable, Dict, Optional, Protocol, Set, Tuple

from core import crypto
from core.bloom import BloomFilter
from core.types import Message, Priority, canonical, event_priority

# Allowlist: kind -> priority. None = derived from the payload (see _m8_priority).
# Kinds are string literals on purpose: M0 must not import the layers above it.
# Hazards and vouches are NOT here: they are M8 events (kinds hazard / vouch) and ride m8.event.
KIND_PRIORITY: Dict[str, Optional[Priority]] = {
    "m8.event": None,
    "m9.case": Priority.DISTRESS,
    "m9.sealed": Priority.DISPATCH,
    "m1.revoke": Priority.DISPATCH,
    "m3.challenge": Priority.DISPATCH,
    "m3.response": Priority.DISPATCH,
}

# Frame types besides hello / data that may cross the guard (anti-entropy, M8). Literals for the
# same reason as above; tests/test_hygiene.py pins them to what m8_merge.anti_entropy really sends.
AE_SUM, AE_EVENTS = "ae.sum", "ae.ev"
AE_FRAMES = frozenset({AE_SUM, AE_EVENTS})


def _m8_priority(payload: dict) -> Priority:
    """Same rule as m8_merge.bridge.to_message: both call core.types.event_priority."""
    return event_priority(payload.get("k"))


class Limiter(Protocol):
    def allow(self, node: str, now: float, cost: float = 1.0) -> bool: ...


class ReplayWindow:
    """Sliding replay window for a single sender (IPsec RFC 4303 style)."""

    def __init__(self, size: int = 64):
        self.size = size
        self.max_seq = 0
        self._seen: Set[int] = set()

    def check_and_add(self, seq: int) -> Tuple[bool, str]:
        """Returns (accepted: bool, reason: str)."""
        if not isinstance(seq, int) or isinstance(seq, bool) or seq <= 0:
            return False, "invalid_seq"
        if seq > self.max_seq:
            self.max_seq = seq
            self._seen.add(seq)
            cutoff = self.max_seq - self.size
            self._seen = {s for s in self._seen if s > cutoff}
            return True, "ok"
        diff = self.max_seq - seq
        if diff >= self.size:
            return False, "out_of_window"
        if seq in self._seen:
            return False, "replayed"
        self._seen.add(seq)
        return True, "ok"


@dataclass(frozen=True)
class IngressLimits:
    max_ttl_s: float = 24 * 3600.0
    max_wire_bytes: int = 2048          # one Message.wire_bytes(); a sealed M9 packet is ~1.2 KB
    max_frame_bytes: int = 4096         # raw data frame incl. framing
    max_hello_bytes: int = 8192
    max_bloom_bits: int = 16384
    max_bloom_hashes: int = 16
    min_bloom_bits: int = 64
    max_origin_len: int = 64
    max_cell_len: int = 32
    max_ae_bytes: int = 16384           # one anti-entropy frame (a 32-event batch is ~10 KB of JSON)
    max_ae_nodes: int = 512             # ae.sum version-vector entries (AntiEntropy.max_nodes)
    max_ae_events: int = 64             # ae.ev events per frame (AntiEntropy.batch defaults to 32)


class IngressGuard:
    def __init__(self, limits: Optional[IngressLimits] = None, limiter: Optional[Limiter] = None,
                 allowed: Optional[Dict[str, Optional[Priority]]] = None,
                 stranger_lane: bool = True,
                 signer: Optional[crypto.Signer] = None,
                 replay_window_size: int = 64):
        self.limits = limits or IngressLimits()
        self.limiter = limiter
        self.allowed = dict(KIND_PRIORITY if allowed is None else allowed)
        self.stranger_lane = stranger_lane
        self.signer = signer
        self.replay_window_size = replay_window_size
        self._replay_windows: Dict[str, ReplayWindow] = {}
        self.dropped: Counter = Counter()

    def _drop(self, why: str):
        self.dropped[why] += 1
        return None

    def get_replay_window(self, sender: str) -> ReplayWindow:
        if sender not in self._replay_windows:
            self._replay_windows[sender] = ReplayWindow(self.replay_window_size)
        return self._replay_windows[sender]

    def authenticate_frame(self, frm: str, f: dict) -> Optional[str]:
        """Authenticate link frame and check replay protection.

        Returns the authenticated sender ID, or None if spoofed / unauthenticated / replayed.
        """
        if self.signer is None:
            return frm
        snd = f.get("snd")
        seq = f.get("seq")
        sig = f.get("sig")
        if not (isinstance(snd, str) and snd and isinstance(seq, int) and not isinstance(seq, bool) and isinstance(sig, str)):
            return self._drop("frame_unauthenticated")
        if frm and frm != snd:
            return self._drop("frame_spoofed_sender")
        body = {k: v for k, v in f.items() if k != "sig"}
        if not self.signer.verify(snd, canonical(body), sig):
            return self._drop("frame_bad_sig")
        ok, reason = self.get_replay_window(snd).check_and_add(seq)
        if not ok:
            return self._drop("frame_" + reason)
        return snd

    # ---- raw frames -------------------------------------------------------------------------
    def frame_ok(self, data: Any) -> bool:
        """Cheap pre-parse check. Data frames, hellos and anti-entropy frames have different
        ceilings; the largest applies here and the precise ones are enforced after parsing."""
        if not isinstance(data, (bytes, bytearray)):
            self._drop("frame_type")
            return False
        L = self.limits
        if len(data) > max(L.max_frame_bytes, L.max_hello_bytes, L.max_ae_bytes):
            self._drop("frame_size")
            return False
        return True

    # ---- hello beacons ----------------------------------------------------------------------
    def hello(self, f: dict, raw_len: int) -> Optional[Tuple[BloomFilter, Optional[str], float]]:
        """-> (bloom, cell, battery) or None if the beacon is malformed or oversized."""
        L = self.limits
        if raw_len > L.max_hello_bytes:
            return self._drop("hello_size")
        bf = f.get("bf")
        if not (isinstance(bf, list) and len(bf) == 3):
            return self._drop("bloom_shape")
        m, k, hexbits = bf
        if any(isinstance(x, bool) or not isinstance(x, int) for x in (m, k)) or not isinstance(hexbits, str):
            return self._drop("bloom_types")
        if not (L.min_bloom_bits <= m <= L.max_bloom_bits and 1 <= k <= L.max_bloom_hashes):
            return self._drop("bloom_bounds")
        if len(hexbits) > m // 4 + 1:
            return self._drop("bloom_hex_len")
        try:
            bits = int(hexbits, 16)
        except ValueError:
            return self._drop("bloom_hex")
        if bits < 0 or bits.bit_length() > m:
            return self._drop("bloom_hex")
        cell = f.get("cell")
        if cell is not None and not (isinstance(cell, str) and len(cell) <= L.max_cell_len):
            return self._drop("hello_cell")
        bat = f.get("bat", 1.0)
        if isinstance(bat, bool) or not isinstance(bat, (int, float)) or not math.isfinite(bat):
            return self._drop("hello_bat")
        return BloomFilter(m, k, bits), cell, min(1.0, max(0.0, float(bat)))

    # ---- data messages ----------------------------------------------------------------------
    def admit(self, msg: Message, from_id: str, now: float) -> Optional[Message]:
        """Return the sanitised message to hand to the engine, or None to drop it."""
        L = self.limits
        if msg.kind not in self.allowed:
            return self._drop("kind_not_allowed")
        if not isinstance(msg.payload, dict):
            return self._drop("payload_type")
        if not (isinstance(msg.origin, str) and 0 < len(msg.origin) <= L.max_origin_len):
            return self._drop("origin")
        if msg.dest_cell is not None and not (isinstance(msg.dest_cell, str) and len(msg.dest_cell) <= L.max_cell_len):
            return self._drop("dest_cell")
        if len(msg.wire_bytes()) > L.max_wire_bytes:
            return self._drop("wire_size")
        ttl = msg.ttl_s
        if not (isinstance(ttl, (int, float)) and math.isfinite(ttl) and ttl > 0):
            return self._drop("ttl")
        created = msg.created
        if not (isinstance(created, (int, float)) and math.isfinite(created)):
            return self._drop("created")
        prio = self.allowed[msg.kind]
        if prio is None:
            prio = _m8_priority(msg.payload)

        if self.limiter is not None and not self.limiter.allow(from_id, now, 1.0):
            # Low-trust, low-rate stranger lane: an unenrolled sender gets distress through
            if (self.stranger_lane and prio == Priority.DISTRESS
                    and hasattr(self.limiter, "allow_stranger")
                    and self.limiter.allow_stranger(from_id, now, 1.0)):
                pass
            else:
                return self._drop("rate_limited")

        # msg_id covers kind/origin/payload/hlc only, so rewriting these fields keeps the identity
        # of the message (dedupe across honest and sanitising relays still works).
        return Message(msg.kind, prio, msg.origin, msg.payload, msg.hlc, dest_cell=msg.dest_cell,
                       ttl_s=min(float(ttl), L.max_ttl_s), created=min(float(created), now))

    # ---- anti-entropy frames ----------------------------------------------------------------
    def ae_frame(self, f: Any, raw_len: int, from_id: str, now: float) -> Optional[dict]:
        """An `ae.sum` / `ae.ev` frame from a radio neighbour -> the frame to hand to M8, or None.

        Only the outer shape is checked here (frame type, size, container type, entry count); the
        content is validated again by m8_merge.anti_entropy, which also treats it as hostile. The
        sender budget is charged before the shape checks, like admit(), so junk still costs."""
        L = self.limits
        t = f.get("t") if isinstance(f, dict) else None
        if not isinstance(t, str) or t not in AE_FRAMES:
            return self._drop("ae_type")
        if self.limiter is not None and not self.limiter.allow(from_id, now, 1.0):
            return self._drop("rate_limited")
        if raw_len > L.max_ae_bytes:
            return self._drop("ae_size")
        if t == AE_SUM:
            body, cap = f.get("s"), L.max_ae_nodes
            ok = isinstance(body, dict)
        else:
            body, cap = f.get("e"), L.max_ae_events
            ok = isinstance(body, list)
        if not ok or len(body) > cap:
            return self._drop("ae_shape")
        return f
