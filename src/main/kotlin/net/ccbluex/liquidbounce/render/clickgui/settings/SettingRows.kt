/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Native port of src-theme/src/routes/clickgui/setting. GenericSetting.svelte
 * dispatches on `setting.valueType` (a string enum) to one Svelte component
 * per type; SettingRenderer.build() below does the same dispatch, one Kotlin
 * SettingRow per type, so the branch structure mirrors the original file
 * one-to-one instead of inventing a new taxonomy.
 *
 * Coverage: BOOLEAN, FLOAT(_RANGE), INT(_RANGE), CHOOSE, MULTI_CHOOSE, TEXT,
 * COLOR and BIND/KEY are implemented. Anything else (CURVE, VECTOR3_*, a
 * mode's nested CONFIGURABLE group beyond simple recursion) falls back to
 * UnsupportedRow, which - like the web's own fallback - prints the value's
 * name and type instead of guessing at a bespoke editor. Recursion into a
 * ValueGroup's children (used by mode-selector "CHOICE" values and nested
 * Configurables alike) IS implemented, since ValueGroup is a real, uniform
 * base (`ValueGroup : Value<MutableCollection<Value<*>>>`), so
 * `group.get()` gives its children regardless of what kind of group it is.
 */
package net.ccbluex.liquidbounce.render.clickgui.settings

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.clickgui.ClickGuiPalette
import net.ccbluex.liquidbounce.render.clickgui.GuiRender2D
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt

/** One editable line in an expanded module's settings body. */
interface SettingRow {
    val indent: Int
    /** Row height for the given available [width]. 【尺寸对齐 ClickGuiScreen】22 → 17 */
    fun height(width: Int): Int = 17

    fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int)
    fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean = false
    fun mouseDragged(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean = false
    fun mouseReleased(mouseX: Int, mouseY: Int, button: Int): Boolean = false
    fun charTyped(chr: Char): Boolean = false
    fun keyPressed(keyCode: Int): Boolean = false
    /** True while this row wants exclusive keyboard focus (a text field being edited). */
    fun isFocused(): Boolean = false
}

private const val LABEL_FRACTION = 0.55f
private const val ROW_PAD = 10

private fun labelAndControlBounds(x: Int, width: Int, indent: Int): Pair<IntRange, IntRange> {
    val left = x + ROW_PAD + indent
    val labelWidth = ((width - ROW_PAD * 2 - indent) * LABEL_FRACTION).toInt()
    val controlLeft = left + labelWidth + 8
    val controlRight = x + width - ROW_PAD
    return (left..(left + labelWidth)) to (controlLeft..controlRight)
}

/**
 * 设置行文字标签渲染 — 超长文字自动跑马灯滚动 (对齐 NativeModuleRow 的跑马灯写法):
 * 不超出 → 直接渲染; 超出 → GuiRender2D.renderMarqueeText (4秒停+4秒滚循环)
 */
private fun drawLabel(gfx: GuiGraphicsExtractor, text: String, x: Int, y: Int, maxWidth: Int, dimmed: Boolean = true) {
    val font = net.minecraft.client.Minecraft.getInstance().font
    val color = if (dimmed) ClickGuiPalette.TEXT_DIMMED else ClickGuiPalette.TEXT
    GuiRender2D.renderMarqueeText(gfx, font, text, x, y + 4, maxWidth, y, 17, color)
}

// --------------------------------------------------------------- BOOLEAN

class BooleanRow(override val indent: Int, private val value: Value<Boolean>) : SettingRow {
    private val switchW = 28
    private val switchH = 14

    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val (label, control) = labelAndControlBounds(x, width, indent)
        drawLabel(gfx, value.name, label.first, y, label.last - label.first)

