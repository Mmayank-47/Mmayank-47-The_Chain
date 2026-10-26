package org.thechain.redteam

import org.thechain.battery.Sample
import org.thechain.battery.batteryLlr
import org.thechain.core.Hlc
import org.thechain.core.Message
import org.thechain.core.Priority
import org.thechain.core.TokenBucket
import org.thechain.hazard.FloodModel
import org.thechain.hazard.HazardGrid
import org.thechain.hazard.RoadGraph
import org.thechain.hazard.findRoute
import org.thechain.transport.CarryStore
import org.thechain.transport.IngressGuard
import org.thechain.transport.StrangerLaneLimiter
import org.thechain.trust.TrustEngine
import org.thechain.trust.VouchGraph
import org.thechain.verify.ObservedClaim
import org.thechain.verify.Response
import org.thechain.verify.Verifier
import org.thechain.verify.checkResponse
import org.thechain.verify.makeChallenge

const val RELEASE = 0.6
val ROOT: ByteArray = "pinned-root-key".toByteArray(Charsets.UTF_8)

data class AttackClaim(
    val id: Int,
    val node: Int,
    val src: Int,
    val pos: Pair<Double, Double>,
    val t: Double,
    val urgency: Double = 1.0,
    val need: String = "medic",
    val battery: Double = 1.0,
) {
    fun observed(): ObservedClaim<Int, Int> = ObservedClaim(id, node, src, pos, t)
}

data class Result(
    val name: String,
    val undefended: Double,
    val defended: Double,
    val maxOk: Double,
    val note: String = "",
) {
    val ok: Boolean get() = defended <= maxOk && undefended > defended
}

private fun square(): RoadGraph {
    val g = RoadGraph()
    for ((n, p) in mapOf("A" to (0.0 to 0.0), "B" to (200.0 to 0.0), "C" to (0.0 to 200.0), "D" to (200.0 to 200.0))) {
        g.addNode(n, p)
    }
    for ((a, b) in listOf("A" to "B", "A" to "C", "C" to "D", "D" to "B")) {
        g.addEdge(a, b)
    }
    return g
}

private fun underpass(p: Pair<Double, Double>): Double =
    if (p.first > 50 && p.first < 150 && p.second < 30) 8.0 else 100.0

fun forgedCredentials(): Result {
    val revoked = setOf(3 to "medic")
    val creds = mapOf(
        1 to signCredential(ROOT, 1, "medic"),
        2 to "f".repeat(64),
        3 to signCredential(ROOT, 3, "medic"),
    )
    val check: (ObservedClaim<Int, Int>) -> Boolean? = { c ->
        if (c.node in creds) verifyCredential(ROOT, c.node, "medic", creds[c.node], revoked) else null
    }
    val claims = listOf(2, 3).mapIndexed { i, n -> AttackClaim(i, n, n, 100.0 * n to 0.0, 0.0) }
    val control = AttackClaim(9, 1, 1, 500.0 to 500.0, 0.0)
    fun success(v: Verifier<Int, Int>): Double =
        claims.count { v.pValid(it.observed()) >= RELEASE }.toDouble() / claims.size
    val off = success(Verifier())
    val on = success(Verifier(credential = check))
    check(Verifier(credential = check).pValid(control.observed()) >= 0.9) {
        "genuine medic must still pass"
    }
    return Result("forged_credentials", off, on, 0.0, "forged + revoked medic credentials")
}

fun sybilSwarm(): Result {
    val g = VouchGraph(seeds = listOf("root", "root2"))
    val honest = (1..5).map { "h$it" }
    g.vouch("root", "h1")
    for ((a, b) in honest.zip(honest.drop(1))) {
        g.vouch(a, b)
        g.vouch(b, a)
    }
    for ((r, n) in listOf("root" to "h3", "root" to "h5", "root2" to "h2", "root2" to "h4")) {
        g.vouch(r, n)
    }
    val ring = (0 until 40).map { "s$it" }
    for (a in ring) {
        for (b in ring) g.vouch(a, b)
    }
    g.vouch("h1", ring[0])
    val naive = ring.count { s ->
        g.out.values.count { vs -> s in vs } >= 3
    }.toDouble() / ring.size
    val eng = TrustEngine(g)
    val strong = ring.count { eng.trust(it, 0.0) >= 0.5 }.toDouble() / ring.size
    val swarm = (0 until 40).map { j ->
        AttackClaim(100 + j, 7, 10_000 + j, 800.0 to 800.0, 5.0)
    }
    val v = Verifier<Int, Int>()
    for (c in swarm) v.observe(c.observed())
    val claimsOk = swarm.count { v.pValid(it.observed()) >= RELEASE }.toDouble() / swarm.size
    val naiveC = if (swarm.map { it.src }.toSet().size >= 3) 1.0 else 0.0
    return Result(
        "sybil_swarm",
        maxOf(naive, naiveC),
        maxOf(strong, claimsOk),
        0.0,
        "40 fake identities: vouch ring + same-place claims",
    )
}

