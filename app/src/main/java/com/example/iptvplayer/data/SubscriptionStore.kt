package com.example.iptvplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences("subs_v2", Context.MODE_PRIVATE)

    fun loadAll(): List<Subscription> {
        val raw = prefs.getString("list", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val list = mutableListOf<Subscription>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += Subscription(
                id = o.optString("id"),
                name = o.optString("name"),
                type = o.optString("type"),
                m3uUrl = o.optString("m3uUrl"),
                host = o.optString("host"),
                user = o.optString("user"),
                pass = o.optString("pass"),
                mac = o.optString("mac")
            )
        }
        return list
    }

    fun saveAll(list: List<Subscription>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("type", s.type)
                put("m3uUrl", s.m3uUrl)
                put("host", s.host)
                put("user", s.user)
                put("pass", s.pass)
                put("mac", s.mac)
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun upsert(sub: Subscription) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == sub.id }
        if (idx >= 0) list[idx] = sub else list.add(sub)
        saveAll(list)
    }

    fun delete(id: String) {
        val list = loadAll().filterNot { it.id == id }
        saveAll(list)
    }

    fun getLastOpened(): String? = prefs.getString("last", null)
    fun setLastOpened(id: String) = prefs.edit().putString("last", id).apply()
}
