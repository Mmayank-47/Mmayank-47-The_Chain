"""Small CRDTs. LWWMap: last-writer-wins by HLC (a total order, since HLC includes node id)."""
from __future__ import annotations

from typing import Any, Dict, Iterator, Tuple

from .types import HLC


class LWWMap:
    def __init__(self):
        self._d: Dict[str, Tuple[HLC, Any]] = {}

    def set(self, key: str, value: Any, hlc: HLC) -> bool:
        cur = self._d.get(key)
        if cur is None or hlc > cur[0]:
            self._d[key] = (hlc, value)
            return True
        return False

    def delete(self, key: str, hlc: HLC) -> bool:
        return self.set(key, None, hlc)       # tombstone, so deletes also converge

    def get(self, key: str, default=None):
        cur = self._d.get(key)
        return default if cur is None or cur[1] is None else cur[1]

    def items(self) -> Iterator[Tuple[str, Any]]:
        return ((k, v) for k, (_, v) in self._d.items() if v is not None)

    def merge(self, other: "LWWMap"):
        for k, (h, v) in other._d.items():
            self.set(k, v, h)
