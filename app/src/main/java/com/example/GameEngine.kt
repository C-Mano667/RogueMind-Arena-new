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
enum class GameState { START_SCREEN, IN_CINEMATIC, PLAYING, UPGRADE_MENU, LEVEL_UP_MENU, GAME_OVER, GAME_WON, MAIN_MENU, INVENTORY, PERMA_UPGRADES, SETTINGS, STAGE_CLEAR, WEAPON_SELECT }
enum class PowerUpType { DAMAGE, RAPID_FIRE, SPEED, SHIELD, HEALTH, MAGNET, BERSERK, GOLDEN, OVERDRIVE, FREEZE, CREDIT, WEAPON_CRATE, NUKE }
enum class WeaponType { DEFAULT, SHOTGUN, SMG, ASSAULT_RIFLE, LASER_RIFLE, PLASMA_CANNON, ROCKET_LAUNCHER, RAILGUN }
enum class UpgradeType { 
    RAPID_FIRE, DAMAGE_CORE, ACCELERATOR, PROJECTILE_EX, PRECISION, CRIT_CORE,
    REINFORCED_ARMOR, CYBER_LEGS, DASH_CAP, XP_SCANNER, NANO_REPAIR,
    SHOCKWAVE, INCENDIARY, CHAIN_LIGHTNING, EXPLOSIVE, AUTO_SHIELD 
}


class GameEngine(val context: android.content.Context? = null) {

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
    var state = GameState.MAIN_MENU
    var currentStage = 1
    var currentPhase = 0 // 0=Quota, 1=Boss Warning, 2=Boss Active
    var stageTotalEnemies = 20
    var stageEnemiesSpawned = 0
    var stageEnemiesKilled = 0
    var stageEnemyCap = 5
    var bossWarningTimer = 0f
    var waveProgressDelay = 0f
    var supportSoldiersTimer = 0f
    var supportSoldiersSpawned = false
    var score = 0
    var enemiesKilled = 0
    var gameTime = 0f
    
    var level = 1
    var xp = 0
    var requiredXp = 100
    
    // Perma progression / Saves
    var credits = 0
    var highestStage = 1
    var highestLevel = 1
    var bestScore = 0
    var totalEnemiesKilledAllTime = 0
    var totalPlayTime = 0f
    
    // Perma Upgrades
    var permaDamageLvl = 0
    var permaFireRateLvl = 0
    var permaHpLvl = 0
    var permaSpeedLvl = 0
    var permaDashLvl = 0
    var permaCritChanceLvl = 0
    var permaCritDamageLvl = 0

    // Weapon Unlocks
    var unlockedShotgun = false
    var unlockedSmg = false
    var unlockedAssaultRifle = false
    var unlockedLaserRifle = false
    var unlockedPlasmaCannon = false
    var unlockedRailgun = false

    var activeWeapon = WeaponType.DEFAULT
    var weaponChoices = emptyList<WeaponType>()

    var freezeTimer = 0f
    var weaponCratesAvailable = mutableListOf<WeaponType>()
    
    // Upgrades
    var upgDamageMod = 0f
    var upgFireRateMod = 0f
    var upgSpeedMod = 0f
    var upgDashCooldown = 0f
    var upgMaxHp = 0f
    var upgCritChance = 0f
    var upgCritDamage = 0f
    var upgBulletSpeedMod = 1f
    var upgExtraProjectiles = 0
    var upgMagnetActive = false
    var upgXpRadiusMod = 0f
    var upgNanoRepair = false
    var nanoRepairTimer = 0f
    
    var upgShockwave = false
    var upgIncendiary = false
    var upgChainLightning = false
    var upgExplosive = false
    var upgAutoShield = false
    var playerShield = 0f
    var autoShieldTimer = 0f
    
    // PowerUp Buffs
    var buffDamageTimer = 0f
    var buffRapidFireTimer = 0f
    var buffSpeedTimer = 0f
    var buffShieldTimer = 0f
    var buffMagnetTimer = 0f
    var buffMultiTimer = 0f
    var buffBerserkTimer = 0f
    var buffOverdriveTimer = 0f

    var timeScale = 1.0f
    var hitStopTimer = 0f

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
        var speedMod = 1.0f
        var damageMod = 1.0f
        var fireRateMod = 1.0f
        var critChance = 0.15f
        var dashCooldownBase = 3.0f
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
    class Enemy(var id: Int, var type: EnemyType) {
        var x = 0f
        var y = 0f
        var radius = if (type == EnemyType.SOLDIER) 24f else if (type == EnemyType.ELITE) 30f else 65f
        var hp = if (type == EnemyType.SOLDIER) 30f else if (type == EnemyType.ELITE) 90f else 1200f
        var maxHp = hp
        var speed = if (type == EnemyType.SOLDIER) 160f else if (type == EnemyType.ELITE) 210f else 115f
        var angle = 0f
        var isActive = false
        
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
        var deathHandled = false
        
        fun reset(newId: Int, newType: EnemyType) {
            id = newId
            type = newType
            radius = if (type == EnemyType.SOLDIER) 24f else if (type == EnemyType.ELITE) 30f else 65f
            hp = if (type == EnemyType.SOLDIER) 30f else if (type == EnemyType.ELITE) 90f else 1200f
            maxHp = hp
            speed = if (type == EnemyType.SOLDIER) 160f else if (type == EnemyType.ELITE) 210f else 115f
            hurtTimer = 0f
            shootCooldown = 0f
            state = EnemyAnimState.IDLE
            stateTimer = 0f
            deathTimer = 0f
            path = emptyList()
            currentPathWaypointIndex = 0
            pathRecalcTimer = 0f
            bossAttackTimer = 0f
            bossAttackPattern = 0
            bossChargeTimer = 0f
            bossChargeVx = 0f
            bossChargeVy = 0f
            deathHandled = false
            isActive = true
        }
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

