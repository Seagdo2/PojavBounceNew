/*
 * ModuleClickGui —— 打开 ClickGuiScreen 的模块
 *
 * FIX: 使用 InputConstants 替代 GLFW（26.3 不直接暴露 GLFW）
 * FIX: 加 @AddonApi 注解（ABI 检查需要）
 * FIX: 加 import mc（mc.gui.screen() 等）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.addon.AddonApi
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc

@AddonApi
object ModuleClickGui :
    ClientModule(
        "ClickGUI",
        ModuleCategories.RENDER,
        bind = InputConstants.KEY_RSHIFT,
        disableActivation = true,
    ) {

    // ==================== 自定义设置 ====================
    val guiScale by float("Scale", 1.0f, 0.5f..2.0f)
    val bgAlpha by float("BackgroundAlpha", 0.69f, 0.1f..1.0f)

    val bgColorR by int("BgColor-R", 0x0D, 0..255)
    val bgColorG by int("BgColor-G", 0x0D, 0..255)
    val bgColorB by int("BgColor-B", 0x12, 0..255)

    val textColorR by int("TextColor-R", 0xC8, 0..255)
    val textColorG by int("TextColor-G", 0xC8, 0..255)
    val textColorB by int("TextColor-B", 0xCC, 0..255)

    val activeTextColorR by int("ActiveColor-R", 0x56, 0..255)
    val activeTextColorG by int("ActiveColor-G", 0xB4, 0..255)
    val activeTextColorB by int("ActiveColor-B", 0xE9, 0..255)

    fun getScale(): Float = try { guiScale } catch (_: Exception) { 1.0f }
    fun getBgAlphaFloat(): Float = try { bgAlpha } catch (_: Exception) { 0.69f }

    fun getBgColor(): Int {
        return try {
            val a = (getBgAlphaFloat() * 255f).toInt().coerceIn(0, 255)
            (a shl 24) or (bgColorR shl 16) or (bgColorG shl 8) or bgColorB
        } catch (_: Exception) { 0xB00D0D12.toInt() }
    }

    fun getTextColor(): Int {
        return try {
            0xFF000000.toInt() or (textColorR shl 16) or (textColorG shl 8) or textColorB
        } catch (_: Exception) { 0xFFC8C8CC.toInt() }
    }

    fun getActiveTextColor(): Int {
        return try {
            0xFF000000.toInt() or (activeTextColorR shl 16) or (activeTextColorG shl 8) or activeTextColorB
        } catch (_: Exception) { 0xFF56B4E9.toInt() }
    }

    // ==================== 模块行为 ====================
    override val running get() = true

    /**
     * FIX: 使用 InputConstants 替代 GLFW。
     * KeyboardKeyEvent.keyCode 是 GLFW v3 键码（256=ESC, 344=RIGHT_SHIFT）。
     */
    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (event.action != 1) return@handler
        val code = event.keyCode

        // ESC = 256 (GLFW v3)
        if (code == 256) {
            val currentScreen = mc.gui.screen()
            if (currentScreen is ClickGuiScreen) {
                mc.execute { closeGui() }
            }
            return@handler
        }
        // RIGHT_SHIFT = 344 (GLFW v3)
        if (code == 344) {
            val currentScreen = mc.gui.screen()
            if (currentScreen == null) {
                openGui()
            } else if (currentScreen is ClickGuiScreen) {
                closeGui()
            }
        }
    }

    override suspend fun enabledEffect() {
        if (mc.gui.screen() !is ClickGuiScreen) {
            openGui()
        }
    }

    private fun openGui() {
        mc.execute { mc.gui.setScreen(ClickGuiScreen()) }
    }

    private fun closeGui() {
        mc.execute { mc.gui.setScreen(null) }
    }

    // ==================== 兼容 API ====================
    fun sync() {}
    fun invalidate() {}
    val isInSearchBar: Boolean get() = false
    fun updateStandaloneScreen(): Boolean = false
}
