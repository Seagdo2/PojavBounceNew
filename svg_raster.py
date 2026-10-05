#!/usr/bin/env python3
"""
Minimal, dependency-free SVG path rasterizer.

Only implements what LiquidBounce's clickgui icon set actually uses:
 - <path d="..."> with M/L/H/V/C/S/Q/T/A/Z (upper+lower, all found in this icon set)
 - <circle cx cy r>
 - <rect x y width height rx? ry?>
 - fill-rule: nonzero (default) and evenodd
 - a single flat fill color per shape (all these icons are flat white glyphs)

Rendering: flatten every path/shape to polygons (beziers + arcs -> line
segments via adaptive subdivision), then rasterize with a scanline
fill (nonzero winding OR even-odd, per fill-rule) at N x supersampling,
box-downsampled to the target size for smooth, alias-free edges.
"""
import re
import struct
import zlib
import math
import xml.etree.ElementTree as ET

SUPERSAMPLE = 4

# ---------------------------------------------------------------- path data

_TOKEN_RE = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.\d+(?:[eE][-+]?\d+)?|-?\d+(?:[eE][-+]?\d+)?")


def _tokenize(d):
    return _TOKEN_RE.findall(d)


def _read_floats(tokens, i, count):
    vals = []
    for _ in range(count):
        vals.append(float(tokens[i]))
        i += 1
    return vals, i


def _read_arc_flag(tokens, i):
    # Arc flags are single 0/1 digits and the tokenizer may have merged
    # "01" style runs into one numeric token (e.g. "0" then "1" usually
    # still separate tokens because the regex is greedy per-number, but
    # flags can be glued to the following coordinate like "1.5"). Handle
    # the common "0"/"1" case; if it's longer, peel the first char off.
    tok = tokens[i]
    if tok in ("0", "1"):
        return int(tok), i + 1
    # Glued digit + following number, e.g. "10.5" meaning flag=1, then 0.5
    flag = int(tok[0])
    rest = tok[1:]
    tokens[i] = rest
    return flag, i


def flatten_cubic(p0, p1, p2, p3, out, depth=0):
    # adaptive flatness test
    if depth >= 16 or _flat_enough(p0, p1, p2, p3):
        out.append(p3)
        return
    p01 = _mid(p0, p1)
    p12 = _mid(p1, p2)
    p23 = _mid(p2, p3)
    p012 = _mid(p01, p12)
    p123 = _mid(p12, p23)
    p0123 = _mid(p012, p123)
    flatten_cubic(p0, p01, p012, p0123, out, depth + 1)
    flatten_cubic(p0123, p123, p23, p3, out, depth + 1)


def _mid(a, b):
    return ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)


def _flat_enough(p0, p1, p2, p3, tol=0.06):
    def dist_point_line(p, a, b):
        (x, y), (ax, ay), (bx, by) = p, a, b
        dx, dy = bx - ax, by - ay
        length_sq = dx * dx + dy * dy
        if length_sq < 1e-9:
            return math.hypot(x - ax, y - ay)
        t = ((x - ax) * dx + (y - ay) * dy) / length_sq
        px, py = ax + t * dx, ay + t * dy
        return math.hypot(x - px, y - py)

    return dist_point_line(p1, p0, p3) < tol and dist_point_line(p2, p0, p3) < tol


def flatten_quadratic(p0, p1, p2, out):
    # promote to cubic
    c1 = (p0[0] + 2 / 3 * (p1[0] - p0[0]), p0[1] + 2 / 3 * (p1[1] - p0[1]))
    c2 = (p2[0] + 2 / 3 * (p1[0] - p2[0]), p2[1] + 2 / 3 * (p1[1] - p2[1]))
    flatten_cubic(p0, c1, c2, p2, out)


