package org.thechain.matching

import kotlin.math.min

const val CRITICAL = 0.7

fun expectedDemand(
    claims: Iterable<Claim>,
    skill: String,
    assigned: Set<String> = emptySet(),
    critical: Double = CRITICAL,
): Double = claims.sumOf { c ->
    if (c.need == skill && c.urgency >= critical && c.id !in assigned) c.pValid else 0.0
}

/**
 * price(skill) = value * min(1, expected demand / idle supply).
 * [extraDemand] is expected arrivals not yet seen.
 */
fun prices(
    responders: Iterable<Responder>,
    claims: Iterable<Claim>,
    assigned: Set<String> = emptySet(),
    extraDemand: Map<String, Double>? = null,
    value: Double = 0.6,
): Map<String, Double> {
    val claimList = claims.toList()
    val rs = responders.toList()
    val skills = LinkedHashSet<String>()
    for (r in rs) skills.addAll(r.skills)
    val out = LinkedHashMap<String, Double>()
    for (s in skills) {
        val supply = rs.count { s in it.skills }
        val demand = expectedDemand(claimList, s, assigned) + (extraDemand?.get(s) ?: 0.0)
        out[s] = value * min(1.0, demand / supply)
    }
    return out
}

/** Using a skill the claim needs is free. Every other skill on the responder costs its price. */
fun oppCost(r: Responder, c: Claim, price: Map<String, Double>): Double =
    r.skills.sumOf { s -> if (s != c.need) price[s] ?: 0.0 else 0.0 }
