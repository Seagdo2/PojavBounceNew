/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of Panel.svelte. Geometry taken directly from its <style>:
 *   width: 250px, border-radius: 5px, header padding 10x15,
 *   header border-bottom: 2px solid var(--clickgui-panel-header-border-color),
 *   body max-height: 545px (scrolls beyond that).
 *
 * Behaviour: mousedown+drag on the header moves the panel
 * (`on:mousedown={onMouseDown}` in the source); right-click on the header
 * collapses/expands the module list (`on:contextmenu|preventDefault=
 * {toggleExpanded}`), same as clicking the little chevron button.
 *
 * Two things beyond the web source, added on request:
 *   - the body height is user-resizable via a small grip at the bottom-right
 *     corner (the web version has a single fixed 545px max-height; there is
 *     no equivalent control to port, so this is a native-only addition)
 *   - scrolling is smoothed (eased toward a target) and accumulates in
 *     float space instead of truncating to Int per-event, which was
 *     dropping small/fractional wheel deltas entirely - see the fixed bug
 *     note on `mouseScrolled` below
 */
package net.ccbluex.liquidbounce.render.clickgui.widget

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiIcons
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs

class NativePanel(
    val category: ModuleCategory,
    modules: List<ClientModule>,
    var x: Int,
    var y: Int,
) {
    companion object {
        const val WIDTH = 250
        const val HEADER_HEIGHT = 30
        const val DEFAULT_MAX_BODY_HEIGHT = 400 // scaled down from the web's 545 CSS px; see README
        const val MIN_BODY_HEIGHT = 60
        const val MAX_BODY_HEIGHT_CAP = 900
        private const val RADIUS = 5
        private const val RESIZE_GRIP = 10
        private const val SCROLL_PX_PER_NOTCH = 42f
        private const val SCROLL_EASE = 0.35f
    }

    val rows: List<NativeModuleRow> = modules.sortedBy { it.name }.map { NativeModuleRow(it) }

    var collapsed = false
        private set

    /** User-adjustable cap on the body's visible height; drag the
     * bottom-right grip to change it. Starts at [DEFAULT_MAX_BODY_HEIGHT]
     * and is simply clamped against how tall the content actually is, so
     * dragging it bigger than the content does nothing surprising. */
    var maxBodyHeight: Int = DEFAULT_MAX_BODY_HEIGHT
        private set

    // --- scrolling: accumulated as float, eased toward target each frame ---
    private var scrollTarget = 0f
    private var scrollAnimated = 0f

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var resizingHeight = false
    private var resizeStartMouseY = 0
    private var resizeStartHeight = 0

    /**
     * FIX (crash: "Scissor size must be >0, was 0x0"):
     * The logical (pre-scale) size of the visible screen, remembered from the
     * last render pass. Used to (a) clamp the scissor rectangle of the body
     * to the actually visible area before pushing it - pushing a rectangle
     * that lies fully or partially outside the screen made vanilla's scissor
     * intersection collapse to 0x0, which crashes FrontendRenderPass - and
     * (b) keep the panel header reachable while dragging, so a panel can
     * never be lost completely off-screen.
     */
    var screenW: Int = Int.MAX_VALUE
    var screenH: Int = Int.MAX_VALUE

    private fun contentHeight(): Int = rows.sumOf { it.totalHeight(WIDTH - 8) }

    private fun bodyHeight(): Int = contentHeight().coerceAtMost(maxBodyHeight).coerceAtLeast(0)

    fun totalHeight(): Int = HEADER_HEIGHT + if (collapsed) 0 else bodyHeight()

    fun render(gfx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, screenWidth: Int = Int.MAX_VALUE, screenHeight: Int = Int.MAX_VALUE) {
        screenW = screenWidth.coerceAtLeast(1)
        screenH = screenHeight.coerceAtLeast(1)
        // ease the visible scroll toward wherever the wheel last left it -
        // this is what makes scrolling feel smooth instead of snapping
        scrollAnimated += (scrollTarget - scrollAnimated) * SCROLL_EASE
        if (abs(scrollTarget - scrollAnimated) < 0.5f) scrollAnimated = scrollTarget
        val scroll = scrollAnimated.toInt()

        val glass = ModuleClickGui.glassMode
        val headerBg = if (glass) ClickGuiPalette.GLASS_PANEL_HEADER_BG else ClickGuiPalette.PANEL_HEADER_BG
        val bodyBg = if (glass) ClickGuiPalette.GLASS_PANEL_BODY_BG else ClickGuiPalette.PANEL_BODY_BG

        val h = totalHeight()
        GuiRender2D.dropShadow(gfx, x, y, WIDTH, h, RADIUS, ClickGuiPalette.PANEL_SHADOW)

        // header (fully rounded when collapsed since it IS the whole panel;
        // when a body follows, square off the header's bottom corners so it
        // sits flush against the body instead of showing a rounded seam)
        GuiRender2D.fillRoundedRect(gfx, x, y, WIDTH, HEADER_HEIGHT, RADIUS, headerBg)
        if (!collapsed) {
            gfx.fill(x, y + HEADER_HEIGHT - RADIUS, x + WIDTH, y + HEADER_HEIGHT, headerBg)
        }
        if (glass) GuiRender2D.frostOverlay(gfx, x, y, WIDTH, HEADER_HEIGHT)
        GuiRender2D.line(gfx, x, y + HEADER_HEIGHT - 2, x + WIDTH, y + HEADER_HEIGHT - 2, 2, ClickGuiPalette.PANEL_HEADER_BORDER)

        val icon = ClickGuiIcons.forCategory(category.tag)
        var textX = x + 12
        if (icon != null) {
            GuiRender2D.icon(gfx, icon, x + 10, y + HEADER_HEIGHT / 2 - 6, 12, ClickGuiPalette.TEXT)
            textX = x + 30
        }
        val font = Minecraft.getInstance().font
        gfx.text(font, category.tag, textX, y + (HEADER_HEIGHT - font.lineHeight) / 2, ClickGuiPalette.TEXT, false)

        val chevronCx = x + WIDTH - 18
        val chevronCy = y + HEADER_HEIGHT / 2
        GuiRender2D.icon(gfx, ClickGuiIcons.SETTINGS_EXPAND, chevronCx - 4, chevronCy - 4, 8, ClickGuiPalette.PANEL_TOGGLE_ICON)

        if (collapsed) {
            if (glass) GuiRender2D.strokeRoundedRect(gfx, x, y, WIDTH, HEADER_HEIGHT, RADIUS, 1, ClickGuiPalette.GLASS_EDGE_HIGHLIGHT)
            return
        }

        val bodyY = y + HEADER_HEIGHT
        val bh = bodyHeight()
        // Bottom corners are drawn square rather than rounded here: doing a
        // correct punch-through round on top of an already-opaque rect needs
        // either a stencil or a per-corner-radius primitive, and this file
        // deliberately only uses the simple, always-correct fillRoundedRect
        // (see GuiRender2D's header comment on why). Visually this reads as
        // a ~5px square corner instead of the web's rounded one - see README.
        gfx.fill(x, bodyY, x + WIDTH, bodyY + bh, bodyBg)
        if (glass) GuiRender2D.frostOverlay(gfx, x, bodyY, WIDTH, bh)

        // FIX (crash: "Scissor size must be >0, was 0x0"): only push a
        // scissor for the part of the body that is actually visible on
        // screen. Pushing the raw (x, bodyY, x+WIDTH, bodyY+bh) rectangle
        // while the panel sits partially or fully outside the screen (or
        // when bh == 0, e.g. an empty category) made the scissor
        // intersection collapse to an empty 0x0 rect, which
        // FrontendRenderPass rejects with an IllegalArgumentException that
        // kills the whole render frame -> opening the ClickGUI crashes the
        // game. An empty intersection simply means nothing of the body is
        // visible, so skipping the push (and the row rendering inside it)
        // is exactly the correct behaviour.
        val clipX0 = maxOf(x, 0)
        val clipY0 = maxOf(bodyY, 0)
        val clipX1 = minOf(x + WIDTH, screenW)
        val clipY1 = minOf(bodyY + bh, screenH)
        if (clipX1 > clipX0 && clipY1 > clipY0) {
            gfx.enableScissor(clipX0, clipY0, clipX1, clipY1)
            var rowY = bodyY - scroll
            for (row in rows) {
                val rh = row.totalHeight(WIDTH - 8)
                if (rowY + rh >= bodyY && rowY <= bodyY + bh) {
                    row.render(gfx, x + 4, rowY, WIDTH - 8, mouseX, mouseY)
                }
                rowY += rh
            }
            gfx.disableScissor()
        }

        clampScroll()

        // glass mode's "light catching the edge" cue - a thin, bright,
        // translucent outline around the whole panel, drawn last so it sits
        // on top of the frost/content rather than getting scissored away
        if (glass) {
            GuiRender2D.strokeRoundedRect(gfx, x, y, WIDTH, h, RADIUS, 1, ClickGuiPalette.GLASS_EDGE_HIGHLIGHT)
        }

        // resize grip: three short diagonal strokes in the bottom-right
        // corner, the universal "drag to resize" affordance
        if (contentHeight() > MIN_BODY_HEIGHT) {
            val gx = x + WIDTH - RESIZE_GRIP
            val gy = bodyY + bh - RESIZE_GRIP
            val gripColor = ClickGuiPalette.withAlpha(ClickGuiPalette.TEXT_DIMMED, 140)
            for (i in 0..2) {
                val o = i * 3
                GuiRender2D.line(gfx, gx + o, gy + RESIZE_GRIP, gx + RESIZE_GRIP, gy + o, 1, gripColor)
            }
        }
    }

    private fun clampScroll() {
        val maxScroll = (contentHeight() - bodyHeight()).coerceAtLeast(0).toFloat()
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollAnimated = scrollAnimated.coerceIn(0f, maxScroll)
    }

    /**
     * FIX: keeps at least the panel header inside the visible screen while
     * dragging, so a panel can never end up completely off-screen (where it
     * would be unreachable, and its body scissor would be empty).
     */
    private fun clampToScreen() {
        if (screenW != Int.MAX_VALUE) {
            x = x.coerceIn(40 - WIDTH, screenW - 40)
        }
        if (screenH != Int.MAX_VALUE) {
            y = y.coerceIn(0, (screenH - HEADER_HEIGHT).coerceAtLeast(0))
        }
    }

    /**
     * Bug fix: this used to do `scroll -= (amount * 16).toInt()`, truncating
     * straight to Int. Minecraft/GLFW can deliver [amount] as a fraction
     * smaller than 1 per event (precision mice, trackpads, some OS scroll
     * curves) - at the old 16px-per-notch scale, anything under ~0.06 was
     * silently discarded every single time, which reads exactly as "scroll
     * feels unresponsive/not smooth". Accumulating in float (scrollTarget)
     * and only rounding to Int at draw time fixes that regardless of how
     * small or large a single event's delta is.
     */
    fun mouseScrolled(mouseX: Int, mouseY: Int, amount: Double): Boolean {
        if (collapsed) return false
        if (mouseX !in x..(x + WIDTH) || mouseY !in (y + HEADER_HEIGHT)..(y + totalHeight())) return false
        scrollTarget -= (amount * SCROLL_PX_PER_NOTCH).toFloat()
        clampScroll()
        return true
    }

    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (mouseX !in x..(x + WIDTH)) return false

        if (!collapsed && button == 0) {
            val gx = x + WIDTH - RESIZE_GRIP
            val gy = y + totalHeight() - RESIZE_GRIP
            if (mouseX >= gx && mouseY >= gy) {
                resizingHeight = true
                resizeStartMouseY = mouseY
                resizeStartHeight = maxBodyHeight
                return true
            }
        }

        if (mouseY in y..(y + HEADER_HEIGHT)) {
            if (button == 1) {
                collapsed = !collapsed
                return true
            }
            val chevronZone = x + WIDTH - 26
            if (mouseX >= chevronZone) {
                collapsed = !collapsed
                return true
            }
            if (button == 0) {
                dragging = true
                dragOffsetX = mouseX - x
                dragOffsetY = mouseY - y
                return true
            }
            return false
        }

        if (!collapsed && mouseY in (y + HEADER_HEIGHT)..(y + totalHeight())) {
            val scroll = scrollAnimated.toInt()
            var rowY = y + HEADER_HEIGHT - scroll
            for (row in rows) {
                val rh = row.totalHeight(WIDTH - 8)
                if (mouseY in rowY..(rowY + rh)) {
                    return row.mouseClicked(x + 4, rowY, WIDTH - 8, mouseX, mouseY, button)
                }
                rowY += rh
            }
        }
        return false
    }

    fun mouseDragged(mouseX: Int, mouseY: Int, button: Int, dragX: Double, dragY: Double): Boolean {
        if (resizingHeight) {
            val delta = mouseY - resizeStartMouseY
            maxBodyHeight = (resizeStartHeight + delta).coerceIn(MIN_BODY_HEIGHT, MAX_BODY_HEIGHT_CAP)
            return true
        }
        if (dragging) {
            x = mouseX - dragOffsetX
            y = mouseY - dragOffsetY
            clampToScreen()
            return true
        }
        if (!collapsed) {
            val scroll = scrollAnimated.toInt()
            var rowY = y + HEADER_HEIGHT - scroll
            for (row in rows) {
                val rh = row.totalHeight(WIDTH - 8)
                if (row.mouseDragged(x + 4, rowY, WIDTH - 8, mouseX, mouseY, button)) return true
                rowY += rh
            }
        }
        return false
    }

    fun mouseReleased(mouseX: Int, mouseY: Int, button: Int): Boolean {
        val was = dragging || resizingHeight
        if (dragging && ModuleClickGui.Snapping.enabled) {
            val grid = ModuleClickGui.Snapping.gridSize
            x = ((x + grid / 2) / grid) * grid
            y = ((y + grid / 2) / grid) * grid
            clampToScreen()
        }
        dragging = false
        resizingHeight = false
        rows.forEach { it.mouseReleased(mouseX, mouseY, button) }
        return was
    }

    fun charTyped(chr: Char): Boolean = rows.any { it.charTyped(chr) }
    fun keyPressed(keyCode: Int): Boolean = rows.any { it.keyPressed(keyCode) }
}
