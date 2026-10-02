/*
 * This file is part of LiquidBounce (https://github.com/LiquidBounce)
 *
 * Persists the native ClickGUI's session-only state across games: each
 * category panel's position, its collapsed/scroll state, and which
 * modules' setting rows were left expanded. The module's own settings
 * (Scale, PanelHeight, GlassMode, Snapping) are persisted by ConfigSystem
 * already; this only covers the layout bits that previously reset on
 * every reopen (the README listed "Panel positions are session-only" as a
 * known gap).
 *
 * Storage is a tiny JSON file in the client config folder, written via the
 * same temp-file-then-rename dance ConfigSystem.store uses, so a crash
 * mid-write cannot truncate the existing layout. Both load and save are
 * fully wrapped - a malformed or unreadable file degrades to "no saved
 * layout" (fresh defaults) rather than throwing.
 */
package net.ccbluex.liquidbounce.render.clickgui

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.gson.fileGson
import net.ccbluex.liquidbounce.utils.client.clientLogger
import java.io.File

/**
 * Snapshot of one category panel's restorable state. [expanded] maps
 * module name -> expanded, holding only the `true` entries (absent is
 * treated as collapsed) so the file stays small.
 */
data class PanelSnapshot(
    val x: Int,
    val y: Int,
    val collapsed: Boolean,
    val scroll: Float,
    val expanded: Map<String, Boolean>,
)

object ClickGuiLayoutStore {

    private val logger = clientLogger("ClickGuiLayout")

    private val file: File get() = File(ConfigSystem.rootFolder, "clickgui-layout.json")
    private val tmpFile: File get() = File(ConfigSystem.rootFolder, "clickgui-layout.json.tmp")

    /**
     * Reads the saved layout keyed by category tag. Returns an empty map if
     * the file does not exist or cannot be parsed, so callers can treat
     * "no layout" and "broken layout" identically (just use fresh defaults).
     */
    fun load(): Map<String, PanelSnapshot> {
        return file.runCatching {
            if (!exists()) return emptyMap()
            bufferedReader().use { reader ->
                val root = fileGson.fromJson(reader, JsonObject::class.java) ?: return emptyMap()
                val panels = root.getAsJsonObject("panels") ?: return emptyMap()
                val out = HashMap<String, PanelSnapshot>(panels.size())
                for ((tag, entry) in panels.entrySet()) {
                    val p = entry.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val x = p.get("x")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                    val y = p.get("y")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    val collapsed = p.get("collapsed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    val scroll = p.get("scroll")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
                    val expanded = HashMap<String, Boolean>()
                    p.getAsJsonObject("expanded")?.entrySet()?.forEach { (name, v) ->
                        if (v.isJsonPrimitive && v.asBoolean) expanded[name] = true
                    }
                    out[tag] = PanelSnapshot(x, y, collapsed, scroll, expanded)
                }
                out
            }
        }.onFailure {
            logger.error("Unable to load clickgui layout", it)
        }.getOrDefault(emptyMap())
    }

    /**
     * Writes [layout] to disk atomically (temp file -> rename). Any failure
     * is logged and swallowed - failing to save the layout must never crash
     * the game, the layout is convenience data.
     */
    fun save(layout: Map<String, PanelSnapshot>) {
        val panels = JsonObject()
        for ((tag, p) in layout) {
            val po = JsonObject()
            po.addProperty("x", p.x)
            po.addProperty("y", p.y)
            po.addProperty("collapsed", p.collapsed)
            po.addProperty("scroll", p.scroll)
            val exp = JsonObject()
            for ((name, on) in p.expanded) {
                if (on) exp.addProperty(name, true)
            }
            po.add("expanded", exp)
            panels.add(tag, po)
        }
        val root = JsonObject()
        root.add("panels", panels)

        tmpFile.runCatching {
            if (!exists()) {
                createNewFile()
            }
            bufferedWriter().use { writer ->
                fileGson.toJson(root, writer)
            }
            if (file.exists() && !file.delete()) {
                error("Unable to delete old clickgui layout file")
            }
            if (!renameTo(file)) {
                error("Unable to rename clickgui layout file")
            }
            logger.info("Saved clickgui layout (${layout.size} panels).")
        }.onFailure {
            logger.error("Unable to save clickgui layout", it)
        }
    }
}
