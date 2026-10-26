package org.thechain.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.thechain.node.PhoneNode

/** Local chat history. The mesh copy is only the addressed m9.chat frame. */
object ChatStore {
    private const val PREFS = "resq_chats"
    private const val KEY = "lines"

    fun load(context: Context): List<PhoneNode.ChatMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val peer = o.optString("peer").trim()
                    val from = o.optString("from").trim()
                    val text = o.optString("text").trim()
                    if (peer.isEmpty() || from.isEmpty() || text.isEmpty()) continue
                    add(
                        PhoneNode.ChatMessage(
                            peerId = peer,
                            fromId = from,
                            name = o.optString("name").trim(),
                            text = text.take(PhoneNode.CHAT_MAX),
                            timeMs = o.optLong("t", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, messages: List<PhoneNode.ChatMessage>) {
        val arr = JSONArray()
        messages.takeLast(400).forEach { line ->
            arr.put(
                JSONObject()
                    .put("peer", line.peerId)
                    .put("from", line.fromId)
                    .put("name", line.name)
                    .put("text", line.text)
                    .put("t", line.timeMs),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}
