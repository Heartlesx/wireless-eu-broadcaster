"""像素图基础操作：掩码解析、二进制 alpha 保存、最近邻放大、深色底合成。"""
from pathlib import Path

from PIL import Image

DARK = (24, 24, 28, 255)


def upscale(img, factor):
    return img.resize((img.width * factor, img.height * factor), Image.Resampling.NEAREST)


def on_dark(img, bg=DARK):
    canvas = Image.new('RGBA', img.size, bg)
    canvas.alpha_composite(img.convert('RGBA'))
    return canvas


def image_from_mask(rows, palette, scale=1):
    """rows 是一组等长字符串，每个字符按 palette 映射成一个像素；'.' 为透明。"""
    width = len(rows[0])
    for index, row in enumerate(rows):
        if len(row) != width:
            raise ValueError('掩码第 %d 行宽 %d，应为 %d' % (index, len(row), width))

    img = Image.new('RGBA', (width, len(rows)), (0, 0, 0, 0))
    px = img.load()
    unknown = set()
    for y, row in enumerate(rows):
        for x, char in enumerate(row):
            if char == '.':
                continue
            color = palette.get(char)
            if color is None:
                unknown.add(char)
                continue
            px[x, y] = color if len(color) == 4 else tuple(color) + (255,)
    if unknown:
        raise KeyError('调色板缺少字符: ' + ' '.join(sorted(unknown)))

    return upscale(img, scale) if scale != 1 else img


def save_png(img, path):
    """保存为 PNG，并把 alpha 收敛成 0/255 两档（1.12 渲染不接受半透明）。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    img = img.convert('RGBA')
    alpha = img.getchannel('A').point(lambda v: 255 if v >= 128 else 0)
    img.putalpha(alpha)
    img.save(path, 'PNG')
    return path
