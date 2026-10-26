package org.thechain.testing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Vectors {
    fun load(name: String): JSONObject {
        val dir = System.getProperty("vectors.dir") ?: error("vectors.dir is not set")
        val file = File(dir, "$name.json")
        return JSONObject(file.readText(Charsets.UTF_8))
    }
}

fun untag(value: Any?): Any? {
    if (value !is JSONObject || !value.has("t")) return value
    return when (value.getString("t")) {
        "null" -> null
        "bool" -> value.getBoolean("v")
        "int" -> value.getLong("v")
        "float" -> value.getDouble("v")
        "str" -> value.getString("v")
        "list" -> {
            val arr = value.getJSONArray("v")
            List(arr.length()) { untag(arr.get(it)) }
        }
        "map" -> {
            val arr = value.getJSONArray("v")
            val out = linkedMapOf<String, Any?>()
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                out[pair.getString(0)] = untag(pair.get(1))
            }
            out
        }
        else -> error("unknown tag ${value.getString("t")}")
    }
}

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
