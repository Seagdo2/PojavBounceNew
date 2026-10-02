/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of Panel.svelte. Behaviour: mousedown+drag on the header moves
 * the panel; right-click on the header collapses/expands the module list.
 *
 * FIX: WIDTH now reads from ModuleClickGui.panelWidth (default 200, was 250)
 * FIX: HEADER_HEIGHT lowered from 30 to 24
 * FIX: collapsed is now publicly settable (for default-collapsed layout)
 * FIX: maxBodyHeight reads from ModuleClickGui.panelMaxHeight
 */
package net.ccbluex.liquidbounce.render.clickgui.widget

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiIcons
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs

class NativePanel(
    val category: ModuleCategory,
    modules: List<ClientModule>,
    var x: Int,
    var y: Int,
) {
    companion object {
        val WIDTH: Int get() = ModuleClickGui.panelWidth
        const val HEADER_HEIGHT = 24
        val DEFAULT_MAX_BODY_HEIGHT: Int get() = ModuleClickGui.panelMaxHeight
        const val MIN_BODY_HEIGHT = 50
        const val MAX_BODY_HEIGHT_CAP = 900
        private const val RADIUS = 5
        private const val RESIZE_GRIP = 10
        private const val SCROLL_PX_PER_NOTCH = 42f
        private const val SCROLL_EASE = 0.35f
    }

    val rows: List<NativeModuleRow> = modules.sortedBy { it.name }.map { NativeModuleRow(it) }

    var collapsed = false
        public set

    var maxBodyHeight: Int = DEFAULT_MAX_BODY_HEIGHT
        private set

    private var scrollTarget = 0f
    private var scrollAnimated = 0f

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var resizingHeight = false
    private var resizeStartMouseY = 0
    private var resizeStartHeight = 0

    var screenW: Int = Int.MAX_VALUE
    var screenH: Int = Int.MAX_VALUE

    private fun contentHeight(): Int = rows.sumOf { it.totalHeight(WIDTH - 8) }

    private fun bodyHeight(): Int = contentHeight().coerceAtMost(maxBodyHeight).coerceAtLeast(0)

    fun totalHeight(): Int = HEADER_HEIGHT + if (collapsed) 0 else bodyHeight()

    fun render(gfx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, screenWidth: Int = Int.MAX_VALUE, screenHeight: Int = Int.MAX_VALUE) {
        screenW = screenWidth.coerceAtLeast(1)
        screenH = screenHeight.coerceAtLeast(1)
        scrollAnimated += (scrollTarget - scrollAnimated) * SCROLL_EASE
        if (abs(scrollTarget - scrollAnimated) < 0.5f) scrollAnimated = scrollTarget
        val scroll = scrollAnimated.toInt()

        val glass = ModuleClickGui.glassMode
        val headerBg = if (glass) ClickGuiPalette.GLASS_PANEL_HEADER_BG else ClickGuiPalette.PANEL_HEADER_BG
        val bodyBg = if (glass) ClickGuiPalette.GLASS_PANEL_BODY_BG else ClickGuiPalette.PANEL_BODY_BG

        val w = WIDTH
        val h = totalHeight()
        GuiRender2D.dropShadow(gfx, x, y, w, h, RADIUS, ClickGuiPalette.PANEL_SHADOW)

        GuiRender2D.fillRoundedRect(gfx, x, y, w, HEADER_HEIGHT, RADIUS, headerBg)
        if (!collapsed) {
            gfx.fill(x, y + HEADER_HEIGHT - RADIUS, x + w, y + HEADER_HEIGHT, headerBg)
        }
        if (glass) GuiRender2D.frostOverlay(gfx, x, y, w, HEADER_HEIGHT)
        GuiRender2D.line(gfx, x, y + HEADER_HEIGHT - 2, x + w, y + HEADER_HEIGHT - 2, 2, ClickGuiPalette.PANEL_HEADER_BORDER)

        val icon = ClickGuiIcons.forCategory(category.tag)
        var textX = x + 10
        if (icon != null) {
            GuiRender2D.icon(gfx, icon, x + 8, y + HEADER_HEIGHT / 2 - 6, 12, ClickGuiPalette.TEXT)
            textX = x + 26
        }
        val font = Minecraft.getInstance().font
        gfx.text(font, category.tag, textX, y + (HEADER_HEIGHT - font.lineHeight) / 2, ClickGuiPalette.TEXT, false)

        val chevronCx = x + w - 16
        val chevronCy = y + HEADER_HEIGHT / 2
        GuiRender2D.icon(gfx, ClickGuiIcons.SETTINGS_EXPAND, chevronCx - 4, chevronCy - 4, 8, ClickGuiPalette.PANEL_TOGGLE_ICON)

        if (collapsed) {
            if (glass) GuiRender2D.strokeRoundedRect(gfx, x, y, w, HEADER_HEIGHT, RADIUS, 1, ClickGuiPalette.GLASS_EDGE_HIGHLIGHT)
            return
        }

        val bodyY = y + HEADER_HEIGHT
        val bh = bodyHeight()
        gfx.fill(x, bodyY, x + w, bodyY + bh, bodyBg)
        if (glass) GuiRender2D.frostOverlay(gfx, x, bodyY, w, bh)

        val clipX0 = maxOf(x, 0)
        val clipY0 = maxOf(bodyY, 0)
        val clipX1 = minOf(x + w, screenW)
        val clipY1 = minOf(bodyY + bh, screenH)
        if (clipX1 > clipX0 && clipY1 > clipY0) {
            gfx.enableScissor(clipX0, clipY0, clipX1, clipY1)
            var rowY = bodyY - scroll
            for (row in rows) {
                val rh = row.totalHeight(w - 8)
                if (rowY + rh >= bodyY && rowY <= bodyY + bh) {
                    row.render(gfx, x + 4, rowY, w - 8, mouseX, mouseY)
                }
                rowY += rh
            }
            gfx.disableScissor()
        }

        clampScroll()

        if (glass) {
            GuiRender2D.strokeRoundedRect(gfx, x, y, w, h, RADIUS, 1, ClickGuiPalette.GLASS_EDGE_HIGHLIGHT)
        }

        if (contentHeight() > MIN_BODY_HEIGHT) {
            val gx = x + w - RESIZE_GRIP
            val gy = bodyY + bh - RESIZE_GRIP
            val gripColor = ClickGuiPalette.withAlpha(ClickGuiPalette.TEXT_DIMMED, 140)
            for (i in 0..2) {
                val o = i * 3
                GuiRender2D.line(gfx, gx + o, gy + RESIZE_GRIP, gx + RESIZE_GRIP, gy + o, 1, gripColor)
            }
        }
    }

    private fun clampScroll() {
        val maxScroll = (contentHeight() - bodyHeight()).coerceAtLeast(0).toFloat()
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollAnimated = scrollAnimated.coerceIn(0f, maxScroll)
    }

    private fun clampToScreen() {
        if (screenW != Int.MAX_VALUE) {
            x = x.coerceIn(40 - WIDTH, screenW - 40)
        }
        if (screenH != Int.MAX_VALUE) {
            y = y.coerceIn(0, (screenH - HEADER_HEIGHT).coerceAtLeast(0))
        }
    }

    fun mouseScrolled(mouseX: Int, mouseY: Int, amount: Double): Boolean {
        if (collapsed) return false
        val w = WIDTH
        if (mouseX !in x..(x + w) || mouseY !in (y + HEADER_HEIGHT)..(y + totalHeight())) return false
        scrollTarget -= (amount * SCROLL_PX_PER_NOTCH).toFloat()
        clampScroll()
        return true
    }

    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        val w = WIDTH
        if (mouseX !in x..(x + w)) return false

        if (!collapsed && button == 0) {
            val gx = x + w - RESIZE_GRIP
            val gy = y + totalHeight() - RESIZE_GRIP
            if (mouseX >= gx && mouseY >= gy) {
                resizingHeight = true
                resizeStartMouseY = mouseY
                resizeStartHeight = maxBodyHeight
                return true
            }
        }

        if (mouseY in y..(y + HEADER_HEIGHT)) {
            if (button == 1) {
                collapsed = !collapsed
                return true
            }
            val chevronZone = x + w - 24
            if (mouseX >= chevronZone) {
                collapsed = !collapsed
                return true
            }
            if (button == 0) {
                dragging = true
                dragOffsetX = mouseX - x
                dragOffsetY = mouseY - y
                return true
            }
            return false
        }

        if (!collapsed && mouseY in (y + HEADER_HEIGHT)..(y + totalHeight())) {
            val scroll = scrollAnimated.toInt()
            var rowY = y + HEADER_HEIGHT - scroll
            for (row in rows) {
                val rh = row.totalHeight(w - 8)
                if (mouseY in rowY..(rowY + rh)) {
                    return row.mouseClicked(x + 4, rowY, w - 8, mouseX, mouseY, button)
                }
                rowY += rh
            }
        }
        return false
    }

    fun mouseDragged(mouseX: Int, mouseY: Int, button: Int, dragX: Double, dragY: Double): Boolean {
        if (resizingHeight) {
            val delta = mouseY - resizeStartMouseY
            maxBodyHeight = (resizeStartHeight + delta).coerceIn(MIN_BODY_HEIGHT, MAX_BODY_HEIGHT_CAP)
            return true
        }
        if (dragging) {
            x = mouseX - dragOffsetX
            y = mouseY - dragOffsetY
            clampToScreen()
            return true
        }
        if (!collapsed) {
            val w = WIDTH
            val scroll = scrollAnimated.toInt()
            var rowY = y + HEADER_HEIGHT - scroll
            for (row in rows) {
                val rh = row.totalHeight(w - 8)
                if (row.mouseDragged(x + 4, rowY, w - 8, mouseX, mouseY, button)) return true
                rowY += rh
            }
        }
        return false
    }

    fun mouseReleased(mouseX: Int, mouseY: Int, button: Int): Boolean {
        val was = dragging || resizingHeight
        if (dragging && ModuleClickGui.Snapping.enabled) {
            val grid = ModuleClickGui.Snapping.gridSize
            x = ((x + grid / 2) / grid) * grid
            y = ((y + grid / 2) / grid) * grid
            clampToScreen()
        }
        dragging = false
        resizingHeight = false
        rows.forEach { it.mouseReleased(mouseX, mouseY, button) }
        return was
    }

    fun charTyped(chr: Char): Boolean = rows.any { it.charTyped(chr) }
    fun keyPressed(keyCode: Int): Boolean = rows.any { it.keyPressed(keyCode) }
}
