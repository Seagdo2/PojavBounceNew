# Native ClickGUI (Kotlin port of the nextgen web theme)

This replaces the Ultralight/Svelte `ClickGui.svelte` overlay with a screen
rendered directly through `GuiGraphics`, built from an actual analysis of
your uploaded `LiquidBounce-nextgen.zip` source — not a from-memory guess
at what LiquidBounce "usually" looks like.

## Fixes from the previous revision (bug reports)

- **Garbled characters in some labels (e.g. a stray `{`)**: the previous
  revision embedded the literal `§` (Minecraft formatting character) directly
  in a few Kotlin string literals. That's valid UTF-8 in the files as shipped,
  but if your toolchain reads source files with a non-UTF-8 default charset
  (common on Chinese Windows setups unless the Gradle/Kotlin compiler is
  explicitly told to use UTF-8), those bytes get misdecoded into garbage -
  landing right where `§7`/`§8` was glued onto a setting's name, which
  matches "name shows extra characters" exactly. Fixed by replacing every
  literal `§` with the `\u00A7` escape sequence (pure ASCII in the source,
  so no charset can mangle it). The entire codebase is now 7-bit ASCII only
  - verified with a byte-level scan, not just the known spots.
- **Right-click sometimes not expanding a module's settings**: found a real
  bug causing this. `MultiChooseRow` (the wrapping chip row) only computed
  its own real height *inside* `render()`, but `NativePanel`/`NativeModuleRow`
  compute total heights for hit-testing *before* every row has necessarily
  been rendered yet. A module with a multi-choose setting could report the
  wrong height to the click router, which throws off the position of every
  row *and module* stacked below it - so a right-click could land on the
  wrong row/module entirely. Fixed by changing `SettingRow.height` from a
  lazy property into `fun height(width: Int)`, so the exact same calculation
  is used for both hit-testing and drawing, in whichever order they're
  called. If this still misbehaves after updating, it'd help to know the
  exact module name you tested on.
- **Scroll wheel not smooth/fast enough**: a real bug here too - scroll was
  truncating straight to `Int` per event (`(amount * 16).toInt()`). Precision
  mice/trackpads can deliver `amount` as a fraction under 1.0 per event, and
  at that scale anything below ~0.06 was silently dropped *every time* -
  which reads exactly as sluggish/unresponsive scrolling. Fixed by
  accumulating scroll in float space (nothing gets dropped) and easing the
  visible position toward that target each frame for a smooth feel instead
  of an instant jump, with a faster per-notch distance (42px, up from 16px).
- **Panel height not adjustable**: added - drag the small grip in a panel's
  bottom-right corner to resize how much of its module list is visible
  before it scrolls (`NativePanel.maxBodyHeight`, clamped 60-900px). The web
  source has no equivalent control (its max-height is a fixed 545px CSS
  value), so this is a native-only addition rather than a port of something.
- **Color picker not customizable enough**: replaced the old click-to-cycle-
  hue stand-in with an actual modern panel-style picker: a hue bar, a
  saturation/value box, and an alpha bar, all click-and-drag, opening as a
  popover under the setting row. See "How the SV box is drawn" below for
  how this stays within the project's real rendering API (no gradient
  primitive exists there, so it's built from layered solid-color strips).

### How the SV (saturation/value) box is drawn

`render/Render2D.kt` has no gradient-fill primitive, and inventing a shader
for this would break the "only real, already-proven APIs" rule the rest of
this project follows. Instead the SV box uses the standard two-layer trick:
a horizontal gradient from white to the pure hue (left to right) with a
vertical gradient from transparent to black layered on top (top to bottom),
each approximated with ~24-40 solid-color strips and left to alpha-blend
together - mathematically the same result as a real 2D gradient, at a cost
of a few dozen cheap `GuiGraphics.fill` calls, only while that one popover
is open.

## Pseudo-glass theme (new)

