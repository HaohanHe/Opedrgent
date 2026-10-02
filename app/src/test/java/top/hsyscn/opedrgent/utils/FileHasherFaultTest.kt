package top.hsyscn.opedrgent.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FileHasher] 故障注入与边界测试（纯 JVM）。
 *
 * 覆盖：空文件哈希、对目录调用 sha256Hex、对缺失文件 matches、
 * 畸形 expected（非十六进制 / 过短 / 大小写 / 含空白）。
 */
class FileHasherFaultTest {

    private fun writeTemp(content: ByteArray): File {
        val f = File.createTempFile("fhft", ".bin")
        f.deleteOnExit()
        f.writeBytes(content)
        return f
    }

    @Test
    fun `空文件 sha256 等于标准空哈希`() {
        val f = writeTemp(ByteArray(0))
        // SHA-256("") = e3b0c442...
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FileHasher.sha256Hex(f),
        )
    }

    @Test(expected = Exception::class)
    fun `对目录计算 sha256 抛异常`() {
        val dir = File.createTempFile("fhdir", ".d")
        dir.delete()
        dir.mkdir()
        dir.deleteOnExit()
        FileHasher.sha256Hex(dir)
    }

    @Test
    fun `matches 对目录返回 false 不崩`() {
        val dir = File.createTempFile("fhdir2", ".d")
        dir.delete()
        dir.mkdir()
        dir.deleteOnExit()
        // 目录 inputStream 抛异常 → matches 捕获返回 false
        assertFalse(FileHasher.matchesSha256(dir, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    }

    @Test
    fun `matches 畸形 expected 不崩且判否`() {
        val f = writeTemp("abc".toByteArray())
        assertFalse(FileHasher.matchesSha256(f, "not-hex!!"))
        assertFalse(FileHasher.matchesSha256(f, "xyz")) // 过短
        assertFalse(FileHasher.matchesSha256(f, "中文哈希"))
    }

    @Test
    fun `matches expected 带首尾空白视为未提供放行`() {
        val f = writeTemp("abc".toByteArray())
        // trim 后非空才校验；这里是合法哈希带空白仍会走校验
        assertTrue(FileHasher.matchesSha256(f, "  ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  "))
    }

    @Test
    fun `sha256Hex 逐字节一致性`() {
        // 单字节文件
        val f = writeTemp(byteArrayOf(0x00))
        val h = FileHasher.sha256Hex(f)
        assertEquals(64, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
