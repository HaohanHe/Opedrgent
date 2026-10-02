package top.hsyscn.opedrgent

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import top.hsyscn.opedrgent.utils.CrashReporter

/**
 * 自定义 Application：
 *  - 安装全局 UncaughtExceptionHandler（崩溃先落 filesDir/logs/crash.log，再交回系统）。
 *  - 暴露应用级 [appScope]：SupervisorJob + Default + CrashReporter.coroutineHandler，
 *    单子协程失败不连累兄弟，异常落本地日志不连带闪退。
 */
class OpedrgentApplication : Application() {

    lateinit var appScope: CoroutineScope
        private set

    override fun onCreate() {
        super.onCreate()
        // 先把 applicationContext 挂到 CrashReporter，供无 context 入口（如 coroutineHandler）写盘。
        CrashReporter.attachAppContext(this)
        // 安装全局未捕获异常 handler（幂等）。
        CrashReporter.install(this)
        // 应用级作用域：SupervisorJob 隔离子任务失败；Default 线程池；handler 落日志。
        appScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CrashReporter.coroutineHandler,
        )
    }
}
