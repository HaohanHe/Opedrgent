package top.hsyscn.opedrgent.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * FileHasher 的纯 JVM 单元测试。
 * 覆盖：标准已知向量、跨 8192 缓冲的大文件一致性、matchesSha256 的空值/大小写/错误/读失败分支。
 */
class FileHasherTest {

    private fun writeTemp(content: ByteArray): File {
        val f = File.createTempFile("fhtmp", ".bin")
        f.deleteOnExit()
        f.writeBytes(content)
        return f
    }

    @Test
    fun sha256OfKnownVectorMatchesStandardDigest() {
        val f = writeTemp("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileHasher.sha256Hex(f),
        )
    }

    @Test
    fun sha256OfLargeFileMatchesIndependentDigest() {
        // 20_000 字节 > 8192 缓冲，强制走多次 read/update 循环。
        val bytes = Random(42).nextBytes(20_000)
        val f = writeTemp(bytes)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, FileHasher.sha256Hex(f))
    }

    @Test
    fun matchesIsNullBlankExpectedMeansUnchecked() {
        val f = writeTemp("abc".toByteArray())
        assertTrue(FileHasher.matchesSha256(f, null))
        assertTrue(FileHasher.matchesSha256(f, ""))
        assertTrue(FileHasher.matchesSha256(f, "   "))
    }

    @Test
    fun matchesIsCaseInsensitiveOnCorrectHash() {
        val f = writeTemp("abc".toByteArray())
        val lower = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(FileHasher.matchesSha256(f, lower))
        assertTrue(FileHasher.matchesSha256(f, lower.uppercase()))
    }

    @Test
    fun matchesIsFalseOnWrongHash() {
        val f = writeTemp("abc".toByteArray())
        assertFalse(FileHasher.matchesSha256(f, "deadbeef"))
    }

    @Test
    fun matchesIsFalseWhenFileMissing() {
        val f = File.createTempFile("missing", ".bin")
        f.delete()
        assertFalse(
            FileHasher.matchesSha256(
                f,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
        )
    }
}