        val sx = control.last - switchW
        val sy = y + (height(width) - switchH) / 2
        val on = value.get()
        val track = if (on) ClickGuiPalette.SWITCH_TRACK_ACTIVE else ClickGuiPalette.SWITCH_TRACK
        GuiRender2D.fillRoundedRect(gfx, sx, sy, switchW, switchH, switchH / 2, track)
        val thumbD = switchH - 4
        val thumbX = if (on) sx + switchW - thumbD - 2 else sx + 2
        val thumb = if (on) ClickGuiPalette.SWITCH_THUMB_ACTIVE else ClickGuiPalette.SWITCH_THUMB
        GuiRender2D.fillCircle(gfx, thumbX + thumbD / 2, sy + 2 + thumbD / 2, thumbD / 2, thumb)
    }

    override fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        val (_, control) = labelAndControlBounds(x, width, indent)
        val sx = control.last - switchW
        if (mouseX in sx..(sx + switchW) && mouseY in y..(y + height(width))) {
            value.set(!value.get())
            return true
        }
        return false
    }
}

// ------------------------------------------------------------ NUMERIC SLIDER

/** Handles FLOAT, INT, FLOAT_RANGE and INT_RANGE - a single-value slider or a
 * dual-handle range slider, chosen by whether [value]'s current type is a
 * ClosedRange or a plain number, matching the web's own Slider/RangeSlider
 * split without needing a second class per numeric width. */
class SliderRow(
    override val indent: Int,
    private val value: RangedValue<Any>,
) : SettingRow {
    private val isRange = value.get() is ClosedRange<*>
    private val isInt = run {
        val probe = if (isRange) (value.get() as ClosedRange<*>).start else value.get()
        probe is Int
    }
    private var dragging = 0 // 0 = none, 1 = low/only handle, 2 = high handle

    private fun rangeLo() = numberOf(value.range.start)
    private fun rangeHi() = numberOf((value.range as ClosedRange<*>).endInclusive)
    private fun numberOf(a: Any?): Float = when (a) {
        is Int -> a.toFloat(); is Float -> a; is Double -> a.toFloat(); is Long -> a.toFloat(); else -> 0f
    }

    private fun currentLo(): Float = if (isRange) numberOf((value.get() as ClosedRange<*>).start) else numberOf(value.get())
    private fun currentHi(): Float = if (isRange) numberOf((value.get() as ClosedRange<*>).endInclusive) else currentLo()

    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val (label, control) = labelAndControlBounds(x, width, indent)
        val display = if (isRange) {
            "${formatNum(currentLo())} - ${formatNum(currentHi())}${value.suffix}"
        } else {
            "${formatNum(currentLo())}${value.suffix}"
        }
        drawLabel(gfx, "${value.name}  \u00A77$display", label.first, y, label.last - label.first)

        val trackY = y + height(width) / 2 - 2
        val trackX = control.first
        val trackW = control.last - control.first
        GuiRender2D.fillRoundedRect(gfx, trackX, trackY, trackW, 4, 2, ClickGuiPalette.SLIDER_TRACK)

        val lo = rangeLo()
        val hi = rangeHi()
        val span = (hi - lo).coerceAtLeast(0.0001f)
        val loFrac = ((currentLo() - lo) / span).coerceIn(0f, 1f)
        val hiFrac = ((currentHi() - lo) / span).coerceIn(0f, 1f)
        val fillX = trackX + (loFrac * trackW).toInt()
        val fillW = ((hiFrac - loFrac) * trackW).toInt().coerceAtLeast(0)
        GuiRender2D.fillRoundedRect(gfx, fillX, trackY, fillW, 4, 2, ClickGuiPalette.SLIDER_FILL)

        drawHandle(gfx, trackX + (loFrac * trackW).toInt(), trackY)
        if (isRange) drawHandle(gfx, trackX + (hiFrac * trackW).toInt(), trackY)
    }

    private fun drawHandle(gfx: GuiGraphicsExtractor, cx: Int, trackY: Int) {
        val d = 10
        GuiRender2D.fillCircle(gfx, cx, trackY + 2, d / 2, ClickGuiPalette.SLIDER_HANDLE)
    }

    private fun formatNum(f: Float) = if (isInt) f.roundToInt().toString() else "%.2f".format(f)

    private fun trackBounds(x: Int, width: Int): IntRange {
        val (_, control) = labelAndControlBounds(x, width, indent)
        return control
    }

    private fun fracToValue(frac: Float): Any {
        val lo = rangeLo(); val hi = rangeHi()
        val raw = lo + (hi - lo) * frac.coerceIn(0f, 1f)
        return if (isInt) raw.roundToInt() else raw
    }

    override fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        val control = trackBounds(x, width)
        if (mouseY !in y..(y + height(width))) return false
        if (mouseX !in control) return false
        val trackW = control.last - control.first
        val frac = (mouseX - control.first).toFloat() / trackW

        if (isRange) {
            val loFrac = (currentLo() - rangeLo()) / (rangeHi() - rangeLo())
            val hiFrac = (currentHi() - rangeLo()) / (rangeHi() - rangeLo())
            dragging = if (kotlin.math.abs(frac - loFrac) <= kotlin.math.abs(frac - hiFrac)) 1 else 2
        } else {
            dragging = 1
        }
        applyDrag(frac)
        return true
    }

    override fun mouseDragged(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (dragging == 0) return false
        val control = trackBounds(x, width)
        val trackW = (control.last - control.first).coerceAtLeast(1)
        val frac = ((mouseX - control.first).toFloat() / trackW).coerceIn(0f, 1f)
        applyDrag(frac)
        return true
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, button: Int): Boolean {
        val was = dragging != 0
        dragging = 0
        return was
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyDrag(frac: Float) {
        val newVal = fracToValue(frac)
        if (isRange) {
            var lo = currentLo(); var hi = currentHi()
            if (dragging == 1) lo = numberOf(newVal).coerceAtMost(hi) else hi = numberOf(newVal).coerceAtLeast(lo)
            val newRange: ClosedRange<*> = if (isInt) lo.roundToInt()..hi.roundToInt() else lo..hi
            (value as Value<Any>).set(newRange as Any)
        } else {
            (value as Value<Any>).set(newVal)
        }
    }
}

