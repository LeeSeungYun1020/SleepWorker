package aiflow.ui.editor

import aiflow.model.*
import aiflow.model.Target

/** Layout the strongly connected component DAG; coordinates never determine execution. */
object GraphLayout {
    val special = listOf("start", "end", "ask")
    fun reachable(w: Workflow): Set<String> {
        val found = mutableSetOf<String>()
        fun visit(id: String) { if (found.add(id)) w.steps.firstOrNull { it.id == id }?.transitions?.forEach { (it.next as? Target.StepId)?.let { visit(it.id) } } }
        if (w.steps.any { it.id == w.start }) visit(w.start)
        return found
    }
    fun positions(w: Workflow): Map<String, NodePosition> {
        val byId = w.steps.associateBy { it.id }
        var next = 0
        val index = mutableMapOf<String, Int>(); val low = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>(); val onStack = mutableSetOf<String>(); val groups = mutableListOf<List<String>>()
        fun visit(id: String) {
            index[id] = next; low[id] = next++ ; stack += id; onStack += id
            byId.getValue(id).transitions.mapNotNull { (it.next as? Target.StepId)?.id }.filter { it in byId }.forEach { target ->
                if (target !in index) { visit(target); low[id] = minOf(low.getValue(id), low.getValue(target)) }
                else if (target in onStack) low[id] = minOf(low.getValue(id), index.getValue(target))
            }
            if (low[id] == index[id]) {
                val group = mutableListOf<String>()
                do { val member = stack.removeAt(stack.lastIndex); onStack -= member; group += member } while (member != id)
                groups += group.sorted()
            }
        }
        byId.keys.sorted().forEach { if (it !in index) visit(it) }
        val groupOf = groups.flatMapIndexed { i, ids -> ids.map { it to i } }.toMap()
        val edges = groups.indices.associateWith { i -> groups[i].flatMap { id -> byId.getValue(id).transitions.mapNotNull { (it.next as? Target.StepId)?.id?.let(groupOf::get) } }.filter { it != i }.toSet() }
        val levels = IntArray(groups.size)
        val incoming = IntArray(groups.size)
        edges.values.forEach { targets -> targets.forEach { incoming[it]++ } }
        val queue = ArrayDeque<Int>(); groups.indices.filter { incoming[it] == 0 }.forEach(queue::add)
        while (queue.isNotEmpty()) { val i = queue.removeFirst(); edges.getValue(i).forEach { target -> levels[target] = maxOf(levels[target], levels[i] + 1); if (--incoming[target] == 0) queue.add(target) } }
        val reachable = reachable(w)
        val rows = mutableMapOf<Pair<Boolean, Int>, Int>()
        val result = linkedMapOf("start" to NodePosition(30f, 50f))
        groups.indices.sortedWith(compareBy({ levels[it] }, { groups[it].first() })).forEach { i ->
            val orphan = groups[i].none { it in reachable }
            val key = orphan to levels[i]
            groups[i].forEach { id -> val row = rows[key] ?: 0; result[id] = NodePosition(300f + levels[i] * 300, (if (orphan) 700f else 50f) + row * 170); rows[key] = row + 1 }
        }
        val last = (levels.maxOrNull() ?: 0) + 2
        result["end"] = NodePosition(last * 300f, 50f); result["ask"] = NodePosition(last * 300f, 250f)
        return result
    }
    fun fillMissing(w: Workflow): Workflow {
        val valid = (special + w.steps.map { it.id }).toSet()
        val saved = w.editor?.nodes.orEmpty().filter { it.key in valid && it.value.x.isFinite() && it.value.y.isFinite() }
        return w.copy(editor = EditorLayout(positions(w) + saved))
    }
}
