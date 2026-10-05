/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Revision note
 * --------------
 * The first version of this file hand-rolled rounded rectangles with a
 * scanline fill over `GuiGraphics.fill(...)`, because it wasn't yet clear
 * this project already ships a real one. It does:
 *
 *   net.ccbluex.liquidbounce.render.Render2D.kt         - GuiGraphicsExtractor.drawRoundedRect/drawQuad/drawCircle/...
 *   net.ccbluex.liquidbounce.render.gui.element.*       - the GuiElementRenderState records those build
 *   resources/liquidbounce/shaders/gui/rounded_rect.*sh - the actual SDF shader (real per-pixel AA, GPU-side)
 *
 * `GuiGraphics` implements `GuiGraphicsExtractor` (see real call sites like
 * NametagEnchantmentRenderer.kt / ItemStackListRenderer.kt calling
 * `guiGraphics.drawRoundedRect(...)` / `.drawQuad(...)` directly), so this
 * file is now just a thin, clickgui-flavoured convenience layer on top of
 * that real API - no custom shader/vertex code, no scanline math, no
 * inventing a rendering mechanism the project doesn't already have.
 */
package net.ccbluex.liquidbounce.render.clickgui

import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawHorizontalLine
import net.ccbluex.liquidbounce.render.drawQuadXYWH
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.render.asTextureSetup
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import kotlin.math.max
import kotlin.math.min

object GuiRender2D {

    private fun c(argb: Int): Color4b? = if ((argb ushr 24) == 0) null else Color4b(argb)

    /** Solid rounded rectangle via the real GUI rounded-rect shader (true
     * per-pixel SDF antialiasing, not an approximation). */
    fun fillRoundedRect(gfx: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        gfx.drawRoundedRect(
            x1 = x.toFloat(), y1 = y.toFloat(), x2 = (x + w).toFloat(), y2 = (y + h).toFloat(),
            radius = radius.toFloat(), fillColor = c(color),
        )
    }

    /** Rounded outline only, [thickness] px - the shader draws fill and
     * outline as two passes of the same primitive, so this is the same
     * call with fillColor omitted rather than a second rect punched in. */
    fun strokeRoundedRect(gfx: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, radius: Int, thickness: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        gfx.drawRoundedRect(
            x1 = x.toFloat(), y1 = y.toFloat(), x2 = (x + w).toFloat(), y2 = (y + h).toFloat(),
            radius = radius.toFloat(), fillColor = null, outlineColor = c(color),
            outlineWidth = thickness.toFloat(),
        )
    }

    /** Flat-colored straight border line (panel header bottom edge, etc). */
    fun line(gfx: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, thickness: Int, color: Int) {
        val col = c(color) ?: return
        if (y1 == y2) {
            gfx.drawHorizontalLine(min(x1, x2).toFloat(), max(x1, x2).toFloat(), y1.toFloat(), thickness.toFloat(), col)
        } else {
            gfx.drawQuadXYWH(x1.toFloat(), min(y1, y2).toFloat(), thickness.toFloat(), kotlin.math.abs(y2 - y1).toFloat(), col)
        }
    }

    /**
     * Filled circle for switch thumbs / slider handles.
     * Drawn as a rounded square (radius == half size) via the same SDF
     * rounded-rect path used by tracks — remains correct without non-uniform
     * pose scale and does not depend on GuiCircleLutAtlas.
     * Uses GuiGraphicsExtractor.drawRoundedRect (MC 26.3 / LB Render2D).
     */
    fun fillCircle(gfx: GuiGraphicsExtractor, cx: Int, cy: Int, radius: Int, color: Int) {
        if (radius <= 0) return
        val r = radius.coerceAtLeast(1)
        fillRoundedRect(gfx, cx - r, cy - r, r * 2, r * 2, r, color)
    }

    /**
     * Cheap layered "blur" shadow: several progressively larger, more
     * transparent rounded rects underneath the panel. Each layer now gets
     * a real antialiased edge from the shader (a genuine improvement over
     * the first version's stepped scanline layers), though this is still
     * not a true offscreen gaussian blur pass.
     */
    fun dropShadow(gfx: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, radius: Int, baseColor: Int, spread: Int = 6) {
        val baseAlpha = (baseColor ushr 24) and 0xFF
        if (baseAlpha == 0 || spread <= 0) return
        for (i in spread downTo 1) {
            val t = i.toFloat() / spread
            val alpha = (baseAlpha * (1f - t) * 0.5f).toInt().coerceIn(0, 255)
            if (alpha == 0) continue
            fillRoundedRect(gfx, x - i, y - i, w + i * 2, h + i * 2, radius + i, ClickGuiPalette.withAlpha(baseColor, alpha))
        }
    }

