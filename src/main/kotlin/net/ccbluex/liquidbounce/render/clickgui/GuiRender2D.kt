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

    /** A filled circle (switch thumbs, dots, etc.) - real GPU circle shader
     * (net.ccbluex.liquidbounce.render.gui.GuiCircleLutAtlas), not a polygon
     * approximation. */
    fun fillCircle(gfx: GuiGraphicsExtractor, cx: Int, cy: Int, radius: Int, color: Int) {
        gfx.drawCircle(cx.toFloat(), cy.toFloat(), radius.toFloat()) { color }
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
    /**
     * Draws a tinted icon texture. [rotationDegrees] rotates around the icon
     * center (matches the web theme's CSS `transform: rotate(...)` on
     * `.expand-arrow-icon` / panel toggle). 0 = as-authored; Module.svelte uses
     * -90 when collapsed and 0 when expanded.
     */
    fun icon(
        gfx: GuiGraphicsExtractor,
        texture: Identifier,
        x: Int,
        y: Int,
        size: Int,
        color: Int,
        rotationDegrees: Float = 0f,
    ) {
        val setup = Minecraft.getInstance().getTextureManager().getTexture(texture).textureSetup
        if (rotationDegrees == 0f) {
            gfx.drawTexQuad(
                setup,
                x0 = x.toFloat(), y0 = y.toFloat(), x1 = (x + size).toFloat(), y1 = (y + size).toFloat(),
                argb = color,
            )
            return
        }
        val cx = x + size / 2f
        val cy = y + size / 2f
        val pose = gfx.pose()
        pose.pushMatrix()
        pose.translate(cx, cy)
        pose.rotate(Math.toRadians(rotationDegrees.toDouble()).toFloat())
        pose.translate(-cx, -cy)
        gfx.drawTexQuad(
            setup,
            x0 = x.toFloat(), y0 = y.toFloat(), x1 = (x + size).toFloat(), y1 = (y + size).toFloat(),
            argb = color,
        )
        pose.popMatrix()
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
        val texture = Minecraft.getInstance().getTextureManager().getTexture(ClickGuiIcons.GLASS_NOISE)
        val repeatSampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
            .getRepeat(com.mojang.blaze3d.textures.FilterMode.LINEAR)
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
}