// ------------------------------------------------------------------- CHOOSE

/** Single choice from a fixed set (ChoiceListValue<T : Tagged>) - rendered as
 * a compact left/right cycle control so it never needs a popover, matching
 * ChooseSetting.svelte's own "cycle through values" trigger behaviour. */
class ChooseRow(override val indent: Int, private val value: ChoiceListValue<Tagged>) : SettingRow {

    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val (label, control) = labelAndControlBounds(x, width, indent)
        drawLabel(gfx, value.name, label.first, y, label.last - label.first)

        GuiRender2D.fillRoundedRect(gfx, control.first, y + 2, control.last - control.first, height(width) - 4, 4, ClickGuiPalette.INPUT_BG)
        GuiRender2D.strokeRoundedRect(gfx, control.first, y + 2, control.last - control.first, height(width) - 4, 4, 1, ClickGuiPalette.DROPDOWN_BORDER)
        val text = value.get().tag
        val font = net.minecraft.client.Minecraft.getInstance().font
        GuiRender2D.renderMarqueeText(gfx, font, text, control.first + 6, y + 4, control.last - control.first - 8, y, height(width), ClickGuiPalette.TEXT)
    }

    override fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        val (_, control) = labelAndControlBounds(x, width, indent)
        if (mouseY !in y..(y + height(width)) || mouseX !in control) return false
        val choices = value.choices.toList()
        val currentIdx = choices.indexOf(value.get()).coerceAtLeast(0)
        // left click -> next, right click -> previous (same "cycle" gesture either direction)
        val next = if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            (currentIdx - 1 + choices.size) % choices.size
        } else {
            (currentIdx + 1) % choices.size
        }
        value.set(choices[next])
        return true
    }
}

// -------------------------------------------------------------- MULTI_CHOOSE

/** Wrapping chip row (MultiChoiceListValue<T : Tagged>). Height grows to fit
 * however many lines of chips are needed, just like the web's flex-wrap. */
