package com.example.dayflash.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class MomentLocation(
    val latitude: Double,
    val longitude: Double,
    val placeName: String?,
    val osmType: String? = null,
    val osmId: Long? = null,
)

object MomentLocationResolver {
    suspend fun resolve(context: Context): MomentLocation? {
        if (!hasLocationPermission(context)) return null
        val location = getBestLocation(context) ?: return null
        val placeName = runCatching { reverseWithDeviceGeocoder(context, location) }.getOrNull()
        return MomentLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            placeName = placeName,
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

        val liveProvider = providers.firstNotNullOfOrNull { provider ->
            runCatching { if (manager.isProviderEnabled(provider)) provider else null }.getOrNull()
        } ?: return lastKnown

        val current = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            requestSingleUpdate(manager, liveProvider)
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

    @Suppress("DEPRECATION")
    private fun reverseWithDeviceGeocoder(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val address = Geocoder(context, Locale.getDefault())
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?: return null

        val candidates = listOf(
            address.featureName,
            address.thoroughfare,
            address.subLocality,
            address.locality,
            address.adminArea,
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()

        return candidates.take(2).joinToString(" · ").takeIf(String::isNotBlank)
    }

    private const val LAST_LOCATION_MAX_AGE_MS = 10 * 60 * 1000L
    private const val LOCATION_TIMEOUT_MS = 2_500L
}
