package top.hsyscn.opedrgent.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

class ResearchSession(
    val sessionId: String,
    private val storageFile: File? = null,
) {
    private val storage = mutableMapOf<String, Any?>()
    private val mutex = Mutex()

    suspend fun set(key: String, value: Any?) {
        mutex.withLock {
            storage[key] = value
        }
        DebugLog.d("ResearchSession[$sessionId]: set $key = ${value?.toString()?.take(50)}")
        saveIfPersistent()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun get(key: String): Any? {
        mutex.withLock {
            return storage[key]
        }
    }

    suspend fun remove(key: String) {
        mutex.withLock {
            storage.remove(key)
        }
        DebugLog.d("ResearchSession[$sessionId]: removed $key")
    }

    suspend fun clear() {
        mutex.withLock {
            storage.clear()
        }
        DebugLog.d("ResearchSession[$sessionId]: cleared")
    }

    suspend fun keys(): List<String> {
        mutex.withLock {
            return storage.keys.toList()
        }
    }

    suspend fun has(key: String): Boolean {
        mutex.withLock {
            return storage.containsKey(key)
        }
    }

    private suspend fun saveIfPersistent() {
        storageFile ?: return
        val snapshot: Map<String, Any?>
        mutex.withLock {
            snapshot = storage.toMap()
        }
        try {
            storageFile.parentFile?.mkdirs()
            val lines = snapshot.entries.joinToString("\n") { (k, v) ->
                "$k=${v.toString().replace("\n", "\\n").take(200)}"
            }
            storageFile.writeText(lines)
        } catch (e: Exception) {
            DebugLog.w("ResearchSession[$sessionId]: failed to persist - ${e.message}")
        }
    }

    fun loadPersistent(): Int {
        storageFile ?: return 0
        if (!storageFile.exists()) return 0
        return try {
            val lines = storageFile.readLines()
            var loaded = 0
            for (line in lines) {
                val eq = line.indexOf('=')
                if (eq > 0) {
                    val k = line.substring(0, eq)
                    val v = line.substring(eq + 1).replace("\\n", "\n")
                    storage[k] = v
                    loaded++
                }
            }
            DebugLog.d("ResearchSession[$sessionId]: loaded $loaded entries from disk")
            loaded
        } catch (e: Exception) {
            DebugLog.w("ResearchSession[$sessionId]: failed to load - ${e.message}")
            0
        }
    }

    override fun toString(): String = "ResearchSession(id=$sessionId, entries=${storage.size})"
}

class SessionManager {
    private val sessions = mutableMapOf<String, ResearchSession>()
    private val mutex = Mutex()

    suspend fun getOrCreate(sessionId: String, storageDir: File? = null): ResearchSession {
        mutex.withLock {
            return sessions.getOrPut(sessionId) {
                val file = storageDir?.let { File(it, "session_$sessionId.txt") }
                ResearchSession(sessionId, file).also { it.loadPersistent() }
            }
        }
    }

    suspend fun destroy(sessionId: String) {
        mutex.withLock {
            sessions.remove(sessionId)
            DebugLog.i("SessionManager: destroyed session $sessionId")
        }
    }

    suspend fun listSessions(): List<String> {
        mutex.withLock {
            return sessions.keys.toList()
        }
    }
}
