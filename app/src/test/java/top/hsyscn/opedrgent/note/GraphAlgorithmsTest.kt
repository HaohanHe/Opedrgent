package top.hsyscn.opedrgent.note

import org.junit.Assert.*
import org.junit.Test
import top.hsyscn.opedrgent.note.KnowledgeGraph.GraphEdge

class GraphAlgorithmsTest {

    @Test
    fun `detectCommunities returns empty map for empty edges`() {
        val result = GraphAlgorithms.detectCommunities(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectCommunities separates two disconnected cliques`() {
        // 两个不相连的三角形（团），标签传播应收敛为两个社区
        val edges = listOf(
            GraphEdge("A", "B"),
            GraphEdge("B", "C"),
            GraphEdge("C", "A"),
            GraphEdge("D", "E"),
            GraphEdge("E", "F"),
            GraphEdge("F", "D"),
        )
        val communities = GraphAlgorithms.detectCommunities(edges)
        assertEquals(6, communities.size)

        val communityA = communities["A"]
        val communityD = communities["D"]
        assertNotNull(communityA)
        assertNotNull(communityD)
        assertNotEquals("Two cliques should belong to different communities", communityA, communityD)

        assertEquals(communityA, communities["B"])
        assertEquals(communityA, communities["C"])
        assertEquals(communityD, communities["E"])
        assertEquals(communityD, communities["F"])
    }

    @Test
    fun `degreeCentrality returns 0 for isolated node`() {
        val nodes = listOf("A")
        val edges = emptyList<GraphEdge>()
        val centrality = GraphAlgorithms.degreeCentrality(nodes, edges)
        assertEquals(0f, centrality["A"] ?: -1f, 1e-6f)
    }

    @Test
    fun `degreeCentrality star graph center returns 1 dot 0`() {
        val nodes = listOf("center", "leaf1", "leaf2", "leaf3")
        val edges = listOf(
            GraphEdge("center", "leaf1"),
            GraphEdge("center", "leaf2"),
            GraphEdge("center", "leaf3"),
        )
        val centrality = GraphAlgorithms.degreeCentrality(nodes, edges)
        assertEquals(1.0f, centrality["center"] ?: -1f, 1e-6f)
        assertEquals(1.0f / 3.0f, centrality["leaf1"] ?: -1f, 1e-6f)
    }

    @Test
    fun `pageRank returns empty map for empty nodes`() {
        val result = GraphAlgorithms.pageRank(emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `pageRank mutual two node connection has positive equal scores`() {
        val nodes = listOf("A", "B")
        val edges = listOf(GraphEdge("A", "B"))
        val ranks = GraphAlgorithms.pageRank(nodes, edges)
        assertEquals(2, ranks.size)
        val rankA = ranks["A"] ?: 0f
        val rankB = ranks["B"] ?: 0f
        assertTrue("Expected positive rank for A", rankA > 0f)
        assertTrue("Expected positive rank for B", rankB > 0f)
        assertEquals(rankA, rankB, 1e-5f)
    }
}