    /**
     * Blits a monochrome icon texture (white mask rasterized 1:1 from the
     * theme's own SVGs - see ClickGuiIcons.kt) tinted to [color], via the
     * real textured-quad path (`drawTexQuad` / `TexQuadGuiElementRenderState`)
     * instead of mutating global shader-color state.
     */
    fun icon(gfx: GuiGraphicsExtractor, texture: Identifier, x: Int, y: Int, size: Int, color: Int) {
        val setup = Minecraft.getInstance().textureManager.getTexture(texture).textureSetup
        gfx.drawTexQuad(
            setup,
            x0 = x.toFloat(), y0 = y.toFloat(), x1 = (x + size).toFloat(), y1 = (y + size).toFloat(),
            argb = color,
        )
    }

    /**
     * Same as [icon], but rotated [rotationDegrees] about its own center
     * (positive = clockwise, matching the web theme's own CSS
     * `transform: rotate(...)` convention on the expand chevron). Rotation
     * is done by temporarily transforming the pose (translate to center,
     * rotate, translate back) and drawing the quad in that local space, then
     * restoring the pose - via `withPush`, the same try/finally-safe wrapper
     * used in NativeClickGuiScreen, so a failure mid-draw can never leave
     * the pose stack unbalanced. At rotationDegrees == 0 this is identical
     * in effect to [icon] (the fast path there is kept as a separate
     * zero-overhead overload for every call site that never rotates).
     */
    fun iconRotated(gfx: GuiGraphicsExtractor, texture: Identifier, x: Int, y: Int, size: Int, color: Int, rotationDegrees: Float) {
        if (rotationDegrees == 0f) {
            icon(gfx, texture, x, y, size, color)
            return
        }
        val setup = Minecraft.getInstance().textureManager.getTexture(texture).textureSetup
        val cx = x + size / 2f
        val cy = y + size / 2f
        val radians = rotationDegrees * (Math.PI.toFloat() / 180f)
        gfx.pose().withPush {
            translate(cx, cy)
            rotate(radians)
            translate(-size / 2f, -size / 2f)
            gfx.drawTexQuad(setup, x0 = 0f, y0 = 0f, x1 = size.toFloat(), y1 = size.toFloat(), argb = color)
        }
    }

