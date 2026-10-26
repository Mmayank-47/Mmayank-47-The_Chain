package org.thechain.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Locally saved mesh contacts. Name + occupation only — never medical fields. */
data class SavedContact(
    val nodeId: String,
    val name: String,
    val occupation: String,
    val savedAtMs: Long,
)

object ContactStore {
    private const val PREFS = "resq_contacts"
    private const val KEY = "contacts"

    fun list(context: Context): List<SavedContact> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id").trim()
                    val name = o.optString("name").trim()
                    if (id.isEmpty() || name.isEmpty()) continue
                    add(
                        SavedContact(
                            nodeId = id,
                            name = name,
                            occupation = o.optString("occ").trim(),
                            savedAtMs = o.optLong("at", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, nodeId: String, name: String, occupation: String) {
        val id = nodeId.trim()
        val cleanName = name.trim().take(64)
        if (id.isEmpty() || cleanName.isEmpty()) return
        val occ = occupation.trim().take(32)
        val current = list(context).filterNot { it.nodeId == id }.toMutableList()
        current.add(0, SavedContact(id, cleanName, occ, System.currentTimeMillis()))
        write(context, current)
    }

    fun remove(context: Context, nodeId: String) {
        write(context, list(context).filterNot { it.nodeId == nodeId })
    }

    private fun write(context: Context, contacts: List<SavedContact>) {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.nodeId)
                    .put("name", c.name)
                    .put("occ", c.occupation)
                    .put("at", c.savedAtMs),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}
