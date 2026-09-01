#!/usr/bin/env python3
"""Generates the cursor and mouse-state sprites the e2e overlay draws.

The art is data, not craft: fifteen small pixel shapes that have to read on a bright inventory and a
dark one alike. Generating them keeps that editable -- a tweak is a line here rather than fifteen
binaries to open -- and keeps any image library off the build.

    python tools/cursor-sprites.py

Every sprite is white with a dark outline and a one-pixel shadow, which is what makes it legible over
whatever happens to be behind it. Sources are 32x32 (mouse parts 24x32) and are drawn at half that,
so they stay crisp at every GUI scale.
"""

import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "e2e-core", "src", "main", "resources", "assets", "e2e", "textures", "gui")

# The palette. Body and outline carry the shape; the shadow softens it against a busy background.
BODY = (255, 255, 255, 255)
LINE = (24, 24, 28, 255)
SHADOW = (0, 0, 0, 90)
HELD = (255, 92, 76, 255)      # a filled mouse button
SCROLL = (110, 200, 255, 255)  # the wheel arrow
NOTHING = (0, 0, 0, 0)


class Sprite:
    """A little RGBA canvas. Coordinates are (x, y) from the top left."""

    def __init__(self, width, height):
        self.width = width
        self.height = height
        self.pixels = [NOTHING] * (width * height)

    def set(self, x, y, colour):
        if 0 <= x < self.width and 0 <= y < self.height:
            self.pixels[y * self.width + x] = colour

    def get(self, x, y):
        if 0 <= x < self.width and 0 <= y < self.height:
            return self.pixels[y * self.width + x]
        return NOTHING

    def fill_rect(self, x, y, w, h, colour):
        for dy in range(h):
            for dx in range(w):
                self.set(x + dx, y + dy, colour)

    def line(self, x0, y0, x1, y1, colour):
        steps = max(abs(x1 - x0), abs(y1 - y0)) or 1
        for i in range(steps + 1):
            self.set(round(x0 + (x1 - x0) * i / steps), round(y0 + (y1 - y0) * i / steps), colour)

    def fill_polygon(self, points, colour):
        """Scanline fill. Good enough for shapes this small, and it needs no dependencies."""
        ys = [p[1] for p in points]
        for y in range(min(ys), max(ys) + 1):
            crossings = []
            for i in range(len(points)):
                (x0, y0), (x1, y1) = points[i], points[(i + 1) % len(points)]
                if y0 == y1:
                    continue
                if min(y0, y1) <= y < max(y0, y1):
                    crossings.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
            crossings.sort()
            for i in range(0, len(crossings) - 1, 2):
                for x in range(round(crossings[i]), round(crossings[i + 1]) + 1):
                    self.set(x, y, colour)

    def outline_polygon(self, points, colour):
        for i in range(len(points)):
            x0, y0 = points[i]
            x1, y1 = points[(i + 1) % len(points)]
            self.line(x0, y0, x1, y1, colour)

    def drop_shadow(self):
        """One pixel down and right, under everything already drawn."""
        shadowed = Sprite(self.width, self.height)
        for y in range(self.height):
            for x in range(self.width):
                if self.get(x, y)[3] != 0:
                    shadowed.set(x + 1, y + 1, SHADOW)
        for y in range(self.height):
            for x in range(self.width):
                if self.get(x, y)[3] != 0:
                    shadowed.set(x, y, self.get(x, y))
        return shadowed

    def write(self, path):
        raw = b""
        for y in range(self.height):
            raw += b"\x00"
            for x in range(self.width):
                raw += bytes(self.get(x, y))

        def chunk(kind, data):
            body = kind + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

        header = struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0)
        png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) \
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as out:
            out.write(png)
        print("  " + os.path.relpath(path, os.path.join(HERE, "..")).replace("\\", "/"))


def shape(points):
    """A filled white shape with a dark edge -- the look every sprite here shares."""
    sprite = Sprite(32, 32)
    sprite.fill_polygon(points, BODY)
    sprite.outline_polygon(points, LINE)
    return sprite.drop_shadow()


def arrow():
    return shape([(2, 1), (2, 24), (8, 18), (12, 27), (16, 25), (12, 16), (20, 16)])


