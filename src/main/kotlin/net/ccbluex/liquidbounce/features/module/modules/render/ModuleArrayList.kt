/*
 * ============================================================================
 *  ArrayList —— 仿 Opal v2 风格的模块列表 HUD (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39)
 *
 *  修改:
 *  - 按像素宽度从长到短排序
 *  - 每条模块独立矩形背景
 *  - 文字颜色模式: CUSTOM / RAINBOW / FADE / SKY / RAINBOW_TEXT / FADE2 / LB / SOLSTICE / CUSTOM2
 *  - Bar 模式: NONE / SOLID / GRADIENT / FOLLOW(跟随文字) / CUSTOM(自定义) / STRIP(连续条形)
 *
 *  【ModuleArrayList_8 新增】
 *  - Shadow 增加与 Glow 一致的 Shadow Mode (EDGE / TEXT / BOTH / PER_CHAR),
 *    字体阴影精确到文本像素(黑字偏移叠影), Shadow 图层永远绘制在最底部
 *  - 修复 Bar STRIP: 改为整条通高一次性绘制的连续条形 (不再交替分段断连)
 *  - 自定义整体大小 Scale
 *  - 水印支持多种颜色模式: SkyBlue / Fade / Rainbow / Custom
 *
 *  【ModuleArrayList77 新增】
 *  - Arraylist 位置支持正/负数自定义 (负数=从屏幕右/下边缘回退)
 *  - Glow 边缘发光 (参考 Solstice 描边多层写法): 总开关 / 强度 / 范围 / 密度 / 位置偏移
 *  - 背景边缘渲染模式: Glow(发光) / Shadow(阴影) / Both / None
 *  - 水印位置同样支持负数自定义
 *
 *  【ModuleArrayList_9 新增】
 *  - Background Alpha 改为 Background Color 颜色选择(含透明度)
 *  - Background 新增 Width(宽度) / Height(高度) / Size(大小缩放) 独立调节
 *  - Color Mode 新增 CUSTOM2: 自定义多色流水渐变(1~7色, 仿 RAINBOW_TEXT 流动)
 *    - C2 Color Count: 颜色数量(1~7), 渲染时只使用前 N 个 Color
 *    - C2 Color 1~7: 调色板, 配合 Count 使用
 *    - C2 Speed: 颜色变换速度(快慢)
 *    - C2 Spread: 每色扩散距离(相邻条目相位差, 越小流动越绵长)
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import java.awt.Color
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleArrayList : ClientModule("ArrayList", ModuleCategories.RENDER) {
    init { enabled = true }

    /* ============================= 可调节项 ============================= */

    private enum class Side(override val tag: String) : Tagged { LEFT("Left"), RIGHT("Right") }

    // 边缘渲染模式 (Glow = 参考 Solstice 描边发光; Shadow = 黑色沿边缘多层描边)
    private enum class EdgeMode(override val tag: String) : Tagged {
        NONE("None"), GLOW("Glow"), SHADOW("Shadow"), BOTH("Both")
    }

    // Glow 发光目标: MODULE(仅模块文字) / BAR(仅装饰条) / BOTH(两者同时发光)
    private enum class GlowMode(override val tag: String) : Tagged {
        MODULE("Module"), BAR("Bar"), BOTH("Both")
    }

    // Shadow 阴影目标: EDGE(背景边缘) / TEXT(字体阴影) / BOTH / PER_CHAR
    private enum class ShadowMode(override val tag: String) : Tagged {
        EDGE("Edge"), TEXT("Text"), BOTH("Both"), PER_CHAR("PerChar")
    }

    // 文字颜色模式
    private enum class ColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"), RAINBOW("Rainbow"), FADE("Fade"), SKY("Sky"),
        RAINBOW_TEXT("RainbowText"),  // 彩虹渐变文字
        FADE2("Fade2"),              // 白色 ↔ 天蓝色渐变循环
        LB("LB"),                    // 文字白色，Bar 天蓝色
        SOLSTICE("Solstice"),        // Solstice 三色渐变 (粉‑蓝‑白)
        CUSTOM2("Custom2")           // 【新增】自定义多色流水渐变 (1~7色)
    }

    private enum class SortMode(override val tag: String) : Tagged {
        LENGTH("Length"), ALPHABETICAL("Alphabetical"), NONE("None")
    }

    // Bar 模式
    private enum class BarMode(override val tag: String) : Tagged {
        NONE("None"), SOLID("Solid"), GRADIENT("Gradient"),
        FOLLOW("Follow"),   // 跟随文字颜色模式
        CUSTOM("Custom"),    // 自定义 Bar 颜色
        STRIP("Strip")       // 条状不间断
    }

    // Bar 左右方向
    private enum class BarSide(override val tag: String) : Tagged {
        AUTO("Auto"), LEFT("Left"), RIGHT("Right")
    }

    // 水印颜色模式
    private enum class WaterMarkColorMode(override val tag: String) : Tagged {
        SKY_BLUE("SkyBlue"), FADE("Fade"), RAINBOW("Rainbow"), CUSTOM("Custom")
    }

    // —— 布局 (支持正负数: 负数 = 从屏幕右/下边缘回退) ——
    private val side by enumChoice("Side", Side.RIGHT)
    private val offsetX by int("Offset X", 4, 0..200)
    private val offsetY by int("Offset Y", 4, 0..200)
    private val spacing by int("Spacing", 0, 0..12)
    private val padding by int("Padding", 0, 0..16)
    private val customScale by float("Scale", 1.0f, 0.5f..3.0f)
    private val upperCase by boolean("Uppercase", false)
    private val sortMode by enumChoice("Sort Mode", SortMode.LENGTH)
    private val showSelf by boolean("Show Self", true)

    // —— 外观 ——
    private val textShadow by boolean("Text Shadow", true)
    private val background by boolean("Background", false)

    // 【ModuleArrayList_9】Background Alpha → Background Color (默认值等效: 黑色 alpha 80)
    private val backgroundColor by color("Background Color", Color4b(0, 0, 0, 80))

    // 【ModuleArrayList_9】Background 尺寸独立调节
    private val backgroundWidth by int("Background Width", 0, -20..60)     // 额外宽度(0=默认, 负=更窄)
    private val backgroundHeight by int("Background Height", 0, -10..30)   // 额外高度(0=默认, 负=更矮)
    private val backgroundSize by float("Background Size", 1.0f, 0.5f..3.0f) // 圆角/内边距整体缩放

    private val backgroundRadius by int("Background Radius", 2, 0..6)
    private val border by boolean("Border", false)

    // —— 颜色 ——
    private val colorMode by enumChoice("Color Mode", ColorMode.RAINBOW_TEXT)
    private val customColor by color("Color", Color4b(0, 160, 255))
    private val rainbowSpeed by float("Rainbow Speed", 6f, 0.1f..10f)
    private val rainbowOffset by int("Rainbow Offset", 5, 0..90)
    private val rainbowTextSpeed by float("Rainbow Text Speed", 6f, 0.1f..20f)

    // —— 【ModuleArrayList_9】Custom2 自定义多色流水渐变 (仿 RAINBOW_TEXT 同帧多色流动) ——
    // 颜色数量(1~7): 渲染时只使用前 N 个 Color, 多余的调色板不参与渐变
    private val custom2ColorCount by int("C2 Color Count", 2, 1..7)
    // 调色板 Color1~Color7 (与 Count 配合: Count=2 时只用 Color1/Color2)
    private val custom2Color1 by color("C2 Color 1", Color4b(0x99, 0x33, 0xFF)) // 紫
    private val custom2Color2 by color("C2 Color 2", Color4b(0x33, 0x66, 0xFF)) // 蓝
    private val custom2Color3 by color("C2 Color 3", Color4b(0x33, 0xFF, 0x66)) // 绿
    private val custom2Color4 by color("C2 Color 4", Color4b(0xFF, 0xFF, 0x33)) // 黄
    private val custom2Color5 by color("C2 Color 5", Color4b(0xFF, 0x99, 0x33)) // 橙
    private val custom2Color6 by color("C2 Color 6", Color4b(0xFF, 0x33, 0x99)) // 玫红
    private val custom2Color7 by color("C2 Color 7", Color4b(0xFF, 0x33, 0x33)) // 红
    // 变换速度: 越大颜色流动越快 (与 Rainbow Text Speed 并列)
    private val custom2Speed by float("C2 Speed", 6f, 0.1f..20f)
    // 每色扩散距离: 相邻条目相位差, 越小流动越绵长平缓, 越大对比越强烈 (类似 Rainbow Offset)
    private val custom2Spread by float("C2 Spread", 20f, 1f..90f)

    // —— 装饰条 ——
    private val barMode by enumChoice("Bar Mode", BarMode.FOLLOW)
    private val barWidth by int("Bar Width", 2, 0..8)
    private val barCustomColor by color("Bar Color", Color4b.WHITE)
    private val barSide by enumChoice("Bar Side", BarSide.AUTO)

    // —— 动画 ——
    private val animationSpeed by float("Animation Speed", 50f, 1f..50f)
    private val slideIn by boolean("Slide In", true)

    // —— Glow 边缘发光 ——
    private val glowEnabled by boolean("Glow", true)
    private val glowMode by enumChoice("Glow Mode", GlowMode.BOTH)
    private val glowRange by float("Glow Range", 18f, 0f..30f)
    private val glowStrength by float("Glow Strength", 0.04f, 0.01f..1f)
    private val glowDensity by int("Glow Density", 6, 1..12)
    private val glowOffsetX by float("Glow Offset X", 0f, -16f..16f)
    private val glowOffsetY by float("Glow Offset Y", 0f, -16f..16f)

    // —— 背景边缘渲染模式 + 自定义边缘大小 ——
    private val perItemEdgeMode by enumChoice("Item Edge Mode", EdgeMode.SHADOW)
    private val listEdgeMode by enumChoice("List Edge Mode", EdgeMode.NONE)
    private val edgeSize by float("Edge Size", 8f, 0f..32f)

    // —— Shadow 阴影 ——
    private val shadowEnabled by boolean("Shadow", true)
    private val shadowMode by enumChoice("Shadow Mode", ShadowMode.EDGE)
    private val shadowRange by float("Shadow Range", 24f, 0f..30f)
    private val shadowOffsetX by float("Shadow Offset X", 0f, -20f..20f)
    private val shadowOffsetY by float("Shadow Offset Y", 0f, -20f..20f)
    private val shadowStrength by float("Shadow Strength", 0.1f, 0.01f..1f)
    private val shadowDensity by int("Shadow Density", 6, 0..10)

    // ==================== 水印 ====================
    private val waterMarkEnabled by boolean("WaterMark", false)
    private val waterMarkText by text("WaterMark Text", "LiquidBounce 0.39")
    private val waterMarkScale by float("WaterMark Scale", 1.0f, 0.5f..3.0f)
    private val waterMarkX by int("WaterMark X", 4, -2000..2000)
    private val waterMarkY by int("WaterMark Y", 4, -2000..2000)
    private val waterMarkBgAlpha by int("WaterMark Bg Alpha", 0, 0..255)
    private val waterMarkColorMode by enumChoice("WM Color Mode", WaterMarkColorMode.FADE)
    private val waterMarkCustomColor by color("WM Custom Color", Color4b(0, 160, 255))
    private val waterMarkRainbowSpeed by float("WM Rainbow Speed", 2f, 0.1f..20f)

    // —— 水印 Glow 发光 ——
    private val waterMarkGlowEnabled by boolean("WM Glow", true)
    private val waterMarkGlowRange by float("WM Glow Range", 24f, 0f..30f)
    private val waterMarkGlowStrength by float("WM Glow Strength", 0.1f, 0.01f..1f)
    private val waterMarkGlowDensity by int("WM Glow Density", 6, 1..12)

    /* ============================= 内部状态 ============================= */

    private class Animation(var y: Float, var slide: Float)
    private data class Entry(val module: ClientModule, val text: String, val width: Int, val height: Int)
    private data class Drawn(val entry: Entry, val y: Float, val x: Float, val color: Color4b)

    private data class ItemLayout(
        val barX: Float, val textX: Float, val textY: Float,
        val bgX: Float, val bgY: Float, val bgW: Float, val bgH: Float,
    )

    private val animations = HashMap<ClientModule, Animation>()
    private var lastFrameNs = 0L

    private data class RectF(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    private val drawnItemRects = mutableListOf<RectF>()

    private val WHITE = Color4b(255, 255, 255, 255)
    private val SKY_BLUE = Color4b(0, 160, 255, 255)

    private val SOLSTICE_COLORS = listOf(
        Color4b(0xE9, 0xA8, 0xBC),
        Color4b(0x6E, 0xC8, 0xF1),
        Color4b(255, 255, 255)
    )

    /* =============================== 渲染 =============================== */

    private fun resolveX(x: Int, w: Int, right: Boolean, screenW: Int): Int =
        if (x < 0) screenW + x - (if (right) 0 else w)
        else if (right) screenW - x - w
        else x

    private fun resolveY(y: Int, h: Int, screenH: Int): Int =
        if (y < 0) screenH + y - h else y

    private fun drawGlowEdge(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color4b, offX: Float, offY: Float, strength: Float, density: Int,
    ) {
        val range = glowRange
        if (range <= 0f || strength <= 0f || density <= 0) return
        if (density <= 1) {
            val w = range * 0.55f
            ctx.drawRoundedRect(
                x1 - w + offX, y1 - w + offY, x2 + w + offX, y2 + w + offY,
                w * 0.5f, Color4b.TRANSPARENT, color.alpha((strength * 255).roundToInt().coerceIn(0, 255)), w,
            )
            return
        }
        for (i in 0 until density) {
            val t = i / (density - 1).toFloat()
            val w = range * (0.3f + 0.7f * t)
            val a = (strength * (0.55f + 0.3f * (1f - t)) * 255).roundToInt().coerceIn(0, 255)
            val off = w * 0.5f
            ctx.drawRoundedRect(
                x1 - off + offX, y1 - off + offY, x2 + off + offX, y2 + off + offY,
                w * 0.5f, Color4b.TRANSPARENT, color.alpha(a), w,
            )
        }
    }

    private fun drawShadowEdge(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        offX: Float, offY: Float, strength: Float, density: Int,
    ) {
        val range = shadowRange
        if (range <= 0f || strength <= 0f || density <= 0) return
        val shadowColor = Color4b(0, 0, 0, 255)
        if (density <= 1) {
            val w = range * 0.55f
            ctx.drawRoundedRect(
                x1 - w + offX, y1 - w + offY, x2 + w + offX, y2 + w + offY,
                w * 0.5f, Color4b.TRANSPARENT, shadowColor.alpha((strength * 255).roundToInt().coerceIn(0, 255)), w,
            )
            return
        }
        for (i in 0 until density) {
            val t = i / (density - 1).toFloat()
            val w = range * (0.3f + 0.7f * t)
            val a = (strength * (0.55f + 0.3f * (1f - t)) * 255).roundToInt().coerceIn(0, 255)
            val off = w * 0.5f
            ctx.drawRoundedRect(
                x1 - off + offX, y1 - off + offY, x2 + off + offX, y2 + off + offY,
                w * 0.5f, Color4b.TRANSPARENT, shadowColor.alpha(a), w,
            )
        }
    }

    private fun renderEdge(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color4b, mode: EdgeMode,
    ) {
        if (mode == EdgeMode.NONE) return
        val edgeScale = (edgeSize / 8f).coerceAtLeast(0f)
        val glowEdge = glowEnabled && (glowMode == GlowMode.MODULE || glowMode == GlowMode.BOTH) && edgeScale > 0f
        when (mode) {
            EdgeMode.NONE -> Unit
            EdgeMode.GLOW -> if (glowEdge) {
                drawGlowEdge(ctx, x1, y1, x2, y2, color,
                    glowOffsetX * edgeScale, glowOffsetY * edgeScale, glowStrength, glowDensity)
            }
            EdgeMode.SHADOW -> Unit
            EdgeMode.BOTH -> if (glowEdge) {
                drawGlowEdge(ctx, x1, y1, x2, y2, color,
                    glowOffsetX * edgeScale, glowOffsetY * edgeScale, glowStrength, glowDensity)
            }
        }
    }

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
        val smoothing = (1f - exp(-animationSpeed * frameTime)).coerceIn(0f, 1f)

        // ----- 水印（在列表之前）-----
        if (waterMarkEnabled) renderWaterMark(context, font)

        // 收集已启用模块 (26.3: ModuleManager 实现 Collection<ClientModule>)
        var modules = ModuleManager.toList()
            .filter { it.enabled && !it.hidden && (showSelf || it !== this) }
            .toList()

        animations.keys.retainAll(modules)

        // 排序：按像素宽度从长到短
        modules = when (sortMode) {
            SortMode.LENGTH -> modules.sortedByDescending { mod ->
                val displayName = if (upperCase) mod.name.uppercase() else mod.name
                font.width(displayName)
            }
            SortMode.ALPHABETICAL -> modules.sortedBy { it.name.lowercase() }
            SortMode.NONE -> modules
        }

        if (modules.isEmpty()) return@handler

        val screenWidth = mc.window.guiScaledWidth
        val screenHeight = mc.window.guiScaledHeight
        val fontHeight = font.lineHeight

        // 构建条目
        val entries = modules.map { module ->
            val text = if (upperCase) module.name.uppercase() else module.name
            Entry(module, text, font.width(text), fontHeight + 4)
        }

        val barEnabled = barMode != BarMode.NONE && barWidth > 0
        val barGap = if (barEnabled) barWidth + 3f else 0f

        val totalH = entries.sumOf { it.height + spacing } - (if (entries.isNotEmpty()) spacing else 0)
        var cursorY = (if (offsetY < 0) screenHeight + offsetY - totalH else offsetY).toFloat()

        // 逐条目计算动画位置与颜色
        val drawn = mutableListOf<Drawn>()
        entries.forEachIndexed { index, entry ->
            val targetY = cursorY
            cursorY += entry.height + spacing

            val anim = animations.getOrPut(entry.module) { Animation(targetY, 0f) }
            anim.y += (targetY - anim.y) * smoothing
            anim.slide += (1f - anim.slide) * smoothing

            val itemWidth = entry.width + barGap + padding * 2f
            val offX = resolveX(offsetX, itemWidth.roundToInt(), side == Side.RIGHT, screenWidth)
            val baseX = offX.toFloat()
            val x = if (slideIn) {
                val slideDistance = itemWidth + 24f
                if (side == Side.RIGHT) baseX + (1f - anim.slide) * slideDistance
                else baseX - (1f - anim.slide) * slideDistance
            } else baseX

            drawn += Drawn(entry, anim.y, x, resolveColor(index, anim.y))
        }

        // 整体缩放
        context.pose().withPush {
            if (customScale != 1f) {
                scale(customScale, customScale)
            }

            drawnItemRects.clear()

            // —— 条目布局计算 ——
            val layouts = drawn.map { d ->
                val barLeft = when (barSide) {
                    BarSide.AUTO -> side == Side.LEFT
                    BarSide.LEFT -> true
                    BarSide.RIGHT -> false
                }
                val lBarX = if (barLeft) d.x
                else d.x + d.entry.width + barGap + padding * 2f - padding - barWidth
                val lTextX = if (barLeft) d.x + barWidth + 3f + padding
                else d.x + padding
                val lTextY = d.y + (d.entry.height - fontHeight) / 2f
                ItemLayout(
                    lBarX, lTextX, lTextY,
                    d.x, d.y,
                    d.entry.width + barGap + padding * 2f,
                    d.entry.height.toFloat(),
                )
            }

            // ================= Shadow 图层 (最底部) =================
            if (shadowEnabled) {
                val shadowScale = (edgeSize / 8f).coerceAtLeast(0f)
                drawn.forEachIndexed { i, d ->
                    val (_, sTextX, sTextY, sBgX, sBgY, sBgW, sBgH) = layouts[i]
                    val edgeShadowOn = (perItemEdgeMode == EdgeMode.SHADOW || perItemEdgeMode == EdgeMode.BOTH) &&
                        (shadowMode == ShadowMode.EDGE || shadowMode == ShadowMode.BOTH)
                    if (edgeShadowOn && shadowScale > 0f) {
                        drawShadowEdge(
                            context, sBgX, sBgY, sBgX + sBgW, sBgY + sBgH,
                            shadowOffsetX * shadowScale, shadowOffsetY * shadowScale,
                            shadowStrength, shadowDensity,
                        )
                    }
                    if (shadowMode != ShadowMode.EDGE) {
                        val shadowTextColor = Color4b(0, 0, 0, 0)
                        when (shadowMode) {
                            ShadowMode.PER_CHAR -> {
                                var cx = sTextX
                                for (ch in d.entry.text) {
                                    val chStr = ch.toString()
                                    context.text(
                                        font, chStr,
                                        (cx + shadowOffsetX).roundToInt(),
                                        (sTextY + shadowOffsetY).roundToInt(),
                                        shadowTextColor.argb, false,
                                    )
                                    cx += font.width(chStr)
                                }
                            }
                            else -> {
                                context.text(
                                    font, d.entry.text,
                                    (sTextX + shadowOffsetX).roundToInt(),
                                    (sTextY + shadowOffsetY).roundToInt(),
                                    shadowTextColor.argb, false,
                                )
                            }
                        }
                    }
                }
                if ((listEdgeMode == EdgeMode.SHADOW || listEdgeMode == EdgeMode.BOTH) &&
                    (shadowMode == ShadowMode.EDGE || shadowMode == ShadowMode.BOTH) &&
                    shadowScale > 0f && layouts.isNotEmpty()
                ) {
                    val minX = layouts.minOf { it.bgX } - 3f
                    val maxX = layouts.maxOf { it.bgX + it.bgW } + 3f
                    val minY = layouts.minOf { it.bgY } - 2f
                    val maxY = layouts.maxOf { it.bgY + it.bgH } + 2f
                    drawShadowEdge(
                        context, minX, minY, maxX, maxY,
                        shadowOffsetX * shadowScale, shadowOffsetY * shadowScale,
                        shadowStrength, shadowDensity,
                    )
                }
            }

            // 绘制每条模块
            drawn.forEachIndexed { i, d ->
                val (barX, textX, textY, bgX, bgY, bgW, bgH) = layouts[i]

                // —— 单条目边缘渲染 (Glow 颜色跟随文字颜色模式) ——
                renderEdge(context, bgX, bgY, bgX + bgW, bgY + bgH, d.color, perItemEdgeMode)

                // —— 背景 ——
                if (background) {
                    // 【ModuleArrayList_9】颜色选择(含透明度) + Width/Height/Size 尺寸调节
                    val bgColor = backgroundColor
                    // 尺寸扩展: Width/Height 向四周扩展, Size 缩放内边距
                    val sizeScale = backgroundSize
                    val extraW = backgroundWidth * 0.5f
                    val extraH = backgroundHeight * 0.5f
                    val bX = bgX - extraW
                    val bY = bgY - extraH
                    val bW = bgW + backgroundWidth
                    val bH = bgH + backgroundHeight
                    if (backgroundRadius > 0) {
                        context.drawRoundedRect(
                            bX, bY, bX + bW, bY + bH,
                            backgroundRadius.toFloat() * sizeScale,
                            bgColor, Color4b.TRANSPARENT, 0f
                        )
                    } else {
                        context.drawQuad(bX, bY, bX + bW, bY + bH, bgColor, Color4b.TRANSPARENT)
                    }
                }

                // —— 边框 ——
                if (border) {
                    context.drawQuad(bgX, bgY, bgX + bgW, bgY + 1f, d.color, Color4b.TRANSPARENT)
                    context.drawQuad(bgX, bgY + bgH - 1f, bgX + bgW, bgY + bgH, d.color, Color4b.TRANSPARENT)
                }

                // —— 确定 Bar 颜色 ——
                val effectiveBarColor: Color4b = when (barMode) {
                    BarMode.CUSTOM -> barCustomColor
                    BarMode.FOLLOW -> d.color
                    else -> d.color
                }

                // —— 侧边装饰条 ——
                if (barEnabled) {
                    when (barMode) {
                        BarMode.NONE -> Unit
                        BarMode.SOLID, BarMode.FOLLOW, BarMode.CUSTOM -> context.drawQuad(
                            barX, d.y + 2f,
                            barX + barWidth, d.y + d.entry.height - 2f,
                            effectiveBarColor
                        )
                        BarMode.GRADIENT -> context.fillGradient(
                            barX.roundToInt(), (d.y + 2f).roundToInt(),
                            (barX + barWidth).roundToInt(), (d.y + d.entry.height - 2f).roundToInt(),
                            effectiveBarColor.argb, effectiveBarColor.copy(alpha = 0).argb
                        )
                        BarMode.STRIP -> {
                            context.drawQuad(
                                barX, d.y,
                                barX + barWidth, d.y + d.entry.height,
                                effectiveBarColor
                            )
                        }
                    }
                }

                // —— 确定文字颜色 ——
                val textDrawColor: Int = when (colorMode) {
                    ColorMode.LB -> WHITE.argb
                    else -> d.color.argb
                }

                // —— 发光 ——
                if (glowEnabled) {
                    if (glowMode == GlowMode.MODULE || glowMode == GlowMode.BOTH) {
                        val tw = font.width(d.entry.text).toFloat()
                        val th = fontHeight.toFloat()
                        drawGlowEdge(
                            context,
                            textX.toFloat(), textY.toFloat(), textX.toFloat() + tw, textY.toFloat() + th,
                            d.color, glowOffsetX, glowOffsetY, glowStrength, glowDensity,
                        )
                    }
                    if ((glowMode == GlowMode.BAR || glowMode == GlowMode.BOTH) && barEnabled && barMode != BarMode.NONE) {
                        drawGlowEdge(
                            context,
                            barX, d.y, barX + barWidth, d.y + d.entry.height,
                            effectiveBarColor, glowOffsetX, glowOffsetY, glowStrength * 0.8f, glowDensity,
                        )
                    }
                }

                // 文字
                context.text(font, d.entry.text, textX.roundToInt(), textY.roundToInt(), textDrawColor, textShadow)

                drawnItemRects += RectF(bgX, bgY, bgX + bgW, bgY + bgH)
            }

            // —— 整个列表的边缘渲染 ——
            if (listEdgeMode != EdgeMode.NONE && drawnItemRects.isNotEmpty()) {
                val minX = drawnItemRects.minOf { it.x1 } - 3f
                val maxX = drawnItemRects.maxOf { it.x2 } + 3f
                val minY = drawnItemRects.minOf { it.y1 } - 2f
                val maxY = drawnItemRects.maxOf { it.y2 } + 2f
                val listColor = resolveColor(0, minY)
                renderEdge(context, minX, minY, maxX, maxY, listColor, listEdgeMode)
            }
        }
    }

    /* ============================= 水印渲染 ============================= */

    private fun renderWaterMark(context: Any, font: Any) {
        @Suppress("UNCHECKED_CAST")
        val ctx = context as? net.minecraft.client.gui.GuiGraphicsExtractor ?: return
        @Suppress("UNCHECKED_CAST")
        val f = font as? net.minecraft.client.gui.Font ?: return

        val wmText = waterMarkText
        val wmScale = waterMarkScale
        val wmPad = 4f * wmScale
        val wmW = f.width(wmText) * wmScale + wmPad * 2f
        val wmH = f.lineHeight * wmScale + wmPad * 2f
        val wmBgX = resolveX(waterMarkX, wmW.roundToInt(), false, mc.window.guiScaledWidth).toFloat() - wmPad
        val wmBgY = resolveY(waterMarkY, wmH.roundToInt(), mc.window.guiScaledHeight).toFloat() - wmPad
        val wmBgW = wmW
        val wmBgH = wmH

        ctx.drawRoundedRect(
            wmBgX, wmBgY, wmBgX + wmBgW, wmBgY + wmBgH,
            2f * wmScale,
            Color4b(0, 0, 0, waterMarkBgAlpha), Color4b.TRANSPARENT, 0f
        )

        val wmTime = (System.currentTimeMillis() % 100000) / 1000f
        val wmTextColor: Int? = when (waterMarkColorMode) {
            WaterMarkColorMode.SKY_BLUE -> SKY_BLUE.argb
            WaterMarkColorMode.FADE -> null
            WaterMarkColorMode.RAINBOW -> {
                val hue = (wmTime * 60f * waterMarkRainbowSpeed) % 360f
                hueColor(hue).argb
            }
            WaterMarkColorMode.CUSTOM -> waterMarkCustomColor.argb
        }

        val wmTextX = wmBgX + wmPad
        val wmTextY = wmBgY + wmPad

        if (waterMarkGlowEnabled) {
            val wmGlowColor: Color4b = when (waterMarkColorMode) {
                WaterMarkColorMode.CUSTOM -> waterMarkCustomColor
                WaterMarkColorMode.SKY_BLUE -> SKY_BLUE
                else -> WHITE
            }
            val wmTw = f.width(wmText).toFloat()
            val wmTh = f.lineHeight.toFloat()
            drawGlowEdge(
                ctx,
                wmTextX.toFloat(), wmTextY.toFloat(),
                wmTextX.toFloat() + wmTw, wmTextY.toFloat() + wmTh,
                wmGlowColor, 0f, 0f, waterMarkGlowStrength, waterMarkGlowDensity,
            )
        }

        if (wmTextColor != null) {
            ctx.text(f, wmText, wmTextX.roundToInt(), wmTextY.roundToInt(), wmTextColor, textShadow)
        } else {
            var cx = wmTextX
            for (i in wmText.indices) {
                val ch = wmText[i].toString()
                val t = if (wmText.length > 1) i / (wmText.length - 1).toFloat() else 0f
                val c = lerpColor(SKY_BLUE, WHITE, t)
                ctx.text(f, ch, cx.roundToInt(), wmTextY.roundToInt(), c.argb, textShadow)
                cx += f.width(ch)
            }
        }
    }

    /* ============================= 工具函数 ============================= */

    private fun resolveColor(index: Int, y: Float): Color4b {
        val time = (System.currentTimeMillis() % 100000) / 1000f
        return when (colorMode) {
            ColorMode.CUSTOM -> customColor
            ColorMode.RAINBOW -> hueColor(time * 36f * rainbowSpeed + index * rainbowOffset)
            ColorMode.FADE -> hueColor(time * 36f * rainbowSpeed + index * rainbowOffset, saturation = 0.65f)
            ColorMode.SKY -> hueColor(y / 720f * 360f + time * 18f * rainbowSpeed, saturation = 0.65f)
            ColorMode.RAINBOW_TEXT -> {
                val hue = (time * 60f * rainbowTextSpeed + index * 20f) % 360f
                hueColor(hue)
            }
            ColorMode.FADE2 -> {
                val t = ((sin(time * 2.0 * rainbowTextSpeed) + 1.0) / 2.0).toFloat()
                lerpColor(WHITE, SKY_BLUE, t)
            }
            ColorMode.LB -> SKY_BLUE
            ColorMode.SOLSTICE -> themedColor(index.toFloat(), time)
            // 【ModuleArrayList_9】Custom2: 自定义多色流水渐变
            ColorMode.CUSTOM2 -> custom2ThemedColor(index.toFloat(), time)
        }
    }

    // ----- Solstice 三色流水渐变 -----
    private fun themedColor(index: Float, time: Float): Color4b {
        val angle = (time * 60f * rainbowTextSpeed + index * 20f) % 360f
        val segFloat = angle / 360f * SOLSTICE_COLORS.size
        val seg = segFloat.toInt().coerceIn(0, SOLSTICE_COLORS.size - 1)
        val t = (segFloat - seg).coerceIn(0f, 1f)
        val next = (seg + 1) % SOLSTICE_COLORS.size
        return lerpColor(SOLSTICE_COLORS[seg], SOLSTICE_COLORS[next], t)
    }

    // ----- 【ModuleArrayList_9】Custom2 自定义多色流水渐变 -----
    /**
     * 仿 RAINBOW_TEXT / SOLSTICE 的同帧多色流动写法:
     * 相位 = time(秒) × 60° × C2 Speed + index × C2 Spread
     * → 同一帧内不同条目处于不同相位, 整列在自定义颜色间如流水般滚动。
     *
     * 与 FADE 模式的区别: FADE 是整列同色呼吸(无多色并存),
     * Custom2 同一时刻能看到多个颜色相邻排布, 颜色沿列表流动。
     *
     * @param index 条目序号(决定相位偏移 → 相邻条目颜色差异)
     * @param time  时间(秒, 驱动整体流动)
     */
    private fun custom2ThemedColor(index: Float, time: Float): Color4b {
        // 收集前 N 个颜色 (C2 Color Count = N, 只使用 Color1..ColorN)
        val count = custom2ColorCount.coerceIn(1, 7)
        val colors = ArrayList<Color4b>(count)
        colors.add(custom2Color1)
        if (count >= 2) colors.add(custom2Color2)
        if (count >= 3) colors.add(custom2Color3)
        if (count >= 4) colors.add(custom2Color4)
        if (count >= 5) colors.add(custom2Color5)
        if (count >= 6) colors.add(custom2Color6)
        if (count >= 7) colors.add(custom2Color7)
        if (colors.isEmpty()) return WHITE

        // 相位公式与 RAINBOW_TEXT 同构: C2 Speed 控制流速, C2 Spread 控制每色扩散距离
        val angle = (time * 60f * custom2Speed + index * custom2Spread) % 360f
        val segFloat = angle / 360f * colors.size
        val seg = segFloat.toInt().coerceIn(0, colors.size - 1)
        val t = (segFloat - seg).coerceIn(0f, 1f)
        val next = (seg + 1) % colors.size
        return lerpColor(colors[seg], colors[next], t)
    }

    private fun hueColor(hueDeg: Float, saturation: Float = 1f, brightness: Float = 1f): Color4b {
        var hue = hueDeg % 360f
        if (hue < 0f) hue += 360f
        return Color4b(Color.getHSBColor(hue / 360f, saturation, brightness))
    }

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val tt = t.coerceIn(0f, 1f)
        return Color4b(
            (a.r + (b.r - a.r) * tt).roundToInt().coerceIn(0, 255),
            (a.g + (b.g - a.g) * tt).roundToInt().coerceIn(0, 255),
            (a.b + (b.b - a.b) * tt).roundToInt().coerceIn(0, 255),
            (a.a + (b.a - a.a) * tt).roundToInt().coerceIn(0, 255)
        )
    }

    private fun Color4b.copy(red: Int = this.r, green: Int = this.g, blue: Int = this.b, alpha: Int = this.a): Color4b {
        return Color4b(red, green, blue, alpha)
    }
}