private fun flood(lim: RateLimiter?, dd: Dedupe?, n: Int = 500): Int {
    var accepted = 0
    for (i in 0 until n) {
        val now = i * 0.02
        val payload = if (i % 2 == 0) "spam-$i".toByteArray() else "spam-${i % 5}".toByteArray()
        if ((lim == null || lim.allow("device" to 66, now)) && (dd == null || dd.first(payload, now))) {
            accepted += 1
        }
    }
    return accepted
}

fun packetFlood(): Result {
    val n = 500
    val off = flood(null, null, n).toDouble() / n
    val lim = RateLimiter(rate = 2.0, burst = 10.0)
    val dd = Dedupe()
    val accepted = flood(lim, dd, n)
    val honest = (0 until 30).count { t -> lim.allow("device" to 1, t.toDouble()) }.toDouble() / 30
    check(honest == 1.0) { "flooder must not starve others" }
    return Result("packet_flood", off, accepted.toDouble() / n, 0.10, "$accepted/$n flood packets got through")
}

fun lureIntoFloodwater(): Result {
    val truth = FloodModel(rate = 0.08)
    fun lured(grid: HazardGrid): Double {
        val r = findRoute(square(), "A", "B", grid.riskFn(), t0 = 0.0, speed = 1.0)
        if (r == null || r.path != listOf("A", "B")) return 0.0
        return if (truth.pFlooded(8.0, 100.0) > 0.99) 1.0 else 0.0
    }
    val naive = HazardGrid(minSafeReporters = 1, minSafeWeight = 0.1)
    val safe = HazardGrid(flood = FloodModel(rate = 0.08), dem = ::underpass)
    for (grid in listOf(naive, safe)) {
        for (k in 0 until 4) {
            for (x in listOf(60.0, 110.0, 140.0)) {
                grid.report("sybil$k", x to 10.0, "safe", 0.0, 1.0)
            }
        }
    }
    val veto = SensorVeto()
    check(veto.abort(0.3, 0.0) && !veto.abort(null, 0.1))
    return Result("lure_into_floodwater", lured(naive), lured(safe), 0.0, "fake 'route is safe' claims over flood")
}

fun decoyEmergencies(): Result {
    val decoys = (0 until 5).map { i ->
        AttackClaim(i, 5, 5, 300.0 + 400 * i to 400.0, 30.0 * i)
    }
    val passive = Verifier<Int, Int>()
    for (c in decoys) passive.observe(c.observed())
    val off = decoys.count { passive.pValid(it.observed()) >= RELEASE }.toDouble() / decoys.size
    val probed = Verifier<Int, Int>()
    val eng = TrustEngine()
    for (c in decoys) probed.observe(c.observed())
    val known = setOf(10, 11, 12)
    for (c in decoys) {
        val chal = makeChallenge(c.id, c.node, c.t, "nonce${c.id}")
        val fake = Response(chal.cid, c.node, 950.0, setOf(91, 92), c.t + 20)
        probed.recordChallenge(c.id, checkResponse(chal, fake, c.t + 30, 1005.0, known))
        eng.rep.record("5", "failed_challenge", c.t)
    }
    val on = decoys.count { probed.pValid(it.observed()) >= RELEASE }.toDouble() / decoys.size
    val honest = AttackClaim(50, 6, 6, 900.0 to 900.0, 0.0)
    val good = Verifier<Int, Int>()
    val chal = makeChallenge(50, 6, 0.0, "n")
    val ok = Response(chal.cid, 6, 1004.0, setOf(10, 11), 10.0)
    good.recordChallenge(50, checkResponse(chal, ok, 20.0, 1005.0, known))
    check(good.pValid(honest.observed()) >= 0.9 && eng.trust("5", 150.0) < 0.2)
    return Result("decoy_emergencies", off, on, 0.0, "passive M3 only vs challenge-response + reputation hit")
}

fun hazardPoisoning(): Result {
    fun denied(weight: Double, reporters: Int = 10): Double {
        val grid = HazardGrid()
        for (cellPos in listOf(100.0 to 0.0, 0.0 to 100.0, 100.0 to 200.0, 200.0 to 100.0)) {
            for (k in 0 until reporters) {
                grid.report("p$k", cellPos, "hazard", 0.0, weight)
            }
        }
        return if (findRoute(square(), "A", "B", grid.riskFn()) == null) 1.0 else 0.0
    }
    val g = HazardGrid()
    g.report("honest", 100.0 to 0.0, "hazard", 0.0, 0.9)
    check(g.risk(g.cellOf(100.0 to 0.0), 0.0) > 0.5)
    val old = HazardGrid(hazardHalfLife = 300.0)
    old.report("p", 0.0 to 0.0, "hazard", 0.0, 1.0)
    check(old.risk(old.cellOf(0.0 to 0.0), 3000.0) < 0.15)
    return Result("hazard_poisoning", denied(1.0), denied(0.05), 0.0, "10 fake reporters per cell, weight = trust")
}

