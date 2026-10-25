"""Radio profiles and regulatory duty-cycle / airtime budget management.

Provides:
  1. LoRaProfile: 868 MHz EU band with regulatory 1% duty-cycle limit (ETSI EN 300 220),
     accurate Semtech airtime calculation formula for SF7-SF12, BW125-BW500.
  2. BLEProfile: 2.4 GHz 1 Mbps profile with 100% duty-cycle allowance.
  3. AirtimeTracker: Sliding-window duty cycle enforcement with Priority.DISTRESS
     emergency bypass and M6 battery energy expenditure calculation.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

from core.types import Priority


@dataclass(frozen=True)
class RadioProfile:
    name: str
    freq_mhz: float
    bandwidth_hz: int
    mtu: int
    duty_cycle_limit: float   # e.g. 0.01 for 1%
    tx_power_dbm: float = 14.0

    def calc_airtime_s(self, payload_bytes: int) -> float:
        raise NotImplementedError


class LoRaProfile(RadioProfile):
    """Sub-GHz LoRa modulation (Semtech SX126x/SX127x standard)."""

    def __init__(self, name: str = "LoRa-EU868", freq_mhz: float = 868.1,
                 bandwidth_hz: int = 125_000, spreading_factor: int = 7,
                 coding_rate: int = 5, preamble_len: int = 8,
                 tx_power_dbm: float = 14.0):
        super().__init__(
            name=name,
            freq_mhz=freq_mhz,
            bandwidth_hz=bandwidth_hz,
            mtu=255,
            duty_cycle_limit=0.01,  # 1% ETSI regulatory cap (36s per hour)
            tx_power_dbm=tx_power_dbm,
        )
        self.sf = spreading_factor
        self.cr = coding_rate  # 5 => 4/5, 6 => 4/6, etc.
        self.preamble_len = preamble_len

    def calc_airtime_s(self, payload_bytes: int) -> float:
        """Exact LoRa packet airtime formula in seconds."""
        t_sym = (2 ** self.sf) / self.bandwidth_hz
        t_preamble = (self.preamble_len + 4.25) * t_sym

        pl = max(0, min(payload_bytes, 255))
        h = 0  # 0 for explicit header, 1 for implicit
        de = 1 if (self.sf >= 11 and self.bandwidth_hz <= 125_000) else 0

        # Semtech payload symbol calculation
        term = math.ceil((8 * pl - 4 * self.sf + 28 + 16 - 20 * h) / (4 * (self.sf - 2 * de)))
        n_payload = 8 + max(0, term * (self.cr))

        t_payload = n_payload * t_sym
        return t_preamble + t_payload


class BLEProfile(RadioProfile):
    """Bluetooth Low Energy 2.4 GHz PHY."""

    def __init__(self, name: str = "BLE-1M", freq_mhz: float = 2402.0,
                 tx_power_dbm: float = 4.0):
        super().__init__(
            name=name,
            freq_mhz=freq_mhz,
            bandwidth_hz=1_000_000,
            mtu=255,
            duty_cycle_limit=1.0,  # No regulatory sub-GHz duty cycle
            tx_power_dbm=tx_power_dbm,
        )

    def calc_airtime_s(self, payload_bytes: int) -> float:
        # Preamble (1B) + Access Address (4B) + Header (2B) + Payload + CRC (3B)
        total_bits = (payload_bytes + 10) * 8
        return total_bits / self.bandwidth_hz


class AirtimeTracker:
    """Tracks airtime usage over a rolling time window and enforces duty cycle limits."""

    def __init__(self, profile: RadioProfile, window_s: float = 3600.0):
        self.profile = profile
        self.window_s = window_s
        self.history: List[Tuple[float, float]] = []  # (timestamp, airtime_s)
        self.total_tx_bytes = 0
        self.total_airtime_s = 0.0

    def _purge(self, now: float):
        cutoff = now - self.window_s
        while self.history and self.history[0][0] < cutoff:
            self.history.pop(0)

    def window_airtime(self, now: float) -> float:
        """Cumulative airtime consumed in the current rolling window."""
        self._purge(now)
        return sum(a for _, a in self.history)

    def current_duty_cycle(self, now: float) -> float:
        """Fraction of window airtime utilized."""
        used = self.window_airtime(now)
        effective_window = min(self.window_s, max(1.0, now))
        return used / effective_window

    def can_transmit(self, payload_bytes: int, now: float,
                     priority: Priority = Priority.CHATTER) -> bool:
        """Check if transmitting payload_bytes complies with duty-cycle budget.
        Priority.DISTRESS bypasses duty cycle cap for life-safety emergency."""
        if priority == Priority.DISTRESS:
            return True

        if self.profile.duty_cycle_limit >= 1.0:
            return True

        self._purge(now)
        new_airtime = self.profile.calc_airtime_s(payload_bytes)
        allowed_airtime = self.window_s * self.profile.duty_cycle_limit
        used_airtime = sum(a for _, a in self.history)

        return (used_airtime + new_airtime) <= allowed_airtime

    def record_tx(self, payload_bytes: int, now: float) -> float:
        """Record transmission, update history, and return airtime consumed in seconds."""
        airtime = self.profile.calc_airtime_s(payload_bytes)
        self.history.append((now, airtime))
        self.total_tx_bytes += payload_bytes
        self.total_airtime_s += airtime
        return airtime

    def energy_drain_mah(self, airtime_s: float, supply_voltage: float = 3.3) -> float:
        """Calculate battery energy drained by transmission based on radio TX power."""
        # SX1262 typical TX current: ~45 mA at +14 dBm (3.3V)
        # BLE typical TX current: ~10 mA at +4 dBm
        p_dbm = self.profile.tx_power_dbm
        tx_current_ma = 45.0 if p_dbm >= 10 else 10.0
        # Energy = Current (mA) * time (hours)
        drain_mah = tx_current_ma * (airtime_s / 3600.0)
        return drain_mah
