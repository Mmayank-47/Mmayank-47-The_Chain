package org.thechain.matching

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * CPython `round(float, ndigits)`: half-even on the exact decimal expansion of the binary value,
 * then back to the nearest double.
 */
fun pythonRound(value: Double, ndigits: Int): Double {
    require(ndigits >= 0)
    if (!value.isFinite()) return value
    return BigDecimal(value).setScale(ndigits, RoundingMode.HALF_EVEN).toDouble()
}
