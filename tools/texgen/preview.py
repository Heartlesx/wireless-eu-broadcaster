"""材质预览：深色底 + 最近邻放大 + 与 GTCE 机器同框。

白色底会把"镂空"看成米白，所以预览一律合成到深色底再检查。
"""
import argparse
from pathlib import Path

from PIL import Image

import jarutil
from pixel import DARK, on_dark, save_png, upscale

DEFAULT_TIERS = ['lv', 'mv', 'hv', 'ev', 'iv', 'luv', 'zpm', 'uv']
SLOTS = [(0, 0), (1, 0), (2, 0), (0, 1), (2, 1), (0, 2), (1, 2), (2, 2)]


def _as_16(img):
    if img.size != (16, 16):
        print('提示：%dx%d 贴图按最近邻缩到 16x16 参与同框' % img.size)
        img = img.resize((16, 16), Image.Resampling.NEAREST)
    return img


def _mock_panel(ours, tiers, cell=16, scale=8):
    panel = Image.new('RGBA', (cell * 3, cell * 3), DARK)
    for (gx, gy), tier in zip(SLOTS, tiers):
        try:
            panel.alpha_composite(jarutil.machine_face(tier), (gx * cell, gy * cell))
        except KeyError:
            print('提示：GTCE 缺少 %s 级壳体，该格留空' % tier)
    panel.alpha_composite(ours, (cell, cell))
    return upscale(on_dark(panel), scale)


def build(tex_path, out_path, scale=16, tiers=None, mock=True):
    ours = Image.open(tex_path).convert('RGBA')
    zoom = upscale(on_dark(ours), scale)
    if not mock:
        return save_png(zoom, out_path)

    panel = _mock_panel(_as_16(ours), tiers or DEFAULT_TIERS)
    gap = 8
    width = zoom.width + gap + panel.width
    height = max(zoom.height, panel.height)
    canvas = Image.new('RGBA', (width, height), DARK)
    canvas.alpha_composite(zoom, (0, (height - zoom.height) // 2))
    canvas.alpha_composite(panel, (zoom.width + gap, (height - panel.height) // 2))
    return save_png(canvas, out_path)


def main():
    parser = argparse.ArgumentParser(description='生成深色底预览和 GTCE 同框图')
    parser.add_argument('src')
    parser.add_argument('out')
    parser.add_argument('--scale', type=int, default=16, help='单图放大倍数')
    parser.add_argument('--tiers', default=','.join(DEFAULT_TIERS),
                        help='同框用的 GTCE 电压等级，逗号分隔')
    parser.add_argument('--no-mock', action='store_true', help='只出单图放大，不拼 GTCE 同框')
    args = parser.parse_args()

    tiers = [t.strip() for t in args.tiers.split(',') if t.strip()]
    path = build(args.src, args.out, args.scale, tiers, not args.no_mock)
    print('预览 %s' % Path(path).resolve())


if __name__ == '__main__':
    main()
