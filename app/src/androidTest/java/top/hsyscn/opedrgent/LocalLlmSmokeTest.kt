package top.hsyscn.opedrgent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.llm.LocalLlmState

/**
 * 本地大模型部署真机自检。
 *
 * 目的：在 arm64 真机上一键验证端侧推理这条「逻辑测试覆盖不到」的路径——
 * 模型能加载、Engine 就绪、能真实生成文字、用完能卸载释放。
 *
 * 前置：先在 App「本地模型管理」内下载任一模型（本自检优先选已下载中最小的，
 * 不自动下载数 GB 文件）。运行：
 *   adb shell am instrument -w -e class top.hsyscn.opedrgent.LocalLlmSmokeTest \
 *     top.hsyscn.opedrgent.test/androidx.test.runner.AndroidJUnitRunner
 * 结果与延迟会打印到 logcat（标签 TestRunner / System.out），可截图作为本地部署证据。
 */
@RunWith(AndroidJUnit4::class)
class LocalLlmSmokeTest {

    @Test
    fun localModel_loads_and_generates() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LocalLlmEngine.getInstance(ctx)

        val info = AvailableLocalModels.MODELS
            .filter { engine.isModelDownloaded(it) }
            .minByOrNull { it.sizeMb }
        if (info == null) {
            fail("尚未下载任何本地模型；请先在 App「本地模型管理」内下载模型，再运行本自检")
            return@runBlocking
        }

        val path = engine.getModelPath(info)
        if (path == null) {
            fail("模型条目存在但文件缺失：${info.id}")
            return@runBlocking
        }

        val config = AvailableLocalModels.buildInferenceConfig(info)
        val loaded = engine.loadModel(path, info, config)
        assertTrue(
            "模型加载失败：${(engine.state as? LocalLlmState.Error)?.message ?: engine.state}",
            loaded,
        )
        assertTrue("Engine 未进入就绪状态：${engine.state}", engine.isReady)

        val resp = engine.generate("请用一句中文介绍你自己。")
        assertTrue(
            "推理返回为空或错误：${resp.text}",
            resp.text.isNotBlank() && !resp.text.startsWith("["),
        )

        // 诊断信息：便于真机验证后截图，作为「本地推理真实可跑」的证据
        println(
            "本地模型自检通过 | 模型=${info.id} | 首句字符数=${resp.text.length} | " +
                "延迟=${resp.latencyMs}ms | 输出=${resp.text.take(120)}"
        )

        engine.unload()
        assertFalse("卸载后 Engine 仍标记就绪", engine.isReady)
    }
}
