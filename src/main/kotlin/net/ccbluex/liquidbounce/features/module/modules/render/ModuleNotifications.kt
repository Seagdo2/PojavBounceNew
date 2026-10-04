/*
 * ============================================================================
 *  ModuleNotifications —— 仿 Opal v2 NotificationsElement 的通知 HUD (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  功能: 在屏幕右下角堆叠显示通知, 每条通知包含:
 *        - 类型图标 (Info / Success / Warning / Error) + 彩色图标底槽
 *        - 标题 (白色) + 描述 (灰色)
 *        - 底部类型色进度条 (随时间增长)
 *        - 从右边缘滑入 / 过期后滑出动画 (EASE_OUT_EXPO 近似)
 *
 *  LB 0.39 本身没有通知管理器 (只有 WebSocket 的 NotificationEvent),
 *  因此本模块内置轻量通知系统, 其他模块 / 脚本可调用:
 *      ModuleNotifications.notify("标题", "描述", NotificationType.SUCCESS, 3000)
 *
 *  可调节项 (20+): 位置 X/Y、缩放、通知高度、间距、最大同时显示数、
 *        背景、背景透明度、圆角、描边、图标开关、图标底槽开关、
 *        进度条开关与高度、标题颜色、描述颜色、文字阴影、动画速度、
 *        通知默认时长等。
 *
 *  渲染: 完全原生 —— OverlayRenderEvent + GuiGraphicsExtractor
 *        (drawRoundedRect / drawQuad / fillGradient / mc.font),
 *        不依赖任何 Web / 浏览器组件。
 *
 *  安装:
 *    1. 本文件放入
 *       src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/ModuleNotifications.kt
 *    2. ModuleManager.kt 中:
 *       - import 区 (render 模块 import 附近) 添加:
 *           import net.ccbluex.liquidbounce.features.module.modules.render.ModuleNotifications
 *       - builtin 模块列表中添加一行:  ModuleNotifications,
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleNotifications : ClientModule("Notifications", ModuleCategories.RENDER) {
init { enabled = true }
    /* ============================= 通知类型 ============================= */
    // 图标使用 ASCII 字符 (mc.font 保证渲染), 颜色对应 Opal 的类型色

    enum class NotificationType(val icon: String, val color: Color4b) {
        INFO("i", Color4b(0, 170, 255)),
        SUCCESS("+", Color4b(0, 220, 120)),
        WARNING("!", Color4b(255, 200, 40)),
        ERROR("x", Color4b(255, 70, 70)),
    }

    class Notification(
        val title: String,
        val description: String,
        val type: NotificationType,
        val duration: Long,
    ) {
        val startTime: Long = System.currentTimeMillis()
        /** 【优化】缓存计算好的面板宽度, 避免每帧重复测量文本 (减少卡顿) */
        var cachedWidth = -1f
        val elapsed: Long get() = System.currentTimeMillis() - startTime
        val hasExpired: Boolean get() = elapsed >= duration
        val progress: Float get() = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
    }

    private class AnimState(var x: Float)

    /* ============================= 通知音效 ============================= */

    private enum class NotificationSound(override val tag: String) : Tagged {
        NONE("None"),
        CLICK("Click"),
        POP("Pop"),
        CHIME("Chime"),
        LEVELUP("LevelUp"),
        CUSTOM("Custom"),
    }

    /* ============================= 可调节项 ============================= */

    // —— 布局 ——
    private val offsetX by int("Offset X", 4, 0..500)             // 距右边缘
    private val offsetY by int("Offset Y", 4, 0..500)             // 距底边缘
    private val scaleValue by float("Scale", 1.1f, 0.5f..2f)        // 整体缩放
    private val notificationHeight by int("Height", 30, 18..40)   // 单条高度
    private val spacing by int("Spacing", 3, 0..10)               // 条间距
    private val maxNotifications by int("Max Notifications", 5, 1..10)

    // —— 外观 ——
    private val background by boolean("Background", true)
    private val backgroundAlpha by int("Background Alpha", 80, 0..255)
    private val backgroundRadius by int("Background Radius", 5, 0..12)
    private val border by boolean("Border", false)
    private val showIcon by boolean("Show Icon", true)
    private val iconBackground by boolean("Icon Background", true)
    private val showProgressBar by boolean("Show Progress Bar", true)
    private val progressBarHeight by int("Progress Bar Height", 2, 1..8)
    private val titleColor by color("Title Color", Color4b.WHITE)
    private val descriptionColor by color("Description Color", Color4b(170, 170, 170))
    private val textShadow by boolean("Text Shadow", true)

    // —— 动画 / 时长 ——
    private val animationSpeed by float("Animation Speed", 50f, 0.5f..50f)
    private val defaultDuration by int("Default Duration", 2800, 500..10000)  // 毫秒
    private val welcomeNotification by boolean("Welcome Notification", true)   // 启用时发送欢迎通知

    // —— 通知音效 (按类型区分) ——
    private val enableSound by enumChoice("Enable Sound", NotificationSound.CUSTOM)    // 模块开启 (SUCCESS)
    private val disableSound by enumChoice("Disable Sound", NotificationSound.CUSTOM)    // 模块关闭 (ERROR)
    private val infoSound by enumChoice("Info Sound", NotificationSound.CUSTOM)         // 普通通知 (INFO/WARNING)
    private val soundVolume by float("Sound Volume", 1f, 0.1f..2f)
    // 自定义音效的资源 ID (对应 assets/liquidbounce/sounds/<name>.ogg)
    private val enableCustomId by text("Enable Custom ID", "liquidbounce:enable")
    private val disableCustomId by text("Disable Custom ID", "liquidbounce:disable")
    private val infoCustomId by text("Info Custom ID", "liquidbounce:info")

    // —— 模块开关通知 ——
    private val moduleToggleNotifications by boolean("Module Toggle Notifications", true)
    private val notifyHiddenModules by boolean("Notify Hidden Modules", false)  // 隐藏模块(如 HUD)开关也通知

    /* ============================= 内部状态 ============================= */

    private val notifications = ArrayList<Notification>()   // index 0 为最新(显示在底部)
    private val animations = HashMap<Notification, AnimState>()
    private var lastFrameNs = 0L
    private var startupTime = 0L                            // 模块启用时刻 (用于过滤启动加载通知)

    /* =========================== 通知发送 API =========================== */

    /**
     * 发送一条通知 (供其他模块 / 脚本调用)。
     * 动画在首次渲染时惰性创建, 初始位置为屏幕右边缘, 自然产生滑入效果。
     */
    fun notify(
        title: String,
        description: String = "",
        type: NotificationType = NotificationType.INFO,
        duration: Long = defaultDuration.toLong(),
    ) {
        // 超过最大数量时丢弃最旧的通知 (列表尾部)
        while (notifications.size >= maxNotifications) {
            val removed = notifications.removeAt(notifications.lastIndex)
            animations.remove(removed)
        }
        notifications.add(0, Notification(title, description, type, duration))
        playNotificationSound(type)
    }

    /** 播放通知音效 (按通知类型区分: 开启/关闭/普通), 原版 SoundManager, 不依赖 Web */
    private fun playNotificationSound(type: NotificationType) {
        val sound: NotificationSound
        val customId: String
        when (type) {
            NotificationType.SUCCESS -> {
                sound = enableSound          // 模块开启
                customId = enableCustomId
            }
            NotificationType.ERROR -> {
                sound = disableSound         // 模块关闭
                customId = disableCustomId
            }
            NotificationType.INFO, NotificationType.WARNING -> {
                sound = infoSound
                customId = infoCustomId
            }
        }
        if (sound == NotificationSound.NONE) {
            return
        }
        val volume = soundVolume
        val instance = when (sound) {
            NotificationSound.NONE -> return
            // UI_BUTTON_CLICK 是 Holder 重载
            NotificationSound.CLICK -> SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f)
            NotificationSound.POP -> SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1f, volume)
            NotificationSound.CHIME -> SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, volume)
            NotificationSound.LEVELUP -> SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1f, volume)
            // 自定义音效: 使用对应类型的 Custom ID
            NotificationSound.CUSTOM -> {
                val event = try {
                    SoundEvent.createVariableRangeEvent(Identifier.parse(customId))
                } catch (_: Exception) {
                    return
                }
                SimpleSoundInstance.forUI(event, 1f, volume)
            }
        }
        mc.soundManager.play(instance)
    }

    /** 清空全部通知 */
    fun clearNotifications() {
        notifications.clear()
        animations.clear()
    }

    /** 模块启用时发送欢迎通知, 便于验证效果 (可通过 "Welcome Notification" 关闭) */
    override suspend fun enabledEffect() {
        startupTime = System.currentTimeMillis()
        if (welcomeNotification) {
            notify("Notifications", "通知系统已启用", NotificationType.SUCCESS)
        }
    }

    /**
     * 监听模块开关事件: 任意模块被启用/禁用时发送通知。
     * 过滤: 自身开关、隐藏模块(可配置)、启动加载时自动启用的模块(3 秒内)。
     */
    @Suppress("unused")
    private val moduleToggleHandler = handler<ModuleToggleEvent> { event ->
        if (!moduleToggleNotifications) {
            return@handler
        }
        // 忽略自身开关, 避免自我通知
        if (event.moduleName == name) {
            return@handler
        }
        // 隐藏模块 (如 HUD 等) 可选通知
        if (event.hidden && !notifyHiddenModules) {
            return@handler
        }
        // 启动加载: 加入世界时配置中已启用的模块会触发 onToggled, 3 秒内忽略
        if (System.currentTimeMillis() - startupTime < 3000) {
            return@handler
        }

        notify(
            event.moduleName,
            if (event.enabled) "已启用" else "已禁用",
            if (event.enabled) NotificationType.SUCCESS else NotificationType.ERROR,
        )
    }

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font

        // 帧间隔(秒), 保证动画与帧率无关
        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        } else {
            0.016f
        }
        lastFrameNs = now
        val smooth = (1f - exp(-animationSpeed * frameTime)).coerceIn(0f, 1f)

        val guiWidth = context.guiWidth().toFloat()
        val guiHeight = context.guiHeight().toFloat()

        // 清理已滑出屏幕且过期的通知
        val iterator = notifications.iterator()
        while (iterator.hasNext()) {
            val notification = iterator.next()
            val anim = animations[notification]
            if (notification.hasExpired && anim != null && anim.x >= guiWidth - 1f) {
                iterator.remove()
                animations.remove(notification)
            }
        }
        if (notifications.isEmpty()) {
            return@handler
        }

        val padding = 3f
        val height = notificationHeight.toFloat()
        val iconOffset = 14f + padding   // 图标槽尺寸

        notifications.forEachIndexed { index, notification ->
            val anim = animations.getOrPut(notification) { AnimState(guiWidth) }

            // 宽度由内容决定 (参照 Opal: 至少 100, 图标 + 标题/描述较宽者)
            // 【优化】缓存宽度, 文本不变时不再每帧测量
            if (notification.cachedWidth < 0f) {
                val titleWidth = font.width(notification.title).toFloat()
                val descWidth = font.width(notification.description).toFloat()
                notification.cachedWidth = maxOf(100f, iconOffset + maxOf(titleWidth + padding * 4f, descWidth))
            }
            val width = notification.cachedWidth
            val endX = guiWidth - width - offsetX

            // 滑入 / 滑出动画 (EASE_OUT_EXPO 的指数平滑近似)
            val targetX = if (notification.hasExpired) guiWidth else endX
            anim.x += (targetX - anim.x) * smooth

            // 自底部向上堆叠: index 0 最新 → 最底部
            val y = guiHeight - offsetY - padding * 2f - (index + 1) * (height + spacing)

            // 完全移出屏幕则跳过
            if (anim.x > guiWidth + width || y + height < 0f || y > guiHeight) {
                return@forEachIndexed
            }

            context.pose().withPush {
                translate(anim.x, y)
                scale(scaleValue, scaleValue)

                // —— 背景 + 边框 ——
                if (background || border) {
                    val fill = if (background) Color4b(0, 0, 0, backgroundAlpha) else Color4b.TRANSPARENT
                    val outline = if (border) Color4b(255, 255, 255, 120) else Color4b.TRANSPARENT
                    if (backgroundRadius > 0) {
                        context.drawRoundedRect(
                            0f, 0f, width, height,
                            backgroundRadius.toFloat(), fill, outline, 1f
                        )
                    } else {
                        context.drawQuad(0f, 0f, width, height, fill, outline)
                    }
                }

                // —— 底部进度条 (类型色, 随时间增长) ——
                if (showProgressBar) {
                    val barW = (width - 0.5f) * notification.progress
                    if (barW > 0.5f) {
                        context.drawRoundedRect(
                            0.5f, height - progressBarHeight.toFloat(),
                            0.5f + barW, height,
                            (progressBarHeight / 2f).coerceAtMost(3f),
                            notification.type.color.alpha(90)
                        )
                    }
                }

                // —— 类型图标 + 底槽 ——
                if (showIcon) {
                    if (iconBackground) {
                        context.drawRoundedRect(
                            padding - 0.5f, padding / 2f + 0.5f,
                            padding - 0.5f + iconOffset, padding / 2f + 0.5f + iconOffset,
                            3f,
                            notification.type.color.darker().alpha(128)
                        )
                    }
                    val icon = notification.type.icon
                    val iconX = padding + (iconOffset - font.width(icon)) / 2f
                    // 字符在图标槽内垂直居中 (9px 字体的 baseline ≈ 中心 + 3.5)
                    val iconY = padding / 2f + 0.5f + iconOffset / 2f - 3.5f
                    context.text(
                        font, icon,
                        iconX.roundToInt(), iconY.roundToInt(),
                        notification.type.color.argb, textShadow
                    )
                }

                // —— 标题 (白色) ——
                context.text(
                    font, notification.title,
                    (padding * 2f + iconOffset).roundToInt(), (padding * 3f).roundToInt(),
                    titleColor.argb, textShadow
                )

                // —— 描述 (灰色, 第二行) ——
                if (notification.description.isNotEmpty()) {
                    context.text(
                        font, notification.description,
                        (padding * 2f + iconOffset).roundToInt(), (padding * 3f + 8.5f).roundToInt(),
                        descriptionColor.argb, textShadow
                    )
                }
            }
        }
    }
}
