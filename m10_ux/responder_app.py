"""Responder-side prompt state machine. Accept / decline / "can't, but I know X" feeds back into M7.

Icon names let a low-literacy UI draw the three choices without text. Only the prompted responder
can answer, each prompt is answered once, and an expired prompt cannot be answered (M7's escalation
timer has already moved on).
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple

from m9_privacy.sealed_envelope import BoxKey

from .justify import Justification

ACCEPT, DECLINE, REFER = "accept", "decline", "refer"
REASONS = ("too_far", "unsafe", "busy", "low_battery", "other")
ICONS = {ACCEPT: "check", DECLINE: "cross", REFER: "person"}


@dataclass(frozen=True)
class Prompt:
    pid: str
    case_id: str
    responder: str
    kind: str
    text: str
    risk: str
    abort: str
    choices: Tuple[Tuple[str, str], ...]
    shown_at: float
    expires_at: float


@dataclass(frozen=True)
class Response:
    pid: str
    case_id: str
    responder: str
    action: str
    t: float
    reason: Optional[str] = None
    referral: Optional[str] = None


class ResponderApp:
    def __init__(self, me: str, ack_timeout: float = 120.0, box: Optional[BoxKey] = None):
        self.me, self.timeout, self.box = me, ack_timeout, box
        self._open: Dict[str, Prompt] = {}
        self._n = 0
        self.on_accept: List[Callable[[Response], None]] = []   # e.g. M9 Discloser.accept + release

    def show(self, case_id: str, j: Justification, now: float) -> Prompt:
        self._n += 1
        p = Prompt(f"{case_id}|{self.me}|{self._n}", case_id, self.me, j.kind, j.text, j.risk, j.abort,
                   tuple((a, ICONS[a]) for a in (ACCEPT, DECLINE, REFER)), now, now + self.timeout)
        self._open[p.pid] = p
        return p

    def pending(self, now: float) -> List[Prompt]:
        return [p for p in self._open.values() if now < p.expires_at]

    def respond(self, pid: str, action: str, now: float, reason: Optional[str] = None,
                referral: Optional[str] = None) -> Response:
        p = self._open.get(pid)
        if p is None:
            raise ValueError("unknown or already answered prompt")
        if now >= p.expires_at:
            del self._open[pid]
            raise ValueError("prompt expired")
        if action not in ICONS:
            raise ValueError(f"bad action {action!r}")
        if action == REFER and (not referral or referral == self.me):
            raise ValueError("referral needs someone other than you")
        if action == DECLINE and reason is not None and reason not in REASONS:
            raise ValueError(f"bad reason {reason!r}")
        del self._open[pid]
        r = Response(pid, p.case_id, self.me, action, now, reason if action == DECLINE else None,
                     referral if action == REFER else None)
        if action == ACCEPT:
            for cb in self.on_accept:
                cb(r)
        return r

    def open_details(self, blob: bytes):
        """Open the stage-2 envelope (M9). None if it is not ours."""
        return self.box.open(blob) if self.box else None


def apply(escalation, r: Response, now: float) -> Optional[str]:
    """Feed a response into an M7 Escalation. Returns the responder prompted next, if any."""
    if r.action == ACCEPT:
        escalation.ack(r.responder, now)
        return None
    if r.action == DECLINE:
        return escalation.decline(r.responder, now)
    return escalation.refer(r.responder, r.referral, now)
