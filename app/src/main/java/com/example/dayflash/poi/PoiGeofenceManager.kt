package com.example.dayflash.poi

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.dayflash.BuildConfig
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

data class PoiData(
    val requestId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val category: String,
    val radiusMeters: Float,
)

object PoiGeofenceManager {
    private const val PREFS = "historyday_poi"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_POIS = "pois_json"
    private const val KEY_LAST_REFRESH = "last_refresh"
    private const val KEY_LAST_LAT = "last_lat"
    private const val KEY_LAST_LON = "last_lon"
    private const val KEY_LAST_COUNT = "last_count"
    private const val KEY_LAST_NOTIFICATION = "last_notification"
    private const val REFRESH_ID = "historyday_refresh_boundary"

    private const val SEARCH_RADIUS_METERS = 6_000
    private const val REFRESH_DISTANCE_METERS = 2_000f
    private const val REFRESH_BOUNDARY_METERS = 3_200f
    private const val MAX_POIS = 90
    private const val REFRESH_AGE_MS = 6L * 60L * 60L * 1000L
    private const val GLOBAL_NOTIFICATION_COOLDOWN_MS = 25L * 60L * 1000L
    private const val SAME_POI_COOLDOWN_MS = 14L * 24L * 60L * 60L * 1000L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) {
            PoiRefreshWorker.enqueue(context, force = true)
        } else {
            runCatching { LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context)) }
        }
    }

    fun registeredCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST_COUNT, 0)

    fun hasForegroundLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh(context: Context, force: Boolean): Boolean {
        if (!isEnabled(context) || !hasForegroundLocation(context) || !hasBackgroundLocation(context)) return false
        val location = currentLocation(context) ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!force && !needsRefresh(prefs = prefs, location = location)) return true

        val pois = withContext(Dispatchers.IO) {
            runCatching { fetchInterestingPois(location.latitude, location.longitude) }.getOrDefault(emptyList())
        }
        if (pois.isEmpty()) return false

        val client = LocationServices.getGeofencingClient(context)
        runCatching { awaitTask(client.removeGeofences(pendingIntent(context))) }

        val geofences = pois.map { poi ->
            Geofence.Builder()
                .setRequestId(poi.requestId)
                .setCircularRegion(poi.latitude, poi.longitude, poi.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(45_000)
                .build()
        }.toMutableList()

        geofences += Geofence.Builder()
            .setRequestId(REFRESH_ID)
            .setCircularRegion(location.latitude, location.longitude, REFRESH_BOUNDARY_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()

        val added = runCatching {
            awaitTask(client.addGeofences(request, pendingIntent(context)))
        }.getOrDefault(false)
        if (!added) return false

        savePois(context, pois)
        prefs.edit()
            .putLong(KEY_LAST_REFRESH, System.currentTimeMillis())
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(location.latitude))
            .putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(location.longitude))
            .putInt(KEY_LAST_COUNT, pois.size)
            .apply()
        return true
    }

    fun metadata(context: Context, requestId: String): PoiData? {
        if (requestId == REFRESH_ID) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POIS, null) ?: return null
        val item = runCatching { JSONObject(raw).optJSONObject(requestId) }.getOrNull() ?: return null
        return PoiData(
            requestId = requestId,
            name = item.optString("name"),
            latitude = item.optDouble("lat"),
            longitude = item.optDouble("lon"),
            category = item.optString("category"),
            radiusMeters = item.optDouble("radius", 200.0).toFloat(),
        ).takeIf { it.name.isNotBlank() }
    }

    fun shouldNotify(context: Context, poi: PoiData): Boolean {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastGlobal = prefs.getLong(KEY_LAST_NOTIFICATION, 0L)
        val lastPoi = prefs.getLong("notified_${poi.requestId}", 0L)
        if (now - lastGlobal < GLOBAL_NOTIFICATION_COOLDOWN_MS) return false
        if (now - lastPoi < SAME_POI_COOLDOWN_MS) return false
        prefs.edit()
            .putLong(KEY_LAST_NOTIFICATION, now)
            .putLong("notified_${poi.requestId}", now)
            .apply()
        return true
    }

    fun isRefreshBoundary(requestId: String): Boolean = requestId == REFRESH_ID

    private fun needsRefresh(prefs: android.content.SharedPreferences, location: Location): Boolean {
        val last = prefs.getLong(KEY_LAST_REFRESH, 0L)
        if (System.currentTimeMillis() - last > REFRESH_AGE_MS) return true
        if (!prefs.contains(KEY_LAST_LAT) || !prefs.contains(KEY_LAST_LON)) return true
        val lastLat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LAT, 0L))
        val lastLon = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LON, 0L))
        val result = FloatArray(1)
        Location.distanceBetween(lastLat, lastLon, location.latitude, location.longitude, result)
        return result[0] >= REFRESH_DISTANCE_METERS
    }

    private suspend fun currentLocation(context: Context): Location? {
        if (!hasForegroundLocation(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { continuation ->
            val tokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation { tokenSource.cancel() }
            try {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
                    .addOnCompleteListener { task ->
                        val current = if (task.isSuccessful) task.result else null
                        if (current != null) {
                            if (continuation.isActive) continuation.resume(current)
                        } else {
                            client.lastLocation.addOnCompleteListener { lastTask ->
                                val last = if (lastTask.isSuccessful) lastTask.result else null
                                if (continuation.isActive) continuation.resume(last)
                            }
                        }
                    }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun fetchInterestingPois(latitude: Double, longitude: Double): List<PoiData> {
        val query = """
            [out:json][timeout:20];
            (
              nwr(around:$SEARCH_RADIUS_METERS,$latitude,$longitude)["historic"]["name"];
              nwr(around:$SEARCH_RADIUS_METERS,$latitude,$longitude)["tourism"~"attraction|museum|viewpoint|artwork"]["name"];
              nwr(around:$SEARCH_RADIUS_METERS,$latitude,$longitude)["man_made"~"tower|lighthouse"]["name"];
              nwr(around:$SEARCH_RADIUS_METERS,$latitude,$longitude)["place"="square"]["name"];
              nwr(around:$SEARCH_RADIUS_METERS,$latitude,$longitude)["leisure"~"park|garden"]["name"];
            );
            out center tags;
        """.trimIndent()

        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 22_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", userAgent())
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val elements = root.optJSONArray("elements") ?: return emptyList()
            val candidates = ArrayList<ScoredPoi>()
            for (index in 0 until elements.length()) {
                val element = elements.optJSONObject(index) ?: continue
                val tags = element.optJSONObject("tags") ?: continue
                val name = tags.optString("name:ru").trim().ifBlank { tags.optString("name").trim() }
                if (name.isBlank()) continue

                val center = element.optJSONObject("center")
                val lat = if (element.has("lat")) element.optDouble("lat") else center?.optDouble("lat") ?: Double.NaN
                val lon = if (element.has("lon")) element.optDouble("lon") else center?.optDouble("lon") ?: Double.NaN
                if (!lat.isFinite() || !lon.isFinite()) continue

                val type = element.optString("type", "node")
                val osmId = element.optLong("id", 0L)
                if (osmId == 0L) continue
                val category = category(tags)
                val score = score(tags)
                val radius = radius(tags)
                val distance = FloatArray(1).also {
                    Location.distanceBetween(latitude, longitude, lat, lon, it)
                }[0]
                candidates += ScoredPoi(
                    poi = PoiData(
                        requestId = "poi_${type}_$osmId",
                        name = name,
                        latitude = lat,
                        longitude = lon,
                        category = category,
                        radiusMeters = radius,
                    ),
                    score = score,
                    distance = distance,
                )
            }

            candidates
                .sortedWith(compareByDescending<ScoredPoi> { it.score }.thenBy { it.distance })
                .distinctBy { it.poi.name.lowercase(Locale.ROOT) }
                .take(MAX_POIS)
                .map { it.poi }
        } finally {
            connection.disconnect()
        }
    }

    private fun score(tags: JSONObject): Int = when {
        tags.optString("historic") in setOf("castle", "fort", "archaeological_site", "monument", "memorial") -> 100
        tags.optString("tourism") in setOf("attraction", "museum", "viewpoint") -> 90
        tags.optString("man_made") in setOf("tower", "lighthouse") -> 85
        tags.optString("place") == "square" -> 78
        tags.optString("tourism") == "artwork" -> 72
        tags.optString("leisure") in setOf("park", "garden") -> 65
        tags.has("historic") -> 80
        else -> 55
    }

    private fun radius(tags: JSONObject): Float = when {
        tags.optString("historic") in setOf("castle", "fort", "archaeological_site") -> 350f
        tags.optString("tourism") in setOf("attraction", "museum", "viewpoint") -> 240f
        tags.optString("leisure") in setOf("park", "garden") -> 280f
        tags.optString("place") == "square" -> 220f
        tags.optString("man_made") == "tower" -> 220f
        else -> 160f
    }

    private fun category(tags: JSONObject): String = when {
        tags.has("historic") -> "historic:${tags.optString("historic")}" 
        tags.has("tourism") -> "tourism:${tags.optString("tourism")}" 
        tags.has("man_made") -> "man_made:${tags.optString("man_made")}" 
        tags.has("place") -> "place:${tags.optString("place")}" 
        tags.has("leisure") -> "leisure:${tags.optString("leisure")}" 
        else -> "poi"
    }

    private fun savePois(context: Context, pois: List<PoiData>) {
        val root = JSONObject()
        pois.forEach { poi ->
            root.put(
                poi.requestId,
                JSONObject()
                    .put("name", poi.name)
                    .put("lat", poi.latitude)
                    .put("lon", poi.longitude)
                    .put("category", poi.category)
                    .put("radius", poi.radiusMeters.toDouble())
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POIS, root.toString())
            .apply()
    }

    private suspend fun awaitTask(task: Task<*>): Boolean = suspendCancellableCoroutine { continuation ->
        task.addOnCompleteListener {
            if (continuation.isActive) continuation.resume(it.isSuccessful)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            7301,
            Intent(context, PoiGeofenceReceiver::class.java),
            flags,
        )
    }

    private fun userAgent(): String =
        "HistoryDay/${BuildConfig.VERSION_NAME} Android (+https://github.com/Mika-dot/HistoryDay)"

    private data class ScoredPoi(
        val poi: PoiData,
        val score: Int,
        val distance: Float,
    )
}
