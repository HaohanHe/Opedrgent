package top.hsyscn.opedrgent.utils

import android.util.Log

object DebugLog {
    private const val TAG = "Opedrgent"
    @Volatile
    var enabled: Boolean = false

    fun isEnabled(): Boolean = enabled
    
    fun isDebugEnabled(): Boolean = enabled  // 别名，语义更清晰

    fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    fun i(msg: String) {
        if (enabled) Log.i(TAG, msg)
    }

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }

    fun w(msg: String) {
        if (enabled) Log.w(TAG, msg)
    }

    fun w(tag: String, msg: String) {
        if (enabled) Log.w(tag, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (enabled) {
            if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (enabled) {
            if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }
    }

    fun json(label: String, json: String) {
        if (!enabled) return
        val trimmed = if (json.length > 500) json.take(500) + "...(${json.length} chars)" else json
        Log.d(TAG, "[$label] $trimmed")
    }
}
