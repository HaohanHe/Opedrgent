package top.hsyscn.opedrgent.env

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.hsyscn.opedrgent.network.HttpClients
import top.hsyscn.opedrgent.utils.DebugLog
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

data class EnvironmentInfo(
    val dateTime: String,
    val dayOfWeek: String,
    val timeZone: String,
    val language: String,
    val platform: String,
    val osVersion: String,
    val location: String?,
    val locationDetail: String?,
)

data class GeocodingResult(
    val displayName: String,
    val detail: String?,
)

object EnvironmentProvider {

    private val WEEKDAY_CN = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    fun getEnvironmentInfo(context: Context, includeLocation: Boolean = false, cachedLocation: String? = null, cachedLocationDetail: String? = null): EnvironmentInfo {
        val now = Calendar.getInstance()
        val sdfDate = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
        val sdfTz = SimpleDateFormat("zzz", Locale.ENGLISH)
        val dateTime = sdfDate.format(Date())
        val dayOfWeek = WEEKDAY_CN[now.get(Calendar.DAY_OF_WEEK) - 1]
        val timeZone = sdfTz.format(Date())
        val language = Locale.getDefault().let { "${it.displayLanguage} (${it.language}-${it.country})" }
        val platform = "Android ${Build.VERSION.RELEASE}"
        val osVersion = "API ${Build.VERSION.SDK_INT} (${Build.MANUFACTURER} ${Build.MODEL})"

        return EnvironmentInfo(
            dateTime = dateTime,
            dayOfWeek = dayOfWeek,
            timeZone = timeZone,
            language = language,
            platform = platform,
            osVersion = osVersion,
            location = cachedLocation,
            locationDetail = cachedLocationDetail,
        )
    }

    /** 单次定位超时（毫秒） */
    private const val LOCATION_TIMEOUT_MS = 8_000L

    /** 缓存位置最大可接受年龄（毫秒）：超过此值视为旧位置，需要重新定位 */
    private const val MAX_CACHED_LOCATION_AGE_MS = 5 * 60_000L // 5 分钟

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Pair<Double, Double>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 1) 先尝试获取一个足够新的最后已知位置（避免每次都在室内等待 GPS）
        getFreshLastKnownLocation(lm)?.let {
            DebugLog.i("EnvironmentProvider: 使用 ${ageMs(it)}ms 内的缓存位置: ${it.latitude}, ${it.longitude}")
            return Pair(it.latitude, it.longitude)
        }

        // 2) 请求一次新的实时定位
        val fresh = requestSingleFreshLocation(lm)
        if (fresh != null) {
            DebugLog.i("EnvironmentProvider: 获取到实时位置: ${fresh.latitude}, ${fresh.longitude}")
            return Pair(fresh.latitude, fresh.longitude)
        }

        // 3) 超时或失败：降级到任意最后已知位置（总比没有强）
        getAnyLastKnownLocation(lm)?.let {
            DebugLog.w("EnvironmentProvider: 实时定位失败/超时，降级使用旧位置（${ageMs(it)}ms 前）: ${it.latitude}, ${it.longitude}")
            return Pair(it.latitude, it.longitude)
        }

        DebugLog.w("EnvironmentProvider: 无法获取任何位置")
        return null
    }

    /** 获取 5 分钟内的最后已知位置 */
    @SuppressLint("MissingPermission")
    private fun getFreshLastKnownLocation(lm: LocationManager): Location? {
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (provider in providers) {
            val loc = lm.getLastKnownLocation(provider) ?: continue
            if (ageMs(loc) <= MAX_CACHED_LOCATION_AGE_MS) {
                if (best == null || loc.accuracy < best.accuracy) {
                    best = loc
                }
            }
        }
        return best
    }

    /** 获取任意最后已知位置，优先精度高且新的 */
    @SuppressLint("MissingPermission")
    private fun getAnyLastKnownLocation(lm: LocationManager): Location? {
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (provider in providers) {
            val loc = lm.getLastKnownLocation(provider) ?: continue
            if (best == null) {
                best = loc
                continue
            }
            // 综合判断：新的优先，精度次之
            val ageDiff = ageMs(best) - ageMs(loc)
            best = when {
                ageDiff > MAX_CACHED_LOCATION_AGE_MS -> loc
                loc.accuracy < best.accuracy -> loc
                else -> best
            }
        }
        return best
    }

    /**
     * 请求一次新位置，带超时。
     * API 30+ 使用 [LocationManager.getCurrentLocation]；低版本 fallback 到 [LocationManager.requestSingleUpdate]。
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFreshLocation(lm: LocationManager): Location? {
        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestCurrentLocationApi30(lm)
            } else {
                requestSingleUpdateLegacy(lm)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocationApi30(lm: LocationManager): Location? {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                val executor = Dispatchers.IO.asExecutor()
                var completed = false

                val finishOnce: (Location?) -> Unit = { loc ->
                    if (!completed) {
                        completed = true
                        signal.cancel()
                        cont.resume(loc)
                    }
                }

                try {
                    lm.getCurrentLocation(
                        LocationManager.GPS_PROVIDER,
                        signal,
                        executor,
                        { location -> finishOnce(location) }
                    )
                } catch (e: Exception) {
                    DebugLog.w("EnvironmentProvider: getCurrentLocation 失败: ${e.message}")
                    finishOnce(null)
                }

                cont.invokeOnCancellation {
                    finishOnce(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun requestSingleUpdateLegacy(lm: LocationManager): Location? {
        return suspendCancellableCoroutine { cont ->
            var listener: LocationListener? = null
            var completed = false

            val finishOnce: (Location?) -> Unit = { loc ->
                if (!completed) {
                    completed = true
                    listener?.let { lm.removeUpdates(it) }
                    cont.resume(loc)
                }
            }

            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    finishOnce(location)
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    finishOnce(null)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }

            try {
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }

                if (provider == null) {
                    finishOnce(null)
                    return@suspendCancellableCoroutine
                }

                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                DebugLog.w("EnvironmentProvider: requestSingleUpdate 失败: ${e.message}")
                finishOnce(null)
            }

            cont.invokeOnCancellation {
                finishOnce(null)
            }
        }
    }

    private fun ageMs(location: Location): Long {
        return System.currentTimeMillis() - location.time
    }

    fun reverseGeocode(lat: Double, lon: Double, http: OkHttpClient = HttpClients.default): GeocodingResult? {
        return try {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&accept-language=zh-CN"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Opedrgent/1.0")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val displayName = json.optString("display_name").ifBlank { null } ?: return null
                val address = json.optJSONObject("address")
                val detail = address?.let { addr ->
                    val parts = mutableListOf<String>()
                    addr.optString("city", "").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                    addr.optString("district", "").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                    addr.optString("town", "").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                    addr.optString("state", "").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                    addr.optString("country", "").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                    parts.joinToString(", ").takeIf { it.isNotBlank() }
                }
                GeocodingResult(displayName = displayName, detail = detail)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun forwardGeocode(query: String, http: OkHttpClient = HttpClients.default): Pair<Double, Double>? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Opedrgent/1.0")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val arr = org.json.JSONArray(body)
                if (arr.length() == 0) return null
                val obj = arr.getJSONObject(0)
                Pair(obj.getDouble("lat"), obj.getDouble("lon"))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getLanguageCode(context: Context): String {
        return Locale.getDefault().toString()
    }
}