class MultiChooseRow(override val indent: Int, private val value: MultiChoiceListValue<Tagged>) : SettingRow {
    /** Relative to control-area-left (x=0), so this depends only on [width],
     * never on the row's absolute screen x - that's what makes it safe to
     * compute from height(width) alone, with no render pass required first. */
    private data class Chip(val tag: Tagged, val relX: Int, val relY: Int, val w: Int)

    private var chips: List<Chip> = emptyList()
    private var wrappedHeight = 17
    private var lastWidth = -1

    private fun ensureWrapped(width: Int) {
        if (lastWidth == width) return
        lastWidth = width
        val (_, control) = labelAndControlBounds(0, width, indent)
        val font = net.minecraft.client.Minecraft.getInstance().font
        val controlWidth = control.last - control.first
        var cx = 0
        var cy = 0
        val rowH = 16
        val gap = 4
        val list = mutableListOf<Chip>()
        for (choice in value.choices) {
            val w = font.width(choice.tag) + 12
            if (cx + w > controlWidth && cx != 0) {
                cx = 0
                cy += rowH + gap
            }
            list.add(Chip(choice, cx, cy, w))
            cx += w + gap
        }
        chips = list
        wrappedHeight = cy + rowH + 6
    }

    override fun height(width: Int): Int {
        ensureWrapped(width)
        return wrappedHeight
    }

    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        ensureWrapped(width)
        val (label, control) = labelAndControlBounds(x, width, indent)
        drawLabel(gfx, value.name, label.first, y, label.last - label.first)
        val selected = value.get().toSet()
        val font = net.minecraft.client.Minecraft.getInstance().font
        for (chip in chips) {
            val isSelected = chip.tag in selected
            val bg = if (isSelected) ClickGuiPalette.CHIP_SELECTED_BG else ClickGuiPalette.CHIP_BG
            val fg = if (isSelected) ClickGuiPalette.CHIP_SELECTED_TEXT else ClickGuiPalette.CHIP_TEXT
            val cx = control.first + chip.relX
            GuiRender2D.fillRoundedRect(gfx, cx, y + chip.relY, chip.w, 16, 8, bg)
            gfx.text(font, chip.tag.tag, cx + 6, y + chip.relY + 4, fg, false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        ensureWrapped(width)
        val (_, control) = labelAndControlBounds(x, width, indent)
        for (chip in chips) {
            val cx = control.first + chip.relX
            val top = y + chip.relY
            if (mouseX in cx..(cx + chip.w) && mouseY in top..(top + 16)) {
                val current = value.get().toMutableSet()
                if (!current.remove(chip.tag)) current.add(chip.tag)
                (value as MultiChoiceListValue<Tagged>).set(current)
                return true
            }
        }
        return false
    }
}

// ------------------------------------------------------------------- TEXT

class TextRow(override val indent: Int, private val value: Value<String>) : SettingRow {
    private var focused = false
    private var buffer = value.get()

    override fun isFocused() = focused

    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val (label, control) = labelAndControlBounds(x, width, indent)
        drawLabel(gfx, value.name, label.first, y, label.last - label.first)
        val w = control.last - control.first
        GuiRender2D.fillRoundedRect(gfx, control.first, y + 2, w, height(width) - 4, 4, ClickGuiPalette.INPUT_BG)
        val border = if (focused) ClickGuiPalette.INPUT_BORDER else ClickGuiPalette.withAlpha(ClickGuiPalette.INPUT_BORDER, 90)
        GuiRender2D.strokeRoundedRect(gfx, control.first, y + 2, w, height(width) - 4, 4, 1, border)
        val shown = if (focused) "$buffer\u00A77_" else buffer
        GuiRender2D.renderMarqueeText(gfx, net.minecraft.client.Minecraft.getInstance().font, shown, control.first + 6, y + 4, w - 8, y, height(width), ClickGuiPalette.TEXT)
    }

