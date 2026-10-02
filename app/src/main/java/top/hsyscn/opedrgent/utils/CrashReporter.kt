package top.hsyscn.opedrgent.utils

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃/异常本地日志。
 *
 * 设计目标：
 *  - 仅记录技术信息（时间、版本、线程、异常类名与 message、堆栈片段），
 *    不记录用户输入内容、笔记/转写文本、密钥、Uri 中的敏感参数。
 *  - 崩溃时先同步、有界地写一条记录到 filesDir/logs/crash.log，
 *    再把异常交回上一个 UncaughtExceptionHandler，保持系统原有的崩溃/重启语义。
 *  - 不依赖 DebugLog.enabled，任何情况下都写。
 *  - 文件按大小轮转（> ~1MB 滚动为 crash.1.log，最多保留 1 个旧文件）。
 *  - 写文件加进程内锁，避免多线程交错。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "crash.log"
    private const val OLD_LOG_FILE = "crash.1.log"
    private const val MAX_BYTES = 1L * 1024L * 1024L // ~1MB
    private const val MAX_STACK_CHARS = 4096

    /** 进程内写锁。 */
    private val lock = Any()

    /** 上一个默认 handler，install 后被我们包装。 */
    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** install 是否已经执行过（幂等）。 */
    @Volatile
    private var installed: Boolean = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 注册全局 UncaughtExceptionHandler。幂等。
     * 保存原 default handler；新 handler 先落盘，再交回原 handler。
     */
    fun install(context: Context) {
        synchronized(lock) {
            if (installed) return
            installed = true
            // 初始化日志目录，避免崩溃路径上再做 IO 异常
            runCatching { ensureLogDir(context) }
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                // 崩溃路径：同步、有界地写一条记录。
                runCatching {
                    writeRecordLocked(
                        context = context.applicationContext,
                        level = "FATAL",
                        tag = "Uncaught",
                        message = "Uncaught exception on thread '${thread.name}'",
                        throwable = throwable,
                    )
                }
                // 交回原 handler，保持系统崩溃/重启语义，不吞崩溃。
                val prev = previousHandler
                when {
                    prev != null -> prev.uncaughtException(thread, throwable)
                    else -> {
                        // 没有上一个 handler 时，退回到线程组默认行为：打印到 logcat 并中断线程。
                        Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
                        Runtime.getRuntime().halt(10)
                    }
                }
            }
        }
    }

    /** 记录一条 Error（带 Throwable）。 */
    fun logError(tag: String, t: Throwable) {
        writeRecord("ERROR", tag, null, t)
    }

    /** 记录一条 Error（带可选 message 与可选 Throwable）。message 必须为技术描述。 */
    fun logError(tag: String, message: String, t: Throwable? = null) {
        writeRecord("ERROR", tag, message, t)
    }

    /** 记录一条 Warn。message 必须为技术描述。 */
    fun logWarn(tag: String, message: String) {
        writeRecord("WARN", tag, message, null)
    }

    /**
     * 协程级异常 handler：异常落本地日志，不连带闪退。
     * 注意：调用方需通过 Application 传入 context 才能写盘；这里用 LazyHolder 保存的 appContext。
     */
    val coroutineHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { ctx, throwable ->
        val ctxName = ctx[kotlinx.coroutines.CoroutineName]?.name ?: ctx.toString()
        writeRecord(
            level = "COROUTINE",
            tag = "Coroutine",
            message = "Exception in context: $ctxName",
            throwable = throwable,
        )
    }

    /** 当前日志文件路径（filesDir/logs/crash.log）。 */
    fun logFile(context: Context): File {
        return File(ensureLogDir(context), LOG_FILE)
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun ensureLogDir(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun writeRecord(level: String, tag: String, message: String?, throwable: Throwable?) {
        val ctx = appContext
        if (ctx == null) {
            // 还没有 Application context（极早路径）：退到 logcat。
            Log.e(tag, message ?: throwable?.message ?: "null", throwable)
            return
        }
        synchronized(lock) {
            runCatching { writeRecordLocked(ctx, level, tag, message, throwable) }
                .onFailure { Log.w(TAG, "writeRecord failed", it) }
        }
    }

    private fun writeRecordLocked(
        context: Context,
        level: String,
        tag: String,
        message: String?,
        throwable: Throwable?,
    ) {
        val logFile = logFile(context)
        rotateIfNeeded(logFile)

        val now = dateFormat.format(Date())
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val versionCode = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
        }.getOrNull() ?: -1L
        val threadName = Thread.currentThread().name
        val exceptionClass = throwable?.let { it.javaClass.name } ?: ""
        val exceptionMessage = throwable?.message ?: ""
        val stack = throwable?.let { boundedStackTrace(it) } ?: ""

        val sb = StringBuilder(256)
        sb.append(now).append(' ')
        sb.append('[').append(level).append("] ")
        sb.append(tag).append(": ")
        if (!message.isNullOrEmpty()) sb.append(message).append(' ')
        if (throwable != null) {
            sb.append("\n  at ").append(exceptionClass)
            if (exceptionMessage.isNotEmpty()) sb.append(": ").append(exceptionMessage)
            if (stack.isNotEmpty()) sb.append("\n  stack:\n").append(stack)
        }
        sb.append('\n')
        // 附带技术元数据（仅一次/每条）
        sb.append("  meta: app=").append(context.packageName)
            .append(" v=").append(versionName).append('(').append(versionCode).append(')')
            .append(" thread=").append(threadName)
            .append('\n')
        sb.append("----\n")

        logFile.appendText(sb.toString(), Charsets.UTF_8)
    }

    /** 堆栈截断到合理长度，避免 OOM。 */
    private fun boundedStackTrace(t: Throwable): String {
        val sw = StringWriter(256)
        t.printStackTrace(PrintWriter(sw))
        val full = sw.toString()
        return if (full.length <= MAX_STACK_CHARS) {
            full
        } else {
            full.substring(0, MAX_STACK_CHARS) + "\n...<truncated>"
        }
    }

    /** 简单轮转：当前文件超过阈值，则把 crash.log 改名为 crash.1.log（覆盖旧的 .1）。 */
    private fun rotateIfNeeded(logFile: File) {
        if (!logFile.exists() || logFile.length() < MAX_BYTES) return
        val dir = logFile.parentFile ?: return
        val old = File(dir, OLD_LOG_FILE)
        if (old.exists()) old.delete()
        logFile.renameTo(old)
    }

    /**
     * 由 OpedrgentApplication 在 onCreate 里 attach，供 coroutineHandler 等无 context 的入口写盘。
     * 包内可见，避免外部直接写。
     */
    @Volatile
    internal var appContext: Context? = null
        private set

    internal fun attachAppContext(context: Context) {
        appContext = context.applicationContext
    }
}
