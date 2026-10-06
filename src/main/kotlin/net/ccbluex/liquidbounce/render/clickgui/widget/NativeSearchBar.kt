/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of Search.svelte: a centered pill, 600px wide, top offset
 * 70px, corner radius 30 while empty, 10 (top corners only, visually) once
 * results are showing below it - see the source's
 * `border-radius: {results.length ? 10 : 30}px`.
 */
package net.ccbluex.liquidbounce.render.clickgui.widget

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

class NativeSearchBar(
    private val allModules: () -> Collection<ClientModule>,
    /** Bug fix: right-clicking a result used to just toggle it (identical to
     * left-click) instead of locating it. Locating needs the panel list,
     * which only NativeClickGuiScreen has, so that's handled there - this
     * callback is how a right-click reaches it. */
    private val onLocate: (ClientModule) -> Unit,
) {

    companion object {
        /** 【尺寸对齐 ClickGuiScreen】搜索框 600x30 → 180x18 */
        const val WIDTH = 180
        const val TOP = 6
        const val HEIGHT = 18
        /** 【尺寸对齐 ClickGuiScreen】结果行 22 → 17 */
        const val ROW_HEIGHT = 17
        const val MAX_RESULTS = 8
    }

    var query: String = ""
        private set
    var focused: Boolean = false

    private var results: List<ClientModule> = emptyList()

    private fun refresh() {
        results = if (query.isBlank()) {
            emptyList()
        } else {
            allModules()
                .filter { mod ->
                    mod.name.contains(query, ignoreCase = true) ||
                        mod.aliases.any { it.contains(query, ignoreCase = true) }
                }
                .sortedBy { it.name.length }
                .take(MAX_RESULTS)
        }
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
                    if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                        // Bug fix: this used to fall through to the same
                        // `enabled = !enabled` as left-click below, so right-
                        // clicking a result just toggled it too. Right-click
                        // now locates the module instead: jump to its panel,
                        // scroll it into view there, and highlight it - then
                        // close the search UI so the panel is visible.
                        onLocate(module)
                        query = ""
                        refresh()
                        focused = false
                        return true
                    }
                    module.enabled = !module.enabled
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
        // Ignore control chars (backspace is handled in keyPressed)
        if (chr.isISOControl()) return true
        query += chr
        refresh()
        return true
    }

    /**
     * Accept SDL scancode ([key]), platform keycode ([keycode]), and legacy GLFW
     * codes so backspace/escape work on desktop 26.3, Pojav, and mixed builds.
     */
    fun keyPressed(key: Int, keycode: Int = -1): Boolean {
        if (!focused) return false
        val backspace =
            key == InputConstants.KEY_BACKSPACE ||
            keycode == InputConstants.KEYCODE_BACKSPACE ||
            key == 259 // legacy GLFW
        val delete =
            key == InputConstants.KEY_DELETE ||
            keycode == InputConstants.KEYCODE_DELETE ||
            key == 261
        val escape =
            key == InputConstants.KEY_ESCAPE ||
            key == 256 // legacy GLFW

        if ((backspace || delete) && query.isNotEmpty()) {
            query = query.dropLast(1)
            refresh()
            return true
        }
        if (escape) {
            if (query.isNotEmpty()) {
                query = ""
                refresh()
            } else {
                focused = false
            }
            return true
        }
        return false
    }

    /** 接收 IME preedit 文本 (Android 输入法在提交前走 preedit 而非 textInput) */
    fun preeditUpdated(text: String): Boolean {
        if (!focused) return false
        if (text.isBlank()) return true
        query = text  // 直接替换为 preedit 内容
        refresh()
        return true
    }
}
