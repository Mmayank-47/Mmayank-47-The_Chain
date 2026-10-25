"""State persistence and reboot recovery for PhoneNode.

Ensures that upon simulated device reboot (crash, battery depletion, or power cycle):
  1. Carried messages and spray tokens are preserved in CarryStore.
  2. M8 EventLog, CRDT Book, and HLC clock counters are recovered without sequence resets.
  3. M5 TrustEngine reputation scores and decay history are restored.
  4. Ingress sequence replay filters are restored to prevent replay vulnerabilities post-reboot.
"""
from __future__ import annotations

import json
import os
from typing import Any, Dict, Optional

from core.types import HLC, Message, Priority
from m8_merge.event_log import Event


class NodePersistence:
    @staticmethod
    def snapshot(phone: Any) -> dict:
        """Create a complete serializable snapshot of phone state."""
        # 1. CarryStore messages
        messages = []
        for mid, carried in phone.engine.store.items():
            messages.append({
                "mid": mid,
                "msg": carried.msg.to_wire(),
                "tok": carried.tokens,
                "received": carried.received,
                "from_id": carried.from_id,
            })

        # 2. M8 EventLog events
        events = [ev.to_json() for log in phone.replica.store.logs.values() for ev in log]

        # 3. M5 BetaRep trust scores
        rep_ev = {}
        if hasattr(phone.trust_engine, "rep") and hasattr(phone.trust_engine.rep, "_ev"):
            rep_ev = {k: list(v) for k, v in phone.trust_engine.rep._ev.items()}

        # 4. Ingress replay sequences
        replay_seqs = {}
        if hasattr(phone, "guard") and phone.guard is not None:
            if hasattr(phone.guard, "_replay_windows"):
                for nid, win in phone.guard._replay_windows.items():
                    replay_seqs[nid] = {"max_seq": win.max_seq, "bitmap": win.bitmap}

        return {
            "node_id": phone.id,
            "battery_level": getattr(phone, "battery_level", 1.0),
            "messages": messages,
            "events": events,
            "rep_ev": rep_ev,
            "replay_seqs": replay_seqs,
        }

    @staticmethod
    def save(phone: Any, filepath: str) -> None:
        """Atomically persist snapshot to disk."""
        data = NodePersistence.snapshot(phone)
        os.makedirs(os.path.dirname(os.path.abspath(filepath)), exist_ok=True)
        tmp_path = filepath + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        os.replace(tmp_path, filepath)

    @staticmethod
    def restore(phone: Any, filepath: str) -> bool:
        """Restore phone state from saved snapshot on disk."""
        if not os.path.exists(filepath):
            return False

        with open(filepath, "r", encoding="utf-8") as f:
            data = json.load(f)

        if data.get("node_id") != phone.id:
            return False

        # 1. Restore battery
        if "battery_level" in data:
            phone.battery_level = data["battery_level"]

        # 2. Restore M8 EventLog and rebuild CRDT book
        events_data = data.get("events", [])
        events = [Event.from_json(e) for e in events_data]
        for ev in events:
            for e in phone.replica.store.add(ev):
                phone.replica._applied(e)

        # 3. Restore CarryStore messages
        messages_data = data.get("messages", [])
        now = phone.now_ms() / 1000.0 if callable(phone.now_ms) else float(phone.now_ms)
        for m_entry in messages_data:
            msg = Message.from_wire(m_entry["msg"])
            tokens = m_entry.get("tok")
            from_id = m_entry.get("from_id")
            phone.engine.store.add(msg, tokens, now=now, from_id=from_id)

        # 4. Restore M5 reputation scores
        rep_ev = data.get("rep_ev", {})
        if hasattr(phone.trust_engine, "rep") and hasattr(phone.trust_engine.rep, "_ev"):
            phone.trust_engine.rep._ev = {k: tuple(v) for k, v in rep_ev.items()}

        # 5. Restore Ingress replay windows
        replay_seqs = data.get("replay_seqs", {})
        if hasattr(phone, "guard") and phone.guard is not None:
            if hasattr(phone.guard, "_replay_windows"):
                for nid, win_data in replay_seqs.items():
                    win = phone.guard.get_replay_window(nid)
                    win.max_seq = win_data["max_seq"]
                    win.bitmap = win_data["bitmap"]

        return True