`ModuleClickGui.GlassMode` (boolean, off by default) switches panels and the
search bar to a frosted-glass look. Like the color picker's SV box, this is
built from the project's real primitives rather than inventing a shader -
there's no backdrop-blur pass available, so "glass" is faked the way a lot
of software fakes it without one:

1. **Much lower background alpha** (roughly half the normal panel opacity),
   so noticeably more of the game world shows through.
2. **A tiled grain texture** (`GuiRender2D.frostOverlay`, backed by
   `assets/.../texture-glass-noise.png`, a small static-noise PNG) laid over
   the translucent background in one draw call via a repeat-wrapping
   sampler - this is what reads as "frosted" rather than just "see-through
   window", and it's drawn before any text/icons so only the backdrop looks
   grainy, never the content.
3. **A thin, bright, translucent edge outline** around the whole panel -
   the classic "light catching a glass edge" highlight.

Applied to panel headers/bodies and the search bar; nested module-settings
backgrounds just get a lower alpha (no separate grain pass there) to keep
the look from getting busy on every expanded row.

**Package**: `net.ccbluex.liquidbounce.render.clickgui` for the rendering
code (widgets, settings rows, palette, icons) — sibling of the project's
real `render.gui` / `render.engine` / `render.utils`. This is deliberately
*not* the same package as `ModuleClickGui` itself: that real module lives
at `net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui`
alongside the project's other `ClientModule` implementations (ModuleChams,
crosshair modes, etc.) - it's a toggleable module with its own keybind and
settings, a different kind of class than a Screen or a row widget, so it
stays where every other module lives rather than moving into a rendering
package. This revision ships a **modified copy of that real file**
(included below) so the two are properly connected instead of just
described as connectable.

## Revision: now built on the project's own real 2D GUI render API

The first version of this drew rounded rectangles by hand (a scanline fill
over `GuiGraphics.fill`), because it wasn't yet clear this codebase already
ships a proper one. It does — and this version uses it instead:

- `net.ccbluex.liquidbounce.render.Render2D.kt` — `GuiGraphicsExtractor.drawRoundedRect/drawQuad/drawCircle/drawTexQuad/drawHorizontalLine/...`
- `net.ccbluex.liquidbounce.render.gui.element.*` — the `GuiElementRenderState` records those functions build (`RoundedRectGuiElementRenderState`, `CircleGuiElementRenderState`, ...)
- `resources/liquidbounce/shaders/gui/rounded_rect.{vsh,fsh}` and `shaders/circle/*` — the actual GPU shaders, real per-pixel signed-distance-field antialiasing, not an approximation
- `GuiGraphics` implements `GuiGraphicsExtractor` (confirmed from real call sites already in your source, e.g. `NametagEnchantmentRenderer.kt` and `ItemStackListRenderer.kt` calling `guiGraphics.drawRoundedRect(...)` / `.drawQuad(...)` directly)

So `GuiRender2D.kt` in this package is now a thin, clickgui-flavoured
convenience layer over that real API — no custom shader/vertex code, no
scanline math, nothing invented that the project doesn't already have.
Rounded corners, the switch thumb, and the slider handle all get true GPU
antialiasing from the same shader every other native LiquidBounce UI
element uses (nametag labels, item-stack lists, etc.), not a hand-rolled
stand-in.

## What was analyzed to build this

