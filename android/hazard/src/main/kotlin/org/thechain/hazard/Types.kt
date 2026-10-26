package org.thechain.hazard

import kotlin.math.hypot

typealias Pos = Pair<Double, Double>
typealias Cell = Pair<Int, Int>

fun dist(a: Pos, b: Pos): Double = hypot(a.first - b.first, a.second - b.second)
