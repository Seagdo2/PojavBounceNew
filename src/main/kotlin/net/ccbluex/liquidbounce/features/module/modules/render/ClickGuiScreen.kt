package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.CharacterEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.File

/**
 * LiquidBounce-style ClickGUI — Multi-panel layout, each category is an independent floating panel.
 * Dark glass theme matching reference screenshot.
 */
class ClickGuiScreen : Screen(Component.literal("ClickGUI")) {

// ==================== Colors (暗黑主题, 匹配参考图: 近纯黑面板 + 白字 + 蓝色高亮) ====================
private val ACCENT = 0xFF6688FFL.toInt()           // 亮蓝 (开启/激活)
private val ACCENT_DARK = 0x666688FFL.toInt()
private val BG = 0xF00E0E12L.toInt()               // 面板背景: 近纯黑微透明
private val PANEL_BG = 0xEE0E0E12L.toInt()
private val TEXT get() = ModuleClickGui.getTextColor()
private val TEXT_BRIGHT = 0xFF6688FFL.toInt()       // 开启模块名亮蓝
private val TEXT_DIM get() = 0xFFAAAAAAL.toInt()   // 关闭模块名浅灰
private val CATEGORY_TITLE = 0xFFFFFFFFL.toInt()    // 分类标题纯白
private val TAB_BG = 0x801A1A1EL.toInt()
private val TAB_ACTIVE = 0xFF222228L.toInt()
private val BORDER = 0x15FFFFFFL.toInt()
private val HOVER = 0x12FFFFFFL.toInt()
private val SCROLL_TRACK = 0x206688FFL.toInt()
private val SCROLL_THUMB = 0x586688FFL.toInt()
private val SCROLL_THUMB_HOVER = 0x786688FFL.toInt()
private val EXPANDED_BG = 0x086688FFL.toInt()
private val GROUP_BG = 0x0A6688FFL.toInt()
private val GROUP_LINE = 0x106688FFL.toInt()
private val SETTING_CHILD_BG = 0x04FFFFFFL.toInt()
private val OVERLAY = 0x90000000L.toInt()            // 全局半透明黑背景
private val SETTING_BG = 0x40080810L.toInt()
// 【新增】开关样式颜色
private val TOGGLE_ON = 0xFF6688FFL.toInt()          // 开启状态填充色
private val TOGGLE_OFF_BORDER = 0x40FFFFFFL.toInt() // 关闭状态边框
private val SEARCH_BG = 0xE0101012L.toInt()         // 搜索框黑色背景

    // ==================== Layout ====================
    private val CORNER = 6f
    private val ITEM_H = 17f
    private val SETTING_H = 17f
    private val SCROLL_W = 3f
    private val PADDING = 5f
    private val SETTING_INDENT = 8f
    private val PANEL_GAP = 0f
    private val PANEL_MIN_W = 130
    private val PANEL_MAX_H = 460
    private val HEADER_H = 22f

    // ==================== Slider Drag State ====================
    private data class SliderContext(
        val value: Value<*>,
        val panel: PanelData,
        val sliderX: Int,
        val sliderY: Int,
        val sliderW: Int,
        val min: Float,
        val max: Float,
        val rangeWidth: Float = 0f,
        val rangePoint: Int = -1
    )
    private var sliderContext: SliderContext? = null

    // ==================== State ====================
    private var expandedModule: ClientModule? = null
    private var searchText = ""
    private var searchFocused = false
    private var listeningValue: Value<*>? = null
    private val collapsedGroups = mutableSetOf<Value<*>>()

    private var activeColorValue: Value<*>? = null
    private var colorPickerX = 0f
    private var colorPickerY = 0f
    private var colorPickerAlpha = 255
    private var colorPickerAlphaDragging = false
    private val PALETTE_ROWS = 9
    private val PALETTE_COLS = 5
    private val PALETTE_CELL = 10f
    private val PALETTE_GAP = 2f
    private val PALETTE_PAD = 4f
    private val ALPHA_BAR_H = 10f
    private val ALPHA_BAR_OFFSET = 6f
    private val PALETTE_W = PALETTE_COLS * (PALETTE_CELL + PALETTE_GAP) - PALETTE_GAP + PALETTE_PAD * 2
    private val PALETTE_H = PALETTE_ROWS * (PALETTE_CELL + PALETTE_GAP) - PALETTE_GAP + PALETTE_PAD * 2 + ALPHA_BAR_H + ALPHA_BAR_OFFSET

    private val paletteColors: List<Color4b> = buildList {
        for (row in 0 until 8) {
            val hue = row * 45f / 360f
            for (col in 0 until 5) {
                val brightness = 0.95f - col * 0.2f
                add(Color4b.ofHSB(hue, 0.9f, brightness))
            }
        }
        for (col in 0 until 5) {
            val brightness = 0.95f - col * 0.2f
            add(Color4b.ofHSB(0f, 0f, brightness))
        }
    }

    private var fadeAnim = 0f
    private var isFirstLoad = true
    private var layoutDirty = false
    private var layoutLastSave = 0L

    // ==================== Smooth Animation State ====================
    private val panelExpandAnim = mutableMapOf<PanelData, Float>()   // 面板展开: 0=折叠, 1=完全展开
    private val settingsExpandAnim = mutableMapOf<ClientModule, Float>() // 模块设置展开: 0=关闭, 1=完全展开
    private var guiOpenAnim = 0f        // GUI 整体打开动画 (0→1)
    private val ANIM_SPEED_EXPAND = 0.08f   // 展开/折叠速度
    private val ANIM_SPEED_SETTINGS = 0.08f  // 设置展开/收起速度
    private val ANIM_SPEED_FADE = 0.10f     // 整体淡入速度
    private val valuesCache = IdentityHashMap<ClientModule, List<Pair<Value<*>, Int>>>()
    private var valuesCacheTime = 0L
    private var cachedAllModules = emptyList<ClientModule>()
    private var cachedAllModulesTime = 0L
    private val categories = ModuleCategories.entries.toList()
    private val modeValueCache = IdentityHashMap<ClientModule, Value<*>?>()

    private data class PanelData(
        val category: ModuleCategory?,
        var x: Float, var y: Float, var w: Float, var h: Float,
        var scrollOffset: Float = 0f, var targetScroll: Float = 0f,
        var draggingScroll: Boolean = false,
        var draggingPanel: Boolean = false,
        var dragOffsetX: Float = 0f,
        var dragOffsetY: Float = 0f,
        var collapsed: Boolean = false
    )

    private var panels = mutableListOf<PanelData>()
    private var searchPanel: PanelData? = null

    override fun isPauseScreen() = false
    // 【修复】恢复 Minecraft 默认的 ESC 关闭行为。
    // shouldCloseOnEsc() = false 时, 原版会在 KeyboardHandler 里直接吞掉 ESC,
    // 既不会关闭本界面, 也不会把 ESC 传给 Screen.keyPressed, 导致无法关闭。
    // 改为 true 后由原版在 onClose() 中正常关闭, 不会把 ESC 漏给游戏触发暂停菜单。
    override fun shouldCloseOnEsc() = true

    private fun fillRect(ctx: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        if (x2 <= x1 || y2 <= y1) {
            return
        }
        ctx.fill(x1, y1, x2, y2, color)
    }

