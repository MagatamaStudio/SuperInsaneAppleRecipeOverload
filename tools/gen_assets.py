#!/usr/bin/env python3
"""SIAO 资源生成器。

从 mc-assets/ 的原版物品渲染图出发，程序化生成：
  * 物品贴图  assets/siao/textures/item/*.png
  * 方块贴图  assets/siao/textures/block/*.png
  * 物品模型 / 方块模型 / blockstate
  * 中英文 lang

用法：  python tools/gen_assets.py [--clean]

设计要点：
  * 物品：以原版渲染图为底，按「重数」向上叠印副本 + 逐级压暗，表达「压了很多层」。
  * 方块：原版方块渲染图是等轴视图，不能直接当六面贴图用；改为取其平均色，
          再画 3×3 压制格 + 重数指示点（第 N 重点亮 N 个格）。
  * 全部在构建期生成并入库，运行期零 tint 代码。
"""

from __future__ import annotations

import argparse
import colorsys
import json
import math
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ASSETS_SRC = ROOT / "mc-assets" / "item-assets"
RES = ROOT / "src" / "main" / "resources" / "assets" / "siao"

MOD_ID = "siao"
MAX_TIER = 9

# 物品贴图分辨率。128 足够清楚，又能把仓库体积压下来。
ITEM_SIZE = 128
BLOCK_SIZE = 64

ZH_TIERS = ["", "一", "二", "三", "四", "五", "六", "七", "八", "九"]

# id, 中文单位名, 英文单位名, 物品底图, 方块底图(None = 由物品底图取色)
MATERIALS = [
    ("iron",      "铁锭",       "Iron Ingot",      "IRON_INGOT",         "IRON_BLOCK",      True),
    ("gold",      "金锭",       "Gold Ingot",      "GOLD_INGOT",         "GOLD_BLOCK",      True),
    ("copper",    "铜锭",       "Copper Ingot",    "COPPER_INGOT",       "COPPER_BLOCK",    True),
    ("netherite", "下界合金锭", "Netherite Ingot", "NETHERITE_INGOT",    "NETHERITE_BLOCK", False),
    ("diamond",   "钻石",       "Diamond",         "DIAMOND",            "DIAMOND_BLOCK",   False),
    ("emerald",   "绿宝石",     "Emerald",         "EMERALD",            "EMERALD_BLOCK",   False),
    ("lapis",     "青金石",     "Lapis Lazuli",    "LAPIS_LAZULI",       "LAPIS_BLOCK",     False),
    ("amethyst",  "紫水晶",     "Amethyst Shard",  "AMETHYST_SHARD",     "AMETHYST_BLOCK",  False),
    ("quartz",    "下界石英",   "Nether Quartz",   "QUARTZ",             "QUARTZ_BLOCK",    False),
    ("coal",      "煤",         "Coal",            "COAL",               "COAL_BLOCK",      False),
    ("redstone",  "红石",       "Redstone",        "REDSTONE",           "REDSTONE_BLOCK",  False),
    ("apple",     "苹果锭",     "Apple Ingot",     "APPLE",              None,              True),
("apple_iron",      "苹果铁合金",   "Apple Iron Alloy",   "IRON_INGOT",      "IRON_BLOCK",      False),
("apple_gold",      "苹果金合金",   "Apple Gold Alloy",   "GOLD_INGOT",      "GOLD_BLOCK",      False),
("apple_copper",    "苹果铜合金",   "Apple Copper Alloy", "COPPER_INGOT",    "COPPER_BLOCK",    False),
("apple_diamond",   "苹果钻石合金", "Apple Diamond Alloy","DIAMOND",         "DIAMOND_BLOCK",   False),
("apple_netherite", "苹果下界合金", "Apple Netherite Alloy","NETHERITE_INGOT","NETHERITE_BLOCK",False),
]

RAW_BASE = {"iron": "RAW_IRON", "gold": "RAW_GOLD", "copper": "RAW_COPPER", "apple": "RAW_GOLD"}
RAW_BLOCK_BASE = {"iron": "RAW_IRON_BLOCK", "gold": "RAW_GOLD_BLOCK", "copper": "RAW_COPPER_BLOCK", "apple": "RAW_GOLD_BLOCK"}
# 粗形态名覆盖：默认是「去锭加粗」，苹果线要的是「粗苹果金」
RAW_NAME = {"apple": ("粗苹果金", "Raw Apple Gold")}


# --------------------------------------------------------------------------- 贴图

def load_base(name: str, size: int) -> Image.Image:
    path = ASSETS_SRC / f"{name}.png"
    if not path.exists():
        raise SystemExit(f"缺少底图: {path}")
    img = Image.open(path).convert("RGBA")
    return img.resize((size, size), Image.LANCZOS)


def load_icon(name: str, size: int) -> Image.Image:
    """裁掉原版渲染图的空白边缘后按比例缩放到 size 见方，让图标占满可用空间。"""
    path = ASSETS_SRC / f"{name}.png"
    if not path.exists():
        raise SystemExit(f"缺少底图: {path}")
    img = Image.open(path).convert("RGBA")
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
    img.thumbnail((size, size), Image.LANCZOS)
    return img


def average_color(img: Image.Image) -> tuple[int, int, int]:
    """按 alpha 加权求平均色。"""
    px = list(img.convert("RGBA").resize((32, 32), Image.LANCZOS).getdata())
    total = [0.0, 0.0, 0.0]
    weight = 0.0
    for r, g, b, a in px:
        if a < 16:
            continue
        w = a / 255.0
        total[0] += r * w
        total[1] += g * w
        total[2] += b * w
        weight += w
    if weight == 0:
        return (128, 128, 128)
    return (int(total[0] / weight), int(total[1] / weight), int(total[2] / weight))


# 重数徽章配色：1 级白金 → 4 级金 → 7 级橙红 → 9 级紫
TIER_COLORS = [
    (236, 240, 245),
    (222, 232, 240),
    (250, 232, 160),
    (250, 210, 110),
    (248, 186, 84),
    (245, 158, 66),
    (240, 120, 60),
    (214, 96, 150),
    (176, 108, 236),
]

