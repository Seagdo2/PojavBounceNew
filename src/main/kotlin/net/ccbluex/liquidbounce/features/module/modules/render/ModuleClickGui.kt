/*
 * ModuleClickGui23 —— 打开 ClickGuiScreen 的模块
 *
 * 功能:
 *   - 快捷键(RightShift/ESC)打开/关闭 ClickGUI
 *   - 自定义 ClickGUI 颜色、不透明度、大小
 *   - 兼容所有现有 API
 *
 * 【本版修复】
 *   - ESC 关闭 ClickGUI 时不再同步 setScreen(null), 改为延迟到下一 tick 关闭,
 *     让本次 ESC 按键由 ClickGuiScreen 自身(shouldCloseOnEsc/keyPressed)消费,
 *     避免 ESC 漏到游戏输入(keyPause)导致弹出暂停菜单。
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.handler
import org.lwjgl.glfw.GLFW

object ModuleClickGui :
    ClientModule(
        "ClickGUI",
        ModuleCategories.RENDER,
        bind = GLFW.GLFW_KEY_RIGHT_SHIFT,
        disableActivation = true,
    ) {

    // ==================== 自定义设置 ====================
    /** GUI 整体缩放 (0.5 ~ 2.0) */
    val guiScale by float("Scale", 1.0f, 0.5f..2.0f)

    /** 面板背景不透明度 (0.1 ~ 1.0) */
    val bgAlpha by float("BackgroundAlpha", 0.69f, 0.1f..1.0f)

    /** 面板背景颜色 R/G/B (0~255) */
    val bgColorR by int("BgColor-R", 0x0D, 0..255)
    val bgColorG by int("BgColor-G", 0x0D, 0..255)
    val bgColorB by int("BgColor-B", 0x12, 0..255)

    /** 未激活模块字体颜色 R/G/B (0~255) */
    val textColorR by int("TextColor-R", 0xC8, 0..255)
    val textColorG by int("TextColor-G", 0xC8, 0..255)
    val textColorB by int("TextColor-B", 0xCC, 0..255)

    /** 激活模块/分类标题字体颜色 R/G/B (0~255) */
    val activeTextColorR by int("ActiveColor-R", 0x56, 0..255)
    val activeTextColorG by int("ActiveColor-G", 0xB4, 0..255)
    val activeTextColorB by int("ActiveColor-B", 0xE9, 0..255)

    // ==================== 便捷访问 (供 ClickGuiScreen 读取) ====================
    fun getScale(): Float = try { guiScale } catch (_: Exception) { 1.0f }
    fun getBgAlphaFloat(): Float = try { bgAlpha } catch (_: Exception) { 0.69f }

    /** 构建面板背景颜色 ARGB: Alpha=bgAlpha*255, R=bgColorR, G=bgColorG, B=bgColorB */
    fun getBgColor(): Int {
        return try {
            val a = (getBgAlphaFloat() * 255f).toInt().coerceIn(0, 255)
            (a shl 24) or (bgColorR shl 16) or (bgColorG shl 8) or bgColorB
        } catch (_: Exception) { 0xB00D0D12.toInt() }
    }

    /** 构建未激活字体颜色 ARGB: Alpha=FF, R=textColorR, G=textColorG, B=textColorB */
    fun getTextColor(): Int {
        return try {
            0xFF000000.toInt() or (textColorR shl 16) or (textColorG shl 8) or textColorB
        } catch (_: Exception) { 0xFFC8C8CC.toInt() }
    }

    /** 构建激活/标题字体颜色 ARGB: Alpha=FF, R=activeTextColorR, G=activeTextColorG, B=activeTextColorB */
    fun getActiveTextColor(): Int {
        return try {
            0xFF000000.toInt() or (activeTextColorR shl 16) or (activeTextColorG shl 8) or activeTextColorB
        } catch (_: Exception) { 0xFF56B4E9.toInt() }
    }

    // ==================== 模块行为 ====================
    override val running get() = true

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (event.action != 1) return@handler
        val code = event.keyCode
        // ESC 关闭: GLFW_KEY_ESCAPE = 256
        // 【修复】ESC 仅用于关闭, 绝不开打; 只有当前屏幕是 ClickGuiScreen 时才响应。
        // 关键点: 不要在按键事件内同步 setScreen(null)——
        // 本事件在 mixin 头部触发, 若同步关闭, 原版 KeyboardHandler 会按「无界面」分支
        // 把本次 ESC 注册到 keyPause, 同帧弹出游戏暂停菜单。
        // 改为延迟到下一 tick 关闭: 本次 ESC 由屏幕自身(keyPressed/shouldCloseOnEsc)
        // 正常消费, 游戏侧完全收不到该按键, 暂停菜单不会出现。
        if (code == GLFW.GLFW_KEY_ESCAPE) {
            val currentScreen = mc.gui.screen()
            if (currentScreen is ClickGuiScreen) {
                mc.execute { closeGui() }
            }
            // 无论屏幕是什么状态, ESC 分支到此为止, 不做任何打开操作
            return@handler
        }
        // 只精确响应右 Shift (GLFW_KEY_RIGHT_SHIFT = 344)
        if (code == GLFW.GLFW_KEY_RIGHT_SHIFT) {
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
        try {
            mc.gui.setScreen(ClickGuiScreen())
            return
        } catch (_: NoSuchMethodError) {
        }
        try {
            mc.javaClass.getMethod("setScreen", net.minecraft.client.gui.screens.Screen::class.java)
                ?.invoke(mc, ClickGuiScreen())
            return
        } catch (_: Exception) {
        }
        mc.execute {
            mc.gui.setScreen(ClickGuiScreen())
        }
    }

    private fun closeGui() {
        try {
            mc.gui.setScreen(null)
            return
        } catch (_: NoSuchMethodError) {
        }
        try {
            mc.javaClass.getMethod("setScreen", net.minecraft.client.gui.screens.Screen::class.java)
                ?.invoke(mc, null)
            return
        } catch (_: Exception) {
        }
        mc.execute {
            mc.gui.setScreen(null)
        }
    }

    // ==================== 兼容 API ====================
    fun sync() {}
    fun invalidate() {}
    val isInSearchBar: Boolean get() = false
    fun updateStandaloneScreen(): Boolean = false
}
