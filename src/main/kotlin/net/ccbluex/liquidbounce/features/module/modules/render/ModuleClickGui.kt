/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * Native ClickGUI: opens NativeClickGuiScreen instead of the web/Ultralight screen.
 * Public surface is annotated with [AddonApi] and kept in sync with api/LiquidBounce.api
 * so checkKotlinAbi passes for published add-ons.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.ClickGuiScaleChangeEvent
import net.ccbluex.liquidbounce.event.events.ClickGuiValueChangeEvent
import net.ccbluex.liquidbounce.features.addon.AddonApi
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.NativeClickGuiScreen
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.game.isTyping
import net.ccbluex.liquidbounce.LiquidBounce

/**
 * ClickGUI module — native GuiGraphics menu (not Ultralight).
 *
 * [AddonApi] members below are part of the published binary ABI. Do not rename
 * or remove them without running `./gradlew updateKotlinAbi` deliberately.
 */
@AddonApi
object ModuleClickGui :
    ClientModule("ClickGUI", ModuleCategories.RENDER, bind = InputConstants.KEY_RSHIFT, disableActivation = true) {

    override val running get() = true

    val scale by float("Scale", 1f, 0.1f..1.3f).onChanged {
        EventManager.callEvent(ClickGuiScaleChangeEvent(it))
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    val searchBarAutoFocus by boolean("SearchBarAutoFocus", true).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    val glassMode by boolean("GlassMode", false).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /**
     * Max visible body height before the panel scrolls.
     */
    val panelHeight by int("PanelHeight", 342, 60..900, "px").onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /**
     * Hard ceiling used by add-ons / layout helpers (ABI).
     * Kept as its own setting so getPanelMaxHeight() stays stable in the binary API.
     */
    val panelMaxHeight by int("PanelMaxHeight", 900, 100..2000, "px").onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /**
     * Per-panel width (NativePanel / initial grid read this live).
     */
    val panelWidth by int("PanelWidth", 130, 120..500, "px").onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    // --- Theme values exposed for add-ons (ABI: getAccentColor / getBgColor* / getFontSize) ---
    // Plain getters so the binary API matches api/LiquidBounce.api without fragile Value ranges.

    /** Accent ARGB — [ClickGuiPalette.ACCENT]. */
    val accentColor: Int
        get() = ClickGuiPalette.ACCENT

    /** Background red channel 0–255 (from packed panel body). */
    val bgColorR: Int
        get() = (ClickGuiPalette.PANEL_BODY_BG ushr 16) and 0xFF

    val bgColorG: Int
        get() = (ClickGuiPalette.PANEL_BODY_BG ushr 8) and 0xFF

    val bgColorB: Int
        get() = ClickGuiPalette.PANEL_BODY_BG and 0xFF

    val bgAlpha: Int
        get() = (ClickGuiPalette.PANEL_BODY_BG ushr 24) and 0xFF

    /** UI font scale factor for add-ons (native screen uses Minecraft font at 1f). */
    val fontSize: Float
        get() = 1f

    val isInSearchBar: Boolean
        get() {
            if (!isTyping) {
                return false
            }
            val screen = mc.gui.screen() ?: return false
            return screen is NativeClickGuiScreen && screen.isSearchFocused()
        }

    /** No-op: native screen reads config live. Kept for AddonApi call sites. */
    fun sync() {}

    /** No-op: native screen reads config live. Kept for AddonApi call sites. */
    fun invalidate() {}

    object Snapping : ToggleableValueGroup(this, "Snapping", true) {

        val gridSize by int("GridSize", 10, 1..100, "px").onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(ModuleClickGui))
        }

        init {
            inner.find { it.name == "Enabled" }?.onChanged {
                EventManager.callEvent(ClickGuiValueChangeEvent(ModuleClickGui))
            }
        }
    }

    init {
        tree(Snapping)
    }

    override fun onEnabled() {
        if (!LiquidBounce.isInitialized || !inGame) {
            return
        }

        mc.execute {
            mc.gui.setScreen(NativeClickGuiScreen())
        }
        super.onEnabled()
    }
}
