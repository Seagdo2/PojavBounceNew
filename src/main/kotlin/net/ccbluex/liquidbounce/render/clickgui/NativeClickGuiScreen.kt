/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of ClickGui.svelte: no dimmed backdrop (the game world stays
 * fully visible behind the panels, exactly like the web overlay), one
 * independently draggable+collapsible NativePanel per ModuleCategory, plus
 * a floating, centered NativeSearchBar on top of everything.
 *
 * Wired to the real ModuleClickGui (features/module/modules/render/
 * ModuleClickGui.kt) rather than inventing separate settings: `Scale`
 * drives the pose scale applied here, `SearchBarAutoFocus` decides whether
 * the search pill grabs focus on open, and `Snapping` (enabled + GridSize)
 * is read by NativePanel on drag-release. See that file for where this
 * screen is actually opened from.
 *
 * Panels are laid out in a simple cascading grid on first open; positions
 * are only kept for the lifetime of this Screen instance (see README
 * "known gaps" for wiring persistence into ConfigSystem if that's wanted).
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

    /** [ModuleClickGui.isInSearchBar] reads this to suppress keybinds while typing,
     * exactly like it previously checked the browser screen's own text-focus state. */
    fun isSearchFocused(): Boolean = searchBar.focused

    private fun scale(): Float = ModuleClickGui.scale.coerceIn(0.5f, 2f)

    /** Converts a real mouse position into this screen's logical (pre-scale)
     * coordinate space, since every widget below still thinks in unscaled px. */
    private fun toLogical(v: Double, s: Float): Int = (v / s).toInt()

    override fun init() {
        super.init()
        if (panels.isEmpty()) {
            buildPanels()
            searchBar.focused = ModuleClickGui.searchBarAutoFocus
        }
    }

    private fun buildPanels() {
        val grouped = ModuleManager.groupBy { it.category }
        val marginX = 12
        val marginY = 40
        val gapX = 10
        val gapY = 10
        var tallestInRow = 0
        var cursorX = marginX
        var cursorY = marginY
        val logicalWidth = (width / scale()).toInt().takeIf { it > 0 } ?: 1280

        for ((category, modules) in grouped) {
            val panel = NativePanel(category, modules, cursorX, cursorY)
            panels += panel

            val h = NativePanel.HEADER_HEIGHT + 40 // rough estimate before first layout pass
            tallestInRow = maxOf(tallestInRow, h)
            cursorX += NativePanel.WIDTH + gapX
            if (cursorX + NativePanel.WIDTH > logicalWidth) {
                cursorX = marginX
                cursorY += tallestInRow + gapY
                tallestInRow = 0
            }
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

        // deliberately no dimmed backdrop / super.extractRenderState() background fill -
        // the whole point of this screen is that the game stays visible
        for (panel in panels) {
            panel.render(gfx, lmx, lmy, logicalWidth, logicalHeight)
        }
        searchBar.render(gfx, logicalWidth, lmx, lmy)

        gfx.pose().popMatrix()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val s = scale()
        val mx = toLogical(event.x, s)
        val my = toLogical(event.y, s)
        val button = event.buttonInfo.button
        val logicalWidth = (width / s).toInt()

        if (searchBar.mouseClicked(logicalWidth, mx, my, button)) return true

        // topmost (last-rendered) panel gets first refusal, then bring it to front
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
        val button = event.buttonInfo.button
        activePanel?.let { if (it.mouseDragged(mx, my, button, dx / s, dy / s)) return true }
        for (panel in panels.asReversed()) {
            if (panel.mouseDragged(mx, my, button, dx / s, dy / s)) return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val s = scale()
        val button = event.buttonInfo.button
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

    /** The game keeps rendering/ticking behind the GUI (it's an overlay, not
     * a menu that pauses the world) - matches the web ClickGUI's behaviour. */
    override fun isPauseScreen(): Boolean = false
}
