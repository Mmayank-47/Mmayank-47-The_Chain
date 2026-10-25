"""Tiny Bloom filter: compact 'what do I already have' summaries for beacons."""
from __future__ import annotations

import hashlib
import math


class BloomFilter:
    def __init__(self, m_bits: int = 256, k: int = 4, bits: int = 0):
        self.m, self.k, self.bits = m_bits, k, bits

    @classmethod
    def for_capacity(cls, n: int, fp: float = 0.01) -> "BloomFilter":
        n = max(n, 16)
        m = max(64, int(-n * math.log(fp) / (math.log(2) ** 2)))
        k = max(1, round(m / n * math.log(2)))
        return cls(m, k)

    def _idx(self, item: str):
        d = hashlib.sha256(item.encode()).digest()
        return [int.from_bytes(d[4 * i:4 * i + 4], "big") % self.m for i in range(self.k)]

    def add(self, item: str):
        for i in self._idx(item):
            self.bits |= 1 << i

    def __contains__(self, item: str) -> bool:
        return all(self.bits >> i & 1 for i in self._idx(item))

    def to_json(self):
        return [self.m, self.k, format(self.bits, "x")]

    @staticmethod
    def from_json(x) -> "BloomFilter":
        return BloomFilter(int(x[0]), int(x[1]), int(x[2], 16))
