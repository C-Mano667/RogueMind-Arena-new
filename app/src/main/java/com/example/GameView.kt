package com.example

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.*

@Composable
fun GameView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }

    // Instantiate game engine
    val gameEngine = remember { GameEngine(context) }

    // Load Sprites
    var loadError by remember { mutableStateOf<String?>(null) }

    val (rawPlayer, rawSoldier, rawBoss, arenaSprite) = remember(context) {
        fun loadSafe(resId: Int): ImageBitmap {
            return try {
                val options = android.graphics.BitmapFactory.Options()
                // Prevent scaling memory issues
                options.inScaled = false 
                var bmp = android.graphics.BitmapFactory.decodeResource(context.resources, resId, options)
                if (bmp == null) {
                    android.util.Log.e("RogueMind", "BitmapFactory returned null for $resId")
                    ImageBitmap(1, 1)
                } else {
                    bmp.asImageBitmap()
                }
            } catch (e: Throwable) {
                android.util.Log.e("RogueMind", "Failed to load resource $resId: ${e.message}", e)
                ImageBitmap(1, 1)
            }
        }
        listOf(
            loadSafe(R.drawable.image_2),
            loadSafe(R.drawable.soldier3),
            loadSafe(R.drawable.boss3),
            loadSafe(R.drawable.arena3)
        )
    }

    val (playerSprite, soldierSprite, bossSprite) = remember(rawPlayer, rawSoldier, rawBoss) {
        fun process(image: ImageBitmap): ImageBitmap {
            try {
                if (image.width <= 1 && image.height <= 1) return image
                val bmp = image.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                val w = bmp.width
                val h = bmp.height
                
                // Safety check: skip processing if image is absurdly large to prevent OOM
                if (w * h > 8000000) {
                    android.util.Log.w("RogueMind", "Image too large to process safely: ${w}x${h}")
                    return image
                }
                
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                var minX = w; var minY = h; var maxX = 0; var maxY = 0
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = android.graphics.Color.red(c)
                    val g = android.graphics.Color.green(c)
                    val b = android.graphics.Color.blue(c)
                    val a = android.graphics.Color.alpha(c)
                    if (r > 190 && g > 190 && b > 190 && kotlin.math.abs(r - g) < 20 && kotlin.math.abs(g - b) < 20) {
                        pixels[i] = android.graphics.Color.TRANSPARENT
                    } else if (a > 10) {
                        val x = i % w
                        val y = i / w
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                }
                if (minX > maxX || minY > maxY) {
                    minX = 0; minY = 0; maxX = w - 1; maxY = h - 1
                }
                bmp.setPixels(pixels, 0, w, 0, 0, w, h)
                val nW = maxX - minX + 1
                val nH = maxY - minY + 1
                return android.graphics.Bitmap.createBitmap(bmp, minX, minY, nW, nH).asImageBitmap()
            } catch (e: Exception) {
                android.util.Log.e("RogueMind", "Error processing sprite: ${e.message}", e)
                return image
            }
        }
        try {
            listOf(process(rawPlayer), process(rawSoldier), process(rawBoss))
        } catch (e: Exception) {
            android.util.Log.e("RogueMind", "Fatal error processing sprites: ${e.message}", e)
            loadError = "Failed to load sprites. Check logcat."
            listOf(rawPlayer, rawSoldier, rawBoss)
        }
    }


    // Link engine haptics directly to Android OS Vibrator
    LaunchedEffect(gameEngine) {
        gameEngine.triggerHaptic = {
            try {
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        vibrator.vibrate(28)
                    }
                }
            } catch (e: Exception) {
                // Squelch errors if emulator doesn't support
            }
        }
    }

    // --- GAME RENDERING STATE REFRESHERS ---
    var isPlaying by remember { mutableStateOf(false) }
    var engineState by remember { mutableStateOf(GameState.MAIN_MENU) }
    var currentStage by remember { mutableStateOf(1) }
    var score by remember { mutableStateOf(0) }
    var enemiesRemaining by remember { mutableStateOf(0) }
    var playerHp by remember { mutableStateOf(100f) }
    var playerMaxHp by remember { mutableStateOf(100f) }
    var playerShield by remember { mutableStateOf(0f) }
    var dashCooldownRemaining by remember { mutableStateOf(0f) }
    var isDashCooldownActive by remember { mutableStateOf(false) }
    var engineLevel by remember { mutableStateOf(1) }
    var engineXp by remember { mutableStateOf(0) }
    var engineRequiredXp by remember { mutableStateOf(100) }
    var credits by remember { mutableStateOf(0) }
    var bossHp by remember { mutableStateOf(0f) }
    var bossMaxHp by remember { mutableStateOf(0f) }
    
    var stageTotalEnemies by remember { mutableStateOf(0) }
    var stageEnemiesKilled by remember { mutableStateOf(0) }
    var currentPhase by remember { mutableStateOf(0) }
    var bossWarningTimerInt by remember { mutableStateOf(0) }

    // Buff Timers
    var buffDamageTimerInt by remember { mutableStateOf(0) }
    var buffRapidFireTimerInt by remember { mutableStateOf(0) }
    var buffSpeedTimerInt by remember { mutableStateOf(0) }
    var buffMagnetTimerInt by remember { mutableStateOf(0) }
    var buffBerserkTimerInt by remember { mutableStateOf(0) }
    var buffOverdriveTimerInt by remember { mutableStateOf(0) }
    var buffFreezeTimerInt by remember { mutableStateOf(0) }

    // Camera variables
    var camX by remember { mutableStateOf(1200f) }
    var camY by remember { mutableStateOf(1200f) }

    // --- MULTITOUCH VIRTUAL JOYSTICK STATE ---
    var leftJoystickOffset by remember { mutableStateOf(Offset.Zero) }
    var rightJoystickOffset by remember { mutableStateOf(Offset.Zero) }
    var isLeftActive by remember { mutableStateOf(false) }
    var isRightActive by remember { mutableStateOf(false) }

    val joystickRadiusPx = with(density) { 62.dp.toPx() }

    // --- HIGH-PERFORMANCE SCREENLOOP CORE TICK @ 60 FPS ---
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastTime = System.nanoTime()
        while (isPlaying) {
            awaitFrame()
            val now = System.nanoTime()
            var dt = (now - lastTime) / 1_000_000_000f
            lastTime = now

            // Cap elapsed tick time to prevent physics clipping
            if (dt > 0.08f) dt = 0.08f

            // 1. Calculate and update joystick velocities
            if (isLeftActive) {
                val dx = leftJoystickOffset.x / joystickRadiusPx
                val dy = leftJoystickOffset.y / joystickRadiusPx
                gameEngine.movePlayer(dx, dy, dt)
            } else {
                gameEngine.movePlayer(0f, 0f, dt)
            }

            if (isRightActive) {
                val dx = rightJoystickOffset.x / joystickRadiusPx
                val dy = rightJoystickOffset.y / joystickRadiusPx
                // Auto fires if joystick pulled beyond deadzone
                if (sqrt(dx * dx + dy * dy) > 0.22f) {
                    gameEngine.performPlayerShoot(dx, dy)
                }
            }

            // 2. Step Engine Update Simulation
            gameEngine.update(dt)

            // 3. Smooth Camera tracking with 100x60 deadzone and 0.15 lerp
            val targetX = gameEngine.player.x
            val targetY = gameEngine.player.y

            val cdx = targetX - camX
            val cdy = targetY - camY

            val deadZoneHalfW = 50f
            val deadZoneHalfH = 30f

            if (abs(cdx) > deadZoneHalfW) {
                val sign = if (cdx > 0) 1f else -1f
                camX += (cdx - sign * deadZoneHalfW) * 0.15f
            }
            if (abs(cdy) > deadZoneHalfH) {
                val sign = if (cdy > 0) 1f else -1f
                camY += (cdy - sign * deadZoneHalfH) * 0.15f
            }

            // Lock camera firmly to world boundary walls
            camX = camX.coerceIn(200f, 2200f)
            camY = camY.coerceIn(200f, 2200f)

            // 4. Update local reactive bounds details
            engineState = gameEngine.state
            currentStage = gameEngine.currentStage
            score = gameEngine.score
            enemiesRemaining = gameEngine.enemies.count { it.hp > 0f }
            playerHp = gameEngine.player.hp
            playerMaxHp = gameEngine.player.maxHp
            playerShield = gameEngine.playerShield
            dashCooldownRemaining = gameEngine.player.dashCooldown
            isDashCooldownActive = gameEngine.player.dashCooldown > 0f
            engineLevel = gameEngine.level
            engineXp = gameEngine.xp
            engineRequiredXp = gameEngine.requiredXp
            credits = gameEngine.credits
            
            // Boss HP sync
            val boss = gameEngine.enemies.find { it.type == EnemyType.BOSS && it.hp > 0f }
            if (boss != null) {
                bossHp = boss.hp
                bossMaxHp = boss.maxHp
            } else {
                bossHp = 0f
            }

            stageTotalEnemies = gameEngine.stageTotalEnemies
            stageEnemiesKilled = gameEngine.stageEnemiesKilled
            currentPhase = gameEngine.currentPhase
            bossWarningTimerInt = gameEngine.bossWarningTimer.toInt()

            // Buff timers sync
            buffDamageTimerInt = gameEngine.buffDamageTimer.toInt()
            buffRapidFireTimerInt = gameEngine.buffRapidFireTimer.toInt()
            buffSpeedTimerInt = gameEngine.buffSpeedTimer.toInt()
            buffMagnetTimerInt = gameEngine.buffMagnetTimer.toInt()
            buffBerserkTimerInt = gameEngine.buffBerserkTimer.toInt()
            buffOverdriveTimerInt = gameEngine.buffOverdriveTimer.toInt()
            buffFreezeTimerInt = gameEngine.freezeTimer.toInt()

            // Stop loop if game states exit playing
            if (engineState == GameState.GAME_OVER || engineState == GameState.GAME_WON) {
                isPlaying = false
            }
        }
    }

    // Capture initial trigger to start game
    LaunchedEffect(isPlaying) {
        if (!isPlaying && engineState == GameState.PLAYING) {
            isPlaying = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030303))
    ) {

        // --- CYBER ARENA MAIN GAMEPLAY CANVAS ---
        if (engineState == GameState.PLAYING || engineState == GameState.IN_CINEMATIC || engineState == GameState.GAME_OVER || engineState == GameState.GAME_WON) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("game_arena_canvas")
            ) {
                val screenW = size.width
                val screenH = size.height
                val screenCenterX = screenW / 2f
                val screenCenterY = screenH / 2f

                // Apply dynamic screen震 screen shake displacement
                val shakeAmt = gameEngine.screenShakeAmount
                val shakeX = if (shakeAmt > 0f) (Math.random().toFloat() * 2f - 1f) * shakeAmt else 0f
                val shakeY = if (shakeAmt > 0f) (Math.random().toFloat() * 2f - 1f) * shakeAmt else 0f

                // --- CAMERA TRANSFORMATIONS ---
                // Zoom 1.40, Follow lerp 0.15, pivot centered
                withTransform({
                    translate(left = screenCenterX + shakeX, top = screenCenterY + shakeY)
                    scale(scaleX = 1.40f, scaleY = 1.40f, pivot = Offset.Zero)
                    translate(left = -camX, top = -camY)
                }) {

                    // 1. Draw Arena Floor
                    drawImage(
                        image = arenaSprite,
                        dstOffset = IntOffset(100, 100),
                        dstSize = IntSize(2200, 2200)
                    )

                    // 2. Draw exactly 16 obstacles (120x120) arranged in a ring at radius 900
                    for (obs in gameEngine.obstacles) {
                        // Drawing thick black structure
                        drawRect(
                            color = Color(0xFF101216),
                            topLeft = Offset(obs.left, obs.top),
                            size = Size(obs.size, obs.size)
                        )
                        // Radiant border neon pink/red
                        drawRect(
                            color = Color(0xFF00FFCC),
                            topLeft = Offset(obs.left, obs.top),
                            size = Size(obs.size, obs.size),
                            style = Stroke(width = 4f)
                        )
                        // Cyber hazard stripes inside
                        for (j in 0..4) {
                            val startOffset = obs.left + j * 24f
                            drawLine(
                                color = Color(0xFF0099FF).copy(alpha = 0.5f),
                                start = Offset(startOffset, obs.top + 5f),
                                end = Offset(startOffset - 20f, obs.bottom - 5f),
                                strokeWidth = 3f
                            )
                        }
                    }

                    // 3. Draw Shadows for everything
                    for (obs in gameEngine.obstacles) {
                        drawOval(color = Color.Black.copy(0.6f), topLeft = Offset(obs.left - 20f, obs.bottom - 40f), size = Size(obs.size + 40f, 60f))
                    }
                    val activePl = gameEngine.player
                    if (activePl.hp > 0f) {
                        drawCircle(brush = androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Cyan.copy(0.6f), Color.Transparent), center = Offset(activePl.x, activePl.y), radius = 70f), radius = 70f, center = Offset(activePl.x, activePl.y))
                        drawOval(color = Color.Black.copy(0.5f), topLeft = Offset(activePl.x - 25f, activePl.y + 15f), size = Size(50f, 20f))
                    }
                    for (enemy in gameEngine.enemies) {
                        val shW = if (enemy.type == EnemyType.BOSS) 140f else if (enemy.type == EnemyType.ELITE) 50f else 40f
                        val shH = if (enemy.type == EnemyType.BOSS) 60f else 20f
                        
                        val glowColor = if (enemy.type == EnemyType.BOSS) Color(0xFFFF8800) else if (enemy.type == EnemyType.ELITE) Color.Magenta else Color.Red
                        val glowRad = if (enemy.type == EnemyType.BOSS) 180f else if (enemy.type == EnemyType.ELITE) 80f else 60f
                        drawCircle(brush = androidx.compose.ui.graphics.Brush.radialGradient(listOf(glowColor.copy(0.5f), Color.Transparent), center = Offset(enemy.x, enemy.y), radius = glowRad), radius = glowRad, center = Offset(enemy.x, enemy.y))

                        drawOval(color = Color.Black.copy(0.4f), topLeft = Offset(enemy.x - shW/2f, enemy.y + (enemy.radius * 0.8f)), size = Size(shW, shH))
                        
                        // Boss Telegraph
                        if (enemy.type == EnemyType.BOSS && enemy.bossAttackTimer > 2.5f) {
                            val alertLevel = (enemy.bossAttackTimer - 2.5f) / 1.7f
                            val pulse = abs(sin(alertLevel * 15f))
                            drawCircle(color = Color.Red.copy(0.2f + 0.3f * pulse), radius = enemy.radius + 150f * alertLevel, center = Offset(enemy.x, enemy.y))
                            drawCircle(color = Color.Red.copy(0.5f), radius = enemy.radius + 150f * alertLevel, center = Offset(enemy.x, enemy.y), style = Stroke(width = 4f))
                        }
                    }

                    // 4. Draw Dash Afterimages
                    for (ai in gameEngine.getActiveAfterimages()) {
                        drawCircle(
                            color = Color.Cyan.copy(alpha = ai.alpha),
                            radius = gameEngine.player.radius,
                            center = Offset(ai.x, ai.y),
                            style = Stroke(width = 3f)
                        )
                    }

                    // 4. Draw Player (Cyan Soldier)
                    val pl = gameEngine.player
                    if (pl.hp > 0f) {
                        val shakeX = if (pl.state == PlayerAnimState.HURT) sin(pl.stateTimer * 45f) * 5f else 0f
                        val flashFilter = if (pl.hurtTimer > 0f && (pl.stateTimer * 20f).toInt() % 2 == 0) ColorFilter.tint(Color.White, BlendMode.SrcAtop) else null
                        val bobbing = if (pl.state == PlayerAnimState.RUN) abs(sin(pl.stateTimer * 16f)) * 4f else 0f
                        val tilt = if (pl.state == PlayerAnimState.RUN) sin(pl.stateTimer * 12f) * 8f else 0f
                        val recoilOffset = if (pl.state == PlayerAnimState.SHOOT) -cos(pl.angle)*8f*(pl.shootCooldown/0.16f) else 0f
                        val recoilOffsetY = if (pl.state == PlayerAnimState.SHOOT) -sin(pl.angle)*8f*(pl.shootCooldown/0.16f) else 0f

                        withTransform({
                            translate(shakeX + recoilOffset, recoilOffsetY)
                            rotate(degrees = pl.angle * 180f / Math.PI.toFloat() + tilt, pivot = Offset(pl.x, pl.y))
                        }) {
                            drawImage(
                                image = playerSprite,
                                dstOffset = IntOffset((pl.x - 32f).toInt(), (pl.y - 32f - bobbing).toInt()),
                                dstSize = IntSize(64, 64),
                                colorFilter = flashFilter
                            )
                        }

                        // Thruster particles tail representation
                        if (pl.state == PlayerAnimState.RUN || pl.state == PlayerAnimState.DASH) {
                            val backAngle = pl.angle + PI.toFloat()
                            val flameLength = if (pl.state == PlayerAnimState.DASH) 60f else 32f
                            drawLine(
                                color = Color.Cyan.copy(alpha = 0.7f),
                                start = Offset(pl.x, pl.y),
                                end = Offset(pl.x + cos(backAngle) * flameLength, pl.y + sin(backAngle) * flameLength),
                                strokeWidth = 5f
                            )
                        }
                    } else {
                        // Dead player exploded debris (DEATH)
                        drawCircle(
                            color = Color.Red.copy(alpha = 0.3f),
                            radius = pl.radius + 15f,
                            center = Offset(pl.x, pl.y),
                            style = Stroke(width = 2f)
                        )
                    }

                    // 5. Draw Enemies (Soldier, Elite, Boss)
                    for (enemy in gameEngine.enemies) {
                        val enemyShakeX = if (enemy.hurtTimer > 0f) sin(enemy.stateTimer * 45f) * 4f else 0f
                        val flashFilter = if (enemy.hurtTimer > 0f && (enemy.stateTimer * 16f).toInt() % 2 == 0) ColorFilter.tint(Color.White, BlendMode.SrcAtop) else null

                        // Size configurations
                        val img = if (enemy.type == EnemyType.BOSS) bossSprite else soldierSprite
                        val sz = if (enemy.type == EnemyType.BOSS) 192 else if (enemy.type == EnemyType.ELITE) 72 else 56
                        val off = sz / 2f
                        
                        val speedFactor = if (enemy.type == EnemyType.ELITE) 15f else 10f
                        val bobbing = if (enemy.state == EnemyAnimState.RUN) abs(sin(enemy.stateTimer * speedFactor)) * 3f else 0f
                        val tilt = if (enemy.state == EnemyAnimState.RUN) sin(enemy.stateTimer * speedFactor) * 8f else 0f

                        if (enemy.hp > 0f) {
                            if (enemy.type == EnemyType.ELITE) {
                                drawCircle(color = Color.Magenta.copy(alpha = 0.5f), radius = 48f, center = Offset(enemy.x, enemy.y))
                            }
                            
                            withTransform({
                                translate(enemyShakeX, 0f)
                                rotate(degrees = enemy.angle * 180f / Math.PI.toFloat() + tilt, pivot = Offset(enemy.x, enemy.y))
                            }) {
                                drawImage(
                                    image = img,
                                    dstOffset = IntOffset((enemy.x - off).toInt(), (enemy.y - off - bobbing).toInt()),
                                    dstSize = IntSize(sz, sz),
                                    colorFilter = flashFilter
                                )
                            }
                        } else {
                            // Disintegrating particles death sequences
                            val tRatio = enemy.deathTimer / 0.6f
                            if (tRatio > 0f) {
                                val alpha = tRatio.coerceIn(0f, 1f)
                                withTransform({
                                    rotate(degrees = enemy.angle * 180f / Math.PI.toFloat(), pivot = Offset(enemy.x, enemy.y))
                                }) {
                                    drawImage(
                                        image = img,
                                        dstOffset = IntOffset((enemy.x - off).toInt(), (enemy.y - off).toInt()),
                                        dstSize = IntSize(sz, sz),
                                        alpha = alpha,
                                        colorFilter = ColorFilter.tint(Color.Red, BlendMode.SrcAtop)
                                    )
                                }
                            }
                        }
                    }

                    // 5a. Draw PowerUps
                    for (pu in gameEngine.powerUps) {
                        if (!pu.isActive) continue
                        val pulse = (sin(gameEngine.gameTime * 4f) * 0.5f + 0.5f)
                        val puColor = when(pu.type) {
                            PowerUpType.DAMAGE -> Color.Red
                            PowerUpType.RAPID_FIRE -> Color.Yellow
                            PowerUpType.SPEED -> Color.Cyan
                            PowerUpType.SHIELD -> Color.Blue
                            PowerUpType.HEALTH -> Color.Green
                            PowerUpType.MAGNET -> Color(0xFFFF00FF)
                            PowerUpType.BERSERK -> Color.Red
                            PowerUpType.GOLDEN -> Color(0xFFFFD700)
                            PowerUpType.OVERDRIVE -> Color(0xFF00FFFF)
                            PowerUpType.FREEZE -> Color.Gray
                            PowerUpType.CREDIT -> Color.Yellow
                            PowerUpType.WEAPON_CRATE -> Color(0xFF888888)
                            PowerUpType.NUKE -> Color.Red
                        }
                        // Base ring
                        drawCircle(color = puColor.copy(alpha = 0.3f), radius = 30f + pulse*10f, center = Offset(pu.x, pu.y + 15f))
                        drawCircle(color = puColor, radius = 30f + pulse*10f, center = Offset(pu.x, pu.y + 15f), style = Stroke(width = 3f))
                        
                        val hover = sin(pu.hoverOffset) * 10f
                        // Core item
                        drawRoundRect(
                            color = puColor,
                            topLeft = Offset(pu.x - 12f, pu.y - 12f + hover),
                            size = Size(24f, 24f),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.5f),
                            topLeft = Offset(pu.x - 8f, pu.y - 8f + hover),
                            size = Size(16f, 16f),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }

                    // 5b. Draw XP Orbs
                    for (orb in gameEngine.xpOrbs) {
                        if (!orb.isActive) continue
                        val orbColor = if (orb.amount >= 40) Color(0xFFFF00AA) else Color(0xFF00FFCC)
                        val orbRadius = if (orb.amount >= 500) 14f else if (orb.amount >= 40) 8f else 5f
                        val pulse = (sin(gameEngine.gameTime * 8f + orb.x) * 4f)
                        drawCircle(color = orbColor.copy(alpha = 0.5f), radius = orbRadius + pulse, center = Offset(orb.x, orb.y))
                        drawCircle(color = Color.White, radius = orbRadius * 0.5f, center = Offset(orb.x, orb.y))
                    }

                    // 6. Draw Bullets Tracers
                    // Bullet pool = 150, Bullet speed = 900f
                    for (b in gameEngine.getActiveBullets()) {
                        // Drawing high speed neon core
                        drawCircle(
                            color = Color.White,
                            radius = 4.5f,
                            center = Offset(b.x, b.y)
                        )
                        // Fading tracer tail line back along speed vx, vy vector
                        val trailLen = 0.045f // Length of trail in seconds
                        drawLine(
                            color = b.color,
                            start = Offset(b.x, b.y),
                            end = Offset(b.x - b.vx * trailLen, b.y - b.vy * trailLen),
                            strokeWidth = 4f
                        )
                    }

                    // 7. Draw Muzzle Flashes
                    for (m in gameEngine.getActiveMuzzleFlashes()) {
                        val progress = m.life / m.maxLife
                        val flashSize = 34f * (1f - progress)
                        withTransform({
                            rotate(degrees = m.angle * 180f / Math.PI.toFloat(), pivot = Offset(m.x, m.y))
                        }) {
                            drawArc(
                                color = Color(0xFFFFD200).copy(alpha = progress),
                                startAngle = -40f,
                                sweepAngle = 80f,
                                useCenter = true,
                                topLeft = Offset(m.x - flashSize, m.y - flashSize),
                                size = Size(flashSize * 2f, flashSize * 2f)
                            )
                        }
                    }

                    // 8. Draw Particle sparks
                    for (p in gameEngine.getActiveParticles()) {
                        drawCircle(
                            color = p.color.copy(alpha = p.alpha),
                            radius = p.size,
                            center = Offset(p.x, p.y)
                        )
                    }
                }

                // --- SCREEN SPACE DRAWINGS (NO CAMERA TRANSFORMATIONS) ---
                
                // Vignette Screen-Space effect
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                        center = Offset(screenCenterX, screenCenterY),
                        radius = screenW * 0.75f
                    ),
                    size = Size(screenW, screenH)
                )

                // Scanline simple overlay effect (CRT)
                for (i in 0..(screenH / 6).toInt()) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.10f),
                        start = Offset(0f, i * 6f),
                        end = Offset(screenW, i * 6f),
                        strokeWidth = 1.5f
                    )
                }

                // 9. Draw Floating Combat Text labels
                for (f in gameEngine.getActiveFloatingTexts()) {
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = Paint().apply {
                            color = android.graphics.Color.argb(
                                (f.alpha * 255).toInt().coerceIn(0, 255),
                                (f.color.red * 255).toInt(),
                                (f.color.green * 255).toInt(),
                                (f.color.blue * 255).toInt()
                            )
                            textSize = if (f.isCritical) with(density) { 20.sp.toPx() } else with(density) { 14.sp.toPx() }
                            typeface = Typeface.create(Typeface.MONOSPACE, if (f.isCritical) Typeface.BOLD else Typeface.NORMAL)
                            textAlign = Paint.Align.CENTER
                            if (f.isCritical) {
                                setShadowLayer(10f, 0f, 0f, android.graphics.Color.YELLOW)
                            }
                        }

                        // Project world coordinate (f.x, f.y) to screen coordinates
                        val sx = screenCenterX + (f.x - camX) * 1.40f + shakeX
                        val sy = screenCenterY + (f.y - camY) * 1.40f + shakeY
                        drawText(f.text, sx, sy, paint)
                    }
                }

                // 10. Off-screen Enemy Indicators
                // Arrow targets at bounds border for enemies not inside current scale field of view.
                // Screen viewport size at Zoom 1.40 is roughly [camX - screenW/2.8, camX + screenW/2.8]
                val viewHalfW = screenCenterX / 1.40f
                val viewHalfH = screenCenterY / 1.40f
                val viewBounds = Rect(camX - viewHalfW, camY - viewHalfH, camX + viewHalfW, camY + viewHalfH)

                for (enemy in gameEngine.enemies) {
                    if (enemy.hp <= 0f) continue
                    if (!viewBounds.contains(Offset(enemy.x, enemy.y))) {
                        // Project direction vector from camera center to off-screen target
                        val dx = enemy.x - camX
                        val dy = enemy.y - camY
                        val ang = atan2(dy, dx)

                        // Boundary padding safe boundaries on actual device screen
                        val borderPaddingX = with(density) { 26.dp.toPx() }
                        val borderPaddingY = with(density) { 26.dp.toPx() }
                        val safeW = screenCenterX - borderPaddingX
                        val safeH = screenCenterY - borderPaddingY

                        // Find intersection on screen margin box
                        val uX = cos(ang)
                        val uY = sin(ang)

                        var tX = screenCenterX + uX * safeW
                        var tY = screenCenterY + uY * safeH

                        // Adjust to preserve aspect boundaries
                        val ratioW = safeW / abs(dx)
                        val ratioH = safeH / abs(dy)
                        val ratio = min(ratioW, ratioH)
                        tX = screenCenterX + dx * ratio
                        tY = screenCenterY + dy * ratio

                        // Determine render indicator colors: Boss (Flashing Golden), Elites (Orange), Soldiers (Red)
                        val color = if (enemy.type == EnemyType.BOSS) Color(0xFFFFDE11) else {
                            if (enemy.type == EnemyType.ELITE) Color(0xFFFF5500) else Color(0xFFFF3333)
                        }

                        // Draw hovering pointer triangle arrow facing outward
                        withTransform({
                            translate(tX + shakeX, tY + shakeY)
                            rotate(degrees = ang * 180f / Math.PI.toFloat(), pivot = Offset.Zero)
                        }) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(0f, 0f)
                                lineTo(-18f, -10f)
                                lineTo(-18f, 10f)
                                close()
                            }
                            drawPath(path = path, color = color)

                            if (enemy.type == EnemyType.BOSS) {
                                // Draw pulsating crown / double loop for boss
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.8f),
                                    radius = 8f,
                                    center = Offset(-30f, 0f)
                                )
                            }
                        }
                    }
                }

                // 11. Circular Minimap Overlay
                // Centered top-right, drawing scaled obstacles, player, and boss
                val mmRad = with(density) { 55.dp.toPx() }
                val mmCenterX = screenW - mmRad - with(density) { 16.dp.toPx() }
                val mmCenterY = mmRad + with(density) { 16.dp.toPx() }

                // Outer border indicator
                drawCircle(
                    color = Color(0xFF020408).copy(alpha = 0.85f),
                    radius = mmRad,
                    center = Offset(mmCenterX, mmCenterY)
                )
                drawCircle(
                    color = Color(0xFF00FFCC),
                    radius = mmRad,
                    center = Offset(mmCenterX, mmCenterY),
                    style = Stroke(width = 3f)
                )

                // Render scaled mini map features
                // Scale factor: minimap fits 2200px world arena into mmRad*1.8 size
                val mmScale = (mmRad * 1.8f) / 2200f

                // Obstacles rings drawing
                for (obs in gameEngine.obstacles) {
                    val mx = mmCenterX + (obs.x - 1200f) * mmScale
                    val my = mmCenterY + (obs.y - 1200f) * mmScale
                    val mSize = obs.size * mmScale
                    drawRect(
                        color = Color.Gray.copy(alpha = 0.6f),
                        topLeft = Offset(mx - mSize/2f, my - mSize/2f),
                        size = Size(mSize, mSize)
                    )
                }

                // XP Orbs
                for (orb in gameEngine.xpOrbs) {
                    if (!orb.isActive) continue
                    val mx = mmCenterX + (orb.x - 1200f) * mmScale
                    val my = mmCenterY + (orb.y - 1200f) * mmScale
                    drawCircle(color = Color(0xFFAA00FF), radius = 1.5f, center = Offset(mx, my))
                }
                
                // Powerups
                val puPulse = (sin(gameEngine.gameTime * 6f) * 1.5f + 2f)
                for (pu in gameEngine.powerUps) {
                    if (!pu.isActive) continue
                    val mx = mmCenterX + (pu.x - 1200f) * mmScale
                    val my = mmCenterY + (pu.y - 1200f) * mmScale
                    drawCircle(color = Color.White, radius = puPulse, center = Offset(mx, my))
                }

                // Enemies indicator on map
                for (enemy in gameEngine.enemies) {
                    if (enemy.hp <= 0f) continue
                    val mx = mmCenterX + (enemy.x - 1200f) * mmScale
                    val my = mmCenterY + (enemy.y - 1200f) * mmScale
                    val dotColor = if (enemy.type == EnemyType.BOSS) Color(0xFFFFDE11) else {
                        if (enemy.type == EnemyType.ELITE) Color(0xFFFF5500) else Color(0xFFFF4444)
                    }
                    val dotRad = if (enemy.type == EnemyType.BOSS) 6f else 3f
                    drawCircle(
                        color = dotColor,
                        radius = dotRad,
                        center = Offset(mx, my)
                    )
                }

                // Player cursor on map
                val pX = mmCenterX + (gameEngine.player.x - 1200f) * mmScale
                val pY = mmCenterY + (gameEngine.player.y - 1200f) * mmScale
                drawCircle(
                    color = Color.Cyan,
                    radius = 4.5f,
                    center = Offset(pX, pY)
                )
                drawCircle(
                    color = Color.White,
                    radius = 6.5f,
                    center = Offset(pX, pY),
                    style = Stroke(width = 1.5f)
                )
            }
        }

        // --- BOTTOM SCREEN CONTROLS OVERLAYS ---
        if (engineState == GameState.PLAYING || engineState == GameState.IN_CINEMATIC) {
            
            // --- TOP PANEL HUD ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Game Statistics
                Column(modifier = Modifier.width(200.dp)) {
                    Text(
                        text = "STAGE $currentStage",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Red, Offset(0f, 0f), 8f))
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "QUOTA: $stageEnemiesKilled / $stageTotalEnemies  |  ALIVE: $enemiesRemaining",
                        color = Color.Yellow,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        text = "CREDITS: $credits  |  LEVEL: $engineLevel",
                        color = Color.Green,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // XP Bar
                    val xpRatio = (engineXp.toFloat() / engineRequiredXp.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF22252C))
                            .border(1.dp, Color(0x6600FFFF), RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(xpRatio)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF00FFCC), Color(0xFFAA00FF))
                                    )
                                )
                        )
                    }
                    Text(text = "XP $engineXp / $engineRequiredXp", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                }

                // Player Core Vital health segment
                Column(
                    modifier = Modifier.width(180.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val hpRatio = (playerHp / playerMaxHp).coerceIn(0f, 1f)
                    Text(
                        text = "${playerHp.toInt()} / ${playerMaxHp.toInt()} HP",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // Custom neon bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFF22252C))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(hpRatio)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF00FFCC), Color(0xFFFF0077))
                                    )
                                )
                        )
                    }
                    
                    if (playerShield > 0f) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF22252C))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth((playerShield / 100f).coerceIn(0f, 1f))
                                    .background(Color(0xFF44AAFF))
                            )
                        }
                    }

                    if (bossHp > 0f) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "BOSS: ${bossHp.toInt()} / ${bossMaxHp.toInt()}",
                            color = Color(0xFFFF4444),
                            fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF22252C))
                                .border(1.dp, Color(0xFFFF4444), RoundedCornerShape(4.dp))
                        ) {
                            val bossRatio = (bossHp / bossMaxHp).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(bossRatio)
                                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF8800), Color(0xFFFF0055))))
                            )
                        }
                    }
                }
                
                Column(modifier = Modifier.width(135.dp)) {
                    if (buffDamageTimerInt > 0) Text("DAMAGE UP: ${buffDamageTimerInt}s", color=Color.Red, fontSize=10.sp)
                    if (buffRapidFireTimerInt > 0) Text("RAPID FIRE: ${buffRapidFireTimerInt}s", color=Color.Yellow, fontSize=10.sp)
                    if (buffSpeedTimerInt > 0) Text("SPEED UP: ${buffSpeedTimerInt}s", color=Color.Cyan, fontSize=10.sp)
                    if (buffMagnetTimerInt > 0) Text("MAGNET: ${buffMagnetTimerInt}s", color=Color.Magenta, fontSize=10.sp)
                    if (buffBerserkTimerInt > 0) Text("BERSERK: ${buffBerserkTimerInt}s", color=Color.Red, fontSize=10.sp)
                    if (buffOverdriveTimerInt > 0) Text("OVERDRIVE: ${buffOverdriveTimerInt}s", color=Color(0xFF00FFFF), fontSize=10.sp)
                    if (buffFreezeTimerInt > 0) Text("FREEZE: ${buffFreezeTimerInt}s", color=Color.Gray, fontSize=10.sp)
                }
            }

            // --- BOTTOM TOUCH INTERACTIVE ZONE ---
            if (engineState == GameState.PLAYING) {
                if (currentPhase == 1 && bossWarningTimerInt > 0) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "BOSS WARNING",
                            color = Color.Red,
                            fontSize = 32.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 0f), 10f))
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.55f)
                        .padding(horizontal = 48.dp, vertical = 16.dp)
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {

                    // A. LEFT JOYSTICK (Movement)
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape)
                            .background(Color(0x22111522))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        isLeftActive = true
                                        leftJoystickOffset = Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val newOffset = leftJoystickOffset + dragAmount
                                        val len = newOffset.getDistance()
                                        leftJoystickOffset = if (len > joystickRadiusPx) {
                                            newOffset * (joystickRadiusPx / len)
                                        } else {
                                            newOffset
                                        }
                                    },
                                    onDragEnd = {
                                        isLeftActive = false
                                        leftJoystickOffset = Offset.Zero
                                    }
                                )
                            }
                            .testTag("left_joysticks"),
                        contentAlignment = Alignment.Center
                    ) {
                        // Joystick Outer Bound guide ring
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x1102FFF3))
                                .align(Alignment.Center)
                        )
                        // Dynamic sliding stick puck
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { leftJoystickOffset.x.toDp() },
                                    y = with(density) { leftJoystickOffset.y.toDp() }
                                )
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color.White, Color.Cyan)
                                    )
                                )
                        )
                    }

                    // B. CENTER ACTION SHIELD / DASH TRIGGERS
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .align(Alignment.Bottom)
                            .padding(bottom = 8.dp)
                    ) {
                        // Curved Cool Down gauge representing 3s countdown arc
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val progress = if (dashCooldownRemaining > 0f) dashCooldownRemaining / GameEngine.DASH_COOLDOWN else 0f
                            drawCircle(
                                color = Color(0x3300FFCC),
                                style = Stroke(width = 4f)
                            )
                            if (progress > 0f) {
                                drawArc(
                                    color = Color(0xFFFF0066),
                                    startAngle = -90f,
                                    sweepAngle = 360f * progress,
                                    useCenter = false,
                                    style = Stroke(width = 6f)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(if (isDashCooldownActive) Color(0xFF15171C) else Color(0xFFFF0055))
                                .clickable {
                                    if (!isDashCooldownActive) {
                                        // Trigger Dash utilizing current movement stick directions
                                        gameEngine.performDash(leftJoystickOffset.x, leftJoystickOffset.y)
                                    }
                                }
                                .testTag("dash_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "DASH",
                                color = if (isDashCooldownActive) Color.Gray else Color.White,
                                fontSize = 12.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Black
                            )
                        }
                    }

                    // C. RIGHT JOYSTICK (Aim & Auto fire)
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape)
                            .background(Color(0x22111522))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        isRightActive = true
                                        rightJoystickOffset = Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val newOffset = rightJoystickOffset + dragAmount
                                        val len = newOffset.getDistance()
                                        rightJoystickOffset = if (len > joystickRadiusPx) {
                                            newOffset * (joystickRadiusPx / len)
                                        } else {
                                            newOffset
                                        }
                                    },
                                    onDragEnd = {
                                        isRightActive = false
                                        rightJoystickOffset = Offset.Zero
                                    }
                                )
                            }
                            .testTag("right_joysticks"),
                        contentAlignment = Alignment.Center
                    ) {
                        // Joystick ring
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x11FF0288))
                                .align(Alignment.Center)
                        )
                        // Dynamic sliding stick puck
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { rightJoystickOffset.x.toDp() },
                                    y = with(density) { rightJoystickOffset.y.toDp() }
                                )
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color.White, Color(0xFFFF00AA))
                                    )
                                )
                        )
                    }
                }
            }
        }

        // --- CINEMATIC WIDESCREEN CINEMA BARS FOR BOSS INTRO ---
        if (engineState == GameState.IN_CINEMATIC) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Black Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(Color.Black)
                )
                // Bottom Black Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(Color.Black)
                )
            }

            // Cinematic title card zooming
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x33000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "!!! WARNING !!!",
                        color = Color(0xFFFF3300),
                        fontSize = 32.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Red, Offset(0f, 0f), 15f))
                    )
                    Text(
                        text = gameEngine.cinematicBossName,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = gameEngine.cinematicBossTitle,
                        color = Color(0xFFFF8800),
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                        letterSpacing = 4.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // --- UPGRADE MENU SCREEN OVERLAY ---
        if (engineState == GameState.UPGRADE_MENU || engineState == GameState.LEVEL_UP_MENU) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE0050811))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val title = if (engineState == GameState.LEVEL_UP_MENU) "LEVEL UP!" else "SYSTEM UPGRADE"
                    val titleColor = if (engineState == GameState.LEVEL_UP_MENU) Color(0xFFFFD700) else Color.Cyan
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(titleColor, Offset(0f, 0f), 15f)),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        val upgrades = remember(engineLevel, currentStage) {
                            val available = UpgradeType.values().toList()
                            available.shuffled().take(3)
                        }
                        
                        fun info(u: UpgradeType): Pair<String, String> {
                            return when(u) {
                                UpgradeType.RAPID_FIRE -> Pair("RAPID FIRE", "Increases fire rate by 15%.")
                                UpgradeType.DAMAGE_CORE -> Pair("DAMAGE CORE", "Increases base damage by 20%.")
                                UpgradeType.ACCELERATOR -> Pair("ACCELERATOR", "Increases projectile speed by 15%.")
                                UpgradeType.PROJECTILE_EX -> Pair("SPREAD SHOT", "Fires 1 extra projectile.")
                                UpgradeType.PRECISION -> Pair("PRECISION", "Increases critical hit chance by 10%.")
                                UpgradeType.CRIT_CORE -> Pair("CRIT CORE", "Increases critical damage by 50%.")
                                UpgradeType.REINFORCED_ARMOR -> Pair("ARMOR", "Max HP increased by 25.")
                                UpgradeType.CYBER_LEGS -> Pair("CYBER LEGS", "Move 10% faster.")
                                UpgradeType.DASH_CAP -> Pair("DASH CAP", "Dash cooldown reduced by 0.45s.")
                                UpgradeType.XP_SCANNER -> Pair("XP SCANNER", "Increases XP pickup radius by 20%.")
                                UpgradeType.NANO_REPAIR -> Pair("NANO REPAIR", "Slowly regenerates HP over time.")
                                UpgradeType.SHOCKWAVE -> Pair("SHOCKWAVE", "Dashing pushes and damages enemies.")
                                UpgradeType.INCENDIARY -> Pair("INCENDIARY", "Bullets deal 25% more final damage.")
                                UpgradeType.CHAIN_LIGHTNING -> Pair("LIGHTNING", "Bullets chain to nearby targets.")
                                UpgradeType.EXPLOSIVE -> Pair("EXPLOSIVE", "Bullets detonate on impact for AoE.")
                                UpgradeType.AUTO_SHIELD -> Pair("AUTO SHIELD", "Gain a shield periodically.")
                            }
                        }

                        upgrades.forEach { upgrade ->
                            val (name, desc) = info(upgrade)
                            Card(
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(240.dp)
                                    .clickable { 
                                        gameEngine.applyLevelUpUpgrade(upgrade)
                                        gameEngine.state = GameState.PLAYING
                                    },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C101B)),
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                                border = androidx.compose.foundation.BorderStroke(2.dp, Brush.linearGradient(listOf(Color.Cyan, Color.Magenta)))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp).fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(text = name, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Cyan, Offset.Zero, 8f)))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(text = desc, color = Color.LightGray, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- NEON CYBERPUNK START OVERLAY SCREEN ---
        if (engineState == GameState.MAIN_MENU) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF030308))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                // Glowing vector backdrop patterns
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color(0xFF00FFCC).copy(alpha = 0.05f),
                        radius = size.width / 3f,
                        center = center
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "ROGUEMIND",
                        color = Color.White,
                        fontSize = 58.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        letterSpacing = 6.sp,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Cyan, Offset(0f, 0f), 20f))
                    )
                    Text(
                        text = "ARENA",
                        color = Color(0xFFFF0055),
                        fontSize = 42.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        letterSpacing = 12.sp,
                        modifier = Modifier.offset(y = (-8).dp),
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Magenta, Offset(0f, 0f), 15f))
                    )

                    Text(
                        text = "NEON TRANS-DUAL SHOOTER SYSTEM",
                        color = Color(0xFF00FFCC),
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 42.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Button(
                            onClick = { if (gameEngine.currentStage > 1) gameEngine.currentStage-- },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("<", fontSize = 20.sp, color = Color.White)
                        }
                        
                        Spacer(modifier = Modifier.width(24.dp))
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("STAGE SELECTION", color = Color.Gray, fontSize = 12.sp)
                            Text("STAGE ${gameEngine.currentStage}", color = Color.Cyan, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                        }

                        Spacer(modifier = Modifier.width(24.dp))
                        
                        Button(
                            onClick = { if (gameEngine.currentStage < gameEngine.highestStage) gameEngine.currentStage++ },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text(">", fontSize = 20.sp, color = Color.White)
                        }
                    }

                    Button(
                        onClick = {
                            gameEngine.startNewGame()
                            isPlaying = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .width(220.dp)
                            .height(52.dp)
                            .testTag("enter_arena_button"),
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        Text(
                            text = "PLAY",
                            fontSize = 18.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(text = "HIGHEST STAGE: ${gameEngine.highestStage}", color = Color.Gray, fontSize = 14.sp)
                    Text(text = "CREDITS: ${gameEngine.credits}", color = Color(0xFFFFD700), fontSize = 14.sp)
                }
            }
        }

        // --- GAME OVER OR VICTORY SCREEN OVERLAYS ---
        if (engineState == GameState.GAME_OVER || engineState == GameState.GAME_WON) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xDD030308))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(420.dp)
                        .wrapContentHeight()
                        .testTag("end_screen_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C101B)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (engineState == GameState.GAME_WON) {
                            Text(
                                text = "CORE DEFEATED",
                                color = Color(0xFF00ffcc),
                                fontSize = 32.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Cyan, Offset(0f,0f), 15f))
                            )
                            Text(
                                text = "VICTORY IMMINENT",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Thin,
                                modifier = Modifier.padding(bottom = 20.dp)
                            )
                        } else {
                            Text(
                                text = "CONNECTION LOST",
                                color = Color(0xFFFF0055),
                                fontSize = 32.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Red, Offset(0f,0f), 15f))
                            )
                            Text(
                                text = "BRAIN CELL COLLAPSE",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Thin,
                                modifier = Modifier.padding(bottom = 20.dp)
                            )
                        }

                        // Score tallies
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "TOTAL SCORE", color = Color.Gray, fontSize = 14.sp)
                            Text(text = "$score", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "CYBERS DESTROYED", color = Color.Gray, fontSize = 14.sp)
                            Text(text = "${gameEngine.enemiesKilled}", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "MAX STAGE CLEARED", color = Color.Gray, fontSize = 14.sp)
                            Text(text = "STAGE $currentStage", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "SURVIVAL TIME", color = Color.Gray, fontSize = 14.sp)
                            val mins = (gameEngine.gameTime / 60).toInt()
                            val secs = (gameEngine.gameTime % 60).toInt()
                            Text(text = String.format("%02d:%02d", mins, secs), color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                gameEngine.startNewGame()
                                isPlaying = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (engineState == GameState.GAME_WON) Color(0xFF00FFCC) else Color(0xFFFF0055),
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("retry_button")
                        ) {
                            Text(
                                text = "RUN RE-INIT",
                                fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // --- STAGE CLEAR OVERLAY ---
        if (engineState == GameState.STAGE_CLEAR) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xE0050811)).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("STAGE $currentStage CLEARED", color = Color(0xFF00FFCC), fontSize = 32.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("CREDITS EARNED: ${100 + (currentStage * 25)}", color = Color(0xFFFFD700), fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { 
                        gameEngine.startStage(currentStage + 1)
                        gameEngine.state = GameState.PLAYING
                    }) { Text("NEXT STAGE") }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { 
                        gameEngine.currentStage = gameEngine.highestStage
                        gameEngine.state = GameState.MAIN_MENU 
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("MAIN MENU") }
                }
            }
        }

        // --- WEAPON SELECT OVERLAY ---
        if (engineState == GameState.WEAPON_SELECT) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xE0050811)).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CHOOSE A WEAPON", color = Color.Cyan, fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (wStr in gameEngine.weaponCratesAvailable) {
                            Button(
                                onClick = {
                                    gameEngine.activeWeapon = wStr
                                    gameEngine.state = GameState.PLAYING 
                                },
                                modifier = Modifier.width(180.dp).height(120.dp)
                            ) { Text(text = wStr.name.replace("_", " "), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                        }
                    }
                }
            }
        }

        // --- PERMA UPGRADES OVERLAY ---
        if (engineState == GameState.PERMA_UPGRADES) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xE0050811)).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("PERMANENT UPGRADES", color = Color(0xFFFFD700), fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("CREDITS: ${gameEngine.credits}", color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(24.dp))

                    fun getCost(lvl: Int) = 100 + (lvl * 150)

                    val upgradeItems = listOf(
                        Triple("DAMAGE", gameEngine.permaDamageLvl) { if (gameEngine.credits >= getCost(gameEngine.permaDamageLvl)) { gameEngine.credits -= getCost(gameEngine.permaDamageLvl); gameEngine.permaDamageLvl++; gameEngine.savePersistence() } },
                        Triple("FIRE RATE", gameEngine.permaFireRateLvl) { if (gameEngine.credits >= getCost(gameEngine.permaFireRateLvl)) { gameEngine.credits -= getCost(gameEngine.permaFireRateLvl); gameEngine.permaFireRateLvl++; gameEngine.savePersistence() } },
                        Triple("MAX HP", gameEngine.permaHpLvl) { if (gameEngine.credits >= getCost(gameEngine.permaHpLvl)) { gameEngine.credits -= getCost(gameEngine.permaHpLvl); gameEngine.permaHpLvl++; gameEngine.savePersistence() } },
                        Triple("SPEED", gameEngine.permaSpeedLvl) { if (gameEngine.credits >= getCost(gameEngine.permaSpeedLvl)) { gameEngine.credits -= getCost(gameEngine.permaSpeedLvl); gameEngine.permaSpeedLvl++; gameEngine.savePersistence() } },
                        Triple("DASH", gameEngine.permaDashLvl) { if (gameEngine.credits >= getCost(gameEngine.permaDashLvl)) { gameEngine.credits -= getCost(gameEngine.permaDashLvl); gameEngine.permaDashLvl++; gameEngine.savePersistence() } }
                    )

                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(upgradeItems.size) { i ->
                            val item = upgradeItems[i]
                            val cost = getCost(item.second)
                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${item.first} LVL ${item.second}", color = Color.Cyan)
                                Button(onClick = { item.third.invoke() }, enabled = gameEngine.credits >= cost) { Text("UPGRADE ($cost)") }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { gameEngine.state = GameState.MAIN_MENU }) { Text("BACK TO MENU") }
                }
            }
        }

        // --- INVENTORY OVERLAY ---
        if (engineState == GameState.INVENTORY) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xE0050811)).pointerInput(Unit) {}) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("ARMORY & INVENTORY", color = Color(0xFF00FFCC), fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        item {
                            Text("EQUIPPED LOADOUT", color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Primary: ${gameEngine.activeWeapon.name}", color = Color.Cyan)
                                    Text("Secondary: None", color = Color.Gray)
                                    Text("Armor Core: Standard Edition", color = Color.Gray)
                                    Text("Passive: None", color = Color.Gray)
                                }
                            }
                        }
                        
                        item {
                            Text("WEAPON COLLECTION (Tap to Equip Primary)", color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            val weapons = listOf(
                                Pair(WeaponType.DEFAULT, true),
                                Pair(WeaponType.SHOTGUN, gameEngine.unlockedShotgun),
                                Pair(WeaponType.SMG, gameEngine.unlockedSmg),
                                Pair(WeaponType.ASSAULT_RIFLE, gameEngine.unlockedAssaultRifle),
                                Pair(WeaponType.LASER_RIFLE, gameEngine.unlockedLaserRifle),
                                Pair(WeaponType.PLASMA_CANNON, gameEngine.unlockedPlasmaCannon),
                                Pair(WeaponType.ROCKET_LAUNCHER, gameEngine.highestStage >= 20), // Placeholder unlock logic since it wasn't requested explicitly but we have the type. Wait.
                                Pair(WeaponType.RAILGUN, gameEngine.unlockedRailgun)
                            )
                            
                            weapons.forEach { (weapon, unlocked) ->
                                val color = if (unlocked) if (gameEngine.activeWeapon == weapon) Color.Yellow else Color.Green else Color.DarkGray
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = unlocked) {
                                        gameEngine.activeWeapon = weapon
                                        gameEngine.savePersistence()
                                    }
                                ) {
                                    Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(weapon.name, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        if (!unlocked) Text("LOCKED", color = Color.Red, fontSize = 12.sp)
                                        else if (gameEngine.activeWeapon == weapon) Text("EQUIPPED", color = Color.Yellow, fontSize = 12.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        
                        item {
                            Text("POWER-UP COLLECTION", color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("All power-ups discovered via play.", color = Color.Gray, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        item {
                            Text("BOSS COLLECTION", color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Defeated: ${if (gameEngine.highestStage >= 1) "Stage 1 Boss" else "None"}", color = Color.LightGray)
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                    
                    Button(
                        onClick = { gameEngine.state = GameState.MAIN_MENU },
                        modifier = Modifier.fillMaxWidth(0.6f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) { 
                        Text("BACK", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) 
                    }
                }
            }
        }
    }
}
