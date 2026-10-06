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

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiIcons
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.ccbluex.liquidbounce.render.clickgui.PanelSnapshot
import net.ccbluex.liquidbounce.render.withPush

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
        val WIDTH: Int get() = ModuleClickGui.panelWidth
        /** 【尺寸对齐 ClickGuiScreen】标题栏 30 → 22 (匹配 HEADER_H) */
        const val HEADER_HEIGHT = 22
        /** 【尺寸对齐 ClickGuiScreen】圆角 5 → 6 (匹配 CORNER) */
        private const val RADIUS = 6
        private const val SCROLL_PX_PER_NOTCH = 42f
        private const val SCROLL_EASE = 0.35f
        /** Collapse animation easing factor (0..1, higher = faster). */
        private const val COLLAPSE_EASE = 0.20f
    }

    val rows: List<NativeModuleRow> = modules.sortedBy { it.name }.map { NativeModuleRow(it) }

    var collapsed = false
        private set

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
    // ======== 【FIX】滚动条拖动状态 ========
    private var draggingScrollbar = false
    private var scrollbarDragStartY = 0f
    private var scrollbarDragStartScroll = 0f

    private fun contentHeight(): Int = rows.sumOf { it.totalHeight(WIDTH - 8) }

    /** Full (un-animated) body height: capped at the user's [ModuleClickGui.panelHeight]
     * setting, never more than the content actually needs, never negative. */
    private fun fullBodyHeight(): Int =
        contentHeight().coerceAtMost(ModuleClickGui.panelHeight).coerceAtLeast(0)

    /**
     * Body height matching what the rows occupy right now: the sum of their
     * animated layout heights, capped at PanelHeight. Once every row has settled
     * this equals [fullBodyHeight]; while rows animate, the body background and
     * scissor grow and shrink in step with them instead of jumping ahead.
     */
    private fun liveBodyHeight(): Int =
        rows.sumOf { it.layoutHeight(WIDTH - 8) }.coerceAtMost(ModuleClickGui.panelHeight).coerceAtLeast(0)

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

    /**
     * [uiScale] is the screen's pose scale. [logicalW] / [logicalH] are the
     * visible area in pre-scale units (real size / scale). The scissor is built
     * in those logical units and clamped to them, so the usable area always
     * covers the whole screen regardless of Scale.
     */
    fun render(gfx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, uiScale: Float, logicalW: Int, logicalH: Int) {
        // 【跑马灯驱动】每帧更新一次: 滚动中暂停计时, 停止后恢复 (GuiRender2D 共享状态)
        val isScrolling = abs(scrollTarget - scrollAnimated) > 0.5f
        GuiRender2D.tickMarquee(isScrolling)
        // Every row advances its expand animation once per frame, before any
        // layout runs, so the rows below an expanding row move in step with it.
        for (row in rows) row.tickExpand()

        // Animate only pose geometry; keep the scissor viewport fixed at the
        // logical body bounds for the entire transition.
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

        val logicalBodyHeight = liveBodyHeight()
        val bh = (logicalBodyHeight * collapseAnim).toInt().coerceAtLeast(0)
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
        // 【翻译】分类标题显示用翻译名 (icon查找仍用英文原tag)
        val displayTag = net.ccbluex.liquidbounce.features.module.modules.misc.ModuleTranslation.t(category.tag)
        gfx.text(font, displayTag, textX, y + (HEADER_HEIGHT - font.lineHeight) / 2, ClickGuiPalette.TEXT, false)

        val chevronCx = x + WIDTH - 18
        val chevronCy = y + HEADER_HEIGHT / 2
        // Bug fix: same issue and same fix as NativeModuleRow's expand arrow -
        // the icon's native art is the "expanded" (not collapsed) state per
        // Module.svelte's CSS (`rotate(-90deg)` by default, `rotate(0)` when
        // `.expanded`), so the panel chevron needs the same -90/0 flip for
        // collapsed/expanded. No eased animation here (unlike the module
        // row, this panel has no existing progress float to reuse) - a
        // direct flip is the minimal, correct fix.
        val chevronAngle = if (collapsed) -90f else 0f
        GuiRender2D.iconRotated(gfx, ClickGuiIcons.SETTINGS_EXPAND, chevronCx - 4, chevronCy - 4, 8, ClickGuiPalette.PANEL_TOGGLE_ICON, chevronAngle)

        if (!bodyVisible && collapseAnim == 0f) {
            if (glass) GuiRender2D.strokeRoundedRect(gfx, x, y, WIDTH, HEADER_HEIGHT, RADIUS, 1, ClickGuiPalette.GLASS_EDGE_HIGHLIGHT)
            return
        }

        val bodyY = y + HEADER_HEIGHT
        // Bottom corners are drawn square rather than rounded here: doing a
        // correct punch-through round on top of an already-opaque rect needs
        // either a stencil or a per-corner-radius primitive, and this file
        // deliberately only uses the simple, always-correct fillRoundedRect.
        // Keep the body background fixed while the panel contents animate.
        // Fill + content without non-uniform Y scale (breaks circle shader).
        // Clip to animated [bh] so collapse still slides content away.
        // Scissor args are logical coords; FrontendRenderPass requires size > 0 (MC 26.3).
        if (bh > 0) {
            gfx.fill(x, bodyY, x + WIDTH, bodyY + bh, bodyBg)
            if (glass) GuiRender2D.frostOverlay(gfx, x, bodyY, WIDTH, bh)
        }

        // Scissor must never be 0x0 after pose scale (RenderPass hard-crash).
        // Track push so we never pop an empty stack (IllegalStateException).
        val clipX0 = x.coerceIn(0, logicalW)
        val clipY0 = bodyY.coerceIn(0, logicalH)
        val clipX1 = (x + WIDTH).coerceIn(0, logicalW)
        val clipY1 = (bodyY + bh).coerceIn(0, logicalH)
        val clipW = clipX1 - clipX0
        val clipH = clipY1 - clipY0
        val clipFits = bh > 0 && clipW >= 1 && clipH >= 1 &&
            clipW * uiScale >= 2f && clipH * uiScale >= 2f &&
            uiScale.isFinite() && uiScale > 0f
        var scissorPushed = false
        try {
            if (clipFits) {
                gfx.enableScissor(clipX0, clipY0, clipX1, clipY1)
                scissorPushed = true
            }
            // 【FIX 穿模】只在 scissor 成功建立后才渲染行; 否则行会溢出到标题栏蓝线上方
            if (bh > 0 && scissorPushed) {
                var rowY = bodyY - scroll
                for (row in rows) {
                    val rh = row.layoutHeight(WIDTH - 8)
                    // Skip rows fully outside the visible body (also when
                    // scissor was skipped to avoid 0x0).
                    if (rowY + rh >= bodyY && rowY <= bodyY + bh) {
                        try {
                            row.render(gfx, x + 4, rowY, WIDTH - 8, mouseX, mouseY)
                        } catch (_: Throwable) {
                            // One bad row must not kill the whole ClickGUI.
                        }
                    }
                    rowY += rh
                }
            }
        } finally {
            if (scissorPushed) {
                try {
                    gfx.disableScissor()
                } catch (_: IllegalStateException) {
                    // stack already empty — ignore
                }
            }
        }

        clampScroll()

        // glass mode's "light catching the edge" cue - a thin, bright,
        // translucent outline around the whole panel, drawn last so it sits
        // on top of the frost/content rather than getting scissored away
        if (glass) {
            GuiRender2D.strokeRoundedRect(gfx, x, y, WIDTH, h, RADIUS, 1, ClickGuiPalette.GLASS_EDGE_HIGHLIGHT)
        }

        // ======== 【FIX】右侧滚动条 (细条, 可点击拖动) ========
        val contentH = contentHeight()
        val maxScrollF = (contentH - fullBodyHeight()).coerceAtLeast(0).toFloat()
        if (maxScrollF > 0.5f && bh > 4) {
            val trackX = x + WIDTH - 3   // 距右边缘3px, 宽2px (细)
            val trackY = bodyY + 2
            val trackH = bh - 4
            // 轨道 (半透明白)
            gfx.fill(trackX, trackY, trackX + 2, trackY + trackH, 0x28FFFFFF)
            // 滑块 (ACCENT 色, 位置按滚动比例)
            val thumbH = (trackH * fullBodyHeight() / contentH).coerceAtLeast(12f)
            val maxThumbY = trackY + trackH - thumbH
            val thumbY = if (maxScrollF > 0f) trackY + (scrollAnimated / maxScrollF) * (trackH - thumbH) else trackY
            val thumbColor = if (draggingScrollbar) ClickGuiPalette.ACCENT else 0x586688FF.toInt()
            gfx.fill(trackX, thumbY.toInt(), trackX + 2, (thumbY + thumbH).toInt(), thumbColor)
        }
    }

    /**
     * Right-clicking a search result calls this (via NativeClickGuiScreen):
     * expand the panel if needed, scroll so [module]'s row is visible, and
     * flash a highlight outline on it. The scroll eases in smoothly on its
     * own via the normal per-frame animation in render() - this only sets
     * the target, same as a mouse-wheel event would.
     */
    fun scrollToModule(module: ClientModule) {
        collapsed = false
        var offset = 0
        for (row in rows) {
            if (row.module === module) {
                row.highlight()
                scrollTarget = (offset - 8).toFloat()
                clampScroll()
                return
            }
            offset += row.layoutHeight(WIDTH - 8)
        }
    }

    private fun clampScroll() {
        val maxScroll = (contentHeight() - fullBodyHeight()).coerceAtLeast(0).toFloat()
        if (!scrollTarget.isFinite()) scrollTarget = 0f
        if (!scrollAnimated.isFinite()) scrollAnimated = 0f
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
        val delta = (amount * SCROLL_PX_PER_NOTCH).toFloat()
        if (delta.isFinite()) {
            scrollTarget -= delta
        }
        clampScroll()
        return true
    }

    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (mouseX !in x..(x + WIDTH)) return false

        // ======== 【FIX】滚动条点击检测 (右侧3px区域, MC 26.3 SDL: 左键=1) ========
        if (!collapsed && button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            val sbX = x + WIDTH - 3
            if (mouseX >= sbX && mouseY in (y + HEADER_HEIGHT)..(y + HEADER_HEIGHT + liveBodyHeight())) {
                val contentH = contentHeight()
                val maxScrollF = (contentH - fullBodyHeight()).coerceAtLeast(0).toFloat()
                if (maxScrollF > 0.5f) {
                    draggingScrollbar = true
                    scrollbarDragStartY = mouseY.toFloat()
                    scrollbarDragStartScroll = scrollTarget
                    return true
                }
            }
        }

        if (mouseY in y..(y + HEADER_HEIGHT)) {
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                collapsed = !collapsed
                return true
            }
            val chevronZone = x + WIDTH - 26
            if (mouseX >= chevronZone) {
                collapsed = !collapsed
                return true
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
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
                val rh = row.layoutHeight(WIDTH - 8)
                if (mouseY in rowY..(rowY + rh)) {
                    return row.mouseClicked(x + 4, rowY, WIDTH - 8, mouseX, mouseY, button)
                }
                rowY += rh
            }
        }
        return false
    }

    fun mouseDragged(mouseX: Int, mouseY: Int, button: Int, dragX: Double, dragY: Double): Boolean {
        // ======== 【FIX】滚动条拖动: 按比例映射到滚动目标 ========
        if (draggingScrollbar) {
            val contentH = contentHeight()
            val bh = fullBodyHeight()
            val trackH = (bh - 4).coerceAtLeast(1f)
            val maxScrollF = (contentH - bh).coerceAtLeast(0).toFloat()
            if (maxScrollF > 0.5f && trackH > 0) {
                val dy = mouseY - scrollbarDragStartY
                val ratio = dy / trackH
                scrollTarget = (scrollbarDragStartScroll + ratio * maxScrollF).coerceIn(0f, maxScrollF)
            }
            return true
        }
        if (dragging) {
            x = mouseX - dragOffsetX
            y = mouseY - dragOffsetY
            return true
        }
        if (!collapsed) {
            val scroll = scrollAnimated.toInt()
            var rowY = y + HEADER_HEIGHT - scroll
            for (row in rows) {
                val rh = row.layoutHeight(WIDTH - 8)
                if (row.mouseDragged(x + 4, rowY, WIDTH - 8, mouseX, mouseY, button)) return true
                rowY += rh
            }
        }
        return false
    }

    fun mouseReleased(mouseX: Int, mouseY: Int, button: Int): Boolean {
        val was = dragging || draggingScrollbar
        if (dragging && ModuleClickGui.Snapping.enabled) {
            val grid = ModuleClickGui.Snapping.gridSize
            x = ((x + grid / 2) / grid) * grid
            y = ((y + grid / 2) / grid) * grid
        }
        dragging = false
        draggingScrollbar = false
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
        val sc = s.scroll
        scrollTarget = if (sc.isFinite() && sc >= 0f) sc else 0f
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
}