    private val enemyPool = Array(300) { Enemy(it, EnemyType.SOLDIER) }
    private var enemyPoolIndex = 0

    private fun getPooledEnemy(type: EnemyType): Enemy {
        for (i in 0 until 300) {
            val idx = (enemyPoolIndex + i) % 300
            val enemy = enemyPool[idx]
            if (!enemy.isActive) {
                enemyPoolIndex = (idx + 1) % 300
                enemy.reset(enemyIdCounter.incrementAndGet(), type)
                return enemy
            }
        }
        val fallback = enemyPool[0]
        fallback.reset(enemyIdCounter.incrementAndGet(), type)
        return fallback
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

    class XpOrb {
        var x = 0f
        var y = 0f
        var amount = 0
        var isActive = false
        var isMagnetized = false
    }
    val xpOrbs = Array(150) { XpOrb() }

    class PowerUp {
        var x = 0f
        var y = 0f
        var type = PowerUpType.HEALTH
        var life = 0f
        var isActive = false
        var hoverOffset = 0f
    }
    val powerUps = Array(10) { PowerUp() }
    var powerUpSpawnTimer = 0f

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
        
        try {
            loadPersistence()
        } catch (e: Exception) {
            android.util.Log.e("RogueMind", "Failed to load persistence: ${e.message}", e)
        }
    }

    fun hurtPlayer(amount: Float): Boolean {
        if (buffOverdriveTimer > 0f) return false
        
        var realDamage = amount
        if (playerShield > 0f) {
            if (realDamage <= playerShield) {
                playerShield -= realDamage
                return false
            } else {
                realDamage -= playerShield
                playerShield = 0f
            }
        }
        return player.takeDamage(realDamage)
    }

    // --- GAME ACTIONS ---

fun spawnXpOrb(x: Float, y: Float, amount: Int) {
        for (orb in xpOrbs) {
            if (!orb.isActive) {
                orb.x = x
                orb.y = y
                orb.amount = amount
                orb.isActive = true
                orb.isMagnetized = false
                break
            }
        }
    }

    fun savePersistence() {
        context?.let {
            val prefs = it.getSharedPreferences("RogueMindPrefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("highestStage", highestStage)
                putInt("highestLevel", highestLevel)
                putInt("bestScore", bestScore)
                putInt("totalEnemies", totalEnemiesKilledAllTime)
                putFloat("totalPlayTime", totalPlayTime)
                putInt("credits", credits)
                putInt("permaDamage", permaDamageLvl)
                putInt("permaFireRate", permaFireRateLvl)
                putInt("permaHp", permaHpLvl)
                putInt("permaSpeed", permaSpeedLvl)
                putInt("permaDash", permaDashLvl)
                putInt("permaCritChance", permaCritChanceLvl)
                putInt("permaCritDamage", permaCritDamageLvl)
                
                putBoolean("unlockedShotgun", unlockedShotgun)
                putBoolean("unlockedSmg", unlockedSmg)
                putBoolean("unlockedAssaultRifle", unlockedAssaultRifle)
                putBoolean("unlockedLaserRifle", unlockedLaserRifle)
                putBoolean("unlockedPlasmaCannon", unlockedPlasmaCannon)
                putBoolean("unlockedRailgun", unlockedRailgun)
                
                putString("activeWeapon", activeWeapon.name)
                apply()
            }
        }
    }

    fun loadPersistence() {
        context?.let {
            val prefs = it.getSharedPreferences("RogueMindPrefs", android.content.Context.MODE_PRIVATE)
            highestStage = prefs.getInt("highestStage", 1)
            currentStage = highestStage
            highestLevel = prefs.getInt("highestLevel", 1)
            bestScore = prefs.getInt("bestScore", 0)
            totalEnemiesKilledAllTime = prefs.getInt("totalEnemies", 0)
            totalPlayTime = prefs.getFloat("totalPlayTime", 0f)
            credits = prefs.getInt("credits", 0)
            permaDamageLvl = prefs.getInt("permaDamage", 0)
            permaFireRateLvl = prefs.getInt("permaFireRate", 0)
            permaHpLvl = prefs.getInt("permaHp", 0)
            permaSpeedLvl = prefs.getInt("permaSpeed", 0)
            permaDashLvl = prefs.getInt("permaDash", 0)
            permaCritChanceLvl = prefs.getInt("permaCritChance", 0)
            permaCritDamageLvl = prefs.getInt("permaCritDamage", 0)

            unlockedShotgun = prefs.getBoolean("unlockedShotgun", highestStage >= 2)
            unlockedSmg = prefs.getBoolean("unlockedSmg", highestStage >= 5)
            unlockedAssaultRifle = prefs.getBoolean("unlockedAssaultRifle", highestStage >= 10)
            unlockedLaserRifle = prefs.getBoolean("unlockedLaserRifle", highestStage >= 15)
            unlockedPlasmaCannon = prefs.getBoolean("unlockedPlasmaCannon", highestStage >= 25)
            unlockedRailgun = prefs.getBoolean("unlockedRailgun", highestStage >= 40)
            
            try {
                val wepName = prefs.getString("activeWeapon", "DEFAULT") ?: "DEFAULT"
                activeWeapon = WeaponType.valueOf(wepName)
            } catch (e: Exception) {
                activeWeapon = WeaponType.DEFAULT
            }
        }
    }

