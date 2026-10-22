# tools/texgen — 材质工具

依赖：Python 3.14（`D:\Python314`）+ Pillow 13，已装在 D 盘系统 Python 里。
所有命令在 `forge-1.12.2` 目录下执行。

## 尺寸与规范

- 方块面 **16×16**（与 GTCE 全部机器贴图一致），物品图标 **32×32**。
- alpha 只有 0/255 两档，`pixel.save_png` 会强制收敛（1.12 渲染不接受半透明）。
- 预览一律合成到深色底：白底会把镂空看成米白色。

## 命令

```bash
# 生图转贴图（v5 配方：按格取众数色、整格主色判透明、并成平涂色板）
python tools/texgen/from_ai.py 生图/xxx.png 输出.png --size 32

# 预览：深色底放大 + 与 GTCE 机器同框（物品用 --no-mock）
python tools/texgen/preview.py 贴图.png 预览.png --scale 16
python tools/texgen/preview.py 贴图.png 预览.png --scale 16 --no-mock

# 自检（合成样图跑完整流水线，输出到 设计草案/texgen-selftest）
python tools/texgen/selftest.py
```

`from_ai.py` 可调参数：`--colors`（平涂色上限，默认 6）、`--bg-tol`（前景阈值，默认 40）、
`--alpha-tol`（整格主色判透明阈值，默认 14）。浅色符号被挖空时调小 `--alpha-tol`。

## 模块

| 文件 | 作用 |
|---|---|
| `pixel.py` | ASCII 掩码转图、二进制 alpha 保存、最近邻放大、深色底合成 |
| `from_ai.py` | 生图 → 贴图（v5 配方） |
| `preview.py` | 单图放大 + GTCE 同框预览 |
| `jarutil.py` | 直接从 `libs/gregtech-1.12.2-2901.jar` 读参考贴图 |
| `selftest.py` | 端到端自检 |

## 画方块面的写法

一个字符一个像素，字符映射到调色板，`.` 为透明：

```python
BLOCK = [
    'kkkkkkkkkkkkkkkk',
    'kmmmmmmmmmmmmmmk',
    'kmhhhhhhhhhhhhsk',
    ...
]
block = image_from_mask(BLOCK, palette)   # 16x16
save_png(block, '.../textures/blocks/xxx/side.png')
```
