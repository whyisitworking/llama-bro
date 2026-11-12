package com.suhel.llamabro.demo.journey

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The fake Hugging Face server `demo/scripts/check.sh` starts (adb reverse): its controls and its request log. */
object FakeHubServer {
    private const val BASE = "http://127.0.0.1:18190"

    fun reset() = post("/_reset", "{}")

    /** Every transfer from now on runs at [mibPerSecond]. */
    fun slow(mibPerSecond: Int) = post("/_control", """{"faults":[{"kind":"slow","rate_mib_s":$mibPerSecond}]}""")

    /** The CDN GETs so far: one per file and per resume (a resume carries a `range`). */
    fun cdnGets(): List<JSONObject> {
        val text = URL("$BASE/_log").openStream().use { String(it.readBytes()) }
        val entries = JSONArray(text)
        return (0 until entries.length())
            .map(entries::getJSONObject)
            .filter { it.getString("host") == "cdn" && it.getString("method") == "GET" }
    }

    private fun post(path: String, body: String) {
        val connection = URL("$BASE$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray()) }
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
    }
}
