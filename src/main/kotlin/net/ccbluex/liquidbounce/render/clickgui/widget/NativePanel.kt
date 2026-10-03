/*
 * This file is part of LiquidBounce (https://github.com/LiquidBounce)
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
 * Two changes from the first revision, on request:
 *   - the body height is now driven by a single ModuleClickGui "PanelHeight"
 *     setting (default 400px, clamped 60-900) instead of the per-panel
 *     bottom-right resize grip. That grip's three diagonal strokes are gone
 *     with it, so each panel's footer is clean again - the web source has no
 *     equivalent control at all, so this is now a faithful match rather than
 *     a native-only addition.
 *   - scrolling is still smoothed (eased toward a target) and accumulates in
 *     float space instead of truncating to Int per-event, which was dropping
 *     small/fractional wheel deltas entirely - see the fixed bug note on
 *     `mouseScrolled` below.
 *
 * Collapse/expand animation: the body height is animated by easing a float
 * (`collapseAnim`) toward 0 (collapsed) or 1 (expanded) every frame, then
 * multiplying by the full body height. This is kept completely separate
 * from `totalHeight()`/`contentHeight()` which always report the *logical*
 * (non-animated) height, so hit-testing and layout never see a transient
 * value. The only place the animated height is used is the actual drawn
 * body rect + scissor rect in `render()`.
 */
