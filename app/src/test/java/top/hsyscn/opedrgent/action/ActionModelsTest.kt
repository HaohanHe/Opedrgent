package top.hsyscn.opedrgent.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * [ActionStatus] / [ActionKind] 枚举解析与 [ActionItem] 数据类的纯 JVM 单元测试。
 *
 * 说明：真正的「完成/搁置/重开」持久化在 action/ActionStore.kt，其依赖
 * Context + android.database.sqlite，属 Android 框架、纯 JVM 不可测，故本类只覆盖
 * 状态/类别枚举的解析语义与 ActionItem 数据契约，不测 ActionStore。
 */
class ActionModelsTest {

    // ---------- ActionStatus.fromName ----------

    @Test
    fun `ActionStatus open 大小写混写映射 OPEN`() {
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName("open"))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName("OPEN"))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName("OpEn"))
    }

    @Test
    fun `ActionStatus done 映射 DONE`() {
        assertEquals(ActionStatus.DONE, ActionStatus.fromName("done"))
        assertEquals(ActionStatus.DONE, ActionStatus.fromName("DONE"))
        assertEquals(ActionStatus.DONE, ActionStatus.fromName("Done"))
    }

    @Test
    fun `ActionStatus deferred 映射 DEFERRED`() {
        assertEquals(ActionStatus.DEFERRED, ActionStatus.fromName("deferred"))
        assertEquals(ActionStatus.DEFERRED, ActionStatus.fromName("DEFERRED"))
    }

    @Test
    fun `ActionStatus null 空串 未知值回退 OPEN`() {
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName(null))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName(""))
        assertEquals(ActionStatus.OPEN, ActionStatus.fromName("bogus"))
    }

    // ---------- ActionKind.fromName ----------

    @Test
    fun `ActionKind saying 大小写映射 SAYING`() {
        assertEquals(ActionKind.SAYING, ActionKind.fromName("saying"))
        assertEquals(ActionKind.SAYING, ActionKind.fromName("SAYING"))
        assertEquals(ActionKind.SAYING, ActionKind.fromName("Saying"))
    }

    @Test
    fun `ActionKind next step 多种写法映射 NEXT_STEP`() {
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("next_step"))
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("next-step"))
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("nextstep"))
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("NEXT-STEP"))
    }

    @Test
    fun `ActionKind 前后空格被 trim`() {
        assertEquals(ActionKind.NEXT_STEP, ActionKind.fromName("  next_step  "))
        assertEquals(ActionKind.SAYING, ActionKind.fromName("   saying   "))
    }

    @Test
    fun `ActionKind general 映射 GENERAL`() {
        assertEquals(ActionKind.GENERAL, ActionKind.fromName("general"))
        assertEquals(ActionKind.GENERAL, ActionKind.fromName("General"))
    }

    @Test
    fun `ActionKind null 空串 未知值回退 GENERAL`() {
        assertEquals(ActionKind.GENERAL, ActionKind.fromName(null))
        assertEquals(ActionKind.GENERAL, ActionKind.fromName(""))
        assertEquals(ActionKind.GENERAL, ActionKind.fromName("unknown_kind"))
    }

    // ---------- ActionItem 数据契约 ----------

    @Test
    fun `ActionItem 默认值 status OPEN kind GENERAL completedAt 0 id 0`() {
        val item = ActionItem(title = "写周报")
        assertEquals(0L, item.id)
        assertEquals(ActionKind.GENERAL, item.kind)
        assertEquals(ActionStatus.OPEN, item.status)
        assertEquals(0L, item.completedAt)
    }

    @Test
    fun `copy 改 status 不污染原对象`() {
        val original = ActionItem(title = "写周报")
        val done = original.copy(status = ActionStatus.DONE)
        val deferred = original.copy(status = ActionStatus.DEFERRED)

        assertEquals(ActionStatus.OPEN, original.status)
        assertEquals(ActionStatus.DONE, done.status)
        assertEquals(ActionStatus.DEFERRED, deferred.status)
        // copy 生成新实例
        assertNotSame(original, done)
    }

    @Test
    fun `ActionItem 数据类 equals 按字段比较`() {
        val a = ActionItem(title = "写周报", status = ActionStatus.OPEN)
        val b = ActionItem(title = "写周报", status = ActionStatus.OPEN)
        assertEquals(a, b)

        val c = b.copy(status = ActionStatus.DONE)
        assertEquals(false, a == c)
    }
}