    override fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        val (_, control) = labelAndControlBounds(x, width, indent)
        val inside = mouseX in control && mouseY in y..(y + height(width))
        if (inside) buffer = value.get()
        focused = inside
        return inside
    }

    override fun charTyped(chr: Char): Boolean {
        if (!focused) return false
        buffer += chr
        value.set(buffer)
        return true
    }

    override fun keyPressed(keyCode: Int): Boolean {
        if (!focused) return false
        // GLFW.GLFW_KEY_BACKSPACE == 259
        if (keyCode == 259 && buffer.isNotEmpty()) {
            buffer = buffer.dropLast(1)
            value.set(buffer)
            return true
        }
        // GLFW.GLFW_KEY_ENTER / ESCAPE both just drop focus
        if (keyCode == 257 || keyCode == 256) {
            focused = false
            return true
        }
        return false
    }
}

// ------------------------------------------------------------------ COLOR

class ColorRow(override val indent: Int, private val value: Value<Color4b>) : SettingRow {

    companion object {
        private const val SWATCH_SIZE = 20
        private const val POPOVER_GAP = 6
        private const val HUE_BAR_H = 14
        private const val SV_BOX_H = 72
        private const val ALPHA_BAR_H = 14
        private const val SECTION_GAP = 6
        private const val POPOVER_HEIGHT =
            POPOVER_GAP + HUE_BAR_H + SECTION_GAP + SV_BOX_H + SECTION_GAP + ALPHA_BAR_H + 8
    }

    private var open = false
    private var hue = 0f
    private var sat = 0f
    private var brightness = 0f
    private var dragTarget = 0 // 0 = none, 1 = hue bar, 2 = SV box, 3 = alpha bar

    private fun syncFromValue() {
        val c = value.get()
        val hsv = java.awt.Color.RGBtoHSB(c.r, c.g, c.b, null)
        hue = hsv[0]; sat = hsv[1]; brightness = hsv[2]
    }

    private fun packColor4b(c: Color4b): Int = (c.a shl 24) or (c.r shl 16) or (c.g shl 8) or c.b

