package net.ccbluex.liquidbounce.integration.title

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import kotlin.math.roundToInt

// ======== 颜色 (对齐 ThemeMod) ========
private val BUTTON_BG     = ARGB.color(230, 24, 24, 30)
private val BUTTON_HOVER  = ARGB.color(255, 70, 70, 78)
private val ICON_PLATE    = ARGB.color(230, 24, 24, 30)
private val ICON_PLATE_HV = ARGB.color(255, 70, 70, 78)
private val SMALL_BG      = ARGB.color(200, 24, 24, 30)
private val SMALL_HOVER   = ARGB.color(255, 70, 70, 78)
private val TEXT_MAIN     = -1
private val TEXT_SMALL    = ARGB.color(255, 200, 200, 210)
private val OVERLAY       = ARGB.color(100, 0, 0, 0)

// ======== 尺寸 (对齐 ThemeMod) ========
private const val BTN_W   = 300
private const val BTN_H   = 46
private const val ICON_SZ = 34
private const val BTN_GAP = 12
private const val START_X = 40
private const val SMALL_H = 20
private const val SM_ICON_SZ = 14

// ======== LiquidBounce Logo (1920x721) ========
private val LOGO_TEX = Identifier.fromNamespaceAndPath("liquidbounce", "textures/gui/logo_banner.png")
private const val LOGO_SRC_W = 1920
private const val LOGO_SRC_H = 721
private const val LOGO_DEST_H = 80
private val LOGO_DEST_W = (LOGO_DEST_H.toFloat() * LOGO_SRC_W / LOGO_SRC_H).roundToInt()

// ======== 按钮图标 (PNG 64x64, 已存在于 assets/liquidbounce/textures/clickgui/) ========
private fun clickGuiIcon(name: String): Identifier =
    Identifier.fromNamespaceAndPath("liquidbounce", "textures/clickgui/$name.png")

private val ICON_COMBAT = clickGuiIcon("icon-combat")    // 单人游戏
private val ICON_PLAYER = clickGuiIcon("icon-player")    // 多人游戏
private val ICON_CROSS  = clickGuiIcon("icon-cross")     // 退出

// ======== 动画参数 (对齐 ThemeMod) ========
private const val ANIM_MS    = 400L
private const val STAGGER_MS = 100L
private const val PRESS_MS   = 120L

// ======== 按钮定义 ========
private data class BigBtn(
    val title: String,
    val icon: Identifier?,          // PNG图标 (null则显示文字缩写)
    val fallback: String,           // 文字缩写备用
    val onClick: (CustomTitleScreen) -> Unit
)

private data class SmBtn(
    val title: String,
    val icon: Identifier?,
    val onClick: (CustomTitleScreen) -> Unit
)

/** 3 个大按钮 (左侧主列, 用PNG图标) */
private val BIG_BTNS = listOf(
    BigBtn("Singleplayer", ICON_COMBAT, "SP") { mc -> mc.gui.setScreen(SelectWorldScreen(mc)) },
    BigBtn("Multiplayer",  ICON_PLAYER, "MP") { mc -> mc.gui.setScreen(JoinMultiplayerScreen(mc)) },
    BigBtn("Options",      null,       "OP") { mc -> mc.gui.setScreen(OptionsScreen(mc, Minecraft.getInstance().options)) },
)

/** 小按钮行 (用PNG图标) */
private val SMALL_BTNS = listOf(
    SmBtn("Quit", ICON_CROSS) { _ -> Minecraft.getInstance().stop() },
)

class CustomTitleScreen : Screen(Component.literal("LiquidBounce")) {

    private var toggledAtMs = System.currentTimeMillis()
    private var hoveredBig = -1
    private var hoveredSm  = -1
    private var pressedBig = -1
    private var pressedSm  = -1
    private var pressedAtMs = 0L
    private var pendingClick: (() -> Unit)? = null

    override fun isPauseScreen() = false
    override fun shouldCloseOnEsc() = false
    // 不覆写 extractBackground → 保留 MC 原版背景

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 半透明遮罩
        ctx.fill(0, 0, width, height, OVERLAY)

        hoveredBig = -1
        hoveredSm = -1

        // 延迟点击 (对齐 ThemeMod)
        if (pendingClick != null && (pressedBig >= 0 || pressedSm >= 0)) {
            if (System.currentTimeMillis() - pressedAtMs >= PRESS_MS) {
                val act = pendingClick
                pendingClick = null
                pressedBig = -1
                pressedSm = -1
                act?.invoke()
            }
        }