    fun gainXp(amount: Int) {
        if (state != GameState.PLAYING) return
        xp += amount
        if (xp >= requiredXp) {
            // Level Up Trigger!
            xp -= requiredXp
            level++
            requiredXp = (requiredXp * 1.5f).roundToInt()
            
            // Lightweight passive increase
            upgMaxHp += 5f
            player.maxHp += 5f
            player.hp = (player.hp + 15f).coerceAtMost(player.maxHp)
            upgDamageMod += 0.05f
            upgFireRateMod += 0.02f
            
            spawnText(player.x, player.y - 60f, "LEVEL UP!", Color(0xFFFFCC00), true)
            
            if (level > highestLevel) highestLevel = level
        }
    }
    fun startNewGame() {
        state = GameState.PLAYING
        currentStage = 1
        level = 1
        xp = 0
        requiredXp = 100
        enemiesKilled = 0
        score = 0
        gameTime = 0f
        waveProgressDelay = 0f
        
        upgDamageMod = permaDamageLvl * 0.10f
        upgFireRateMod = permaFireRateLvl * 0.05f
        upgSpeedMod = permaSpeedLvl * 0.05f
        upgDashCooldown = permaDashLvl * 0.1f
        upgMaxHp = permaHpLvl * 10f
        upgCritChance = permaCritChanceLvl * 0.05f
        upgCritDamage = permaCritDamageLvl * 0.10f
        
        upgBulletSpeedMod = 1f
        upgExtraProjectiles = 0
        upgMagnetActive = false
        upgXpRadiusMod = 0f
        upgNanoRepair = false
        upgShockwave = false
        upgIncendiary = false
        upgChainLightning = false
        upgExplosive = false
        upgAutoShield = false
        playerShield = 0f
        
        activeWeapon = WeaponType.DEFAULT
        
        buffDamageTimer = 0f
        buffRapidFireTimer = 0f
        buffSpeedTimer = 0f
        buffShieldTimer = 0f
        buffMagnetTimer = 0f
        buffMultiTimer = 0f
        buffBerserkTimer = 0f
        buffOverdriveTimer = 0f
        
        for (orb in xpOrbs) orb.isActive = false
        for (pu in powerUps) pu.isActive = false

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

        startStage(currentStage)
    }

    fun startStage(stage: Int) {
        currentStage = stage
        for (e in enemies) e.isActive = false
        enemies.clear()
        
        stageTotalEnemies = when (stage) {
            1 -> 20
            2 -> 25
            5 -> 35
            10 -> 50
            25 -> 80
            50 -> 120
            else -> 20 + (stage * 2)
        }
        stageEnemyCap = when (stage) {
            1 -> 5
            2 -> 5
            5 -> 6
            10 -> 7
            25 -> 10
            50 -> 12
            else -> 5 + (stage / 5)
        }
        
        stageEnemiesSpawned = 0
        stageEnemiesKilled = 0
        currentPhase = 0 // Quota filling
        bossWarningTimer = 0f
        
        spawnUpToCap()
    }

    fun spawnUpToCap() {
        if (currentPhase != 0) return
        
        val activeEnemies = enemies.count { it.hp > 0f && it.type != EnemyType.BOSS }
        val needed = stageEnemyCap - activeEnemies
        val canSpawn = stageTotalEnemies - stageEnemiesSpawned
        
        val toSpawn = needed.coerceAtMost(canSpawn)
        
        if (toSpawn > 0) {
            spawnEnemiesCount(toSpawn)
        }
    }

    private fun spawnEnemiesCount(count: Int) {
        val plX = player.x
        val plY = player.y
        val hpScale = Math.pow(1.10, (currentStage - 1).toDouble()).toFloat()
        
        var spawned = 0
        var attempts = 0

        while (spawned < count && attempts < 1000) {
            attempts++
            val angle = Random.nextFloat() * 2.0f * PI.toFloat()
            val dist = Random.nextFloat() * 700f + 550f 
            val ex = (ARENA_CENTER_X + dist * cos(angle)).coerceIn(MIN_X + 60f, MAX_X - 60f)
            val ey = (ARENA_CENTER_Y + dist * sin(angle)).coerceIn(MIN_Y + 60f, MAX_Y - 60f)

            var overlapsObstacle = false
            for (obs in obstacles) {
                if (ex >= obs.left - 20f && ex <= obs.right + 20f && ey >= obs.top - 20f && ey <= obs.bottom + 20f) {
                    overlapsObstacle = true
                    break
                }
            }

            if (!overlapsObstacle && dist(ex, ey, plX, plY) > 400f) {
                val type = if (currentStage > 1 && Random.nextFloat() < 0.2f) EnemyType.ELITE else EnemyType.SOLDIER
                val enemy = getPooledEnemy(type)
                enemy.x = ex
                enemy.y = ey
                
                if (type == EnemyType.SOLDIER) {
                    enemy.maxHp = 100f * hpScale
                    enemy.hp = enemy.maxHp
                } else if (type == EnemyType.ELITE) {
                    enemy.maxHp = 350f * hpScale
                    enemy.hp = enemy.maxHp
                }
                
                enemy.angle = angle + PI.toFloat()
                enemies.add(enemy)
                spawned++
                stageEnemiesSpawned++
            }
        }
    }

