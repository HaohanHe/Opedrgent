package top.hsyscn.opedrgent.env

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.hsyscn.opedrgent.network.HttpClients
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(context: Context): Pair<Double, Double>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        for (provider in providers) {
            val loc = lm.getLastKnownLocation(provider)
            if (loc != null) return Pair(loc.latitude, loc.longitude)
        }
        return null
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
