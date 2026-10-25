"""Bounded priority outbox: distress > dispatch > hazard > chatter, FIFO within a class."""
from __future__ import annotations

import heapq
from typing import Any, Dict, Hashable, Optional, Tuple


class PriorityOutbox:
    def __init__(self, capacity: int = 256):
        self.capacity = capacity
        self._heap: list = []
        self._items: Dict[Hashable, Tuple[int, int, Any]] = {}
        self._seq = 0

    def __len__(self):
        return len(self._items)

    def __contains__(self, item_id):
        return item_id in self._items

    def push(self, item_id: Hashable, priority: int, payload: Any = None) -> bool:
        """False if duplicate, or if full and the new item is no better than the worst queued."""
        if item_id in self._items:
            return False
        if len(self._items) >= self.capacity:
            worst_id = max(self._items, key=lambda i: (self._items[i][0], self._items[i][1]))
            if self._items[worst_id][0] <= priority:
                return False
            del self._items[worst_id]      # lazy-removed from the heap
        self._seq += 1
        self._items[item_id] = (int(priority), self._seq, payload)
        heapq.heappush(self._heap, (int(priority), self._seq, item_id))
        return True

    def pop(self) -> Optional[Any]:
        while self._heap:
            prio, seq, item_id = heapq.heappop(self._heap)
            cur = self._items.get(item_id)
            if cur is not None and cur[1] == seq:
                del self._items[item_id]
                return cur[2]
        return None
