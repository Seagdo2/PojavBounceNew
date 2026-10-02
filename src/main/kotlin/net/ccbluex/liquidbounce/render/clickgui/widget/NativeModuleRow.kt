/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of Module.svelte. Interaction model:
 *   - left click on the name row  -> toggle the module on/off
 *   - right click on the name row -> toggle the settings panel open/closed
 *
 * FIX: ROW_HEIGHT lowered from 30 to 22
 * FIX: text scale applied via pose matrix using ModuleClickGui.fontSize
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
        const val ROW_HEIGHT = 22
        private const val ARROW_ZONE = 36
    }

    var expanded: Boolean = false
        private set

    private var settingRows: List<SettingRow>? = null
    private var hovered = false

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
        return ROW_HEIGHT + inner + 6
    }

    fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        hovered = mouseX in x..(x + width) && mouseY in y..(y + ROW_HEIGHT)
        if (hovered) {
            gfx.fill(x, y, x + width, y + ROW_HEIGHT, ClickGuiPalette.MODULE_HOVER_BG)
        }

        val font = Minecraft.getInstance().font
        val textColor = if (module.enabled) ClickGuiPalette.MODULE_ENABLED else ClickGuiPalette.TEXT
        val maxNameWidth = width - 16 - (if (hasSettings) ARROW_ZONE else 8)
        val name = GuiRender2D.ellipsize(gfx, module.name, maxNameWidth)

        // 应用字体缩放
        val fs = ModuleClickGui.fontSize
        if (fs != 1.0f) {
            gfx.pose().pushMatrix()
            gfx.pose().scale(fs, fs)
            gfx.text(font, name, ((x + 8) / fs).toInt(), ((y + (ROW_HEIGHT - font.lineHeight) / 2) / fs).toInt(), textColor, false)
            gfx.pose().popMatrix()
        } else {
            gfx.text(font, name, x + 8, y + (ROW_HEIGHT - font.lineHeight) / 2, textColor, false)
        }

        if (hasSettings) {
            val arrowCx = x + width - ARROW_ZONE / 2
            val arrowCy = y + ROW_HEIGHT / 2
            GuiRender2D.icon(gfx, ClickGuiIcons.SETTINGS_EXPAND, arrowCx - 4, arrowCy - 4, 8, ClickGuiPalette.PANEL_TOGGLE_ICON)
        }

        if (expanded) {
            val bodyY = y + ROW_HEIGHT
            val rows = rowsFor(width)
            val bodyH = rows.sumOf { it.height(width) + 2 } + 6
            gfx.fill(x, bodyY, x + width, bodyY + bodyH, if (ModuleClickGui.glassMode) ClickGuiPalette.withAlpha(ClickGuiPalette.MODULE_SETTINGS_BG, 56) else ClickGuiPalette.MODULE_SETTINGS_BG)
            gfx.fill(x, bodyY, x + 3, bodyY + bodyH, ClickGuiPalette.MODULE_SETTINGS_BORDER)

            var rowY = bodyY + 4
            for (row in rows) {
                row.render(gfx, x, rowY, width, mouseX, mouseY)
                rowY += row.height(width) + 2
            }
        }
    }

    fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (mouseX !in x..(x + width)) return false

        if (mouseY in y..(y + ROW_HEIGHT)) {
            val arrowZoneStart = x + width - ARROW_ZONE
            if (hasSettings && mouseX >= arrowZoneStart) {
                toggleExpanded()
                return true
            }
            if (button == 1) {
                if (hasSettings) toggleExpanded()
                return true
            }
            if (button == 0) {
                module.enabled = !module.enabled
                return true
            }
            return false
        }

        if (expanded && mouseY > y + ROW_HEIGHT) {
            var rowY = y + ROW_HEIGHT + 4
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
        var rowY = y + ROW_HEIGHT + 4
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
