"""The signed wire claim: one format every layer converts from and to.

{id, node, src, pos, radius, t, parsed}. `node` is the physical device that signs; `src` is the
identity the claim speaks for (they differ under spoofing / sybil, which is exactly what M3's
identity_binding looks at, so the wire keeps both). `parsed` is M2's ParsedClaim.to_json().
`id` is derived from the content, so a relay cannot change a claim and keep its id, and the same
claim arriving twice dedupes by id. Adapters to M3 / M7 / M8 / sim live in core/claim_adapters.py.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from typing import Any, Dict, Tuple

from .crypto import Signer
from .types import canonical, sha256_hex

MAX_ID_LEN = 64
MAX_COORD = 1e7
MAX_RADIUS_M = 5000.0
MAX_PARSED_BYTES = 900          # keeps one claim comfortably inside M0's 2 KB wire cap


class ClaimError(ValueError):
    pass


def _finite(x) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def _check(node, src, pos, radius, t, parsed) -> None:
    for name, v in (("node", node), ("src", src)):
        if not isinstance(v, str) or not 0 < len(v) <= MAX_ID_LEN:
            raise ClaimError(f"bad {name}")
    if not (isinstance(pos, (list, tuple)) and len(pos) == 2
            and all(_finite(c) and abs(c) <= MAX_COORD for c in pos)):
        raise ClaimError("bad pos")
    if not _finite(radius) or not 0 <= radius <= MAX_RADIUS_M:
        raise ClaimError("bad radius")
    if not _finite(t) or t < 0:
        raise ClaimError("bad t")
    if not isinstance(parsed, dict):
        raise ClaimError("parsed must be an object")
    try:
        size = len(json.dumps(parsed, sort_keys=True, separators=(",", ":"), allow_nan=False))
    except (TypeError, ValueError):
        raise ClaimError("parsed is not plain JSON")
    if size > MAX_PARSED_BYTES:
        raise ClaimError("parsed too large")


def _body(node, src, pos, radius, t, parsed) -> dict:
    return {"n": node, "s": src, "p": [float(pos[0]), float(pos[1])], "r": float(radius),
            "t": float(t), "d": parsed}


@dataclass(frozen=True)
class WireClaim:
    id: str
    node: str
    src: str
    pos: Tuple[float, float]
    radius: float
    t: float
    parsed: Dict[str, Any]
    sig: str = ""

    # ---- construction ------------------------------------------------------------------------
    @staticmethod
    def make(node: str, pos, radius: float, t: float, parsed: dict, signer: Signer,
             src: str = None) -> "WireClaim":
        """Sign as `node`. `src` defaults to node (an honest claim speaks for itself)."""
        src = node if src is None else src
        _check(node, src, pos, radius, t, parsed)
        body = _body(node, src, pos, radius, t, parsed)
        cid = sha256_hex(canonical(body))[:16]
        return WireClaim(cid, node, src, (body["p"][0], body["p"][1]), body["r"], body["t"], parsed,
                         signer.sign(node, cid.encode()))

    # ---- verification ------------------------------------------------------------------------
    def derived_id(self) -> str:
        return sha256_hex(canonical(_body(self.node, self.src, self.pos, self.radius, self.t, self.parsed)))[:16]

    def verify(self, signer: Signer) -> bool:
        """True iff the id matches the content and `node` (an enrolled key) signed that id."""
        try:
            _check(self.node, self.src, self.pos, self.radius, self.t, self.parsed)
            return (isinstance(self.id, str) and self.id == self.derived_id()
                    and isinstance(self.sig, str) and signer.verify(self.node, self.id.encode(), self.sig))
        except ClaimError:
            return False

    # ---- wire --------------------------------------------------------------------------------
    def to_json(self) -> dict:
        return {"i": self.id, "n": self.node, "s": self.src, "p": list(self.pos), "r": self.radius,
                "t": self.t, "d": self.parsed, "g": self.sig}

    @staticmethod
    def from_json(j) -> "WireClaim":
        """Structural validation only; call verify() before believing it."""
        try:
            node, src, pos, radius, t, parsed = j["n"], j["s"], j["p"], j["r"], j["t"], j["d"]
            _check(node, src, pos, radius, t, parsed)
            if not isinstance(j["i"], str) or not isinstance(j["g"], str):
                raise ClaimError("bad id or signature")
            return WireClaim(j["i"], node, src, (float(pos[0]), float(pos[1])), float(radius),
                             float(t), parsed, j["g"])
        except (KeyError, TypeError) as e:
            raise ClaimError(f"malformed claim: {e!r}")
