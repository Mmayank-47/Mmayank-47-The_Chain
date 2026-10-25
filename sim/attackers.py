"""Red-team claim generators. Called once by World; returns Claims to inject."""
import math


def schedule(w):
    s, rng = w.scn, w.rng
    out = []
    atk = [n for n in w.nodes if n.role == "attacker"]
    victims = [n for n in w.nodes if n.role == "victim"]
    if s.attack == "none":
        return out
    for a in atk:
        for k in range(s.attack_claims):
            t = rng.uniform(0, s.duration * 0.6)
            if s.attack == "decoy":
                out.append(w.claim(a, a.id, w.dry_pos(), t, 1.0, "medic", "decoy"))
            elif s.attack == "lure":
                # just-dry now, underwater soon: responders get pulled into the rising edge
                pos = (w.flood_front(t) + rng.uniform(10, 60), rng.uniform(0, s.area))
                out.append(w.claim(a, a.id, pos, t, 1.0, "medic", "lure"))
            elif s.attack == "sybil":
                pos = w.dry_pos()   # same fake claim "corroborated" by many identities
                for j in range(s.sybil_ids):
                    out.append(w.claim(a, 10_000 + a.id * 100 + j, pos, t, 1.0, "medic", "sybil"))
            elif s.attack == "spoof" and victims:
                v = rng.choice(victims)   # impersonate a real victim, wrong location
                ang, d = rng.uniform(0, 2 * math.pi), rng.uniform(300, 600)
                pos = (v.pos[0] + d * math.cos(ang), v.pos[1] + d * math.sin(ang))
                out.append(w.claim(a, v.id, pos, v.t0 + rng.uniform(10, 120), 1.0, "medic", "spoof"))
    return out