package net.ccbluex.liquidbounce.render.clickgui.widget

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiIcons
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.ccbluex.liquidbounce.render.clickgui.PanelSnapshot
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.Minecraft
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
        private const val RADIUS = 5
        private const val SCROLL_PX_PER_NOTCH = 42f
        private const val SCROLL_EASE = 0.35f
        /** Collapse animation easing factor (0..1, higher = faster). */
        private const val COLLAPSE_EASE = 0.20f
    }

    val rows: List<NativeModuleRow> = modules.sortedBy { it.name }.map { NativeModuleRow(it) }

    /**
     * Whether the module list body is collapsed (header only) or expanded.
     * Publicly settable so NativeClickGuiScreen can restore the saved layout
     * (panel.restore / search locate) without going through a click event.
     * render() recomputes the collapse-animation target from this every
     * frame, so an external write still animates smoothly instead of snapping.
     */
    var collapsed = false
        public set

    /**
     * Collapse animation progress: 0 = fully collapsed, 1 = fully expanded.
     * Eased toward the target every frame. Kept separate from all
     * height/scroll logic so it can never produce inconsistent values.
     */
    private var collapseAnim = 1f
    private var collapseAnimTarget = 1f

    // --- scrolling: accumulated as float, eased toward target each frame ---
    private var scrollTarget = 0f
    private var scrollAnimated = 0f

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private fun contentHeight(): Int = rows.sumOf { it.totalHeight(WIDTH - 8) }

    /** Full (un-animated) body height: capped at the user's [ModuleClickGui.panelHeight]
     * setting, never more than the content actually needs, never negative. */
    private fun fullBodyHeight(): Int =
        contentHeight().coerceAtMost(ModuleClickGui.panelHeight).coerceAtLeast(0)

    /**
     * Logical (non-animated) total height. Used for hit-testing, layout
     * store, and anything that needs to know the panel's "real" size
     * regardless of animation state. The animated height is only used
     * inside `render()` for the drawn body + scissor rect.
     */
    fun totalHeight(): Int = HEADER_HEIGHT + if (collapsed) 0 else fullBodyHeight()

    /** Animated body height for rendering only. Uses the full (un-collapsed)
     * body height multiplied by the animation progress, so the body
     * smoothly shrinks/grows instead of snapping when `collapsed` toggles. */
    private fun animatedBodyHeight(): Int {
        val full = fullBodyHeight()
        return (full.toFloat() * collapseAnim).toInt().coerceAtLeast(0)
    }

    fun render(gfx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // ease the collapse animation toward its target (1 = expanded, 0 = collapsed)
        collapseAnimTarget = if (collapsed) 0f else 1f
        collapseAnim += (collapseAnimTarget - collapseAnim) * COLLAPSE_EASE
        if (abs(collapseAnimTarget - collapseAnim) < 0.01f) collapseAnim = collapseAnimTarget

        // ease the visible scroll toward wherever the wheel last left it -
        // this is what makes scrolling feel smooth instead of snapping
        scrollAnimated += (scrollTarget - scrollAnimated) * SCROLL_EASE
        if (abs(scrollTarget - scrollAnimated) < 0.5f) scrollAnimated = scrollTarget
        val scroll = scrollAnimated.toInt()

        val glass = ModuleClickGui.glassMode
        val headerBg = if (glass) ClickGuiPalette.GLASS_PANEL_HEADER_BG else ClickGuiPalette.PANEL_HEADER_BG
        val bodyBg = if (glass) ClickGuiPalette.GLASS_PANEL_BODY_BG else ClickGuiPalette.PANEL_BODY_BG

        val bh = animatedBodyHeight()
        val h = HEADER_HEIGHT + bh
        GuiRender2D.dropShadow(gfx, x, y, WIDTH, h, RADIUS, ClickGuiPalette.PANEL_SHADOW)

        // header (fully rounded when no body is visible since it IS the whole
        // panel; when a body follows, square off the header's bottom corners
        // so it sits flush against the body instead of showing a rounded seam)
        val bodyVisible = bh >= 1
        GuiRender2D.fillRoundedRect(gfx, x, y, WIDTH, HEADER_HEIGHT, RADIUS, headerBg)
        if (bodyVisible) {
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
        // Match web panel toggle direction: collapsed points sideways (-90), expanded points down (0)
        val chevronRot = if (collapsed) -90f else 0f
        GuiRender2D.icon(gfx, ClickGuiIcons.SETTINGS_EXPAND, chevronCx - 4, chevronCy - 4, 8, ClickGuiPalette.PANEL_TOGGLE_ICON, chevronRot)

        if (!bodyVisible) {
            if (glass) GuiRender2D.strokeRoundedRect(gfx, x, y, WIDTH, HEADER_HEIGHT, RADIUS, 1, ClickGuiPalette.GLASS_EDGE_HIGHLIGHT)
            return
        }

        val bodyY = y + HEADER_HEIGHT
        // Bottom corners are drawn square rather than rounded here: doing a
        // correct punch-through round on top of an already-opaque rect needs
        // either a stencil or a per-corner-radius primitive, and this file
        // deliberately only uses the simple, always-correct fillRoundedRect
        // (see GuiRender2D's header comment on why). Visually this reads as
        // a ~5px square corner instead of the web's rounded one - see README.
        gfx.fill(x, bodyY, x + WIDTH, bodyY + bh, bodyBg)
        if (glass) GuiRender2D.frostOverlay(gfx, x, bodyY, WIDTH, bh)

        // During collapse animation, clip the body to the animated height
        // so content slides out of view instead of overlapping the next panel.
        // Use a guard to avoid enabling a zero/negative scissor rect.
        // 防闪退：scissor 矩形必须裁剪到屏幕可见范围内，
        // 否则离屏面板会产生 0x0 矩形导致 FrontendRenderPass 崩溃
        val mc = Minecraft.getInstance()
        val s = ModuleClickGui.scale
        val screenW = (mc.window.guiScaledWidth / s).toInt().coerceAtLeast(1)
        val screenH = (mc.window.guiScaledHeight / s).toInt().coerceAtLeast(1)
        val clipX0 = maxOf(x, 0)
        val clipY0 = maxOf(bodyY, 0)
        val clipX1 = minOf(x + WIDTH, screenW)
        val clipY1 = minOf(bodyY + bh, screenH)
        if (bh > 0 && clipX1 > clipX0 && clipY1 > clipY0) {
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

        // The bottom-right corner is intentionally empty now: the first
        // revision drew three short diagonal "drag to resize" strokes here
        // for a per-panel body-height grip, but that has been replaced by a
        // single ModuleClickGui "PanelHeight" setting, so the footer is
        // clean (matching the web source, which has no such control).
    }

    private fun clampScroll() {
        val maxScroll = (contentHeight() - fullBodyHeight()).coerceAtLeast(0).toFloat()
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollAnimated = scrollAnimated.coerceIn(0f, maxScroll)
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
        if (dragging) {
            x = mouseX - dragOffsetX
            y = mouseY - dragOffsetY
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
        val was = dragging
        if (dragging && ModuleClickGui.Snapping.enabled) {
            val grid = ModuleClickGui.Snapping.gridSize
            x = ((x + grid / 2) / grid) * grid
            y = ((y + grid / 2) / grid) * grid
        }
        dragging = false
        rows.forEach { it.mouseReleased(mouseX, mouseY, button) }
        return was
    }

    fun charTyped(chr: Char): Boolean = rows.any { it.charTyped(chr) }
    fun keyPressed(keyCode: Int): Boolean = rows.any { it.keyPressed(keyCode) }

    // ----------------------------------------------------------- layout I/O

    /** Captures this panel's restorable state for ClickGuiLayoutStore. */
    fun snapshot(): PanelSnapshot = PanelSnapshot(
        x = x,
        y = y,
        collapsed = collapsed,
        scroll = scrollTarget,
        expanded = rows.filter { it.hasSettings }.associate { it.module.name to it.expanded },
    )

    /** Applies a previously saved [PanelSnapshot] (no-op if null, e.g. first
     * ever open or a panel that had no saved state). Called once from
     * NativeClickGuiScreen.init() before the first render. */
    fun restore(s: PanelSnapshot?) {
        if (s == null) return
        x = s.x
        y = s.y
        collapsed = s.collapsed
        for (row in rows) {
            if (row.hasSettings) row.setExpanded(s.expanded[row.module.name] == true)
        }
        scrollTarget = s.scroll.coerceAtLeast(0f)
        scrollAnimated = scrollTarget
        // snap the collapse animation so restored panels don't animate on open
        collapseAnim = if (collapsed) 0f else 1f
        collapseAnimTarget = collapseAnim
        clampScroll()
    }

    /** Keeps at least the header reachable when restored positions came from
     * a different screen resolution than the one we're opening on now. */
    fun clampToScreen(screenW: Int, screenH: Int) {
        val maxX = (screenW - 60).coerceAtLeast(0)
        val maxY = (screenH - HEADER_HEIGHT).coerceAtLeast(0)
        x = x.coerceIn(0, maxX)
        y = y.coerceIn(0, maxY)
    }

    /** Whether this panel owns [module] (by identity). Used by search locate. */
    fun containsModule(module: ClientModule): Boolean = rows.any { it.module === module }

    /**
     * Expand the panel if needed and scroll so [module]'s row is centered in
     * the visible body - mirrors Panel.svelte + Module.svelte highlight flow
     * (`panelConfig.expanded = true` then `scrollIntoView({ block: "center" })`).
     */
    fun scrollToModule(module: ClientModule) {
        collapsed = false
        collapseAnimTarget = 1f
        var offset = 0
        for (row in rows) {
            if (row.module === module) {
                val body = fullBodyHeight()
                val target = (offset - (body - NativeModuleRow.ROW_HEIGHT) / 2).toFloat()
                scrollTarget = target
                clampScroll()
                // snap animated scroll too so the locate is immediate
                scrollAnimated = scrollTarget
                row.highlighted = true
                return
            }
            offset += row.totalHeight(WIDTH - 8)
        }
    }

    /** Clears highlight on every module row (after a locate or on next open). */
    fun clearHighlights() {
        rows.forEach { it.highlighted = false }
    }

}