def flatten_arc(p0, rx, ry, x_rot_deg, large_arc, sweep, p1, out):
    if rx == 0 or ry == 0:
        out.append(p1)
        return
    phi = math.radians(x_rot_deg)
    cos_phi, sin_phi = math.cos(phi), math.sin(phi)
    dx2, dy2 = (p0[0] - p1[0]) / 2.0, (p0[1] - p1[1]) / 2.0
    x1p = cos_phi * dx2 + sin_phi * dy2
    y1p = -sin_phi * dx2 + cos_phi * dy2

    rx, ry = abs(rx), abs(ry)
    lam = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s

    sign = -1 if large_arc == sweep else 1
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = sign * math.sqrt(max(0.0, num / den)) if den > 1e-9 else 0.0
    cxp = co * (rx * y1p / ry)
    cyp = co * (-ry * x1p / rx)

    cx = cos_phi * cxp - sin_phi * cyp + (p0[0] + p1[0]) / 2.0
    cy = sin_phi * cxp + cos_phi * cyp + (p0[1] + p1[1]) / 2.0

    def ang(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        length = math.hypot(ux, uy) * math.hypot(vx, vy)
        a = math.acos(max(-1.0, min(1.0, dot / length))) if length > 1e-9 else 0.0
        return a if ux * vy - uy * vx >= 0 else -a

    theta1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dtheta = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dtheta > 0:
        dtheta -= 2 * math.pi
    elif sweep and dtheta < 0:
        dtheta += 2 * math.pi

    steps = max(4, int(abs(dtheta) / 0.12))
    for i in range(1, steps + 1):
        t = theta1 + dtheta * i / steps
        x = cx + rx * math.cos(t) * cos_phi - ry * math.sin(t) * sin_phi
        y = cy + rx * math.cos(t) * sin_phi + ry * math.sin(t) * cos_phi
        out.append((x, y))


def parse_path(d):
    """Returns a list of polygons (each a list of (x, y) tuples)."""
    tokens = _tokenize(d)
    i = 0
    polys = []
    cur = []
    cx = cy = 0.0
    start = (0.0, 0.0)
    last_cmd = None
    prev_ctrl = None  # for S/T reflection

    def close():
        nonlocal cur
        if len(cur) > 1:
            polys.append(cur)
        cur = []

    while i < len(tokens):
        tok = tokens[i]
        if tok.isalpha():
            cmd = tok
            i += 1
        else:
            cmd = last_cmd
            # implicit repeat: M repeats as L
            if cmd in ("M",):
                cmd = "L"
            elif cmd in ("m",):
                cmd = "l"

        if cmd in ("M", "m"):
            (x, y), i = _read_floats(tokens, i, 2)
            if cmd == "m" and last_cmd is not None:
                x, y = cx + x, cy + y
            close()
            cx, cy = x, y
            start = (x, y)
            cur = [(x, y)]
        elif cmd in ("L", "l"):
            (x, y), i = _read_floats(tokens, i, 2)
            if cmd == "l":
                x, y = cx + x, cy + y
            cx, cy = x, y
            cur.append((x, y))
        elif cmd in ("H", "h"):
            (x,), i = _read_floats(tokens, i, 1)
            x = x if cmd == "H" else cx + x
            cx = x
            cur.append((x, cy))
        elif cmd in ("V", "v"):
            (y,), i = _read_floats(tokens, i, 1)
            y = y if cmd == "V" else cy + y
            cy = y
            cur.append((cx, y))
        elif cmd in ("C", "c"):
            (x1, y1, x2, y2, x, y), i = _read_floats(tokens, i, 6)
            if cmd == "c":
                x1, y1, x2, y2, x, y = cx + x1, cy + y1, cx + x2, cy + y2, cx + x, cy + y
            pts = []
            flatten_cubic((cx, cy), (x1, y1), (x2, y2), (x, y), pts)
            cur.extend(pts)
            prev_ctrl = (x2, y2)
            cx, cy = x, y
        elif cmd in ("S", "s"):
            (x2, y2, x, y), i = _read_floats(tokens, i, 4)
            if cmd == "s":
                x2, y2, x, y = cx + x2, cy + y2, cx + x, cy + y
            if last_cmd in ("C", "c", "S", "s") and prev_ctrl:
                x1, y1 = 2 * cx - prev_ctrl[0], 2 * cy - prev_ctrl[1]
            else:
                x1, y1 = cx, cy
            pts = []
            flatten_cubic((cx, cy), (x1, y1), (x2, y2), (x, y), pts)
            cur.extend(pts)
            prev_ctrl = (x2, y2)
            cx, cy = x, y
        elif cmd in ("Q", "q"):
            (x1, y1, x, y), i = _read_floats(tokens, i, 4)
            if cmd == "q":
                x1, y1, x, y = cx + x1, cy + y1, cx + x, cy + y
            pts = []
            flatten_quadratic((cx, cy), (x1, y1), (x, y), pts)
            cur.extend(pts)
            prev_ctrl = (x1, y1)
            cx, cy = x, y
        elif cmd in ("T", "t"):
            (x, y), i = _read_floats(tokens, i, 2)
            if cmd == "t":
                x, y = cx + x, cy + y
            if last_cmd in ("Q", "q", "T", "t") and prev_ctrl:
                x1, y1 = 2 * cx - prev_ctrl[0], 2 * cy - prev_ctrl[1]
            else:
                x1, y1 = cx, cy
            pts = []
            flatten_quadratic((cx, cy), (x1, y1), (x, y), pts)
            cur.extend(pts)
            prev_ctrl = (x1, y1)
            cx, cy = x, y
        elif cmd in ("A", "a"):
            (rx, ry, rot), i = _read_floats(tokens, i, 3)
            large_arc, i = _read_arc_flag(tokens, i)
            sweep, i = _read_arc_flag(tokens, i)
            (x, y), i = _read_floats(tokens, i, 2)
            if cmd == "a":
                x, y = cx + x, cy + y
            pts = []
            flatten_arc((cx, cy), rx, ry, rot, large_arc, sweep, (x, y), pts)
            cur.extend(pts)
            cx, cy = x, y
        elif cmd in ("Z", "z"):
            if cur:
                cur.append(start)
            close()
            cx, cy = start
        else:
            raise ValueError(f"Unsupported path command '{cmd}'")

        last_cmd = cmd

    close()
    return polys


def circle_polygon(cx, cy, r, segments=48):
    return [(cx + r * math.cos(2 * math.pi * k / segments),
             cy + r * math.sin(2 * math.pi * k / segments)) for k in range(segments)]


def rect_polygon(x, y, w, h):
    return [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]


def stroke_polygons(polyline, width, closed=False):
    """Approximate a stroked polyline (round caps + round joins) as a set of
    filled polygons: one quad per segment plus a disc at every vertex. All
    discs/quads share winding orientation, so they safely union under the
    nonzero fill rule without cancelling each other out."""
    half = width / 2.0
    polys = []
    n = len(polyline)
    if n < 2:
        return polys

    seg_count = n if closed else n - 1
    for k in range(seg_count):
        x0, y0 = polyline[k]
        x1, y1 = polyline[(k + 1) % n]
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy)
        if length < 1e-9:
            continue
        nx, ny = -dy / length * half, dx / length * half
        polys.append([(x0 + nx, y0 + ny), (x1 + nx, y1 + ny),
                       (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)])

    # round joins/caps at every vertex (harmless extra discs at interior
    # joins too - they're fully covered by the segment quads anyway)
    vertex_count = n if closed else n
    for k in range(vertex_count):
        polys.append(circle_polygon(polyline[k][0], polyline[k][1], half, segments=16))

    return polys


