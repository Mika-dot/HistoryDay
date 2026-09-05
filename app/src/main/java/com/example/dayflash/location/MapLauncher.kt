package com.example.dayflash.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

object MapLauncher {
    fun open(context: Context, latitude: Double, longitude: Double, label: String?): Boolean {
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        val safeLabel = label?.trim()?.takeIf(String::isNotEmpty) ?: "$lat, $lon"
        val query = Uri.encode("$lat,$lon ($safeLabel)")
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$query"))

        if (geoIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(Intent.createChooser(geoIntent, null))
            return true
        }

        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://2gis.ru/geo/$lon,$lat"),
        )
        return if (webIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(webIntent)
            true
        } else {
            false
        }
    }
}