        // ======== LiquidBounce Logo (替代 Minecraft 标题) ========
        val bigStartY0 = height / 2 - (BIG_BTNS.size * (BTN_H + BTN_GAP)) / 2 - 20
        val logoY = bigStartY0 - LOGO_DEST_H - 16
        if (logoY > 0) {
            ctx.blit(
                RenderPipelines.GUI_TEXTURED,
                LOGO_TEX,
                START_X, logoY,
                0f, 0f,
                LOGO_DEST_W, LOGO_DEST_H,
                LOGO_SRC_W, LOGO_SRC_H,
                LOGO_SRC_W, LOGO_SRC_H
            )
        }

        // ======== 大按钮 (左侧主列) ========
        val bigStartY = bigStartY0
        for ((i, btn) in BIG_BTNS.withIndex()) {
            val prog = animProgress(i)
            if (prog <= 0.01f) continue
            val offX = ((1f - prog) * -260f).roundToInt()
            val x = START_X + offX
            val y = bigStartY + i * (BTN_H + BTN_GAP)
            val hovered = mouseX in x..(x + BTN_W) && mouseY in y..(y + BTN_H) && offX == 0
            if (hovered) hoveredBig = i
            val press = pressScale(i)
            drawBigBtn(ctx, btn, x, y, hovered, press)
        }

        // ======== 小按钮行 ========
        val smStartY = bigStartY + BIG_BTNS.size * (BTN_H + BTN_GAP) + 8
        var smX = START_X
        for ((i, btn) in SMALL_BTNS.withIndex()) {
            val prog = animProgress(BIG_BTNS.size + i)
            if (prog <= 0.01f) { smX += smBtnWidth(btn) + 8; continue }
            val offX = ((1f - prog) * -80f).roundToInt()
            val x = smX + offX
            val w = smBtnWidth(btn)
            val hovered = mouseX in x..(x + w) && mouseY in smStartY..(smStartY + SMALL_H) && offX == 0
            if (hovered) hoveredSm = i
            val press = if (pressedSm == i) pressScale(BIG_BTNS.size + i) else 1f
            drawSmBtn(ctx, btn, x, smStartY, w, hovered, press)
            smX += w + 8
        }

