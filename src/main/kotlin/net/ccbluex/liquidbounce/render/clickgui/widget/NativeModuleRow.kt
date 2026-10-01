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
    }

    var expanded: Boolean = false
        private set

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

    fun totalHeight(width: Int): Int {
        if (!expanded) return ROW_HEIGHT
        val settingsWidth = width
        val inner = rowsFor(settingsWidth).sumOf { it.height(settingsWidth) + 2 }
        return ROW_HEIGHT + inner + 8
    }

    fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        hovered = mouseX in x..(x + width) && mouseY in y..(y + ROW_HEIGHT)
        if (hovered) {
            gfx.fill(x, y, x + width, y + ROW_HEIGHT, ClickGuiPalette.MODULE_HOVER_BG)
        }

        val font = Minecraft.getInstance().font
        val textColor = if (module.enabled) ClickGuiPalette.MODULE_ENABLED else ClickGuiPalette.TEXT
        val maxNameWidth = width - 20 - (if (hasSettings) ARROW_ZONE else 12)
        val name = GuiRender2D.ellipsize(gfx, module.name, maxNameWidth)
        gfx.text(font, name, x + 12, y + (ROW_HEIGHT - font.lineHeight) / 2, textColor, false)

        if (hasSettings) {
            val arrowCx = x + width - ARROW_ZONE / 2
            val arrowCy = y + ROW_HEIGHT / 2
            GuiRender2D.icon(gfx, ClickGuiIcons.SETTINGS_EXPAND, arrowCx - 4, arrowCy - 4, 8, ClickGuiPalette.PANEL_TOGGLE_ICON)
        }

        if (expanded) {
            val bodyY = y + ROW_HEIGHT
            val rows = rowsFor(width)
            val bodyH = rows.sumOf { it.height(width) + 2 } + 8
            gfx.fill(x, bodyY, x + width, bodyY + bodyH, if (ModuleClickGui.glassMode) ClickGuiPalette.withAlpha(ClickGuiPalette.MODULE_SETTINGS_BG, 56) else ClickGuiPalette.MODULE_SETTINGS_BG)
            gfx.fill(x, bodyY, x + 4, bodyY + bodyH, ClickGuiPalette.MODULE_SETTINGS_BORDER)

            var rowY = bodyY + 6
            for (row in rows) {
                row.render(gfx, x, rowY, width, mouseX, mouseY)
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
}
