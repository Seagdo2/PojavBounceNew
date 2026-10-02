/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native (non-web) re-implementation of the nextgen ClickGUI theme.
 * All values are packed 0xAARRGGBB.
 */
package net.ccbluex.liquidbounce.render.clickgui

object ClickGuiPalette {

    // ---- 动态颜色（可由 ModuleClickGui 设置修改） ----
    var accentColorVar: Int = 0xFF4677FF.toInt()
    var bgAlphaVar: Int = 100

    // 便捷访问
    val ACCENT: Int get() = accentColorVar
    val ACCENT_HOVER: Int get() = withAlpha(accentColorVar, ((accentColorVar ushr 24) and 0xFF) * 82 / 100)
    val ACCENT_SUBTLE_BG: Int get() = withAlpha(accentColorVar, 31)

    const val SUCCESS: Int = 0xFF4DAC68.toInt()
    const val ERROR: Int = 0xFFFC4130.toInt()
    const val WARNING: Int = 0xFFEFBF04.toInt()
    const val GRID: Int = 0x40808080

    const val TEXT: Int = 0xFFFFFFFF.toInt()
    const val TEXT_DIMMED: Int = 0xFFD3D3D3.toInt()

    // ---- base-N: alpha 降低到 ~100/255 ----
    val BASE_30: Int get() = (bgAlphaVar * 30 / 100 shl 24)
    val BASE_36: Int get() = (bgAlphaVar * 36 / 100 shl 24)
    val BASE_50: Int get() = (bgAlphaVar * 50 / 100 shl 24)
    val BASE_60: Int get() = (bgAlphaVar * 60 / 100 shl 24)
    val BASE_70: Int get() = (bgAlphaVar * 70 / 100 shl 24)
    val BASE_80: Int get() = (bgAlphaVar * 80 / 100 shl 24)
    val BASE_85: Int get() = (bgAlphaVar * 85 / 100 shl 24)
    val BASE_90: Int get() = (bgAlphaVar * 90 / 100 shl 24)

    // ---- panel（使用动态 alpha） ----
    val PANEL_HEADER_BG: Int get() = BASE_90
    val PANEL_HEADER_BORDER: Int get() = ACCENT
    val PANEL_BODY_BG: Int get() = BASE_80
    val PANEL_TOGGLE_ICON: Int get() = TEXT
    val PANEL_SHADOW: Int get() = BASE_50

    // ---- glass ----
    const val GLASS_PANEL_HEADER_BG: Int = 0x50000000
    const val GLASS_PANEL_BODY_BG: Int = 0x38000000
    const val GLASS_SEARCH_BG: Int = 0x50000000
    const val GLASS_EDGE_HIGHLIGHT: Int = 0x30FFFFFF

    // ---- module row ----
    val MODULE_HOVER_BG: Int get() = BASE_85
    val MODULE_ENABLED: Int get() = ACCENT
    val MODULE_SETTINGS_BG: Int get() = BASE_50
    val MODULE_SETTINGS_BORDER: Int get() = ACCENT

    // ---- search ----
    val SEARCH_BG: Int get() = BASE_90
    val SEARCH_SHADOW: Int get() = BASE_50
    val SEARCH_BORDER: Int get() = ACCENT
    const val SEARCH_ENABLED: Int = 0xFF4677FF.toInt()
    const val SEARCH_ALIAS: Int = 0x99D3D3D3.toInt()
    const val SEARCH_HINT: Int = 0x66FFFFFF

    // ---- description tooltip ----
    val DESCRIPTION_BG: Int get() = BASE_90
    val DESCRIPTION_SHADOW: Int get() = BASE_50

    // ---- inputs / buttons / dropdowns ----
    val INPUT_BG: Int get() = BASE_36
    val INPUT_BORDER: Int get() = ACCENT
    val BUTTON_BG: Int get() = ACCENT
    val BUTTON_HOVER_BG: Int get() = ACCENT_HOVER
    val SETTING_GROUP_BORDER: Int get() = ACCENT
    const val DROPDOWN_BG: Int = 0xFF000000.toInt()
    val DROPDOWN_BORDER: Int get() = ACCENT
    const val DROPDOWN_OPTION: Int = 0xFFD3D3D3.toInt()
    const val DROPDOWN_OPTION_HOVER: Int = 0xFFFFFFFF.toInt()
    val DROPDOWN_OPTION_SELECTED: Int get() = ACCENT

    // ---- chips ----
    val CHIP_BG: Int get() = BASE_30
    const val CHIP_TEXT: Int = 0xFFD3D3D3.toInt()
    val CHIP_SELECTED_BG: Int get() = ACCENT_SUBTLE_BG
    val CHIP_SELECTED_TEXT: Int get() = ACCENT
    const val CHIP_REMOVE_BG: Int = 0x1AFC4130
    const val CHIP_REMOVE_TEXT: Int = 0xFFFC4130.toInt()

    // ---- switch ----
    const val SWITCH_TRACK: Int = 0xFF737373.toInt()
    const val SWITCH_THUMB: Int = 0xFFFFFFFF.toInt()
    const val SWITCH_TRACK_ACTIVE: Int = 0xFF1C3066.toInt()
    val SWITCH_THUMB_ACTIVE: Int get() = ACCENT

    // ---- slider ----
    const val SLIDER_TRACK: Int = 0xFF333333.toInt()
    val SLIDER_HANDLE: Int get() = ACCENT
    val SLIDER_FILL: Int get() = ACCENT

    // ---- misc ----
    const val DIVIDER: Int = 0x1FFFFFFF
    const val OVERLAY_BACKDROP: Int = 0x64000000

    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

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