        ctx.text(font, "LiquidBounce", START_X, height - 24, TEXT_SMALL, false)
    }

    // ==================== 交互 ====================

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(click, doubled)
        }
        val mx = click.x.toInt()
        val my = click.y.toInt()

        // 大按钮
        val bigStartY = height / 2 - (BIG_BTNS.size * (BTN_H + BTN_GAP)) / 2 - 20
        for ((i, btn) in BIG_BTNS.withIndex()) {
            val y = bigStartY + i * (BTN_H + BTN_GAP)
            if (mx in START_X..(START_X + BTN_W) && my in y..(y + BTN_H)) {
                pressedBig = i
                pressedAtMs = System.currentTimeMillis()
                pendingClick = { btn.onClick(this) }
                return true
            }
        }

        // 小按钮
        val smStartY = bigStartY + BIG_BTNS.size * (BTN_H + BTN_GAP) + 8
        var smX = START_X
        for ((i, btn) in SMALL_BTNS.withIndex()) {
            val w = smBtnWidth(btn)
            if (mx in smX..(smX + w) && my in smStartY..(smStartY + SMALL_H)) {
                pressedSm = i
                pressedAtMs = System.currentTimeMillis()
                pendingClick = { btn.onClick(this) }
                return true
            }
            smX += w + 8
        }

        return super.mouseClicked(click, doubled)
    }

    // ==================== 大按钮绘制 (PNG图标 + 对齐 ThemeMod) ====================

    private fun drawBigBtn(ctx: GuiGraphicsExtractor, btn: BigBtn, x: Int, y: Int, hovered: Boolean, press: Float) {
        val bw = (BTN_W * press).roundToInt().coerceAtLeast(1)
        val bh = (BTN_H * press).roundToInt().coerceAtLeast(1)
        val bx = x + (BTN_W - bw) / 2
        val by = y + (BTN_H - bh) / 2
        val r = 6

        // 深灰圆角背景
        fillRounded(ctx, bx, by, bx + bw, by + bh, r, if (hovered) BUTTON_HOVER else BUTTON_BG)

        // 图标底板 (左侧方格)
        val ix = bx + 8
        val iy = by + (bh - ICON_SZ) / 2
        fillRounded(ctx, ix, iy, ix + ICON_SZ, iy + ICON_SZ, 3, if (hovered) ICON_PLATE_HV else ICON_PLATE)

        // 图标 (PNG blit 或文字缩写)
        if (btn.icon != null) {
            drawIcon(ctx, btn.icon, ix, iy, ICON_SZ)
        } else {
            val lw = font.width(btn.fallback)
            val lh = font.lineHeight
            ctx.text(font, btn.fallback, ix + (ICON_SZ - lw) / 2, iy + (ICON_SZ - lh) / 2, TEXT_MAIN, false)
        }

        // 按钮标题
        ctx.text(font, btn.title, ix + ICON_SZ + 14, by + (bh - font.lineHeight) / 2, TEXT_MAIN, false)
    }

    // ==================== 小按钮绘制 (PNG图标) ====================

    private fun drawSmBtn(ctx: GuiGraphicsExtractor, btn: SmBtn, x: Int, y: Int, w: Int, hovered: Boolean, press: Float) {
        val pw = (w * press).roundToInt().coerceAtLeast(1)
        val ph = (SMALL_H * press).roundToInt().coerceAtLeast(1)
        val px = x + (w - pw) / 2
        val py = y + (SMALL_H - ph) / 2

        fillRounded(ctx, px, py, px + pw, py + ph, 4, if (hovered) SMALL_HOVER else SMALL_BG)

        var textX = px
        // 图标 (如有)
        if (btn.icon != null) {
            val iconSize = SM_ICON_SZ
            val iconY = py + (ph - iconSize) / 2
            drawIcon(ctx, btn.icon, px + 4, iconY, iconSize)
            textX = px + 4 + iconSize + 4
        }

        val tw = font.width(btn.title)
        ctx.text(font, btn.title, textX + (pw - (textX - px) - tw) / 2, py + (ph - font.lineHeight) / 2, TEXT_MAIN, false)
    }

    // ==================== PNG图标绘制 (对齐 ThemeMod blit 调用) ====================

    /** 在 (x,y) 位置绘制 64x64 图标, 缩放到 size×size, 保持比例居中 */
    private fun drawIcon(ctx: GuiGraphicsExtractor, iconId: Identifier, x: Int, y: Int, size: Int) {
        val pad = 4
        val inner = size - pad * 2
        if (inner <= 0) return
        // 64x64 正方形 → 等比缩放填满 inner×inner
        val dx = x + pad
        val dy = y + pad
        ctx.blit(
            RenderPipelines.GUI_TEXTURED,
            iconId,
            dx, dy,
            0f, 0f,
            inner, inner,     // 目标尺寸 (等比缩放)
            64, 64,           // 源区域 (全图)
            64, 64            // 纹理总尺寸
        )
    }

    private fun smBtnWidth(btn: SmBtn): Int {
        val iconW = if (btn.icon != null) SM_ICON_SZ + 8 else 0
        return font.width(btn.title) + iconW + 16
    }

    // ==================== 动画 (完全对齐 ThemeMod) ====================

    private fun animProgress(index: Int): Float {
        val elapsed = System.currentTimeMillis() - toggledAtMs
        val delay = index * STAGGER_MS
        val local = (elapsed - delay).coerceIn(0, ANIM_MS)
        return local.toFloat() / ANIM_MS
    }

    private fun pressScale(index: Int): Float {
        val isPressed = (pressedBig >= 0 && index == pressedBig) ||
            (pressedSm >= 0 && index == BIG_BTNS.size + pressedSm)
        if (!isPressed) return 1f
        val t = ((System.currentTimeMillis() - pressedAtMs).toFloat() / PRESS_MS).coerceIn(0f, 1f)
        return if (t < 0.5f) 1f - 0.08f * (t / 0.5f) else 0.92f + 0.04f * ((t - 0.5f) / 0.5f)
    }

    // ==================== 圆角矩形 (对齐 ThemeMod) ====================

    private fun fillRounded(ctx: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, r: Int, color: Int) {
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return
        val radius = r.coerceAtMost(w / 2).coerceAtMost(h / 2).coerceAtLeast(0)
        if (radius <= 0) { ctx.fill(x0, y0, x1, y1, color); return }
        val r2 = radius.toDouble() * radius
        for (row in 0 until h) {
            val yy = y0 + row
            val inset = when {
                row < radius -> {
                    val dy = (radius - 1 - row).toDouble()
                    (radius - kotlin.math.sqrt((r2 - dy * dy).coerceAtLeast(0.0))).toInt().coerceIn(0, radius)
                }
                row >= h - radius -> {
                    val dy = (row - (h - radius)).toDouble()
                    (radius - kotlin.math.sqrt((r2 - dy * dy).coerceAtLeast(0.0))).toInt().coerceIn(0, radius)
                }
                else -> 0
            }
            ctx.fill(x0 + inset, yy, x1 - inset, yy + 1, color)
        }
    }
}
