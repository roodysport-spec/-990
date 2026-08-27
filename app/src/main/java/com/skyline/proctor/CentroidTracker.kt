package com.skyline.proctor

import kotlin.math.sqrt

/**
 * ترجمة حرفية لـ centroidtracker.py
 * يربط كل وجه برقم طالب ثابت (objectID) حتى لو تحرك بالكادر،
 * بالاعتماد على أقرب مسافة إقليدية بين مركز الوجه بالإطار الحالي
 * ومراكز الوجوه المعروفة بالإطار السابق.
 */
class CentroidTracker(
    private val maxDisappeared: Int = 8,
    private val historyMaxLen: Int = 15
) {
    private var nextObjectID = 1
    val objects = LinkedHashMap<Int, Pair<Int, Int>>()          // objectID -> centroid (cx, cy)
    private val disappeared = LinkedHashMap<Int, Int>()          // objectID -> عدد الإطارات المفقود فيها
    val histories = LinkedHashMap<Int, ArrayDeque<String>>()     // objectID -> آخر اتجاهات النظر

    data class Rect(val startX: Int, val startY: Int, val endX: Int, val endY: Int)

    private fun register(centroid: Pair<Int, Int>): Int {
        val id = nextObjectID
        objects[id] = centroid
        disappeared[id] = 0
        histories[id] = ArrayDeque()
        nextObjectID++
        return id
    }

    private fun deregister(objectID: Int) {
        objects.remove(objectID)
        disappeared.remove(objectID)
        histories.remove(objectID)
    }

    /**
     * @return خريطة (فهرس الوجه بالقائمة المُمررة -> رقم الطالب الثابت)
     */
    fun update(rects: List<Rect>): Map<Int, Int> {
        val rectToId = mutableMapOf<Int, Int>()

        if (rects.isEmpty()) {
            for (objectID in disappeared.keys.toList()) {
                disappeared[objectID] = (disappeared[objectID] ?: 0) + 1
                if ((disappeared[objectID] ?: 0) > maxDisappeared) {
                    deregister(objectID)
                }
            }
            return rectToId
        }

        val inputCentroids = rects.map { r ->
            Pair(((r.startX + r.endX) / 2.0).toInt(), ((r.startY + r.endY) / 2.0).toInt())
        }

        if (objects.isEmpty()) {
            for (i in inputCentroids.indices) {
                val id = register(inputCentroids[i])
                rectToId[i] = id
            }
            return rectToId
        }

        val objectIDs = objects.keys.toList()
        val objectCentroids = objectIDs.map { objects[it]!! }

        // مصفوفة المسافات الإقليدية بين كل وجه معروف وكل وجه جديد بالإطار
        val distances = Array(objectCentroids.size) { r ->
            DoubleArray(inputCentroids.size) { c ->
                val dx = (objectCentroids[r].first - inputCentroids[c].first).toDouble()
                val dy = (objectCentroids[r].second - inputCentroids[c].second).toDouble()
                sqrt(dx * dx + dy * dy)
            }
        }

        // نفس منطق argsort/argmin بالبايثون: نرتب الصفوف حسب أقل مسافة فيها
        val rowMins = distances.indices.map { r -> distances[r].minOrNull() ?: Double.MAX_VALUE }
        val rowsSorted = distances.indices.sortedBy { rowMins[it] }

        val usedRows = mutableSetOf<Int>()
        val usedCols = mutableSetOf<Int>()

        for (row in rowsSorted) {
            if (row in usedRows) continue
            val col = distances[row].indices.minByOrNull { distances[row][it] } ?: continue
            if (col in usedCols) continue
            if (distances[row][col] > 150.0) continue // نفس عتبة 150 بكسل بالبايثون

            val objectID = objectIDs[row]
            objects[objectID] = inputCentroids[col]
            disappeared[objectID] = 0
            rectToId[col] = objectID

            usedRows.add(row)
            usedCols.add(col)
        }

        val unusedRows = objectIDs.indices.toSet() - usedRows
        val unusedCols = inputCentroids.indices.toSet() - usedCols

        for (row in unusedRows) {
            val objectID = objectIDs[row]
            disappeared[objectID] = (disappeared[objectID] ?: 0) + 1
            if ((disappeared[objectID] ?: 0) > maxDisappeared) {
                deregister(objectID)
            }
        }

        for (col in unusedCols) {
            val id = register(inputCentroids[col])
            rectToId[col] = id
        }

        return rectToId
    }

    fun reset() {
        objects.clear()
        disappeared.clear()
        histories.clear()
        nextObjectID = 1
    }

    /** يضيف اتجاه جديد لتاريخ طالب معين، مع الحفاظ على الحد الأقصى (زي deque(maxlen=...)) */
    fun pushDirection(objectID: Int, direction: String) {
        val h = histories[objectID] ?: return
        h.addLast(direction)
        while (h.size > historyMaxLen) h.removeFirst()
    }
}
