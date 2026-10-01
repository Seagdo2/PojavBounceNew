/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native (non-web) re-implementation of the nextgen ClickGUI theme.
 *
 * Every constant below is the *resolved* value of the corresponding CSS
 * custom property in `src-theme/src/colors.scss`. Where the source uses
 * `color-mix(in srgb, A p%, B)`, the arithmetic was done once, offline
 * (p% * A + (1-p%) * B per channel, alpha handled the same way for
 * `transparent`), so these are exact matches, not approximations:
 *
 *   --accent-color:                  #4677ff
 *   --accent-hover-color:            color-mix(accent 82%, black)   -> #3962D1
 *   --accent-subtle-background-color color-mix(accent 12%, transp.) -> 4677FF @ 12% alpha
 *   --clickgui-base-NN-color:        color-mix(black NN%, transp.)  -> black @ NN% alpha
 *
 * All values are packed 0xAARRGGBB, ready for GuiGraphics.fill/drawString/etc.
 */
package net.ccbluex.liquidbounce.render.clickgui

object ClickGuiPalette {

    // ---- foundation --------------------------------------------------
    const val ACCENT: Int = 0xFF4677FF.toInt()
    const val ACCENT_HOVER: Int = 0xFF3962D1.toInt()
    const val ACCENT_SUBTLE_BG: Int = 0x1F4677FF
    const val SUCCESS: Int = 0xFF4DAC68.toInt()
    const val ERROR: Int = 0xFFFC4130.toInt()
    const val WARNING: Int = 0xFFEFBF04.toInt()
    const val GRID: Int = 0x40808080

    const val TEXT: Int = 0xFFFFFFFF.toInt()
    const val TEXT_DIMMED: Int = 0xFFD3D3D3.toInt()

    // ---- base-N: black at N% alpha (the whole clickgui is built from
    // layering these over the 3D world, there is no opaque backdrop) ----
    const val BASE_30: Int = 0x4C000000
    const val BASE_36: Int = 0x5C000000
    const val BASE_50: Int = 0x80000000.toInt()
    const val BASE_60: Int = 0x99000000.toInt()
    const val BASE_70: Int = 0xB2000000.toInt()
    const val BASE_80: Int = 0xCC000000.toInt()
    const val BASE_85: Int = 0xD9000000.toInt()
    const val BASE_90: Int = 0xE6000000.toInt()

    // ---- panel ---------------------------------------------------------
    const val PANEL_HEADER_BG: Int = BASE_90
    const val PANEL_HEADER_BORDER: Int = ACCENT
    const val PANEL_BODY_BG: Int = BASE_80
    const val PANEL_TOGGLE_ICON: Int = TEXT
    const val PANEL_SHADOW: Int = BASE_50

    // ---- pseudo-glass theme (ModuleClickGui.glassMode) ----------------------
    // No blur-behind pass exists in this project's render API, so "glass" is
    // faked with: a much more transparent backing (so more of the game world
    // shows through), a tiled grain overlay (GuiRender2D.frostOverlay) to
    // read as frosted rather than just plain see-through, and a bright,
    // thin edge highlight (the classic "light catching a glass edge" cue).
    const val GLASS_PANEL_HEADER_BG: Int = 0x80000000.toInt()
    const val GLASS_PANEL_BODY_BG: Int = 0x59000000
    const val GLASS_SEARCH_BG: Int = 0x80000000.toInt()
    const val GLASS_EDGE_HIGHLIGHT: Int = 0x40FFFFFF

    // ---- module row ------------------------------------------------------
    const val MODULE_HOVER_BG: Int = BASE_85
    const val MODULE_ENABLED: Int = ACCENT
    const val MODULE_SETTINGS_BG: Int = BASE_50
    const val MODULE_SETTINGS_BORDER: Int = ACCENT

    // ---- search ----------------------------------------------------------
    const val SEARCH_BG: Int = BASE_90
    const val SEARCH_SHADOW: Int = BASE_50
    const val SEARCH_BORDER: Int = ACCENT
    const val SEARCH_ENABLED: Int = ACCENT
    const val SEARCH_ALIAS: Int = 0x99D3D3D3.toInt()
    const val SEARCH_HINT: Int = 0x66FFFFFF

    // ---- description tooltip ----------------------------------------------
    const val DESCRIPTION_BG: Int = BASE_90
    const val DESCRIPTION_SHADOW: Int = BASE_50

    // ---- inputs / buttons / dropdowns --------------------------------------
    const val INPUT_BG: Int = BASE_36
    const val INPUT_BORDER: Int = ACCENT
    const val BUTTON_BG: Int = ACCENT
    const val BUTTON_HOVER_BG: Int = ACCENT_HOVER
    const val SETTING_GROUP_BORDER: Int = ACCENT
    const val DROPDOWN_BG: Int = 0xFF000000.toInt()
    const val DROPDOWN_BORDER: Int = ACCENT
    const val DROPDOWN_OPTION: Int = TEXT_DIMMED
    const val DROPDOWN_OPTION_HOVER: Int = TEXT
    const val DROPDOWN_OPTION_SELECTED: Int = ACCENT

    // ---- chips (multi-choose) ------------------------------------------------
    const val CHIP_BG: Int = BASE_30
    const val CHIP_TEXT: Int = TEXT_DIMMED
    const val CHIP_SELECTED_BG: Int = ACCENT_SUBTLE_BG
    const val CHIP_SELECTED_TEXT: Int = ACCENT
    const val CHIP_REMOVE_BG: Int = 0x1AFC4130
    const val CHIP_REMOVE_TEXT: Int = ERROR

    // ---- switch (boolean) ----------------------------------------------------
    const val SWITCH_TRACK: Int = 0xFF737373.toInt()
    const val SWITCH_THUMB: Int = TEXT
    const val SWITCH_TRACK_ACTIVE: Int = 0xFF1C3066.toInt()
    const val SWITCH_THUMB_ACTIVE: Int = ACCENT

    // ---- slider ----------------------------------------------------------------
    const val SLIDER_TRACK: Int = 0xFF333333.toInt()
    const val SLIDER_HANDLE: Int = ACCENT
    const val SLIDER_FILL: Int = ACCENT

    // ---- misc ---------------------------------------------------------------------
    const val DIVIDER: Int = 0x1FFFFFFF
    const val OVERLAY_BACKDROP: Int = 0x99000000.toInt()

    /** Replaces just the alpha channel of an ARGB int (0-255). */
    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** Linearly interpolates between two ARGB colors, channel-wise. */
    fun lerp(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