    private fun triggerBossIntroCinematic(stage: Int) {
        android.util.Log.d("RogueMind", "BOSS_SPAWN_BEGIN")
        state = GameState.IN_CINEMATIC
        cinematicTimer = 2.5f 
        
        cinematicBossName = when (stage) {
            1 -> "CYBER TANK"
            5 -> "TITAN MECH"
            10 -> "PLASMA OVERSEER"
            20 -> "WAR MACHINE"
            30 -> "OMEGA CORE"
            40 -> "VOID TITAN"
            50 -> "ROGUEMIND PRIME"
            else -> "MECHANICAL ABOMINATION"
        }
        cinematicBossTitle = "STAGE $stage GUARDIAN"

        for (b in bulletPool) b.isActive = false

        val boss = getPooledEnemy(EnemyType.BOSS)
        boss.x = ARENA_CENTER_X
        boss.y = ARENA_CENTER_Y
        boss.maxHp = 400f + (stage - 1) * 50f
        boss.hp = boss.maxHp

        boss.angle = PI.toFloat() / 2f
        enemies.add(boss)

        supportSoldiersTimer = 2.0f
        supportSoldiersSpawned = false

        android.util.Log.d("RogueMind", "BOSS_SPAWN_COMPLETE")
    }

    private fun spawnSupportSoldiers() {
        val guardingCoords = listOf(
            Pair(ARENA_CENTER_X - 250f, ARENA_CENTER_Y - 250f),
            Pair(ARENA_CENTER_X + 250f, ARENA_CENTER_Y - 250f),
            Pair(ARENA_CENTER_X - 250f, ARENA_CENTER_Y + 250f),
            Pair(ARENA_CENTER_X + 250f, ARENA_CENTER_Y + 250f),
            Pair(ARENA_CENTER_X, ARENA_CENTER_Y - 300f) // 5th soldier
        )
        for (coord in guardingCoords) {
            val soldier = getPooledEnemy(EnemyType.SOLDIER)
            soldier.x = coord.first
            soldier.y = coord.second
            soldier.angle = atan2(ARENA_CENTER_Y - coord.second, ARENA_CENTER_X - coord.first)
            enemies.add(soldier)
        }
        supportSoldiersSpawned = true
        android.util.Log.d("RogueMind", "SUPPORT_SOLDIERS_SPAWNED")
    }

    // --- GAME ENGINE CYCLE UPDATE ---

    fun update(dtRaw: Float) {
        if (state != GameState.PLAYING && state != GameState.IN_CINEMATIC) return

        if (hitStopTimer > 0f) {
            hitStopTimer -= dtRaw
            return // Skip updates to create hit-stop effect
        }

        val dt = dtRaw * timeScale
        gameTime += dt
        
        updateBuffs(dt)
        updatePowerUpSpawns(dt)
        updateXpOrbs(dt)

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
            cinematicTimer -= dtRaw
            if (cinematicTimer <= 0f) {
                state = GameState.PLAYING
                // Add cinematic impact particles
                createExplosionParticles(ARENA_CENTER_X, ARENA_CENTER_Y, Color.Red, 30)
                screenShakeAmount = 25f
                android.util.Log.d("RogueMind", "BOSS_AI_START")
            }
        }

        if (player.hp <= 0f) {
            // Player death sequence
            player.stateTimer += dt
            if (player.stateTimer > 1.5f && state != GameState.GAME_OVER) {
                totalPlayTime += gameTime
                savePersistence()
                state = GameState.GAME_OVER
            }
            updateEnemySpasms(dt) // Enemies keep moving/playing
            return
        }

        // 1. Progress active player status
        updatePlayerStatus(dt)

        // 2. Active bullets movement & collisions
        updateBullets(dt)

        // 3. Enemies AI decision scripts & Animations/Hurt Tracking
        updateEnemySpasms(dt)
        updateEnemiesAI(dt)

        // 4. Wave progression watch
        checkStageProgression(dt)
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

    private fun updateBuffs(dt: Float) {
        if (buffDamageTimer > 0f) buffDamageTimer -= dt
        if (buffRapidFireTimer > 0f) buffRapidFireTimer -= dt
        if (buffSpeedTimer > 0f) buffSpeedTimer -= dt
        if (buffShieldTimer > 0f) buffShieldTimer -= dt
        if (buffMagnetTimer > 0f) buffMagnetTimer -= dt
        if (buffMultiTimer > 0f) buffMultiTimer -= dt
        if (buffBerserkTimer > 0f) buffBerserkTimer -= dt
        if (buffOverdriveTimer > 0f) {
            buffOverdriveTimer -= dt
        }
        
        // Recalculate player modifiers
        player.damageMod = 1.0f + upgDamageMod + (if (buffDamageTimer > 0f) 0.5f else 0f) + (if (buffBerserkTimer > 0f) 0.25f else 0f)
        player.fireRateMod = 1.0f + upgFireRateMod + (if (buffRapidFireTimer > 0f) 0.4f else 0f) + (if (buffBerserkTimer > 0f) 0.25f else 0f)
        player.speedMod = 1.0f + upgSpeedMod + (if (buffSpeedTimer > 0f) 0.3f else 0f) + (if (buffBerserkTimer > 0f) 0.15f else 0f)
        player.dashCooldownBase = 3.0f - upgDashCooldown
        player.critChance = 0.15f + upgCritChance
        
        if (upgAutoShield && autoShieldTimer <= 0f && playerShield <= 0f) {
            autoShieldTimer = 30f
            playerShield = 50f
        } else if (autoShieldTimer > 0f) {
            autoShieldTimer -= dt
        }

        if (upgNanoRepair) {
            nanoRepairTimer -= dt
            if (nanoRepairTimer <= 0f) {
                player.hp = (player.hp + 2f).coerceAtMost(player.maxHp)
                nanoRepairTimer = 5f
            }
        }
    }
    
