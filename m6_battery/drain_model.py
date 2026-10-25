"""Plausible-drain checks on battery telemetry, rate fitting and time-to-silence.

Telemetry signatures belong to M1; here we only judge whether the *curve* is believable.
Rates are fractions of full charge per second.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Tuple

MIN_RATE, MAX_RATE = 2e-6, 2e-4       # ~0.7 %/h (screen off) .. ~72 %/h (flat out)


@dataclass(frozen=True)
class Sample:
    t: float
    level: float          # 0..1
    charging: bool = False


def check(samples: List[Sample], rise_tol: float = 0.02, min_dt: float = 30.0,
          flat_span: float = 3600.0) -> Tuple[bool, List[str]]:
    why: List[str] = []
    s = sorted(samples, key=lambda x: x.t)
    if any(not 0.0 <= x.level <= 1.0 for x in s):
        why.append("level out of range")
    for a, b in zip(s, s[1:]):
        dt = b.t - a.t
        if dt <= 0:
            continue
        if b.level > a.level + rise_tol and not (a.charging or b.charging):
            why.append("level rose without charging")
        elif dt >= min_dt and (a.level - b.level) / dt > MAX_RATE * 1.5:
            why.append("drained faster than hardware allows")
    if len(s) >= 2 and s[-1].t - s[0].t >= flat_span and s[0].level - s[-1].level < 0.002 \
            and not any(x.charging for x in s):
        why.append("flat curve over a long span")
    return (not why), sorted(set(why))


def fit_rate(samples: List[Sample], default: float = 3e-5) -> float:
    """Least-squares drain rate; falls back to `default` with < 3 samples. Clamped to hardware range."""
    s = [x for x in samples if not x.charging]
    if len(s) < 3:
        return default
    n = len(s)
    mt, ml = sum(x.t for x in s) / n, sum(x.level for x in s) / n
    var = sum((x.t - mt) ** 2 for x in s)
    if var == 0:
        return default
    slope = sum((x.t - mt) * (x.level - ml) for x in s) / var
    return min(MAX_RATE, max(MIN_RATE, -slope))


def time_to_silence(level: float, rate: float) -> float:
    return max(0.0, level) / max(rate, MIN_RATE)


def battery_llr(samples: List[Sample]) -> float:
    """Signal for M3: implausible curve is strong evidence of a fake; a clean multi-sample curve is mild support."""
    if len(samples) < 2:
        return 0.0
    ok, _ = check(samples)
    return 0.5 if ok and len(samples) >= 3 else (-3.0 if not ok else 0.0)
