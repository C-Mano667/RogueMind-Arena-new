package com.example

import java.util.PriorityQueue
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

    // Grid representing blocked cells
    val isObstacle = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) { false } }

    init {
        // Initialize the 16 circular obstacles in a ring at radius 900
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

            // Mark cells that intersect with this 120x120 bounding box
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

    data class Node(
        val cx: Int,
        val cy: Int,
        var g: Float = Float.MAX_VALUE,
        var h: Float = 0f,
        var parent: Node? = null
    ) {
        val f: Float get() = g + h

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Node) return false
            return cx == other.cx && cy == other.cy
        }

        override fun hashCode(): Int = cx * 31 + cy
    }

    fun worldToGridX(worldX: Float): Int {
        return ((worldX - WORLD_START) / CELL_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
    }

    fun worldToGridY(worldY: Float): Int {
        return ((worldY - WORLD_START) / CELL_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
    }

    fun gridToWorldX(cx: Int): Float {
        return WORLD_START + cx * CELL_SIZE + CELL_SIZE / 2f
    }

    fun gridToWorldY(cy: Int): Float {
        return WORLD_START + cy * CELL_SIZE + CELL_SIZE / 2f
    }

    // Is a cell walkable?
    fun isWalkable(cx: Int, cy: Int): Boolean {
        if (cx !in 0 until GRID_SIZE || cy !in 0 until GRID_SIZE) return false
        return !isObstacle[cx][cy]
    }

    // Find nearest walkable cell close to grid coords
    private fun findNearestWalkableCell(targetCx: Int, targetCy: Int): Pair<Int, Int> {
        if (isWalkable(targetCx, targetCy)) return Pair(targetCx, targetCy)

        var radius = 1
        while (radius < GRID_SIZE) {
            for (dx in -radius..radius) {
                for (dy in -radius..radius) {
                    if (abs(dx) != radius && abs(dy) != radius) continue
                    val nx = targetCx + dx
                    val ny = targetCy + dy
                    if (isWalkable(nx, ny)) {
                        return Pair(nx, ny)
                    }
                }
            }
            radius++
        }
        return Pair(targetCx, targetCy) // Fallback
    }

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

        // Make sure positions are walkable or clamp to nearest walkable cell
        if (!isWalkable(startCx, startCy)) {
            val nearest = findNearestWalkableCell(startCx, startCy)
            startCx = nearest.first
            startCy = nearest.second
        }
        if (!isWalkable(endCx, endCy)) {
            val nearest = findNearestWalkableCell(endCx, endCy)
            endCx = nearest.first
            endCy = nearest.second
        }

        if (startCx == endCx && startCy == endCy) {
            return listOf(Pair(gridToWorldX(endCx), gridToWorldY(endCy)))
        }

        val openSet = PriorityQueue<Node>(compareBy { it.f })
        val openMap = HashMap<Pair<Int, Int>, Node>()
        val closedSet = HashSet<Pair<Int, Int>>()

        val startNode = Node(startCx, startCy, g = 0f, h = heuristic(startCx, startCy, endCx, endCy))
        openSet.add(startNode)
        openMap[Pair(startCx, startCy)] = startNode

        val directions = listOf(
            Pair(0, 1) to 1.0f,
            Pair(1, 0) to 1.0f,
            Pair(0, -1) to 1.0f,
            Pair(-1, 0) to 1.0f,
            Pair(1, 1) to 1.414f,
            Pair(1, -1) to 1.414f,
            Pair(-1, 1) to 1.414f,
            Pair(-1, -1) to 1.414f
        )

        while (openSet.isNotEmpty()) {
            val current = openSet.poll() ?: break
            val currCoords = Pair(current.cx, current.cy)
            openMap.remove(currCoords)
            closedSet.add(currCoords)

            if (current.cx == endCx && current.cy == endCy) {
                // Reconstruct path
                val path = mutableListOf<Pair<Float, Float>>()
                var curr: Node? = current
                while (curr != null) {
                    path.add(Pair(gridToWorldX(curr.cx), gridToWorldY(curr.cy)))
                    curr = curr.parent
                }
                path.reverse()
                return path
            }

            for ((dir, cost) in directions) {
                val nx = current.cx + dir.first
                val ny = current.cy + dir.second
                val neighborCoords = Pair(nx, ny)

                if (!isWalkable(nx, ny) || closedSet.contains(neighborCoords)) continue

                val tentativeG = current.g + cost
                val existing = openMap[neighborCoords]

                if (existing == null) {
                    val neighborNode = Node(
                        cx = nx,
                        cy = ny,
                        g = tentativeG,
                        h = heuristic(nx, ny, endCx, endCy),
                        parent = current
                    )
                    openSet.add(neighborNode)
                    openMap[neighborCoords] = neighborNode
                } else if (tentativeG < existing.g) {
                    openSet.remove(existing)
                    existing.g = tentativeG
                    existing.parent = current
                    openSet.add(existing)
                }
            }
        }

        // Return direct link if path not found
        return listOf(Pair(gridToWorldX(endCx), gridToWorldY(endCy)))
    }

    private fun heuristic(x1: Int, y1: Int, x2: Int, y2: Int): Float {
        // Euclidean distance heuristic for natural movement paths
        val dx = (x1 - x2).toFloat()
        val dy = (y1 - y2).toFloat()
        return sqrt(dx * dx + dy * dy)
    }
}
