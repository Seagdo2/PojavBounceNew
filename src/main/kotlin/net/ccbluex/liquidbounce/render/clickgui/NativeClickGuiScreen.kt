/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of ClickGui.svelte: no dimmed backdrop, one independently
 * draggable+collapsible NativePanel per ModuleCategory, plus a floating
 * centered NativeSearchBar on top of everything.
 *
 * FIX: 面板优先于搜索框接收点击事件（搜索框与面板重叠时不再吞掉点击）
 * FIX: 右键展开/收缩分类，左键拖动面板和开关模块
 * FIX: 渲染异常 try-catch 防闪退
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

    fun isSearchFocused(): Boolean = searchBar.focused

    private fun scale(): Float {
        val raw = ModuleClickGui.scale.coerceIn(0.5f, 2f)
        return if (raw.isFinite() && raw > 0f) raw else 1f
    }

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
        val marginY = 50
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

        gfx.pose().pushMatrix()
        try {
            gfx.pose().scale(s, s)

            for (panel in panels) {
                panel.render(gfx, lmx, lmy)
            }
            searchBar.render(gfx, (width / s).toInt(), lmx, lmy)
        } catch (_: Exception) {
            // 防闪退
        } finally {
            gfx.pose().popMatrix()
        }
    }

    /**
     * FIX: 面板优先于搜索框检查点击。
     * 原代码先检查搜索框，当搜索框与面板重叠时会吞掉面板的点击事件。
     * 现在先检查面板，只有面板未命中时才检查搜索框。
     */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val s = scale()
        val mx = toLogical(event.x, s)
        val my = toLogical(event.y, s)
        val button = event.button()
        val logicalWidth = (width / s).toInt()

        // 先检查面板（从顶层到底层）
        for (panel in panels.asReversed()) {
            if (panel.mouseClicked(mx, my, button)) {
                activePanel = panel
                panels.remove(panel)
                panels.add(panel)
                return true
            }
        }

        // 面板未命中才检查搜索框
        if (searchBar.mouseClicked(logicalWidth, mx, my, button)) return true

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
    override fun shouldCloseOnEsc(): Boolean = true

    override fun removed() {
        super.removed()
        saveLayout()
    }

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
