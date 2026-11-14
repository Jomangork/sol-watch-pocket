"""Preview a 24×24 icon design the way Android shows it: one PNG with the round launcher icon
(large and at home-screen size) and the white status-bar silhouette on black.

    python3 tools/preview_icon.py tools/icon/wristwatch-v2.txt tools/icon/preview-v2.png
"""
import struct
import sys
import zlib

import make_icon as m

NIGHT = (24, 26, 32)        # launcher wallpaper behind the round mask
BAR = (0, 0, 0)             # status bar
LABEL_GAP = 16


def rgb(hex_colour):
    return tuple(int(hex_colour[k:k + 2], 16) for k in (1, 3, 5))


def round_icon(rows, px_per_dp):
    """The 72 dp visible part of the adaptive icon, under a circle mask."""
    size = 72 * px_per_dp
    image = []
    for py in range(size):
        line = []
        for px in range(size):
            dx, dy = px / px_per_dp - 36, py / px_per_dp - 36
            if dx * dx + dy * dy > 36 * 36:
                line.append(NIGHT)
                continue
            x, y = px / px_per_dp + 18 - m.OFFSET, py / px_per_dp + 18 - m.OFFSET
            i, j = int(x // m.SCALE), int(y // m.SCALE)
            inside = x >= 0 and y >= 0 and i < 24 and j < 24
            line.append(rgb(m.PALETTE[rows[j][i]]) if inside and rows[j][i] != "." else rgb(m.BACKGROUND))
        image.append(line)
    return image


def silhouette(rows, scale):
    mono = m.silhouette(rows)
    return [[(255, 255, 255) if mono[y // scale][x // scale] == "#" else BAR for x in range(24 * scale)]
            for y in range(24 * scale)]


def paste(canvas, image, left, top):
    for y, line in enumerate(image):
        canvas[top + y][left:left + len(line)] = line


def write_png(path, canvas):
    h, w = len(canvas), len(canvas[0])
    raw = b"".join(b"\0" + bytes(c for px in line for c in px) for line in canvas)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def main():
    rows = m.read(sys.argv[1])
    big, small = round_icon(rows, 4), round_icon(rows, 1)          # 288 px and 72 px (home screen at 1×)
    bar_big, bar_small = silhouette(rows, 6), silhouette(rows, 1)  # 144 px and 24 px
    width = len(big) + LABEL_GAP * 3 + len(small) + len(bar_big)
    canvas = [[NIGHT] * width for _ in range(len(big))]
    paste(canvas, big, 0, 0)
    x = len(big) + LABEL_GAP
    paste(canvas, small, x, 0)
    x += len(small) + LABEL_GAP
    bar = [[BAR] * (len(bar_big) + LABEL_GAP) for _ in range(len(bar_big) + 40)]
    paste(bar, bar_big, 0, 0)
    paste(bar, bar_small, 0, len(bar_big) + 8)
    paste(canvas, bar, x, 0)
    write_png(sys.argv[2], canvas)


if __name__ == "__main__":
    main()
