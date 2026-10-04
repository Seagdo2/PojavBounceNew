/*
 * ModuleDynamicIsland —— 还原 DynamicIsland.zip (Renderer + Notifications + Card)
 * 原生 OverlayRender，无 Web / 无 Skia
 *
 * 模式优先级（与原版一致）:
 *   Tab 列表 > 通知 > 低血警报 > 物品使用 > 方块计数 > 默认信息条
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object ModuleDynamicIsland : ClientModule(
    "DynamicIsland",
    ModuleCategories.RENDER,
    aliases = listOf("DynamicIslandHUD", "Island"),
) {

    /* ============================= 可调节 ============================= */

    private val offsetX by float("Offset X", 0f, -400f..400f)
    private val offsetY by float("Offset Y", 0f, -40f..200f)
    private val uiScale by float("Scale", 1f, 0.6f..1.6f)
    private val sizeEase by float("Size Ease", 13.5f, 4f..30f)
    private val tabFadeSpeed by float("Tab Fade", 9f, 2f..20f)

    private val minWidth by float("Min Width", 250f, 120f..400f)
    private val islandHeight by float("Height", 30f, 22f..48f)
    private val blockHeight by float("Expanded Height", 72f, 50f..120f)
    private val alertHeight by float("Alert Height", 58f, 40f..100f)
    private val topPad by float("Top Padding", 10f, 0f..40f)

    private val bgColor by color("Background", Color4b(18, 18, 22, 230))
    private val bgHighlight by color("Highlight", Color4b(40, 40, 48, 90))
    private val textPrimary by color("Text Primary", Color4b(255, 255, 255, 240))
    private val textSecondary by color("Text Secondary", Color4b(180, 180, 190, 200))
    private val separatorCol by color("Separator", Color4b(120, 120, 130, 160))
    private val accent by color("Accent", Color4b(0x56, 0xB4, 0xE9, 255))
    private val successCol by color("Success", Color4b(0x22, 0xC5, 0x5E, 255))
    private val failureCol by color("Failure", Color4b(0xFF, 0x55, 0x55, 255))
    private val goldCol by color("Victory Gold", Color4b(0xFF, 0xC8, 0x57, 255))
    private val alertCol by color("Alert", Color4b(0xFF, 0x6B, 0x6B, 255))
    private val progressTrack by color("Progress Track", Color4b(255, 255, 255, 40))
    private val iconChip by color("Icon Chip", Color4b(255, 255, 255, 28))

    private val showDefaultInfo by boolean("Default Info", true)
    private val showFps by boolean("Show FPS", true)
    private val showTime by boolean("Show Time", true)
    private val showCoords by boolean("Show Coords", false)
    private val showNotifications by boolean("Notifications", true)
    private val showLowHealth by boolean("Low Health Alert", true)
    private val lowHealthThreshold by float("Low Health %", 0.35f, 0.1f..0.7f)
    private val showItemUse by boolean("Item Use Status", true)
    private val showBlockCount by boolean("Block Count", true)
    private val showTabList by boolean("Tab List Expand", true)
    private val hideInContainer by boolean("Hide In Container", true)
    private val dropShadow by boolean("Drop Shadow", true)
    private val notifyDurationMs by int("Notify Duration Ms", 4000, 1000..10000)

    /* ============================= 常量（对齐原版） ============================= */

    private const val PADDING_X = 18f
    private const val BLOCK_ICON_X = 12f
    private const val BLOCK_ICON_Y = 10f
    private const val BLOCK_ICON_BOX = 42f
    private const val BLOCK_TEXT_X = 68f
    private const val BLOCK_PROGRESS_X = 16f
    private const val BLOCK_PROGRESS_Y = 59f
    private const val BLOCK_PROGRESS_H = 9f
    private const val BLOCK_RIGHT_PADDING = 18f
    private const val BLOCK_MIN_WIDTH = 206f
    private const val TAB_MIN_WIDTH = 340f
    private const val TAB_TOP_PADDING = 16f
    private const val TAB_BOTTOM_PADDING = 16f
    private const val TAB_SIDE_PADDING = 22f
    private const val TAB_ROW_HEIGHT = 14f
    private const val TAB_COLUMN_GAP = 20f
    private const val TAB_MAX_ROWS = 20
    private const val TAB_SELF = 0xFFFF5555.toInt()
    private const val TAB_DEFAULT = 0xFFFFFFFF.toInt()
    private const val TAB_SPEC = 0xFFAAAAAA.toInt()

    /* ============================= 通知 API（对齐 DynamicIslandNotifications） ============================= */

    data class NotifyCard(
        val visible: Boolean,
        val icon: String,
        val title: String,
        val message: String,
        val accentArgb: Int,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        companion object {
            val EMPTY = NotifyCard(false, "", "", "", 0xFFFFFFFF.toInt(), 0L)
        }
    }

    private var currentNotify = NotifyCard.EMPTY
    private var notifyExpireAt = 0L

    fun success(message: String) = showNotify("Success", "✓", message, successCol.argb)
    fun failure(message: String) = showNotify("Failure", "✕", message, failureCol.argb)
    fun error(message: String) = showNotify("Error", "!", message, failureCol.argb)
    fun victory() = showNotify("Victory!", "★", "You are the ultimate winner.", goldCol.argb)

    private fun showNotify(title: String, icon: String, message: String, accentArgb: Int) {
        currentNotify = NotifyCard(true, icon, title, message, accentArgb)
        notifyExpireAt = System.currentTimeMillis() + notifyDurationMs
    }

    private fun notifySnapshot(): NotifyCard {
        if (!currentNotify.visible) return NotifyCard.EMPTY
        if (System.currentTimeMillis() > notifyExpireAt) {
            currentNotify = NotifyCard.EMPTY
            return NotifyCard.EMPTY
        }
        return currentNotify
    }

    /* ============================= 动画状态 ============================= */

    private data class Layout(val width: Float, val height: Float, val radius: Float, val isTab: Boolean)

    private var animW = -1f
    private var animH = -1f
    private var tabFade = 0f
    private var lastNs = 0L

    private enum class Mode { TAB, NOTIFY, ALERT, ITEM_USE, BLOCK, DEFAULT }

    /* ============================= 工具 ============================= */

    private fun easeTo(current: Float, target: Float, speed: Float, dt: Float): Float {
        if (current < 0f) return target
        val t = (1f - (1f - 0.15f).pow(dt * speed)).coerceIn(0f, 1f)
        return current + (target - current) * t
    }

    private fun clamp(v: Float, minV: Float, maxV: Float) = max(minV, min(maxV, v))

    private fun withA(c: Color4b, a: Float) = Color4b(c.r, c.g, c.b, (c.a * a.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255))

    private fun argbToColor(argb: Int, aScale: Float = 1f): Color4b {
        val a = (((argb ushr 24) and 0xFF) * aScale).roundToInt().coerceIn(0, 255)
        return Color4b((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, a)
    }

    private fun progressColor(p: Float, a: Float): Color4b {
        // 绿→黄→红
        val t = p.coerceIn(0f, 1f)
        val r: Int
        val g: Int
        val b: Int
        if (t > 0.5f) {
            val u = (t - 0.5f) * 2f
            r = (lerp(255f, 34f, u)).roundToInt()
            g = (lerp(200f, 197f, u)).roundToInt()
            b = (lerp(50f, 94f, u)).roundToInt()
        } else {
            val u = t * 2f
            r = (lerp(255f, 255f, u)).roundToInt()
            g = (lerp(80f, 200f, u)).roundToInt()
            b = (lerp(80f, 50f, u)).roundToInt()
        }
        return Color4b(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), (230 * a).roundToInt())
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun isTabHeld(): Boolean {
        if (!showTabList) return false
        return try {
            val win = mc.window
            var handle = 0L
            try {
                val f = win.javaClass.getDeclaredField("handle")
                f.isAccessible = true
                handle = f.getLong(win)
            } catch (_: Throwable) {
                try {
                    handle = win.javaClass.methods.firstOrNull {
                        it.name.equals("getHandle", true) && it.parameterCount == 0
                    }?.invoke(win) as? Long ?: 0L
                } catch (_: Throwable) {
                    return false
                }
            }
            if (handle == 0L) return false
            GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS
        } catch (_: Throwable) {
            false
        }
    }

    private fun font(): Font = mc.font

    private fun measure(s: String): Float = font().width(s).toFloat()

    /* ============================= 内容构建 ============================= */

    private data class DefaultContent(val left: String, val mid: String, val right: String)

    private fun buildDefaultContent(): DefaultContent {
        val p = mc.player
        val parts = mutableListOf<String>()
        if (showTime) {
            val cal = java.util.Calendar.getInstance()
            parts += String.format(Locale.ROOT, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }
        if (showFps) {
            val fps = try {
                mc.fps
            } catch (_: Throwable) {
                try {
                    mc.javaClass.methods.firstOrNull { it.name.equals("getFps", true) && it.parameterCount == 0 }
                        ?.invoke(mc) as? Int ?: 0
                } catch (_: Throwable) {
                    0
                }
            }
            parts += "${fps}fps"
        }
        if (showCoords && p != null) {
            parts += String.format(Locale.ROOT, "%.0f %.0f %.0f", p.x, p.y, p.z)
        }
        val name = p?.gameProfile?.name ?: "Player"
        return when (parts.size) {
            0 -> DefaultContent(name, "", "")
            1 -> DefaultContent(name, parts[0], "")
            2 -> DefaultContent(name, parts[0], parts[1])
            else -> DefaultContent(name, parts[0], parts.drop(1).joinToString(" · "))
        }
    }

    private data class BlockSnap(val visible: Boolean, val itemName: String, val detail: String, val progress: Float, val alpha: Float)

    private fun blockSnapshot(): BlockSnap {
        if (!showBlockCount) return BlockSnap(false, "", "", 0f, 0f)
        val p = mc.player ?: return BlockSnap(false, "", "", 0f, 0f)
        val stack = p.getMainHandItem()
        if (stack.isEmpty || stack.item !is BlockItem) return BlockSnap(false, "", "", 0f, 0f)
        val count = stack.count
        val max = stack.maxStackSize.coerceAtLeast(1)
        val name = try {
            stack.hoverName.string
        } catch (_: Throwable) {
            stack.item.toString()
        }
        return BlockSnap(true, name, "x$count / $max", count.toFloat() / max, 1f)
    }

    private data class ItemUseSnap(val visible: Boolean, val title: String, val detail: String, val progress: Float, val alpha: Float)

    private fun itemUseSnapshot(): ItemUseSnap {
        if (!showItemUse) return ItemUseSnap(false, "", "", 0f, 0f)
        val p = mc.player ?: return ItemUseSnap(false, "", "", 0f, 0f)
        val using = try {
            p.isUsingItem()
        } catch (_: Throwable) {
            false
        }
        if (!using) return ItemUseSnap(false, "", "", 0f, 0f)
        val stack = try {
            p.getUseItem()
        } catch (_: Throwable) {
            p.getMainHandItem()
        }
        val remain = try {
            p.getUseItemRemainingTicks()
        } catch (_: Throwable) {
            0
        }
        val total = try {
            stack.getUseDuration(p)
        } catch (_: Throwable) {
            max(1, remain)
        }.coerceAtLeast(1)
        val progress = 1f - (remain.toFloat() / total)
        val name = try {
            stack.hoverName.string
        } catch (_: Throwable) {
            "Using"
        }
        return ItemUseSnap(true, name, String.format(Locale.ROOT, "%.0f%%", progress * 100f), progress.coerceIn(0f, 1f), 1f)
    }

    private data class AlertSnap(val visible: Boolean, val title: String, val message: String)

    private fun alertSnapshot(): AlertSnap {
        if (!showLowHealth) return AlertSnap(false, "", "")
        val p = mc.player ?: return AlertSnap(false, "", "")
        val maxH = p.maxHealth.coerceAtLeast(1f)
        val ratio = p.health / maxH
        if (ratio > lowHealthThreshold) return AlertSnap(false, "", "")
        return AlertSnap(true, "Low Health", String.format(Locale.ROOT, "%.1f / %.1f HP", p.health, maxH))
    }

    private data class TabPlayer(val name: String, val latency: Int, val spectator: Boolean, val self: Boolean)

    private fun tabPlayers(): List<TabPlayer> {
        val conn = mc.connection ?: return emptyList()
        val selfName = mc.player?.gameProfile?.name
        return try {
            conn.listedOnlinePlayers.map { info ->
                val name = try {
                    info.profile.name
                } catch (_: Throwable) {
                    info.toString()
                }
                val lat = try {
                    info.latency
                } catch (_: Throwable) {
                    0
                }
                val spec = try {
                    info.gameMode == GameType.SPECTATOR
                } catch (_: Throwable) {
                    false
                }
                TabPlayer(name, lat, spec, name == selfName)
            }.sortedBy { it.name.lowercase() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun measureDefault(content: DefaultContent, screenW: Float): Layout {
        val textW = measure(content.left) +
            (if (content.mid.isNotEmpty()) measure(" | ") + measure(content.mid) else 0f) +
            (if (content.right.isNotEmpty()) measure(" | ") + measure(content.right) else 0f)
        val maxW = screenW - 48f
        val w = clamp(max(minWidth * uiScale, textW + PADDING_X * 2f * uiScale), minWidth * 0.5f, maxW)
        val h = islandHeight * uiScale
        return Layout(w, h, min(h * 0.5f, 16f * uiScale), false)
    }

    private fun measureExpanded(title: String, detail: String, screenW: Float, h: Float): Layout {
        val contentW = BLOCK_TEXT_X * uiScale + max(measure(title), measure(detail)) + BLOCK_RIGHT_PADDING * uiScale
        val maxW = max(BLOCK_MIN_WIDTH * uiScale, screenW - 48f)
        val w = clamp(max(BLOCK_MIN_WIDTH * uiScale, contentW), BLOCK_MIN_WIDTH * 0.8f, maxW)
        return Layout(w, h * uiScale, 14f * uiScale, false)
    }

    private fun measureTab(players: List<TabPlayer>, screenW: Float, screenH: Float): Layout {
        val count = players.size.coerceAtLeast(1)
        val columns = max(1, (count + TAB_MAX_ROWS - 1) / TAB_MAX_ROWS)
        val rows = max(1, (count + columns - 1) / columns)
        val w = clamp(TAB_MIN_WIDTH * uiScale, TAB_MIN_WIDTH * 0.7f, screenW - 68f)
        val h = clamp(
            (TAB_TOP_PADDING + TAB_BOTTOM_PADDING + rows * TAB_ROW_HEIGHT + 12f) * uiScale,
            74f * uiScale,
            screenH * 0.7f,
        )
        return Layout(w, h, 18f * uiScale, true)
    }

    /* ============================= 绘制 ============================= */

    private fun GuiGraphicsExtractor.drawIslandBg(x: Float, y: Float, w: Float, h: Float, r: Float, a: Float) {
        if (dropShadow) {
            for (i in 1..4) {
                val e = i * 1.8f
                val sa = (14 * a * (1f - i / 5f)).roundToInt()
                drawRoundedRect(x - e, y - e * 0.4f, x + w + e, y + h + e, r + 1f, Color4b(0, 0, 0, sa))
            }
        }
        drawRoundedRect(x, y, x + w, y + h, r, withA(bgColor, a))
        // 顶部高光
        drawRoundedRect(x + 2f, y + 1f, x + w - 2f, y + h * 0.45f, r * 0.8f, withA(bgHighlight, a * 0.55f))
    }

    private fun GuiGraphicsExtractor.drawDefault(x: Float, y: Float, layout: Layout, content: DefaultContent, a: Float) {
        val f = font()
        val ty = y + layout.height / 2f - 4f
        var cx = x + PADDING_X * uiScale
        val tc = withA(textPrimary, a)
        val sc = withA(separatorCol, a)
        text(f, content.left, cx.roundToInt(), ty.roundToInt(), tc.argb, true)
        cx += measure(content.left)
        if (content.mid.isNotEmpty()) {
            cx += 8f * uiScale
            text(f, "|", cx.roundToInt(), ty.roundToInt(), sc.argb, false)
            cx += measure("|") + 8f * uiScale
            text(f, content.mid, cx.roundToInt(), ty.roundToInt(), withA(textSecondary, a).argb, false)
            cx += measure(content.mid)
        }
        if (content.right.isNotEmpty()) {
            cx += 8f * uiScale
            text(f, "|", cx.roundToInt(), ty.roundToInt(), sc.argb, false)
            cx += measure("|") + 8f * uiScale
            text(f, content.right, cx.roundToInt(), ty.roundToInt(), withA(textSecondary, a).argb, false)
        }
    }

    private fun GuiGraphicsExtractor.drawDetailCard(
        x: Float, y: Float, layout: Layout,
        icon: String, title: String, detail: String,
        accentC: Color4b, progress: Float?, a: Float,
    ) {
        val f = font()
        val s = uiScale
        // 图标底
        val ix = x + BLOCK_ICON_X * s
        val iy = y + BLOCK_ICON_Y * s
        val box = BLOCK_ICON_BOX * s
        drawRoundedRect(ix, iy, ix + box, iy + box, 10f * s, withA(iconChip, a))
        val iconW = measure(icon)
        text(f, icon, (ix + (box - iconW) / 2f).roundToInt(), (iy + box / 2f - 4f).roundToInt(), withA(accentC, a).argb, true)
        // 文字
        val tx = x + BLOCK_TEXT_X * s
        text(f, title, tx.roundToInt(), (y + 16f * s).roundToInt(), withA(textPrimary, a).argb, true)
        text(f, detail, tx.roundToInt(), (y + 32f * s).roundToInt(), withA(textSecondary, a).argb, false)
        // 进度条
        if (progress != null) {
            val px = x + BLOCK_PROGRESS_X * s
            val py = y + layout.height - 13f * s
            val pw = max(80f * s, layout.width - px * 2f + x - x)
            val ph = BLOCK_PROGRESS_H * s
            val progressW = max(80f * s, layout.width - (BLOCK_PROGRESS_X * 2f * s))
            drawRoundedRect(px, py, px + progressW, py + ph, ph * 0.5f, withA(progressTrack, a))
            val fillW = max(ph, progressW * progress.coerceIn(0f, 1f))
            drawRoundedRect(px, py, px + fillW, py + ph, ph * 0.5f, progressColor(progress, a))
        }
    }

    private fun GuiGraphicsExtractor.drawTab(x: Float, y: Float, layout: Layout, players: List<TabPlayer>, fade: Float) {
        if (fade <= 0.01f || players.isEmpty()) return
        val f = font()
        val a = fade
        val count = players.size
        val columns = max(1, (count + TAB_MAX_ROWS - 1) / TAB_MAX_ROWS)
        val rows = max(1, (count + columns - 1) / columns)
        val usableW = layout.width - TAB_SIDE_PADDING * 2f * uiScale - (columns - 1) * TAB_COLUMN_GAP * uiScale
        val columnW = usableW / columns
        val totalH = rows * TAB_ROW_HEIGHT * uiScale
        val startY = y + TAB_TOP_PADDING * uiScale +
            (layout.height - TAB_TOP_PADDING * uiScale - TAB_BOTTOM_PADDING * uiScale - totalH) * 0.5f

        for (i in 0 until count) {
            val column = i / rows
            val row = i % rows
            val px = x + TAB_SIDE_PADDING * uiScale + column * (columnW + TAB_COLUMN_GAP * uiScale)
            val py = startY + row * TAB_ROW_HEIGHT * uiScale
            val pl = players[i]
            val nameCol = when {
                pl.self -> TAB_SELF
                pl.spectator -> TAB_SPEC
                else -> TAB_DEFAULT
            }
            val name = if (measure(pl.name) > columnW - 46f) pl.name.take(10) + "…" else pl.name
            text(f, name, px.roundToInt(), py.roundToInt(), argbToColor(nameCol, a).argb, false)
            val lat = "${pl.latency}ms"
            val latCol = when {
                pl.latency < 80 -> successCol
                pl.latency < 150 -> goldCol
                else -> failureCol
            }
            text(f, lat, (px + columnW - measure(lat)).roundToInt(), py.roundToInt(), withA(latCol, a).argb, false)
        }
    }

    /* ============================= 事件 ============================= */

    @Suppress("unused")
    private val moduleToggleHandler = handler<ModuleToggleEvent> { e ->
        if (!showNotifications || !enabled) return@handler
        // ModuleToggleEvent 在 LB NextGen 中使用 event.module (val)
        val mod = try {
            // 尝试直接访问 module 字段
            val f = e.javaClass.getDeclaredField("module")
            f.isAccessible = true
            f.get(e)
        } catch (_: Throwable) {
            try {
                // 尝试通过 getter
                e.javaClass.methods.firstOrNull { 
                    it.name == "getModule" || it.name == "module" && it.parameterCount == 0 
                }?.invoke(e)
            } catch (_: Throwable) {
                return@handler
            }
        } ?: return@handler

        val name = try {
            val nameField = mod.javaClass.getDeclaredField("name")
            nameField.isAccessible = true
            nameField.get(mod)?.toString()
        } catch (_: Throwable) {
            mod.javaClass.simpleName
        } ?: return@handler

        val on = try {
            val enabledField = mod.javaClass.getDeclaredField("enabled")
            enabledField.isAccessible = true
            enabledField.getBoolean(mod)
        } catch (_: Throwable) {
            try {
                val enabledMethod = mod.javaClass.methods.firstOrNull { 
                    it.name == "getEnabled" || it.name == "isEnabled" 
                }
                enabledMethod?.invoke(mod) as? Boolean
            } catch (_: Throwable) {
                return@handler
            }
        } ?: return@handler

        if (on) success("$name enabled") else failure("$name disabled")
    }

    @Suppress("unused")
    private val notificationHandler = handler<NotificationEvent> { e ->
        if (!showNotifications || !enabled) return@handler
        val title = try {
            e.javaClass.methods.firstOrNull { it.name == "title" || it.name == "getTitle" && it.parameterCount == 0 }
                ?.invoke(e)?.toString() ?: "Notice"
        } catch (_: Throwable) {
            "Notice"
        }
        val msg = try {
            e.javaClass.methods.firstOrNull { it.name == "message" || it.name == "getMessage" && it.parameterCount == 0 }
                ?.invoke(e)?.toString() ?: ""
        } catch (_: Throwable) {
            ""
        }
        val sev = try {
            e.javaClass.methods.firstOrNull { it.name.contains("severity", true) || it.name.contains("Severity") }
                ?.invoke(e)?.toString()?.lowercase() ?: ""
        } catch (_: Throwable) {
            ""
        }
        when {
            "error" in sev || "fail" in sev -> error(msg.ifBlank { title })
            "success" in sev || "info" in sev -> success(msg.ifBlank { title })
            else -> showNotify(title, "•", msg.ifBlank { title }, accent.argb)
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val player = mc.player ?: return@handler
        if (hideInContainer) {
            val scn = try {
                mc.gui.screen()
            } catch (_: Throwable) {
                null
            }
            if (scn != null) {
                val n = scn.javaClass.name
                if (n.contains("Container", true) || n.contains("AbstractContainer", true) ||
                    n.contains("Inventory", true) && !n.contains("Creative", true)
                ) {
                    return@handler
                }
            }
        }

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now

        val ctx = event.context
        val screenW = ctx.guiWidth().toFloat()
        val screenH = ctx.guiHeight().toFloat()

        val tabOpen = isTabHeld()
        val players = if (tabOpen) tabPlayers() else emptyList()
        val notify = if (showNotifications) notifySnapshot() else NotifyCard.EMPTY
        val alert = alertSnapshot()
        val itemUse = itemUseSnapshot()
        val block = blockSnapshot()
        val defaultContent = buildDefaultContent()

        val mode = when {
            tabOpen -> Mode.TAB
            notify.visible -> Mode.NOTIFY
            alert.visible -> Mode.ALERT
            itemUse.visible -> Mode.ITEM_USE
            block.visible -> Mode.BLOCK
            showDefaultInfo -> Mode.DEFAULT
            else -> Mode.DEFAULT
        }

        val target = when (mode) {
            Mode.TAB -> measureTab(players, screenW, screenH)
            Mode.NOTIFY -> measureExpanded(notify.title, notify.message, screenW, alertHeight)
            Mode.ALERT -> measureExpanded(alert.title, alert.message, screenW, alertHeight)
            Mode.ITEM_USE -> measureExpanded(itemUse.title, itemUse.detail, screenW, blockHeight)
            Mode.BLOCK -> measureExpanded(block.itemName, block.detail, screenW, blockHeight)
            Mode.DEFAULT -> measureDefault(defaultContent, screenW)
        }

        animW = easeTo(animW, target.width, sizeEase, dt)
        animH = easeTo(animH, target.height, sizeEase, dt)
        val layout = Layout(animW, animH, min(animH * 0.5f, if (target.isTab) 18f * uiScale else 16f * uiScale), target.isTab)

        val targetFade = if (mode == Mode.TAB) 1f else 0f
        tabFade = easeTo(tabFade, targetFade, tabFadeSpeed, dt)

        val x = clamp((screenW - layout.width) * 0.5f + offsetX, 0f, max(0f, screenW - layout.width))
        val y = clamp(topPad + offsetY, 0f, max(0f, screenH - layout.height))
        val a = 1f

        ctx.drawIslandBg(x, y, layout.width, layout.height, layout.radius, a)

        when (mode) {
            Mode.TAB -> ctx.drawTab(x, y, layout, players, tabFade)
            Mode.NOTIFY -> ctx.drawDetailCard(
                x, y, layout, notify.icon, notify.title, notify.message,
                argbToColor(notify.accentArgb), null, a,
            )
            Mode.ALERT -> ctx.drawDetailCard(x, y, layout, "!", alert.title, alert.message, alertCol, null, a)
            Mode.ITEM_USE -> ctx.drawDetailCard(
                x, y, layout, "◌", itemUse.title, itemUse.detail, accent, itemUse.progress, a,
            )
            Mode.BLOCK -> ctx.drawDetailCard(
                x, y, layout, "▣", block.itemName, block.detail, accent, block.progress, a,
            )
            Mode.DEFAULT -> ctx.drawDefault(x, y, layout, defaultContent, a)
        }
    }
}
