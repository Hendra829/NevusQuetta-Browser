package com.nevus.quetta

import android.content.Context
import org.json.JSONObject

object NativeGuard {
    init {
        System.loadLibrary("nevus_guard")
    }

    external fun isBlockedHost(host: String): Boolean
    private external fun nativeLoadHosts(hosts: Array<String>, version: Int)

    @Volatile
    var rulesetVersion: Int = 0
        private set

    fun loadRuleset(context: Context) {
        val raw = context.assets.open("nevus_ruleset.json").bufferedReader().use { it.readText() }
        val json = JSONObject(raw)
        val version = json.getInt("version")
        val array = json.getJSONArray("hosts")
        val hosts = Array(array.length()) { index -> array.getString(index) }
        nativeLoadHosts(hosts, version)
        rulesetVersion = version
    }
}
