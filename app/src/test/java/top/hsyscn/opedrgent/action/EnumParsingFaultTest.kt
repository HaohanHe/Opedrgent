package top.hsyscn.opedrgent.action

import org.junit.Assert.assertEquals
import org.junit.Test
import top.hsyscn.opedrgent.cultivation.model.FollowUpStatus
import top.hsyscn.opedrgent.cultivation.model.IssueMark
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens

/**
 * 各枚举 fromName 容错解析的故障注入测试（纯 JVM）。
 *
 * 覆盖：null / 空串 / 纯空白 / 大小写混杂 / 首尾空白 / 连字符变体 / 未知值。
 * 验证模型传来的脏字符串不会导致未捕获异常，且按契约回退到默认值。
 */
class EnumParsingFaultTest {

    // ---------- ActionStatus ----------

    @Test
    fun `ActionStatus fromName 容错`() {
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName(null))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName(""))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName("   "))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName("bogus"))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName("open"))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName("OPEN"))
        assertEquals(ActionStatus.DONE, ActionStatus.fromName("done"))
        assertEquals(ActionStatus.DONE, ActionStatus.fromName("DONE"))
        assertEquals(ActionStatus.DEFERRED, ActionStatus.fromName("deferred"))
        assertEquals(ActionStatus.DEFERRED, ActionStatus.fromName("DEFERRED"))
        // 带首尾空白：equals ignoreCase 不会 trim，" done " 不匹配 → 回退 OPEN（固化当前行为）
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName(" done "))
    }

    // ---------- ActionKind ----------

    @Test
    fun `ActionKind fromName 容错与连字符变体`() {
        assertEquals(ActionKind.GENERAL, ActionKind.fromName(null))
        assertEquals(ActionKind.GENERAL, ActionKind.fromName(""))
        assertEquals(ActionKind.GENERAL, ActionKind.fromName("   "))
        assertEquals(ActionKind.GENERAL, ActionKind.fromName("bogus"))
        assertEquals(ActionKind.SAYING, ActionKind.fromName("saying"))
        assertEquals(ActionKind.SAYING, ActionKind.fromName("SAYING"))
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("next_step"))
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("next-step"))
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("nextstep"))
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("NEXT_STEP"))
        assertEquals(ActionKind.GENERAL, ActionKind.fromName("general"))
    }

    // ---------- ReflectionLens ----------

    @Test
    fun `ReflectionLens fromName 容错`() {
        assertEquals(ReflectionLens.CRITIQUE, ReflectionLens.fromName(null))
        assertEquals(ReflectionLens.CRITIQUE, ReflectionLens.fromName(""))
        assertEquals(ReflectionLens.CRITIQUE, ReflectionLens.fromName("bogus"))
        assertEquals(ReflectionLens.CRITIQUE, ReflectionLens.fromName("critique"))
        assertEquals(ReflectionLens.COGNITIVE, ReflectionLens.fromName("cognitive"))
        assertEquals(ReflectionLens.EXEMPLAR, ReflectionLens.fromName("exemplar"))
    }

    // ---------- MirrorRoute ----------

    @Test
    fun `MirrorRoute fromName 容错`() {
        assertEquals(MirrorRoute.ANALYZE, MirrorRoute.fromName(null))
        assertEquals(MirrorRoute.ANALYZE, MirrorRoute.fromName(""))
        assertEquals(MirrorRoute.ANALYZE, MirrorRoute.fromName("bogus"))
        assertEquals(MirrorRoute.ANALYZE, MirrorRoute.fromName("analyze"))
        assertEquals(MirrorRoute.SUPPORT, MirrorRoute.fromName("support"))
        assertEquals(MirrorRoute.CRISIS, MirrorRoute.fromName("crisis"))
    }

    // ---------- FollowUpStatus ----------

    @Test
    fun `FollowUpStatus fromName 容错`() {
        assertEquals(FollowUpStatus.OPEN, FollowUpStatus.fromName(null))
        assertEquals(FollowUpStatus.OPEN, FollowUpStatus.fromName(""))
        assertEquals(FollowUpStatus.OPEN, FollowUpStatus.fromName("bogus"))
        assertEquals(FollowUpStatus.DONE, FollowUpStatus.fromName("done"))
        assertEquals(FollowUpStatus.DROPPED, FollowUpStatus.fromName("dropped"))
    }

    // ---------- IssueMark ----------

    @Test
    fun `IssueMark fromName 容错`() {
        assertEquals(IssueMark.NONE, IssueMark.fromName(null))
        assertEquals(IssueMark.NONE, IssueMark.fromName(""))
        assertEquals(IssueMark.NONE, IssueMark.fromName("bogus"))
        assertEquals(IssueMark.ACCEPTED, IssueMark.fromName("accepted"))
        assertEquals(IssueMark.DISAGREED, IssueMark.fromName("disagreed"))
        assertEquals(IssueMark.IMPROVED, IssueMark.fromName("improved"))
    }
}
