package top.hsyscn.opedrgent.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

object MapTileFetcher {
    private const val TILE_SIZE = 256
    private const val DEFAULT_ZOOM = 16
    private const val MAP_WIDTH_TILES = 3
    private const val MAP_HEIGHT_TILES = 3
    private val TILE_URLS = listOf(
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
        "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
        "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
    )

    private val tileClient: OkHttpClient by lazy {
        HttpClients.default.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class MapResult(
        val base64Png: String,
        val widthPx: Int,
        val heightPx: Int,
        val zoom: Int,
        val centerLat: Double,
        val centerLon: Double,
    )

    suspend fun fetchMapImage(
        lat: Double,
        lon: Double,
        zoom: Int = DEFAULT_ZOOM,
        widthTiles: Int = MAP_WIDTH_TILES,
        heightTiles: Int = MAP_HEIGHT_TILES,
    ): MapResult? = withContext(Dispatchers.IO) {
        try {
            DebugLog.i("MapTileFetcher: fetching map at $lat, $lon zoom=$zoom ${widthTiles}x${heightTiles} tiles")

            val (centerTileX, centerTileY) = latLonToTile(lat, lon, zoom)
            val offsetX = ((lonToX(lon, zoom) - centerTileX) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE)
            val offsetY = ((latToY(lat, zoom) - centerTileY) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE)

            val startX = centerTileX - widthTiles / 2
            val startY = centerTileY - heightTiles / 2
            val totalWidth = widthTiles * TILE_SIZE
            val totalHeight = heightTiles * TILE_SIZE

            val outputBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)

            var successCount = 0
            for (dy in 0 until heightTiles) {
                for (dx in 0 until widthTiles) {
                    val tileX = startX + dx
                    val tileY = startY + dy
                    val bitmap = downloadTile(tileX, tileY, zoom)
                    if (bitmap != null) {
                        try {
                            canvas.drawBitmap(bitmap, (dx * TILE_SIZE).toFloat(), (dy * TILE_SIZE).toFloat(), null)
                            successCount++
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
            }

            if (successCount == 0) {
                DebugLog.w("MapTileFetcher: all tiles failed")
                return@withContext null
            }

            val baos = ByteArrayOutputStream()
            outputBitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
            val bytes = baos.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            outputBitmap.recycle()

            DebugLog.i("MapTileFetcher: map ready ${totalWidth}x${totalHeight}px $successCount/${widthTiles * heightTiles} tiles, base64=${base64.length} chars")

            MapResult(
                base64Png = "data:image/png;base64,$base64",
                widthPx = totalWidth,
                heightPx = totalHeight,
                zoom = zoom,
                centerLat = lat,
                centerLon = lon,
            )
        } catch (e: Exception) {
            DebugLog.e("MapTileFetcher error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun downloadTile(x: Int, y: Int, z: Int): Bitmap? {
        var lastError: Exception? = null
        for (urlTemplate in TILE_URLS) {
            try {
                val url = urlTemplate.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
                val request = Request.Builder().url(url).get()
                    .header("User-Agent", "Opedrgent/1.0")
                    .build()

                val response = tileClient.newCall(request).execute()
                if (!response.isSuccessful || response.body == null) continue

                val bytes = response.body!!.bytes()
                if (bytes.isEmpty()) continue

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) return bitmap
            } catch (e: Exception) {
                lastError = e
                continue
            }
        }

        if (lastError != null) {
            DebugLog.d("MapTileFetcher: tile $z/$x/$y failed: ${lastError.message}")
        }
        return null
    }

    private fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        return Pair(lonToX(lon, zoom), latToY(lat, zoom))
    }

    private fun lonToX(lon: Double, zoom: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    }

    private fun latToY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 shl zoom)).toInt()
    }
}