    /** Clamp helpers used across the widgets for drag/slider math. */
    fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))
    fun clampF(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

    /**
     * Pseudo-glass frost: this project's real render API has no gradient or
     * blur-behind primitive, so "frosted glass" is faked the way many games
     * fake it without a blur pass - a fine static-grain texture
     * (ClickGuiIcons.GLASS_NOISE) tiled across the area at a low tint alpha,
     * in ONE draw call via a repeat-wrapping sampler (not hundreds of tiny
     * fills, which tiling it this way avoids entirely). Meant to be drawn
     * once over an already-translucent background, before any text/icons on
     * top of it, so only the backdrop looks grainy/frosted - never the content.
     */
    fun frostOverlay(gfx: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, alpha: Int = 22, tileSize: Int = 48) {
        if (w <= 0 || h <= 0 || alpha <= 0) return
        val texture = Minecraft.getInstance().textureManager.getTexture(ClickGuiIcons.GLASS_NOISE)
        val repeatSampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
            .getRepeat(com.mojang.renderpearl.api.textures.FilterMode.LINEAR)
        val setup = texture.textureView.asTextureSetup(repeatSampler)
        val u2 = w.toFloat() / tileSize
        val v2 = h.toFloat() / tileSize
        val tint = ClickGuiPalette.withAlpha(ClickGuiPalette.TEXT, alpha)
        gfx.drawTexQuad(
            setup,
            x0 = x.toFloat(), y0 = y.toFloat(), x1 = (x + w).toFloat(), y1 = (y + h).toFloat(),
            u1 = 0f, v1 = 0f, u2 = u2, v2 = v2,
            argb = tint,
        )
    }

    /** Ellipsizes [text] with a trailing "..." so it never overflows [maxWidth] -
     * every module/setting label goes through this one place, so nothing can
     * spill out of its row regardless of name/value length. */
    fun ellipsize(gfx: GuiGraphicsExtractor, text: String, maxWidth: Int): String {
        val font = Minecraft.getInstance().font
        if (font.width(text) <= maxWidth) return text
        val ellipsis = "..."
        val ellipsisWidth = font.width(ellipsis)
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val candidate = text.substring(0, mid)
            if (font.width(candidate) + ellipsisWidth <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return if (lo <= 0) ellipsis else text.substring(0, lo) + ellipsis
    }

    // ==================== 跑马灯 (Marquee) ====================
    /** 跑马灯停顿时长(秒): 先在开头停 4 秒 */
    const val MARQUEE_PAUSE_S = 4f
    /** 跑马灯滚动时长(秒): 然后字符循环滚动 4 秒回到原位 */
    const val MARQUEE_SCROLL_S = 4f
    /** 跑马灯经过的时间(秒), 每帧由 NativePanel.render 更新 */
    var marqueeTime = 0f
    /** 暂停标志: NativePanel 滚动中 → true (玩家滚动GUI时跑马灯暂停) */
    var marqueePaused = false
    /** 上次 tick 的纳秒时间戳 (防同帧多行重复累加) */
    private var lastMarqueeTickNs = 0L

    /**
     * 每帧由 NativePanel.render() 调用一次:
     * - 玩家滚动GUI时 (paused=true) 跑马灯计时冻结
     * - 停止滚动后恢复计时, 跑马灯从暂停处继续
     */
    fun tickMarquee(paused: Boolean) {
        val now = System.nanoTime()
        if (lastMarqueeTickNs != 0L && !paused) {
            val dt = ((now - lastMarqueeTickNs) / 1e9f).coerceIn(0f, 0.1f)
            marqueeTime += dt
        }
        lastMarqueeTickNs = now
        marqueePaused = paused
    }

    /**
     * 跑马灯文字渲染: 超长文字用电子屏式循环滚动代替省略号
     * - 文字不超出 → 直接渲染 (调用方自行处理, 本函数只处理超出情况)
     * - 4秒停顿(显示开头) → 4秒字符循环滚动(滚完回原位) → 如此往复
     * - 滚动期间用 scissor 裁剪到可见区域, 文字从右边缘环绕进入 (两份拷贝)
     * - 玩家滚动面板时计时冻结, 停止后从暂停处继续
     *
     * @param clipY    裁剪区域顶部 Y (通常 = 行顶部)
     * @param clipH    裁剪区域高度 (通常 = 行高)
     */
    fun renderMarqueeText(
        gfx: GuiGraphicsExtractor,
        font: net.minecraft.client.gui.Font,
        text: String,
        textX: Int,
        textY: Int,
        maxWidth: Int,
        clipY: Int,
        clipH: Int,
        color: Int,
    ) {
        if (maxWidth <= 0 || clipH <= 0) return
        val textW = font.width(text)
        if (textW <= maxWidth) {
            // 不超出 → 直接渲染 (无 scissor 开销)
            gfx.text(font, text, textX, textY, color, false)
            return
        }
        val cycle = MARQUEE_PAUSE_S + MARQUEE_SCROLL_S
        val phase = marqueeTime % cycle

        // scissor 裁剪区域 (防闪退: 确保非零)
        val sx0 = maxOf(textX, 0)
        val sy0 = maxOf(clipY, 0)
        val sx1 = textX + maxWidth
        val sy1 = clipY + clipH
        if (sx1 <= sx0 || sy1 <= sy0) return

        gfx.enableScissor(sx0, sy0, sx1, sy1)

        if (phase < MARQUEE_PAUSE_S) {
            // 【停顿阶段】显示开头
            gfx.text(font, text, textX, textY, color, false)
        } else {
            // 【滚动阶段】offset 从 0 → textW, 4秒内完成一圈
            val t = (phase - MARQUEE_PAUSE_S) / MARQUEE_SCROLL_S
            val offset = (t * textW).toInt()
            // 拷贝1: 从 textX - offset 开始向左滑出
            gfx.text(font, text, textX - offset, textY, color, false)
            // 拷贝2: 紧跟其后从右侧环绕进入 (offset=textW 时拷贝2 恰好在 textX 原位)
            if (offset > 0) {
                gfx.text(font, text, textX - offset + textW, textY, color, false)
            }
        }

        gfx.disableScissor()
    }
}
