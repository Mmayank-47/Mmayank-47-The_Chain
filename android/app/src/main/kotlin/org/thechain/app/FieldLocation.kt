package org.thechain.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import org.thechain.core.cellOf
import java.io.File

/**
 * GNSS listener. The last fix is kept on disk so the pin is still the place
 * this phone last knew, after the sky view drops.
 */
object FieldLocation {
    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Float,
        val timeMs: Long,
        val provider: String,
        val cell: String,
    )

    @Volatile
    private var current: Fix? = null

    private var listening = false

    fun enabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching { lm.isLocationEnabled }.getOrDefault(false)
    }

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun lastFix(context: Context): Fix? {
        current?.let { return it }
        val stored = read(context)
        current = stored
        return stored
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (current == null) current = read(app)
        if (!permitted(app) || listening) return
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val persisting = LocationListener { location -> remember(location, disk = app) }
        var any = false
        for (provider in providers()) {
            val on = runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
            if (!on) continue
            val ok = runCatching {
                lm.requestLocationUpdates(provider, 5_000L, 0f, persisting, Looper.getMainLooper())
                lm.getLastKnownLocation(provider)?.let { remember(it, disk = app) }
            }.isSuccess
            if (ok) any = true
        }
        if (any) {
            listening = true
            activeListener = persisting
            activeManager = lm
        }
    }

    fun stop() {
        val lm = activeManager
        val ear = activeListener
        if (lm != null && ear != null) {
            runCatching { lm.removeUpdates(ear) }
        }
        activeManager = null
        activeListener = null
        listening = false
    }

    private var activeManager: LocationManager? = null
    private var activeListener: LocationListener? = null

    private fun providers(): List<String> {
        val names = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            names.add(LocationManager.FUSED_PROVIDER)
        }
        return names
    }

    private fun remember(location: Location, disk: Context?) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        val pos = location.latitude to location.longitude
        val fix = Fix(
            lat = pos.first,
            lon = pos.second,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
            timeMs = if (location.time > 0L) location.time else System.currentTimeMillis(),
            provider = location.provider ?: LocationManager.GPS_PROVIDER,
            cell = cellOf(pos, 0.005),
        )
        current = fix
        if (disk != null) write(disk, fix)
    }

    private fun file(context: Context): File = File(context.filesDir, "gnss-last.txt")

    private fun write(context: Context, fix: Fix) {
        runCatching {
            file(context).writeText(
                listOf(fix.lat, fix.lon, fix.accuracyM, fix.timeMs, fix.provider, fix.cell).joinToString(","),
            )
        }
    }

    private fun read(context: Context): Fix? {
        val raw = runCatching { file(context).readText() }.getOrNull() ?: return null
        val parts = raw.split(",")
        if (parts.size < 6) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        return Fix(
            lat = lat,
            lon = lon,
            accuracyM = parts[2].toFloatOrNull() ?: 0f,
            timeMs = parts[3].toLongOrNull() ?: 0L,
            provider = parts[4],
            cell = parts[5],
        )
    }
}
