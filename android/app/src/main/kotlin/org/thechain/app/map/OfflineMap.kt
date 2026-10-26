package org.thechain.app.map

import android.content.Context
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos

/**
 * Offline pack for the area around a GNSS fix.
 *
 * The phone uses MapLibre Native, which speaks the same style as
 * [MapLibre GL JS](https://maplibre.org/maplibre-gl-js/docs/API/).
 * GL JS runs in a browser; this SDK is what can download a region on Android.
 */
object OfflineMap {
    const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
    private const val PREFS = "maplibre-pack"
    private const val RADIUS_M = 1_400.0

    data class Summary(val count: Int, val zoom: Int)

    @Volatile
    private var savedResources: Int = -1

    fun summary(context: Context): Summary {
        if (savedResources < 0) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            savedResources = prefs.getInt("resources", 0)
        }
        val zoom = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("zoom", 0)
        return Summary(savedResources.coerceAtLeast(0), zoom)
    }

    fun download(
        context: Context,
        lat: Double,
        lon: Double,
        onProgress: (done: Long, total: Long) -> Unit,
        onDone: (ok: Boolean) -> Unit,
    ) {
        if (!lat.isFinite() || !lon.isFinite()) {
            onDone(false)
            return
        }
        val app = context.applicationContext
        MapLibre.getInstance(app)
        val manager = OfflineManager.getInstance(app)
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                deleteThenCreate(manager, offlineRegions?.toList().orEmpty(), app, lat, lon, onProgress, onDone)
            }

            override fun onError(error: String) {
                onDone(false)
            }
        })
    }

    private fun deleteThenCreate(
        manager: OfflineManager,
        existing: List<OfflineRegion>,
        context: Context,
        lat: Double,
        lon: Double,
        onProgress: (Long, Long) -> Unit,
        onDone: (Boolean) -> Unit,
    ) {
        val left = existing.toMutableList()
        fun next() {
            val region = left.removeFirstOrNull()
            if (region == null) {
                create(manager, context, lat, lon, onProgress, onDone)
                return
            }
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = next()
                override fun onError(error: String) = next()
            })
        }
        next()
    }

    private fun create(
        manager: OfflineManager,
        context: Context,
        lat: Double,
        lon: Double,
        onProgress: (Long, Long) -> Unit,
        onDone: (Boolean) -> Unit,
    ) {
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE,
            bounds(lat, lon, RADIUS_M),
            12.0,
            16.0,
            context.resources.displayMetrics.density,
        )
        val metadata = """{"lat":$lat,"lon":$lon}""".toByteArray()
        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            onProgress(status.completedResourceCount, status.requiredResourceCount)
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                remember(context, status.completedResourceCount.toInt(), 16)
                                onDone(true)
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            onDone(false)
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            onDone(false)
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    onDone(false)
                }
            },
        )
    }

    private fun remember(context: Context, count: Int, zoom: Int) {
        savedResources = count
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("resources", count)
            .putInt("zoom", zoom)
            .apply()
    }

    fun bounds(lat: Double, lon: Double, radiusM: Double): LatLngBounds {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return LatLngBounds.Builder()
            .include(LatLng(lat - dLat, lon - dLon))
            .include(LatLng(lat + dLat, lon + dLon))
            .build()
    }
}
