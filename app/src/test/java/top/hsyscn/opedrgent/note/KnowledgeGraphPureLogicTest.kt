package top.hsyscn.opedrgent.note

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class KnowledgeGraphPureLogicTest {

    /**
     * 与 [KnowledgeGraph] 内部实现保持一致的余弦相似度计算，
     * 用于验证其纯算法逻辑。
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 与 [KnowledgeGraph] 中关系权重选择逻辑保持一致。
     */
    private fun chooseRelationWeight(
        similarity: Float,
        sharedEntities: Set<String>,
        sharedKeywords: Set<String>,
    ): Float {
        return when {
            sharedEntities.isNotEmpty() -> kotlin.math.max(similarity, 0.9f)
            sharedKeywords.size >= 2 -> kotlin.math.max(similarity, 0.8f)
            else -> similarity
        }
    }

    @Test
    fun `cosine similarity of identical vectors is 1 dot 0`() {
        val vector = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0f, cosineSimilarity(vector, vector), 1e-6f)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0 dot 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0.0f, cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `cosine similarity returns 0 for mismatched dimensions`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertEquals(0.0f, cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `shared entity weight takes max of similarity and 0 dot 9`() {
        assertEquals(0.9f, chooseRelationWeight(0.5f, setOf("北京"), emptySet()), 1e-6f)
        assertEquals(0.95f, chooseRelationWeight(0.95f, setOf("北京"), emptySet()), 1e-6f)
    }

    @Test
    fun `shared keywords weight takes max of similarity and 0 dot 8`() {
        assertEquals(0.8f, chooseRelationWeight(0.5f, emptySet(), setOf("机器", "学习")), 1e-6f)
        assertEquals(0.85f, chooseRelationWeight(0.85f, emptySet(), setOf("机器", "学习")), 1e-6f)
    }

    @Test
    fun `semantic similarity is used directly as weight`() {
        assertEquals(0.6f, chooseRelationWeight(0.6f, emptySet(), emptySet()), 1e-6f)
        assertEquals(0.0f, chooseRelationWeight(0.0f, emptySet(), setOf("一个")), 1e-6f)
    }
}
