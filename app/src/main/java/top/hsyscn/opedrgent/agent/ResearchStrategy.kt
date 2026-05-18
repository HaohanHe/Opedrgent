package top.hsyscn.opedrgent.agent

enum class StrategyMode { LEGACY, GRAPH }

sealed class StrategyNode {
    object Start : StrategyNode()
    object Finish : StrategyNode()
    data class Step(
        val name: String,
        val action: suspend (Any?) -> Any?,
    ) : StrategyNode()
}

data class Edge(
    val from: StrategyNode,
    val to: StrategyNode,
    val condition: (suspend (Any?) -> Boolean)? = null,
    val transformer: (suspend (Any?) -> Any?)? = null,
)

class ResearchStrategyBuilder {
    private val nodes = mutableListOf<StrategyNode>()
    private val edges = mutableListOf<Edge>()

    fun start(): StrategyNode.Start {
        val node = StrategyNode.Start
        nodes.add(node)
        return node
    }

    fun finish(): StrategyNode.Finish {
        val node = StrategyNode.Finish
        nodes.add(node)
        return node
    }

    fun step(name: String, action: suspend (Any?) -> Any?): StrategyNode.Step {
        val node = StrategyNode.Step(name, action)
        nodes.add(node)
        return node
    }

    infix fun StrategyNode.then(next: StrategyNode) {
        edges.add(Edge(this, next))
    }

    infix fun StrategyNode.forwardTo(next: StrategyNode): EdgeBuilder {
        return EdgeBuilder(this, next)
    }

    fun edge(
        from: StrategyNode,
        to: StrategyNode,
        condition: (suspend (Any?) -> Boolean)? = null,
        transformer: (suspend (Any?) -> Any?)? = null,
    ) {
        edges.add(Edge(from, to, condition, transformer))
    }

    fun build(): ResearchStrategy = ResearchStrategy(nodes.toList(), edges.toList())

    inner class EdgeBuilder(
        private val from: StrategyNode,
        private val to: StrategyNode,
    ) {
        private var condition: (suspend (Any?) -> Boolean)? = null
        private var transformer: (suspend (Any?) -> Any?)? = null

        infix fun onCondition(condition: (suspend (Any?) -> Boolean)?): EdgeBuilder {
            this.condition = condition
            return this
        }

        infix fun transformed(transformer: (suspend (Any?) -> Any?)?): EdgeBuilder {
            this.transformer = transformer
            return this
        }

        fun build(): Edge {
            val edge = Edge(from, to, condition, transformer)
            edges.add(edge)
            return edge
        }
    }
}

class ResearchStrategy(
    private val nodes: List<StrategyNode>,
    private val edges: List<Edge>,
) {
    suspend fun execute(initialInput: Any?): Any? {
        var currentNode: StrategyNode = StrategyNode.Start
        var currentInput: Any? = initialInput

        while (currentNode !is StrategyNode.Finish) {
            when (currentNode) {
                is StrategyNode.Step -> {
                    currentInput = currentNode.action(currentInput)
                }
                is StrategyNode.Start, is StrategyNode.Finish -> {}
            }

            val nextEdge = edges.firstOrNull { it.from == currentNode }
            if (nextEdge == null) {
                if (currentNode !is StrategyNode.Finish) {
                    currentNode = StrategyNode.Finish
                }
                break
            }

            if (nextEdge.condition != null && !nextEdge.condition!!.invoke(currentInput)) {
                currentNode = StrategyNode.Finish
                break
            }

            if (nextEdge.transformer != null) {
                currentInput = nextEdge.transformer!!.invoke(currentInput)
            }

            currentNode = nextEdge.to
        }

        return currentInput
    }
}

fun singleRunStrategy(): ResearchStrategy = ResearchStrategyBuilder().apply {
    val doTask = step("execute_task") { input -> input }
    start() then doTask
    doTask then finish()
}.build()

fun deepResearchStrategy(): ResearchStrategy = ResearchStrategyBuilder().apply {
    val gather = step("gather_information") { input -> input }
    val analyze = step("analyze_findings") { input -> input }
    val synthesize = step("synthesize_report") { input -> input }
    start() then gather
    gather then analyze
    analyze then synthesize
    synthesize then finish()
}.build()