    private fun pushToValue() {
        val rgb = java.awt.Color.HSBtoRGB(hue, sat, brightness)
        value.set(Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, value.get().a))
    }

    override fun height(width: Int): Int = 17 + if (open) POPOVER_HEIGHT else 0

    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val (label, control) = labelAndControlBounds(x, width, indent)
        drawLabel(gfx, value.name, label.first, y, label.last - label.first)
        val c = value.get()
        val packed = packColor4b(c)
        val sx = control.last - SWATCH_SIZE
        GuiRender2D.fillRoundedRect(gfx, sx, y + 2, SWATCH_SIZE, 22 - 4, 4, packed)
        GuiRender2D.strokeRoundedRect(gfx, sx, y + 2, SWATCH_SIZE, 22 - 4, 4, 1, ClickGuiPalette.INPUT_BORDER)
        val hex = "#%02X%02X%02X".format(c.r, c.g, c.b)
        gfx.text(net.minecraft.client.Minecraft.getInstance().font, hex, control.first, y + 4, ClickGuiPalette.TEXT_DIMMED, false)

        if (!open) return

        val boxLeft = x + ROW_PAD + indent
        val boxRight = x + width - ROW_PAD
        val boxW = boxRight - boxLeft
        var cy = y + 22 + POPOVER_GAP

        // --- hue bar: one solid-color strip per 1/255th of the hue wheel ---
        val hueSteps = 48
        val stepW = (boxW.toFloat() / hueSteps)
        for (i in 0 until hueSteps) {
            val stripHue = i / hueSteps.toFloat()
            val rgb = java.awt.Color.HSBtoRGB(stripHue, 1f, 1f)
            val sxPx = (boxLeft + i * stepW).toInt()
            val exPx = (boxLeft + (i + 1) * stepW).toInt() + 1
            gfx.fill(sxPx, cy, exPx, cy + HUE_BAR_H, 0xFF000000.toInt() or (rgb and 0xFFFFFF))
        }
        run {
            val hx = boxLeft + (hue * boxW).toInt()
            gfx.fill(hx - 1, cy - 1, hx + 2, cy + HUE_BAR_H + 1, ClickGuiPalette.TEXT)
        }
        cy += HUE_BAR_H + SECTION_GAP

        // --- SV box: horizontal white->hue gradient, with a vertical
        // transparent->black gradient laid on top - the standard two-layer
        // trick for rendering an HSV saturation/value square out of plain
        // solid-color strips (no shader or dedicated gradient primitive
        // needed, just two passes of flat-colored quads) ---
        val hueRgb = java.awt.Color.HSBtoRGB(hue, 1f, 1f)
        val hueR = (hueRgb shr 16) and 0xFF; val hueG = (hueRgb shr 8) and 0xFF; val hueB = hueRgb and 0xFF
        val svSteps = 40
        val svStepW = boxW.toFloat() / svSteps
        for (i in 0 until svSteps) {
            val s = i / svSteps.toFloat()
            val r = (255 + (hueR - 255) * s).toInt().coerceIn(0, 255)
            val g = (255 + (hueG - 255) * s).toInt().coerceIn(0, 255)
            val b = (255 + (hueB - 255) * s).toInt().coerceIn(0, 255)
            val col = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            val sxPx = (boxLeft + i * svStepW).toInt()
            val exPx = (boxLeft + (i + 1) * svStepW).toInt() + 1
            gfx.fill(sxPx, cy, exPx, cy + SV_BOX_H, col)
        }
        val vSteps = 24
        val vStepH = SV_BOX_H.toFloat() / vSteps
        for (j in 0 until vSteps) {
            val v = j / vSteps.toFloat() // 0 at top (transparent) -> 1 at bottom (opaque black)
            val alpha = (v * 255).toInt().coerceIn(0, 255)
            val syPx = (cy + j * vStepH).toInt()
            val eyPx = (cy + (j + 1) * vStepH).toInt() + 1
            gfx.fill(boxLeft, syPx, boxRight, eyPx, (alpha shl 24))
        }
        GuiRender2D.strokeRoundedRect(gfx, boxLeft, cy, boxW, SV_BOX_H, 0, 1, ClickGuiPalette.INPUT_BORDER)
        run {
            val cursorX = boxLeft + (sat * boxW).toInt()
            val cursorY = cy + ((1f - brightness) * SV_BOX_H).toInt()
            GuiRender2D.fillCircle(gfx, cursorX, cursorY, 4, ClickGuiPalette.TEXT)
            GuiRender2D.fillCircle(gfx, cursorX, cursorY, 3, packed)
        }
        cy += SV_BOX_H + SECTION_GAP

        // --- alpha bar: current color at full opacity -> fully transparent ---
        val aSteps = 24
        val aStepW = boxW.toFloat() / aSteps
        for (i in 0 until aSteps) {
            val a = 255 - (i * 255 / aSteps)
            val col = (a shl 24) or (hueR shl 16) or (hueG shl 8) or hueB
            // checkerboard every other cell so alpha is readable against any backdrop
            val bgCol = if (i % 2 == 0) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt()
            val sxPx = (boxLeft + i * aStepW).toInt()
            val exPx = (boxLeft + (i + 1) * aStepW).toInt() + 1
            gfx.fill(sxPx, cy, exPx, cy + ALPHA_BAR_H, bgCol)
            gfx.fill(sxPx, cy, exPx, cy + ALPHA_BAR_H, col)
        }
        run {
            val ax = boxLeft + ((c.a / 255f) * boxW).toInt()
            gfx.fill(ax - 1, cy - 1, ax + 2, cy + ALPHA_BAR_H + 1, ClickGuiPalette.TEXT)
        }
    }

    private fun popoverBounds(x: Int, y: Int, width: Int): Triple<IntRange, Int, Int> {
        val boxLeft = x + ROW_PAD + indent
        val boxRight = x + width - ROW_PAD
        val hueTop = y + 22 + POPOVER_GAP
        val svTop = hueTop + HUE_BAR_H + SECTION_GAP
        return Triple(boxLeft..boxRight, hueTop, svTop)
    }

    override fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        val (_, control) = labelAndControlBounds(x, width, indent)
        val sx = control.last - SWATCH_SIZE
        if (mouseY in y..(y + 22) && mouseX in sx..(sx + SWATCH_SIZE)) {
            open = !open
            if (open) syncFromValue()
            return true
        }
        if (!open) return false

        val (box, hueTop, svTop) = popoverBounds(x, y, width)
        val alphaTop = svTop + SV_BOX_H + SECTION_GAP

        if (mouseY in hueTop..(hueTop + HUE_BAR_H) && mouseX in box) {
            dragTarget = 1
            dragHue(box, mouseX)
            return true
        }
        if (mouseY in svTop..(svTop + SV_BOX_H) && mouseX in box) {
            dragTarget = 2
            dragSv(box, svTop, mouseX, mouseY)
            return true
        }
        if (mouseY in alphaTop..(alphaTop + ALPHA_BAR_H) && mouseX in box) {
            dragTarget = 3
            dragAlpha(box, mouseX)
            return true
        }
        // clicked elsewhere while open: swallow it so clicks don't fall through
        // to whatever row happens to be underneath the popover
        return mouseY in y..(y + height(width))
    }

    override fun mouseDragged(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (dragTarget == 0) return false
        val (box, hueTop, svTop) = popoverBounds(x, y, width)
        when (dragTarget) {
            1 -> dragHue(box, mouseX)
            2 -> dragSv(box, svTop, mouseX, mouseY)
            3 -> dragAlpha(box, mouseX)
        }
        return true
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, button: Int): Boolean {
        val was = dragTarget != 0
        dragTarget = 0
        return was
    }

    private fun dragHue(box: IntRange, mouseX: Int) {
        val w = (box.last - box.first).coerceAtLeast(1)
        hue = ((mouseX - box.first).toFloat() / w).coerceIn(0f, 1f)
        pushToValue()
    }

    private fun dragSv(box: IntRange, svTop: Int, mouseX: Int, mouseY: Int) {
        val w = (box.last - box.first).coerceAtLeast(1)
        sat = ((mouseX - box.first).toFloat() / w).coerceIn(0f, 1f)
        brightness = 1f - ((mouseY - svTop).toFloat() / SV_BOX_H).coerceIn(0f, 1f)
        pushToValue()
    }

    private fun dragAlpha(box: IntRange, mouseX: Int) {
        val w = (box.last - box.first).coerceAtLeast(1)
        val a = (((mouseX - box.first).toFloat() / w).coerceIn(0f, 1f) * 255).toInt()
        val c = value.get()
        value.set(Color4b(c.r, c.g, c.b, a))
    }
}


