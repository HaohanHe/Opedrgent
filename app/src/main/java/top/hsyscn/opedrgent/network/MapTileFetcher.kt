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
            .connectTimeout(NetworkConfig.MAP_TILE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.MAP_TILE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(NetworkConfig.MAP_TILE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
            // 入口边界约束（U35-09）：防止越界 zoom/lat/lon 产生非法瓦片坐标或超大 Bitmap
            val zoomC = zoom.coerceIn(0, 20)
            val latC = lat.coerceIn(-85.0511, 85.0511)
            val lonC = ((((lon + 180.0) % 360.0) + 360.0) % 360.0 - 180.0).coerceIn(-180.0, 180.0)
            val wT = widthTiles.coerceIn(1, 8)
            val hT = heightTiles.coerceIn(1, 8)

            DebugLog.i("MapTileFetcher: fetching map at $latC, $lonC zoom=$zoomC ${wT}x${hT} tiles")

            // 保留浮点瓦片坐标，计算真实像素偏移（U35-02）：使目标经纬度落在画布中心
            val tileXF = lonToXFloat(lonC, zoomC)
            val tileYF = latToYFloat(latC, zoomC)
            val centerTileX = tileXF.toInt()
            val centerTileY = tileYF.toInt()
            val offsetX = ((tileXF - centerTileX) * TILE_SIZE).toFloat()
            val offsetY = ((tileYF - centerTileY) * TILE_SIZE).toFloat()

            val startX = centerTileX - wT / 2
            val startY = centerTileY - hT / 2
            val totalWidth = wT * TILE_SIZE
            val totalHeight = hT * TILE_SIZE

            // outputBitmap 在所有路径上统一 finally recycle，避免 successCount==0 或 compress 异常时泄漏（U35-01）
            var outputBitmap: Bitmap? = null
            try {
                outputBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outputBitmap)

                var successCount = 0
                for (dy in 0 until hT) {
                    for (dx in 0 until wT) {
                        val tileX = startX + dx
                        val tileY = startY + dy
                        val bitmap = downloadTile(tileX, tileY, zoomC)
                        if (bitmap != null) {
                            try {
                                canvas.drawBitmap(bitmap,
                                    (dx * TILE_SIZE - offsetX),
                                    (dy * TILE_SIZE - offsetY),
                                    null)
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

                DebugLog.i("MapTileFetcher: map ready ${totalWidth}x${totalHeight}px $successCount/${wT * hT} tiles, base64=${base64.length} chars")

                MapResult(
                    base64Png = "data:image/png;base64,$base64",
                    widthPx = totalWidth,
                    heightPx = totalHeight,
                    zoom = zoomC,
                    centerLat = latC,
                    centerLon = lonC,
                )
            } finally {
                outputBitmap?.takeIf { !it.isRecycled }?.recycle()
            }
        } catch (e: Exception) {
            DebugLog.e("MapTileFetcher error: ${e.message}", e)
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

                tileClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful || response.body == null) return@use

                    val bytes = response.body!!.bytes()
                    if (bytes.isEmpty()) return@use

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) return bitmap
                }
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

    // 浮点瓦片坐标：用于按经纬度在画布上居中（U35-02）
    private fun lonToXFloat(lon: Double, zoom: Int): Double =
        (lon + 180.0) / 360.0 * (1 shl zoom).toDouble()

    private fun latToYFloat(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat)
        val sin = Math.sin(latRad)
        val y = 0.5 - Math.log((1.0 + sin) / (1.0 - sin)) / (4.0 * Math.PI)
        return y * (1 shl zoom).toDouble()
    }

    private fun lonToX(lon: Double, zoom: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    }

    private fun latToY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 shl zoom)).toInt()
    }
}
