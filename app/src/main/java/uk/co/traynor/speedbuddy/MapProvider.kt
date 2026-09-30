package uk.co.traynor.speedbuddy

import android.content.Context

/** Renderer-specific facilities stay outside Drive, repositories and normal settings UI. */
interface MapProvider {
    val name: String
    val styleUrl: String
    val attribution: String
    fun clearTileCache(context: Context,finished: (String?)->Unit)
}
object SpeedBuddyMapProvider: MapProvider {
    override val name="MapLibre · OpenFreeMap Liberty"
    override val styleUrl="https://tiles.openfreemap.org/styles/liberty"
    override val attribution="© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap"
    override fun clearTileCache(context: Context,finished: (String?)->Unit) {
        org.maplibre.android.MapLibre.getInstance(context)
        org.maplibre.android.offline.OfflineManager.getInstance(context).clearAmbientCache(
            object: org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                override fun onSuccess() { finished(null) }
                override fun onError(message: String) { finished(message) }
            })
    }
}