private fun naiveBatteryOk(samples: List<Sample>): Boolean =
    samples.all { it.level in 0.0..1.0 }

fun batteryCurveFraud(): Result {
    val fake = (0 until 20).map { t -> Sample(t * 300.0, 1.0) }
    val jump = listOf(Sample(0.0, 0.1), Sample(600.0, 0.9))
    val off = listOf(fake, jump).count { naiveBatteryOk(it) }.toDouble() / 2
    val on = listOf(fake, jump).count { batteryLlr(it) >= 0 }.toDouble() / 2
    return Result("battery_curve_fraud", off, on, 0.0, "flat or rising battery curves")
}

/** Minimal enrolled/stranger limiter matching m1 IdentityLimiter for store_flooding. */
private class FleetLimiter(
    private val enrolled: Set<String>,
    private val baseRate: Double = 1.0,
    private val baseBurst: Double = 5.0,
    private val strangerBurst: Double = 1.0,
) : StrangerLaneLimiter {
    private val buckets = mutableMapOf<String, TokenBucket>()
    private val strangers = mutableMapOf<String, TokenBucket>()

    override fun allow(node: String, now: Double, cost: Double): Boolean {
        if (node !in enrolled) return false
        val b = buckets.getOrPut(node) { TokenBucket(baseRate, baseBurst).also { it.t = now } }
        b.rate = baseRate
        b.burst = baseBurst
        return b.allow(now, cost)
    }

    override fun allowStranger(node: String, now: Double, cost: Double): Boolean {
        if (node in enrolled) return false
        if (strangerBurst <= 0.0) return false
        val b = strangers.getOrPut(node) { TokenBucket(0.0, strangerBurst).also { it.t = now } }
        b.rate = 0.0
        b.burst = strangerBurst
        return b.allow(now, cost)
    }
}

fun storeFlooding(): Result {
    val undefStore = CarryStore(capacity = 512, maxPerOrigin = null, maxPerNeighbour = null)
    for (i in 0 until 500) {
        val m = Message("m9.case", Priority.DISTRESS, "flooder", mapOf("seq" to i), Hlc(1, i, "flooder"), ttlS = 3600.0)
        undefStore.add(m, null, 0.0, fromId = "evil_peer")
    }
    val flooderUndef = undefStore.size

    val defStore = CarryStore(capacity = 512, maxPerOrigin = 32, maxPerNeighbour = 64)
    for (i in 0 until 500) {
        val m = Message("m9.case", Priority.DISTRESS, "flooder", mapOf("seq" to i), Hlc(1, i, "flooder"), ttlS = 3600.0)
        defStore.add(m, null, 0.0, fromId = "evil_peer")
    }
    val flooderDef = defStore.size
    check(defStore.originCount("flooder") <= 32)
    check(flooderDef <= 32)

    val lim = FleetLimiter(enrolled = setOf("honest_node"), baseRate = 1.0, baseBurst = 5.0, strangerBurst = 1.0)
    val guard = IngressGuard(limiter = lim, strangerLane = true)
    var victimAdmitted = 0
    for (i in 0 until 5) {
        val m = Message(
            "m9.case",
            Priority.DISTRESS,
            "victim_stranger",
            mapOf("seq" to i),
            Hlc(1, i, "victim_stranger"),
            ttlS = 3600.0,
        )
        if (guard.admit(m, "victim_stranger", 0.0) != null) victimAdmitted++
    }
    check(victimAdmitted == 1)

    val chatter = Message(
        "m8.event",
        Priority.CHATTER,
        "victim_stranger",
        mapOf("k" to "beat"),
        Hlc(1, 10, "victim_stranger"),
        ttlS = 3600.0,
    )
    check(guard.admit(chatter, "victim_stranger", 1.0) == null)

    val off = flooderUndef / 500.0
    val on = flooderDef / 500.0
    return Result("store_flooding", off, on, 0.10, "$flooderDef/500 flood quota capped, stranger lane 1 allowed")
}

val ALL: List<() -> Result> = listOf(
    ::forgedCredentials,
    ::sybilSwarm,
    ::packetFlood,
    ::lureIntoFloodwater,
    ::decoyEmergencies,
    ::hazardPoisoning,
    ::batteryCurveFraud,
    ::storeFlooding,
)

fun runAll(): List<Result> = ALL.map { it() }