def _is_no_paint(value):
    if value is None:
        return False
    v = value.strip().lower()
    if v == "none":
        return True
    m = re.match(r"rgba\(\s*[\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+\s*,\s*([\d.]+)\s*\)", v)
    if m:
        return float(m.group(1)) <= 0.0
    return False


# ------------------------------------------------------------ 2D transforms

IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def mat_mult(m1, m2):
    """Returns M such that M(p) == m1(m2(p)) - i.e. m2 is applied first."""
    a1, b1, c1, d1, e1, f1 = m1
    a2, b2, c2, d2, e2, f2 = m2
    return (
        a1 * a2 + c1 * b2,
        b1 * a2 + d1 * b2,
        a1 * c2 + c1 * d2,
        b1 * c2 + d1 * d2,
        a1 * e2 + c1 * f2 + e1,
        b1 * e2 + d1 * f2 + f1,
    )


def mat_apply(m, pt):
    a, b, c, d, e, f = m
    x, y = pt
    return (a * x + c * y + e, b * x + d * y + f)


_TRANSFORM_FUNC_RE = re.compile(r"(\w+)\s*\(([^)]*)\)")


def parse_transform(attr):
    """Parses an SVG `transform` attribute into a single composed matrix."""
    if not attr:
        return IDENTITY
    total = IDENTITY
    for name, argstr in _TRANSFORM_FUNC_RE.findall(attr):
        args = [float(v) for v in re.split(r"[\s,]+", argstr.strip()) if v]
        if name == "translate":
            tx = args[0]
            ty = args[1] if len(args) > 1 else 0.0
            m = (1.0, 0.0, 0.0, 1.0, tx, ty)
        elif name == "scale":
            sx = args[0]
            sy = args[1] if len(args) > 1 else sx
            m = (sx, 0.0, 0.0, sy, 0.0, 0.0)
        elif name == "rotate":
            theta = math.radians(args[0])
            cos_t, sin_t = math.cos(theta), math.sin(theta)
            if len(args) >= 3:
                cx, cy = args[1], args[2]
                m = mat_mult(mat_mult((1, 0, 0, 1, cx, cy), (cos_t, sin_t, -sin_t, cos_t, 0, 0)),
                              (1, 0, 0, 1, -cx, -cy))
            else:
                m = (cos_t, sin_t, -sin_t, cos_t, 0.0, 0.0)
        elif name == "matrix":
            m = tuple(args[:6])
        elif name == "skewX":
            m = (1.0, 0.0, math.tan(math.radians(args[0])), 1.0, 0.0, 0.0)
        elif name == "skewY":
            m = (1.0, math.tan(math.radians(args[0])), 0.0, 1.0, 0.0, 0.0)
        else:
            m = IDENTITY
        total = mat_mult(total, m)
    return total


