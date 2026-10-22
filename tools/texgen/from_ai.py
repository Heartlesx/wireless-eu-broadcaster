"""生图 → MC 贴图（v5 配方）。

要点：按格取中心区域的众数色，绝不跨格平均；透明度按整格主色判断，
避免把浅色符号（米白/象牙）按像素阈值挖空。
"""
import argparse
import math
from collections import Counter
from pathlib import Path

from PIL import Image

from pixel import save_png


def _dist(a, b):
    return ((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2) ** 0.5


def _background(img):
    """边框像素按 8 级量化取众数桶，再求桶内均值。"""
    w, h = img.size
    px = img.load()
    step = max(1, min(w, h) // 128)
    edge = []
    for x in range(0, w, step):
        edge.append(px[x, 0])
        edge.append(px[x, h - 1])
    for y in range(0, h, step):
        edge.append(px[0, y])
        edge.append(px[w - 1, y])
    key = Counter(tuple(c // 8 * 8 for c in p) for p in edge).most_common(1)[0][0]
    members = [p for p in edge if tuple(c // 8 * 8 for c in p) == key]
    return tuple(round(sum(m[i] for m in members) / len(members)) for i in range(3))


def _bbox(img, bg, tol):
    px = img.load()
    minx, miny, maxx, maxy = img.width, img.height, -1, -1
    for y in range(img.height):
        for x in range(img.width):
            if _dist(px[x, y], bg) >= tol:
                minx = min(minx, x)
                maxx = max(maxx, x)
                miny = min(miny, y)
                maxy = max(maxy, y)
    if maxx < 0:
        raise ValueError('整张图都接近背景色，没有检测到前景')
    return minx, miny, maxx, maxy


def _square_crop(bbox, margin):
    minx, miny, maxx, maxy = bbox
    side = max(maxx - minx + 1, maxy - miny + 1) * (1 + 2 * margin)
    cx = (minx + maxx + 1) / 2
    cy = (miny + maxy + 1) / 2
    return cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2


def _cell_color(img, x0, y0, x1, y1, frac=0.6):
    """格中心 frac 区域内 4bit 分桶取众数，再求该桶像素均值。"""
    padx = (x1 - x0) * (1 - frac) / 2
    pady = (y1 - y0) * (1 - frac) / 2
    ax = math.ceil(x0 + padx)
    bx = math.floor(x1 - padx)
    ay = math.ceil(y0 + pady)
    by = math.floor(y1 - pady)
    if bx - ax < 1 or by - ay < 1:
        ax, bx = math.floor((x0 + x1) / 2), math.floor((x0 + x1) / 2) + 1
        ay, by = math.floor((y0 + y1) / 2), math.floor((y0 + y1) / 2) + 1
    ax = max(0, min(img.width - 1, ax))
    bx = max(ax + 1, min(img.width, bx))
    ay = max(0, min(img.height - 1, ay))
    by = max(ay + 1, min(img.height, by))

    colors = img.crop((ax, ay, bx, by)).getcolors((bx - ax) * (by - ay))
    if not colors:
        return None

    buckets = Counter()
    for count, (r, g, b) in colors:
        buckets[(r >> 4, g >> 4, b >> 4)] += count
    key = buckets.most_common(1)[0][0]

    total = rs = gs = bs = 0
    for count, (r, g, b) in colors:
        if (r >> 4, g >> 4, b >> 4) == key:
            total += count
            rs += r * count
            gs += g * count
            bs += b * count
    return (round(rs / total), round(gs / total), round(bs / total))


def _merge_palette(colors, max_colors):
    """按出现次数从多到少贪心聚类，收敛成 max_colors 个平涂色。"""
    clusters = []
    for color in colors:
        for cluster in clusters:
            avg = tuple(c / cluster[3] for c in cluster[:3])
            if _dist(color, avg) < 24:
                cluster[0] += color[0]
                cluster[1] += color[1]
                cluster[2] += color[2]
                cluster[3] += 1
                break
        else:
            clusters.append([color[0], color[1], color[2], 1])
    clusters.sort(key=lambda c: -c[3])
    return [tuple(round(v / c[3]) for v in c[:3]) for c in clusters[:max_colors]]


def convert(src, size=32, bg_tol=40, alpha_tol=14, max_colors=6, margin=0.035):
    img = Image.open(src).convert('RGB')
    bg = _background(img)
    crop = _square_crop(_bbox(img, bg, bg_tol), margin)
    x0, y0, x1, y1 = crop

    cells = []
    for row in range(size):
        line = []
        for col in range(size):
            line.append(_cell_color(
                img,
                x0 + (x1 - x0) * col / size, y0 + (y1 - y0) * row / size,
                x0 + (x1 - x0) * (col + 1) / size, y0 + (y1 - y0) * (row + 1) / size))
        cells.append(line)

    opaque = [c for line in cells for c in line
              if c is not None and _dist(c, bg) >= alpha_tol]
    palette = _merge_palette(opaque, max_colors)

    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    px = out.load()
    for y, line in enumerate(cells):
        for x, color in enumerate(line):
            if color is None or _dist(color, bg) < alpha_tol:
                continue
            nearest = min(palette, key=lambda p: _dist(color, p))
            px[x, y] = nearest + (255,)

    return out, {'bg': bg, 'palette': palette, 'opaque': len(opaque), 'total': size * size}


def main():
    parser = argparse.ArgumentParser(description='生图转 MC 贴图（v5 配方）')
    parser.add_argument('src')
    parser.add_argument('out')
    parser.add_argument('--size', type=int, default=32, help='输出边长，物品 32、方块 16')
    parser.add_argument('--colors', type=int, default=6, help='平涂色数量上限')
    parser.add_argument('--bg-tol', type=int, default=40, help='前景判定阈值')
    parser.add_argument('--alpha-tol', type=int, default=14, help='整格主色判透明的阈值')
    args = parser.parse_args()

    img, info = convert(args.src, args.size, args.bg_tol, args.alpha_tol, args.colors)
    save_png(img, args.out)
    print('背景 %s，不透明格 %d/%d' % (info['bg'], info['opaque'], info['total']))
    print('调色板 ' + ' '.join('#%02X%02X%02X' % c for c in info['palette']))
    print('输出 %s (%dx%d)' % (Path(args.out).resolve(), img.width, img.height))


if __name__ == '__main__':
    main()