    private fun fillRect(ctx: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        if (x2 <= x1 || y2 <= y1) {
            return
        }
        ctx.fill(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), color)
    }

    private fun drawText(ctx: GuiGraphicsExtractor, font: Font, text: String, x: Int, y: Int, color: Int) {
        val pose = try {
            ctx.pose()
        } catch (_: Exception) {
            null
        }
        if (pose != null) {
            try {
                pose.pushMatrix()
                pose.translate(x.toFloat(), y.toFloat())
                pose.scale(TEXT_SCALE, TEXT_SCALE)
                ctx.text(font, text, 0, 0, color)
                pose.popMatrix()
                return
            } catch (_: Exception) {
                try {
                    pose.popMatrix()
                } catch (_: Exception) {
                }
            }
        }
        ctx.text(font, text, x, y, color)
    }

    private fun drawRoundedRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = radius.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
        if (r <= 0.5f) {
            fillRect(ctx, x, y, x + w, y + h, color)
            return
        }
        val x1 = x
        val y1 = y
        val x2 = x + w
        val y2 = y + h
        fillRect(ctx, x1 + r, y1, x2 - r, y2, color)
        fillRect(ctx, x1, y1 + r, x1 + r, y2 - r, color)
        fillRect(ctx, x2 - r, y1 + r, x2, y2 - r, color)
        drawCorner(ctx, x1 + r, y1 + r, r, 180f, 270f, color)
        drawCorner(ctx, x2 - r, y1 + r, r, 270f, 360f, color)
        drawCorner(ctx, x2 - r, y2 - r, r, 0f, 90f, color)
        drawCorner(ctx, x1 + r, y2 - r, r, 90f, 180f, color)
    }

    private fun drawCorner(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, start: Float, end: Float, color: Int) {
        var a = start
        while (a < end) {
            val rad1 = Math.toRadians(a.toDouble())
            val rad2 = Math.toRadians((a + 6f).coerceAtMost(end).toDouble())
            val px1 = cx + (cos(rad1) * r).toFloat()
            val py1 = cy + (sin(rad1) * r).toFloat()
            val px2 = cx + (cos(rad2) * r).toFloat()
            val py2 = cy + (sin(rad2) * r).toFloat()
            val minX = cx.coerceAtMost(px1).coerceAtMost(px2).toInt()
            val maxX = cx.coerceAtLeast(px1).coerceAtLeast(px2).toInt()
            val minY = cy.coerceAtMost(py1).coerceAtMost(py2).toInt()
            val maxY = cy.coerceAtLeast(py1).coerceAtLeast(py2).toInt()
            fillRect(ctx, minX, minY, max(minX + 1, maxX), max(minY + 1, maxY), color)
            a += 6f
        }
    }

    /** 真正圆形: 逐像素判定 x²+y²≤r², 按行填充 */
    private fun drawCircle(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, color: Int) {
        val ri = r.toInt()
        for (dy in -ri..ri) {
            val halfW = sqrt(r * r - dy * dy).toInt()
            val yy = (cy + dy).toInt()
            fillRect(ctx, (cx - halfW).toInt(), yy, (cx + halfW).toInt(), yy + 1, color)
        }
    }

    private val TEXT_SCALE = 0.9f

    private fun trimText(font: Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) {
            return text
        }
        var str = text
        while (str.isNotEmpty() && font.width("$str...") > maxWidth) {
            str = str.substring(0, str.length - 1)
        }
        return if (str.isEmpty()) {
            "..."
        } else {
            "$str..."
        }
    }

    private fun getCategoryModules(category: ModuleCategory): List<ClientModule> {
        return try {
            getAllModulesCached()
                .filter { it.category == category }
                .filter { searchText.isEmpty() || it.name.contains(searchText, ignoreCase = true) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 模块列表缓存: GUI 开启期间模块集合基本不变, 避免每帧重复复制/过滤全部模块 */
    private fun getAllModulesCached(): List<ClientModule> {
        val now = System.currentTimeMillis()
        if (cachedAllModules.isEmpty() || now - cachedAllModulesTime > 1000L) {
            cachedAllModules = ModuleManager.getModules().toList()
            cachedAllModulesTime = now
        }
        return cachedAllModules
    }

    private fun getExpandedHeight(mod: ClientModule): Float {
        if (expandedModule != mod) {
            return 0f
        }
        var h = 0f
        for ((v, _) in getVisibleValuesCached(mod)) {
            val actual = getActualValue(v)
            if (v is ModeValueGroup<*>) {
                h += max(1f, (1 + v.modes.size).toFloat()) * SETTING_H
            } else if (isEnumWithMultiple(actual)) {
                val constants = getEnumConstants(actual)
                h += max(1f, constants.size.toFloat()) * SETTING_H
            } else {
                h += SETTING_H
            }
        }
        return h
    }

    private fun getContentHeight(modules: List<ClientModule>): Float {
        var h = 0f
        modules.forEach { mod ->
            h += ITEM_H
            h += getExpandedHeight(mod)
        }
        return h
    }

    override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
    }

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // 【新增】全局半透明黑背景 (高性能: 单次 fillRect 覆盖全屏, 无额外开销)
        ctx.fill(0, 0, minecraft!!.window.guiScaledWidth, minecraft!!.window.guiScaledHeight, OVERLAY)

        // 【丝滑动画】整体淡入 + 面板展开/折叠 + 设置展开/收起
        guiOpenAnim += (1f - guiOpenAnim) * ANIM_SPEED_FADE
        fadeAnim = guiOpenAnim
        if (fadeAnim < 0.01f) return

        // 【丝滑】面板折叠/展开动画: 0=完全折叠(26px), 1=完全展开(fullH)
        for (panel in panels) {
            val targetExpand = if (panel.collapsed) 0f else 1f
            val current = panelExpandAnim.getOrDefault(panel, targetExpand)
            val next = current + (targetExpand - current) * ANIM_SPEED_EXPAND
            panelExpandAnim[panel] = next
        }
        // 【丝滑】模块设置展开/收起动画: 0=关闭, 1=完全展开
        for (mod in getAllModulesCached()) {
            val targetSettings = if (expandedModule == mod) 1f else 0f
            val current = settingsExpandAnim.getOrDefault(mod, targetSettings)
            settingsExpandAnim[mod] = current + (targetSettings - current) * ANIM_SPEED_SETTINGS
        }

        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight
        val font = minecraft!!.font
        val isSearching = searchText.isNotEmpty()

        // 【优化】不在渲染帧里做自动保存(文件 IO + 全模块扫描会卡帧)。
        // JSON 改为仅在 onClose()/removed() 关闭时保存一次。

        val savedLayout = if (isFirstLoad) {
            loadLayout()
        } else {
            LayoutState(emptyMap(), null, emptyList())
        }
        val targetPanels = mutableListOf<PanelData>()

        if (isSearching) {
            val w = (sc * 0.6f).coerceAtLeast(PANEL_MIN_W.toFloat()).coerceAtMost(sc.toFloat())
            val h = (sh * 0.7f).coerceAtMost(PANEL_MAX_H.toFloat())
            val x = (sc - w) / 2f
            val y = (sh - h) / 2f
            searchPanel = searchPanel ?: PanelData(null, x, y, w, h, 0f, 0f)
            searchPanel?.let {
                it.x = x
                it.y = y
                it.w = w
                it.h = h
            }
            if (searchPanel != null) {
                targetPanels.add(searchPanel!!)
            }
        } else {
            searchPanel = null
            val count = categories.size
            // 【修改】单列居中布局: 每个分类一个面板, 从上到下排列在屏幕正中间
            // 面板尺寸: 宽度与原版一致(按分类数均分屏宽), 高度为可展开的完整面板高
            val totalGap = PANEL_GAP * (count - 1)
            val widthPerPanel = ((sc - totalGap) / count).coerceAtMost(PANEL_MIN_W * 1.6f)
            val panelW = widthPerPanel.coerceAtLeast(PANEL_MIN_W.toFloat())
            val panelH = (sh * 0.7f).coerceAtMost(PANEL_MAX_H.toFloat())
            val panelGap = 4f          // 面板之间的小间隙
            // 【修复】折叠面板的槽位用折叠高度(26px)紧凑排列居中; 面板保留完整高度用于展开
            val slotH = HEADER_H + 2f  // 折叠时的视觉高度
            val totalH = slotH * count + panelGap * (count - 1)
            val startX = (sc - panelW) / 2f  // 水平居中
            var startY = (sh - totalH) / 2f   // 垂直居中（整体）

            for ((idx, cat) in categories.withIndex()) {
                val panelX = startX
                val panelY = startY
                startY += slotH + panelGap  // 下一个面板往下排

                val existingPanel = panels.find { it.category == cat }
                if (existingPanel != null) {
                    if (isFirstLoad) {
                        savedLayout.panels[cat.tag]?.let {
                            existingPanel.x = it.x.toFloat()
                            existingPanel.y = it.y.toFloat()
                            existingPanel.collapsed = it.collapsed
                            existingPanel.targetScroll = it.scroll
                            existingPanel.scrollOffset = it.scroll
                        } ?: run {
                            // 无存档 → 复位到默认槽位
                            existingPanel.x = panelX
                            existingPanel.y = panelY
                        }
                    }
                    // 【修复】始终更新面板尺寸(宽高), 保证能正常展开列表
                    existingPanel.w = panelW
                    existingPanel.h = panelH
                    targetPanels.add(existingPanel)
                } else {
                    val saved = savedLayout.panels[cat.tag]
                    val savedX = saved?.x?.toFloat() ?: panelX
                    val savedY = saved?.y?.toFloat() ?: panelY
                    // 【修改】默认全部折叠 (collapsed=true), 有保存状态才恢复
                    val savedCollapsed = saved?.collapsed ?: true
                    val newPanel = PanelData(cat, savedX, savedY, panelW, panelH, collapsed = savedCollapsed)
                    newPanel.targetScroll = saved?.scroll ?: 0f
                    newPanel.scrollOffset = newPanel.targetScroll
                    targetPanels.add(newPanel)
                }
            }
            panels.removeAll {
                targetPanels.contains(it).not() && it.category != null
            }
            panels = targetPanels
            if (isFirstLoad) {
                savedLayout.expandedModule?.let { name ->
                    val mod = ModuleManager.getModuleByName(name)
                    expandedModule = mod
                }
                for (key in savedLayout.collapsedGroups) {
                    val idx = key.indexOf(':')
                    if (idx <= 0) {
                        continue
                    }
                    val mod = ModuleManager.getModuleByName(key.substring(0, idx)) ?: continue
                    val groupName = key.substring(idx + 1)
                    for ((v, _) in getVisibleValues(mod)) {
                        if (v.name == groupName && isGroupValue(v)) {
                            collapsedGroups.add(v)
                        }
                    }
                }
                isFirstLoad = false
            }
        }

        // 【修复】展开的面板最后渲染(置顶), 避免被下方折叠面板盖住
        val panelRenderList = if (isSearching) targetPanels else panels.sortedBy { it.collapsed }
        for (panel in panelRenderList) {
                val px = panel.x
                val py = panel.y
                val pw = panel.w
                val ph = panel.h
            // 【丝滑动画】面板高度用动画值插值: 折叠(26px) ↔ 展开(fullH)
            val expandT = panelExpandAnim.getOrDefault(panel, if (panel.collapsed) 0f else 1f)
            val collapsedH = HEADER_H + 2f
            val expandedH = ph
            val actualHeight = collapsedH + (expandedH - collapsedH) * expandT
            drawRoundedRect(ctx, px, py, pw, actualHeight, CORNER, BG)

            var panelModules: List<ClientModule>
            if (isSearching) {
                panelModules = categories.flatMap { getCategoryModules(it) }.distinct()
                drawText(ctx, font, "§lSearch Results", (px + 8f).toInt(), (py + 4f).toInt(), ACCENT)
            } else {
                val category = panel.category ?: continue
                panelModules = getCategoryModules(category)
                // 【修改】分类名移到标题栏最左边, ▼▶ 改为 –+ 并移到最右边
                drawText(ctx, font, "§l${category.tag}", (px + 8f).toInt(), (py + 4f).toInt(), CATEGORY_TITLE)
                val arrow = if (panel.collapsed) "–" else "+"
                val arrowX = (px + pw - 10f - font.width(arrow) * TEXT_SCALE).roundToInt()
                drawText(ctx, font, "§l$arrow", arrowX, (py + 4f).toInt(), CATEGORY_TITLE)
                // 【修改】底部蓝紫细条: 通宽面板, 1px 细线
                fillRect(ctx, px, py + HEADER_H - 1f, px + pw, py + HEADER_H, ACCENT)
            }

            if (panel.collapsed) {
                continue
            }

            val headerH = HEADER_H
            val listAreaX = px + PADDING
            val listAreaW = pw - PADDING * 2 - SCROLL_W
            val listAreaY = py + headerH + 4f
            val listAreaH = ph - headerH - 8f

            val contentH = getContentHeight(panelModules)
            val maxScroll = max(0f, contentH - listAreaH)
            panel.targetScroll = panel.targetScroll.coerceIn(0f, maxScroll)
            panel.scrollOffset += (panel.targetScroll - panel.scrollOffset) * 0.3f

            ctx.enableScissor(
                listAreaX.toInt(),
                listAreaY.toInt(),
                (listAreaX + listAreaW).toInt(),
                (listAreaY + listAreaH).toInt()
            )
            var curY = listAreaY - panel.scrollOffset

            for (mod in panelModules) {
                val isExpanded = expandedModule == mod
                val modEndY = curY + ITEM_H

                if (curY < listAreaY + listAreaH && modEndY > listAreaY) {
                    val isHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                            mouseY in curY.toInt()..modEndY.toInt()
                    if (isHover) {
                        fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, modEndY, HOVER)
                    }
                    if (isExpanded) {
                        fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, modEndY, EXPANDED_BG)
                    }
                    val nameColor = if (mod.enabled) {
                        TEXT_BRIGHT
                    } else {
                        TEXT
                    }
                    val nameMaxW = (listAreaW - 16).toInt()
                    drawText(ctx, font, trimText(font, mod.name, nameMaxW), (listAreaX + 4f).toInt(), (curY + 4f).toInt(), nameColor)
                    val dotX = (listAreaX + listAreaW - 4f).toInt()
                    val dotY = curY.toInt() + 7
                    fillRect(ctx, dotX, dotY, dotX + 4, dotY + 4, if (mod.enabled) ACCENT else 0x40808080.toInt())
                }

                curY += ITEM_H
                if (isExpanded) {
                    // 【丝滑动画】设置区域高度用动画值插值: 0=关闭, 1=完全展开
                    val settingsT = settingsExpandAnim.getOrDefault(mod, 1f)
                    val values = getVisibleValuesCached(mod)
                    var totalSettingH = 0f
                    for ((v, _) in values) {
                        val actual = getActualValue(v)
                        if (v is ModeValueGroup<*>) {
                            totalSettingH += max(1f, (1 + v.modes.size).toFloat()) * SETTING_H
                        } else if (isEnumWithMultiple(actual)) {
                            totalSettingH += max(1f, getEnumConstants(actual).size.toFloat()) * SETTING_H
                        } else {
                            totalSettingH += SETTING_H
                        }
                    }
                    // 动画缩放实际渲染高度 (从上往下生长效果)
                    val animatedTotalH = totalSettingH * settingsT
                    val bgStart = curY.coerceAtLeast(listAreaY)
                    val bgEnd = (curY + animatedTotalH).coerceAtMost(listAreaY + listAreaH)
                    if (bgEnd > bgStart && settingsT > 0.01f) {
                        fillRect(ctx, listAreaX, bgStart, listAreaX + listAreaW, bgEnd, SETTING_BG)
                        // 【丝滑】用 scissor 裁剪动画中的设置区域
                        ctx.enableScissor(
                            listAreaX.toInt(),
                            bgStart.toInt(),
                            (listAreaX + listAreaW).toInt(),
                            bgEnd.toInt()
                        )
                    }

                    for ((v, depth) in values) {
                        val actual = getActualValue(v)
                        if (v is ModeValueGroup<*>) {
                            val rowCount = renderModeListForModeGroup(ctx, font, v, listAreaX, curY, listAreaW, depth, mouseX, mouseY)
                            curY += rowCount * SETTING_H
                        } else if (isEnumWithMultiple(actual)) {
                            val rowCount = renderModeListVertical(ctx, font, v, getEnumConstants(actual), actual as Enum<*>, listAreaX, curY, listAreaW, depth, mouseX, mouseY, listAreaY, listAreaH)
                            curY += rowCount * SETTING_H
                        } else {
                            val settingEndY = curY + SETTING_H
                            if (curY < listAreaY + listAreaH && settingEndY > listAreaY) {
                                val isSettingHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                                        mouseY in curY.toInt()..settingEndY.toInt()
                                if (isSettingHover) {
                                    fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, settingEndY, HOVER)
                                }
                                renderSetting(ctx, v, depth, listAreaX, curY, listAreaW, mouseX, mouseY, mod)
                            }
                            curY += SETTING_H
                        }
                    }
                    // 关闭设置的 scissor
                    if (bgEnd > bgStart && settingsT > 0.01f) {
                        ctx.disableScissor()
                    }
                }
            }
            ctx.disableScissor()

            if (contentH > listAreaH) {
                fillRect(ctx, listAreaX + listAreaW, listAreaY, listAreaX + listAreaW + SCROLL_W, listAreaY + listAreaH, SCROLL_TRACK)
                val thumbH = (listAreaH * listAreaH / contentH).coerceAtLeast(12f)
                val thumbY = listAreaY + if (maxScroll > 0f) (panel.scrollOffset / maxScroll) * (listAreaH - thumbH) else 0f
                val isScrollHover = mouseX in (listAreaX + listAreaW - 4).toInt()..(listAreaX + listAreaW + SCROLL_W + 4).toInt() &&
                        mouseY in listAreaY.toInt()..(listAreaY + listAreaH).toInt()
                val thumbColor = if (isScrollHover || panel.draggingScroll) {
                    SCROLL_THUMB_HOVER
                } else {
                    SCROLL_THUMB
                }
                fillRect(ctx, listAreaX + listAreaW, thumbY, listAreaX + listAreaW + SCROLL_W, thumbY + thumbH, thumbColor)
            }
        }

        // 【修改】搜索框移到最上方正中间, 黑色圆角样式
        val searchW = 180f
        val searchH = 18f
        val searchX = (sc - searchW) / 2f
        val searchY = 6f
        drawRoundedRect(ctx, searchX, searchY, searchW, searchH, 5f, SEARCH_BG)

        if (searchText.isEmpty()) {
            drawText(ctx, font, "§7Search modules...", (searchX + 4f).toInt(), (searchY + 3f).toInt(), TEXT_DIM)
        } else {
            drawText(ctx, font, trimText(font, searchText, (searchW - 20).toInt()), (searchX + 4f).toInt(), (searchY + 3f).toInt(), TEXT)
        }

        if (searchFocused) {
            val cursorX = searchX.toInt() + 4 + font.width(searchText)
            if (cursorX < searchX + searchW - 4) {
                val blink = System.currentTimeMillis() / 500 % 2 == 0L
                if (blink) {
                    fillRect(ctx, cursorX, (searchY + 3f).toInt(), cursorX + 1, (searchY + 15f).toInt(), TEXT_BRIGHT)
                }
            }
        }

        val colorVal = activeColorValue
        if (colorVal != null) {
            val px = colorPickerX.coerceIn(0f, sc - PALETTE_W)
            val py = colorPickerY.coerceIn(0f, sh - PALETTE_H)
            drawRoundedRect(ctx, px, py, PALETTE_W, PALETTE_H, 4f, BG)
            fillRect(ctx, px.toInt(), py.toInt(), (px + PALETTE_W).toInt(), py.toInt() + 1, SCROLL_THUMB)
            fillRect(ctx, px.toInt(), (py + PALETTE_H - 1).toInt(), (px + PALETTE_W).toInt(), (py + PALETTE_H).toInt(), SCROLL_THUMB)
            fillRect(ctx, px.toInt(), py.toInt(), px.toInt() + 1, (py + PALETTE_H).toInt(), SCROLL_THUMB)
            fillRect(ctx, (px + PALETTE_W - 1).toInt(), py.toInt(), (px + PALETTE_W).toInt(), (py + PALETTE_H).toInt(), SCROLL_THUMB)

            val currentRgb = extractColor(colorVal).rgb and 0xFFFFFF
            for (index in paletteColors.indices) {
                val row = index / PALETTE_COLS
                val col = index % PALETTE_COLS
                val cx = px + PALETTE_PAD + col * (PALETTE_CELL + PALETTE_GAP)
                val cy = py + PALETTE_PAD + row * (PALETTE_CELL + PALETTE_GAP)
                val color4b = paletteColors[index]
                fillRect(ctx, cx.toInt(), cy.toInt(), (cx + PALETTE_CELL).toInt(), (cy + PALETTE_CELL).toInt(), color4b.argb)
                if ((color4b.argb and 0xFFFFFF) == currentRgb) {
                    fillRect(ctx, (cx - 1).toInt(), (cy - 1).toInt(), (cx + PALETTE_CELL + 1).toInt(), cy.toInt(), TEXT_BRIGHT)
                    fillRect(ctx, (cx - 1).toInt(), (cy + PALETTE_CELL).toInt(), (cx + PALETTE_CELL + 1).toInt(), (cy + PALETTE_CELL + 1).toInt(), TEXT_BRIGHT)
                    fillRect(ctx, (cx - 1).toInt(), (cy - 1).toInt(), cx.toInt(), (cy + PALETTE_CELL + 1).toInt(), TEXT_BRIGHT)
                    fillRect(ctx, (cx + PALETTE_CELL).toInt(), (cy - 1).toInt(), (cx + PALETTE_CELL + 1).toInt(), (cy + PALETTE_CELL + 1).toInt(), TEXT_BRIGHT)
                }
            }

            val barY = py + PALETTE_H - ALPHA_BAR_H
            val barX = px + PALETTE_PAD
            val barW = PALETTE_W - PALETTE_PAD * 2
            val (cr, cg, cb) = currentColorRgb(colorVal)
            for (s in 0 until 8) {
                val a = (s * 255 / 7).coerceIn(0, 255)
                val sx = barX + barW * s / 8
                val ex = barX + barW * (s + 1) / 8
                fillRect(ctx, sx.toInt(), barY.toInt(), ex.toInt(), (barY + ALPHA_BAR_H).toInt(), (a shl 24) or (cr shl 16) or (cg shl 8) or cb)
            }
            val knobX = barX + barW * colorPickerAlpha / 255
            fillRect(ctx, (knobX - 2).toInt(), (barY - 2).toInt(), (knobX + 2).toInt(), (barY + ALPHA_BAR_H + 2).toInt(), TEXT_BRIGHT)
            drawText(ctx, font, "$colorPickerAlpha", (barX + barW + 3f).roundToInt(), (barY + 1f).toInt(), TEXT_DIM)
        }
    }

    private data class SliderLayout(
        val sliderX: Int,
        val sliderW: Int,
        val valText: String,
        val valX: Int,
        val fv: Float,
        val minV: Float,
        val maxV: Float,
        val rangeWidth: Float = 0f,
        val lowerPointX: Int = 0,
        val upperPointX: Int = 0,
        val rangeEnd: Float = 0f,
    )

    private fun formatRange(r: ClosedRange<*>): String {
        val s = r.start
        return when (s) {
            is Int -> "${s}~${r.endInclusive}"
            is Long -> "$s~${r.endInclusive}"
            is Float -> String.format(java.util.Locale.US, "%.1f~%.1f", s, r.endInclusive)
            is Double -> String.format(java.util.Locale.US, "%.1f~%.1f", s, r.endInclusive)
            else -> "$s~${r.endInclusive}"
        }
    }

    private fun computeSliderLayout(font: Font, v: Value<*>, actual: Any?, x: Float, w: Float, indent: Float): SliderLayout {
        var fv = 0f
        var minV = 0f
        var maxV = 100f
        var rangeText: String? = null
        var rangeWidth = 0f
        var rangeEnd = 0f

        if (actual is ClosedRange<*>) {
            val start = (actual.start as? Number)?.toFloat() ?: 0f
            val endIncl = (actual.endInclusive as? Number)?.toFloat() ?: start
            fv = start
            rangeEnd = endIncl
            rangeWidth = (endIncl - start).coerceAtLeast(0f)
            rangeText = formatRange(actual)
        } else if (actual is Number) {
            fv = actual.toFloat()
        }
        if (v is RangedValue<*>) {
            minV = (v.range.start as? Number)?.toFloat() ?: 0f
            maxV = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
        }

        val rightEdge = (x + w - 2).toInt()
        val sliderW = 36
        val sliderX = rightEdge - 3 - sliderW
        val minSliderX = (x + 6 + indent).toInt() + 24
        val maxValW = (sliderX - 3 - minSliderX).coerceIn(12, 80)
        val valText = trimText(font, rangeText ?: String.format(java.util.Locale.US, "%.1f", fv), maxValW)
        val valW = font.width(valText).coerceAtMost(maxValW)
        val valX = sliderX - 3 - valW

        var lowerPointX = 0
        var upperPointX = 0
        if (rangeWidth > 0f || actual is ClosedRange<*>) {
            val span = (maxV - minV).takeIf { it > 0f } ?: 1f
            lowerPointX = sliderX + ((fv - minV) / span * sliderW).toInt().coerceIn(0, sliderW)
            upperPointX = sliderX + ((rangeEnd - minV) / span * sliderW).toInt().coerceIn(0, sliderW)
        }
        return SliderLayout(sliderX, sliderW, valText, valX, fv, minV, maxV, rangeWidth, lowerPointX, upperPointX, rangeEnd)
    }

    private fun renderSetting(ctx: GuiGraphicsExtractor, v: Value<*>, depth: Int, x: Float, y: Float, w: Float, mouseX: Int, mouseY: Int, mod: ClientModule) {
        val font = minecraft!!.font
        val indent = depth * SETTING_INDENT

        if (depth > 0) {
            fillRect(ctx, x, y, x + w, y + SETTING_H, SETTING_CHILD_BG)
            val lineX = (x + 4f + indent).toInt()
            fillRect(ctx, lineX, y.toInt() + 1, lineX + 1, y.toInt() + SETTING_H.toInt() - 1, GROUP_LINE)
        }

        val actual = getActualValue(v)
        val isGroup = isGroupValue(v)
        val labelX = (x + 6 + indent).toInt()
        val toggleX = (x + w - 26).toInt()
        val valueX = (x + w - 44).toInt()
        val labelMaxW = (valueX - labelX - 4).coerceAtLeast(10)

        when {
            isGroup -> {
                val isCollapsed = collapsedGroups.contains(v)
                val arrow = if (isCollapsed) {
                    "▶"
                } else {
                    "▼"
                }
                fillRect(ctx, x, y, x + w, y + SETTING_H, GROUP_BG)
                val groupMaxW = (w - 16 - indent).toInt().coerceAtLeast(10)
                drawText(ctx, font, "$arrow ${trimText(font, v.name, groupMaxW)}", labelX, (y + 4f).toInt(), if (isCollapsed) TEXT_DIM else ACCENT)
            }
            actual is Boolean -> {
                val nameMaxW = (toggleX - labelX - 28).coerceAtLeast(10)   // 留出开关空间
                drawText(ctx, font, trimText(font, v.name, nameMaxW), labelX, (y + 4f).toInt(), TEXT_DIM)
                // 【修改】仿图药丸开关: ON=蓝色药丸+右白圆, OFF=只有白色圆
                val tw = 22f
                val th = 12f
                val tx = toggleX.toFloat()
                val ty = y.toInt() + 2f
                val tr = th / 2f   // 半径=高度/2 → 完全圆形药丸
                if (actual) {
                    // 开启状态: 蓝紫填充圆形药丸
                    drawRoundedRect(ctx, tx, ty, tw, th, tr, TOGGLE_ON)
                    // 右侧白色正圆钮 (真圆)
                    drawCircle(ctx, tx + tw - 6f, ty + 6f, 4f, 0xFFFFFFFF.toInt())
                } else {
                    // 关闭状态: 无药丸底, 只有左侧白色正圆
                    drawCircle(ctx, tx + 6f, ty + 6f, 4f, 0xFFFFFFFF.toInt())
                }
            }
            isBindValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 4f).toInt(), TEXT_DIM)
                val isListening = listeningValue == v
                val bindStr = trimText(font, formatBindValue(v), (w - 60 - indent).toInt())
                val display = if (isListening) {
                    "§e[...]"
                } else {
                    "§7$bindStr"
                }
                drawText(ctx, font, display, valueX, (y + 4f).toInt(), if (isListening) ACCENT else TEXT_DIM)
            }
            isSliderValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 3f).toInt(), TEXT_DIM)
                val layout = computeSliderLayout(font, v, actual, x, w, indent)
                val sliderY = y.toInt() + 8
                val isRange = layout.rangeWidth > 0f || layout.upperPointX != 0

                fillRect(ctx, layout.sliderX, sliderY, layout.sliderX + layout.sliderW, sliderY + 1, 0x40FFFFFF.toInt())

                if (isRange) {
                    val lx = layout.lowerPointX
                    val ux = layout.upperPointX.coerceAtLeast(lx + 2)
                    fillRect(ctx, lx, sliderY, ux, sliderY + 1, ACCENT)
                    fillRect(ctx, lx - 3, sliderY - 3, lx + 3, sliderY + 3, TEXT_BRIGHT)
                    fillRect(ctx, ux - 3, sliderY - 3, ux + 3, sliderY + 3, TEXT_BRIGHT)
                } else {
                    val progress = if (layout.maxV > layout.minV) {
                        ((layout.fv - layout.minV) / (layout.maxV - layout.minV)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    fillRect(ctx, layout.sliderX, sliderY, layout.sliderX + (layout.sliderW * progress).toInt(), sliderY + 1, ACCENT)
                    fillRect(ctx, layout.sliderX + (layout.sliderW * progress).toInt() - 3, sliderY - 3, layout.sliderX + (layout.sliderW * progress).toInt() + 3, sliderY + 3, TEXT_BRIGHT)
                }
                drawText(ctx, font, layout.valText, layout.valX, (y + 3f).toInt(), TEXT_DIM)
            }
            isColorValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 4f).toInt(), TEXT_DIM)
                val color = extractColor(v)
                fillRect(ctx, valueX, y.toInt() + 4, valueX + 10, y.toInt() + 14, color.rgb)
                if (activeColorValue == v) {
                    val bx = valueX - 1
                    val by = y.toInt() + 3
                    fillRect(ctx, bx, by, bx + 12, by + 1, TEXT_BRIGHT)
                    fillRect(ctx, bx, by + 11, bx + 12, by + 12, TEXT_BRIGHT)
                    fillRect(ctx, bx, by, bx + 1, by + 12, TEXT_BRIGHT)
                    fillRect(ctx, bx + 11, by, bx + 12, by + 12, TEXT_BRIGHT)
                }
            }
            else -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 4f).toInt(), TEXT_DIM)
                val valMaxW = (x + w - valueX - 2).toInt().coerceAtLeast(10)
                drawText(ctx, font, "§7${trimText(font, getDisplayValue(v), valMaxW)}", valueX, (y + 4f).toInt(), TEXT_DIM)
            }
        }

        if (depth > 0 && getActualValue(v) !is Boolean && !isEnumWithMultiple(getActualValue(v)) &&
            !isGroup && !isSliderValue(v) && !isColorValue(v) && !isBindValue(v)) {
            val va = getActualValue(v)
            if (va is Enum<*>) {
                val dotX2 = (x + w - 4f).toInt()
                val dotY2 = y.toInt() + 6
                val isActive = getDisplayValue(v) == va.name
                fillRect(ctx, dotX2, dotY2, dotX2 + 4, dotY2 + 4, if (isActive) ACCENT else 0x40808080.toInt())
            }
        }
    }

    private fun renderModeListForModeGroup(ctx: GuiGraphicsExtractor, font: Font, v: ModeValueGroup<*>, x: Float, curY: Float, w: Float, depth: Int, mouseX: Int, mouseY: Int): Int {
        val indent = depth * SETTING_INDENT
        val labelX = (x + 6 + indent).toInt()
        val collapsed = collapsedGroups.contains(v)

        fillRect(ctx, x, curY, x + w, curY + SETTING_H, GROUP_BG)
        drawText(ctx, font, "${if (collapsed) "▶" else "▼"} ${v.name}", labelX, (curY + 4f).toInt(), ACCENT)
        if (collapsed) {
            return 1
        }

        var yOff = curY + SETTING_H
        for (mode in v.modes) {
            val isActive = mode === v.activeMode
            val dotX = labelX + 4
            val dotY = yOff.toInt() + 6
            fillRect(ctx, dotX, dotY, dotX + 4, dotY + 4, if (isActive) ACCENT else 0x40808080.toInt())
            val textX = dotX + 6
            val maxTextW = (x + w - 8 - textX).toInt().coerceAtLeast(10)
            drawText(ctx, font, trimText(font, mode.name, maxTextW), textX, (yOff + 4f).toInt(), if (isActive) TEXT_BRIGHT else TEXT_DIM)
            yOff += SETTING_H
        }
        return 1 + v.modes.size
    }

    private fun renderModeListVertical(ctx: GuiGraphicsExtractor, font: Font, v: Value<*>, constants: List<Any>, current: Enum<*>, x: Float, curY: Float, w: Float, depth: Int, mouseX: Int, mouseY: Int, listAreaY: Float, listAreaH: Float): Int {
        val indent = depth * SETTING_INDENT
        val labelX = (x + 6 + indent).toInt()
        val nameMaxW = 50
        if (curY < listAreaY + listAreaH && curY + SETTING_H > listAreaY) {
            drawText(ctx, font, trimText(font, v.name, nameMaxW), labelX, (curY + 4f).toInt(), TEXT)
        }
        var yOff = curY + SETTING_H

        val dotSize = 4
        val dotGap = 2
        val nameX = labelX
        for (const in constants) {
            if (yOff < listAreaY + listAreaH && yOff + SETTING_H > listAreaY) {
                val displayName = const.toString()
                val isActive = displayName == current.name
                val dotX = nameX + 4
                val dotY = yOff.toInt() + 6

                fillRect(ctx, dotX, dotY, dotX + dotSize, dotY + dotSize, if (isActive) ACCENT else 0x40808080.toInt())
                val textX = dotX + dotSize + dotGap
                val maxTextW = (x + w - 8 - textX).toInt().coerceAtLeast(10)
                drawText(ctx, font, trimText(font, displayName, maxTextW), textX, (yOff + 4f).toInt(), if (isActive) TEXT_BRIGHT else TEXT_DIM)
            }
            yOff += SETTING_H
        }
        return 1 + constants.size
    }

    private fun isEnumWithMultiple(actual: Any?): Boolean {
        if (actual !is Enum<*>) {
            return false
        }
        return getEnumConstants(actual).size >= 2
    }

    private fun getEnumConstants(actual: Any?): List<Any> {
        if (actual == null) {
            return emptyList()
        }
        return try {
            (actual.javaClass.enumConstants?.toList() ?: emptyList()) as List<Any>
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val btn = event.button()
        val mx = event.x().toInt()
        val my = event.y().toInt()
        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight

        // 【修复】搜索框点击判定匹配新位置 (顶部居中, 180x18 圆角黑底)
        val sSearchW = 180f
        val sSearchH = 18f
        val sSearchX = (sc - sSearchW) / 2f
        val sSearchY = 6f
        if (mx in sSearchX.toInt()..(sSearchX + sSearchW).toInt() && my in sSearchY.toInt()..(sSearchY + sSearchH).toInt()) {
            searchFocused = true
            return true
        }
        searchFocused = false

        val colorVal = activeColorValue
        if (colorVal != null) {
            val px = colorPickerX.coerceIn(0f, sc - PALETTE_W)
            val py = colorPickerY.coerceIn(0f, sh - PALETTE_H)
            if (mx in px.toInt()..(px + PALETTE_W).toInt() && my in py.toInt()..(py + PALETTE_H).toInt()) {
                val barY = py + PALETTE_H - ALPHA_BAR_H
                if (my >= barY.toInt() && my <= (barY + ALPHA_BAR_H).toInt()) {
                    val barX = px + PALETTE_PAD
                    val barW = PALETTE_W - PALETTE_PAD * 2
                    val alpha = (((mx - barX) / barW) * 255).toInt().coerceIn(0, 255)
                    colorPickerAlpha = alpha
                    colorPickerAlphaDragging = true
                    val (r, g, b) = currentColorRgb(colorVal)
                    trySetValue(colorVal, Color4b(r, g, b, alpha))
                    return true
                }
                val col = ((mx - px - PALETTE_PAD) / (PALETTE_CELL + PALETTE_GAP)).toInt()
                val row = ((my - py - PALETTE_PAD) / (PALETTE_CELL + PALETTE_GAP)).toInt()
                val index = row * PALETTE_COLS + col
                if (index in paletteColors.indices) {
                    val picked = paletteColors[index]
                    trySetValue(colorVal, Color4b(picked.r, picked.g, picked.b, colorPickerAlpha))
                    activeColorValue = null
                }
                return true
            }
            activeColorValue = null
        }

        var targetPanel: PanelData? = null
        // 【修复】与渲染顺序一致: 折叠面板先、展开面板后(置顶); 从后往前遍历使上层面板优先接收事件
        val allPanels = currentPanels().sortedBy { it.collapsed }
        for (i in allPanels.lastIndex downTo 0) {
            val panel = allPanels[i]
            // 【修复】命中范围用实际绘制高度: 折叠时只占标题栏(26px)
            val hitH = if (panel.collapsed) (HEADER_H + 2f) else panel.h
            if (mx in panel.x.toInt()..(panel.x + panel.w).toInt() && my in panel.y.toInt()..(panel.y + hitH).toInt()) {
                targetPanel = panel
                break
            }
        }

        if (targetPanel != null) {
            val headerY = targetPanel.y
            val headerH = HEADER_H
            if (my in headerY.toInt()..(headerY + headerH).toInt()) {
                if (btn == 1) {
                    targetPanel.collapsed = !targetPanel.collapsed
                    layoutDirty = true
                    // 【修复】不再立即保存，延迟到 onClose/removed 时统一保存
                    return true
                } else if (btn == 0) {
                    targetPanel.draggingPanel = true
                    targetPanel.dragOffsetX = mx - targetPanel.x
                    targetPanel.dragOffsetY = my - targetPanel.y
                    return true
                }
            }
        }

        if (targetPanel == null) {
            return true
        }
        val panel = targetPanel!!
        if (panel.collapsed) {
            return true
        }

        val listAreaX = panel.x + PADDING
        val listAreaW = panel.w - PADDING * 2 - SCROLL_W
        val listAreaY = panel.y + 28f
        val listAreaH = panel.h - 32f

        if (mx in (listAreaX + listAreaW - 6).toInt()..(listAreaX + listAreaW + SCROLL_W + 6).toInt() && my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {
            val modules = getModulesForPanel(panel)
            val contentH = getContentHeight(modules)
            if (contentH > listAreaH) {
                val clickRatio = (my - listAreaY) / listAreaH
                val maxScroll = contentH - listAreaH
                panel.targetScroll = clickRatio * maxScroll
                panel.draggingScroll = true
                layoutDirty = true
                return true
            }
        }

        if (mx in listAreaX.toInt()..(listAreaX + listAreaW).toInt() && my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {
            val modules = getModulesForPanel(panel)
            var curY = listAreaY - panel.scrollOffset
            for (mod in modules) {
                val isExpanded = expandedModule == mod
                val modEndY = curY + ITEM_H
                if (my in curY.toInt()..modEndY.toInt()) {
                    when (btn) {
                        0 -> {
                            if (mod.name != "ClickGUI") {
                                try {
                                    mod.enabled = !mod.enabled
                                } catch (_: Exception) {
                                }
                                if (!mod.enabled && expandedModule == mod) {
                                    expandedModule = null
                                }
                            }
                        }
                        1 -> {
                            expandedModule = if (expandedModule == mod) {
                                null
                            } else {
                                mod
                            }
                        }
                    }
                    layoutDirty = true
                    return true
                }
                curY += ITEM_H
                if (isExpanded) {
                    val values = getVisibleValuesCached(mod)
                    for ((v, depth) in values) {
                        val actual = getActualValue(v)
                        if (v is ModeValueGroup<*>) {
                            val titleEndY = curY + SETTING_H
                            if (my in curY.toInt()..titleEndY.toInt()) {
                                if (btn == 0) {
                                    if (collapsedGroups.contains(v)) {
                                        collapsedGroups.remove(v)
                                    } else {
                                        collapsedGroups.add(v)
                                    }
                                    layoutDirty = true
                                }
                                return true
                            }
                            curY += SETTING_H
                            for (mode in v.modes) {
                                val settingEndY = curY + SETTING_H
                                if (my in curY.toInt()..settingEndY.toInt()) {
                                    if (btn == 0) {
                                        try {
                                            v.setByString(mode.name)
                                        } catch (_: Exception) {
                                        }
                                        valuesCache.clear()
                                        valuesCacheTime = 0L
                                    }
                                    return true
                                }
                                curY += SETTING_H
                            }
                        } else if (isEnumWithMultiple(actual)) {
                            val constants = getEnumConstants(actual)
                            curY += SETTING_H
                            for (const in constants) {
                                val settingEndY = curY + SETTING_H
                                if (my in curY.toInt()..settingEndY.toInt()) {
                                    if (btn == 0) {
                                        trySetValue(v, const)
                                    }
                                    return true
                                }
                                curY += SETTING_H
                            }
                        } else {
                            val settingEndY = curY + SETTING_H
                            if (my in curY.toInt()..settingEndY.toInt()) {
                                handleSettingClick(v, btn, mx.toFloat(), curY, listAreaW, listAreaX, panel, depth * SETTING_INDENT)
                                return true
                            }
                            curY += SETTING_H
                        }
                    }
                }
            }
        }
        return true
    }

    private fun handleSettingClick(v: Value<*>, btn: Int, mx: Float, y: Float, w: Float, x: Float, panel: PanelData, indent: Float) {
        if (btn != 0) {
            return
        }
        val actual = getActualValue(v) ?: return
        if (isGroupValue(v)) {
            if (collapsedGroups.contains(v)) {
                collapsedGroups.remove(v)
            } else {
                collapsedGroups.add(v)
            }
            layoutDirty = true
            return
        }
        if (actual is Boolean) {
            trySetValue(v, !actual)
            return
        }
        if (isBindValue(v)) {
            listeningValue = if (listeningValue == v) {
                null
            } else {
                v
            }
            return
        }
        if (isColorValue(v)) {
            if (activeColorValue == v) {
                activeColorValue = null
            } else {
                activeColorValue = v
                colorPickerAlpha = currentColorAlpha(v)
                colorPickerAlphaDragging = false
                colorPickerX = x + w - PALETTE_W - 2f
                colorPickerY = y + SETTING_H + 2f
            }
            return
        }
        if (isSliderValue(v)) {
            val font = minecraft!!.font
            val layout = computeSliderLayout(font, v, actual, x, w, indent)
            val hitStart = layout.sliderX - 6
            val hitEnd = layout.sliderX + layout.sliderW + 6
            if (mx.toInt() in hitStart..hitEnd) {
                val progress = ((mx.toInt() - layout.sliderX).toFloat() / layout.sliderW).coerceIn(0f, 1f)
                val newValue = layout.minV + (layout.maxV - layout.minV) * progress
                var rangePoint = -1
                if (layout.upperPointX != 0) {
                    val mxInt = mx.toInt()
                    val dLower = abs(mxInt - layout.lowerPointX)
                    val dUpper = abs(mxInt - layout.upperPointX)
                    rangePoint = if (dLower <= dUpper) {
                        0
                    } else {
                        1
                    }
                }
                if (rangePoint == -1) {
                    applySliderValue(v, actual, newValue, layout.rangeWidth)
                } else {
                    applyRangePoint(v, actual, newValue, rangePoint)
                }
                sliderContext = SliderContext(v, panel, layout.sliderX, y.toInt() + 8, layout.sliderW, layout.minV, layout.maxV, layout.rangeWidth, rangePoint)
            }
        }
    }

    private fun applyRangePoint(v: Value<*>, actual: Any?, newValue: Float, point: Int) {
        if (actual !is ClosedRange<*>) {
            return
        }
        val start = (actual.start as? Number)?.toFloat() ?: return
        val end = (actual.endInclusive as? Number)?.toFloat() ?: return
        val min = ((v as? RangedValue<*>)?.range?.start as? Number)?.toFloat() ?: 0f
        val max = ((v as? RangedValue<*>)?.range?.endInclusive as? Number)?.toFloat() ?: 100f
        val newStart: Float
        val newEnd: Float
        if (point == 0) {
            newStart = newValue.coerceIn(min, end)
            newEnd = end
        } else {
            newStart = start
            newEnd = newValue.coerceIn(start, max)
        }
        when (actual.start) {
            is Int -> trySetValue(v, newStart.toInt()..newEnd.toInt())
            is Long -> trySetValue(v, newStart.toLong()..newEnd.toLong())
            is Float -> trySetValue(v, newStart..newEnd)
            is Double -> trySetValue(v, newStart.toDouble()..newEnd.toDouble())
        }
    }

    private fun applySliderValue(v: Value<*>, actual: Any?, newValue: Float, rangeWidth: Float) {
        when (actual) {
            is ClosedRange<*> -> {
                val start = actual.start
                val newStart: Any = when (start) {
                    is Int -> newValue.toInt()
                    is Long -> newValue.toLong()
                    is Float -> newValue
                    is Double -> newValue.toDouble()
                    else -> return
                }
                val newRange: Any = when (start) {
                    is Int -> (newStart as Int)..(newStart + rangeWidth.toInt())
                    is Long -> (newStart as Long)..(newStart + rangeWidth.toLong())
                    is Float -> (newStart as Float)..(newStart + rangeWidth)
                    is Double -> (newStart as Double)..(newStart + rangeWidth.toDouble())
                    else -> return
                }
                trySetValue(v, newRange)
            }
            is Float -> trySetValue(v, newValue)
            is Double -> trySetValue(v, newValue.toDouble())
            is Int -> trySetValue(v, newValue.toInt())
            is Long -> trySetValue(v, newValue.toLong())
        }
    }

    private fun trySetValue(v: Value<*>, value: Any) {
        valuesCache.clear()
        valuesCacheTime = 0L
        try {
            val setMethod = v.javaClass.methods.firstOrNull {
                it.name == "set" && it.parameterCount == 1
            }
            setMethod?.invoke(v, value)
        } catch (_: Exception) {
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val mx = event.x().toFloat()
        val my = event.y().toFloat()
        if (colorPickerAlphaDragging) {
            val colorVal = activeColorValue
            if (colorVal != null) {
                val px = colorPickerX.coerceIn(0f, minecraft!!.window.guiScaledWidth - PALETTE_W)
                val py = colorPickerY.coerceIn(0f, minecraft!!.window.guiScaledHeight - PALETTE_H)
                val barX = px + PALETTE_PAD
                val barW = PALETTE_W - PALETTE_PAD * 2
                val alpha = (((mx - barX) / barW) * 255).toInt().coerceIn(0, 255)
                colorPickerAlpha = alpha
                val (r, g, b) = currentColorRgb(colorVal)
                trySetValue(colorVal, Color4b(r, g, b, alpha))
            }
            return true
        }
        val context = sliderContext
        if (context != null) {
            val progress = ((mx.toInt() - context.sliderX).toFloat() / context.sliderW).coerceIn(0f, 1f)
            val newValue = context.min + (context.max - context.min) * progress
            val actual = getActualValue(context.value)
            if (context.rangePoint == -1) {
                applySliderValue(context.value, actual, newValue, context.rangeWidth)
            } else {
                applyRangePoint(context.value, actual, newValue, context.rangePoint)
            }
            return true
        }
        for (panel in currentPanels()) {
            if (panel.draggingPanel) {
                panel.x = mx - panel.dragOffsetX
                panel.y = my - panel.dragOffsetY
                layoutDirty = true
                return true
            }
            if (panel.draggingScroll) {
                val modules = getModulesForPanel(panel)
                val contentH = getContentHeight(modules)
                val listAreaH = panel.h - 32f
                if (contentH > listAreaH) {
                    val maxScroll = contentH - listAreaH
                    panel.targetScroll = (panel.targetScroll + dy.toFloat() * 1.5f).coerceIn(0f, maxScroll)
                }
                layoutDirty = true
                return true
            }
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        for (panel in currentPanels()) {
            panel.draggingScroll = false
            panel.draggingPanel = false
        }
        sliderContext = null
        colorPickerAlphaDragging = false
        // 【修复】mouseReleased 不再保存，避免每次松手都写文件
        // 布局状态在 onClose()/removed() 关闭时统一保存一次
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        // 【修复】与渲染顺序一致: 展开面板置顶优先接收滚动事件
        val allPanels = currentPanels().sortedBy { it.collapsed }
        for (i in allPanels.lastIndex downTo 0) {
            val panel = allPanels[i]
            if (panel.collapsed) {
                continue
            }
            if (mouseX in panel.x.toDouble()..(panel.x + panel.w).toDouble() && mouseY in panel.y.toDouble()..(panel.y + panel.h).toDouble()) {
                panel.targetScroll = (panel.targetScroll - vertical.toFloat() * 22f).coerceAtLeast(0f)
                layoutDirty = true
                return true
            }
        }
        return true
    }

    private fun currentPanels(): List<PanelData> = if (searchText.isNotEmpty()) {
        listOfNotNull(searchPanel)
    } else {
        panels
    }

    private fun getModulesForPanel(panel: PanelData): List<ClientModule> {
        return if (searchText.isNotEmpty()) {
            categories.flatMap { getCategoryModules(it) }.distinct()
        } else {
            panel.category?.let { getCategoryModules(it) } ?: emptyList()
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (listeningValue != null) {
            listeningValue = null
            return true
        }
        val isEscape = event.key() == GLFW.GLFW_KEY_ESCAPE || event.scancode() == 1
        if (isEscape) {
            // 搜索框聚焦时, 第一次 ESC 只取消聚焦
            if (searchFocused) {
                searchFocused = false
                return true
            }
            // 【修复】其余情况直接走 onClose() 关闭 (配合 shouldCloseOnEsc() = true),
            // 不在这里调用 setScreen(null), 避免 ESC 事件漏给游戏触发暂停菜单。
            onClose()
            return true
        }
        if (searchFocused) {
            when (event.key()) {
                GLFW.GLFW_KEY_BACKSPACE -> {
                    if (searchText.isNotEmpty()) {
                        searchText = searchText.dropLast(1)
                    }
                    return true
                }
                GLFW.GLFW_KEY_SPACE -> {
                    if (searchText.length < 50) {
                        searchText += " "
                    }
                    return true
                }
                else -> {
                    val name = GLFW.glfwGetKeyName(event.key(), 0)
                    if (name != null && name.length == 1 && searchText.length < 50) {
                        searchText += name
                        return true
                    }
                }
            }
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (searchFocused && searchText.length < 50) {
            try {
                val cls = event.javaClass
                var codepoint = 0
                try {
                    val m = cls.getMethod("codepoint")
                    codepoint = m.invoke(event) as? Int ?: 0
                } catch (_: Exception) {
                }
                if (codepoint > 31) {
                    searchText += codepoint.toChar()
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return true
    }

    override fun onClose() {
        // 【修复】确保关闭时保存布局
        saveLayout()
        setScreenCompat(null)
        fadeAnim = 0f
        isFirstLoad = true
    }

    override fun removed() {
        // 【修复】确保移除时保存布局
        saveLayout()
    }

    private fun setScreenCompat(screen: Screen?) {
        val mc = minecraft ?: return
        try {
            mc.javaClass.getMethod("setScreen", Screen::class.java)?.invoke(mc, screen)
            return
        } catch (_: NoSuchMethodException) {
        }
        try {
            mc.javaClass.getMethod("openScreen", Screen::class.java)?.invoke(mc, screen)
            return
        } catch (_: NoSuchMethodException) {
        }
        try {
            mc.javaClass.getMethod("displayGuiScreen", Screen::class.java)?.invoke(mc, screen)
        } catch (_: Exception) {
        }
    }

    private fun getModeValue(mod: ClientModule): Value<*>? {
        modeValueCache[mod]?.let {
            return it
        }
        val topValues = try {
            mod.collectValuesRecursively()
        } catch (_: Exception) {
            return null
        }
        for (v in topValues) {
            val actual = getActualValue(v) ?: continue
            if (actual is Enum<*>) {
                val constants = try {
                    actual.javaClass.enumConstants?.toList() ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                if (constants.size >= 2) {
                    modeValueCache[mod] = v
                    return v
                }
            }
        }
        modeValueCache[mod] = null
        return null
    }

    /** 【优化】getVisibleValues 缓存: 每帧只对展开模块做一次反射, 改动设置时由 trySetValue 清空 */
    private fun getVisibleValuesCached(module: ClientModule): List<Pair<Value<*>, Int>> {
        val now = System.currentTimeMillis()
        if (now - valuesCacheTime > 250L) {
            valuesCacheTime = now
            valuesCache.clear()
        }
        return valuesCache.getOrPut(module) { getVisibleValues(module) }
    }

    private fun getVisibleValues(module: ClientModule): List<Pair<Value<*>, Int>> {
        val result = mutableListOf<Pair<Value<*>, Int>>()
        val topValues = try {
            module.collectValuesRecursively()
        } catch (_: Exception) {
            return emptyList()
        }
        val visited = IdentityHashMap<Value<*>, Boolean>()
        val modeVal = getModeValue(module)
        val currentModeName = if (modeVal != null) {
            (getActualValue(modeVal) as? Enum<*>)?.name ?: ""
        } else {
            ""
        }
        val allModeNames = if (modeVal != null && currentModeName.isNotEmpty()) {
            val actual = getActualValue(modeVal)
            val constants = try {
                actual!!.javaClass.enumConstants?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            constants.map { it.toString() }.filter { it != currentModeName }
        } else {
            emptyList()
        }
        fun isHiddenForCurrentMode(v: Value<*>): Boolean {
            if (allModeNames.isEmpty()) {
                return false
            }
            if (v == modeVal) {
                return false
            }
            val vName = v.name
            for (modeName in allModeNames) {
                if (vName.contains(modeName, ignoreCase = true) && !vName.contains(currentModeName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
        fun process(v: Value<*>, depth: Int) {
            if (visited.containsKey(v)) {
                return
            }
            if (v !is ModeValueGroup<*> && isHiddenForCurrentMode(v)) {
                visited[v] = true
                return
            }
            visited[v] = true
            result.add(Pair(v, depth))
            when {
                v is ModeValueGroup<*> -> {
                    if (!collapsedGroups.contains(v)) {
                        getGroupChildren(v.activeMode).forEach { child ->
                            if (!isHiddenForCurrentMode(child)) {
                                process(child, depth + 1)
                            }
                        }
                    }
                }
                isGroupValue(v) -> {
                    if (!collapsedGroups.contains(v)) {
                        getGroupChildren(v).forEach { child ->
                            if (!isHiddenForCurrentMode(child)) {
                                process(child, depth + 1)
                            }
                        }
                    }
                }
            }
        }
        topValues.forEach { v ->
            var isChild = false
            topValues.forEach { other ->
                if (other != v && isGroupValue(other) && getGroupChildren(other).contains(v)) {
                    isChild = true
                }
            }
            if (!isChild) {
                process(v, 0)
            }
        }
        return result
    }

    private fun isGroupValue(v: Value<*>): Boolean {
        return try {
            v.javaClass.simpleName.contains("Group", true) || v.javaClass.simpleName.contains("Container", true)
        } catch (_: Exception) {
            false
        }
    }

    private fun getGroupChildren(v: Value<*>): List<Value<*>> {
        val list = mutableListOf<Value<*>>()
        val visited = IdentityHashMap<Value<*>, Boolean>()
        try {
            for (m in v.javaClass.methods) {
                if ((m.name.equals("getValues", true) || m.name.equals("getSubValues", true)) && m.parameterCount == 0) {
                    val res = m.invoke(v)
                    if (res is Collection<*>) {
                        for (child in res) {
                            if (child is Value<*> && !visited.containsKey(child)) {
                                visited[child] = true
                                list.add(child)
                            }
                        }
                    }
                }
            }
            for (f in v.javaClass.declaredFields) {
                f.isAccessible = true
                val valObj = f.get(v)
                if (valObj is Value<*> && !visited.containsKey(valObj)) {
                    visited[valObj] = true
                    list.add(valObj)
                } else if (valObj is Collection<*>) {
                    for (child in valObj) {
                        if (child is Value<*> && !visited.containsKey(child)) {
                            visited[child] = true
                            list.add(child)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return list.distinct()
    }

    private fun getActualValue(v: Value<*>): Any? {
        var obj: Any? = try {
            v.get()
        } catch (_: Exception) {
            null
        }
        var depth = 0
        while (obj is Value<*> && depth < 5) {
            obj = try {
                obj.get()
            } catch (_: Exception) {
                null
            }
            depth++
        }
        return obj
    }

    private fun isBindValue(v: Value<*>): Boolean {
        val name = v.name.lowercase()
        if (name.contains("key") || name.contains("bind")) {
            return true
        }
        val actual = getActualValue(v) ?: return false
        return actual.javaClass.simpleName.lowercase().contains("key") || actual.javaClass.simpleName.lowercase().contains("bind")
    }

    private fun isSliderValue(v: Value<*>): Boolean {
        val actual = getActualValue(v) ?: return false
        return actual is Number || actual is ClosedRange<*> || v is RangedValue<*>
    }

    private fun isColorValue(v: Value<*>): Boolean {
        val actual = getActualValue(v) ?: return false
        return actual is Color || actual.javaClass.simpleName.contains("Color", true)
    }

    private fun extractColor(v: Value<*>): Color {
        val actual = getActualValue(v) ?: return Color.WHITE
        if (actual is Color) {
            return actual
        }
        if (actual is Color4b) {
            return Color(actual.argb, true)
        }
        if (actual is Number) {
            return Color(actual.toInt(), true)
        }
        return Color.WHITE
    }

    private fun currentColorAlpha(v: Value<*>): Int {
        val actual = getActualValue(v)
        if (actual is Color4b) {
            return actual.a
        }
        return 255
    }

    private fun currentColorRgb(v: Value<*>): Triple<Int, Int, Int> {
        val actual = getActualValue(v)
        if (actual is Color4b) {
            return Triple(actual.r, actual.g, actual.b)
        }
        val c = extractColor(v)
        return Triple(c.red, c.green, c.blue)
    }

    private fun formatBindValue(v: Value<*>): String {
        val actual = getActualValue(v) ?: return "NONE"
        try {
            val keyField = actual.javaClass.declaredFields.find {
                it.name.equals("boundKey", true) || it.name.equals("key", true)
            }
            if (keyField != null) {
                keyField.isAccessible = true
                val key = keyField.get(actual)
                if (key != null) {
                    return key.toString().replace("key.keyboard.", "").uppercase()
                }
            }
        } catch (_: Exception) {
        }
        return actual.toString().replace("key.keyboard.", "").take(10).uppercase()
    }

    private fun getDisplayValue(v: Value<*>): String {
        val actual = getActualValue(v) ?: return "NONE"
        if (actual is Enum<*>) {
            return actual.name
        }
        if (actual is Boolean) {
            return if (actual) {
                "ON"
            } else {
                "OFF"
            }
        }
        return actual.toString().take(15)
    }

    private fun getLayoutFile(): File {
        return try {
            val root = net.ccbluex.liquidbounce.config.ConfigSystem.rootFolder
            File(root, "clickgui_panels.json")
        } catch (_: Exception) {
            File(minecraft?.gameDirectory ?: File("."), "config/liquidbounce_clickgui_panels.json")
        }
    }

    private data class PanelState(val x: Int, val y: Int, val collapsed: Boolean, val scroll: Float)
    private data class LayoutState(val panels: Map<String, PanelState>, val expandedModule: String?, val collapsedGroups: List<String>)

    private fun saveLayout() {
        try {
            layoutLastSave = System.currentTimeMillis()
            // 如果处于搜索状态，不保存布局以免覆盖分类面板数据
            if (searchText.isNotEmpty()) {
                return
            }
            if (panels.isEmpty()) {
                return
            }

            // 【修复】确保使用最新面板数据
            val panelsJson = panels.filter { it.category != null }.joinToString(",") { p ->
                val tag = p.category?.tag ?: ""
                val safeScroll = p.scrollOffset.takeIf { it.isFinite() }?.coerceIn(0f, 100000f) ?: 0f
                val formattedScroll = String.format(java.util.Locale.US, "%.2f", safeScroll)
                """{"tag":"$tag","x":${p.x.toInt()},"y":${p.y.toInt()},"collapsed":${p.collapsed},"scroll":$formattedScroll}"""
            }

            val expandedName = expandedModule?.name ?: ""
            val groups = mutableListOf<String>()
            try {
                for (mod in ModuleManager.getModules()) {
                    for ((v, _) in getVisibleValues(mod)) {
                        if (collapsedGroups.contains(v)) {
                            groups += "${mod.name}:${v.name}"
                        }
                    }
                }
            } catch (_: Exception) {
            }
            val groupsJson = groups.joinToString(",") { "\"$it\"" }
            val sb = StringBuilder()
            sb.append("{\"panels\":[").append(panelsJson).append("],")
            sb.append("\"expanded\":\"").append(expandedName).append("\",")
            sb.append("\"groups\":[").append(groupsJson).append("]}")
            val file = getLayoutFile()
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            file.writeText(sb.toString())
            layoutDirty = false
            println("[ClickGui] saveLayout OK -> ${file.absolutePath}")
        } catch (e: Exception) {
            println("[ClickGui] saveLayout failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun loadLayout(): LayoutState {
        return try {
            val file = getLayoutFile()
            if (!file.exists()) {
                println("[ClickGui] loadLayout: file not found -> ${file.absolutePath}")
                return LayoutState(emptyMap(), null, emptyList())
            }
            val content = file.readText()
            println("[ClickGui] loadLayout: file found, size=${content.length}")
            val panels = mutableMapOf<String, PanelState>()
            val panelRegex = Regex("""\{"tag"\s*:\s*"([^"]+)"\s*,\s*"x"\s*:\s*(-?[0-9]+)\s*,\s*"y"\s*:\s*(-?[0-9]+)\s*,\s*"collapsed"\s*:\s*(true|false)(?:\s*,\s*"scroll"\s*:\s*(-?[0-9.,]+))?\}""")
            for (match in panelRegex.findAll(content)) {
                val tag = match.groupValues[1]
                val x = match.groupValues[2].toInt()
                val y = match.groupValues[3].toInt()
                val collapsed = match.groupValues[4].toBoolean()
                val scrollStr = match.groupValues[5].replace(",", ".")
                val scroll = scrollStr.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: 0f
                panels[tag] = PanelState(x, y, collapsed, scroll)
            }
            println("[ClickGui] loadLayout: parsed ${panels.size} panels -> ${panels.keys}")
            val expanded = Regex(""""expanded":"([^"]*)"""").find(content)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            val groups = mutableListOf<String>()
            Regex(""""groups":\[(.*?)\]""").find(content)?.groupValues?.get(1)?.let { gs ->
                Regex(""""([^"]+)"""").findAll(gs).forEach {
                    groups.add(it.groupValues[1])
                }
            }
            LayoutState(panels, expanded, groups)
        } catch (e: Exception) {
            println("[ClickGui] loadLayout failed: ${e.message}")
            e.printStackTrace()
            LayoutState(emptyMap(), null, emptyList())
        }
    }
}