// -------------------------------------------------------------- BIND / KEY

class BindRow(override val indent: Int, private val value: Value<Any>, private val keyName: (Any?) -> String) : SettingRow {
    private var listening = false

    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val (label, control) = labelAndControlBounds(x, width, indent)
        drawLabel(gfx, value.name, label.first, y, label.last - label.first)
        val w = control.last - control.first
        val bg = if (listening) ClickGuiPalette.ACCENT_SUBTLE_BG else ClickGuiPalette.INPUT_BG
        GuiRender2D.fillRoundedRect(gfx, control.first, y + 2, w, height(width) - 4, 4, bg)
        GuiRender2D.strokeRoundedRect(gfx, control.first, y + 2, w, height(width) - 4, 4, 1, ClickGuiPalette.INPUT_BORDER)
        val text = if (listening) "..." else keyName(value.get())
        GuiRender2D.renderMarqueeText(gfx, net.minecraft.client.Minecraft.getInstance().font, text, control.first + 6, y + 4, w - 8, y, height(width), ClickGuiPalette.TEXT)
    }

    override fun isFocused() = listening

    override fun mouseClicked(x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int, button: Int): Boolean {
        val (_, control) = labelAndControlBounds(x, width, indent)
        val inside = mouseX in control && mouseY in y..(y + height(width))
        if (inside) {
            listening = true
            return true
        }
        return false
    }

    /** Screen should route the next raw key/mouse event here while [isFocused]. */
    fun capture(rawKeyOrButton: Any) {
        @Suppress("UNCHECKED_CAST")
        (value as Value<Any>).set(rawKeyOrButton)
        listening = false
    }
}

