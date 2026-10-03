/*
 * ModuleClickGui —— 打开 ClickGuiScreen 的模块
 *
 * ===== 修复说明 =====
 * BUG 1: enabledEffect() 永远不会被调用
 *   原因: disableActivation=true 导致 onToggled() 返回 false，
 *   enabled 值不会真正改变，onChanged 回调不触发，enabledEffect() 不执行。
 *   修复: 改用 onEnabled()——它在 onToggled 内部被调用，
 *   发生在 disableActivation 检查之前，所以一定会执行。
 *
 * BUG 2: keyHandler 检查 event.keyCode == 344（GLFW键码），永远不匹配
 *   原因: MC 26.3 使用 SDL 而非 GLFW。
 *   MixinKeyboardHandler 构造 KeyboardKeyEvent 时:
 *     keyCode = keyEvent.keycode()  → SDL keycode（不是 GLFW 344）
 *     scanCode = keyEvent.key()    → SDL scancode（= InputConstants.KEY_* 值）
 *   InputConstants.KEY_RSHIFT = 229（SDL scancode）
 *   InputConstants.KEY_ESCAPE = 41（SDL scancode）
 *   修复: 用 event.scanCode 和 InputConstants 常量替代硬编码的 GLFW 键码。
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
     * FIX 1: 用 onEnabled() 替代 enabledEffect()。
     *
     * 调用链: ModuleManager 设 m.enabled → ClientModule.onToggled(true)
     * → super.onToggled → Toggleable.onToggled → onEnabled()  ← 这里调用
     * → 回到 ClientModule.onToggled → disableActivation 检查 → return false
     *
     * onEnabled() 在 disableActivation 检查之前执行，所以一定会被调用。
     * 而 enabledEffect() 依赖 onChanged 回调，disableActivation 导致值不变
     * → onChanged 不触发 → enabledEffect() 永远不执行。
     */
    override fun onEnabled() {
        if (mc.gui.screen() !is ClickGuiScreen) {
            openGui()
        }
    }

    /**
     * FIX 2: keyHandler 用 event.scanCode（SDL scancode）而非 event.keyCode（SDL keycode）。
     *
     * MC 26.3 MixinKeyboardHandler 构造 KeyboardKeyEvent:
     *   keyCode  = keyEvent.keycode()  → SDL keycode（不是 GLFW 344）
     *   scanCode = keyEvent.key()      → SDL scancode（= InputConstants.KEY_* 值）
     *
     * InputConstants.KEY_RSHIFT = 229（SDL scancode，不是 GLFW 344）
     * InputConstants.KEY_ESCAPE = 41（SDL scancode，不是 GLFW 256）
     */
    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (event.action != 1) return@handler // 只处理按下
        val scancode = event.scanCode

        // ESC: InputConstants.KEY_ESCAPE = 41 (SDL scancode)
        if (scancode == InputConstants.KEY_ESCAPE) {
            val currentScreen = mc.gui.screen()
            if (currentScreen is ClickGuiScreen) {
                mc.execute { closeGui() }
            }
            return@handler
        }
        // RIGHT_SHIFT: InputConstants.KEY_RSHIFT = 229 (SDL scancode)
        if (scancode == InputConstants.KEY_RSHIFT) {
            val currentScreen = mc.gui.screen()
            if (currentScreen == null) {
                openGui()
            } else if (currentScreen is ClickGuiScreen) {
                closeGui()
            }
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