def pointing_hand():
    """A fist with the index finger up, which is what every system draws for a link."""
    sprite = Sprite(32, 32)
    # The finger, its tip rounded off by clipping the corners.
    finger = [(11, 5), (12, 3), (14, 3), (15, 5), (15, 15), (11, 15)]
    # The fist: knuckles on the right, thumb on the left, tapering to the wrist.
    fist = [
        (15, 12), (17, 11), (19, 12), (19, 13),
        (21, 13), (23, 15), (23, 24), (21, 28),
        (13, 28), (10, 25), (9, 18), (11, 15),
    ]
    for points in (fist, finger):
        sprite.fill_polygon(points, BODY)
    for points in (fist, finger):
        sprite.outline_polygon(points, LINE)
    # Creases between the folded fingers, so the fist reads as a hand and not a mitten.
    for y in (18, 21, 24):
        sprite.line(16, y, 22, y, LINE)
    return sprite.drop_shadow()


def ibeam():
    sprite = Sprite(32, 32)
    sprite.fill_rect(14, 4, 3, 24, BODY)
    sprite.fill_rect(10, 3, 11, 2, BODY)
    sprite.fill_rect(10, 27, 11, 2, BODY)
    outlined = Sprite(32, 32)
    for y in range(32):
        for x in range(32):
            if sprite.get(x, y)[3] != 0:
                outlined.set(x, y, BODY)
            elif any(sprite.get(x + dx, y + dy)[3] != 0 for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                outlined.set(x, y, LINE)
    return outlined.drop_shadow()


def crosshair():
    sprite = Sprite(32, 32)
    sprite.fill_rect(15, 3, 2, 10, BODY)
    sprite.fill_rect(15, 19, 2, 10, BODY)
    sprite.fill_rect(3, 15, 10, 2, BODY)
    sprite.fill_rect(19, 15, 10, 2, BODY)
    outlined = Sprite(32, 32)
    for y in range(32):
        for x in range(32):
            if sprite.get(x, y)[3] != 0:
                outlined.set(x, y, BODY)
            elif any(sprite.get(x + dx, y + dy)[3] != 0 for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                outlined.set(x, y, LINE)
    return outlined.drop_shadow()


def double_arrow(horizontal):
    """The resize cursors: one shaft, a head at each end."""
    sprite = Sprite(32, 32)
    if horizontal:
        sprite.fill_polygon([(2, 16), (10, 10), (10, 22)], BODY)
        sprite.outline_polygon([(2, 16), (10, 10), (10, 22)], LINE)
        sprite.fill_polygon([(29, 16), (21, 10), (21, 22)], BODY)
        sprite.outline_polygon([(29, 16), (21, 10), (21, 22)], LINE)
        sprite.fill_rect(9, 14, 14, 5, BODY)
        sprite.line(9, 13, 22, 13, LINE)
        sprite.line(9, 19, 22, 19, LINE)
    else:
        sprite.fill_polygon([(16, 2), (10, 10), (22, 10)], BODY)
        sprite.outline_polygon([(16, 2), (10, 10), (22, 10)], LINE)
        sprite.fill_polygon([(16, 29), (10, 21), (22, 21)], BODY)
        sprite.outline_polygon([(16, 29), (10, 21), (22, 21)], LINE)
        sprite.fill_rect(14, 9, 5, 14, BODY)
        sprite.line(13, 9, 13, 22, LINE)
        sprite.line(19, 9, 19, 22, LINE)
    return sprite.drop_shadow()


def resize_all():
    sprite = Sprite(32, 32)
    for points in (
        [(16, 2), (11, 9), (21, 9)],
        [(16, 29), (11, 22), (21, 22)],
        [(2, 16), (9, 11), (9, 21)],
        [(29, 16), (22, 11), (22, 21)],
    ):
        sprite.fill_polygon(points, BODY)
        sprite.outline_polygon(points, LINE)
    sprite.fill_rect(14, 8, 5, 16, BODY)
    sprite.fill_rect(8, 14, 16, 5, BODY)
    return sprite.drop_shadow()


def not_allowed():
    """A ring with a bar through it. Hollow, or it is just a dot."""
    from math import hypot

    sprite = Sprite(32, 32)
    cx, cy = 15.5, 15.5
    for y in range(32):
        for x in range(32):
            distance = hypot(x - cx, y - cy)
            if 9.5 <= distance <= 13.5:
                sprite.set(x, y, BODY)

    # The bar, at the angle a "no" sign uses. One solid quad rather than a fan of offset lines:
    # a diagonal line is a staircase, and five of them side by side leave gaps between the steps.
    sprite.fill_polygon([(7, 21), (10, 24), (24, 10), (21, 7)], BODY)

    # Outline everything that is now filled, which gives the ring and the bar one shared edge.
    outlined = Sprite(32, 32)
    for y in range(32):
        for x in range(32):
            if sprite.get(x, y)[3] != 0:
                outlined.set(x, y, BODY)
            elif any(sprite.get(x + dx, y + dy)[3] != 0
                     for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                outlined.set(x, y, LINE)
    return outlined.drop_shadow()


def default_cursor():
    """What Minecraft asks for when it wants the plain system pointer: our arrow, dimmed."""
    sprite = arrow()
    faded = Sprite(sprite.width, sprite.height)
    for y in range(sprite.height):
        for x in range(sprite.width):
            r, g, b, a = sprite.get(x, y)
            faded.set(x, y, (r, g, b, a * 3 // 4))
    return faded


# -- the mouse glyph, in parts so held buttons can stack --------------------------------------

# Wider than the mouse itself: the scroll arrow sits beside the wheel, where there is room for it
# to be big enough to read. Crammed into the body's own width it was three pixels tall.
MOUSE_W, MOUSE_H = 32, 32


def mouse_body():
    sprite = Sprite(MOUSE_W, MOUSE_H)
    outline = [(4, 8), (8, 2), (16, 2), (20, 8), (20, 26), (16, 30), (8, 30), (4, 26)]
    sprite.fill_polygon(outline, BODY)
    sprite.outline_polygon(outline, LINE)
    sprite.line(4, 15, 20, 15, LINE)   # where the buttons end
    sprite.line(12, 3, 12, 15, LINE)   # between left and right
    return sprite.drop_shadow()


def mouse_region(which):
    """Just the fill for one button, drawn over the body when it is held."""
    sprite = Sprite(MOUSE_W, MOUSE_H)
    if which == "left":
        sprite.fill_polygon([(5, 8), (8, 3), (11, 3), (11, 14), (5, 14)], HELD)
    elif which == "right":
        sprite.fill_polygon([(13, 3), (16, 3), (19, 8), (19, 14), (13, 14)], HELD)
    else:
        sprite.fill_rect(10, 5, 4, 8, HELD)
        sprite.outline_polygon([(10, 5), (13, 5), (13, 12), (10, 12)], LINE)
    return sprite


def scroll_arrow(up):
    """An arrow beside the wheel, pointing the way it turned."""
    sprite = Sprite(MOUSE_W, MOUSE_H)
    if up:
        head = [(26, 2), (21, 10), (31, 10)]
        shaft = (24, 9, 5, 8)
    else:
        head = [(26, 18), (21, 10), (31, 10)]
        shaft = (24, 3, 5, 8)
    sprite.fill_polygon(head, SCROLL)
    sprite.fill_rect(*shaft, SCROLL)
    sprite.outline_polygon(head, LINE)
    return sprite


def main():
    print("cursors:")
    cursors = {
        "default": default_cursor(),
        "arrow": arrow(),
        "ibeam": ibeam(),
        "crosshair": crosshair(),
        "pointing_hand": pointing_hand(),
        "resize_ns": double_arrow(horizontal=False),
        "resize_ew": double_arrow(horizontal=True),
        "resize_all": resize_all(),
        "not_allowed": not_allowed(),
    }
    for name, sprite in cursors.items():
        sprite.write(os.path.join(ASSETS, "cursor", name + ".png"))

    print("mouse:")
    parts = {
        "body": mouse_body(),
        "left": mouse_region("left"),
        "right": mouse_region("right"),
        "wheel": mouse_region("wheel"),
        "scroll_up": scroll_arrow(up=True),
        "scroll_down": scroll_arrow(up=False),
    }
    for name, sprite in parts.items():
        sprite.write(os.path.join(ASSETS, "mouse", name + ".png"))


if __name__ == "__main__":
    main()
