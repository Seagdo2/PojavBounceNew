/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 *
 * ---
 * MODIFIED from the stock file to open the native GuiGraphics-rendered
 * NativeClickGuiScreen (render/clickgui/) instead of the web/Ultralight
 * CustomSharedMinecraftScreen / CustomStandaloneMinecraftScreen. Diff
 * summary against the original, so this is auditable rather than a
 * silent rewrite:
 *
 *   - onEnabled() now does `mc.gui.setScreen(NativeClickGuiScreen())`
 *     instead of opening a CustomSharedMinecraftScreen/
 *     CustomStandaloneMinecraftScreen(CustomScreenType.CLICK_GUI).
 *   - `scale`, `searchBarAutoFocus`, and Snapping's `gridSize` lost their
 *     `private` modifier - NativeClickGuiScreen/NativePanel read them
 *     directly instead of this module needing to push the values out via
 *     events (ClickGuiScaleChangeEvent/ClickGuiValueChangeEvent are still
 *     fired on change, in case other code listens for them, but nothing
 *     here still *needs* them fired to function).
 *   - The `Cache` setting, `standaloneScreen` field,
 *     `updateStandaloneScreen()`, and the `browserReadyHandler` /
 *     `tickHandler` handlers are REMOVED: all of that machinery exists to
 *     manage a persistent embedded-browser instance's lifecycle (whether
 *     to cache it, syncing it after a world change, toggling its
 *     visibility every tick). A plain Screen has no such instance -
 *     Minecraft already constructs/discards it for free - so keeping that
 *     code would be dead weight that references a browser this screen
 *     doesn't have. If you still use the web GUI elsewhere and want both
 *     available side by side, don't drop this file in as a full
 *     replacement - restore the browser-lifecycle members from your
 *     original copy instead.
 *   - isInSearchBar now checks NativeClickGuiScreen.isSearchFocused()
 *     instead of the two Custom*MinecraftScreen classes' text-focus state.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.ClickGuiScaleChangeEvent
import net.ccbluex.liquidbounce.event.events.ClickGuiValueChangeEvent
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.game.isTyping
import net.ccbluex.liquidbounce.render.clickgui.NativeClickGuiScreen
import net.ccbluex.liquidbounce.utils.client.inGame

/**
 * ClickGUI module
 *
 * Shows you an easy-to-use menu to toggle and configure modules.
 */

object ModuleClickGui :
    ClientModule("ClickGUI", ModuleCategories.RENDER, bind = InputConstants.KEY_RSHIFT, disableActivation = true) {

    override val running get() = true

    val scale by float("Scale", 1f, 0.5f..2f).onChanged {
        EventManager.callEvent(ClickGuiScaleChangeEvent(it))
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    val searchBarAutoFocus by boolean("SearchBarAutoFocus", true).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /**
     * Pseudo-glass theme: no real backdrop-blur pass exists in this project's
     * render API, so this fakes the "frosted glass" look with a lower-alpha
     * background, a static grain overlay, and a bright edge highlight
     * instead of a true blur. See GuiRender2D.frostOverlay's comment.
     */
    val glassMode by boolean("GlassMode", false).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    val isInSearchBar: Boolean
        get() {
            if (!isTyping) {
                return false
            }

            val screen = mc.gui.screen() ?: return false
            return screen is NativeClickGuiScreen && screen.isSearchFocused()
        }

    /**
     * No-op compatibility shims. The browser-based ClickGUI these originally
     * pushed state to is gone (this module now opens a plain native Screen),
     * but several call sites still invoke them after mutating config -
     * AutoConfig, the bind/value/targets/models commands, ModelManager,
     * ScriptManager, ThemeManager, ScreenManager - so they stay as empty
     * methods rather than breaking those callers. The native screen reads
     * config live on every render, so it never needs an explicit refresh.
     */
    fun sync() {}

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
