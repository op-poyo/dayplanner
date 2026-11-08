package dev.nathanxu.dayplanner.travel

import android.util.Log
import dev.nathanxu.dayplanner.data.TravelCache
import dev.nathanxu.dayplanner.data.TravelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class TravelMode { TRANSIT, WALK, BICYCLE, DRIVE }

/**
 * Travel time via Google Routes API (computeRoutes), cached in Room for 7 days.
 * Returns null when there is no API key, no network, or no route.
 */
class TravelService(
    private val dao: TravelDao,
    private val apiKey: String,
    private val packageName: String,
    /** SHA-1 of the signing cert, uppercase hex without colons. Needed for Android-restricted keys. */
    private val certSha1: String?,
) {
    val enabled: Boolean get() = apiKey.isNotBlank()

    suspend fun minutes(from: String, to: String, mode: TravelMode = TravelMode.TRANSIT): Int? {
        if (!enabled) return null
        val key = "${mode.name}|${from.trim().lowercase()}|${to.trim().lowercase()}"
        val now = System.currentTimeMillis()
        dao.get(key)?.let { if (now - it.fetchedAt < TTL_MS) return toMinutes(it.seconds) }

        val seconds = fetchSeconds(from, to, mode) ?: return null
        dao.put(TravelCache(key, seconds, now))
        return toMinutes(seconds)
    }

    private fun toMinutes(seconds: Int) = (seconds + 59) / 60

    private suspend fun fetchSeconds(from: String, to: String, mode: TravelMode): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("origin", JSONObject().put("address", from))
                    .put("destination", JSONObject().put("address", to))
                    .put("travelMode", mode.name)
                    .put("languageCode", "en-GB")
                    .put("units", "METRIC")
                    .toString()

                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                    setRequestProperty("X-Goog-FieldMask", "routes.duration")
                    // Needed if the key is restricted to this Android app.
                    setRequestProperty("X-Android-Package", packageName)
                    certSha1?.let { setRequestProperty("X-Android-Cert", it) }
                }
                try {
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    if (conn.responseCode !in 200..299) {
                        val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                        Log.w(TAG, "Routes API ${conn.responseCode}: $err")
                        return@runCatching null
                    }
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val routes = json.optJSONArray("routes") ?: return@runCatching null
                    if (routes.length() == 0) return@runCatching null
                    // Duration comes back as e.g. "1234s".
                    routes.getJSONObject(0).optString("duration").removeSuffix("s").toIntOrNull()
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "Routes API failed", it) }.getOrNull()
        }

    companion object {
        private const val TAG = "TravelService"
        private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
