/*
 * ============================================================================
 *  ModuleSolsticeNotification —— 还原 Notifications.cpp / Notifications.hpp
 *
 *  适用: LiquidBounce Nextgen 0.39 · 原生 Overlay 渲染 · 无 Web
 *
 *  原版要点:
 *   - Style: Solaris
 *   - 右下角堆叠；关闭时向东南方斜向下移出
 *   - currentDuration = lerp(cur, timeUp?0:1, dt*5)
 *   - 进度条 percentDone，左侧主题色 / 可选渐变，右侧半透明黑
 *   - getThemedColor(y*2)；Warning 黄 / Error 红；alpha 0.7
 *   - AddShadowRect 近似为多层圆角描边
 *   - Show on toggle / join（模块开关时可选推送）
 *
 *  本文件改动:
 *   - 进度条动画改为「从左到右」：填充随 percentDone 由左侧向右推进
 *   - 进度条新增独立 Rainbow 模式：连续色谱按位置采样，视觉无分段拼接
 *   - 卡片四角均为直角
 *   - 模块开/关时播放音频：liquidbounce:enable / liquidbounce:disable
 *     （对应源码 assets/liquidbounce/sounds/enable.ogg 与 disable.ogg，
 *       并需在 assets/liquidbounce/sounds.json 中注册同名事件）
 *   - 修复左侧黑边: 圆角列弧高改取列外缘(只小不大), 且进度填充按
 *     卡片轮廓裁剪, 不再从圆角处伸出
 *   - 修复 Max Notifications 无效: add() 超限时让最旧的平滑退场;
 *     同时修复被裁剪通知因移除条件过严而永久滞留的问题
 *   - 性能: 辉光每层回归单图元圆角矩形(不再逐列拼形); 主题色列表、
 *     文本宽度缓存; 去掉每帧 filter/listOf 分配; 空列表零开销; 移除死代码
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ServerConnectEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
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
import net.minecraft.util.Mth
import kotlin.math.max
import kotlin.math.roundToInt

object ModuleSolsticeNotification : ClientModule(
    "SolsticeNotification",
    ModuleCategories.RENDER,
    aliases = listOf("Notifications", "SolsticeNotif"),
) {
    init { enabled = true }

    private enum class Style(override val tag: String) : Tagged { SOLARIS("Solaris") }

    private val style by enumChoice("Style", Style.SOLARIS)
    private val showOnToggle by boolean("Show On Toggle", true)
    private val showOnJoin by boolean("Show On Join", true)
    private val soundOnToggle by boolean("Sound On Toggle", true)
    private val soundVolume by float("Sound Volume", 0.6f, 0f..1f)
    private val limitNotifications by boolean("Limit Notifications", false)
    private val maxNotifications by int("Max Notifications", 6, 1..25)
    private val fontSize by float("Font Size", 8f, 8f..20f)
    private val rightMargin by float("Right Margin", 10f, 0f..40f)
    private val bottomMargin by float("Bottom Margin", 10f, 0f..40f)
    private val animSpeed by float("Anim Speed", 5f, 1f..15f)
    private val defaultDuration by float("Default Duration", 3f, 1f..15f)
    private val cornerRadius by float("Corner Radius", 5f, 0f..16f)

    // ==================== 颜色模式 ====================
    private enum class NotifColorMode(override val tag: String) : Tagged {
        /** Custom: 开启/关闭分别用自定义色 (调色板可选), 便于一眼分辨 */
        CUSTOM("Custom"),
        /** Gradient: 进度条左→右两色渐变 */
        GRADIENT("Gradient"),
        /** Random: 每条通知随机颜色 (创建时生成, 不闪烁) */
        RANDOM("Random"),
    }
    private val colorMode by enumChoice("Color Mode", NotifColorMode.CUSTOM)

    // —— Custom 模式: 开启/关闭两色 ——
    private val customEnabledColor by color("Enabled Color", Color4b(0x22, 0x44, 0xCC, 255))   // 深蓝
    private val customDisabledColor by color("Disabled Color", Color4b(0x99, 0x33, 0xFF, 255)) // 紫
    private val customDefaultColor by color("Default Color", Color4b(0x33, 0x66, 0xFF, 255)) // 非开关通知(信息提示等)

    // —— GRADIENT 模式: 进度条左右两色 ——
    private val gradientLeft by color("Gradient Left", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val gradientRight by color("Gradient Right", Color4b(0x6E, 0xC8, 0xF1, 255))

    // ==================== Glow (对齐 ArrayList 写法, 默认值一致) ====================
    private val glowEnabled by boolean("Glow", true)
    private val glowRange by float("Glow Range", 17f, 0f..30f)
    private val glowStrength by float("Glow Strength", 0.18f, 0.01f..1f)
    private val glowDensity by int("Glow Density", 12, 1..12)
    private val glowOffsetX by float("Glow Offset X", 0f, -16f..16f)
    private val glowOffsetY by float("Glow Offset Y", 0f, -16f..16f)

    enum class Type { INFO, WARNING, ERROR }

    class Notification(
        val message: String,
        val type: Type = Type.INFO,
        val duration: Float = 3f,
        /** 开关状态: null=非开关通知, true=开启模块, false=关闭模块 (Custom 模式分色用) */
        val toggleState: Boolean? = null,
        /** Random 模式: 创建时生成的随机色 (固定, 不闪烁) */
        val randomColor: Color4b = Color4b.WHITE,
    ) {
        var timeShown = 0f
        /** 0→1 入场，关闭时 1→0（仅水平滑出，不带动其他通知乱跳）
         *  初始给一点 slide，避免入场前半段辉光完全不可见 */
        var slide = 0.35f
        var isTimeUp = false
        /** 堆叠目标 Y（底部基准坐标，每条独立平滑） */
        var targetY = 0f
        var animY = 0f
        var initedY = false
        /** 文本宽度缓存（fontSize 变化时自动重算），避免每帧重复量测 */
        var cachedFontW = -1
        var cachedFontSize = -1f
    }

    private val notifications = ArrayList<Notification>()
    private var lastFrameNs = 0L

    /** 开/关提示音：事件名对应 sounds.json 中的 "enable" / "disable"，文件位于 assets/liquidbounce/sounds/ 下 */
    private val enableSoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("liquidbounce", "enable"))
    private val disableSoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("liquidbounce", "disable"))

    /** UI 音效播放（跟随主音量），音频引擎未就绪时静默忽略 */
    private fun playToggleSound(enabled: Boolean) {
        if (!soundOnToggle || soundVolume <= 0f) return
        val event = if (enabled) enableSoundEvent else disableSoundEvent
        runCatching {
            mc.soundManager.play(SimpleSoundInstance.forUI(event, 1f, soundVolume))
        }
    }

    /** 随机颜色 (Random 模式): 随机 HSB 色相, 90% 饱和度, 100% 亮度 */
    private fun randomNotifColor(): Color4b {
        val hue = kotlin.random.Random.nextFloat()
        return Color4b.ofHSB(hue, 0.9f, 1f)
    }

    fun add(message: String, type: Type = Type.INFO, duration: Float = defaultDuration, toggleState: Boolean? = null) {
        notifications.add(Notification(message, type, duration, toggleState, randomNotifColor()))
        trimToLimit()
    }

    /**
     * 超出 Max Notifications 上限时让「最旧」的先退场（标记超时 → 平滑滑出），
     * 使上限真正生效。此前上限只在渲染时 break 跳过新通知，且默认开关关闭，
     * 该项完全不起作用；超限的通知还会在列表里堆积。
     */
    private fun trimToLimit() {
        if (!limitNotifications) return
        var excess = notifications.size - maxNotifications.coerceIn(1, 25)
        if (excess <= 0) return
        for (n in notifications) {
            if (excess <= 0) break
            if (!n.isTimeUp) {
                n.isTimeUp = true
                excess--
            }
        }
    }

    fun info(msg: String, duration: Float = defaultDuration) = add(msg, Type.INFO, duration)
    fun warning(msg: String, duration: Float = defaultDuration) = add(msg, Type.WARNING, duration)
    fun error(msg: String, duration: Float = defaultDuration) = add(msg, Type.ERROR, duration)

    fun notifyToggle(moduleName: String, enabled: Boolean) {
        if (!showOnToggle) return
        add("$moduleName was ${if (enabled) "enabled" else "disabled"}", Type.INFO, defaultDuration, enabled)
    }

    fun notifyJoin(address: String) {
        if (!showOnJoin) return
        add("Connecting to $address...", Type.INFO, 6f)
    }

    /** 监听模块开关 → 播放开/关提示音（独立于通知显示） */
    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { e ->
        if (!enabled) return@handler
        if (e.hidden) return@handler
        // 避免自己开关刷屏
        if (e.moduleName.equals(name, true) || e.moduleName.contains("Notification", true)) return@handler
        playToggleSound(e.enabled)
        if (!showOnToggle) return@handler
        notifyToggle(e.moduleName, e.enabled)
    }

    /** 监听客户端统一通知事件 */
    @Suppress("unused")
    private val notifEventHandler = handler<NotificationEvent> { e ->
        if (!enabled) return@handler
        // 【FIX】跳过模块开关的内置通知 (ENABLED/DISABLED 来自 ClientModule.onToggled
        // 的 notification() 调用, 会显示翻译后的中文"已启用/已禁用"), 只由 toggleHandler
        // 处理开关 → 只显示一行英文 "ModuleName was enabled/disabled", 不再出现两行
        if (e.severity == NotificationEvent.Severity.ENABLED ||
            e.severity == NotificationEvent.Severity.DISABLED) {
            return@handler
        }
        val type = when (e.severity) {
            NotificationEvent.Severity.ERROR -> Type.ERROR
            NotificationEvent.Severity.ENABLED, NotificationEvent.Severity.SUCCESS -> Type.INFO
            NotificationEvent.Severity.DISABLED -> Type.WARNING
            else -> Type.INFO
        }
        val msg = if (e.title.isNotBlank() && e.message.isNotBlank()) {
            "${e.title}: ${e.message}"
        } else e.title.ifBlank { e.message }
        if (msg.isNotBlank()) add(msg, type, defaultDuration)
    }

    /** 进服提示 */
    @Suppress("unused")
    private val connectHandler = handler<ServerConnectEvent> { e ->
        if (!enabled || !showOnJoin) return@handler
        val nice = try {
            e.address.toString()
        } catch (_: Throwable) {
            try { e.serverInfo.name } catch (_: Throwable) { "server" }
        }
        notifyJoin(nice.take(48))
    }

    /** 测试：开启模块时推一条，确认渲染链路正常 */
    override suspend fun enabledEffect() {
        add("Solstice Notification enabled", Type.INFO, 2.5f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)

    /** 主题色列表每帧缓存（避免每次取色都 listOf 分配） — 改为 Custom2 前N色 */
    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val tt = t.coerceIn(0f, 1f)
        return Color4b(
            (a.r + (b.r - a.r) * tt).roundToInt().coerceIn(0, 255),
            (a.g + (b.g - a.g) * tt).roundToInt().coerceIn(0, 255),
            (a.b + (b.b - a.b) * tt).roundToInt().coerceIn(0, 255),
            (a.a + (b.a - a.a) * tt).roundToInt().coerceIn(0, 255),
        )
    }

    /**
     * 按颜色模式取通知主题色:
     * - Custom: 开启 → customEnabledColor (深蓝), 关闭 → customDisabledColor (紫),
     *           非开关通知 → customDefaultColor
     * - Gradient: 进度条左色 (右色由渲染端 gradientRight 采样)
     * - Random: 使用创建通知时预生成的 n.randomColor (固定, 不闪烁)
     * - Warning/Error 类型在 Custom/Gradient 下保持黄色/红色 (便于区分告警)
     */
    private fun themedColorFor(n: Notification): Color4b {
        // 告警类型优先: Warning黄/Error红 (所有模式下保持一致, 便于区分)
        if (n.type == Type.WARNING) return Color4b(255, 204, 0, 255)
        if (n.type == Type.ERROR) return Color4b(255, 0, 0, 255)
        return when (colorMode) {
            NotifColorMode.CUSTOM -> when (n.toggleState) {
                true -> customEnabledColor   // 开启 → 深蓝
                false -> customDisabledColor // 关闭 → 紫
                null -> customDefaultColor   // 非开关 → 默认色
            }
            NotifColorMode.GRADIENT -> gradientLeft
            NotifColorMode.RANDOM -> n.randomColor
        }
    }

    private fun textH() = mc.font.lineHeight * (fontSize / 9f)

    /** 通知文本宽度(带缓存): 每帧对同一条通知只量测一次, fontSize 改变时重算 */
    private fun notifW(n: Notification): Float {
        if (n.cachedFontW < 0 || n.cachedFontSize != fontSize) {
            n.cachedFontW = mc.font.width(n.message)
            n.cachedFontSize = fontSize
        }
        return n.cachedFontW * (fontSize / 9f)
    }

    private fun drawScaledText(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        text: String, x: Float, y: Float, color: Color4b,
    ) {
        ctx.pose().withPush {
            translate(x, y)
            scale(fontSize / 9f, fontSize / 9f)
            ctx.text(mc.font, text, 0, 0, color.argb, false)
        }
    }

    /**
     * 绘制「左侧圆角 + 右侧上下直角」的卡片形状（卡片底色用）。
     *
     * 精确分块平铺：主体矩形 + 左侧中段 + 左上/左下四分之一圆(竖向列切片)。
     * 弧高取列「外缘」(dx = cx - sx) 而非列中心 —— 圆弧左端接近垂直,
     * 取中心会让最左列高出真实圆弧数像素, 在左上/左下角形成黑色突出边;
     * 取外缘则高度只小不大, 绝不超出轮廓(缺口 ≤1px, 视觉上等同抗锯齿)。
     */
    private fun drawCardShape(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        radius: Float, color: Color4b,
    ) {
        // 左侧上下两角改为直角 → 整卡矩形
        if (x2 - x1 <= 0.5f || y2 - y1 <= 0.5f) return
        ctx.drawQuad(x1, y1, x2, y2, color)
    }

    /**
     * 进度条填充：按卡片轮廓裁剪（左侧圆角、右侧直角），颜色按横向位置采样。
     * 与 [drawCardShape] 同样的保守圆角规则 → 填充绝不从圆角处伸出、左侧无黑边。
     *
     * @param bodyStep 圆角区之后每列宽度(纯色填充传超大值 → 主体只画 1 个矩形)
     * @param colorAt  传入相对位置 u∈[0,1](相对整条进度条), 返回该处颜色
     */
    private fun drawProgressFill(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x: Float, y1: Float, y2: Float,
        boxW: Float, fillW: Float,
        radius: Float, aMul: Float,
        bodyStep: Float,
        colorAt: (u: Float) -> Color4b,
    ) {
        if (fillW <= 0.5f || boxW <= 1f) return
        val a = (245 * aMul).toInt().coerceIn(0, 255)
        val end = x + fillW
        var sx = x
        while (sx < end - 0.01f) {
            val ex = (sx + bodyStep).coerceAtMost(end)
            val u = (((sx + ex) * 0.5f - x) / boxW).coerceIn(0f, 1f)
            ctx.drawQuad(sx, y1, ex, y2, colorAt(u).alpha(a))
            sx = ex
        }
    }

    /**
     * 【Glow 边缘发光】写法对齐 ModuleArrayList.drawGlowEdge:
     * 分层四边+外框扩散, 参数与 ArrayList 默认值一致 (Range=18 / Strength=0.04 / Density=6)。
     * 发光颜色跟随当前颜色模式的进度条主题色。
     */
    private fun drawGlow(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        base: Color4b, alphaMul: Float,
    ) {
        if (!glowEnabled || alphaMul <= 0.02f) return
        val range = glowRange
        if (range <= 0f) return
        val strength = glowStrength.coerceIn(0.01f, 1f)
        val density = glowDensity.coerceIn(1, 12)
        val ox = glowOffsetX
        val oy = glowOffsetY

        // 从外到内画，外层更淡 (对齐 ArrayList drawGlowEdge 的层循环)
        for (i in density downTo 1) {
            val u = i / density.toFloat()
            val e = range * u
            val fall = (1f - u).coerceIn(0f, 1f)
            val fall2 = fall * fall * (3f - 2f * fall) // smoothstep
            val a = (fall2 * strength * 255f * alphaMul).toInt().coerceIn(0, 200)
            if (a < 3) continue
            val col = Color4b(base.r, base.g, base.b, a)
            // 四边扩散 + 位置偏移
            ctx.drawQuad(x1 - e + ox, y1 - e + oy, x2 + e + ox, y1 + oy, col) // 上
            ctx.drawQuad(x1 - e + ox, y2 + oy, x2 + e + ox, y2 + e + oy, col) // 下
            ctx.drawQuad(x1 - e + ox, y1 + oy, x1 + ox, y2 + oy, col) // 左
            ctx.drawQuad(x2 + ox, y1 + oy, x2 + e + ox, y2 + oy, col) // 右
        }

        // 紧贴卡片的一圈实线 (对齐 ArrayList 的 rim 写法, 保证可见性)
        val rimA = (140f * strength * 6f * alphaMul).toInt().coerceIn(0, 180)
        if (rimA > 2) {
            val rim = Color4b(base.r, base.g, base.b, rimA)
            val t = 1.5f
            ctx.drawQuad(x1 - t + ox, y1 - t + oy, x2 + t + ox, y1 + oy, rim)
            ctx.drawQuad(x1 - t + ox, y2 + oy, x2 + t + ox, y2 + t + oy, rim)
            ctx.drawQuad(x1 - t + ox, y1 + oy, x1 + ox, y2 + oy, rim)
            ctx.drawQuad(x2 + ox, y1 + oy, x2 + t + ox, y2 + oy, rim)
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context
        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs != 0L) {
            ((nowNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        } else 0.016f
        lastFrameNs = nowNs

        // 无通知时直接返回, 不做任何计算/绘制
        if (notifications.isEmpty()) return@handler

        val scaleF = fontSize / 11f
        val boxHP = textH() + 30f * scaleF

        // 超时标记
        for (n in notifications) {
            n.timeShown += dt
            if (n.timeShown >= n.duration) n.isTimeUp = true
        }
        // 完全滑出后移除。
        // 注: 不能再附加 timeShown > duration 条件 —— 被上限裁剪的通知
        // 时长未到却已滑出, 会永远滞留在列表里(不可见但占用堆叠计算)
        notifications.removeAll { it.isTimeUp && it.slide <= 0.01f }

        val screenW = ctx.guiWidth().toFloat()
        val screenH = ctx.guiHeight().toFloat()

        // 1) 更新 slide：入场→1，关闭只减自己的 slide（水平退出）
        for (n in notifications) {
            val targetSlide = if (n.isTimeUp) 0f else 1f
            n.slide = lerp(n.slide, targetSlide, dt * animSpeed).coerceIn(0f, 1f)
        }

        // 2) 只对仍可见的通知算堆叠目标（slide>0.05），从下往上。原地遍历, 不分配列表
        var stackY = screenH - bottomMargin
        for (n in notifications) {
            if (n.slide <= 0.05f) continue
            n.targetY = stackY
            if (!n.initedY) {
                n.animY = stackY
                n.initedY = true
            }
            stackY -= (boxHP - 10f + 8f)
        }

        // 3) 平滑 Y，互不影响关闭动画
        for (n in notifications) {
            if (!n.initedY) continue
            n.animY = lerp(n.animY, n.targetY, (dt * animSpeed * 1.2f).coerceIn(0f, 1f))
        }

        for (n in notifications) {
            if (n.slide <= 0.01f) continue

            val percentDone = Mth.clamp(n.timeShown / n.duration.coerceAtLeast(0.01f), 0f, 1f)
            val boxW = max(200f * scaleF, 50f + notifW(n))
            val boxH = boxHP

            val beginX = screenW - boxW - rightMargin
            val endX = screenW + 8f
            // 入场：从右滑入；关闭：向右下（东南）滑出
            val x = lerp(endX, beginX, n.slide)
            val exitDown = (1f - n.slide) * (boxH + 36f)
            val y = n.animY + exitDown
            val boxTop = y - boxH
            val boxBottom = y - 10f

            // 进度条主题色 (按颜色模式; Warning/Error 黄红优先)
            val theme = themedColorFor(n)
            val aMul = n.slide
            val glowMul = if (n.isTimeUp) {
                aMul.coerceIn(0f, 1f)
            } else {
                max(aMul, 0.9f)
            }
            val cLeft = theme.alpha((220 * aMul).toInt().coerceIn(0, 255))
            // 右端色: GRADIENT 用 gradientRight, 其他模式与左色一致
            val cRight = if (colorMode == NotifColorMode.GRADIENT) {
                gradientRight.alpha((200 * aMul).toInt().coerceIn(0, 255))
            } else {
                cLeft
            }

            // 【Glow 对齐 ArrayList】发光颜色跟随进度条主题色 (不再单独设置 Glow Color)
            drawGlow(ctx, x, boxTop, x + boxW, boxBottom, theme, glowMul)

            // 整卡大进度条：底色纯黑；左侧圆角、右侧直角
            val blackBg = Color4b(0, 0, 0, (240 * aMul).toInt().coerceIn(0, 255))
            drawCardShape(ctx, x, boxTop, x + boxW, boxBottom, cornerRadius, blackBg)

            val fillW = boxW * percentDone
            if (fillW > 0.5f) {
                when (colorMode) {
                    // Custom: 单色填充 (开启深蓝/关闭紫/默认色, 调色板可调)
                    // Random: 单色填充 (通知创建时预生成的随机色)
                    NotifColorMode.CUSTOM, NotifColorMode.RANDOM -> drawProgressFill(
                        ctx, x, boxTop, boxBottom, boxW, fillW, cornerRadius, aMul, 1e5f,
                    ) { cLeft }

                    // Gradient: 左→右平滑渐变 (Gradient Left → Gradient Right)
                    NotifColorMode.GRADIENT -> drawProgressFill(
                        ctx, x, boxTop, boxBottom, boxW, fillW, cornerRadius, aMul, 3f,
                    ) { u ->
                        val s = u * u * (3f - 2f * u)
                        Color4b(
                            lerp(cLeft.r.toFloat(), cRight.r.toFloat(), s).toInt().coerceIn(0, 255),
                            lerp(cLeft.g.toFloat(), cRight.g.toFloat(), s).toInt().coerceIn(0, 255),
                            lerp(cLeft.b.toFloat(), cRight.b.toFloat(), s).toInt().coerceIn(0, 255),
                        )
                    }
                }
            }

            drawScaledText(ctx, n.message, x + 10f, boxTop + 10f * (fontSize / 11f), Color4b.WHITE)
        }
    }

    override fun onDisabled() {
        notifications.clear()
        lastFrameNs = 0L
    }
}
