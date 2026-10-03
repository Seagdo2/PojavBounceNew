/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of Search.svelte: a centered pill, 600px wide, top offset
 * 70px, corner radius 30 while empty, 10 (top corners only, visually) once
 * results are showing below it - see the source's
 * `border-radius: {results.length ? 10 : 30}px`.
 */
package net.ccbluex.liquidbounce.render.clickgui.widget

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

class NativeSearchBar(private val allModules: () -> Collection<ClientModule>) {

    companion object {
        const val WIDTH = 400
        const val TOP = 4
        const val HEIGHT = 22
        const val ROW_HEIGHT = 20
        const val MAX_RESULTS = 8
    }

    var query: String = ""
        private set
    var focused: Boolean = false

    /** Invoked on right-click of a search result - locate the module in its panel (Search.svelte contextmenu). */
    var onLocateModule: ((ClientModule) -> Unit)? = null

    private var results: List<ClientModule> = emptyList()

    private fun refresh() {
        results = if (query.isBlank()) emptyList() else
            allModules().filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name.length }
                .take(MAX_RESULTS)
    }

    fun centerX(screenWidth: Int): Int = (screenWidth - WIDTH) / 2

    fun render(gfx: GuiGraphicsExtractor, screenWidth: Int, mouseX: Int, mouseY: Int) {
        val x = centerX(screenWidth)
        val y = TOP
        val hasResults = results.isNotEmpty()
        val radius = if (hasResults) 10 else HEIGHT / 2
        val glass = ModuleClickGui.glassMode
        val bg = if (glass) ClickGuiPalette.GLASS_SEARCH_BG else ClickGuiPalette.SEARCH_BG

        GuiRender2D.dropShadow(gfx, x, y, WIDTH, HEIGHT, radius, ClickGuiPalette.SEARCH_SHADOW)
        GuiRender2D.fillRoundedRect(gfx, x, y, WIDTH, HEIGHT, radius, bg)
        if (glass) GuiRender2D.frostOverlay(gfx, x, y, WIDTH, HEIGHT)
        if (focused) {
            GuiRender2D.strokeRoundedRect(gfx, x, y, WIDTH, HEIGHT, radius, 1, ClickGuiPalette.SEARCH_BORDER)
        } else if (glass) {
            GuiRender2D.strokeRoundedRect(gfx, x, y, WIDTH, HEIGHT, radius, 1, ClickGuiPalette.GLASS_EDGE_HIGHLIGHT)
        }

        val font = Minecraft.getInstance().font
        val shown = if (query.isEmpty() && !focused) "\u00A77Search modules..." else query + if (focused) "\u00A77_" else ""
        gfx.text(font, shown, x + 16, y + (HEIGHT - font.lineHeight) / 2, ClickGuiPalette.TEXT, false)

        if (!hasResults) return

        val resultsY = y + HEIGHT
        val resultsH = results.size * ROW_HEIGHT + 6
        GuiRender2D.fillRoundedRect(gfx, x, resultsY, WIDTH, resultsH, 10, bg)
        if (glass) GuiRender2D.frostOverlay(gfx, x, resultsY, WIDTH, resultsH)
        GuiRender2D.line(gfx, x + 12, resultsY, x + WIDTH - 12, resultsY, 2, ClickGuiPalette.SEARCH_BORDER)

        var rowY = resultsY + 6
        for (module in results) {
            val hovered = mouseX in x..(x + WIDTH) && mouseY in rowY..(rowY + ROW_HEIGHT)
            if (hovered) gfx.fill(x + 4, rowY, x + WIDTH - 4, rowY + ROW_HEIGHT, ClickGuiPalette.MODULE_HOVER_BG)
            val color = if (module.enabled) ClickGuiPalette.MODULE_ENABLED else ClickGuiPalette.TEXT
            gfx.text(font, GuiRender2D.ellipsize(gfx, module.name, WIDTH - 32), x + 16, rowY + 6, color, false)
            rowY += ROW_HEIGHT
        }
    }

    fun mouseClicked(screenWidth: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        val x = centerX(screenWidth)
        if (mouseX in x..(x + WIDTH) && mouseY in TOP..(TOP + HEIGHT)) {
            focused = true
            return true
        }
        if (results.isNotEmpty()) {
            val resultsY = TOP + HEIGHT
            var rowY = resultsY + 6
            for (module in results) {
                if (mouseX in x..(x + WIDTH) && mouseY in rowY..(rowY + ROW_HEIGHT)) {
                    if (button == 1) {
                        // Right-click: locate module in its category panel (Search.svelte
                        // on:contextmenu -> highlightModuleName = name). Does NOT toggle.
                        onLocateModule?.invoke(module)
                        return true
                    }
                    if (button == 0) {
                        // Left-click: toggle module enabled (Search.svelte on:click)
                        module.enabled = !module.enabled
                        return true
                    }
                    return true
                }
                rowY += ROW_HEIGHT
            }
        }
        focused = false
        return false
    }

    fun charTyped(chr: Char): Boolean {
        if (!focused) return false
        query += chr
        refresh()
        return true
    }

    fun keyPressed(keyCode: Int): Boolean {
        if (!focused) return false
        if (keyCode == 259 && query.isNotEmpty()) { // backspace
            query = query.dropLast(1)
            refresh()
            return true
        }
        if (keyCode == 256) { // escape: clear focus, let the screen decide whether to close
            focused = false
            return true
        }
        return false
    }
}
