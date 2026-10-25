"""Low battery as an urgency feature, power modes, and the one-shot last-gasp packet."""
from __future__ import annotations

import math
from enum import IntEnum


class Mode(IntEnum):
    NORMAL = 0
    SAVER = 1
    BEACON = 2      # duty-cycled beacon only, no relaying


DUTY = {Mode.NORMAL: 1.0, Mode.SAVER: 0.5, Mode.BEACON: 0.1}
SAVER_AT, BEACON_AT, LAST_GASP_AT = 0.30, 0.15, 0.05


def mode(level: float) -> Mode:
    return Mode.NORMAL if level > SAVER_AT else Mode.SAVER if level > BEACON_AT else Mode.BEACON


def urgency(tts_s: float, tau: float = 3600.0) -> float:
    """0..1; 1 = about to go silent. A victim at 12 % who will be silent soon outranks one at 80 %."""
    return math.exp(-max(0.0, tts_s) / tau)


def send_location_now(level: float, tts_s: float, window_s: float = 3600.0) -> bool:
    """Push the exact (M9-protected) location out early while the phone can still transmit."""
    return tts_s <= window_s or level <= BEACON_AT


class LastGasp:
    """Fires exactly once, the first time the level falls to LAST_GASP_AT or below."""

    def __init__(self):
        self.sent = False

    def should_send(self, level: float) -> bool:
        if not self.sent and level <= LAST_GASP_AT:
            self.sent = True
            return True
        return False
