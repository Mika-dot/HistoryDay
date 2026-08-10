package com.example.dayflash.poi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.dayflash.notify.NotificationHelper
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class PoiGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val event = intent?.let { GeofencingEvent.fromIntent(it) } ?: return
        if (event.hasError()) return
        val geofences = event.triggeringGeofences.orEmpty()

        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT &&
            geofences.any { PoiGeofenceManager.isRefreshBoundary(it.requestId) }
        ) {
            PoiRefreshWorker.enqueue(context, force = true)
            return
        }

        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_DWELL) return
        val poi = geofences
            .asSequence()
            .filterNot { PoiGeofenceManager.isRefreshBoundary(it.requestId) }
            .mapNotNull { PoiGeofenceManager.metadata(context, it.requestId) }
            .firstOrNull() ?: return

        if (PoiGeofenceManager.shouldNotify(context, poi)) {
            NotificationHelper.showPoi(context, poi)
        }
    }
}