# 九重的辉光色，与徽章同色系
GLOW_COLOR = (186, 126, 250)


def pick_font(px: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for name in ("arialbd.ttf", "seguisb.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, px)
        except OSError:
            continue
    return ImageFont.load_default()


def make_item_texture(base_name: str, tier: int) -> Image.Image:
    """扇形堆叠 tier 个图标 + 右下角重数徽章，九重额外加发光。

    第 N 重就是「N 个叠在一起」——视觉语义与玩法语义完全一致，
    一眼就能看出重数，也不会再被误认成原版物品。
    """
    size = ITEM_SIZE
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    content = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    n = tier
    step = max(3, size // 24)                       # 每层错位
    badge = int(size * 0.30)                        # 徽章边长
    usable = size - badge // 2 - 4                  # 给徽章留位置
    # 九重要在四周留出辉光余量，否则光晕会被画布边缘裁掉
    fit = 0.86 if tier == MAX_TIER else 0.96
    icon_side = int((usable - (n - 1) * step) * fit)

    icon = load_icon(base_name, max(16, icon_side))
    group = (n - 1) * step + max(icon.size)
    ox = (size - group) // 2
    oy = (size - group) // 2 - int(size * 0.05)

    for i in range(n):
        x = ox + i * step
        y = oy + (n - 1 - i) * step
        layer = icon
        if i < n - 1:
            # 越靠下层越暗，制造深度；不要阴影
            factor = 0.42 + 0.58 * (i / max(1, n - 1))
            layer = ImageEnhance.Brightness(icon).enhance(factor)
            alpha = layer.getchannel("A").point(lambda a: int(a * 0.92))
            layer = layer.copy()
            layer.putalpha(alpha)
        content.alpha_composite(layer, (x, y))

    # 九重：内容轮廓外扩两层辉光，垫在内容底下
    if tier == MAX_TIER:
        alpha = content.getchannel("A")
        for radius, strength in ((max(2, size // 22), 0.55), (max(1, size // 40), 0.85)):
            mask = alpha.filter(ImageFilter.GaussianBlur(radius))
            glow = Image.new("RGBA", (size, size), GLOW_COLOR + (0,))
            glow.putalpha(mask.point(lambda a, s=strength: int(a * s)))
            canvas.alpha_composite(glow)

    canvas.alpha_composite(content)

    # 重数徽章
    draw = ImageDraw.Draw(canvas)
    bx = size - badge - 1
    by = size - badge - 1
    color = TIER_COLORS[min(tier, 9) - 1]
    draw.rounded_rectangle([bx, by, size - 1, size - 1], radius=max(3, badge // 5),
                           fill=(18, 18, 22, 225), outline=color, width=max(1, size // 64))
    font = pick_font(int(badge * 0.70))
    text = str(tier)
    tb = draw.textbbox((0, 0), text, font=font)
    tx = bx + (badge - (tb[2] - tb[0])) / 2 - tb[0]
    ty = by + (badge - (tb[3] - tb[1])) / 2 - tb[1]
    draw.text((tx, ty), text, font=font, fill=color)

    return canvas


def make_block_texture(color: tuple[int, int, int], tier: int) -> Image.Image:
    """3×3 压制格 + 重数指示：第 N 重点亮 N 个格。"""
    r, g, b = color
    img = Image.new("RGBA", (BLOCK_SIZE, BLOCK_SIZE), (r, g, b, 255))
    draw = ImageDraw.Draw(img)

    margin = max(2, BLOCK_SIZE // 16)
    inner = BLOCK_SIZE - margin * 2
    cell = inner // 3
    gap = max(1, BLOCK_SIZE // 32)

    lit = tier
    for row in range(3):
        for col in range(3):
            idx = row * 3 + col + 1
            x0 = margin + col * cell + gap
            y0 = margin + row * cell + gap
            x1 = margin + (col + 1) * cell - gap - 1
            y1 = margin + (row + 1) * cell - gap - 1
            if idx <= lit:
                f = 1.25 - 0.04 * tier
                fill = (min(255, int(r * f)), min(255, int(g * f)), min(255, int(b * f)), 255)
                # 左上高光、右下阴影，做出浮雕
                draw.rectangle([x0, y0, x1, y1], fill=fill,
                               outline=(min(255, int(r * 1.5)), min(255, int(g * 1.5)), min(255, int(b * 1.5)), 255))
            else:
                fill = (int(r * 0.55), int(g * 0.55), int(b * 0.55), 255)
                draw.rectangle([x0, y0, x1, y1], fill=fill)

    # 外框随重数加深
    edge = (int(r * 0.35), int(g * 0.35), int(b * 0.35), 255)
    draw.rectangle([0, 0, BLOCK_SIZE - 1, BLOCK_SIZE - 1], outline=edge, width=max(1, BLOCK_SIZE // 32))
    return img


# --------------------------------------------------------------------------- 机器 / 升级 / GUI

# 与 Java 端 MachineLayout 一一对应，改一边必须改另一边
LAYOUT = {
    "width": 176, "height": 200, "slot": 18,
    "input_x": 34, "input_y": 17,
    "output_x": 118, "output_y": 17,
    "energy_x": 8, "energy_y": 17, "energy_w": 14, "energy_h": 52,
    "arrow_x": 94, "arrow_y": 40, "arrow_w": 20, "arrow_h": 8,
    "upgrade_x": 34, "upgrade_y": 76, "upgrade_cols": 4,
    "player_x": 8, "player_y": 116, "hotbar_y": 174,
}

MACHINES = [
    ("compressor", "苹果压缩机", "Apple Compressor", (74, 128, 196), (120, 190, 255)),
    ("enchanter", "苹果附魔台", "Apple Enchanter", (146, 96, 206), (206, 150, 255)),
    ("centrifuge", "苹果离心机", "Apple Centrifuge", (72, 158, 128), (130, 236, 180)),
    ("orchard", "苹果农场", "Apple Orchard", (86, 148, 84), (146, 232, 136)),
    ("generator", "苹果发电机", "Apple Generator", (188, 92, 70), (255, 176, 96)),
]

# 铜专线：4 氧化阶 × 5 整块形状 × 2 涂蜡
COPPER_STAGES = [
    ("", "", "", (196, 124, 88)),
    ("exposed_", "斑驳", "Exposed", (168, 138, 112)),
    ("weathered_", "锈蚀", "Weathered", (108, 152, 130)),
    ("oxidized_", "氧化", "Oxidized", (82, 162, 148)),
]
COPPER_SHAPES = [
    ("", "", ""),
    ("cut", "切制", "Cut"),
    ("polished", "磨制", "Polished"),
    ("grate", "格栅", "Grate"),
    ("chiseled", "雕纹", "Chiseled"),
    ("stairs", "楼梯", "Stairs"),
    ("slab", "台阶", "Slab"),
]

# 朝向 -> 基础旋转（与 vanilla 楼梯 blockstate 一致）
STAIRS_FACING_Y = {"east": 0, "south": 90, "west": 180, "north": 270}


def stairs_blockstate(model_ns: str) -> dict:
    """复刻 vanilla 楼梯 blockstate 的 40 个变体。"""
    variants = {}
    for facing, base in STAIRS_FACING_Y.items():
        for half in ("bottom", "top"):
            for shape in ("straight", "inner_left", "inner_right", "outer_left", "outer_right"):
                if half == "bottom":
                    delta = {"straight": 0, "inner_right": 0, "outer_right": 0,
                             "inner_left": -90, "outer_left": -90}[shape]
                else:
                    delta = {"straight": 0, "inner_left": 0, "outer_left": 0,
                             "inner_right": 90, "outer_right": 90}[shape]
                y = (base + delta) % 360
                model = model_ns
                if shape.startswith("inner"):
                    model = f"{model_ns}_inner"
                elif shape.startswith("outer"):
                    model = f"{model_ns}_outer"
                entry = {"model": model}
                if half == "top":
                    entry["x"] = 180
                if y:
                    entry["y"] = y
                if y or half == "top":
                    entry["uvlock"] = True
                variants[f"facing={facing},half={half},shape={shape}"] = entry
    return {"variants": variants}


def slab_blockstate(model_ns: str) -> dict:
    return {"variants": {
        "type=bottom": {"model": model_ns},
        "type=double": {"model": model_ns},
        "type=top": {"model": f"{model_ns}_top"},
    }}


def stairs_loot(block_id: str) -> dict:
    return {
        "type": "minecraft:block",
        "pools": [{
            "bonus_rolls": 0.0,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:{block_id}"}],
            "rolls": 1.0,
        }],
        "random_sequence": f"{MOD_ID}:blocks/{block_id}",
    }


def slab_loot(block_id: str) -> dict:
    return {
        "type": "minecraft:block",
        "pools": [{
            "bonus_rolls": 0.0,
            "entries": [{
                "type": "minecraft:item",
                "functions": [
                    {
                        "add": False,
                        "conditions": [{
                            "block": f"{MOD_ID}:{block_id}",
                            "condition": "minecraft:block_state_property",
                            "properties": {"type": "double"},
                        }],
                        "count": 2.0,
                        "function": "minecraft:set_count",
                    },
                    {"function": "minecraft:explosion_decay"},
                ],
                "name": f"{MOD_ID}:{block_id}",
            }],
            "rolls": 1.0,
        }],
        "random_sequence": f"{MOD_ID}:blocks/{block_id}",
    }

UPGRADES = [
    ("speed", "速度升级", "Speed Upgrade", (90, 200, 255), False),
    ("energy", "能量升级", "Energy Upgrade", (255, 200, 70), False),
    ("parallel", "并行升级", "Parallel Upgrade", (140, 230, 140), False),
    ("anchor", "锚定升级", "Anchor Upgrade", (170, 170, 190), False),
    ("overclock", "超频核心", "Overclock Core", (255, 120, 90), True),
    ("quantum", "量子核心", "Quantum Core", (180, 120, 255), True),
    ("fission", "裂变核心", "Fission Core", (120, 255, 180), True),
]


def make_machine_texture(base: tuple[int, int, int], accent: tuple[int, int, int]) -> Image.Image:
    s = BLOCK_SIZE
    img = Image.new("RGBA", (s, s), base + (255,))
    draw = ImageDraw.Draw(img)
    margin = max(3, s // 12)

    draw.rectangle([0, 0, s - 1, s - 1], outline=(20, 20, 24, 255), width=max(2, s // 32))
    draw.rounded_rectangle([margin, margin, s - margin - 1, s - margin - 1],
                           radius=max(2, s // 16), fill=(38, 38, 46, 255),
                           outline=accent + (255,), width=max(1, s // 32))

    rivet = max(1, s // 28)
    for x, y in ((margin + rivet + 2, margin + rivet + 2),
                 (s - margin - rivet - 3, margin + rivet + 2),
                 (margin + rivet + 2, s - margin - rivet - 3),
                 (s - margin - rivet - 3, s - margin - rivet - 3)):
        draw.ellipse([x - rivet, y - rivet, x + rivet, y + rivet], fill=(150, 150, 160, 255))

    center = s // 2
    radius = max(4, s // 7)
    draw.ellipse([center - radius, center - radius, center + radius, center + radius],
                 fill=accent + (255,), outline=(255, 255, 255, 220))
    draw.ellipse([center - radius // 3, center - radius // 3,
                  center + radius // 3, center + radius // 3], fill=(255, 255, 255, 190))
    return img


def make_core_texture(color: tuple[int, int, int], advanced: bool) -> Image.Image:
    size = ITEM_SIZE
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    center = size / 2
    outer = size * 0.44

    draw.ellipse([center - outer, center - outer, center + outer, center + outer],
                 fill=(28, 28, 34, 235), outline=(10, 10, 12, 255), width=max(2, size // 48))
    if advanced:
        draw.ellipse([center - outer + 3, center - outer + 3, center + outer - 3, center + outer - 3],
                     outline=color + (255,), width=max(2, size // 40))

    inner = outer * 0.62
    points = [(center + inner * math.cos(math.pi / 3 * i - math.pi / 6),
               center + inner * math.sin(math.pi / 3 * i - math.pi / 6)) for i in range(6)]
    draw.polygon(points, fill=color + (255,), outline=(255, 255, 255, 225))
    highlight = inner // 3
    draw.ellipse([center - highlight, center - highlight, center + highlight, center + highlight],
                 fill=(255, 255, 255, 170))
    return img


def make_shard_texture() -> Image.Image:
    size = ITEM_SIZE
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    center = size // 2
    top = (center, int(size * 0.08))
    left = (int(size * 0.20), int(size * 0.58))
    right = (int(size * 0.80), int(size * 0.58))
    bottom = (center, int(size * 0.92))
    draw.polygon([top, right, bottom, left], fill=(176, 108, 236, 255), outline=(238, 220, 255, 255))
    draw.line([top, bottom], fill=(255, 255, 255, 150), width=max(1, size // 48))
    draw.line([left, right], fill=(255, 255, 255, 90), width=max(1, size // 64))
    return img




def make_raw_apple_gold_texture() -> Image.Image:
    """粗苹果金：粗金的形状，表面带一层苹果红。"""
    base = load_base("RAW_GOLD", ITEM_SIZE).convert("RGBA")
    overlay = Image.new("RGBA", (ITEM_SIZE, ITEM_SIZE), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.ellipse([ITEM_SIZE * 0.30, ITEM_SIZE * 0.30, ITEM_SIZE * 0.70, ITEM_SIZE * 0.70],
               fill=(198, 46, 40, 150))
    od.ellipse([ITEM_SIZE * 0.38, ITEM_SIZE * 0.36, ITEM_SIZE * 0.46, ITEM_SIZE * 0.44],
               fill=(255, 176, 160, 200))
    return Image.alpha_composite(base, overlay)


ALLOY_BASE_TEXTURE = {
    "APPLE_IRON_ALLOY": "IRON_INGOT",
    "APPLE_GOLD_ALLOY": "GOLD_INGOT",
    "APPLE_COPPER_ALLOY": "COPPER_INGOT",
    "APPLE_DIAMOND_ALLOY": "DIAMOND",
    "APPLE_NETHERITE_ALLOY": "NETHERITE_INGOT",
}


def make_alloy_texture(base_name: str):
    """苹果合金：对应材料的原版贴图 + 一层苹果红。"""
    def factory() -> Image.Image:
        base = load_base(base_name, ITEM_SIZE).convert("RGBA")
        tint = Image.new("RGBA", base.size, (198, 46, 40, 110))
        return Image.alpha_composite(base, tint)
    return factory


def make_configurator_texture() -> Image.Image:
    """配置器：一把扳手轮廓 + 红色握柄。"""
    size = ITEM_SIZE
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    steel = (206, 212, 220, 255)
    dark = (96, 104, 116, 255)
    handle = (196, 84, 68, 255)
    width = max(6, size // 12)

    draw.line([(int(size * 0.30), int(size * 0.74)), (int(size * 0.66), int(size * 0.30))],
              fill=dark, width=width + 4)
    draw.line([(int(size * 0.30), int(size * 0.74)), (int(size * 0.66), int(size * 0.30))],
              fill=steel, width=width)
    draw.ellipse([int(size * 0.22), int(size * 0.66), int(size * 0.40), int(size * 0.84)],
                 fill=handle, outline=dark, width=max(2, size // 48))
    cx, cy = int(size * 0.70), int(size * 0.24)
    radius = int(size * 0.16)
    draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                 outline=steel, width=max(4, size // 20))
    draw.rectangle([cx - radius // 2, cy - radius - 2, cx + radius // 2, cy - radius + 3],
                   fill=(0, 0, 0, 0))
    return img

def make_singularity_texture() -> Image.Image:
    """绝对苹果奇点：事件视界 + 紫色吸积盘。"""
    size = ITEM_SIZE
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    c = size / 2.0

    for radius, alpha in ((0.48, 50), (0.41, 80), (0.34, 110)):
        r = size * radius
        draw.ellipse([c - r, c - r, c + r, c + r], fill=(120, 60, 220, alpha))

    r = size * 0.30
    draw.ellipse([c - r, c - r, c + r, c + r], outline=(190, 130, 255, 220),
                 width=max(2, size // 32))
    r = size * 0.36
    draw.ellipse([c - r, c - r, c + r, c + r], outline=(120, 70, 200, 130),
                 width=max(1, size // 48))
    r = size * 0.21
    draw.ellipse([c - r, c - r, c + r, c + r], fill=(8, 6, 14, 255))

    spark = max(1, size // 40)
    for i in range(8):
        angle = math.pi / 4 * i
        x = c + math.cos(angle) * size * 0.40
        y = c + math.sin(angle) * size * 0.15
        draw.ellipse([x - spark, y - spark, x + spark, y + spark], fill=(255, 240, 255, 230))
    return img


def make_chaos_apple_texture() -> Image.Image:
    """混沌苹果：原版苹果 + 坐标驱动的确定性伪随机色相，看起来就是不稳定的。"""
    size = ITEM_SIZE
    icon = load_icon("APPLE", int(size * 0.86)).convert("RGBA")
    pixels = icon.load()
    width, height = icon.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a < 8:
                continue
            hue = ((x * 7 + y * 13) % 360) / 360.0
            tint = colorsys.hsv_to_rgb(hue, 0.85, 1.0)
            pixels[x, y] = (
                int(r * 0.35 + tint[0] * 255 * 0.65),
                int(g * 0.35 + tint[1] * 255 * 0.65),
                int(b * 0.35 + tint[2] * 255 * 0.65),
                a,
            )
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(icon, ((size - width) // 2, (size - height) // 2))
    return canvas


def slot_boxes() -> list[tuple[int, int]]:
    boxes = []
    for i in range(9):
        boxes.append((LAYOUT["input_x"] + (i % 3) * LAYOUT["slot"],
                      LAYOUT["input_y"] + (i // 3) * LAYOUT["slot"]))
    for i in range(3):
        boxes.append((LAYOUT["output_x"], LAYOUT["output_y"] + i * LAYOUT["slot"]))
    for i in range(8):
        boxes.append((LAYOUT["upgrade_x"] + (i % LAYOUT["upgrade_cols"]) * LAYOUT["slot"],
                      LAYOUT["upgrade_y"] + (i // LAYOUT["upgrade_cols"]) * LAYOUT["slot"]))
    for row in range(3):
        for column in range(9):
            boxes.append((LAYOUT["player_x"] + column * LAYOUT["slot"],
                          LAYOUT["player_y"] + row * LAYOUT["slot"]))
    for column in range(9):
        boxes.append((LAYOUT["player_x"] + column * LAYOUT["slot"], LAYOUT["hotbar_y"]))
    return boxes


def make_copper_texture(color: tuple[int, int, int], shape: str, waxed: bool) -> Image.Image:
    """铜变种方块贴图：基础色 + 形状纹样；涂蜡额外加高光斜纹。"""
    size = BLOCK_SIZE
    r, g, b = color
    if waxed:
        r = min(255, int(r * 1.10) + 18)
        g = min(255, int(g * 1.10) + 18)
        b = min(255, int(b * 1.10) + 18)

    img = Image.new("RGBA", (size, size), (r, g, b, 255))
    draw = ImageDraw.Draw(img)
    margin = max(2, size // 16)
    dark = (int(r * 0.55), int(g * 0.55), int(b * 0.55), 255)
    line = max(1, size // 32)

    draw.rectangle([0, 0, size - 1, size - 1], outline=dark, width=line)

    if shape == "cut":
        mid = size // 2
        edge = (int(r * 0.70), int(g * 0.70), int(b * 0.70), 255)
        draw.line([(mid, 0), (mid, size - 1)], fill=edge, width=line)
        draw.line([(0, mid), (size - 1, mid)], fill=edge, width=line)
    elif shape == "polished":
        lighter = (min(255, r + 18), min(255, g + 18), min(255, b + 18), 255)
        draw.rectangle([margin, margin, size - margin - 1, size - margin - 1], fill=lighter)
        draw.line([(margin, margin), (size - margin - 1, margin)], fill=(255, 255, 255, 90), width=1)
    elif shape == "grate":
        step = size // 4
        hole = (int(r * 0.45), int(g * 0.45), int(b * 0.45), 255)
        for col in range(1, 4):
            for row in range(1, 4):
                x0 = col * step - step // 3
                y0 = row * step - step // 3
                draw.rectangle([x0, y0, x0 + step // 2, y0 + step // 2], fill=hole)
    elif shape == "chiseled":
        lighter = (min(255, r + 22), min(255, g + 22), min(255, b + 22), 255)
        draw.polygon([(size // 2, margin), (size - margin, size // 2),
                      (size // 2, size - margin), (margin, size // 2)], fill=lighter)
        draw.line([(size // 2, margin), (size // 2, size - margin)],
                  fill=(int(r * 0.6), int(g * 0.6), int(b * 0.6), 255), width=1)

    if waxed:
        step = max(4, size // 8)
        for offset in range(0, size, step):
            draw.line([(0, offset), (offset, 0)], fill=(255, 255, 255, 26), width=1)

    return img


def make_gui_texture() -> Image.Image:
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    width, height = LAYOUT["width"], LAYOUT["height"]

    draw.rectangle([0, 0, width - 1, height - 1], fill=(198, 198, 198, 255))
    draw.rectangle([0, 0, width - 1, height - 1], outline=(52, 52, 58, 255), width=2)
    draw.rectangle([3, 3, width - 4, height - 4], outline=(160, 160, 165, 255), width=1)

    for x, y in slot_boxes():
        draw.rectangle([x - 1, y - 1, x + 16, y + 16], fill=(139, 139, 139, 255),
                       outline=(55, 55, 55, 255))
        draw.rectangle([x, y, x + 15, y + 15], fill=(96, 96, 96, 255))

    ex, ey = LAYOUT["energy_x"], LAYOUT["energy_y"]
    ew, eh = LAYOUT["energy_w"], LAYOUT["energy_h"]
    draw.rectangle([ex, ey, ex + ew - 1, ey + eh - 1], fill=(40, 40, 44, 255),
                   outline=(55, 55, 55, 255))

    ax, ay = LAYOUT["arrow_x"], LAYOUT["arrow_y"]
    aw, ah = LAYOUT["arrow_w"], LAYOUT["arrow_h"]
    draw.rectangle([ax, ay, ax + aw - 1, ay + ah - 1], fill=(70, 70, 70, 255),
                   outline=(55, 55, 55, 255))

    title_w = 96
    draw.line([LAYOUT["player_x"], LAYOUT["player_y"] - 13,
               LAYOUT["player_x"] + title_w, LAYOUT["player_y"] - 13], fill=(120, 120, 125, 255))
    return img


# --------------------------------------------------------------------------- 数据

def unit_id(material: str, tier: int) -> str:
    return f"compressed_{tier}x_{material}"


def unit_block_id(material: str, tier: int) -> str:
    return f"{unit_id(material, tier)}_block"


def raw_unit_id(material: str, tier: int) -> str:
    return f"compressed_{tier}x_raw_{material}"


def raw_block_id(material: str, tier: int) -> str:
    return f"{raw_unit_id(material, tier)}_block"


def zh_unit(zh: str, tier: int) -> str:
    return f"{ZH_TIERS[tier]}重压缩{zh}"


def zh_raw(zh: str, tier: int) -> str:
    stem = zh[:-1] if zh.endswith("锭") else zh
    return f"{ZH_TIERS[tier]}重压缩粗{stem}"


def num_prefix(tier: int) -> str:
    return f"{tier}x "


def en_raw(en: str) -> str:
    return en[:-5].strip() if en.endswith("Ingot") else en


# --------------------------------------------------------------------------- 主流程

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true", help="先清空已生成的资源目录")
    args = parser.parse_args()

    tex_item = RES / "textures" / "item"
    tex_block = RES / "textures" / "block"
    models_item = RES / "models" / "item"
    models_block = RES / "models" / "block"
    blockstates = RES / "blockstates"
    lang_dir = RES / "lang"
    gui_dir = RES / "textures" / "gui"
    loot_dir = ROOT / "src" / "main" / "resources" / "data" / "siao" / "loot_table" / "blocks"

    if args.clean:
        for d in (tex_item, tex_block, models_item, models_block, blockstates, gui_dir, loot_dir):
            if d.exists():
                shutil.rmtree(d)

    for d in (tex_item, tex_block, models_item, models_block, blockstates, lang_dir, gui_dir, loot_dir):
        d.mkdir(parents=True, exist_ok=True)

    zh_lang: dict[str, str] = {
        "itemGroup.siao.main": "超级疯狂的苹果配方超载",
        "tooltip.siao.equivalent": "相当于 %s 个%s",
        "prefix.siao.enchanted": "附魔",
        "side.siao.both": "双向",
        "side.siao.input": "仅输入",
        "side.siao.output": "仅输出",
        "side.siao.none": "封闭",
        "tooltip.siao.side_button": "点击切换「你站的这一面」的物品通行规则",
    }
    en_lang: dict[str, str] = {
        "itemGroup.siao.main": "SuperInsaneAppleRecipeOverload",
        "tooltip.siao.equivalent": "Equivalent to %s \u00d7 %s",
        "prefix.siao.enchanted": "Enchanted ",
        "side.siao.both": "Both",
        "side.siao.input": "Input only",
        "side.siao.output": "Output only",
        "side.siao.none": "Closed",
        "tooltip.siao.side_button": "Cycle the item access on the face you are standing at",
    }

    def write_json(path: Path, data: dict) -> None:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def emit_item(texture_id: str, base_name: str, tier: int, zh: str, en: str) -> None:
        make_item_texture(base_name, tier).save(tex_item / f"{texture_id}.png")
        write_json(models_item / f"{texture_id}.json", {
            "parent": "item/generated",
            "textures": {"layer0": f"{MOD_ID}:item/{texture_id}"},
        })
        zh_lang[f"item.{MOD_ID}.{texture_id}"] = zh
        en_lang[f"item.{MOD_ID}.{texture_id}"] = en

    def emit_block(block_id: str, color: tuple[int, int, int], tier: int, zh: str, en: str) -> None:
        make_block_texture(color, tier).save(tex_block / f"{block_id}.png")
        write_json(models_block / f"{block_id}.json", {
            "parent": "block/cube_all",
            "textures": {"all": f"{MOD_ID}:block/{block_id}"},
        })
        write_json(blockstates / f"{block_id}.json", {
            "variants": {"": {"model": f"{MOD_ID}:block/{block_id}"}},
        })
        write_json(models_item / f"{block_id}.json", {
            "parent": f"{MOD_ID}:block/{block_id}",
        })
        zh_lang[f"block.{MOD_ID}.{block_id}"] = zh
        en_lang[f"block.{MOD_ID}.{block_id}"] = en
        block_ids.append(block_id)

    block_ids: list[str] = []
    counts = {"item": 0, "block": 0}

    for mat_id, zh, en, item_base, block_base, has_raw in MATERIALS:
        color = average_color(load_base(block_base or item_base, 64))
        zh_lang[f"material.siao.{mat_id}"] = zh
        en_lang[f"material.siao.{mat_id}"] = en
        for tier in range(1, MAX_TIER + 1):
            emit_item(unit_id(mat_id, tier), item_base, tier,
                      zh_unit(zh, tier), f"{num_prefix(tier)}Compressed {en}")
            counts["item"] += 1
            emit_block(unit_block_id(mat_id, tier), color, tier,
                       zh_unit(zh, tier) + "块", f"{num_prefix(tier)}Compressed {en} Block")
            counts["block"] += 1

        if has_raw:
            override = RAW_NAME.get(mat_id)
            if override:
                raw_zh, raw_en = override
            else:
                raw_zh = zh[:-1] if zh.endswith("锭") else zh
                raw_en = f"Raw {en_raw(en)}"
            raw_color = average_color(load_base(RAW_BLOCK_BASE[mat_id], 64))
            for tier in range(1, MAX_TIER + 1):
                emit_item(raw_unit_id(mat_id, tier), RAW_BASE[mat_id], tier,
                          f"{ZH_TIERS[tier]}重压缩{raw_zh}", f"{num_prefix(tier)}Compressed {raw_en}")
                counts["item"] += 1
                emit_block(raw_block_id(mat_id, tier), raw_color, tier,
                           f"{ZH_TIERS[tier]}重压缩{raw_zh}块", f"{num_prefix(tier)}Compressed {raw_en} Block")
                counts["block"] += 1

    # ---- C 线：所有压缩方块的氧化变种 --------------------------------------
    OX_STAGES = [(1, "exposed_", "斑驳", "Exposed", (150, 178, 152)),
                 (2, "weathered_", "锈蚀", "Weathered", (110, 160, 140)),
                 (3, "oxidized_", "氧化", "Oxidized", (82, 162, 148))]
    ox_next = {}
    wax_map = {}
    ox_blocks = 0
    for mat_id, zh, en, item_base, block_base, has_raw in MATERIALS:
        for tier in range(1, MAX_TIER + 1):
            for raw in ([False, True] if has_raw else [False]):
                base_id = raw_block_id(mat_id, tier) if raw else unit_block_id(mat_id, tier)
                zh_stem = (zh_raw(zh, tier) if raw else zh_unit(zh, tier)) + "块"
                en_stem = (f"{num_prefix(tier)}Compressed {en_raw(en)}" if raw
                           else f"{num_prefix(tier)}Compressed {en}") + " Block"
                # 0 阶涂蜡
                emit_block(f"{base_id}_waxed", average_color(load_base(block_base or item_base, 64)),
                           tier, "涂蜡" + zh_stem, "Waxed " + en_stem)
                ox_blocks += 1
                wax_map[f"{MOD_ID}:{base_id}"] = f"{MOD_ID}:{base_id}_waxed"
                prev = base_id
                for stage, prefix, zh_s, en_s, color in OX_STAGES:
                    sid = f"{prefix}{base_id}"
                    emit_block(sid, color, tier, zh_s + zh_stem, en_s + " " + en_stem)
                    emit_block(f"{sid}_waxed", color, tier, "涂蜡" + zh_s + zh_stem,
                               "Waxed " + en_s + " " + en_stem)
                    ox_blocks += 2
                    ox_next[f"{MOD_ID}:{prev}"] = f"{MOD_ID}:{sid}"
                    wax_map[f"{MOD_ID}:{sid}"] = f"{MOD_ID}:{sid}_waxed"
                    prev = sid

    # 合并进 data map（保留铜专线已有的条目）
    for map_path, pairs in (
        (ROOT / "src/main/resources/data/neoforge/data_maps/block/oxidizables.json", ox_next),
        (ROOT / "src/main/resources/data/neoforge/data_maps/block/waxables.json", wax_map),
    ):
        existing = json.loads(map_path.read_text(encoding="utf-8")) if map_path.exists() else {"values": {}}
        existing.setdefault("values", {}).update(pairs)
        write_json(map_path, existing)
    # ---- 机器 ---------------------------------------------------------------
    for machine_id, zh, en, base, accent in MACHINES:
        block_id = f"apple_{machine_id}"
        make_machine_texture(base, accent).save(tex_block / f"{block_id}.png")
        write_json(models_block / f"{block_id}.json", {
            "parent": "block/cube_all",
            "textures": {"all": f"{MOD_ID}:block/{block_id}"},
        })
        write_json(blockstates / f"{block_id}.json", {
            "variants": {"": {"model": f"{MOD_ID}:block/{block_id}"}},
        })
        write_json(models_item / f"{block_id}.json", {"parent": f"{MOD_ID}:block/{block_id}"})
        zh_lang[f"block.{MOD_ID}.{block_id}"] = zh
        en_lang[f"block.{MOD_ID}.{block_id}"] = en
        block_ids.append(block_id)
        counts["block"] += 1

    # ---- 升级物品 -----------------------------------------------------------
    for upgrade_id, zh, en, color, advanced in UPGRADES:
        item_id = f"{upgrade_id}_upgrade"
        make_core_texture(color, advanced).save(tex_item / f"{item_id}.png")
        write_json(models_item / f"{item_id}.json", {
            "parent": "item/generated",
            "textures": {"layer0": f"{MOD_ID}:item/{item_id}"},
        })
        zh_lang[f"item.{MOD_ID}.{item_id}"] = zh
        en_lang[f"item.{MOD_ID}.{item_id}"] = en
        counts["item"] += 1

    # ---- 奇点碎片 / 奇点 / 混沌苹果 -----------------------------------------
    specials = [
        ("configurator", "苹果配置器", "Apple Configurator", make_configurator_texture),
        ("raw_apple_gold", "粗苹果金", "Raw Apple Gold", make_raw_apple_gold_texture),
    ("apple_iron_alloy", "苹果铁合金", "Apple Iron Alloy", make_alloy_texture("IRON_INGOT")),
    ("apple_gold_alloy", "苹果金合金", "Apple Gold Alloy", make_alloy_texture("GOLD_INGOT")),
    ("apple_copper_alloy", "苹果铜合金", "Apple Copper Alloy", make_alloy_texture("COPPER_INGOT")),
    ("apple_diamond_alloy", "苹果钻石合金", "Apple Diamond Alloy", make_alloy_texture("DIAMOND")),
    ("apple_netherite_alloy", "苹果下界合金", "Apple Netherite Alloy", make_alloy_texture("NETHERITE_INGOT")),
    ("singularity_shard", "奇点碎片", "Singularity Shard", make_shard_texture),
        ("singularity", "绝对苹果奇点", "Absolute Apple Singularity", make_singularity_texture),
        ("chaos_apple", "混沌苹果", "Chaos Apple", make_chaos_apple_texture),
    ]
    for item_id, zh, en, factory in specials:
        factory().save(tex_item / f"{item_id}.png")
        write_json(models_item / f"{item_id}.json", {
            "parent": "item/generated",
            "textures": {"layer0": f"{MOD_ID}:item/{item_id}"},
        })
        zh_lang[f"item.{MOD_ID}.{item_id}"] = zh
        en_lang[f"item.{MOD_ID}.{item_id}"] = en
        counts["item"] += 1

    # ---- 铜专线：4 氧化阶 × 5 整块形状 × 2 涂蜡 ------------------------------
    # (stage_prefix, shape_suffix, waxed) -> block_id；未氧化+基础+未涂蜡这一格
    # 已经被阶段 1 的压缩块占用了，直接复用那个 ID
    copper_ids: dict[tuple[str, str, bool], str] = {}
    copper_jobs = []
    for stage_prefix, _, _, color in COPPER_STAGES:
        for shape_suffix, _, _ in COPPER_SHAPES:
            for waxed in (False, True):
                if stage_prefix == "" and shape_suffix == "" and not waxed:
                    copper_ids[(stage_prefix, shape_suffix, False)] = unit_block_id("copper", 1)
                    continue
                copper_jobs.append((stage_prefix, shape_suffix, waxed, color))

    special_loot: list[tuple[str, str]] = []
    for stage_prefix, shape_suffix, waxed, color in copper_jobs:
        block_id = f"{stage_prefix}compressed_1x_copper"
        if shape_suffix:
            block_id += f"_{shape_suffix}"
        if waxed:
            block_id += "_waxed"
        copper_ids[(stage_prefix, shape_suffix, waxed)] = block_id

        is_stairs = shape_suffix == "stairs"
        is_slab = shape_suffix == "slab"
        texture_shape = "" if (is_stairs or is_slab) else shape_suffix
        make_copper_texture(color, texture_shape, waxed).save(tex_block / f"{block_id}.png")

        model_ns = f"{MOD_ID}:block/{block_id}"
        texture_ns = f"{MOD_ID}:block/{block_id}"
        if is_stairs:
            for suffix, parent in (("", "minecraft:block/stairs"),
                                   ("_inner", "minecraft:block/inner_stairs"),
                                   ("_outer", "minecraft:block/outer_stairs")):
                write_json(models_block / f"{block_id}{suffix}.json", {
                    "parent": parent,
                    "textures": {"bottom": texture_ns, "top": texture_ns, "side": texture_ns},
                })
            write_json(blockstates / f"{block_id}.json", stairs_blockstate(model_ns))
            special_loot.append((block_id, "stairs"))
        elif is_slab:
            for suffix, parent in (("", "minecraft:block/slab"),
                                   ("_top", "minecraft:block/slab_top")):
                write_json(models_block / f"{block_id}{suffix}.json", {
                    "parent": parent,
                    "textures": {"bottom": texture_ns, "top": texture_ns, "side": texture_ns},
                })
            write_json(blockstates / f"{block_id}.json", slab_blockstate(model_ns))
            special_loot.append((block_id, "slab"))
        else:
            write_json(models_block / f"{block_id}.json", {
                "parent": "block/cube_all",
                "textures": {"all": texture_ns},
            })
            write_json(blockstates / f"{block_id}.json", {
                "variants": {"": {"model": model_ns}},
            })
            block_ids.append(block_id)

        write_json(models_item / f"{block_id}.json", {"parent": model_ns})

        stage_zh = next(zh for pfx, zh, _, _ in COPPER_STAGES if pfx == stage_prefix)
        stage_en = next(en for pfx, _, en, _ in COPPER_STAGES if pfx == stage_prefix)
        shape_zh = next(zh for sfx, zh, _ in COPPER_SHAPES if sfx == shape_suffix)
        shape_en = next(en for sfx, _, en in COPPER_SHAPES if sfx == shape_suffix)
        zh_lang[f"block.{MOD_ID}.{block_id}"] = (
            ("涂蜡" if waxed else "") + stage_zh + shape_zh + "一重压缩铜锭块")
        en_lang[f"block.{MOD_ID}.{block_id}"] = (
            ("Waxed " if waxed else "")
            + (stage_en + " " if stage_en else "")
            + (shape_en + " " if shape_en else "")
            + "1x Compressed Copper Ingot Block")
        counts["block"] += 1

    # ---- data map：氧化 / 涂蜡（命名空间必须是 neoforge）--------------------
    data_maps = ROOT / "src" / "main" / "resources" / "data" / "neoforge" / "data_maps" / "block"
    data_maps.mkdir(parents=True, exist_ok=True)

    oxidizables: dict[str, dict] = {}
    waxables: dict[str, dict] = {}
    stage_prefixes = [pfx for pfx, _, _, _ in COPPER_STAGES]
    for index, stage_prefix in enumerate(stage_prefixes):
        for shape_suffix, _, _ in COPPER_SHAPES:
            current = copper_ids[(stage_prefix, shape_suffix, False)]
            if index + 1 < len(stage_prefixes):
                nxt = copper_ids[(stage_prefixes[index + 1], shape_suffix, False)]
                oxidizables[f"{MOD_ID}:{current}"] = {"next_oxidation_stage": f"{MOD_ID}:{nxt}"}
            waxed_id = copper_ids.get((stage_prefix, shape_suffix, True))
            if waxed_id:
                waxables[f"{MOD_ID}:{current}"] = {"waxed": f"{MOD_ID}:{waxed_id}"}

    # 合并而不是覆盖：C 线已经把全部材料的氧化链写进去了
    for map_path, pairs in ((data_maps / "oxidizables.json", oxidizables),
                            (data_maps / "waxables.json", waxables)):
        existing = json.loads(map_path.read_text(encoding="utf-8")) if map_path.exists() else {"values": {}}
        existing.setdefault("values", {}).update(pairs)
        write_json(map_path, existing)

    # ---- 方块标签：可被镐加速/可掉落 ----------------------------------------
    # ⚠️ 带 requiresCorrectToolForDrops 的方块如果不在 #minecraft:mineable/pickaxe 里，
    # 任何工具都算不上「正确工具」，结果是挖了什么都不掉。
    tags_dir = ROOT / "src" / "main" / "resources" / "data" / "minecraft" / "tags" / "block" / "mineable"
    tags_dir.mkdir(parents=True, exist_ok=True)
    pickaxe_blocks = sorted({f"{MOD_ID}:{bid}" for bid in block_ids}
                            | {f"{MOD_ID}:{bid}" for bid, _ in special_loot})
    write_json(tags_dir / "pickaxe.json", {"replace": False, "values": pickaxe_blocks})

    # ---- 机器 GUI 底图 ------------------------------------------------------
    make_gui_texture().save(gui_dir / "apple_machine.png")

    # ---- 战利品表（没有它，挖方块什么都不掉）--------------------------------
    for block_id in block_ids:
        write_json(loot_dir / f"{block_id}.json", {
            "type": "minecraft:block",
            "pools": [{
                "rolls": 1.0,
                "bonus_rolls": 0.0,
                "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:{block_id}"}],
                "conditions": [{"condition": "minecraft:survives_explosion"}],
            }],
        })

    # 楼梯/台阶的掉落规则不同（台阶双份要掉 2 个）
    for block_id, kind in special_loot:
        payload = stairs_loot(block_id) if kind == "stairs" else slab_loot(block_id)
        write_json(loot_dir / f"{block_id}.json", payload)

    write_json(lang_dir / "zh_cn.json", dict(sorted(zh_lang.items())))
    write_json(lang_dir / "en_us.json", dict(sorted(en_lang.items())))

    print(f"物品贴图/模型 : {counts['item']}")
    print(f"方块贴图/模型 : {counts['block']}")
    print(f"战利品表      : {len(block_ids) + len(special_loot)}")
    print(f"lang 条目     : {len(zh_lang)}")


if __name__ == "__main__":
    main()