- `src-theme/src/routes/clickgui/{ClickGui,Panel,Module,Search}.svelte` — layout, sizes, interaction model
- `src-theme/src/colors.scss` — every `--clickgui-*` color, `color-mix()` arithmetic resolved by hand into exact ARGB ints (see `ClickGuiPalette.kt`'s header comment for the formulas)
- `src-theme/src/routes/clickgui/setting/**` (`GenericSetting.svelte` and friends) — the setting-type dispatch that `SettingRenderer` mirrors
- `src-theme/public/img/clickgui/*.svg` — the actual category/UI icons, converted to PNG (see below), not redrawn
- `render/Render2D.kt`, `render/gui/element/*`, `render/gui/GuiCircleLutAtlas.kt` — the real drawing primitives this revision uses
- `config/types/{Value,RangedValue}.kt`, `config/types/group/ValueGroup.kt`, `config/types/list/{ChoiceListValue,MultiChoiceListValue}.kt`, `features/module/{ClientModule,ModuleManager,ModuleCategory}.kt`, `render/engine/type/Color4b.kt` — the real API this UI has to call

## Icons: rasterized from the real SVGs, not reinvented

`svg_raster.py` is a small, dependency-free SVG-path-to-PNG rasterizer I
wrote because this sandbox has no working `rsvg-convert`/`cairosvg`
offline. It parses the actual path/circle data from
`public/img/clickgui/*.svg` (including the `transform` attributes a few
of them use, and the stroke-only paths like `icon-cross.svg`), flattens
beziers/arcs, and fills with a supersampled nonzero/even-odd scanline
rasterizer. Output is 16 flat white 64×64 alpha masks under
`assets/liquidbounce/textures/clickgui/`, tinted at draw time
(`GuiRender2D.icon`, via the real `drawTexQuad`) the same way the web
theme tints them via CSS `fill: var(--...)`. If the theme's icons ever
change, rerun:

```
python3 svg_raster.py <path-to>/src-theme/public/img/clickgui assets/liquidbounce/textures/clickgui 64
```

## What's fully implemented

- Panel: 250px wide, 5px radius, accent header border, drag-to-move,
  right-click (or the chevron) to collapse — matches `Panel.svelte` — plus
  a native-only resize grip (bottom-right corner) to adjust how tall its
  module list gets before scrolling.
- Module row: left-click toggles, right-click (or the 40px chevron
  hit-zone) expands settings — matches `Module.svelte` exactly, including
  the arrow's `stopPropagation` so it doesn't also toggle the module.
- Search: centered 600px pill, filters by name, click a result to toggle it.
- Settings: Boolean (switch, real circular thumb), Float/Int + their
  `_RANGE` variants (slider, single or dual-handle, real circular handle),
  Choose (cycle control), MultiChoose (wrapping chips), Text, Color
  (hue/SV/alpha popover, see below), Bind/Key (click to arm, next input
  captured), and recursive nested groups (mode selectors /
  sub-configurables), indented, exactly like `ConfigurableSetting.svelte`.
- Text never overflows: every label and value goes through
  `GuiRender2D.ellipsize`, which binary-searches for the longest prefix
  that fits before appending `...` — this was one of your explicit
  requirements and it's enforced in one shared place, not per-widget.
- Rounded corners, circles and shadows all go through the project's real
  GPU shader path (see "Revision" above) — genuine antialiasing, not a
  stair-stepped or fringed approximation.

## Known gaps (being upfront, not guessing past them)

- **Color picker** now has a real hue/SV/alpha popover (see the changelog
  above) but still no hex *text entry* - you set color by dragging, not by
  typing a hex code. Adding a hex field is a small, separate addition on
  top of `ColorRow` if wanted.
- **Curve / Vector3 / File-picker / Registry-list** setting types render
  as a plain "(unsupported type)" label (`UnsupportedRow`) rather than a
  bespoke editor — same philosophy as the web's own fallback branch:
  admit it plainly instead of rendering something wrong.
- **Panel positions are session-only** (kept in the `NativePanel` list
  for the life of the `Screen`). Wiring them into `ConfigSystem` for
  persistence across games is a small, separate addition.
- **Description tooltips** (`Module.svelte`'s hover popover) aren't
  wired up — I didn't confirm the exact property name LiquidBounce
  uses for a module's description text in this snapshot, and guessing
  a wrong property name is worse than leaving a clearly-labeled gap.
- **Drop shadow** is a handful of layered translucent rounded rects
  (each with a real antialiased edge now), not a true gaussian blur —
  there's no offscreen blur pass wired up here. Reads correctly at
  normal UI scale.
- Text still goes through vanilla `GuiGraphics`/`Minecraft.getInstance().font`
  rather than the project's custom SDF font renderer under
  `render/engine/font/` (the one `ModuleNametags.fontRenderer` uses) —
  that renderer looked built for in-world/HUD text with its own DSL
  (`.draw(text) { x = ...; scale = ... }`), and wiring a screen-space
  ClickGUI into it is a further, separate integration than swapping the
  rect-drawing primitives was.
- The web canvas has a "2× virtual resolution" scaling quirk specific to
  the embedded-browser overlay; this port just uses Minecraft's own GUI
  coordinate space 1:1 (its own accounting for the player's GUI Scale
  setting already does the equivalent job), so pixel sizes are taken as
  literal GUI px, not re-derived through that browser-specific math.

## No more open assumptions

The previous revision flagged `ClientModule.enabled` as an unverified
assumption. It's now confirmed directly from `ToggleableValueGroup.kt`:
`override var enabled by enabledValue` - a real, public, mutable property,
exactly as `NativeModuleRow.kt` uses it. Every other primitive used here
(`drawRoundedRect`, `drawQuad`, `drawCircle`, `drawTexQuad`, `.textureSetup`,
`ToggleableValueGroup.enabled`, `ModuleClickGui.scale`/`.Snapping`) was
read directly from your source before use, not assumed.

## Wiring it in

This is now done for you, not left as an instruction: drop in
`features/module/modules/render/ModuleClickGui.kt` from this zip to
replace your existing copy. Its `onEnabled()` now does

```kotlin
mc.execute { mc.gui.setScreen(NativeClickGuiScreen()) }
```

(matching the exact `mc.gui.setScreen(...)` call your source already used
for the browser screen - not `Minecraft.getInstance().setScreen(...)`,
which was a slip in this doc's previous revision) instead of opening
`CustomSharedMinecraftScreen`/`CustomStandaloneMinecraftScreen`. See the
comment block at the top of that file for the full, itemized diff against
your original - including what was deliberately removed (the browser
lifecycle/caching machinery: `Cache`, `standaloneScreen`, `sync()`,
`invalidate()`, and the handlers that kept a persistent embedded browser
in sync) and why: a plain `Screen` has nothing like that to manage.

The real module's own settings now actually drive this screen instead of
sitting unused:
- **Scale** (0.5-2, already existed) is applied as a pose scale over the
  whole screen, with mouse coordinates divided back down so hit-testing
  still lines up.
- **SearchBarAutoFocus** (already existed) focuses the search pill on open.
- **Snapping** → **Enabled**/**GridSize** (already existed) snaps a
  panel's position to the grid on drag-release.

None of these are new settings I invented - they were already declared on
your `ModuleClickGui` and previously only affected the web GUI.

## File map

```
src/net/ccbluex/liquidbounce/
├── features/module/modules/render/
│   └── ModuleClickGui.kt        MODIFIED real module: opens NativeClickGuiScreen,
│                                exposes Scale/SearchBarAutoFocus/Snapping publicly
└── render/clickgui/
    ├── ClickGuiPalette.kt       resolved colors.scss constants
    ├── ClickGuiIcons.kt         texture identifiers for the rasterized icons
    ├── NativeClickGuiScreen.kt  top-level Screen (panels + search bar)
    ├── GuiRender2D.kt           thin convenience layer over the real render/Render2D.kt API
    ├── widget/NativePanel.kt    draggable/collapsible category panel
    ├── widget/NativeModuleRow.kt module row + expand/collapse
    ├── widget/NativeSearchBar.kt search pill + results
    └── settings/SettingRows.kt  one row type per ValueType + the dispatcher
assets/liquidbounce/textures/clickgui/*.png   rasterized icons
svg_raster.py                                 the rasterizer, for regenerating them
```