# ------------------------------------------------------------ rasterization

def rasterize_polygons(polygons, width, height, fill_rule="nonzero", supersample=SUPERSAMPLE):
    """Scanline-fill a set of (possibly overlapping/nested) polygons into an
    alpha mask of size width x height, anti-aliased via supersampling."""
    sw, sh = width * supersample, height * supersample
    mask = bytearray(sw * sh)

    # Pre-scale polygons into supersampled space and build an edge list:
    # (y0, y1, x_at_y0, dx/dy, winding_dir)
    edges = []
    for poly in polygons:
        n = len(poly)
        if n < 2:
            continue
        for k in range(n):
            (x0, y0) = poly[k]
            (x1, y1) = poly[(k + 1) % n]
            x0, y0, x1, y1 = x0 * supersample, y0 * supersample, x1 * supersample, y1 * supersample
            if y0 == y1:
                continue
            direction = 1 if y1 > y0 else -1
            if y0 > y1:
                x0, y0, x1, y1 = x1, y1, x0, y0
            edges.append((y0, y1, x0, (x1 - x0) / (y1 - y0), direction))

    for py in range(sh):
        y = py + 0.5
        xs = []
        for (y0, y1, x0, slope, direction) in edges:
            if y0 <= y < y1:
                x = x0 + (y - y0) * slope
                xs.append((x, direction))
        if not xs:
            continue
        xs.sort(key=lambda t: t[0])

        if fill_rule == "evenodd":
            it = iter(xs)
            for (xa, _), (xb, _) in zip(it, it):
                _fill_span(mask, sw, py, xa, xb)
        else:  # nonzero
            winding = 0
            span_start = None
            for (x, direction) in xs:
                prev_winding = winding
                winding += direction
                if prev_winding == 0 and winding != 0:
                    span_start = x
                elif prev_winding != 0 and winding == 0 and span_start is not None:
                    _fill_span(mask, sw, py, span_start, x)
                    span_start = None

    return _downsample(mask, sw, sh, width, height, supersample)


def _fill_span(mask, sw, py, xa, xb):
    if xb < xa:
        xa, xb = xb, xa
    xa_i = max(0, int(math.floor(xa)))
    xb_i = min(sw, int(math.ceil(xb)))
    row = py * sw
    for x in range(xa_i, xb_i):
        # fractional coverage at the span edges for extra smoothness
        left = max(xa, x)
        right = min(xb, x + 1)
        cov = max(0.0, right - left)
        val = min(255, mask[row + x] + int(cov * 255))
        mask[row + x] = val


