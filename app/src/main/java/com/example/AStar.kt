package com.example

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AStar {
    companion object {
        const val GRID_SIZE = 44
        const val ARENA_BOUNDS = 2200f
        const val WORLD_START = 100f
        const val WORLD_END = 2300f
        const val CELL_SIZE = 50f // 2200 / 44 = 50f
    }

    val isObstacle = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) { false } }

    init {
        val centerX = 1200f
        val centerY = 1200f
        val radius = 900f
        val numObstacles = 16
        val obstacleSize = 120f
        val obstacleHalf = obstacleSize / 2f

        for (i in 0 until numObstacles) {
            val theta = i * (2.0f * Math.PI.toFloat() / numObstacles)
            val obsX = centerX + radius * cos(theta)
            val obsY = centerY + radius * sin(theta)

            val leftX = obsX - obstacleHalf
            val rightX = obsX + obstacleHalf
            val topY = obsY - obstacleHalf
            val bottomY = obsY + obstacleHalf

            val startCellX = ((leftX - WORLD_START) / CELL_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
            val endCellX = ((rightX - WORLD_START) / CELL_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
            val startCellY = ((topY - WORLD_START) / CELL_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
            val endCellY = ((bottomY - WORLD_START) / CELL_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)

            for (cx in startCellX..endCellX) {
                for (cy in startCellY..endCellY) {
                    isObstacle[cx][cy] = true
                }
            }
        }
    }

    fun worldToGridX(worldX: Float): Int = ((worldX - WORLD_START) / CELL_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
    fun worldToGridY(worldY: Float): Int = ((worldY - WORLD_START) / CELL_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
    fun gridToWorldX(cx: Int): Float = WORLD_START + cx * CELL_SIZE + CELL_SIZE / 2f
    fun gridToWorldY(cy: Int): Float = WORLD_START + cy * CELL_SIZE + CELL_SIZE / 2f

    fun isWalkable(cx: Int, cy: Int): Boolean {
        if (cx !in 0 until GRID_SIZE || cy !in 0 until GRID_SIZE) return false
        return !isObstacle[cx][cy]
    }

    private fun findNearestWalkableCell(targetCx: Int, targetCy: Int): Pair<Int, Int> {
        if (isWalkable(targetCx, targetCy)) return Pair(targetCx, targetCy)
        var radius = 1
        while (radius < GRID_SIZE) {
            for (dx in -radius..radius) {
                for (dy in -radius..radius) {
                    if (abs(dx) != radius && abs(dy) != radius) continue
                    val nx = targetCx + dx
                    val ny = targetCy + dy
                    if (isWalkable(nx, ny)) return Pair(nx, ny)
                }
            }
            radius++
        }
        return Pair(targetCx, targetCy)
    }

    // Zero-allocation A* arrays
    private val gScore = FloatArray(GRID_SIZE * GRID_SIZE)
    private val fScore = FloatArray(GRID_SIZE * GRID_SIZE)
    private val parent = IntArray(GRID_SIZE * GRID_SIZE)
    private val inOpen = BooleanArray(GRID_SIZE * GRID_SIZE)
    private val inClosed = BooleanArray(GRID_SIZE * GRID_SIZE)
    private val openHeap = IntArray(GRID_SIZE * GRID_SIZE)
    private var heapSize = 0

    private fun pushHeap(nodeIdx: Int) {
        var i = heapSize
        openHeap[heapSize++] = nodeIdx
        while (i > 0) {
            val p = (i - 1) / 2
            if (fScore[openHeap[i]] < fScore[openHeap[p]]) {
                val temp = openHeap[i]; openHeap[i] = openHeap[p]; openHeap[p] = temp
                i = p
            } else break
        }
    }

    private fun popHeap(): Int {
        val res = openHeap[0]
        openHeap[0] = openHeap[--heapSize]
        var i = 0
        while (true) {
            val left = 2 * i + 1
            if (left >= heapSize) break
            val right = left + 1
            val minChild = if (right < heapSize && fScore[openHeap[right]] < fScore[openHeap[left]]) right else left
            if (fScore[openHeap[minChild]] < fScore[openHeap[i]]) {
                val temp = openHeap[i]; openHeap[i] = openHeap[minChild]; openHeap[minChild] = temp
                i = minChild
            } else break
        }
        return res
    }

    private val directionsX = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)
    private val directionsY = intArrayOf(1, 0, -1, 0, -1, 1, 1, -1)
    private val directionsCost = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f, 1.414f, 1.414f, 1.414f, 1.414f)

    fun findPath(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): List<Pair<Float, Float>> {
        var startCx = worldToGridX(startX)
        var startCy = worldToGridY(startY)
        var endCx = worldToGridX(endX)
        var endCy = worldToGridY(endY)

        if (!isWalkable(startCx, startCy)) {
            val nearest = findNearestWalkableCell(startCx, startCy)
            startCx = nearest.first; startCy = nearest.second
        }
        if (!isWalkable(endCx, endCy)) {
            val nearest = findNearestWalkableCell(endCx, endCy)
            endCx = nearest.first; endCy = nearest.second
        }

        if (startCx == endCx && startCy == endCy) {
            return listOf(Pair(gridToWorldX(endCx), gridToWorldY(endCy)))
        }

        // Fast clean array states
        gScore.fill(Float.MAX_VALUE)
        fScore.fill(Float.MAX_VALUE)
        inOpen.fill(false)
        inClosed.fill(false)
        heapSize = 0

        val startIdx = startCy * GRID_SIZE + startCx
        val endIdx = endCy * GRID_SIZE + endCx
        gScore[startIdx] = 0f
        fScore[startIdx] = heuristic(startCx, startCy, endCx, endCy)
        parent[startIdx] = -1
        inOpen[startIdx] = true
        pushHeap(startIdx)

        var foundEnd = false

        while (heapSize > 0) {
            val currentIdx = popHeap()
            inOpen[currentIdx] = false
            inClosed[currentIdx] = true

            if (currentIdx == endIdx) {
                foundEnd = true
                break
            }

            val cx = currentIdx % GRID_SIZE
            val cy = currentIdx / GRID_SIZE

            for (i in 0 until 8) {
                val nx = cx + directionsX[i]
                val ny = cy + directionsY[i]
                if (nx !in 0 until GRID_SIZE || ny !in 0 until GRID_SIZE) continue
                if (isObstacle[nx][ny]) continue

                val neighborIdx = ny * GRID_SIZE + nx
                if (inClosed[neighborIdx]) continue

                val cost = directionsCost[i]
                val tentativeG = gScore[currentIdx] + cost

                if (!inOpen[neighborIdx] || tentativeG < gScore[neighborIdx]) {
                    gScore[neighborIdx] = tentativeG
                    fScore[neighborIdx] = tentativeG + heuristic(nx, ny, endCx, endCy)
                    parent[neighborIdx] = currentIdx
                    if (!inOpen[neighborIdx]) {
                        inOpen[neighborIdx] = true
                        pushHeap(neighborIdx)
                    }
                }
            }
        }

        if (!foundEnd) {
            return listOf(Pair(gridToWorldX(endCx), gridToWorldY(endCy)))
        }

        val resultPath = mutableListOf<Pair<Float, Float>>()
        var curr = endIdx
        while (curr != -1) {
            val cx = curr % GRID_SIZE
            val cy = curr / GRID_SIZE
            resultPath.add(Pair(gridToWorldX(cx), gridToWorldY(cy)))
            curr = parent[curr]
        }
        resultPath.reverse()
        return resultPath
    }

    private fun heuristic(x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt((dx * dx + dy * dy).toFloat())
    }
}
