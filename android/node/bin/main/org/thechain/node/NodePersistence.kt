package org.thechain.node

import org.json.JSONArray
import org.json.JSONObject
import org.thechain.core.Storage
import org.thechain.merge.Event

/** Minimal snapshot / restore for PhoneNode battery + M8 event log. */
object NodePersistence {
    fun save(phone: PhoneNode, storage: Storage, key: String) {
        val events = JSONArray()
        for (log in phone.replica.store.logs.values) {
            for (ev in log) {
                events.put(JSONObject(ev.toJson()))
            }
        }
        val root = JSONObject()
            .put("node_id", phone.id)
            .put("battery_level", phone.batteryLevel)
            .put("events", events)
        storage.put(key, root.toString().toByteArray(Charsets.UTF_8))
    }

    fun restore(phone: PhoneNode, storage: Storage, key: String): Boolean {
        val raw = storage.get(key) ?: return false
        val data = JSONObject(String(raw, Charsets.UTF_8))
        if (data.optString("node_id") != phone.id) return false
        phone.batteryLevel = data.optDouble("battery_level", phone.batteryLevel)
        val arr = data.optJSONArray("events") ?: return true
        val evs = ArrayList<Event>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            @Suppress("UNCHECKED_CAST")
            evs.add(Event.fromJson(jsonToMap(obj) as Map<String, Any?>))
        }
        phone.replica.ingest(evs)
        return true
    }

    private fun jsonToMap(obj: JSONObject): MutableMap<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for (k in obj.keys()) {
            out[k] = unwrap(obj.get(k))
        }
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL, null -> null
        is JSONObject -> jsonToMap(v)
        is JSONArray -> List(v.length()) { unwrap(v.get(it)) }
        is Number, is String, is Boolean -> v
        else -> v.toString()
    }
}
