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
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.ClickGuiScaleChangeEvent
import net.ccbluex.liquidbounce.event.events.ClickGuiValueChangeEvent
import net.ccbluex.liquidbounce.features.addon.AddonApi
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.game.isTyping
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.NativeClickGuiScreen
import net.ccbluex.liquidbounce.utils.client.inGame

/**
 * ClickGUI module
 *
 * Shows you an easy-to-use menu to toggle and configure modules.
 */
@AddonApi
object ModuleClickGui :
    ClientModule("ClickGUI", ModuleCategories.RENDER, bind = InputConstants.KEY_RSHIFT, disableActivation = true) {

    override val running get() = true

    /** GUI 整体缩放，默认调小 */
    val scale by float("Scale", 0.8f, 0.5f..2f).onChanged {
        EventManager.callEvent(ClickGuiScaleChangeEvent(it))
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    val searchBarAutoFocus by boolean("SearchBarAutoFocus", true).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    val glassMode by boolean("GlassMode", false).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /** 主题色 (0xAARRGGBB)，默认蓝色 */
    val accentColor by int("AccentColor", 0xFF4677FF.toInt(), 0..0xFFFFFFFF.toInt()).onChanged {
        ClickGuiPalette.accentColorVar = it
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /** 字体大小倍率 */
    val fontSize by float("FontSize", 0.8f, 0.5f..1.5f).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /** 面板宽度 */
    val panelWidth by int("PanelWidth", 200, 150..350).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /** 面板最大高度 */
    val panelMaxHeight by int("PanelMaxHeight", 300, 100..600).onChanged {
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /** 背景不透明度 (0~255)，默认100 */
    val bgAlpha by int("BackgroundAlpha", 100, 0..255).onChanged {
        ClickGuiPalette.bgAlphaVar = it
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    /** 背景颜色 R/G/B */
    val bgColorR by int("BgColor-R", 0, 0..255).onChanged { EventManager.callEvent(ClickGuiValueChangeEvent(this)) }
    val bgColorG by int("BgColor-G", 0, 0..255).onChanged { EventManager.callEvent(ClickGuiValueChangeEvent(this)) }
    val bgColorB by int("BgColor-B", 0, 0..255).onChanged { EventManager.callEvent(ClickGuiValueChangeEvent(this)) }

    init {
        ClickGuiPalette.accentColorVar = accentColor
        ClickGuiPalette.bgAlphaVar = bgAlpha
    }

    val isInSearchBar: Boolean
        get() {
            if (!isTyping) return false
            val screen = mc.gui.screen() ?: return false
            return screen is NativeClickGuiScreen && screen.isSearchFocused()
        }

    fun sync() {}
    fun invalidate() {}
    fun updateStandaloneScreen(): Boolean = false

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
        if (!LiquidBounce.isInitialized || !inGame) return
        mc.execute { mc.gui.setScreen(NativeClickGuiScreen()) }
        super.onEnabled()
    }
}