// ------------------------------------------------------------ UNSUPPORTED

/** Same spirit as the web theme's own fallback branch in GenericSetting.svelte:
 * rather than guess at a bespoke editor for CURVE / VECTOR3_* / anything new,
 * name the value and its type plainly instead of rendering something wrong. */
class UnsupportedRow(override val indent: Int, private val value: Value<*>) : SettingRow {
    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val (label, _) = labelAndControlBounds(x, width, indent)
        drawLabel(gfx, "${value.name} \u00A78(unsupported type)", label.first, y, width - ROW_PAD * 2 - indent)
    }
}

// ---------------------------------------------------------------- BUILDER

object SettingRenderer {

    /** Flattens a value list into rows, recursing into ValueGroup children
     * (indented +14px per level) exactly the way ConfigurableSetting.svelte
     * nests a mode's own settings under its selector. */
    @Suppress("UNCHECKED_CAST")
    fun build(values: Collection<Value<*>>, indent: Int = 0): List<SettingRow> {
        val rows = mutableListOf<SettingRow>()
        for (value in values) {
            if (value.name == "Bind" || value.name == "Hidden") continue

            rows += rowFor(value, indent)

            if (value is ValueGroup) {
                rows += build(value.get(), indent + 14)
            }
        }
        return rows
    }

    @Suppress("UNCHECKED_CAST")
    private fun rowFor(value: Value<*>, indent: Int): SettingRow {
        val current = value.get()
        return when {
            value is ChoiceListValue<*> -> ChooseRow(indent, value as ChoiceListValue<Tagged>)
            value is MultiChoiceListValue<*> -> MultiChooseRow(indent, value as MultiChoiceListValue<Tagged>)
            value is RangedValue<*> && (current is Number || current is ClosedRange<*>) ->
                SliderRow(indent, value as RangedValue<Any>)
            current is Boolean -> BooleanRow(indent, value as Value<Boolean>)
            current is String -> TextRow(indent, value as Value<String>)
            current is Color4b -> ColorRow(indent, value as Value<Color4b>)
            value.javaClass.simpleName.contains("Bind") || value.javaClass.simpleName.contains("Key") ->
                BindRow(indent, value as Value<Any>) { it?.toString() ?: "NONE" }
            // A plain (non-toggleable) group has no control of its own - it's
            // just a labeled section; build() already recurses into its
            // children right after this row.
            value is ValueGroup -> SectionHeaderRow(indent, value.name)
            else -> UnsupportedRow(indent, value)
        }
    }
}

/** A thin, non-interactive divider line + label, used above a nested
 * settings group (mirrors the indentation rule/border-left accent that
 * Module.svelte's own `.settings` container uses for nested Configurables). */
class SectionHeaderRow(override val indent: Int, private val label: String) : SettingRow {
    override fun height(width: Int): Int = 18
    override fun render(gfx: GuiGraphicsExtractor, x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val left = x + ROW_PAD + indent
        GuiRender2D.renderMarqueeText(gfx, net.minecraft.client.Minecraft.getInstance().font, label, left, y + 4, width - ROW_PAD * 2 - indent, y, height(width), ClickGuiPalette.MODULE_ENABLED)
        GuiRender2D.line(gfx, left, y + 15, x + width - ROW_PAD, y + 15, 1, ClickGuiPalette.DIVIDER)
    }
}
