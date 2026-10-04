/*
 * ============================================================================
 *  ModuleWatermark —— 移植 LiquidBounce 1.8.9 的 WaterMark 模块 (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  原版功能 (WaterMark.java, 1.8.9):
 *   1. 水印横幅: 屏幕顶部中央, 依次显示
 *      [Logo图标] 客户端名 · [用户图标] 用户名 · [Ping图标] ping ms to 服务器IP · [FPS图标] fps
 *      - 从屏幕中央向两侧展开动画 (EaseOutExpo)
 *      - 半透明圆角背景 + 可选阴影
 *   2. 模块开关通知: 顶部中央弹出通知条 (标题 + 描述 + 滑动开关按钮动画)
 *
 *  移植说明:
 *   - 1.8.9 的自定义资源图标 (lizz/logo_icon.png 等) 用原生几何图形还原
 *   - Fonts (GoogleSans40) 用 mc.font (同为 9px 高) 替代
 *   - AnimationUtil / GlowUtils 用帧率无关指数平滑 / 多层半透明圆角替代
 *   - 服务器信息: mc.currentServer / 玩家 ping: connection.getPlayerInfo(uuid).latency
 *
 *  可调节项 (25+): 位置 X/Y、缩放、客户端名、各段显示开关 (Logo/用户名/Ping/FPS/服务器IP)、
 *        Custom IP、图标开关、强调色/Ping色/文字色、背景/透明度/圆角/描边、
 *        阴影与强度、模块通知开关/隐藏模块通知/通知时长/最大条数、动画速度等。
 *
 *  渲染: 完全原生 —— OverlayRenderEvent + GuiGraphicsExtractor
 *        (drawRoundedRect / drawQuad / drawCircle / mc.font), 无任何 Web 依赖。
 *
 *  安装:
 *    1. 放入 src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/ModuleWatermark.kt
 *    2. ModuleManager.kt: import + builtin 列表加 ModuleWatermark,
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleWatermark : ClientModule("Watermark", ModuleCategories.RENDER, aliases = listOf("WaterMark")) {

    /* ============================= 可调节项 ============================= */

    // —— 布局 ——
    private val offsetX by int("Offset X", 0, -500..500)
    private val offsetY by int("Offset Y", 0, 0..800)          // 距顶部 (原版 height/20)
    private val scaleValue by float("Scale", 1f, 0.5f..2f)

    // —— 水印内容 ——
    private val clientName by text("Client Name", "LiquidBounce")
    private val showLogo by boolean("Show Logo", true)
    private val showUsername by boolean("Show Username", true)
    private val showPing by boolean("Show Ping", true)
    private val showFps by boolean("Show FPS", true)
    private val showServerIp by boolean("Show Server IP", true)
    private val showIcons by boolean("Show Icons", true)
    private val customIp by boolean("Custom IP", false)
    private val customIpValue by text("IP", "mc.hypixel.net")

    // —— 外观 ——
    private val accentColor by color("Accent Color", Color4b(20, 150, 180))
    private val pingColor by color("Ping Color", Color4b(0, 255, 0))
    private val textColor by color("Text Color", Color4b.WHITE)
    private val background by boolean("Background", true)
    private val backgroundAlpha by int("Background Alpha", 160, 0..255)
    private val backgroundRadius by int("Background Radius", 6, 0..16)
    private val border by boolean("Border", false)
    private val textShadow by boolean("Text Shadow", true)

    // —— 阴影 (原版 GlowUtils 近似: 多层半透明圆角) ——
    private val shadow by boolean("Shadow", false)
    private val shadowStrength by int("Shadow Strength", 1, 1..2)

    // —— 模块通知 ——
    private val moduleNotify by boolean("Module Notify", true)
    private val notifyHiddenModules by boolean("Notify Hidden Modules", false)
    private val notificationDuration by int("Notification Duration", 3000, 500..10000)
    private val maxNotifications by int("Max Notifications", 4, 1..8)

    // —— 动画 ——
    private val animationSpeed by float("Animation Speed", 0.45f, 0.05f..1f)

    /* ============================= 内部结构 ============================= */

    private enum class IconType { LOGO, USER, PING, FPS }

    private data class Segment(val icon: IconType?, val text: String, val color: Color4b)

    /** 通知基类 */
    private abstract class Notification(
        var title: String,
        var message: String,
        val duration: Long,
    ) {
        val createTime: Long = System.currentTimeMillis()
        var markedForDelete = false
        val elapsed: Long get() = System.currentTimeMillis() - createTime
        val expired: Boolean get() = elapsed >= duration
        /** 消失前 400ms 淡出 */
        val fadeAlpha: Float
            get() {
                val remaining = duration - elapsed
                return if (remaining < 400) (remaining / 400f).coerceIn(0f, 1f) else 1f
            }
        val height: Float get() = 37f
        open fun update() {
            if (expired) markedForDelete = true
        }
        // 注意: draw 由对象内的扩展函数 Notification.draw(...) 实现,
        // 基类不声明抽象成员, 否则子类必须 override 而扩展函数不算重写。
    }

    /** 模块开关通知 (开关按钮 + 标题 + 描述) */
    private class ToggleNotification(
        title: String,
        message: String,
        duration: Long,
        val enabled: Boolean,
        val moduleName: String,
    ) : Notification(title, message, duration) {
        var buttonAnim = if (enabled) 1f else 0f
    }

    /* ============================= 内部状态 ============================= */

    private val notifications = ArrayList<Notification>()
    private var lastFrameNs = 0L
    private var startupTime = 0L

    // 水印展开动画 (原版 AnimStartX / AnimEndX)
    private var animStartX = 0f
    private var animEndX = 100f
    // 通知面板高度动画 (原版 AnimModuleEndY)
    private var animModuleEndY = 37f

    /* ========================= 模块开关通知入口 ========================= */

    /** 发送模块开关通知 (供其他代码调用) */
    fun showToggleNotification(
        title: String,
        message: String,
        enabled: Boolean,
        duration: Long = notificationDuration.toLong(),
        moduleName: String? = null,
    ) {
        // 同模块已有通知 → 刷新 (重置时间)
        if (moduleName != null) {
            val existing = notifications.filterIsInstance<ToggleNotification>()
                .firstOrNull { it.moduleName == moduleName }
            if (existing != null) {
                existing.title = title
                existing.message = message
                existing.markedForDelete = false
                existing.buttonAnim = if (enabled) 1f else 0f
                notifications.remove(existing)
                notifications.add(0, existing)
                return
            }
        }
        while (notifications.size >= maxNotifications) {
            notifications.removeAt(notifications.lastIndex)
        }
        notifications.add(0, ToggleNotification(title, message, duration, enabled, moduleName ?: ""))
    }

    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { event ->
        if (!moduleNotify) return@handler
        if (event.moduleName == name) return@handler
        if (event.hidden && !notifyHiddenModules) return@handler
        // 启动加载过滤 (进世界 3 秒内自动启用的模块不通知)
        if (System.currentTimeMillis() - startupTime < 3000) return@handler
        showToggleNotification(
            event.moduleName,
            if (event.enabled) "已启用" else "已禁用",
            event.enabled,
            notificationDuration.toLong(),
            event.moduleName,
        )
    }

    override suspend fun enabledEffect() {
        startupTime = System.currentTimeMillis()
    }

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font

        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        } else {
            0.016f
        }
        lastFrameNs = now
        val smooth = (1f - exp(-animationSpeed * 60f * frameTime)).coerceIn(0f, 1f)

        // 更新通知
        updateNotifications()

        // 整体缩放 (锚定屏幕左上角, 偏移可用 Offset X/Y 微调)
        context.pose().withPush {
            scale(scaleValue, scaleValue)
            if (notifications.isNotEmpty() && moduleNotify) {
                drawNotificationsUI(context, font, smooth)
            } else {
                drawWatermark(context, font, smooth)
            }
        }
    }

    private fun updateNotifications() {
        val iterator = notifications.iterator()
        while (iterator.hasNext()) {
            val n = iterator.next()
            n.update()
            if (n.markedForDelete) {
                iterator.remove()
            }
        }
    }

    /* ============================= 水印渲染 ============================= */

    private fun drawWatermark(ctx: GuiGraphicsExtractor, font: Font, smooth: Float) {
        val username = mc.user.name
        val fps = mc.fps
        val ping = try {
            mc.player?.connection?.getPlayerInfo(mc.player!!.uuid)?.latency ?: 0
        } catch (_: Exception) {
            0
        }
        val serverIp = if (customIp) {
            customIpValue
        } else {
            mc.currentServer?.ip?.takeIf { it.isNotEmpty() } ?: "SinglePlayer"
        }

        // 构建段 (还原原版顺序: Logo名 · 用户名 · ping ms to IP · fps)
        val segments = buildList {
            if (showLogo) add(Segment(IconType.LOGO, clientName, accentColor))
            if (showUsername) add(Segment(IconType.USER, username, textColor))
            if (showPing) add(Segment(IconType.PING, "$ping ms", pingColor))
            if (showServerIp) add(Segment(null, " to $serverIp", textColor))
            if (showFps) add(Segment(IconType.FPS, "$fps fps", textColor))
        }
        if (segments.isEmpty()) return

        val iconSize = 13f
        val padding = 8f
        val elementSpacing = 6f
        val dotSpacing = 12f
        val containerHeight = 28f

        // 总宽度 (原版公式: padding + 各段[图标+间距+文字] + 段间 dotSpacing + padding + 10)
        var totalWidth = padding * 2 + 10f
        segments.forEachIndexed { index, seg ->
            if (index > 0) totalWidth += dotSpacing
            if (seg.icon != null && showIcons) totalWidth += iconSize + elementSpacing
            totalWidth += font.width(seg.text).toFloat()
        }

        val screenWidth = ctx.guiWidth().toFloat()
        val startY = offsetY.toFloat()
        val targetStartX = (screenWidth - totalWidth) / 2f + offsetX
        val targetEndX = targetStartX + totalWidth

        // 展开动画 (从屏幕中央向两侧)
        animStartX += (targetStartX - animStartX) * smooth
        animEndX += (targetEndX - animEndX) * smooth

        // 阴影 (多层半透明圆角近似 glow)
        if (shadow) {
            val layers = if (shadowStrength >= 2) 3 else 2
            for (i in 1..layers) {
                val inset = -i * 2f
                ctx.drawRoundedRect(
                    animStartX + inset, startY + inset, animEndX - inset, startY + containerHeight - inset,
                    backgroundRadius.toFloat(), Color4b(0, 0, 0, 24)
                )
            }
        }

        // 背景 (原版圆角容器)
        if (background) {
            ctx.drawRoundedRect(
                animStartX, startY, animEndX, startY + containerHeight,
                backgroundRadius.toFloat(), Color4b(0, 0, 0, backgroundAlpha)
            )
        }
        if (border) {
            ctx.drawRoundedRect(
                animStartX, startY, animEndX, startY + containerHeight,
                backgroundRadius.toFloat(), Color4b.TRANSPARENT, Color4b(255, 255, 255, 60), 1f
            )
        }

        // 文字基线 (9px 字体在 28px 容器中垂直居中)
        val textBaseY = startY + (containerHeight - font.lineHeight) / 2f + font.lineHeight - 8f
        val iconY = startY + (containerHeight - iconSize) / 2f
        var currentX = animStartX + padding

        segments.forEachIndexed { index, seg ->
            if (index > 0) currentX += dotSpacing
            if (seg.icon != null && showIcons) {
                drawWatermarkIcon(ctx, seg.icon, currentX, iconY, iconSize, seg.color)
                currentX += iconSize + elementSpacing
            }
            ctx.text(font, seg.text, currentX.roundToInt(), textBaseY.roundToInt(), seg.color.argb, textShadow)
            currentX += font.width(seg.text).toFloat()
        }
    }

    /** 几何图标 (替代 1.8.9 的资源图片) */
    private fun drawWatermarkIcon(ctx: GuiGraphicsExtractor, type: IconType, x: Float, y: Float, size: Float, color: Color4b) {
        when (type) {
            IconType.LOGO -> ctx.drawCircle(x + size / 2, y + size / 2, size / 2, colorGetter = { color.argb })
            IconType.USER -> {
                // 人形: 头圆 + 身圆
                ctx.drawCircle(x + size * 0.5f, y + size * 0.35f, size * 0.22f, colorGetter = { color.argb })
                ctx.drawCircle(x + size * 0.5f, y + size * 0.75f, size * 0.3f, colorGetter = { color.argb })
            }
            IconType.PING -> ctx.drawCircle(x + size / 2, y + size / 2, size / 2, colorGetter = { color.argb })
            IconType.FPS -> ctx.drawQuad(x + size * 0.2f, y + size * 0.2f, x + size * 0.8f, y + size * 0.8f, color)
        }
    }

    /* ============================= 通知渲染 ============================= */

    private fun drawNotificationsUI(ctx: GuiGraphicsExtractor, font: Font, smooth: Float) {
        var resultHeight = 0f
        var maxWidth = 0f
        for (n in notifications) {
            resultHeight += n.height
            maxWidth = maxOf(maxWidth, font.width(n.message).toFloat() + 45f)
        }
        if (resultHeight <= 0f) return

        val screenWidth = ctx.guiWidth().toFloat()
        val startY = offsetY.toFloat()
        val startX = (screenWidth - maxWidth) / 2f + offsetX

        // 背景动画 (原版 AnimStartX/AnimEndX/AnimModuleEndY)
        animModuleEndY += (startY + resultHeight - animModuleEndY) * smooth
        animStartX += (startX - animStartX) * smooth
        animEndX += (startX + maxWidth + 3f - animEndX) * smooth

        if (shadow) {
            val layers = if (shadowStrength >= 2) 3 else 2
            for (i in 1..layers) {
                val inset = -i * 2f
                ctx.drawRoundedRect(
                    animStartX + inset, startY + inset, animEndX - inset, animModuleEndY - inset,
                    10f, Color4b(0, 0, 0, 24)
                )
            }
        }
        ctx.drawRoundedRect(
            animStartX, startY, animEndX, animModuleEndY, 10f,
            Color4b(0, 0, 0, backgroundAlpha)
        )

        var currentY = startY
        for (n in notifications) {
            n.draw(ctx, font, startX, currentY, n.height)
            currentY += n.height
        }
    }

    /* ======================== 通知条目绘制工具 ======================== */

    private fun GuiGraphicsExtractor.drawToggleButton(
        x: Float, y: Float, boardH: Float, state: Boolean, anim: Float, alpha: Float,
    ) {
        val buttonHeight = 19f
        val buttonWidth = 30f
        val distance = 4f
        val rounded = buttonHeight / 2f
        val smallSize = buttonHeight - distance * 2f
        val startX = x + (boardH - buttonHeight) / 2f

        val a = (255 * alpha).roundToInt().coerceIn(0, 255)
        if (!state) {
            drawRoundedRect(x + 6f, y + startX, x + 6f + buttonWidth, y + startX + buttonHeight, rounded, Color4b(64, 64, 64, a))
        }
        val mainColor = if (state) accentColor.alpha(a) else Color4b(108, 108, 108, a)
        drawRoundedRect(
            x + 7f, y + startX + 1f, x + 7f + buttonWidth - 2f, y + startX + buttonHeight - 1f,
            rounded - 1f, mainColor
        )
        val smallX = x + 6f + distance + (buttonWidth - distance * 2f - smallSize) * anim
        val lightColor = if (state) {
            Color4b(
                (accentColor.r + 50).coerceAtMost(255),
                (accentColor.g + 50).coerceAtMost(255),
                (accentColor.b + 50).coerceAtMost(255), a
            )
        } else {
            Color4b(64, 64, 64, a)
        }
        drawRoundedRect(
            smallX, y + startX + distance, smallX + smallSize, y + startX + distance + smallSize,
            smallSize / 2f, lightColor
        )
    }

    private fun GuiGraphicsExtractor.drawToggleText(
        font: Font, x: Float, y: Float, title: String, description: String, boardH: Float, alpha: Float,
    ) {
        val a = (255 * alpha).roundToInt().coerceIn(0, 255)
        val textStartX = x + 42f
        val white = Color4b.WHITE.alpha(a)
        text(font, title, textStartX.roundToInt(), (y + boardH / 2f - 10f).roundToInt(), white.argb, textShadow)
        text(font, description, textStartX.roundToInt(), (y + boardH / 2f + 2f).roundToInt(), white.argb, textShadow)
    }

    /* ========================= 通知条目绘制 ========================= */

    private fun Notification.draw(ctx: GuiGraphicsExtractor, font: Font, x: Float, y: Float, boardH: Float) {
        val alpha = fadeAlpha
        if (alpha <= 0f) return
        when (this) {
            is ToggleNotification -> {
                // 按钮滑动动画 (启用 -> 1, 禁用 -> 0)
                buttonAnim += (if (enabled) 1f else 0f - buttonAnim) * 0.2f
                ctx.drawToggleButton(x, y, boardH, enabled, buttonAnim, alpha)
                ctx.drawToggleText(font, x, y, title, message, boardH, alpha)
            }
            else -> {
            }
        }
    }
}
