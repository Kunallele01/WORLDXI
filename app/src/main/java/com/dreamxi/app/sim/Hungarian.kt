package com.dreamxi.app.sim

// Moved here from feature/freemode/MagicXi.kt unchanged, because the World Cup
// engine picks every opponent's best eleven with it and the sim package may
// not depend on UI code.

/**
 * Kuhn-Munkres assignment, for a rectangular matrix with at least as many
 * columns as rows.
 *
 * Written out rather than approximated because the whole point of choosing the
 * eleven together is that the answer is actually optimal; a heuristic here
 * would quietly reintroduce the greedy behaviour this exists to avoid. It runs
 * on an 11-by-a-few-hundred matrix, so cost is irrelevant.
 *
 * Cells holding NEGATIVE_INFINITY are forbidden pairings and are never chosen
 * unless a row has no legal column at all, which returns -1 for that row.
 */
internal object Hungarian {

    /** For each row, the column assigned to it, or -1 when none is possible. */
    fun maximise(value: Array<DoubleArray>): IntArray {
        if (value.isEmpty()) return IntArray(0)
        if (value[0].isEmpty()) return IntArray(value.size) { -1 }

        // A row with no legal column is dropped before solving rather than
        // handed one and filtered out afterwards. Left in, it takes a column
        // with it — and that column may have been the only legal option for a
        // row that could actually have used it.
        val live = value.indices.filter { r -> value[r].any { it != Double.NEGATIVE_INFINITY } }
        val out = IntArray(value.size) { -1 }
        if (live.isEmpty()) return out
        if (live.size < value.size) {
            val sub = solve(Array(live.size) { value[live[it]] })
            for ((i, r) in live.withIndex()) out[r] = sub[i]
            return out
        }
        return solve(value)
    }

    private fun solve(value: Array<DoubleArray>): IntArray {
        val rows = value.size
        val cols = value[0].size

        // Solved as a minimisation over costs, with forbidden pairings priced
        // far above any real one rather than at infinity, which would poison
        // the potentials.
        var span = 0.0
        for (r in 0 until rows) for (c in 0 until cols) {
            val v = value[r][c]
            if (v != Double.NEGATIVE_INFINITY) span = maxOf(span, kotlin.math.abs(v))
        }
        val forbidden = (span + 1.0) * (rows + 1)
        // Padded out to a square when candidates are scarcer than shirts, so
        // the algorithm's "every row gets a column" invariant holds; rows that
        // land on padding are reported unassigned.
        val width = maxOf(cols, rows)
        val cost = Array(rows) { r ->
            DoubleArray(width) { c ->
                val v = if (c < cols) value[r][c] else Double.NEGATIVE_INFINITY
                if (v == Double.NEGATIVE_INFINITY) forbidden else -v
            }
        }

        val u = DoubleArray(rows + 1)
        val v = DoubleArray(width + 1)
        val p = IntArray(width + 1) { -1 }   // column -> row
        val way = IntArray(width + 1) { -1 }

        for (i in 0 until rows) {
            p[width] = i
            var j0 = width
            val minv = DoubleArray(width + 1) { Double.MAX_VALUE }
            val used = BooleanArray(width + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.MAX_VALUE
                var j1 = -1
                for (j in 0 until width) {
                    if (used[j]) continue
                    val cur = cost[i0][j] - u[i0] - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                if (j1 < 0) break
                for (j in 0..width) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != -1)
            while (j0 != width) {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            }
        }

        val result = IntArray(rows) { -1 }
        for (j in 0 until cols) {
            val r = p[j]
            if (r in 0 until rows && value[r][j] != Double.NEGATIVE_INFINITY) result[r] = j
        }
        return result
    }
}