    private fun spawnPowerUp(x: Float, y: Float, forcedType: PowerUpType? = null) {
        val type = forcedType ?: when (Random.nextFloat()) {
            in 0.000f..0.030f -> PowerUpType.NUKE
            in 0.030f..0.060f -> PowerUpType.OVERDRIVE
            in 0.060f..0.100f -> PowerUpType.GOLDEN
            in 0.100f..0.150f -> PowerUpType.WEAPON_CRATE
            in 0.150f..0.220f -> PowerUpType.FREEZE
            in 0.220f..0.320f -> PowerUpType.CREDIT
            in 0.320f..0.400f -> PowerUpType.MAGNET
            in 0.400f..0.520f -> PowerUpType.BERSERK
            in 0.520f..0.620f -> PowerUpType.DAMAGE
            in 0.620f..0.720f -> PowerUpType.RAPID_FIRE
            in 0.720f..0.820f -> PowerUpType.SPEED
            in 0.820f..0.920f -> PowerUpType.SHIELD
            else -> PowerUpType.HEALTH
        }
        for (pu in powerUps) {
            if (!pu.isActive) {
                pu.x = x
                pu.y = y
                pu.type = type
                pu.life = 20f
                pu.isActive = true
                pu.hoverOffset = 0f
                break
            }
        }
    }

    private fun updatePowerUpSpawns(dt: Float) {
        for (pu in powerUps) {
            if (!pu.isActive) continue
            pu.life -= dt
            pu.hoverOffset += dt * 2f
            if (pu.life <= 0f) {
                pu.isActive = false
                continue
            }
            if (dist(pu.x, pu.y, player.x, player.y) < 50f) {
                collectPowerUp(pu)
            }
        }
    }

    private fun collectPowerUp(pu: PowerUp) {
        pu.isActive = false
        triggerHaptic?.invoke()
        createExplosionParticles(pu.x, pu.y, Color.Cyan, 15)
        
        val duration = 15f
        when(pu.type) {
            PowerUpType.DAMAGE -> { buffDamageTimer = duration; spawnText(player.x, player.y - 40f, "DAMAGE UP!", Color.Red, true) }
            PowerUpType.RAPID_FIRE -> { buffRapidFireTimer = duration; spawnText(player.x, player.y - 40f, "RAPID FIRE!", Color.Yellow, true) }
            PowerUpType.SPEED -> { buffSpeedTimer = duration; spawnText(player.x, player.y - 40f, "SPEED UP!", Color.Cyan, true) }
            PowerUpType.SHIELD -> { buffShieldTimer = 20f; playerShield += 100f; spawnText(player.x, player.y - 40f, "+100 SHIELD", Color.Blue, true) }
            PowerUpType.HEALTH -> { player.hp = (player.hp + 30f).coerceAtMost(player.maxHp); spawnText(player.x, player.y - 40f, "+30 HP", Color.Green, true) }
            PowerUpType.MAGNET -> { buffMagnetTimer = 20f; spawnText(player.x, player.y - 40f, "MAGNET!", Color(0xFFFF00FF), true) }
            PowerUpType.BERSERK -> { buffBerserkTimer = 10f; spawnText(player.x, player.y - 40f, "BERSERK!", Color.Red, true) }
            PowerUpType.GOLDEN -> { gainXp(requiredXp - xp); spawnText(player.x, player.y - 40f, "LEVEL UP!", Color(0xFFFFD700), true) }
            PowerUpType.OVERDRIVE -> { buffOverdriveTimer = 5f; spawnText(player.x, player.y - 40f, "OVERDRIVE!", Color(0xFF00FFFF), true) }
            PowerUpType.FREEZE -> { freezeTimer = 5f; spawnText(player.x, player.y - 40f, "FREEZE!", Color(0xFFAAAAAA), true) }
            PowerUpType.CREDIT -> { credits += 50; spawnText(player.x, player.y - 40f, "+50 CREDITS", Color.Yellow, true) }
            PowerUpType.NUKE -> { 
                for (e in enemies) if (e.hp > 0f) e.takeDamage(1000f)
                spawnText(player.x, player.y - 40f, "NUKE!", Color.Red, true) 
                createExplosionParticles(player.x, player.y, Color.Red, 40)
            }
            PowerUpType.WEAPON_CRATE -> {
                // Populate weapons to choose from
                val availableWeapons = mutableListOf<WeaponType>()
                if (unlockedShotgun) availableWeapons.add(WeaponType.SHOTGUN)
                if (unlockedSmg) availableWeapons.add(WeaponType.SMG)
                if (unlockedAssaultRifle) availableWeapons.add(WeaponType.ASSAULT_RIFLE)
                if (unlockedLaserRifle) availableWeapons.add(WeaponType.LASER_RIFLE)
                if (unlockedPlasmaCannon) availableWeapons.add(WeaponType.PLASMA_CANNON)
                if (unlockedRailgun) availableWeapons.add(WeaponType.RAILGUN)
                
                if (availableWeapons.isEmpty()) {
                    spawnText(player.x, player.y - 40f, "NO WEAPONS UNLOCKED", Color.Gray, true)
                } else {
                    weaponCratesAvailable.clear()
                    availableWeapons.shuffle()
                    weaponCratesAvailable.addAll(availableWeapons.take(3))
                    state = GameState.WEAPON_SELECT
                }
            }
            else -> {}
        }
    }

