package org.thechain.matching

enum class Category(val wire: String) {
    IMMEDIATE("immediate"),
    DELAYED("delayed"),
    MINOR("minor"),
    EXPECTANT("expectant"),
    UNKNOWN("unknown"),
}

/** (urgency, survival gain). EXPECTANT stays positive so a free responder can still serve it. */
val SCORES: Map<Category, Pair<Double, Double>> = mapOf(
    Category.IMMEDIATE to (1.0 to 0.8),
    Category.DELAYED to (0.5 to 0.5),
    Category.MINOR to (0.15 to 0.2),
    Category.EXPECTANT to (0.3 to 0.05),
    Category.UNKNOWN to (0.5 to 0.5),
)

data class Features(
    val canWalk: Boolean? = null,
    val breathing: Boolean? = null,
    val respRate: Double? = null,
    val perfusionOk: Boolean? = null,
    val obeysCommands: Boolean? = null,
)

fun classify(f: Features): Category {
    if (f.canWalk == true) return Category.MINOR
    if (f.breathing == false) return Category.EXPECTANT
    if (f.respRate != null && f.respRate > 30.0) return Category.IMMEDIATE
    if (f.perfusionOk == false || f.obeysCommands == false) return Category.IMMEDIATE
    val known = listOf(f.canWalk, f.breathing, f.respRate, f.perfusionOk, f.obeysCommands)
    if (known.count { it != null } < 3) return Category.UNKNOWN
    return Category.DELAYED
}

/** category, urgency, survival gain */
fun scores(f: Features): Triple<Category, Double, Double> {
    val category = classify(f)
    val (urgency, gain) = SCORES.getValue(category)
    return Triple(category, urgency, gain)
}
