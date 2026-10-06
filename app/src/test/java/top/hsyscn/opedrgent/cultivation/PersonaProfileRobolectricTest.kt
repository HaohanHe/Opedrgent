package top.hsyscn.opedrgent.cultivation

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.hsyscn.opedrgent.cultivation.mirror.ReflectionToolProtocol
import top.hsyscn.opedrgent.cultivation.model.PersonaProfile
import top.hsyscn.opedrgent.cultivation.model.PersonaTrait
import top.hsyscn.opedrgent.cultivation.model.PersonaTraitStatus
import top.hsyscn.opedrgent.cultivation.store.PersonaProfileStore

/**
 * 自动人格画像的编解码往返、存储版本演进与同批画像更新解析验证。
 *
 * 编解码依赖 org.json、存储构造需要 Android Context，纯 JVM 无法运行，故用 Robolectric。
 * 不覆盖真实磁盘 IO 的并发细节与 native 工具回调，那些需真机或更重集成环境。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersonaProfileRobolectricTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: PersonaProfileStore

    @Before
    fun setUp() {
        store = PersonaProfileStore(ctx)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val profile = PersonaProfile(
            version = 3,
            actualSelf = listOf(
                PersonaTrait("在对方话未说完时开始回应", "你别说了，听我说", "录音A", 1000L, PersonaTraitStatus.ACTIVE),
                PersonaTrait("旧习惯", "x", "录音B", 900L, PersonaTraitStatus.RETIRED),
            ),
            aspiredSelf = listOf(PersonaTrait("先听完再回应", "", "录音A", 1000L, PersonaTraitStatus.ACTIVE)),
            openQuestions = listOf("是否在压力下更易打断"),
            createdAt = 100L,
            updatedAt = 200L,
        )
        val decoded = PersonaProfileStore.decode(PersonaProfileStore.encode(profile))
        assertEquals(3, decoded.version)
        assertEquals(2, decoded.actualSelf.size)
        assertEquals("在对方话未说完时开始回应", decoded.actualSelf[0].text)
        assertEquals("你别说了，听我说", decoded.actualSelf[0].evidence)
        assertEquals(PersonaTraitStatus.RETIRED, decoded.actualSelf[1].status)
        assertEquals(1, decoded.aspiredSelf.size)
        assertEquals("先听完再回应", decoded.aspiredSelf[0].text)
        assertEquals(listOf("是否在压力下更易打断"), decoded.openQuestions)
        assertEquals(100L, decoded.createdAt)
    }

    @Test
    fun isEmptyReflectsTraits() {
        assertTrue(PersonaProfile().isEmpty())
        assertFalse(PersonaProfile(actualSelf = listOf(PersonaTrait("x"))).isEmpty())
        assertFalse(PersonaProfile(aspiredSelf = listOf(PersonaTrait("y"))).isEmpty())
    }

    @Test
    fun statusFromNameIsLenientAndDefaultsActive() {
        assertEquals(PersonaTraitStatus.SUPERSEDED, PersonaTraitStatus.fromName("superseded"))
        assertEquals(PersonaTraitStatus.RETIRED, PersonaTraitStatus.fromName("RETIRED"))
        assertEquals(PersonaTraitStatus.ACTIVE, PersonaTraitStatus.fromName("nonsense"))
        assertEquals(PersonaTraitStatus.ACTIVE, PersonaTraitStatus.fromName(null))
    }

    @Test
    fun saveIncrementsVersionKeepsCreatedAtAndGetReturnsIt() = runTest {
        assertNull(store.get())
        val first = store.save(PersonaProfile(version = 0, actualSelf = listOf(PersonaTrait("a")), createdAt = 1234L))
        assertEquals(1, first.version)
        assertEquals(1234L, first.createdAt)

        // 新实例（无内存缓存）应从磁盘读到同一画像
        val reopened = PersonaProfileStore(ctx)
        assertEquals(1, reopened.get()?.version)

        val second = reopened.save(PersonaProfile(version = 0, actualSelf = listOf(PersonaTrait("b"))))
        assertEquals(2, second.version)
        // 跨版本保留首次 createdAt
        assertEquals(1234L, second.createdAt)
        assertEquals("b", reopened.get()?.actualSelf?.first()?.text)
    }

    @Test
    fun parsePersonaUpdateExtractsSameBatchCall() {
        val raw = JSONObject()
            .put("summary", "本次观察")
            .put(
                "toolCalls",
                JSONArray()
                    .put(JSONObject().put("name", ReflectionToolProtocol.RECENT).put("args", JSONObject()))
                    .put(
                        JSONObject().put("name", ReflectionToolProtocol.PERSONA_UPDATE).put(
                            "args",
                            JSONObject()
                                .put("actual_self", "[]")
                                .put("aspired_self", "[]")
                                .put("open_questions", JSONArray().put("是否在疲惫时更易急躁"))
                        )
                    )
            ).toString()
        val call = ReflectionToolProtocol.parsePersonaUpdate(raw)
        assertEquals(ReflectionToolProtocol.PERSONA_UPDATE, call?.name)
        assertEquals(1, call?.args?.optJSONArray("open_questions")?.length() ?: 0)
    }

    @Test
    fun parsePersonaUpdateReturnsNullWhenAbsent() {
        val onlyRead = JSONObject().put(
            "toolCalls",
            JSONArray().put(JSONObject().put("name", ReflectionToolProtocol.RECENT).put("args", JSONObject()))
        ).toString()
        assertNull(ReflectionToolProtocol.parsePersonaUpdate(onlyRead))
        assertNull(ReflectionToolProtocol.parsePersonaUpdate(JSONObject().put("summary", "x").toString()))
        assertNull(ReflectionToolProtocol.parsePersonaUpdate("garbage not json"))
    }
}
