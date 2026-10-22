"""自检：合成一张 1024 白底样图跑完整流水线，输出到 设计草案/texgen-selftest。"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

import preview
from from_ai import convert
from pixel import image_from_mask, save_png

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / '设计草案' / 'texgen-selftest'

ITEM_MASK_PALETTE = {
    'k': (0x15, 0x16, 0x17),
    'm': (0x80, 0x80, 0x8F),
    'b': (0xC6, 0xC6, 0xDA),
    'h': (0xE9, 0xE9, 0xF8),
    's': (0xAD, 0xAD, 0xC2),
    'g': (0xDA, 0xC3, 0x6B),
}

BLOCK_MASK = [
    'kkkkkkkkkkkkkkkk',
    'kmmmmmmmmmmmmmmk',
    'kmhhhhhhhhhhhhsk',
    'kmhbbbbbbbbbbbsk',
    'kmhbggggggggbbsk',
    'kmhbgg....ggbbsk',
    'kmhbgg....ggbbsk',
    'kmhbggggggggbbsk',
    'kmhbbbbbbbbbbbsk',
    'kmhhhhhhhhhhhhsk',
    'kmhbbbbbbbbbbbsk',
    'kmhssssssssssbsk',
    'kmhbbbbbbbbbbbsk',
    'kmhhhhhhhhhhhhsk',
    'kmmmmmmmmmmmmmmk',
    'kkkkkkkkkkkkkkkk',
]


def fake_art(path):
    """模拟生图产物：1024 白底、软边、图案居中。"""
    img = Image.new('RGB', (1024, 1024), (255, 255, 255))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((220, 300, 804, 724), radius=48,
                           fill=(198, 198, 218), outline=(120, 120, 143), width=14)
    draw.rounded_rectangle((268, 348, 756, 676), radius=28, fill=(24, 25, 27))
    for radius in (120, 80, 40):
        draw.arc((512 - radius, 512 - radius, 512 + radius, 512 + radius),
                 start=225, end=315, fill=(218, 195, 107), width=18)
    img = img.filter(ImageFilter.GaussianBlur(2))
    save_png(img, path)
    return path


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    failures = []

    art = fake_art(OUT / 'selftest_source.png')
    item, info = convert(art, size=32)
    item_path = save_png(item, OUT / 'selftest_item_32.png')
    alphas = {value for _, value in item.getchannel('A').getcolors(256)}
    if item.size != (32, 32):
        failures.append('物品尺寸 %s != 32x32' % (item.size,))
    if not alphas <= {0, 255}:
        failures.append('alpha 不是 0/255: %s' % sorted(alphas))
    if not 0.1 < info['opaque'] / info['total'] < 0.9:
        failures.append('不透明比例异常: %d/%d' % (info['opaque'], info['total']))

    block = image_from_mask(BLOCK_MASK, ITEM_MASK_PALETTE)
    block_path = save_png(block, OUT / 'selftest_block_16.png')
    if block.size != (16, 16):
        failures.append('方块尺寸 %s != 16x16' % (block.size,))

    previews = [
        preview.build(item_path, OUT / 'selftest_item_preview.png', scale=16, mock=False),
        preview.build(block_path, OUT / 'selftest_block_preview.png', scale=16),
    ]
    for path in previews:
        if not Path(path).exists() or Path(path).stat().st_size == 0:
            failures.append('预览未生成: %s' % path)

    print('调色板 ' + ' '.join('#%02X%02X%02X' % c for c in info['palette']))
    print('不透明格 %d/%d' % (info['opaque'], info['total']))
    for path in [item_path, block_path] + previews:
        print('  ' + str(path))

    if failures:
        print('自检失败:')
        for line in failures:
            print('  - ' + line)
        return 1
    print('自检通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
