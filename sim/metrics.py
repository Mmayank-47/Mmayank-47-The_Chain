from statistics import mean


def _r(a, b):
    return a / b if b else 0.0


def summarize(w):
    V = [n for n in w.nodes if n.role == "victim"]
    R = [n for n in w.nodes if n.role == "responder"]
    D, nd = w.dispatches, len(w.dispatches)
    ev = lambda k: sum(e[1] == k for e in w.events)
    fake = [c for c in w.claims if not c.real]
    rescued = [v for v in V if v.rescued is not None]

    first, per_victim = {}, {}   # real dispatches only
    for d in D:
        if d.claim.real:
            first.setdefault(d.claim.victim, d.t)
            per_victim.setdefault(d.claim.victim, set()).add(d.rid)
    ttd = [first[v.id] - v.t0 for v in V if v.id in first]
    real_d = sum(d.claim.real for d in D)

    return {
        "rescued": _r(len(rescued), len(V)),
        "died": _r(sum(v.died is not None for v in V), len(V)),
        "time_to_dispatch": mean(ttd) if ttd else 0.0,
        "time_to_rescue": mean(v.rescued - v.t0 for v in rescued) if rescued else 0.0,
        "fake_dispatch": _r(sum(not d.claim.real for d in D), nd),     # share of dispatches wasted on fakes
        "attacker_success": _r(sum(c.id in w.dispatched for c in fake), len(fake)),
        "unsafe": _r(ev("unsafe"), nd),
        "double_dispatch": _r(sum(len(s) - 1 for s in per_victim.values()), real_d),
        "medic_misuse": _r(sum(d.misuse for d in D), nd),
        "battery_used": mean(1 - n.battery for n in R) if R else 0.0,
        "_pairs": [(d.p_valid, d.claim.real) for d in D],
    }


def reliability(pairs, bins=5):
    """Calibration table: (mean predicted p_valid, observed fraction real, n) per bin."""
    out = []
    for b in range(bins):
        lo, hi = b / bins, (b + 1) / bins
        grp = [(p, y) for p, y in pairs if lo <= p < hi or (b == bins - 1 and p == 1.0)]
        if grp:
            out.append((mean(p for p, _ in grp), mean(float(y) for _, y in grp), len(grp)))
    return out
