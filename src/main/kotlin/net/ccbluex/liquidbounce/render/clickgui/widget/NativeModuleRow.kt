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

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiIcons
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.ccbluex.liquidbounce.render.clickgui.settings.SettingRenderer
import net.ccbluex.liquidbounce.render.clickgui.settings.SettingRow
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

class NativeModuleRow(val module: ClientModule) {

    companion object {
        const val ROW_HEIGHT = 30
        private const val ARROW_ZONE = 40
        /** Expand animation easing factor (0..1, higher = faster). */
        private const val EXPAND_EASE = 0.20f
    }

    var expanded: Boolean = false
        private set

    /** Set by the search-bar locate flow (right-click a result); draws an accent box like Module.svelte `.highlight`. */
    var highlighted: Boolean = false

    /**
     * Expand animation progress: 0 = collapsed, 1 = expanded.
     * Eased toward the target every frame. Used only for rendering;
     * [totalHeight] always reports the logical height.
     */
    private var expandAnim = 0f
    private var expandAnimTarget = 0f

    private var settingRows: List<SettingRow>? = null
    private var hovered = false

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
     * Total height including the *animated* settings body. Reporting the
     * eased height (not a snap to 0 when [expanded] flips) is what lets
     * sibling module rows slide up/down with the collapse/expand animation
     * instead of jumping while the settings content is still visible.
     */
    fun totalHeight(width: Int): Int {
        tickExpandAnim()
        val animH = (settingsBodyHeight(width).toFloat() * expandAnim).toInt()
        return ROW_HEIGHT + animH
    }

    /** Advance expand/collapse ease. Called from [totalHeight] so layout and
     * drawing stay in sync even when a row is culled from the visible pass. */
    private var expandAnimTickStamp = -1L
    private fun tickExpandAnim() {
        // At most one ease step per millisecond-bucket so totalHeight+render
        // in the same frame do not double-speed the animation.
        val stamp = System.nanoTime() / 1_000_000L
        if (stamp == expandAnimTickStamp) return
        expandAnimTickStamp = stamp
        expandAnimTarget = if (expanded) 1f else 0f
        expandAnim += (expandAnimTarget - expandAnim) * EXPAND_EASE
        if (kotlin.math.abs(expandAnimTarget - expandAnim) < 0.01f) expandAnim = expandAnimTarget
    }

    fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        // expandAnim is advanced in totalHeight(), which the panel always
        // calls before render for layout - keeps height and clip in sync.
        // Still tick here as a fallback when render is invoked without a prior
        // totalHeight (e.g. tests / future callers).
        tickExpandAnim()

        hovered = mouseX in x..(x + width) && mouseY in y..(y + ROW_HEIGHT)
        if (hovered) {
            gfx.fill(x, y, x + width, y + ROW_HEIGHT, ClickGuiPalette.MODULE_HOVER_BG)
        }
        // Web Module.svelte `.highlight::before` - 2px accent border around the name row
        if (highlighted) {
            GuiRender2D.strokeRoundedRect(gfx, x + 1, y + 1, width - 2, ROW_HEIGHT - 2, 2, 2, ClickGuiPalette.ACCENT)
        }

        val font = Minecraft.getInstance().font
        val textColor = if (module.enabled) ClickGuiPalette.MODULE_ENABLED else ClickGuiPalette.TEXT
        val maxNameWidth = width - 20 - (if (hasSettings) ARROW_ZONE else 12)
        val name = GuiRender2D.ellipsize(gfx, module.name, maxNameWidth)
        gfx.text(font, name, x + 12, y + (ROW_HEIGHT - font.lineHeight) / 2, textColor, false)

        if (hasSettings) {
            val arrowCx = x + width - ARROW_ZONE / 2
            val arrowCy = y + ROW_HEIGHT / 2
            // Web Module.svelte: collapsed = rotate(-90deg), expanded = rotate(0)
            val rot = if (expanded) 0f else -90f
            val arrowColor = if (expanded) ClickGuiPalette.PANEL_TOGGLE_ICON else ClickGuiPalette.withAlpha(ClickGuiPalette.PANEL_TOGGLE_ICON, 128)
            GuiRender2D.icon(gfx, ClickGuiIcons.SETTINGS_EXPAND, arrowCx - 4, arrowCy - 4, 8, arrowColor, rot)
        }

        // Animated body height for rendering only. Uses the full (un-collapsed)
        // settings body height multiplied by the animation progress, so the
        // body smoothly shrinks/grows instead of snapping.
        val fullBodyH = settingsBodyHeight(width)
        val animBodyH = (fullBodyH.toFloat() * expandAnim).toInt()
        if (animBodyH > 0 && width > 0) {
            val bodyY = y + ROW_HEIGHT
            gfx.fill(x, bodyY, x + width, bodyY + animBodyH, if (ModuleClickGui.glassMode) ClickGuiPalette.withAlpha(ClickGuiPalette.MODULE_SETTINGS_BG, 56) else ClickGuiPalette.MODULE_SETTINGS_BG)
            gfx.fill(x, bodyY, x + 4, bodyY + animBodyH, ClickGuiPalette.MODULE_SETTINGS_BORDER)

            // 防闪退：不使用嵌套 enableScissor（与父级 scissor 交集可能为 0x0 导致崩溃）
            // 改为手动跳过不可见的行
            val rows = rowsFor(width)
            var rowY = bodyY + 6
            for (row in rows) {
                if (rowY < bodyY + animBodyH) {
                    row.render(gfx, x, rowY, width, mouseX, mouseY)
                }
                rowY += row.height(width) + 2
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
            if (button == 1) { // right click anywhere else on the row
                if (hasSettings) toggleExpanded()
                return true
            }
            if (button == 0) { // left click -> toggle module
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