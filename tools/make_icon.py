"""Renders the launcher icon (indigo disc with a white ghost) as PNGs, no dependencies."""
import math, os, struct, sys, zlib

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
TOP, BOTTOM = (0x7C, 0x78, 0xF5), (0x3B, 0x37, 0xB8)
def seg_dist(px, py, a, b):
    ax, ay = a; bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))

def ghost(u, v):
    """True inside the ghost: a domed head, straight sides and a wavy hem, minus two eyes."""
    cx, top_r, head_y, hem_y = 0.5, 0.20, 0.43, 0.70
    for ex in (0.42, 0.58):  # eyes
        if math.hypot(u - ex, v - 0.43) < 0.04:
            return False
    if abs(u - cx) > top_r:
        return False
    if v < head_y:
        return math.hypot(u - cx, v - head_y) <= top_r
    wave = 0.035 * math.cos((u - (cx - top_r)) / (2 * top_r) * 3 * 2 * math.pi)
    return v <= hem_y + wave

def sample(u, v):
    r = math.hypot(u - 0.5, v - 0.5)
    if r > 0.46:
        return None
    if ghost(u, v):
        return (255, 255, 255)
    k = (u + v) / 2
    return tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * k) for i in range(3))

def render(size, ss=4):
    rows = []
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            acc = [0, 0, 0]; hits = 0
            for sy in range(ss):
                for sx in range(ss):
                    c = sample((x + (sx + 0.5) / ss) / size, (y + (sy + 0.5) / ss) / size)
                    if c:
                        hits += 1
                        for i in range(3): acc[i] += c[i]
            if hits:
                row += bytes([acc[0] // hits, acc[1] // hits, acc[2] // hits, 255 * hits // (ss * ss)])
            else:
                row += bytes(4)
        rows.append(bytes(row))
    return b"".join(rows)

def png(size, raw):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

out = sys.argv[1] if len(sys.argv) > 1 else "res"
for name, size in SIZES.items():
    d = os.path.join(out, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "ic_launcher.png"), "wb") as f:
        f.write(png(size, render(size)))
print("icons written")
