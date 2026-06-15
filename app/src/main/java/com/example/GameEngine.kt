package com.example

import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*
import kotlin.random.Random

// --- ANIMATION STATES ---
enum class PlayerAnimState { IDLE, RUN, DASH, SHOOT, HURT, DEATH }
enum class EnemyAnimState { IDLE, RUN, ATTACK, HURT, DEATH }
enum class EnemyType { SOLDIER, ELITE, BOSS }
enum class GameState { START_SCREEN, IN_CINEMATIC, PLAYING, GAME_OVER, GAME_WON }

class GameEngine {

    companion object {
        const val WORLD_SIZE = 2400f
        const val ARENA_CENTER_X = 1200f
        const val ARENA_CENTER_Y = 1200f
        const val ARENA_SIZE = 2200f
        const val MIN_X = 100f
        const val MAX_X = 2300f
        const val MIN_Y = 100f
        const val MAX_Y = 2300f

        const val BULLET_POOL_SIZE = 150
        const val BULLET_SPEED = 900f
        const val BULLET_LIFETIME = 1.5f

        const val DASH_COOLDOWN = 3.0f
        const val DASH_DISTANCE = 220f
        const val DASH_SPEED = 1200f // pixels/sec during high-speed dash

        const val OBSTACLE_RADIUS = 900f
        const val OBSTACLE_SIZE = 120f
        const val NUM_OBSTACLES = 16
    }

    // --- GAME CONTROL ---
    var state = GameState.START_SCREEN
    var waveNumber = 1
    var waveProgressDelay = 0f
    var score = 0
    var enemiesKilled = 0
    var gameTime = 0f

    // Cinematic track variables
    var cinematicTimer = 0f
    var cinematicBossName = "CYBER-OVERDOM MECH"
    var cinematicBossTitle = "DESTROYER OF CORE"

    // --- ENTITY STRUCTURES ---

    // Bullet Pool (Size 150)
    class Bullet {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var angle = 0f
        var isActive = false
        var isPlayerOwned = true
        var life = 0f
        var damage = 10f
        var color = Color.Cyan
    }
    private val bulletPool = Array(BULLET_POOL_SIZE) { Bullet() }
    private var bulletPoolIndex = 0

    // Player Data
    class Player {
        var x = ARENA_CENTER_X
        var y = ARENA_CENTER_Y
        var radius = 28f
        var hp = 100f
        var maxHp = 100f
        var speed = 320f
        var angle = 0f
        
        var dashCooldown = 0f
        var dashActiveTime = 0f
        var dashVx = 0f
        var dashVy = 0f
        var isDashing = false
        var afterimageTimer = 0f

        var shootCooldown = 0f
        var hurtTimer = 0f
        var state = PlayerAnimState.IDLE
        var stateTimer = 0f
        
        fun takeDamage(damage: Float): Boolean {
            if (hp <= 0f) return false
            hp = (hp - damage).coerceAtLeast(0f)
            hurtTimer = 0.25f
            if (hp <= 0f) {
                state = PlayerAnimState.DEATH
                stateTimer = 0f
            } else if (state != PlayerAnimState.DASH) {
                state = PlayerAnimState.HURT
                stateTimer = 0f
            }
            return true
        }
    }
    val player = Player()

    // Enemy Data
    class Enemy(val id: Int, val type: EnemyType) {
        var x = 0f
        var y = 0f
        var radius = if (type == EnemyType.SOLDIER) 24f else if (type == EnemyType.ELITE) 30f else 65f
        var hp = if (type == EnemyType.SOLDIER) 30f else if (type == EnemyType.ELITE) 90f else 1200f
        var maxHp = hp
        var speed = if (type == EnemyType.SOLDIER) 160f else if (type == EnemyType.ELITE) 210f else 115f
        var angle = 0f
        
        var hurtTimer = 0f
        var shootCooldown = 0f
        var state = EnemyAnimState.IDLE
        var stateTimer = 0f
        var deathTimer = 0f

        // Path / Steering properties
        var path: List<Pair<Float, Float>> = emptyList()
        var currentPathWaypointIndex = 0
        var pathRecalcTimer = 0f

        // Boss-only Special attacks
        var bossAttackTimer = 0f
        var bossAttackPattern = 0 // 0: Normal concentric, 1: Radial spiral, 2: Soldier spawn charge
        var bossChargeTimer = 0f
        var bossChargeVx = 0f
        var bossChargeVy = 0f

        fun takeDamage(damage: Float): Boolean {
            if (hp <= 0f) return false
            hp = (hp - damage).coerceAtLeast(0f)
            hurtTimer = 0.22f
            if (hp <= 0f) {
                state = EnemyAnimState.DEATH
                stateTimer = 0f
                deathTimer = 0.6f // Keep in death state to do disintegrate particles
            } else {
                state = EnemyAnimState.HURT
                stateTimer = 0f
            }
            return true
        }
    }
    val enemies = mutableListOf<Enemy>()
    private val enemyIdCounter = AtomicInteger(1)

    // A* Instances
    val aStar = AStar()

    // Circular Obstacles definitions
    class Obstacle(val x: Float, val y: Float, val size: Float) {
        val half = size / 2f
        val left = x - half
        val right = x + half
        val top = y - half
        val bottom = y + half
    }
    val obstacles = mutableListOf<Obstacle>()

    // Visuals Effects Pools / Lists
    class FloatingText {
        var x = 0f
        var y = 0f
        var text = ""
        var color = Color.White
        var alpha = 1f
        var speedY = -120f
        var speedX = 0f
        var life = 0f
        var isCritical = false
        var isActive = false
    }
    private val floatingTexts = Array(40) { FloatingText() }

