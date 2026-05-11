package top.hsyscn.opedrgent.utils

import android.util.Log

object DebugLog {
    private const val TAG = "Opedrgent"
    var enabled: Boolean = false

    fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }

    fun i(msg: String) {
        if (enabled) Log.i(TAG, msg)
    }

    fun w(msg: String) {
        if (enabled) Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (enabled) {
            if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        }
    }

    fun json(label: String, json: String) {
        if (!enabled) return
        val trimmed = if (json.length > 500) json.take(500) + "...(${json.length} chars)" else json
        Log.d(TAG, "[$label] $trimmed")
    }
}
