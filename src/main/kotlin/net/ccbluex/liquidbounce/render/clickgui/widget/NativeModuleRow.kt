/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of Module.svelte. Interaction model copied exactly from the
 * source component:
 *   - left click on the name row  -> toggle the module on/off
 *   - right click on the name row -> toggle the settings panel open/closed
 *     (Module.svelte: `on:contextmenu|preventDefault={toggleExpanded}`)
 *   - the small chevron button on the right is a second way to do the same
 *     right-click action, and stops the click from also toggling the module
 *     (Module.svelte's toggleExpanded() calls `e.stopPropagation()`)
 *   - when expanded, settings render below with a left accent border and a
 *     darker background (module-settings-background / -border-color)
 *
 * Expand/collapse animation: identical approach to NativePanel - a float
 * `expandAnim` (0=collapsed, 1=expanded) is eased every frame and used only
 * for the drawn body height. `totalHeight()` always returns the logical
 * (non-animated) height so layout and hit-testing are never affected by
 * animation state.
 */
package net.ccbluex.liquidbounce.render.clickgui.widget

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiIcons
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.ccbluex.liquidbounce.render.clickgui.settings.SettingRenderer
import net.ccbluex.liquidbounce.render.clickgui.settings.SettingRow
import net.ccbluex.liquidbounce.render.withPush
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

class NativeModuleRow(val module: ClientModule) {

    companion object {
        /** 【尺寸对齐 ClickGuiScreen】行高 30 → 17 (匹配 ITEM_H) */
        const val ROW_HEIGHT = 17
        /** 箭头区按行高比例缩小 40 → 28 */
        private const val ARROW_ZONE = 28
        /** Expand animation easing factor (0..1, higher = faster). */
        private const val EXPAND_EASE = 0.20f
        // 跑马灯状态已移至 GuiRender2D (共享给 SettingRows 使用)
    }

    var expanded: Boolean = false
        private set

    /**
     * Expand animation progress: 0 = collapsed, 1 = expanded.
     * Eased toward the target every frame. Used only for rendering;
     * [totalHeight] always reports the logical height.
     */
    private var expandAnim = 0f
    private var expandAnimTarget = 0f

    private var settingRows: List<SettingRow>? = null
    private var hovered = false

    /** Set by NativePanel.scrollToModule (right-clicking a search result) -
     * draws a fading accent-colored outline around this row for a bit so
     * the user can actually spot it after being scrolled into view. */
    private var highlightUntilMs = 0L

    fun highlight(durationMs: Long = 2500L) {
        highlightUntilMs = System.currentTimeMillis() + durationMs
    }

    /** Module names the config system flags as internal and never shows -
     * mirrors GenericSetting's own `name !== "Bind" && name !== "Hidden"` filter. */
    private fun visibleValues() = module.get().filter { it.name != "Bind" && it.name != "Hidden" }

    val hasSettings: Boolean get() = visibleValues().isNotEmpty()

    private fun rowsFor(width: Int): List<SettingRow> {
        var rows = settingRows
        if (rows == null) {
            rows = SettingRenderer.build(visibleValues())
            settingRows = rows
        }
        return rows
    }

    /** Full (non-animated) height of the settings body (excluding the row itself). */
    private fun settingsBodyHeight(width: Int): Int {
        if (!hasSettings) return 0
        return rowsFor(width).sumOf { it.height(width) + 2 } + 8
    }

    /**
     * Logical (non-animated) total height. Always returns the "real" size
     * regardless of animation state, so layout and hit-testing are stable.
     */
    fun totalHeight(width: Int): Int {
        if (!expanded) return ROW_HEIGHT
        return ROW_HEIGHT + settingsBodyHeight(width)
    }

    /**
     * Height this row occupies in the panel layout right now, using the eased
     * expand progress. Matches the body height drawn in render() exactly, so the
     * panel positions every following row with the same height the user sees.
     */
    fun layoutHeight(width: Int): Int {
        if (expandAnim <= 0f) return ROW_HEIGHT
        return ROW_HEIGHT + (settingsBodyHeight(width).toFloat() * expandAnim).toInt()
    }

    /** Advances the expand animation by one frame. The panel calls this for every
     * row before layout, so rows outside the viewport also animate and report a
     * correct [layoutHeight]. */
    fun tickExpand() {
        expandAnimTarget = if (expanded) 1f else 0f
        expandAnim += (expandAnimTarget - expandAnim) * EXPAND_EASE
        if (kotlin.math.abs(expandAnimTarget - expandAnim) < 0.01f) expandAnim = expandAnimTarget
    }

    fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        // expandAnim is advanced by tickExpand(), called from the parent panel.
        // The panel owns the stable viewport scissor for Minecraft 26.3.

        hovered = mouseX in x..(x + width) && mouseY in y..(y + ROW_HEIGHT)
        if (hovered) {
            gfx.fill(x, y, x + width, y + ROW_HEIGHT, ClickGuiPalette.MODULE_HOVER_BG)
        }

        val highlightRemaining = highlightUntilMs - System.currentTimeMillis()
        if (highlightRemaining > 0) {
            val fadeMs = 400f
            val alpha = if (highlightRemaining < fadeMs) (highlightRemaining / fadeMs).coerceIn(0f, 1f) else 1f
            val highlightColor = ClickGuiPalette.withAlpha(ClickGuiPalette.MODULE_ENABLED, (255 * alpha).toInt())
            GuiRender2D.strokeRoundedRect(gfx, x, y, width, ROW_HEIGHT, 4, 2, highlightColor)
        }

        val font = Minecraft.getInstance().font
        val textColor = if (module.enabled) ClickGuiPalette.MODULE_ENABLED else ClickGuiPalette.TEXT
        val maxNameWidth = width - 20 - (if (hasSettings) ARROW_ZONE else 12)
        val textX = x + 12
        val textY = y + (ROW_HEIGHT - font.lineHeight) / 2
        // 【翻译】显示名用翻译名 (module.name 仍是原英文, 用于配置/命令/布局存储)
        val displayName = net.ccbluex.liquidbounce.features.module.modules.misc.ModuleTranslation.t(module.name)
        val textW = font.width(displayName)

        if (textW <= maxNameWidth) {
            // 文字不超出 → 直接渲染
            gfx.text(font, displayName, textX, textY, textColor, false)
        } else {
            // 【跑马灯】超出范围 → 电子屏式循环滚动 (4秒停 + 4秒滚, 如此往复)
            GuiRender2D.renderMarqueeText(gfx, font, displayName, textX, textY, maxNameWidth, y, ROW_HEIGHT, textColor)
        }

        if (hasSettings) {
            val arrowCx = x + width - ARROW_ZONE / 2
            val arrowCy = y + ROW_HEIGHT / 2
            // Bug fix: the chevron never rotated with expand/collapse state.
            // Module.svelte's CSS has `.expand-arrow-icon { transform:
            // rotate(-90deg) }` by default and `rotate(0)` once `.expanded` -
            // i.e. the icon's native art (a downward V) IS the expanded
            // state, and collapsed is that same art turned -90deg. Reusing
            // expandAnim (already eased for the body-height animation) gets
            // the rotation animating in sync for free.
            val angle = -90f + 90f * expandAnim
            GuiRender2D.iconRotated(gfx, ClickGuiIcons.SETTINGS_EXPAND, arrowCx - 4, arrowCy - 4, 8, ClickGuiPalette.PANEL_TOGGLE_ICON, angle)
        }

        // Animated body height for rendering only. Uses the full (un-collapsed)
        // settings body height multiplied by the animation progress, so the
        // body smoothly shrinks/grows instead of snapping.
        val fullBodyH = settingsBodyHeight(width)
        val animBodyH = (fullBodyH.toFloat() * expandAnim).toInt().coerceAtLeast(0)
        if (animBodyH > 0) {
            val bodyY = y + ROW_HEIGHT
            gfx.fill(x, bodyY, x + width, bodyY + animBodyH, if (ModuleClickGui.glassMode) ClickGuiPalette.withAlpha(ClickGuiPalette.MODULE_SETTINGS_BG, 56) else ClickGuiPalette.MODULE_SETTINGS_BG)
            gfx.fill(x, bodyY, x + 4, bodyY + animBodyH, ClickGuiPalette.MODULE_SETTINGS_BORDER)

            // Expand/collapse: NO nested scissor (nested push can become a 0x0
            // intersection after pose-scale + panel clip, which crashes the
            // render pass; disableScissor then risks stack underflow and
            // leaves the GUI broken for the rest of the session).
            // Only draw setting rows fully inside animBodyH; layoutHeight
            // still eases so siblings slide. Circles stay unscaled.
            val rows = rowsFor(width)
            val limitY = bodyY + animBodyH
            var rowY = bodyY + 6
            for (row in rows) {
                val rh = row.height(width)
                if (rowY >= limitY) break
                if (rowY + rh <= limitY) {
                    row.render(gfx, x, rowY, width, mouseX, mouseY)
                }
                rowY += rh + 2
            }
        }
    }

    /** Returns true if this row consumed the click. [width] must match what
     * was passed to render() so hit-testing lines up with what's drawn. */
    fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (mouseX !in x..(x + width)) return false

        if (mouseY in y..(y + ROW_HEIGHT)) {
            val arrowZoneStart = x + width - ARROW_ZONE
            if (hasSettings && mouseX >= arrowZoneStart) {
                toggleExpanded()
                return true
            }
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) { // right click anywhere else on the row
                if (hasSettings) toggleExpanded()
                return true
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) { // left click -> toggle module
                module.enabled = !module.enabled
                return true
            }
            return false
        }

        if (expanded && mouseY > y + ROW_HEIGHT) {
            var rowY = y + ROW_HEIGHT + 6
            for (row in rowsFor(width)) {
                if (mouseY in rowY..(rowY + row.height(width))) {
                    return row.mouseClicked(x, rowY, width, mouseX, mouseY, button)
                }
                rowY += row.height(width) + 2
            }
        }
        return false
    }

    fun mouseDragged(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (!expanded) return false
        var rowY = y + ROW_HEIGHT + 6
        for (row in rowsFor(width)) {
            if (row.mouseDragged(x, rowY, width, mouseX, mouseY, button)) return true
            rowY += row.height(width) + 2
        }
        return false
    }

    fun mouseReleased(mouseX: Int, mouseY: Int, button: Int): Boolean {
        settingRows?.forEach { it.mouseReleased(mouseX, mouseY, button) }
        return false
    }

    fun charTyped(chr: Char): Boolean = settingRows?.any { it.isFocused() && it.charTyped(chr) } ?: false
    fun keyPressed(keyCode: Int): Boolean = settingRows?.any { it.isFocused() && it.keyPressed(keyCode) } ?: false

    // 跑马灯渲染已移至 GuiRender2D.renderMarqueeText (共享给 SettingRows 使用)

    private fun toggleExpanded() {
        expanded = !expanded
    }

    /** Restores the expanded state from a saved layout snapshot (see
     * ClickGuiLayoutStore). Only meaningful for rows that [hasSettings];
     * rows without settings are never expandable, so callers filter first. */
    fun setExpanded(value: Boolean) {
        expanded = value
        // snap the animation so restored rows don't animate on first open
        expandAnim = if (value) 1f else 0f
        expandAnimTarget = expandAnim
    }
}