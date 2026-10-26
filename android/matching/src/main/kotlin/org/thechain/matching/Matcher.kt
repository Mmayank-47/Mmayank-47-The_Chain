package org.thechain.matching

fun buildUtilities(
    claims: Iterable<Claim>,
    responders: Iterable<Responder>,
    routeFn: (Responder, Claim) -> Pair<Double, Double>?,
    reachFn: (Responder, Double) -> Double,
    assigned: Set<String> = emptySet(),
    extraDemand: Map<String, Double>? = null,
    lossValue: Double = 1.0,
    speed: Double = 1.5,
    horizon: Double = 3600.0,
    timeWeight: Double = 0.3,
): Pair<Map<String, Map<String, Double>>, Map<Pair<String, String>, String>> {
    val open = claims.filter { it.id !in assigned }
    val rs = responders.toList()
    val price = prices(rs, open, assigned, extraDemand)
    val util = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    for (r in rs) util[r.id] = LinkedHashMap()
    val decisions = LinkedHashMap<Pair<String, String>, String>()
    for (r in rs) {
        for (c in open) {
            if (!c.need.isNullOrEmpty() && c.need !in r.skills) continue
            val route = routeFn(r, c)
            if (route == null) {
                decisions[r.id to c.id] = "no_route"
                continue
            }
            val (length, pSafe) = route
            val pReach = reachFn(r, length)
            val decision = gate(c.pValid, pSafe, c.trust, pReach)
            decisions[r.id to c.id] = decision
            if (decision != "dispatch") continue
            val u = pairUtility(
                c,
                r,
                length,
                pSafe,
                pReach,
                oppCost(r, c, price),
                lossValue,
                speed,
                horizon,
                timeWeight,
            )
            if (u > 0.0) util.getValue(r.id)[c.id] = u
        }
    }
    return util to decisions
}

/** Deterministic responder order for one claim. Higher utility first, then responder id. */
fun ranking(utilities: Map<String, Map<String, Double>>, claimId: String): List<String> =
    utilities
        .mapNotNull { (responder, bids) -> bids[claimId]?.let { responder to it } }
        .sortedWith(compareBy({ -it.second }, { it.first }))
        .map { it.first }
