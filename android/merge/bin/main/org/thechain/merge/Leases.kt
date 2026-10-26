package org.thechain.merge

/** Last moment the responder was heard from. */
fun aliveMs(createdWallMs: Long, beatMs: Long): Long = maxOf(createdWallMs, beatMs)

fun expiresAt(alive: Long, leaseMs: Long): Long = alive + leaseMs

fun isLive(alive: Long, nowMs: Long, leaseMs: Long): Boolean = nowMs - alive <= leaseMs
