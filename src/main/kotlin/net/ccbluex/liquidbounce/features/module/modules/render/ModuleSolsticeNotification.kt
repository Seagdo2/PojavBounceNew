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
    init { enabled = false }

    private enum class Style(override val tag: String) : Tagged { SOLARIS("Solaris") }

    private val style by enumChoice("Style", Style.SOLARIS)
    private val showOnToggle by boolean("Show On Toggle", true)
    private val showOnJoin by boolean("Show On Join", true)
    private val soundOnToggle by boolean("Sound On Toggle", true)
    private val soundVolume by float("Sound Volume", 0.6f, 0f..1f)
    private val colorGradient by boolean("Color Gradient", true)
    private val progressRainbow by boolean("Progress Rainbow", false)
    private val limitNotifications by boolean("Limit Notifications", false)
    private val maxNotifications by int("Max Notifications", 6, 1..25)
    private val fontSize by float("Font Size", 11f, 8f..20f)
    private val rightMargin by float("Right Margin", 10f, 0f..40f)
    private val bottomMargin by float("Bottom Margin", 10f, 0f..40f)
    private val animSpeed by float("Anim Speed", 5f, 1f..15f)
    private val defaultDuration by float("Default Duration", 3f, 1f..15f)
    private val cornerRadius by float("Corner Radius", 5f, 0f..16f)

    private val glow by boolean("Glow", true)
    private val glowRadius by float("Glow Radius", 16f, 2f..48f)
    private val glowStrength by float("Glow Strength", 0.85f, 0.05f..1.5f)
    private val glowLayers by int("Glow Layers", 12, 3..24)
    private val glowSoftness by float("Glow Softness", 1.35f, 0.5f..3f)
    private val glowSpread by float("Glow Spread", 1.0f, 0.3f..2.5f)
    private val glowInner by float("Glow Inner", 0.15f, 0f..0.8f)
    private enum class GlowColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"),
        THEME("Theme"),
        TYPE("By Type"),
        GRADIENT("Gradient"),
    }

    private val glowColorMode by enumChoice("Glow Color Mode", GlowColorMode.CUSTOM)
    private val glowColor by color("Glow Color", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val glowColor2 by color("Glow Color 2", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val glowAlpha by float("Glow Alpha", 1f, 0.1f..1.5f)
    private val glowPulse by boolean("Glow Pulse", false)
    private val glowPulseSpeed by float("Glow Pulse Speed", 2.2f, 0.5f..8f)

    private val themeA by color("Theme A", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val themeB by color("Theme B", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val themeC by color("Theme C", Color4b(255, 255, 255, 128))
    private val themeSeconds by float("Theme Cycle Sec", 3f, 0.5f..10f)

    enum class Type { INFO, WARNING, ERROR }

    class Notification(
        val message: String,
        val type: Type = Type.INFO,
        val duration: Float = 3f,
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

    fun add(message: String, type: Type = Type.INFO, duration: Float = defaultDuration) {
        notifications.add(Notification(message, type, duration))
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
        add("$moduleName was ${if (enabled) "enabled" else "disabled"}", Type.INFO, defaultDuration)
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

    /** 主题色列表每帧缓存（避免每次取色都 listOf 分配） */
    private var themeColors = listOf(
        Color4b(0xE9, 0xA8, 0xBC, 255),
        Color4b(0x6E, 0xC8, 0xF1, 255),
        Color4b(255, 255, 255, 128),
    )

    private fun getThemedColor(index: Float, ms: Long = 0L): Color4b {
        val colors = themeColors
        val time = 10000f / themeSeconds.coerceAtLeast(0.01f)
        val now = if (ms == 0L) System.currentTimeMillis() else ms
        val angle = ((now + index.toLong()) % time.toLong()).toFloat()
        val segT = time / colors.size
        val seg = (angle / segT).toInt() % colors.size
        val t = (angle / segT - (angle / segT).toInt()).coerceIn(0f, 1f)
        val s = colors[seg]
        val e = colors[(seg + 1) % colors.size]
        return Color4b(
            lerp(s.r.toFloat(), e.r.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(s.g.toFloat(), e.g.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(s.b.toFloat(), e.b.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(s.a.toFloat(), e.a.toFloat(), t).toInt().coerceIn(0, 255),
        )
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
     * 外圈辉光：分层四边+外框矩形扩散（不用圆角 API，全平台稳定可见）。
     * 每张卡片在显示期间都画满辉光。
     */
    private fun drawGlow(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        base: Color4b, base2: Color4b, alphaMul: Float, gradient: Boolean,
    ) {
        if (!glow || alphaMul <= 0.02f) return
        val layers = glowLayers.coerceIn(3, 24)
        val maxR = (glowRadius * glowSpread).coerceAtLeast(4f)
        val strength = glowStrength.coerceIn(0.05f, 1.5f)
        val soft = glowSoftness.coerceIn(0.5f, 3f)
        val aScale = glowAlpha.coerceIn(0.1f, 1.5f)

        var pulse = 1f
        if (glowPulse) {
            val t = (System.currentTimeMillis() % 100000L) / 1000.0
            pulse = (0.82f + 0.18f * kotlin.math.sin(t * glowPulseSpeed).toFloat())
        }

        // 从外到内画，外层更淡
        for (i in layers downTo 1) {
            val u = i / layers.toFloat() // 1=最外
            val e = maxR * u
            // 更平缓的衰减，保证内圈也够亮
            val fall = (1f - u).coerceIn(0f, 1f)
            val fall2 = fall * fall * (3f - 2f * fall) // smoothstep
            val softMul = (0.55f + 0.45f / soft).coerceIn(0.4f, 1.4f)
            val a = (fall2 * softMul * strength * pulse * aScale * 255f * alphaMul)
                .toInt()
                .coerceIn(0, 230)
            if (a < 3) continue

            val src = if (gradient) {
                Color4b(
                    lerp(base.r.toFloat(), base2.r.toFloat(), u).toInt().coerceIn(0, 255),
                    lerp(base.g.toFloat(), base2.g.toFloat(), u).toInt().coerceIn(0, 255),
                    lerp(base.b.toFloat(), base2.b.toFloat(), u).toInt().coerceIn(0, 255),
                    255,
                )
            } else base

            val col = Color4b(src.r, src.g, src.b, a)
            // 整圈外框（比四边拼接更明显）
            ctx.drawQuad(x1 - e, y1 - e, x2 + e, y1, col) // 上
            ctx.drawQuad(x1 - e, y2, x2 + e, y2 + e, col) // 下
            ctx.drawQuad(x1 - e, y1, x1, y2, col) // 左
            ctx.drawQuad(x2, y1, x2 + e, y2, col) // 右
        }

        // 紧贴卡片的一圈实线，保证“有没有辉光”一眼能看出来
        val rimA = (140f * strength * aScale * pulse * alphaMul).toInt().coerceIn(0, 200)
        if (rimA > 2) {
            val rim = Color4b(base.r, base.g, base.b, rimA)
            val t = 1.5f
            ctx.drawQuad(x1 - t, y1 - t, x2 + t, y1, rim)
            ctx.drawQuad(x1 - t, y2, x2 + t, y2 + t, rim)
            ctx.drawQuad(x1 - t, y1, x1, y2, rim)
            ctx.drawQuad(x2, y1, x2 + t, y2, rim)
        }
    }

    /** u∈[0,1] → 自然彩虹色：红→紫（HSV 色相连续插值，非几个锚点色 RGB 拼接） */
    private fun rainbowAt(u: Float): Color4b {
        val h = u.coerceIn(0f, 1f) * 0.78f // 0=红 … 0.78≈紫
        val s = 0.95f
        val v = 1f
        val hh = h * 6f
        val i = hh.toInt().coerceIn(0, 5)
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Color4b(
            (r * 255f).roundToInt().coerceIn(0, 255),
            (g * 255f).roundToInt().coerceIn(0, 255),
            (b * 255f).roundToInt().coerceIn(0, 255),
            255,
        )
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

        // 每帧刷新一次主题色缓存
        themeColors = listOf(themeA, themeB, themeC)
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

            // 进度条主题色（Warning/Error 仍可区分类型）
            var theme = getThemedColor(boxTop * 2f)
            when (n.type) {
                Type.WARNING -> theme = Color4b(255, 204, 0, 255)
                Type.ERROR -> theme = Color4b(255, 0, 0, 255)
                Type.INFO -> Unit
            }
            val aMul = n.slide
            val glowMul = if (n.isTimeUp) {
                aMul.coerceIn(0f, 1f)
            } else {
                max(aMul, 0.9f)
            }
            val cLeft = theme.alpha((220 * aMul).toInt().coerceIn(0, 255))
            val cRight = getThemedColor(boxTop * 2f + boxW * 1.2f)
                .alpha((200 * aMul).toInt().coerceIn(0, 255))
            // 辉光严格跟随设置：CUSTOM/GRADIENT 永远用 Glow Color；
            // THEME 用 Theme A/B 循环色（不受 Warning 黄/红覆盖）；
            // 仅 TYPE 模式才按通知类型上色
            val (g1, g2, gGrad) = when (glowColorMode) {
                GlowColorMode.CUSTOM -> Triple(glowColor, glowColor, false)
                GlowColorMode.GRADIENT -> Triple(glowColor, glowColor2, true)
                GlowColorMode.THEME -> Triple(
                    getThemedColor(boxTop * 2f),
                    getThemedColor(boxTop * 2f + 40f),
                    true,
                )
                GlowColorMode.TYPE -> Triple(
                    when (n.type) {
                        Type.WARNING -> Color4b(255, 204, 0, 255)
                        Type.ERROR -> Color4b(255, 60, 60, 255)
                        Type.INFO -> glowColor
                    },
                    glowColor2,
                    false,
                )
            }
            drawGlow(ctx, x, boxTop, x + boxW, boxBottom, g1, g2, glowMul, gGrad)

            // 整卡大进度条：底色纯黑；左侧圆角、右侧直角
            val blackBg = Color4b(0, 0, 0, (240 * aMul).toInt().coerceIn(0, 255))
            drawCardShape(ctx, x, boxTop, x + boxW, boxBottom, cornerRadius, blackBg)

            val fillW = boxW * percentDone
            if (fillW > 0.5f) {
                when {
                    progressRainbow -> drawProgressFill(
                        ctx, x, boxTop, boxBottom, boxW, fillW, cornerRadius, aMul, 3f,
                    ) { u -> rainbowAt(u) }

                    colorGradient -> drawProgressFill(
                        ctx, x, boxTop, boxBottom, boxW, fillW, cornerRadius, aMul, 3f,
                    ) { u ->
                        val s = u * u * (3f - 2f * u)
                        Color4b(
                            lerp(cLeft.r.toFloat(), cRight.r.toFloat(), s).toInt().coerceIn(0, 255),
                            lerp(cLeft.g.toFloat(), cRight.g.toFloat(), s).toInt().coerceIn(0, 255),
                            lerp(cLeft.b.toFloat(), cRight.b.toFloat(), s).toInt().coerceIn(0, 255),
                        )
                    }

                    else -> drawProgressFill(
                        ctx, x, boxTop, boxBottom, boxW, fillW, cornerRadius, aMul, 1e5f,
                    ) { cLeft }
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
