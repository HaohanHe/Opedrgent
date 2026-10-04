package top.hsyscn.opedrgent.interview

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.hsyscn.opedrgent.interview.FullDuplexAudioEngine.DuplexState

/**
 * 全双工状态机（[FullDuplexAudioEngine.DuplexState]）在 Robolectric 下的状态迁移与门控验证。
 *
 * 本类只覆盖「纯状态机 + 权限门 + 入队门 + 状态回调」逻辑，绝不触碰真实音频硬件：
 *  - 不调用 connect() 成功后的 AudioRecord/AudioTrack 创建路径（RECORD_AUDIO 权限默认未授予，
 *    connect() 在创建任何硬件句柄之前就抛 SecurityException，正好验证权限门）；
 *  - 不调用 start()（它会拉起 startRecording()/startPlayback() 真实采集/播放线程）；
 *  - 到达 CONNECTED 态走的是公共方法 stop()（其内部对 null 的 recordJob/audioRecord 等全部
 *    runCatching 空转），不经过硬件。
 *
 * 真实未覆盖项（如实保留）：AudioRecord/AudioTrack 采集播放、VAD 帧处理、barge-in 连续确认、
 * 看门狗超时、管线自恢复重建——均需真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FullDuplexStateMachineRobolectricTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var engine: FullDuplexAudioEngine

    @Before
    fun setUp() {
        engine = FullDuplexAudioEngine(ctx)
    }

    @Test
    fun `fresh engine starts in IDLE`() {
        assertEquals(DuplexState.IDLE, engine.state)
    }

    @Test
    fun `connect without RECORD_AUDIO permission throws and stays IDLE`() {
        // 显式确保未授予（Robolectric 默认即未授予）
        assertEquals(PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO))

        var thrown: SecurityException? = null
        try {
            engine.connect()
        } catch (e: SecurityException) {
            thrown = e
        }
        assertNotNull(thrown)
        // 权限门在创建 AudioRecord/AudioTrack 之前触发，状态不得迁移
        assertEquals(DuplexState.IDLE, engine.state)
    }

    @Test
    fun `start rejected while IDLE no hardware started`() {
        val events = mutableListOf<DuplexState>()
        engine.onStateChanged { events.add(it) }

        engine.start()

        assertEquals(DuplexState.IDLE, engine.state)
        // start() 被门控拒绝，不应产生任何状态迁移
        assertTrue("start() 拒绝时不应触发状态回调: $events", events.isEmpty())
    }

    @Test
    fun `aiSpeak rejected while IDLE`() {
        val events = mutableListOf<DuplexState>()
        engine.onStateChanged { events.add(it) }

        val accepted = engine.aiSpeak(ByteArray(64) { it.toByte() })

        assertFalse("IDLE 态 aiSpeak 应被拒绝", accepted)
        assertEquals(DuplexState.IDLE, engine.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `aiSpeak empty payload rejected even when state allows`() {
        // 先经 stop() 离开 IDLE（stop 对空资源空转），到达 CONNECTED
        engine.stop()
        assertEquals(DuplexState.CONNECTED, engine.state)

        assertFalse(engine.aiSpeak(ByteArray(0)))
        // 空载荷不入队、不迁移到 AI_SPEAKING
        assertEquals(DuplexState.CONNECTED, engine.state)
    }

    @Test
    fun `connect is idempotent and returns early when not IDLE`() {
        // stop() 把 IDLE -> CONNECTED（真实行为：停在已连接态等待 start）
        engine.stop()
        assertEquals(DuplexState.CONNECTED, engine.state)

        // 再次 connect：state != IDLE，直接 return，不抛权限异常、不重复迁移
        engine.connect()

        assertEquals(DuplexState.CONNECTED, engine.state)
    }

    @Test
    fun `state transition sequence CONNECTED to AI_SPEAKING to LISTENING to MUTED`() {
        val events = mutableListOf<DuplexState>()
        engine.onStateChanged { events.add(it) }

        // stop(): IDLE -> CONNECTED
        engine.stop()
        // aiSpeak 入队: CONNECTED -> AI_SPEAKING
        val accepted = engine.aiSpeak(ByteArray(128) { 7 })
        assertTrue(accepted)
        assertEquals(DuplexState.AI_SPEAKING, engine.state)
        // stopAiSpeaking(): AI_SPEAKING -> LISTENING
        engine.stopAiSpeaking()
        assertEquals(DuplexState.LISTENING, engine.state)
        // mute(true): LISTENING -> MUTED
        engine.muteUser(true)
        assertEquals(DuplexState.MUTED, engine.state)

        assertEquals(
            listOf(
                DuplexState.CONNECTED,
                DuplexState.AI_SPEAKING,
                DuplexState.LISTENING,
                DuplexState.MUTED,
            ),
            events,
        )
    }

    @Test
    fun `aiSpeak rejected while MUTED and stays MUTED`() {
        engine.stop()                 // -> CONNECTED
        engine.aiSpeak(ByteArray(16)) // -> AI_SPEAKING
        engine.stopAiSpeaking()       // -> LISTENING
        engine.muteUser(true)         // -> MUTED
        assertEquals(DuplexState.MUTED, engine.state)

        val eventsBefore = engine.state
        val accepted = engine.aiSpeak(ByteArray(64) { 3 })

        assertFalse("MUTED 态 aiSpeak 应被拒绝", accepted)
        assertEquals(eventsBefore, engine.state)
    }

    @Test
    fun `mute is idempotent and unmute returns to LISTENING when not playing`() {
        val events = mutableListOf<DuplexState>()
        engine.onStateChanged { events.add(it) }

        engine.stop()           // -> CONNECTED
        engine.muteUser(true)   // CONNECTED -> MUTED（非 LISTENING 也允许静音）
        assertEquals(DuplexState.MUTED, engine.state)

        // 再次 mute(true)：已在 MUTED，changeState 同态短路，不产生重复回调
        engine.muteUser(true)
        assertEquals(DuplexState.MUTED, engine.state)

        // unmute：isPlaying=false（未拉起播放线程），回落 LISTENING
        engine.muteUser(false)
        assertEquals(DuplexState.LISTENING, engine.state)

        // 回调序列：CONNECTED, MUTED（第二次 mute 被短路），LISTENING
        assertEquals(
            listOf(DuplexState.CONNECTED, DuplexState.MUTED, DuplexState.LISTENING),
            events,
        )
    }

    @Test
    fun `checkBargeIn resets via resetBargeIn`() {
        // 初始未检测到插话
        assertFalse(engine.checkBargeIn())
        // stopAiSpeaking 会置 bargeInDetected=true（插话中断语义）
        engine.stop()
        engine.aiSpeak(ByteArray(8))
        engine.stopAiSpeaking()
        assertTrue("stopAiSpeaking 应标记 bargeInDetected", engine.checkBargeIn())
        engine.resetBargeIn()
        assertFalse(engine.checkBargeIn())
    }
}
