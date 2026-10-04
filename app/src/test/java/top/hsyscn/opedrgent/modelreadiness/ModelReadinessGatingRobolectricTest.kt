package top.hsyscn.opedrgent.modelreadiness

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.settings.ApiSettings

/**
 * 端侧模型就绪门控（[ModelReadinessRepository]）在 Robolectric 下的判定验证。
 *
 * 历史上该仓库因依赖 ActivityManager / ConnectivityManager / TextToSpeech /
 * ModelDownloadManager(context) / ApiSettings(context)，纯 JVM 无法实例化，
 * 相关「模型缺失 / active id 指向已删除模型 / 枚举未知回退」判定未被覆盖。
 * Robolectric 下这些系统服务均有 shadow，可真实实例化并断言快照行为。
 *
 * 本类只断言就绪门控逻辑，不触发真实下载、不触碰 NPU/GPU 推理：
 *  - 用稀疏文件（setLength，不真实分配磁盘）伪造「已下载完成」以驱动 READY 分支；
 *  - 端侧真实文件加载、NPU/GPU 推理、真实 TTS 引擎音色均不在此覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelReadinessGatingRobolectricTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var repo: ModelReadinessRepository
    private lateinit var apiSettings: ApiSettings

    @Before
    fun setUp() {
        resetRepoSingleton()
        // 清空明文偏好，保证 active_local_model_id 不串用例
        ctx.getSharedPreferences("opedrgent_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        apiSettings = ApiSettings(ctx)
        repo = ModelReadinessRepository.getInstance(ctx)
    }

    @After
    fun tearDown() {
        // 删除用稀疏文件伪造的已下载模型，避免污染后续用例
        runCatching {
            val mm = ModelDownloadManager.getInstance(ctx)
            AvailableLocalModels.MODELS.forEach { mm.getModelFile(it.id)?.delete() }
        }
        resetRepoSingleton()
    }

    private fun resetRepoSingleton() {
        runCatching { ModelReadinessRepository.getInstance(ctx).release() }
        runCatching {
            val f = ModelReadinessRepository::class.java.declaredFields.first {
                it.type == ModelReadinessRepository::class.java
            }
            f.isAccessible = true
            f.set(null, null)
        }
    }

    @Test
    fun `selectLlm unknown id rejected and not persisted`() {
        val before = apiSettings.getActiveLocalModelId()

        val ok = repo.selectLlm("totally-unknown-model-id")

        assertFalse(ok)
        assertEquals(before, apiSettings.getActiveLocalModelId())
    }

    @Test
    fun `selectLlm known id accepted and persisted`() {
        val known = AvailableLocalModels.MODELS.first().id

        val ok = repo.selectLlm(known)

        assertTrue(ok)
        assertEquals(known, apiSettings.getActiveLocalModelId())
    }

    @Test
    fun `startLlm unknown id is a safe no-op`() {
        // 未知 id：仅打日志后 return，不改动 selectedLlmId、不触发下载流
        repo.startLlm("bogus-id-xxx")
        // 不抛异常即门控生效；快照 llm 仍为初始 NOT_PRESENT
        assertEquals(ReadyState.NOT_PRESENT, repo.snapshot.value.llm.state)
    }

    @Test
    fun `startAsr unknown enum name is a safe no-op`() {
        // ModelType.valueOf("NO_SUCH_TYPE") 抛 IllegalArgumentException，被吞后 return
        repo.startAsr("NO_SUCH_TYPE_ENUM")
        // 不抛异常即枚举未知回退生效
    }

    @Test
    fun `refresh with active id pointing to deleted model falls back to catalog`() {
        // 持久化一个「目录里已不存在」的 active id
        apiSettings.setActiveLocalModelId("deleted-model-id-not-in-catalog")

        repo.refresh()

        // 门控：persisted id 经 AvailableLocalModels.findById 命中 null，
        // 回落到推荐 id 并重新持久化——不再保留悬空 id
        val recovered = apiSettings.getActiveLocalModelId()
        assertNotNull(recovered)
        assertTrue(
            "回落后的 active id 必须在目录中: $recovered",
            AvailableLocalModels.findById(recovered!!) != null,
        )
        // 列表视图里恰好一个 active 项，且属于目录
        val active = repo.listLocalModelEntries().filter { it.active }
        assertEquals(1, active.size)
        assertNotNull(AvailableLocalModels.findById(active.first().id))
    }

    @Test
    fun `missing model resolves to NOT_PRESENT not fake READY`() {
        // 选一个目录内、但磁盘上没有文件的模型
        val id = AvailableLocalModels.MODELS.last().id
        // 确保文件不存在
        runCatching { ModelDownloadManager.getInstance(ctx).getModelFile(id)?.delete() }

        repo.selectLlm(id)

        assertEquals(ReadyState.NOT_PRESENT, repo.snapshot.value.llm.state)
        // filePath 不应伪造
        assertNull(repo.snapshot.value.llm.filePath)
    }

    @Test
    fun `downloaded model on disk resolves to READY`() {
        val info = AvailableLocalModels.MODELS.first { it.id == "functiongemma-270m-it" }
        val mm = ModelDownloadManager.getInstance(ctx)
        val file = mm.getModelFile(info.id)!!
        file.parentFile!!.mkdirs()
        // 稀疏文件：长度达标但不真实分配磁盘，驱动 isModelComplete -> READY
        java.io.RandomAccessFile(file, "rw").use {
            it.setLength(info.sizeMb * 1024L * 1024L)
        }
        assertTrue(file.exists())

        repo.selectLlm(info.id)

        val llm = repo.snapshot.value.llm
        assertEquals(ReadyState.READY, llm.state)
        assertEquals(file.absolutePath, llm.filePath)
    }

    @Test
    fun `listLocalModelEntries reflects catalog download and active flags`() {
        val entries = repo.listLocalModelEntries()

        assertEquals(AvailableLocalModels.MODELS.size, entries.size)
        // 每个 id 都能在目录找到
        entries.forEach { e ->
            assertNotNull(AvailableLocalModels.findById(e.id))
        }
        // 未选择时至多一个 active
        assertTrue(entries.count { it.active } <= 1)
    }
}
