package com.nboard.ime

internal class DictionaryTrie {
    /**
     * While building, children live in a HashMap. [freeze] replaces it with two sorted arrays:
     * a 50k-word tree has ~120k nodes, and a HashMap per node cost about 30 MB per language.
     */
    private class Node {
        var children: HashMap<Char, Node>? = HashMap(4)
        var isTerminal = false
        var frequency = 0
        // Volatile, written nodes-first: a concurrent search sees both arrays or neither.
        @Volatile var childChars: CharArray? = null
        @Volatile var childNodes: Array<Node>? = null

        fun child(char: Char): Node? {
            children?.let { return it[char] }
            val chars = childChars ?: return null
            val index = chars.binarySearch(char)
            return if (index >= 0) childNodes!![index] else null
        }

        /** A frozen node gets its map back when a word is added later. */
        fun mutableChildren(): HashMap<Char, Node> = children ?: HashMap<Char, Node>(4).also { map ->
            val chars = childChars; val nodes = childNodes
            if (chars != null && nodes != null) for (i in chars.indices) map[chars[i]] = nodes[i]
            childChars = null; childNodes = null
            children = map
        }
    }

    private fun compact(node: Node) {
        if (node.childChars != null) return
        val entries = node.children.orEmpty().entries.sortedBy { it.key }
        node.childNodes = Array(entries.size) { entries[it].value }
        node.childChars = CharArray(entries.size) { entries[it].key }
    }

    private val root = Node()

    fun insert(word: String, frequency: Int) {
        if (word.isBlank() || frequency <= 0) {
            return
        }
        var node = root
        word.forEach { char ->
            node.childChars = null; node.childNodes = null
            node = node.mutableChildren().getOrPut(char) { Node() }
        }
        node.childChars = null; node.childNodes = null
        node.isTerminal = true
        if (frequency > node.frequency) {
            node.frequency = frequency
        }
    }

    /** Converts every node to compact arrays; call once the dictionary is built. */
    fun freeze() {
        val pending = ArrayDeque<Node>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            compact(node)
            node.children = null
            node.childNodes?.forEach { pending.add(it) }
        }
    }

    fun frequency(word: String): Int? {
        if (word.isBlank()) {
            return null
        }
        var node = root
        word.forEach { char ->
            node = node.child(char) ?: return null
        }
        return if (node.isTerminal) node.frequency else null
    }

    fun contains(word: String): Boolean {
        return frequency(word) != null
    }

    /**
     * Visits dictionary words within [maxCost] of [typed] under a weighted
     * Damerau-Levenshtein distance (adjacent transpositions), pruning subtrees that
     * cannot come back under budget. Costs come from [model]. Rows are reused per depth.
     */
    fun searchWithinCost(typed: String, maxCost: Double, model: TypoModel,
                         visit: (word: String, frequency: Int, cost: Double) -> Unit) {
        val n = typed.length
        val insertion = model.insertionCosts(typed)
        val rows = ArrayList<DoubleArray>()
        rows += DoubleArray(n + 1).also { for (j in 1..n) it[j] = it[j - 1] + insertion[j - 1] }
        val path = StringBuilder()
        fun walk(node: Node, depth: Int) {
            val previousRow = rows[depth]
            val beforePrevious = if (depth > 0) rows[depth - 1] else null
            if (rows.size <= depth + 1) rows += DoubleArray(n + 1)
            val row = rows[depth + 1]
            val previousChar = path.lastOrNull()
            compact(node)
            val chars = node.childChars ?: return
            val nodes = node.childNodes ?: return
            for (k in chars.indices) {
                val char = chars[k]
                val child = nodes[k]
                val deletion = model.deletion(char, previousChar)
                row[0] = previousRow[0] + deletion
                var best = row[0]
                for (j in 1..n) {
                    val t = typed[j - 1]
                    var cost = previousRow[j - 1] + model.substitution(t, char)
                    val removed = previousRow[j] + deletion
                    if (removed < cost) cost = removed
                    val added = row[j - 1] + insertion[j - 1]
                    if (added < cost) cost = added
                    if (beforePrevious != null && j >= 2 && t == previousChar && typed[j - 2] == char) {
                        val swapped = beforePrevious[j - 2] + TypoModel.TRANSPOSITION
                        if (swapped < cost) cost = swapped
                    }
                    row[j] = cost
                    if (cost < best) best = cost
                }
                if (best > maxCost) continue
                path.append(char)
                if (child.isTerminal && row[n] <= maxCost) visit(path.toString(), child.frequency, row[n])
                // The child's own search overwrites deeper rows only; this row is re-filled per sibling.
                walk(child, depth + 1)
                path.setLength(path.length - 1)
            }
        }
        walk(root, 0)
    }
}
