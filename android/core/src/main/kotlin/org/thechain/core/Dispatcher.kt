package org.thechain.core

/**
 * Message-kind router. A handler that throws cannot starve the others.
 * Hazards and vouches have no kind of their own: they travel as m8.event.
 */
val KINDS = listOf(
    "m8.event",
    "m9.case",
    "m9.pin",
    "m9.card",
    "m9.chat",
    "m9.ack",
    "m9.sealed",
    "m1.revoke",
    "m3.challenge",
    "m3.response",
)

fun interface Handler {
    fun on(message: Message)
}

class Dispatcher(kinds: Collection<String> = KINDS) {
    val kinds: Set<String> = kinds.toSet()
    private val handlers: MutableMap<String, MutableList<Handler>> =
        kinds.associateWith { mutableListOf<Handler>() }.toMutableMap()
    val stats: MutableMap<String, Int> = mutableMapOf()

    fun register(kind: String, handler: Handler): Handler {
        if (kind !in kinds) throw IllegalArgumentException("unknown message kind '$kind'")
        handlers.getValue(kind).add(handler)
        return handler
    }

    operator fun invoke(message: Message): Int {
        val list = handlers[message.kind]
        if (list.isNullOrEmpty()) {
            bump("unrouted")
            return 0
        }
        var ok = 0
        for (h in list.toList()) {
            try {
                h.on(message)
                ok += 1
            } catch (_: Exception) {
                bump("error:${message.kind}")
            }
        }
        stats["delivered"] = (stats["delivered"] ?: 0) + ok
        return ok
    }

    private fun bump(key: String) {
        stats[key] = (stats[key] ?: 0) + 1
    }
}
