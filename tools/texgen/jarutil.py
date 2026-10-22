"""从 GTCE jar 里直接读参考贴图，用于同框预览。"""
import io
import zipfile
from pathlib import Path

from PIL import Image

JAR = Path(__file__).resolve().parents[2] / 'libs' / 'gregtech-1.12.2-2901.jar'


def read_sprite(entry):
    with zipfile.ZipFile(JAR) as jar:
        data = jar.read(entry)
    return Image.open(io.BytesIO(data)).convert('RGBA')


def machine_face(tier, machine='alloy_smelter', face='front'):
    """GTCE 单方块机器的正面 = 电压壳体 + 机器 overlay。"""
    sprite = read_sprite(
        'assets/gregtech/textures/blocks/casings/voltage/%s/side.png' % tier)
    try:
        overlay = read_sprite(
            'assets/gregtech/textures/blocks/machines/%s/overlay_%s.png' % (machine, face))
        sprite.alpha_composite(overlay, (0, 0))
    except KeyError:
        pass
    return sprite