def _downsample(mask, sw, sh, w, h, s):
    out = bytearray(w * h)
    for y in range(h):
        for x in range(w):
            total = 0
            for sy in range(s):
                base = (y * s + sy) * sw + x * s
                for sx in range(s):
                    total += mask[base + sx]
            out[y * w + x] = min(255, total // (s * s))
    return out


# --------------------------------------------------------------- PNG output

def write_rgba_png(path, width, height, alpha_mask, rgb=(255, 255, 255)):
    r, g, b = rgb

    def chunk(tag, data):
        c = tag + data
        return struct.pack("!I", len(data)) + c + struct.pack("!I", zlib.crc32(c) & 0xffffffff)

    raw = bytearray()
    for y in range(height):
        raw.append(0)  # no filter
        for x in range(width):
            a = alpha_mask[y * width + x]
            raw += bytes((r, g, b, a))

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack("!IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 9)
    with open(path, "wb") as f:
        f.write(sig)
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat))
        f.write(chunk(b"IEND", b""))


# ------------------------------------------------------------------ driver

def render_svg(svg_path, out_path, target=64):
    tree = ET.parse(svg_path)
    root = tree.getroot()

    def strip_ns(tag):
        return tag.split("}")[-1]

    vb = root.get("viewBox")
    if vb:
        vx, vy, vw, vh = map(float, vb.split())
    else:
        vw = float(root.get("width", "16"))
        vh = float(root.get("height", "16"))
        vx = vy = 0.0

    scale = target / max(vw, vh)
    off_x = (target - vw * scale) / 2 - vx * scale
    off_y = (target - vh * scale) / 2 - vy * scale

    def xf(pt):
        return (pt[0] * scale + off_x, pt[1] * scale + off_y)

    polys_by_rule = {"nonzero": [], "evenodd": []}

    def walk(el, in_defs, matrix):
        tag = strip_ns(el.tag)
        # <defs>/<clipPath> contents are never directly painted - they're
        # only referenced (e.g. as a clip mask), so skip the whole subtree.
        if tag in ("defs", "clipPath", "mask", "symbol"):
            in_defs = True

        local = parse_transform(el.get("transform"))
        matrix = mat_mult(matrix, local)

        def xfm(pt):
            return xf(mat_apply(matrix, pt))

        if not in_defs:
            rule = el.get("fill-rule", "nonzero")
            fill = el.get("fill")
            stroke = el.get("stroke")
            stroke_width = float(el.get("stroke-width", "1") or 1)
            use_stroke = _is_no_paint(fill) and stroke and not _is_no_paint(stroke)
            # approximate uniform scale factor of this element's local matrix,
            # for converting stroke-width (defined in local units) to output px
            local_scale = math.hypot(matrix[0], matrix[1])

            if tag == "path":
                d = el.get("d")
                if d:
                    for poly in parse_path(d):
                        pts = [xfm(p) for p in poly]
                        if use_stroke:
                            closed = len(pts) > 2 and pts[0] == pts[-1]
                            sw = stroke_width * scale * local_scale
                            for spoly in stroke_polygons(pts, sw, closed=closed):
                                polys_by_rule["nonzero"].append(spoly)
                        elif not _is_no_paint(fill):
                            polys_by_rule[rule].append(pts)
            elif tag == "circle":
                cx, cy, r = float(el.get("cx")), float(el.get("cy")), float(el.get("r"))
                if not _is_no_paint(fill):
                    polys_by_rule[rule].append([xfm(p) for p in circle_polygon(cx, cy, r)])
            elif tag == "rect":
                x, y = float(el.get("x", 0)), float(el.get("y", 0))
                w, h = float(el.get("width")), float(el.get("height"))
                if not _is_no_paint(fill):
                    polys_by_rule[rule].append([xfm(p) for p in rect_polygon(x, y, w, h)])

        for child in el:
            walk(child, in_defs, matrix)

    walk(root, False, IDENTITY)

    combined = bytearray(target * target)
    for rule, polys in polys_by_rule.items():
        if not polys:
            continue
        mask = rasterize_polygons(polys, target, target, fill_rule=rule)
        for idx in range(len(combined)):
            combined[idx] = max(combined[idx], mask[idx])

    write_rgba_png(out_path, target, target, combined)


if __name__ == "__main__":
    import sys
    import os

    src_dir, out_dir, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
    os.makedirs(out_dir, exist_ok=True)
    for fname in sorted(os.listdir(src_dir)):
        if not fname.endswith(".svg"):
            continue
        out_name = os.path.splitext(fname)[0] + ".png"
        render_svg(os.path.join(src_dir, fname), os.path.join(out_dir, out_name), target=size)
        print("rendered", fname, "->", out_name)