    class DashAfterimage {
        var x = 0f
        var y = 0f
        var angle = 0f
        var alpha = 1f
        var isActive = false
    }
    private val afterimages = Array(20) { DashAfterimage() }

    class MuzzleFlash {
        var x = 0f
        var y = 0f
        var angle = 0f
        var life = 0f
        var maxLife = 0.15f
        var isActive = false
    }
    private val muzzleFlashes = Array(20) { MuzzleFlash() }

    class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var color = Color.White
        var size = 4f
        var alpha = 1f
        var life = 0f
        var maxLife = 0f
        var isActive = false
    }
    private val particlePool = Array(200) { Particle() }

    // Screen Shake Indicator
    var screenShakeAmount = 0f

    // Vibration trigger interface
    var triggerHaptic: (() -> Unit)? = null

    init {
        // Initialize static circular obstacles at radius 900
        val centerX = ARENA_CENTER_X
        val centerY = ARENA_CENTER_Y
        for (i in 0 until NUM_OBSTACLES) {
            val theta = i * (2.0f * Math.PI.toFloat() / NUM_OBSTACLES)
            val obsX = centerX + OBSTACLE_RADIUS * cos(theta)
            val obsY = centerY + OBSTACLE_RADIUS * sin(theta)
            obstacles.add(Obstacle(obsX, obsY, OBSTACLE_SIZE))
        }
    }

    // --- GAME ACTIONS ---

    fun startNewGame() {
        state = GameState.PLAYING
        waveNumber = 1
        enemiesKilled = 0
        score = 0
        gameTime = 0f
        waveProgressDelay = 0f

        // Reset player
        player.x = ARENA_CENTER_X
        player.y = ARENA_CENTER_Y
        player.hp = 100f
        player.maxHp = 100f
        player.dashCooldown = 0f
        player.dashActiveTime = 0f
        player.isDashing = false
        player.state = PlayerAnimState.IDLE
        player.shootCooldown = 0f

        // Clear other groups
        enemies.clear()
        for (b in bulletPool) b.isActive = false
        for (f in floatingTexts) f.isActive = false
        for (a in afterimages) a.isActive = false
        for (m in muzzleFlashes) m.isActive = false
        for (p in particlePool) p.isActive = false

        spawnWave(waveNumber)
    }

    private fun spawnWave(wave: Int) {
        enemies.clear()
        val plX = player.x
        val plY = player.y

        when (wave) {
            1 -> {
                // Wave 1 = 10 Soldiers
                spawnEnemiesCount(EnemyType.SOLDIER, 10, plX, plY)
            }
            2 -> {
                // Wave 2 = 15 Soldiers + 3 Elites
                spawnEnemiesCount(EnemyType.SOLDIER, 15, plX, plY)
                spawnEnemiesCount(EnemyType.ELITE, 3, plX, plY)
            }
            3 -> {
                // Wave 3 = 20 Soldiers + 5 Elites
                spawnEnemiesCount(EnemyType.SOLDIER, 20, plX, plY)
                spawnEnemiesCount(EnemyType.ELITE, 5, plX, plY)
            }
            4 -> {
                // Wave 4 = Boss
                triggerBossIntroCinematic()
            }
        }
    }

    private fun spawnEnemiesCount(type: EnemyType, count: Int, plX: Float, plY: Float) {
        var spawned = 0
        while (spawned < count) {
            val angle = Random.nextFloat() * 2.0f * PI.toFloat()
            val dist = Random.nextFloat() * 700f + 550f // Keep range away from center but inside arena
            val ex = (ARENA_CENTER_X + dist * cos(angle)).coerceIn(MIN_X + 60f, MAX_X - 60f)
            val ey = (ARENA_CENTER_Y + dist * sin(angle)).coerceIn(MIN_Y + 60f, MAX_Y - 60f)

            // Check if coordinates overlap with obstacles
            var overlapsObstacle = false
            for (obs in obstacles) {
                if (ex >= obs.left - 20f && ex <= obs.right + 20f && ey >= obs.top - 20f && ey <= obs.bottom + 20f) {
                    overlapsObstacle = true
                    break
                }
            }

            if (!overlapsObstacle && dist(ex, ey, plX, plY) > 400f) {
                val enemy = Enemy(enemyIdCounter.incrementAndGet(), type)
                enemy.x = ex
                enemy.y = ey
                enemy.angle = angle + PI.toFloat() // Face the player's general direction
                enemies.add(enemy)
                spawned++
            }
        }
    }

    private fun triggerBossIntroCinematic() {
        state = GameState.IN_CINEMATIC
        cinematicTimer = 2.5f // 2.5 seconds cinema pause

        // Clear any leftover bullets or texts
        for (b in bulletPool) b.isActive = false

        // Spawn Boss precisely at center
        val boss = Enemy(enemyIdCounter.incrementAndGet(), EnemyType.BOSS)
        boss.x = ARENA_CENTER_X
        boss.y = ARENA_CENTER_Y
        boss.angle = PI.toFloat() / 2f
        enemies.add(boss)

        // Spawn 4 companion soldiers guarding corners
        val guardingCoords = listOf(
            Pair(ARENA_CENTER_X - 250f, ARENA_CENTER_Y - 250f),
            Pair(ARENA_CENTER_X + 250f, ARENA_CENTER_Y - 250f),
            Pair(ARENA_CENTER_X - 250f, ARENA_CENTER_Y + 250f),
            Pair(ARENA_CENTER_X + 250f, ARENA_CENTER_Y + 250f)
        )
        for (coord in guardingCoords) {
            val soldier = Enemy(enemyIdCounter.incrementAndGet(), EnemyType.SOLDIER)
            soldier.x = coord.first
            soldier.y = coord.second
            soldier.angle = atan2(ARENA_CENTER_Y - coord.second, ARENA_CENTER_X - coord.first)
            enemies.add(soldier)
        }
    }

    // --- GAME ENGINE CYCLE UPDATE ---

    fun update(dt: Float) {
        if (state == GameState.START_SCREEN) return

        gameTime += dt

        // Decrement screen shake
        if (screenShakeAmount > 0f) {
            screenShakeAmount = (screenShakeAmount - dt * 25f).coerceAtLeast(0f)
        }

        // Handle visual effects cycles
        updateParticles(dt)
        updateFloatingTexts(dt)
        updateAfterimages(dt)
        updateMuzzleFlashes(dt)

        if (state == GameState.IN_CINEMATIC) {
            cinematicTimer -= dt
            if (cinematicTimer <= 0f) {
                state = GameState.PLAYING
                // Add cinematic impact particles
                createExplosionParticles(ARENA_CENTER_X, ARENA_CENTER_Y, Color.Red, 45)
                screenShakeAmount = 25f
            }
            // Update animations/hurt states only
            updateEnemySpasms(dt)
            return
        }

        if (player.hp <= 0f) {
            // Player death sequence
            player.stateTimer += dt
            if (player.stateTimer > 1.5f) {
                state = GameState.GAME_OVER
            }
            updateEnemySpasms(dt) // Enemies keep moving/playing
            return
        }

        // 1. Progress active player status
        updatePlayerStatus(dt)

        // 2. Active bullets movement & collisions
        updateBullets(dt)

        // 3. Enemies AI decision scripts
        updateEnemiesAI(dt)

        // 4. Wave progression watch
        checkWaveProgression(dt)
    }

    private fun updatePlayerStatus(dt: Float) {
        if (player.dashCooldown > 0f) {
            player.dashCooldown = (player.dashCooldown - dt).coerceAtLeast(0f)
        }
        if (player.shootCooldown > 0f) {
            player.shootCooldown = (player.shootCooldown - dt).coerceAtLeast(0f)
        }
        if (player.hurtTimer > 0f) {
            player.hurtTimer = (player.hurtTimer - dt).coerceAtLeast(0f)
        }

        // Active Dash Mode
        if (player.isDashing) {
            player.dashActiveTime -= dt
            player.afterimageTimer -= dt

            // Draw speedy dash traces
            if (player.afterimageTimer <= 0f) {
                spawnAfterimage(player.x, player.y, player.angle)
                player.afterimageTimer = 0.04f
            }

            // High priority Dash physics
            val prevX = player.x
            val prevY = player.y
            player.x += player.dashVx * dt
            player.y += player.dashVy * dt

            // Obstacle colliding resolution during dash
            resolvePlayerObstacleCollisions(prevX, prevY)

            // Clamp inside bounds
            player.x = player.x.coerceIn(MIN_X + player.radius, MAX_X - player.radius)
            player.y = player.y.coerceIn(MIN_Y + player.radius, MAX_Y - player.radius)

            if (player.dashActiveTime <= 0f) {
                player.isDashing = false
                player.state = PlayerAnimState.IDLE
            }
        } else {
            // Natural animation decay
            if (player.state == PlayerAnimState.HURT && player.hurtTimer <= 0f) {
                player.state = PlayerAnimState.IDLE
            }
        }
    }

    private fun spawnAfterimage(x: Float, y: Float, angle: Float) {
        for (ai in afterimages) {
            if (!ai.isActive) {
                ai.x = x
                ai.y = y
                ai.angle = angle
                ai.alpha = 0.65f
                ai.isActive = true
                break
            }
        }
    }

    // --- ENEMY RECIRCULATION & SIMULATION AI ---

    private fun updateEnemySpasms(dt: Float) {
        // Just keep animating and ticking down timers of enemies during cinematic/death pauses
        for (enemy in enemies) {
            if (enemy.hurtTimer > 0f) enemy.hurtTimer -= dt
            enemy.stateTimer += dt
            if (enemy.hp <= 0f) {
                enemy.deathTimer -= dt
            }
        }
        // Purging dead enemies completely when animation ends
        enemies.removeAll { it.hp <= 0f && it.deathTimer <= 0f }
    }

    private fun updateEnemiesAI(dt: Float) {
        val px = player.x
        val py = player.y

        for (enemy in enemies) {
            if (enemy.hp <= 0f) {
                enemy.deathTimer -= dt
                enemy.stateTimer += dt
                continue
            }

            if (enemy.hurtTimer > 0f) {
                enemy.hurtTimer -= dt
            }
            if (enemy.shootCooldown > 0f) {
                enemy.shootCooldown -= dt
            }

            enemy.stateTimer += dt

            val distToPl = dist(enemy.x, enemy.y, px, py)

            when (enemy.type) {
                EnemyType.SOLDIER -> {
                    // --- STEERING BEHAVIOR AND OBSTACLE AVOIDANCE ---
                    var steerX = px - enemy.x
                    var steerY = py - enemy.y
                    val len = sqrt(steerX * steerX + steerY * steerY)
                    if (len > 0f) {
                        steerX /= len
                        steerY /= len
                    }

                    // Obstacle avoidance force overlay
                    var avoidX = 0f
                    var avoidY = 0f
                    for (obs in obstacles) {
                        val dObs = dist(enemy.x, enemy.y, obs.x, obs.y)
                        val triggerDist = 140f // Obstacle half + padding
                        if (dObs < triggerDist) {
                            // High priority repulsion away from obstacle center
                            var rx = enemy.x - obs.x
                            var ry = enemy.y - obs.y
                            val rLen = sqrt(rx * rx + ry * ry)
                            if (rLen > 0f) {
                                rx /= rLen
                                ry /= rLen
                                val force = (triggerDist - dObs) / triggerDist
                                avoidX += rx * force * 1.8f
                                avoidY += ry * force * 1.8f
                            }
                        }
                    }

                    // Blending steer forward and obstacle separation repulsion
                    var moveX = steerX * 0.7f + avoidX * 1.2f
                    var moveY = steerY * 0.7f + avoidY * 1.2f
                    val mLen = sqrt(moveX * moveX + moveY * moveY)
                    if (mLen > 0f) {
                        moveX /= mLen
                        moveY /= mLen
                    }

                    val prevX = enemy.x
                    val prevY = enemy.y
                    enemy.x += moveX * enemy.speed * dt
                    enemy.y += moveY * enemy.speed * dt
                    enemy.angle = atan2(moveY, moveX)

                    resolveEnemyObstacleCollision(enemy, prevX, prevY)
                    enemy.state = EnemyAnimState.RUN

                    // Soldier fires when within short range
                    if (distToPl < 350f && enemy.shootCooldown <= 0f) {
                        fireEnemyBullet(enemy, px, py)
                        enemy.shootCooldown = 1.6f + Random.nextFloat() * 0.5f
                        enemy.state = EnemyAnimState.ATTACK
                        enemy.stateTimer = 0f
                    }
                }

                EnemyType.ELITE -> {
                    // --- A* PATHFINDING EVERY 250ms ---
                    enemy.pathRecalcTimer -= dt
                    if (enemy.pathRecalcTimer <= 0f || enemy.path.isEmpty()) {
                        enemy.path = aStar.findPath(enemy.x, enemy.y, px, py)
                        enemy.currentPathWaypointIndex = 0
                        enemy.pathRecalcTimer = 0.250f // Every 250ms
                    }

                    navigatePath(enemy, dt)

                    // Elite fires fast double round burst
                    if (distToPl < 450f && enemy.shootCooldown <= 0f) {
                        fireEliteBurst(enemy, px, py)
                        enemy.shootCooldown = 2.2f + Random.nextFloat() * 0.4f
                        enemy.state = EnemyAnimState.ATTACK
                        enemy.stateTimer = 0f
                    }
                }

                EnemyType.BOSS -> {
                    // --- BOSS A* PATHFINDING EVERY 150MS ---
                    enemy.pathRecalcTimer -= dt
                    if (enemy.pathRecalcTimer <= 0f || enemy.path.isEmpty()) {
                        enemy.path = aStar.findPath(enemy.x, enemy.y, px, py)
                        enemy.currentPathWaypointIndex = 0
                        enemy.pathRecalcTimer = 0.150f // Every 150ms
                    }

                    // Boss special attacks tick
                    enemy.bossAttackTimer += dt
                    if (enemy.bossAttackTimer > 4.2f) {
                        triggerBossSpecialAttack(enemy, px, py)
                        enemy.bossAttackTimer = 0f
                    }

                    // Process charge rush if active
                    if (enemy.bossChargeTimer > 0f) {
                        enemy.bossChargeTimer -= dt
                        enemy.state = EnemyAnimState.ATTACK
                        
                        val prevX = enemy.x
                        val prevY = enemy.y
                        enemy.x += enemy.bossChargeVx * dt
                        enemy.y += enemy.bossChargeVy * dt
                        enemy.angle = atan2(enemy.bossChargeVy, enemy.bossChargeVx)

                        resolveEnemyObstacleCollision(enemy, prevX, prevY)
                        spawnBossTrailParticles(enemy)

                        // Collide player during charge
                        if (dist(enemy.x, enemy.y, px, py) < enemy.radius + player.radius) {
                            if (player.takeDamage(22f)) {
                                createExplosionParticles(px, py, Color.Red, 12)
                                triggerHaptic?.invoke()
                                screenShakeAmount = 14f
                                // Push player away Slightly
                                val pkAngle = atan2(py - enemy.y, px - enemy.x)
                                player.x = (player.x + cos(pkAngle) * 80f).coerceIn(MIN_X + 30f, MAX_X - 30f)
                                player.y = (player.y + sin(pkAngle) * 80f).coerceIn(MIN_Y + 30f, MAX_Y - 30f)
                            }
                        }
                    } else {
                        // Standard traversal towards player
                        navigatePath(enemy, dt)
                    }

                    // Standard bullet rapid fires
                    if (distToPl < 600f && enemy.shootCooldown <= 0f && enemy.bossChargeTimer <= 0f) {
                        fireBossStandardSalvo(enemy, px, py)
                        enemy.shootCooldown = 0.65f
                        enemy.state = EnemyAnimState.ATTACK
                    }
                }
            }

            // Universal clamp inside arena sandbox grid
            enemy.x = enemy.x.coerceIn(MIN_X + enemy.radius, MAX_X - enemy.radius)
            enemy.y = enemy.y.coerceIn(MIN_Y + enemy.radius, MAX_Y - enemy.radius)

            // Resolve collision against other enemies (push-separation)
            resolveEnemyOverlaps(enemy)
        }

        // Clean arrays
        enemies.removeAll { it.hp <= 0f && it.deathTimer <= 0f }
    }

    private fun navigatePath(enemy: Enemy, dt: Float) {
        if (enemy.path.isEmpty() || enemy.currentPathWaypointIndex >= enemy.path.size) {
            enemy.state = EnemyAnimState.IDLE
            return
        }

        val waypoint = enemy.path[enemy.currentPathWaypointIndex]
        val dx = waypoint.first - enemy.x
        val dy = waypoint.second - enemy.y
        val distToWaypoint = sqrt(dx * dx + dy * dy)

        if (distToWaypoint < 30f) {
            enemy.currentPathWaypointIndex++
            if (enemy.currentPathWaypointIndex >= enemy.path.size) {
                enemy.state = EnemyAnimState.IDLE
                return
            }
        }

        // Steer towards current target waypoint in the path list
        val targetWp = enemy.path[enemy.currentPathWaypointIndex]
        var moveX = targetWp.first - enemy.x
        var moveY = targetWp.second - enemy.y
        val len = sqrt(moveX * moveX + moveY * moveY)
        if (len > 0f) {
            moveX /= len
            moveY /= len
        }

        val prevX = enemy.x
        val prevY = enemy.y
        enemy.x += moveX * enemy.speed * dt
        enemy.y += moveY * enemy.speed * dt
        enemy.angle = atan2(moveY, moveX)

        resolveEnemyObstacleCollision(enemy, prevX, prevY)
        enemy.state = EnemyAnimState.RUN
    }

    // --- ENEMY COLLISION SAFETY ---

    private fun resolveEnemyObstacleCollision(enemy: Enemy, px: Float, py: Float) {
        for (obs in obstacles) {
            // Check bounding box intersection with buffer
            val eRadius = enemy.radius
            if (enemy.x + eRadius > obs.left && enemy.x - eRadius < obs.right &&
                enemy.y + eRadius > obs.top && enemy.y - eRadius < obs.bottom
            ) {
                // Determine entry axis and push out
                val overlapX = if (enemy.x < obs.x) (enemy.x + eRadius) - obs.left else (enemy.x - eRadius) - obs.right
                val overlapY = if (enemy.y < obs.y) (enemy.y + eRadius) - obs.top else (enemy.y - eRadius) - obs.bottom

                if (abs(overlapX) < abs(overlapY)) {
                    enemy.x -= overlapX
                } else {
                    enemy.y -= overlapY
                }
            }
        }
    }

    private fun resolveEnemyOverlaps(me: Enemy) {
        for (other in enemies) {
            if (other.id == me.id || other.hp <= 0f) continue
            val d = dist(me.x, me.y, other.x, other.y)
            val minDist = me.radius + other.radius
            if (d < minDist) {
                val overlap = minDist - d
                val angle = if (d > 0f) atan2(me.y - other.y, me.x - other.x) else Random.nextFloat() * 2f * PI.toFloat()
                
                // Push them away equally
                val pushX = cos(angle) * (overlap * 0.5f)
                val pushY = sin(angle) * (overlap * 0.5f)
                
                me.x += pushX
                me.y += pushY
                
                other.x -= pushX
                other.y -= pushY
            }
        }
    }

    // --- PLAYER MOVEMENT PHYSIC ENGINE CAPS ---

    fun movePlayer(vx: Float, vy: Float, dt: Float) {
        if (player.hp <= 0f || player.isDashing || state != GameState.PLAYING) return

        val prevX = player.x
        val prevY = player.y

        player.x += vx * player.speed * dt
        player.y += vy * player.speed * dt

        if (vx != 0f || vy != 0f) {
            player.angle = atan2(vy, vx)
            player.state = PlayerAnimState.RUN
        } else {
            if (player.state == PlayerAnimState.RUN) {
                player.state = PlayerAnimState.IDLE
            }
        }

        // Collision bounds resolution against static obstacles
        resolvePlayerObstacleCollisions(prevX, prevY)

        // Lock to world confines
        player.x = player.x.coerceIn(MIN_X + player.radius, MAX_X - player.radius)
        player.y = player.y.coerceIn(MIN_Y + player.radius, MAX_Y - player.radius)
    }

    private fun resolvePlayerObstacleCollisions(prevX: Float, prevY: Float) {
        val pRad = player.radius
        for (obs in obstacles) {
            if (player.x + pRad > obs.left && player.x - pRad < obs.right &&
                player.y + pRad > obs.top && player.y - pRad < obs.bottom
            ) {
                // Find shortest distance vector map push out
                val overlapX = if (player.x < obs.x) (player.x + pRad) - obs.left else (player.x - pRad) - obs.right
                val overlapY = if (player.y < obs.y) (player.y + pRad) - obs.top else (player.y - pRad) - obs.bottom

                if (abs(overlapX) < abs(overlapY)) {
                    player.x -= overlapX
                } else {
                    player.y -= overlapY
                }
            }
        }
    }

    // --- DASH INITIATION ---

    fun performDash(dx: Float, dy: Float) {
        if (player.hp <= 0f || player.dashCooldown > 0f || player.isDashing || state != GameState.PLAYING) return

        // Calculate direction of dash
        var vx = dx
        var vy = dy
        val len = sqrt(vx * vx + vy * vy)
        
        if (len <= 0.01f) {
            // Default to player angle if joysticks are idle
            vx = cos(player.angle)
            vy = sin(player.angle)
        } else {
            vx /= len
            vy /= len
        }

        player.isDashing = true
        player.dashCooldown = DASH_COOLDOWN
        player.dashActiveTime = DASH_DISTANCE / DASH_SPEED
        player.dashVx = vx * DASH_SPEED
        player.dashVy = vy * DASH_SPEED
        player.afterimageTimer = 0f
        
        player.state = PlayerAnimState.DASH
        player.stateTimer = 0f

        // Emit swift dash particles
        createDashSmokeParticles(player.x, player.y, -vx, -vy)
        triggerHaptic?.invoke()
    }

    // --- WEAPONRY FIRE SCHEDULING ---

    fun performPlayerShoot(dx: Float, dy: Float) {
        if (player.hp <= 0f || player.shootCooldown > 0f || player.isDashing || state != GameState.PLAYING) return

        var fireAngle = atan2(dy, dx)
        player.angle = fireAngle // Aim joystick forces facing angle

        // Recoil effect representation
        player.shootCooldown = 0.16f
        player.state = PlayerAnimState.SHOOT
        player.stateTimer = 0f

        // Fire single cyber bullet
        // Double gun offsets (right / left alternating)
        val offsetAngle = fireAngle + (PI.toFloat() / 2f) * (if (Random.nextBoolean()) 1f else -1f)
        val gunX = player.x + cos(fireAngle) * 35f + cos(offsetAngle) * 12f
        val gunY = player.y + sin(fireAngle) * 35f + sin(offsetAngle) * 12f

        addBullet(gunX, gunY, fireAngle, true, 22f, Color.Cyan)
        addMuzzleFlash(gunX, gunY, fireAngle)

        // Haptic tap on fire
        triggerHaptic?.invoke()
    }

    private fun addBullet(x: Float, y: Float, angle: Float, isPlayer: Boolean, damage: Float, color: Color) {
        val b = bulletPool[bulletPoolIndex]
        
        // Recycling oldest bullet cleanly when full
        b.x = x
        b.y = y
        b.vx = cos(angle) * BULLET_SPEED
        b.vy = sin(angle) * BULLET_SPEED
        b.angle = angle
        b.isPlayerOwned = isPlayer
        b.damage = damage
        b.color = color
        b.life = BULLET_LIFETIME
        b.isActive = true

        // Increment pointer inside pool
        bulletPoolIndex = (bulletPoolIndex + 1) % BULLET_POOL_SIZE
    }

    private fun addMuzzleFlash(x: Float, y: Float, angle: Float) {
        for (m in muzzleFlashes) {
            if (!m.isActive) {
                m.x = x
                m.y = y
                m.angle = angle
                m.life = m.maxLife
                m.isActive = true
                break
            }
        }
    }

    // --- ENEMY WEAPON CALLOUT BURST FIRE ---

    private fun fireEnemyBullet(enemy: Enemy, targetX: Float, targetY: Float) {
        val angle = atan2(targetY - enemy.y, targetX - enemy.x) + (Random.nextFloat() * 0.1f - 0.05f)
        val bulletX = enemy.x + cos(angle) * enemy.radius
        val bulletY = enemy.y + sin(angle) * enemy.radius
        addBullet(bulletX, bulletY, angle, false, 8f, Color(0xFFFF3333)) // Light red for common soldiers
        addMuzzleFlash(bulletX, bulletY, angle)
    }

    private fun fireEliteBurst(enemy: Enemy, targetX: Float, targetY: Float) {
        val baseAngle = atan2(targetY - enemy.y, targetX - enemy.x)
        // Fire 2 spread rounds
        val angle1 = baseAngle - 0.12f
        val angle2 = baseAngle + 0.12f

        val bX1 = enemy.x + cos(angle1) * enemy.radius
        val bY1 = enemy.y + sin(angle1) * enemy.radius
        addBullet(bX1, bY1, angle1, false, 14f, Color(0xFFFF5500)) // Bright orange for elites
        addMuzzleFlash(bX1, bY1, angle1)

        val bX2 = enemy.x + cos(angle2) * enemy.radius
        val bY2 = enemy.y + sin(angle2) * enemy.radius
        addBullet(bX2, bY2, angle2, false, 14f, Color(0xFFFF5500))
        addMuzzleFlash(bX2, bY2, angle2)
    }

    private fun fireBossStandardSalvo(enemy: Enemy, targetX: Float, targetY: Float) {
        val baseAngle = atan2(targetY - enemy.y, targetX - enemy.x)
        // Salvo of 3 bullets
        val spreads = listOf(-0.2f, 0f, 0.2f)
        for (sp in spreads) {
            val angle = baseAngle + sp
            val gunX = enemy.x + cos(angle) * enemy.radius
            val gunY = enemy.y + sin(angle) * enemy.radius
            addBullet(gunX, gunY, angle, false, 16f, Color(0xFFFFDE11)) // Gold lasers for boss
            addMuzzleFlash(gunX, gunY, angle)
        }
    }

    private fun triggerBossSpecialAttack(enemy: Enemy, targetX: Float, targetY: Float) {
        enemy.bossAttackPattern = (enemy.bossAttackPattern + 1) % 4
        
        when (enemy.bossAttackPattern) {
            0 -> {
                // 1. CIRCULAR NOVA: Fires 16 bullets in a circular explosion ring
                for (i in 0 until 16) {
                    val angle = i * (2f * PI.toFloat() / 16f)
                    val bX = enemy.x + cos(angle) * (enemy.radius + 10f)
                    val bY = enemy.y + sin(angle) * (enemy.radius + 10f)
                    addBullet(bX, bY, angle, false, 15f, Color(0xFFFF11BB)) // Neon Pink Nova
                }
                screenShakeAmount = 18f
                enemy.state = EnemyAnimState.ATTACK
                enemy.stateTimer = 0f
                spawnText(enemy.x, enemy.y - 100f, "RING NOVA!", Color(0xFFFF11BB), true)
            }
            1 -> {
                // 2. BOSS CHARGE RUSH: Charges at high speed directly at player
                val angle = atan2(targetY - enemy.y, targetX - enemy.x)
                enemy.bossChargeTimer = 1.2f // Rushes for 1.2 seconds max
                enemy.bossChargeVx = cos(angle) * 750f
                enemy.bossChargeVy = sin(angle) * 750f
                enemy.state = EnemyAnimState.ATTACK
                enemy.stateTimer = 0f
                spawnText(enemy.x, enemy.y - 100f, "CHARGE!", Color.Red, true)
            }
            2 -> {
                // 3. RADIAL SPIRAL SPREADS
                val baseAngle = atan2(targetY - enemy.y, targetX - enemy.x)
                for (i in -4..4) {
                    val angle = baseAngle + (i * 0.15f)
                    val bX = enemy.x + cos(angle) * (enemy.radius + 10f)
                    val bY = enemy.y + sin(angle) * (enemy.radius + 10f)
                    addBullet(bX, bY, angle, false, 12f, Color.White)
                }
                spawnText(enemy.x, enemy.y - 100f, "PLASMA SPIRAL!", Color.White, true)
            }
            3 -> {
                // 4. PORTAL CAST: Summons 2 common soldiers at side lanes
                val summonsAngles = listOf(PI.toFloat() / 2f, -PI.toFloat() / 2f)
                for (spAngle in summonsAngles) {
                    val ex = enemy.x + cos(enemy.angle + spAngle) * 160f
                    val ey = enemy.y + sin(enemy.angle + spAngle) * 160f
                    
                    val common = Enemy(enemyIdCounter.incrementAndGet(), EnemyType.SOLDIER)
                    common.x = ex.coerceIn(MIN_X + 40f, MAX_X - 40f)
                    common.y = ey.coerceIn(MIN_Y + 40f, MAX_Y - 40f)
                    common.angle = enemy.angle
                    enemies.add(common)
                    
                    createExplosionParticles(common.x, common.y, Color.Red, 10)
                }
                spawnText(enemy.x, enemy.y - 100f, "GUARD SUMMON!", Color.Yellow, true)
                enemy.state = EnemyAnimState.ATTACK
                enemy.stateTimer = 0f
            }
        }
    }

    private fun spawnBossTrailParticles(enemy: Enemy) {
        val angle = enemy.angle + PI.toFloat() + (Random.nextFloat() * 0.4f - 0.2f)
        val px = enemy.x + cos(angle) * enemy.radius
        val py = enemy.y + sin(angle) * enemy.radius
        addParticle(px, py, cos(angle) * 200f, sin(angle) * 200f, Color.Red, 6f, 0.4f)
    }

    // --- BULLETS SYSTEM MATRIX SIMULATION ---

    private fun updateBullets(dt: Float) {
        for (b in bulletPool) {
            if (!b.isActive) continue

            b.life -= dt
            if (b.life <= 0f) {
                b.isActive = false
                continue
            }

            // High Precision physics integration step
            val nextX = b.x + b.vx * dt
            val nextY = b.y + b.vy * dt

            // Check Obstacles Collisions
            var hitObstacle = false
            for (obs in obstacles) {
                // Simple point vs box collider with small 10f bullet bounding box radius
                if (nextX + 10f > obs.left && nextX - 10f < obs.right &&
                    nextY + 10f > obs.top && nextY - 10f < obs.bottom
                ) {
                    hitObstacle = true
                    createExplosionParticles(b.x, b.y, b.color, 4)
                    break
                }
            }

            if (hitObstacle) {
                b.isActive = false
                continue
            }

            // Check Arena boundary limits
            if (nextX !in MIN_X..MAX_X || nextY !in MIN_Y..MAX_Y) {
                b.isActive = false
                continue
            }

            // Move Bullet position coords
            b.x = nextX
            b.y = nextY

            // --- DAMAGE TRIGGERS COLLISION RESOLUTIONS ---
            if (b.isPlayerOwned) {
                var hitEnemy: Enemy? = null
                for (enemy in enemies) {
                    if (enemy.hp <= 0f) continue
                    if (dist(b.x, b.y, enemy.x, enemy.y) < enemy.radius + 12f) {
                        hitEnemy = enemy
                        break
                    }
                }

                if (hitEnemy != null) {
                    b.isActive = false
                    val isCrit = Random.nextFloat() < 0.15f
                    val baseDamage = b.damage + (Random.nextFloat() * 4f - 2f)
                    val finalDmg = if (isCrit) baseDamage * 1.8f else baseDamage
                    
                    if (hitEnemy.takeDamage(finalDmg)) {
                        createExplosionParticles(b.x, b.y, Color.Cyan, 8)
                        val textCol = if (isCrit) Color(0xFFFFDE11) else Color.Cyan
                        spawnText(b.x, b.y - 20f, "${finalDmg.toInt()}", textCol, isCrit)
                        score += (finalDmg * 1.5).toInt()

                        if (hitEnemy.hp <= 0f) {
                            enemiesKilled++
                            score += if (hitEnemy.type == EnemyType.SOLDIER) 100 else if (hitEnemy.type == EnemyType.ELITE) 300 else 5000
                            createExplosionParticles(hitEnemy.x, hitEnemy.y, Color.Magenta, 22)
                            
                            if (hitEnemy.type == EnemyType.BOSS) {
                                state = GameState.GAME_WON
                            }
                        }
                    }
                }
            } else {
                // Enemy shoots Player
                if (dist(b.x, b.y, player.x, player.y) < player.radius + 10f) {
                    b.isActive = false
                    if (!player.isDashing) {
                        val baseDamage = b.damage + (Random.nextFloat() * 2f - 1f)
                        if (player.takeDamage(baseDamage)) {
                            createExplosionParticles(b.x, b.y, Color.Red, 12)
                            spawnText(b.x, b.y - 20f, "-${baseDamage.toInt()}", Color.Red, false)
                            triggerHaptic?.invoke()
                            screenShakeAmount = 9f
                        }
                    }
                }
            }
        }
    }

    // --- GAME WAVE SYSTEM CODES ---

    private fun checkWaveProgression(dt: Float) {
        // Find if all active core enemies are dead (ignoring summons if requested, or checking all active sizes)
        // Wait, Boss cinematic state counts as no standard list or sum checks
        if (state == GameState.IN_CINEMATIC) return

        val livingMajorEnemies = enemies.count { it.hp > 0f }
        if (livingMajorEnemies == 0) {
            waveProgressDelay += dt
            if (waveProgressDelay > 3.0f) {
                waveProgressDelay = 0f
                waveNumber++
                if (waveNumber <= 4) {
                    spawnWave(waveNumber)
                } else {
                    // Won Game beyond Wave 4 (Safety)
                    state = GameState.GAME_WON
                }
            }
        }
    }

    // --- PROCEDURAL VISUAL SPARK EMITTERS ---

    fun addParticle(x: Float, y: Float, vx: Float, vy: Float, color: Color, size: Float, maxLife: Float) {
        for (p in particlePool) {
            if (!p.isActive) {
                p.x = x
                p.y = y
                p.vx = vx
                p.vy = vy
                p.color = color
                p.size = size
                p.life = maxLife
                p.maxLife = maxLife
                p.isActive = true
                p.alpha = 1f
                break
            }
        }
    }

    private fun createExplosionParticles(x: Float, y: Float, color: Color, count: Int) {
        for (i in 0 until count) {
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val speed = Random.nextFloat() * 240f + 80f
            val maxL = Random.nextFloat() * 0.45f + 0.2f
            val size = Random.nextFloat() * 4f + 3f
            addParticle(x, y, cos(angle) * speed, sin(angle) * speed, color, size, maxL)
        }
    }

    private fun createDashSmokeParticles(x: Float, y: Float, backVectorX: Float, backVectorY: Float) {
        for (i in 0 until 12) {
            val angle = atan2(backVectorY, backVectorX) + (Random.nextFloat() * 0.6f - 0.3f)
            val speed = Random.nextFloat() * 180f + 60f
            val maxL = Random.nextFloat() * 0.4f + 0.15f
            addParticle(x, y, cos(angle) * speed, sin(angle) * speed, Color.Cyan, 5f, maxL)
        }
    }

    fun spawnText(x: Float, y: Float, text: String, color: Color, isCrit: Boolean) {
        for (f in floatingTexts) {
            if (!f.isActive) {
                f.x = x
                f.y = y
                f.text = text
                f.color = color
                f.alpha = 1f
                f.speedY = if (isCrit) -180f else -130f
                // Add minor random drift sideways
                f.speedX = Random.nextFloat() * 70f - 35f
                f.life = if (isCrit) 1.2f else 0.8f
                f.isCritical = isCrit
                f.isActive = true
                break
            }
        }
    }

    // --- TICK CALCULATORS ---

    private fun updateParticles(dt: Float) {
        for (p in particlePool) {
            if (!p.isActive) continue
            p.life -= dt
            if (p.life <= 0f) {
                p.isActive = false
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.alpha = (p.life / p.maxLife).coerceIn(0f, 1f)
        }
    }

    private fun updateFloatingTexts(dt: Float) {
        for (f in floatingTexts) {
            if (!f.isActive) continue
            f.life -= dt
            if (f.life <= 0f) {
                f.isActive = false
                continue
            }
            f.x += f.speedX * dt
            f.y += f.speedY * dt
            f.alpha = (f.life / (if (f.isCritical) 1.2f else 0.8f)).coerceIn(0f, 1f)
        }
    }

    private fun updateAfterimages(dt: Float) {
        for (ai in afterimages) {
            if (!ai.isActive) continue
            ai.alpha -= dt * 2.2f
            if (ai.alpha <= 0f) {
                ai.isActive = false
            }
        }
    }

    private fun updateMuzzleFlashes(dt: Float) {
        for (m in muzzleFlashes) {
            if (!m.isActive) continue
            m.life -= dt
            if (m.life <= 0f) {
                m.isActive = false
            }
        }
    }

    // --- ACCELERATOR HELPERS ---

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    // --- EXPOSURES FOR RENDERING CANVAS ---

    fun getActiveBullets(): List<Bullet> {
        return bulletPool.filter { it.isActive }
    }

    fun getActiveParticles(): List<Particle> {
        return particlePool.filter { it.isActive }
    }

    fun getActiveFloatingTexts(): List<FloatingText> {
        return floatingTexts.filter { it.isActive }
    }

    fun getActiveAfterimages(): List<DashAfterimage> {
        return afterimages.filter { it.isActive }
    }

    fun getActiveMuzzleFlashes(): List<MuzzleFlash> {
        return muzzleFlashes.filter { it.isActive }
    }
}
