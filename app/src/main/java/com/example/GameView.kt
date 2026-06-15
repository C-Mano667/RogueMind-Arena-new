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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
    val gameEngine = remember { GameEngine() }

    // Load Sprites
    val playerSprite = ImageBitmap.imageResource(id = R.drawable.image_2)
    val soldierSprite = ImageBitmap.imageResource(id = R.drawable.soldier3)
    val bossSprite = ImageBitmap.imageResource(id = R.drawable.boss3)
    val arenaSprite = ImageBitmap.imageResource(id = R.drawable.arena3)


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
    var engineState by remember { mutableStateOf(GameState.START_SCREEN) }
    var waveNum by remember { mutableStateOf(1) }
    var score by remember { mutableStateOf(0) }
    var enemiesRemaining by remember { mutableStateOf(0) }
    var playerHp by remember { mutableStateOf(100f) }
    var playerMaxHp by remember { mutableStateOf(100f) }
    var dashCooldownRemaining by remember { mutableStateOf(0f) }

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
            waveNum = gameEngine.waveNumber
            score = gameEngine.score
            enemiesRemaining = gameEngine.enemies.count { it.hp > 0f }
            playerHp = gameEngine.player.hp
            playerMaxHp = gameEngine.player.maxHp
            dashCooldownRemaining = gameEngine.player.dashCooldown

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

                    // 3. Draw Dash Afterimages
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
                Column {
                    Text(
                        text = "WAVE $waveNum",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Cyan, Offset(0f, 0f), 10f))
                    )
                    Text(
                        text = "SCORE: $score",
                        color = Color(0xFF00FFCC),
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
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
                }
                
                // Keep blank layout on right side to balance minimap overlap
                Spacer(modifier = Modifier.width(135.dp))
            }

            // --- BOTTOM TOUCH INTERACTIVE ZONE ---
            if (engineState == GameState.PLAYING) {
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
                        val progress = if (dashCooldownRemaining > 0f) dashCooldownRemaining / GameEngine.DASH_COOLDOWN else 0f
                        
                        Canvas(modifier = Modifier.fillMaxSize()) {
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
                                .background(if (dashCooldownRemaining > 0f) Color(0xFF15171C) else Color(0xFFFF0055))
                                .clickable {
                                    if (dashCooldownRemaining <= 0f) {
                                        // Trigger Dash utilizing current movement stick directions
                                        gameEngine.performDash(leftJoystickOffset.x, leftJoystickOffset.y)
                                    }
                                }
                                .testTag("dash_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "DASH",
                                color = if (dashCooldownRemaining > 0f) Color.Gray else Color.White,
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

        // --- NEON CYBERPUNK START OVERLAY SCREEN ---
        if (engineState == GameState.START_SCREEN) {
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
                            text = "ENTER ARENA",
                            fontSize = 18.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                    }
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
                            Text(text = "MAX WAVE CLEARED", color = Color.Gray, fontSize = 14.sp)
                            Text(text = "WAVE $waveNum", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
    }
}
