"""Ephemeral rotating pseudonyms and BLE addresses derived from a per-device seed.

Each epoch gets a fresh id and a fresh locally-administered random-looking BLE address. Without the
seed, ids from different epochs cannot be linked. Only the owner can prove an id was theirs.
"""
from __future__ import annotations

import hashlib
import hmac


class PseudonymChain:
    def __init__(self, seed: bytes, epoch_s: float = 600.0):
        if epoch_s <= 0:
            raise ValueError("epoch_s must be positive")
        self._seed, self.epoch_s = seed, epoch_s

    def epoch_of(self, t: float) -> int:
        return int(t // self.epoch_s)

    def _h(self, label: bytes, epoch: int) -> bytes:
        return hmac.new(self._seed, label + b"|" + str(epoch).encode(), hashlib.sha256).digest()

    def pid(self, t: float) -> str:
        return self._h(b"pid", self.epoch_of(t))[:6].hex()

    def ble_addr(self, t: float) -> str:
        b = bytearray(self._h(b"ble", self.epoch_of(t))[:6])
        b[0] = (b[0] & 0xFC) | 0x02                  # unicast, locally administered
        return ":".join(f"{x:02x}" for x in b)

    def owns(self, pid: str, t: float, slack: int = 1) -> bool:
        e = self.epoch_of(t)
        return any(hmac.compare_digest(self._h(b"pid", k)[:6].hex(), pid) for k in range(e - slack, e + slack + 1))