    private fun updateXpOrbs(dt: Float) {
        val grabRadius = 120f * (1f + upgXpRadiusMod) * (if (buffMagnetTimer > 0f) 4f else 1f)
        for (orb in xpOrbs) {
            if (!orb.isActive) continue
            var d = dist(orb.x, orb.y, player.x, player.y)
            if (d < grabRadius || orb.isMagnetized) {
                orb.isMagnetized = true
                val angle = atan2(player.y - orb.y, player.x - orb.x)
                orb.x += cos(angle) * 800f * dt
                orb.y += sin(angle) * 800f * dt
                d = dist(orb.x, orb.y, player.x, player.y)
                if (d < 50f) {
                    orb.isActive = false
                    gainXp(orb.amount)
                }
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
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            if (enemy.hp <= 0f && enemy.deathTimer <= 0f) {
                enemy.isActive = false
                iterator.remove()
            }
        }
    }

    private fun updateEnemiesAI(dt: Float) {
        if (state == GameState.IN_CINEMATIC) return
        
        if (freezeTimer > 0f) {
            freezeTimer -= dt
            return
        }
        val px = player.x
        val py = player.y

        for (enemy in enemies) {
            if (enemy.hp <= 0f) {
                if (!enemy.deathHandled) {
                    enemy.deathHandled = true
                    
                    enemiesKilled++
                    stageEnemiesKilled++
                    totalEnemiesKilledAllTime++
                    score += if (enemy.type == EnemyType.SOLDIER) 100 else if (enemy.type == EnemyType.ELITE) 300 else 5000
                    createExplosionParticles(enemy.x, enemy.y, Color.Magenta, 22)
                    
                    val xpGained = if (enemy.type == EnemyType.SOLDIER) 10 else if (enemy.type == EnemyType.ELITE) 40 else 500
                    spawnXpOrb(enemy.x, enemy.y, xpGained)

                    val dropChance = if (enemy.type == EnemyType.SOLDIER) 0.5f else 1.0f
                    if (enemy.type == EnemyType.BOSS || Random.nextFloat() < dropChance) {
                        if (powerUps.count { it.isActive } < 10) {
                            if (enemy.type == EnemyType.BOSS) {
                                spawnPowerUp(enemy.x - 30f, enemy.y - 30f) // Just spawn something, we'll randomize types
                                spawnPowerUp(enemy.x + 30f, enemy.y - 30f)
                                spawnPowerUp(enemy.x, enemy.y + 30f)
                                spawnXpOrb(enemy.x, enemy.y + 40f, 1000)
                            } else {
                                spawnPowerUp(enemy.x, enemy.y)
                            }
                        }
                    }
                }
                
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

                    val hpRatio = enemy.hp / enemy.maxHp
                    val isPhase1 = hpRatio > 0.6f
                    val isPhase2 = hpRatio > 0.3f && hpRatio <= 0.6f
                    val isRage = hpRatio <= 0.3f
                    
                    val attackInterval = if (isRage) 2.5f else if (isPhase2) 3.5f else 4.2f

                    // Boss special attacks tick
                    enemy.bossAttackTimer += dt
                    if (enemy.bossAttackTimer > attackInterval) {
                        triggerBossSpecialAttack(enemy, px, py, isPhase1, isPhase2, isRage)
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
                            if (hurtPlayer(22f)) {
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
        if (player.hp <= 0f || player.isDashing || (state != GameState.PLAYING && state != GameState.IN_CINEMATIC)) return

        val prevX = player.x
        val prevY = player.y

        player.x += vx * player.speed * player.speedMod * dt
        player.y += vy * player.speed * player.speedMod * dt

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
        if (player.hp <= 0f || player.dashCooldown > 0f || player.isDashing || (state != GameState.PLAYING && state != GameState.IN_CINEMATIC)) return

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
        player.dashCooldown = player.dashCooldownBase
        player.dashActiveTime = DASH_DISTANCE / DASH_SPEED
        player.dashVx = vx * DASH_SPEED
        player.dashVy = vy * DASH_SPEED
        player.afterimageTimer = 0f
        
        player.state = PlayerAnimState.DASH
        player.stateTimer = 0f

        if (upgShockwave) {
            for (enemy in enemies) {
                if (dist(player.x, player.y, enemy.x, enemy.y) < 180f) {
                    enemy.takeDamage(15f)
                    val pkAngle = atan2(enemy.y - player.y, enemy.x - player.x)
                    enemy.x += cos(pkAngle) * 60f
                    enemy.y += sin(pkAngle) * 60f
                }
            }
            createExplosionParticles(player.x, player.y, Color.Yellow, 20)
        }

        // Emit swift dash particles
        createDashSmokeParticles(player.x, player.y, -vx, -vy)
        triggerHaptic?.invoke()
    }

    // --- WEAPONRY FIRE SCHEDULING ---

    fun performPlayerShoot(dx: Float, dy: Float) {
        if (player.hp <= 0f || player.shootCooldown > 0f || player.isDashing || (state != GameState.PLAYING && state != GameState.IN_CINEMATIC)) return

        var fireAngle = atan2(dy, dx)
        player.angle = fireAngle // Aim joystick forces facing angle

        player.state = PlayerAnimState.SHOOT
        player.stateTimer = 0f

        val offsetAngle = fireAngle + (PI.toFloat() / 2f) * (if (Random.nextBoolean()) 1f else -1f)
        val gunX = player.x + cos(fireAngle) * 35f + cos(offsetAngle) * 12f
        val gunY = player.y + sin(fireAngle) * 35f + sin(offsetAngle) * 12f

        var baseDmg = 22f
        var fireCooldown = 0.16f
        var projCount = 1
        var spread = 0.0f
        var autoColor = Color.Cyan
        
        when (activeWeapon) {
            WeaponType.SHOTGUN -> {
                baseDmg = 18f
                fireCooldown = 0.6f
                projCount = 6
                spread = 0.5f
                autoColor = Color(0xFFFF5555)
            }
            WeaponType.SMG -> {
                baseDmg = 12f
                fireCooldown = 0.08f
                projCount = 1
                autoColor = Color(0xFFFFFF55)
                spread = 0.1f // Innacuracy
            }
            WeaponType.ASSAULT_RIFLE -> {
                baseDmg = 25f
                fireCooldown = 0.14f
                projCount = 1
                autoColor = Color(0xFF55FF55)
            }
            WeaponType.LASER_RIFLE -> {
                baseDmg = 35f
                fireCooldown = 0.25f
                projCount = 1
                autoColor = Color(0xFFFF0055)
            }
            WeaponType.PLASMA_CANNON -> {
                baseDmg = 65f
                fireCooldown = 0.45f
                projCount = 1
                autoColor = Color(0xFFBB00FF)
            }
            WeaponType.ROCKET_LAUNCHER -> {
                baseDmg = 120f
                fireCooldown = 0.8f
                projCount = 1
                autoColor = Color(0xFFFF8800)
            }
            WeaponType.RAILGUN -> {
                baseDmg = 250f
                fireCooldown = 1.2f
                projCount = 1
                autoColor = Color(0xFFFFFFFF)
            }
            else -> {}
        }
        
        player.shootCooldown = (if (buffOverdriveTimer > 0f) 0.05f else fireCooldown) / player.fireRateMod
        
        val totalProjCount = projCount + upgExtraProjectiles

        if (totalProjCount == 1) {
            val finalAngle = if (spread > 0f) fireAngle + (Random.nextFloat() * spread - spread/2f) else fireAngle
            addBullet(gunX, gunY, finalAngle, true, baseDmg, autoColor)
        } else {
            val totalSpread = if (spread > 0f) spread else 0.1f * totalProjCount
            var currentAngle = fireAngle - totalSpread / 2f
            val step = totalSpread / (totalProjCount - 1).coerceAtLeast(1)
            for (i in 0 until totalProjCount) {
                addBullet(gunX, gunY, currentAngle, true, baseDmg, autoColor)
                currentAngle += step
            }
        }
        
        addMuzzleFlash(gunX, gunY, fireAngle)
        triggerHaptic?.invoke()
    }

    private fun addBullet(x: Float, y: Float, angle: Float, isPlayer: Boolean, damage: Float, color: Color) {
        val b = bulletPool[bulletPoolIndex]
        
        // Recycling oldest bullet cleanly when full
        b.x = x
        b.y = y
        val speed = BULLET_SPEED * (if (isPlayer) upgBulletSpeedMod else 1f)
        b.vx = cos(angle) * speed
        b.vy = sin(angle) * speed
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

    private fun triggerBossSpecialAttack(enemy: Enemy, targetX: Float, targetY: Float, isPhase1: Boolean, isPhase2: Boolean, isRage: Boolean) {
        // Determine pattern based on current phase bounds
        val nextPattern = if (isPhase1) {
            0 // Only Nova
        } else if (isPhase2) {
            if (Random.nextBoolean()) 1 else 2 // Charge or Spiral
        } else {
            Random.nextInt(1, 4) // Charge, Spiral, or Summon
        }
        
        enemy.bossAttackPattern = nextPattern
        
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
                    
                    val common = getPooledEnemy(EnemyType.SOLDIER)
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
                    val isCrit = Random.nextFloat() < player.critChance
                    val baseDamage = (b.damage * player.damageMod) + (Random.nextFloat() * 4f - 2f)
                    val finalDmg = if (isCrit) baseDamage * 1.8f else baseDamage
                    
                    if (upgExplosive) {
                        for (other in enemies) {
                            if (other != hitEnemy && other.hp > 0f && dist(b.x, b.y, other.x, other.y) < 120f) {
                                other.takeDamage(finalDmg * 0.4f)
                            }
                        }
                        createExplosionParticles(b.x, b.y, Color.Yellow, 12)
                    }

                    if (upgChainLightning) {
                        var closest: Enemy? = null
                        var closestDist = Float.MAX_VALUE
                        for (other in enemies) {
                            if (other != hitEnemy && other.hp > 0f) {
                                val d = dist(hitEnemy.x, hitEnemy.y, other.x, other.y)
                                if (d < 250f && d < closestDist) {
                                    closest = other
                                    closestDist = d
                                }
                            }
                        }
                        if (closest != null) {
                            closest.takeDamage(finalDmg * 0.6f)
                            addParticle(b.x, b.y, (closest.x - b.x)*2f, (closest.y - b.y)*2f, Color.Cyan, 8f, 0.2f)
                        }
                    }
                    
                    val actualDamage = if (upgIncendiary) finalDmg * 1.25f else finalDmg

                    if (hitEnemy.takeDamage(actualDamage)) {
                        createExplosionParticles(b.x, b.y, Color.Cyan, 8)
                        val textCol = if (isCrit) Color(0xFFFFDE11) else Color.Cyan
                        spawnText(b.x, b.y - 20f, "${actualDamage.toInt()}", textCol, isCrit)
                        score += (actualDamage * 1.5).toInt()

                        if (hitEnemy.hp <= 0f) {
                            hitStopTimer = 0.12f // Large hit stop on kill
                        } else {
                            hitStopTimer = 0.04f // Small hit stop on hit
                        }
                    }
                }
            } else {
                // Enemy shoots Player
                if (dist(b.x, b.y, player.x, player.y) < player.radius + 10f) {
                    b.isActive = false
                    if (!player.isDashing) {
                        val baseDamage = b.damage + (Random.nextFloat() * 2f - 1f)
                        if (hurtPlayer(baseDamage)) {
                            createExplosionParticles(b.x, b.y, Color.Red, 12)
                            spawnText(b.x, b.y - 20f, "-${baseDamage.toInt()}", Color.Red, false)
                            triggerHaptic?.invoke()
                            screenShakeAmount = 9f
                            hitStopTimer = 0.08f // Player took damage
                        }
                    }
                }
            }
        }
    }

    // --- GAME WAVE SYSTEM CODES ---

    private fun checkStageProgression(dt: Float) {
        if (state == GameState.IN_CINEMATIC) return

        val livingMajorEnemies = enemies.count { it.hp > 0f }
        
        if (currentPhase == 0) {
            spawnUpToCap()
            if (stageEnemiesKilled >= stageTotalEnemies && livingMajorEnemies == 0) {
                currentPhase = 1
                bossWarningTimer = 3f
                android.util.Log.d("RogueMind", "BOSS_WARNING_START")
            }
        } else if (currentPhase == 1) {
            bossWarningTimer -= dt
            if (bossWarningTimer <= 0f) {
                android.util.Log.d("RogueMind", "BOSS_WARNING_END")
                currentPhase = 2
                triggerBossIntroCinematic(currentStage)
            }
        } else if (currentPhase == 2) {
            if (!supportSoldiersSpawned && supportSoldiersTimer > 0f) {
                supportSoldiersTimer -= dt
                if (supportSoldiersTimer <= 0f) {
                    spawnSupportSoldiers()
                }
            }

            if (livingMajorEnemies == 0 && supportSoldiersSpawned) {
                waveProgressDelay += dt
                if (waveProgressDelay > 3.0f) {
                    waveProgressDelay = 0f
                    
                    // Stage Clear
                    credits += 100 + (currentStage * 25)
                    if (currentStage >= highestStage) {
                        highestStage = currentStage + 1
                    }
                    unlockedShotgun = highestStage >= 2
                    unlockedSmg = highestStage >= 5
                    unlockedAssaultRifle = highestStage >= 10
                    unlockedLaserRifle = highestStage >= 15
                    unlockedPlasmaCannon = highestStage >= 25
                    unlockedRailgun = highestStage >= 40
                    savePersistence()
                    
                    if (currentStage >= 50) {
                        totalPlayTime += gameTime
                        state = GameState.GAME_WON
                    } else {
                        state = GameState.STAGE_CLEAR
                    }
                }
            }
        }
    }

    fun applyLevelUpUpgrade(upgrade: UpgradeType) {
        when(upgrade) {
            UpgradeType.RAPID_FIRE -> upgFireRateMod += 0.15f
            UpgradeType.DAMAGE_CORE -> upgDamageMod += 0.20f
            UpgradeType.ACCELERATOR -> upgBulletSpeedMod += 0.15f
            UpgradeType.PROJECTILE_EX -> upgExtraProjectiles += 1
            UpgradeType.PRECISION -> upgCritChance += 0.10f
            UpgradeType.CRIT_CORE -> upgCritDamage += 0.50f
            UpgradeType.REINFORCED_ARMOR -> {
                upgMaxHp += 25f
                player.maxHp += 25f
                player.hp += 25f
            }
            UpgradeType.CYBER_LEGS -> upgSpeedMod += 0.10f
            UpgradeType.DASH_CAP -> upgDashCooldown += 0.45f
            UpgradeType.XP_SCANNER -> upgXpRadiusMod += 0.20f
            UpgradeType.NANO_REPAIR -> upgNanoRepair = true
            UpgradeType.SHOCKWAVE -> upgShockwave = true
            UpgradeType.INCENDIARY -> upgIncendiary = true
            UpgradeType.CHAIN_LIGHTNING -> upgChainLightning = true
            UpgradeType.EXPLOSIVE -> upgExplosive = true
            UpgradeType.AUTO_SHIELD -> upgAutoShield = true
        }
        
        state = GameState.PLAYING
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
