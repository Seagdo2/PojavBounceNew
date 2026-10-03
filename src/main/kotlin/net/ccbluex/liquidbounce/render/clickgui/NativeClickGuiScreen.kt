/*
 * This file is part of LiquidBounce (https://github.com/LiquidBounce)
 *
 * Native port of ClickGui.svelte: no dimmed backdrop (the game world stays
 * fully visible behind the panels, exactly like the web overlay), one
 * independently draggable+collapsible NativePanel per ModuleCategory, plus
 * a floating, centered NativeSearchBar on top of everything.
 *
 * Wired to the real ModuleClickGui (features/module/modules/render/
 * ModuleClickGui.kt) rather than inventing separate settings: `Scale`
 * drives the pose scale applied here, `SearchBarAutoFocus` decides whether
 * the search pill grabs focus on open, `PanelHeight` caps how tall each
 * panel's module list gets before scrolling, and `Snapping` (enabled +
 * GridSize) is read by NativePanel on drag-release. See that file for where
 * this screen is actually opened from.
 *
 * Layout (panel positions, collapse/scroll state, expanded modules) is now
 * persisted across games via ClickGuiLayoutStore - restored in init() and
 * saved in removed(), so reopening the GUI brings everything back where it
 * was left. The module's own settings are persisted by ConfigSystem.
 */
package net.ccbluex.liquidbounce.render.clickgui

import net.ccbluex.liquidbounce.features.module.ClientModule
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

    /**
     * The user-facing Scale setting is coerced to 0.5-2 here. coerceIn clamps
     * finite values, but a corrupt/NaN persisted Scale would slip straight
     * through it (NaN comparisons are false) and then poison every coordinate
     * computed below - feeding NaN into the pose matrix, the scissor rect and
     * every draw call, which is exactly what crashed the GUI when Scale was
     * touched. Falling back to 1f on any non-finite value is what stops that.
     */
    private fun scale(): Float {
        val raw = ModuleClickGui.scale.coerceIn(0.5f, 2f)
        return if (raw.isFinite() && raw > 0f) raw else 1f
    }

    /** Converts a real mouse position into this screen's logical (pre-scale)
     * coordinate space, since every widget below still thinks in unscaled px. */
    private fun toLogical(v: Double, s: Float): Int = (v / s).toInt()

    override fun init() {
        super.init()
        if (panels.isEmpty()) {
            buildPanels()
            restoreLayout()
            searchBar.focused = ModuleClickGui.searchBarAutoFocus
        }
        searchBar.onLocateModule = { module -> locateModule(module) }
    }

    private fun buildPanels() {
        val grouped = ModuleManager.groupBy { it.category }
        val marginX = 10
        val marginY = 20
        val gapY = 4
        var cursorY = marginY

        for ((category, modules) in grouped) {
            val panel = NativePanel(category, modules, marginX, cursorY)
            panel.collapsed = true
            panels += panel
            cursorY += NativePanel.HEADER_HEIGHT + gapY
        }
    }

    override fun extractRenderState(gfx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (width <= 0 || height <= 0) return
        val s = scale()
        val lmx = (mouseX / s).toInt()
        val lmy = (mouseY / s).toInt()
        val logicalWidth = (width / s).toInt().takeIf { it > 0 } ?: 1280
        val logicalHeight = (height / s).toInt().takeIf { it > 0 } ?: 720

        // pushMatrix/popMatrix is wrapped in try/finally (the project's own
        // `withPush` does the same) so an exception thrown inside a panel's
        // render can never leave the pose stack pushed - which would otherwise
        // corrupt every screen rendered afterwards. This is what makes Scale
        // safe rather than crash-prone: a geometry/value that a sub-render
        // chokes on now restores the matrix instead of taking the GUI down.
        gfx.pose().pushMatrix()
        try {
            gfx.pose().scale(s, s)

            // deliberately no dimmed backdrop / super.extractRenderState() background fill -
            // the whole point of this screen is that the game stays visible
            for (panel in panels) {
                panel.render(gfx, lmx, lmy, logicalWidth, logicalHeight)
            }
            searchBar.render(gfx, logicalWidth, lmx, lmy)
        } finally {
            gfx.pose().popMatrix()
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val s = scale()
        val mx = toLogical(event.x, s)
        val my = toLogical(event.y, s)
        val button = event.button()
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

    /** The game keeps rendering/ticking behind the GUI (it's an overlay, not
     * a menu that pauses the world) - matches the web ClickGUI's behaviour. */
    override fun isPauseScreen(): Boolean = false

    // --------------------------------------------------------- layout I/O

    /**
     * Persist the whole layout when the screen is removed (ESC, world change,
     * any setScreen(...) that replaces us). The module's own settings are
     * saved by ConfigSystem; this covers the session-only bits - panel
     * positions, collapse/scroll state, and which modules' settings were left
     * expanded. Wrapped so a failure to save never crashes the game.
     */
    override fun removed() {
        super.removed()
        saveLayout()
    }


    /**
     * Search.svelte right-click flow: expand the owning panel, bring it to
     * front, scroll the module into view, and draw an accent highlight box
     * on the module row (Module.svelte `.highlight`).
     */
    private fun locateModule(module: ClientModule) {
        panels.forEach { it.clearHighlights() }
        val panel = panels.firstOrNull { it.containsModule(module) } ?: return
        panels.remove(panel)
        panels.add(panel)
        panel.scrollToModule(module)
        searchBar.focused = false
    }

    private fun restoreLayout() {
        val saved = ClickGuiLayoutStore.load()
        if (saved.isEmpty()) return
        for (panel in panels) {
            panel.restore(saved[panel.category.tag])
        }
        // keep at least the header of every panel reachable even if the
        // saved positions came from a different resolution than this one
        val sw = (width / scale()).toInt().coerceAtLeast(1)
        val sh = (height / scale()).toInt().coerceAtLeast(1)
        for (panel in panels) {
            panel.clampToScreen(sw, sh)
        }
    }

    private fun saveLayout() {
        val layout = panels.associate { it.category.tag to it.snapshot() }
        ClickGuiLayoutStore.save(layout)
    }
}
