package com.example.dayflash.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

data class MomentLocation(
    val latitude: Double,
    val longitude: Double,
    val placeName: String?,
    val osmType: String?,
    val osmId: Long?,
)

object MomentLocationResolver {
    suspend fun resolve(context: Context): MomentLocation? {
        if (!hasLocationPermission(context)) return null
        val location = getBestLocation(context) ?: return null
        val place = runCatching { reverseWithNominatim(location) }.getOrNull()
        return MomentLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            placeName = place?.name,
            osmType = place?.type,
            osmId = place?.id,
        )
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun getBestLocation(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }

        val lastKnown = providers.mapNotNull { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.getOrNull()
        }.maxWithOrNull(compareBy<Location> { it.time }.thenBy { -it.accuracy })

        val freshEnough = lastKnown?.let { System.currentTimeMillis() - it.time <= LAST_LOCATION_MAX_AGE_MS } == true
        if (freshEnough) return lastKnown

        val live = providers.firstNotNullOfOrNull { provider ->
            runCatching { if (manager.isProviderEnabled(provider)) provider else null }.getOrNull()
        } ?: return lastKnown

        val current = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            requestSingleUpdate(manager, live)
        }
        return current ?: lastKnown
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun requestSingleUpdate(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }

            continuation.invokeOnCancellation {
                runCatching { manager.removeUpdates(listener) }
            }

            runCatching {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun reverseWithNominatim(location: Location): OsmPlace? {
        val lat = String.format(Locale.US, "%.6f", location.latitude)
        val lon = String.format(Locale.US, "%.6f", location.longitude)
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=18&addressdetails=1"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            setRequestProperty("User-Agent", "HistoryDay/1.3 Android (https://github.com/Mika-dot/HistoryDay)")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            setRequestProperty("Accept", "application/json")
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val address = json.optJSONObject("address")
            val directName = json.optString("name").trim().takeIf { it.isNotEmpty() }
            val addressName = listOf(
                "attraction", "tourism", "historic", "amenity", "leisure",
                "building", "office", "shop", "road", "pedestrian", "neighbourhood", "suburb", "city"
            ).firstNotNullOfOrNull { key -> address?.optString(key)?.trim()?.takeIf { it.isNotEmpty() } }
            val displayName = json.optString("display_name").substringBefore(',').trim().takeIf { it.isNotEmpty() }
            val id = json.optLong("osm_id", 0L).takeIf { it != 0L }
            OsmPlace(
                name = directName ?: addressName ?: displayName,
                type = json.optString("type").trim().takeIf { it.isNotEmpty() },
                id = id,
            )
        } finally {
            connection.disconnect()
        }
    }

    private data class OsmPlace(
        val name: String?,
        val type: String?,
        val id: Long?,
    )

    private const val LAST_LOCATION_MAX_AGE_MS = 10 * 60 * 1000L
    private const val LOCATION_TIMEOUT_MS = 2_500L
    private const val NETWORK_TIMEOUT_MS = 2_500
}
