from dataclasses import dataclass
from typing import Optional, Tuple


@dataclass(frozen=True)
class Scenario:
    seed: int = 0
    area: float = 2000.0            # square side, metres
    n_responders: int = 30
    medic_frac: float = 0.2
    n_victims: int = 20
    n_attackers: int = 0
    attack: str = "none"            # none | decoy | lure | sybil | spoof
    attack_claims: int = 5          # fake claims per attacker
    sybil_ids: int = 10             # fake identities per sybil claim
    duration: float = 3600.0        # seconds
    tick: float = 10.0
    radio_range: float = 400.0
    flood_x0: float = 200.0         # water edge at t=0 (water covers x < edge)
    flood_rate: float = 0.15        # m/s, only ever rises
    gps_sigma: float = 15.0
    partition: Optional[Tuple[float, float]] = None   # (start, end): wall at x=area/2
