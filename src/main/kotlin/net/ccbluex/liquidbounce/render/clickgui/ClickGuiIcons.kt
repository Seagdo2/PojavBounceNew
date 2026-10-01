/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * These textures are NOT hand-drawn substitutes. Each PNG under
 * assets/liquidbounce/textures/clickgui/ was produced by rasterizing the
 * exact vector path data from src-theme/src/routes/clickgui's own icon
 * set (the .svg files under public/img/clickgui) - same curves, same stroke widths,
 * same winding rules - just baked to a 64x64 white alpha mask so a
 * native GuiGraphics.blit() can render + tint them. See svg_raster.py
 * for the conversion tool if the web theme's icons ever change and
 * these need regenerating.
 *
 * They are plain white masks on purpose: the web theme colors these via
 * CSS `fill: var(--...)`, and the native equivalent is tinting the blit
 * with ClickGuiPalette at draw time (GuiRender2D.icon), so one texture
 * serves every state (idle / hover / enabled) instead of needing
 * pre-baked colored variants.
 */
package net.ccbluex.liquidbounce.render.clickgui

import net.minecraft.resources.Identifier

object ClickGuiIcons {

    private fun id(name: String) = Identifier.fromNamespaceAndPath("liquidbounce", "textures/clickgui/$name.png")

    val CLIENT = id("icon-client")
    val COMBAT = id("icon-combat")
    val CROSS = id("icon-cross")
    val DRAG = id("icon-drag")
    val EXPLOIT = id("icon-exploit")
    val FUN = id("icon-fun")
    val MISC = id("icon-misc")
    val MOVEMENT = id("icon-movement")
    val OPEN_FILE = id("icon-open-file")
    val PLAYER = id("icon-player")
    val RENDER = id("icon-render")
    val RESET = id("icon-reset")
    val SETTINGS_EXPAND = id("icon-settings-expand")
    val TICK = id("icon-tick")
    val TICK_CHECKED = id("icon-tick-checked")
    val WORLD = id("icon-world")

    /** Fine static-grain texture used by GuiRender2D.frostOverlay for the
     * pseudo-glass theme (ModuleClickGui.glassMode) - tiled, not stretched,
     * so it reads as grain at any panel size. */
    val GLASS_NOISE = id("texture-glass-noise")

    /**
     * Maps a [ModuleCategory] tag to its built-in icon. Add-on categories
     * that ship their own icon (see ModuleCategory.kt: `val icon:
     * Identifier?`) should use that one instead - this fallback table only
     * covers the categories the web theme itself ships icons for.
     */
    fun forCategory(tag: String): Identifier? = when (tag.uppercase()) {
        "COMBAT" -> COMBAT
        "MOVEMENT" -> MOVEMENT
        "PLAYER" -> PLAYER
        "RENDER", "VISUAL" -> RENDER
        "WORLD" -> WORLD
        "EXPLOIT" -> EXPLOIT
        "FUN" -> FUN
        "CLIENT" -> CLIENT
        else -> MISC
    }
}
