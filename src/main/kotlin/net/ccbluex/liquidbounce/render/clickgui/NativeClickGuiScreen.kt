/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of ClickGui.svelte: no dimmed backdrop, one independently
 * draggable+collapsible NativePanel per ModuleCategory, plus a floating
 * centered NativeSearchBar on top of everything.
 *
 * FIX: button API changed from event.buttonInfo.button to event.button()
 * FIX: panels now default to collapsed, stacked vertically in one column
 */
package net.ccbluex.liquidbounce.render.clickgui

import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.widget.NativePanel
import net.ccbluex.liquidbounce.render.clickgui.widget.NativeSearchBar
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class NativeClickGuiScreen : Screen(Component.literal("LiquidBounce")) {

    private val panels = mutableListOf<NativePanel>()
    private val searchBar = NativeSearchBar { ModuleManager }
    private var activePanel: NativePanel? = null

    fun isSearchFocused(): Boolean = searchBar.focused

    private fun scale(): Float = ModuleClickGui.scale.coerceIn(0.5f, 2f)

    private fun toLogical(v: Double, s: Float): Int = (v / s).toInt()

    override fun init() {
        super.init()
        if (panels.isEmpty()) {
            buildPanels()
            searchBar.focused = ModuleClickGui.searchBarAutoFocus
        }
    }

    /**
     * FIX: panels default to collapsed=true, stacked vertically in a single column.
     * Each panel is directly below the previous one, not side-by-side.
     */
    private fun buildPanels() {
        val grouped = ModuleManager.groupBy { it.category }
        val marginX = 10
        val marginY = 20
        val gapY = 4
        var cursorY = marginY

        for ((category, modules) in grouped) {
            val panel = NativePanel(category, modules, marginX, cursorY)
            panel.collapsed = true // FIX: 默认收起
            panels += panel
            cursorY += NativePanel.HEADER_HEIGHT + gapY
        }
    }

    override fun extractRenderState(gfx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val s = scale()
        val lmx = (mouseX / s).toInt()
        val lmy = (mouseY / s).toInt()
        val logicalWidth = (width / s).toInt().takeIf { it > 0 } ?: 1280
        val logicalHeight = (height / s).toInt().takeIf { it > 0 } ?: 720

        gfx.pose().pushMatrix()
        gfx.pose().scale(s, s)

        for (panel in panels) {
            panel.render(gfx, lmx, lmy, logicalWidth, logicalHeight)
        }
        searchBar.render(gfx, logicalWidth, lmx, lmy)

        gfx.pose().popMatrix()
    }

    /**
     * FIX: use event.button() instead of event.buttonInfo.button
     * The original MC 1.21 DroneControlScreen uses click.button(), not click.buttonInfo.button.
     */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val s = scale()
        val mx = toLogical(event.x, s)
        val my = toLogical(event.y, s)
        val button = event.button()
        val logicalWidth = (width / s).toInt()

        if (searchBar.mouseClicked(logicalWidth, mx, my, button)) return true

        for (panel in panels.asReversed()) {
            if (panel.mouseClicked(mx, my, button)) {
                activePanel = panel
                panels.remove(panel)
                panels.add(panel)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val s = scale()
        val mx = toLogical(event.x, s)
        val my = toLogical(event.y, s)
        val button = event.button()
        activePanel?.let { if (it.mouseDragged(mx, my, button, dx / s, dy / s)) return true }
        for (panel in panels.asReversed()) {
            if (panel.mouseDragged(mx, my, button, dx / s, dy / s)) return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val s = scale()
        val button = event.button()
        activePanel = null
        var consumed = false
        for (panel in panels) {
            if (panel.mouseReleased(toLogical(event.x, s), toLogical(event.y, s), button)) consumed = true
        }
        return consumed || super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val s = scale()
        val mx = toLogical(mouseX, s)
        val my = toLogical(mouseY, s)
        for (panel in panels.asReversed()) {
            if (panel.mouseScrolled(mx, my, scrollY)) return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val chr = event.codepoint.toChar()
        if (searchBar.charTyped(chr)) return true
        if (panels.any { it.charTyped(chr) }) return true
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.key
        if (searchBar.keyPressed(keyCode)) return true
        if (panels.any { it.keyPressed(keyCode) }) return true
        return super.keyPressed(event)
    }

    override fun isPauseScreen(): Boolean = false
}
