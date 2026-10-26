package org.thechain.app.map

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * MapLibre map of the phone's area. After [OfflineMap.download] the same style
 * is served from the on-device region, so the streets stay visible with no network.
 */
@Composable
fun ChainMap(
    lat: Double?,
    lon: Double?,
    pins: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            view.getMapAsync { map ->
                val style = map.style
                if (style == null) {
                    map.setStyle(Style.Builder().fromUri(OfflineMap.STYLE)) {
                        showPins(map, lat, lon, pins, moveCamera = true)
                    }
                } else {
                    showPins(map, lat, lon, pins, moveCamera = false)
                }
            }
        },
    )
}

private fun showPins(
    map: MapLibreMap,
    lat: Double?,
    lon: Double?,
    pins: List<Pair<Double, Double>>,
    moveCamera: Boolean,
) {
    if (moveCamera && lat != null && lon != null) {
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(14.0)
            .build()
    }
    map.removeAnnotations()
    val seen = linkedSetOf<Pair<Double, Double>>()
    if (lat != null && lon != null) seen.add(lat to lon)
    pins.forEach { seen.add(it) }
    seen.forEach { (pinLat, pinLon) ->
        map.addMarker(MarkerOptions().position(LatLng(pinLat, pinLon)))
    }
}
