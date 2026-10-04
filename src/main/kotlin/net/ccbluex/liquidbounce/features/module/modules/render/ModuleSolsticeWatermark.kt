/*
 * ModuleSolsticeWatermark —— Solstice 水印（修复字体黑斑 / 辉光 / 可改文字）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import kotlin.math.max
import kotlin.math.roundToInt

object ModuleSolsticeWatermark : ClientModule(
    "SolsticeWatermark",
    ModuleCategories.RENDER,
    aliases = listOf("Watermark", "SolsticeWM"),
) {
    init { enabled = true }

    private enum class Style(override val tag: String) : Tagged {
        SOLSTICE("Solstice"),
        SEVEN_DAYS("7 Days"),
    }

    private val style by enumChoice("Style", Style.SOLSTICE)
    /** 显示文字，可在模块设置里修改 */
    private val customText by text("Text", "solstice")
    private val glow by boolean("Glow", true)
    private val dropShadow by boolean("Drop Shadow", true)
    private val bold by boolean("Bold", false)

    private val fontScale by float("Font Scale", 1.8f, 1f..4f)
    private val posX by float("Pos X", 12f, 0f..800f)
    private val posY by float("Pos Y", 10f, 0f..600f)
    private val shadowOffset by float("Shadow Offset", 1.5f, 0f..6f)
    private val glowRadius by float("Glow Radius", 8f, 2f..28f)
    private val glowStrength by float("Glow Strength", 0.55f, 0f..1f)
    private val glowSoftness by float("Glow Softness", 6f, 2f..12f)
    private val animSpeed by float("Anim Speed", 10f, 1f..30f)
    private val charSpacing by float("Char Spacing", 0f, -2f..4f)
    private val themeSpeed by float("Theme Speed", 1f, 0.1f..5f)

    private val themeA by color("Theme A", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val themeB by color("Theme B", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val themeC by color("Theme C", Color4b(0xFF, 0xFF, 0xFF, 255))

    private var anim = 0f
    private var lastFrameNs = 0L

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun getThemedColor(offsetMs: Float): Color4b {
        val colors = listOf(themeA, themeB, themeC, themeA)
        val period = 3000f / themeSpeed.coerceAtLeast(0.1f)
        val t0 = ((System.currentTimeMillis() + offsetMs.toLong()) % period.toLong()).toFloat() / period
        val angle = t0 * (colors.size - 1)
        val seg = angle.toInt().coerceIn(0, colors.size - 2)
        val t = (angle - seg).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t) // smoothstep，避免色带发黑
        val c0 = colors[seg]
        val c1 = colors[seg + 1]
        return Color4b(
            lerp(c0.r.toFloat(), c1.r.toFloat(), s).roundToInt().coerceIn(0, 255),
            lerp(c0.g.toFloat(), c1.g.toFloat(), s).roundToInt().coerceIn(0, 255),
            lerp(c0.b.toFloat(), c1.b.toFloat(), s).roundToInt().coerceIn(0, 255),
            255,
        )
    }

    /** 仅外环辉光，中心不填实心，避免字体中间发黑 */
    private fun drawGlowRing(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        cx: Float, cy: Float, radius: Float, color: Color4b, alpha: Float,
    ) {
        val layers = glowSoftness.roundToInt().coerceIn(3, 12)
        for (i in 1..layers) {
            val t = i / layers.toFloat()
            val r = radius * (0.55f + 0.55f * t)
            val a = (alpha * glowStrength * (1f - t) * (1f - t) * 70f).toInt().coerceIn(0, 55)
            if (a < 3) continue
            ctx.drawRoundedRect(
                cx - r, cy - r * 0.85f, cx + r, cy + r * 0.85f,
                r * 0.9f,
                color.alpha(a),
            )
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context
        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs != 0L) {
            ((nowNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
        } else 0.016f
        lastFrameNs = nowNs

        anim = lerp(anim, if (enabled) 1f else 0f, (dt * animSpeed).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        if (anim < 0.01f) return@handler

        val label = when {
            customText.isNotBlank() -> customText
            style == Style.SEVEN_DAYS -> "7 days"
            else -> "solstice"
        }

        val font = mc.font
        val aMul = anim
        val scale = fontScale.coerceIn(1f, 4f)

        val targetX = posX
        val targetY = posY
        var drawX = lerp(targetX - 40f, targetX, anim)
        val drawY = lerp(targetY - 8f, targetY, anim)

        for (i in label.indices) {
            val ch = label[i].toString()
            val baseW = font.width(ch).toFloat()
            val charW = baseW * scale + charSpacing
            val color = getThemedColor(i * 120f)
            val cx = drawX + charW * 0.5f
            val cy = drawY + font.lineHeight * scale * 0.45f

            if (glow) {
                drawGlowRing(ctx, cx, cy, max(glowRadius, scale * 4f), color, aMul)
            }

            val mainA = (255 * aMul).roundToInt().coerceIn(0, 255)
            val mainCol = Color4b(color.r, color.g, color.b, mainA)

            // 本 fork 为 2D Matrix3x2f pose：用 withPush + Float
            ctx.pose().withPush {
                translate(drawX, drawY)
                scale(scale, scale)

                if (dropShadow) {
                    val sh = Color4b(
                        (color.r * 0.15f).toInt().coerceIn(0, 40),
                        (color.g * 0.15f).toInt().coerceIn(0, 40),
                        (color.b * 0.15f).toInt().coerceIn(0, 40),
                        (mainA * 0.45f).toInt().coerceIn(0, 120),
                    )
                    ctx.text(font, ch, shadowOffset.roundToInt(), shadowOffset.roundToInt(), sh.argb, false)
                }

                ctx.text(font, ch, 0, 0, mainCol.argb, false)
                if (bold) {
                    ctx.text(font, ch, 1, 0, mainCol.alpha((mainA * 0.85f).toInt()).argb, false)
                }
            }
            drawX += charW
        }
    }

    override fun onDisabled() {
        lastFrameNs = 0L
    }
}
