"""Message-kind router: replaces the single `engine.on_deliver` callback.

M0 hands every accepted Message to one callable. A Dispatcher is that callable: each layer registers
for the kinds it owns, a handler that raises cannot starve the others (or the gossip engine), and
anything nobody claimed is counted in `stats` instead of vanishing.
"""
from __future__ import annotations

from collections import Counter
from typing import Callable, Dict, Iterable, List

from .types import Message

# Must match m0_transport.ingress.KIND_PRIORITY (a test pins this).
KINDS = ("m8.event", "m9.case", "m9.sealed", "m1.revoke", "m3.challenge", "m3.response",
         "m4.hazard", "m5.vouch")

Handler = Callable[[Message], None]


class Dispatcher:
    def __init__(self, kinds: Iterable[str] = KINDS):
        self.kinds = frozenset(kinds)
        self._h: Dict[str, List[Handler]] = {k: [] for k in self.kinds}
        self.stats: Counter = Counter()

    def register(self, kind: str, handler: Handler) -> Handler:
        if kind not in self.kinds:
            raise ValueError(f"unknown message kind {kind!r}")
        self._h[kind].append(handler)
        return handler

    def on(self, kind: str):
        """Decorator form of register()."""
        return lambda handler: self.register(kind, handler)

    def __call__(self, msg: Message) -> int:
        """Deliver to every handler of msg.kind; returns how many ran without raising."""
        kind = getattr(msg, "kind", None)
        handlers = self._h.get(kind)
        if not handlers:
            self.stats["unrouted"] += 1
            return 0
        ok = 0
        for h in list(handlers):
            try:
                h(msg)
                ok += 1
            except Exception:                       # one layer's bug must not stop the others
                self.stats[f"error:{kind}"] += 1
        self.stats["delivered"] += ok
        return ok
