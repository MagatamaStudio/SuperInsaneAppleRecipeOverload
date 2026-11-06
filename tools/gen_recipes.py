#!/usr/bin/env python3
"""SIAO 配方生成器。

生成 data/siao/recipe/ 下的合成配方，全部走自定义类型 `siao:compression`
（产物在 assemble 里现算，才能让附魔组件穿透压缩链）：
  * 压缩  9 × tier(N)          -> 1 × tier(N+1)   （N=0 的输入是原版物品）
  * 拆分  1 × tier(N+1)        -> 9 × tier(N)
  * 装块  9 × tier(N)          -> 1 × block(N)
  * 拆块  1 × block(N)         -> 9 × tier(N)

用法： python tools/gen_recipes.py [--clean]
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "data" / "siao" / "recipe"

MOD_ID = "siao"
MAX_TIER = 9

# 材料 id -> 原版基础物品
VANILLA_BASE = {
    "iron": "minecraft:iron_ingot",
    "gold": "minecraft:gold_ingot",
    "copper": "minecraft:copper_ingot",
    "netherite": "minecraft:netherite_ingot",
    "diamond": "minecraft:diamond",
    "emerald": "minecraft:emerald",
    "lapis": "minecraft:lapis_lazuli",
    "amethyst": "minecraft:amethyst_shard",
    "quartz": "minecraft:quartz",
    "coal": "minecraft:coal",
    "redstone": "minecraft:redstone",
    "apple": "minecraft:apple",
}

RAW_BASE = {
    "iron": "minecraft:raw_iron",
    "gold": "minecraft:raw_gold",
    "copper": "minecraft:raw_copper",
    # 苹果线的粗形态第 0 重是自制物品（原构想 L4 的「粗苹果金」）
    "apple": "siao:raw_apple_gold",
}


def item(material: str, tier: int, raw: bool = False) -> str:
    if tier == 0:
        return RAW_BASE[material] if raw else VANILLA_BASE[material]
    prefix = "raw_" if raw else ""
    return f"{MOD_ID}:compressed_{tier}x_{prefix}{material}"


def block(material: str, tier: int, raw: bool = False) -> str:
    prefix = "raw_" if raw else ""
    return f"{MOD_ID}:compressed_{tier}x_{prefix}{material}_block"


def compression(ingredient: str, result: str, result_count: int, count: int,
                group: str, enchant: str) -> dict:
    """自定义配方类型 siao:compression。

    产物在 assemble() 里现算，所以 result 只是「展示用」的基础产物；
    enchant 决定附魔怎么处理：
      * preserve —— 保留输入的附魔（压缩链上行）
      * reject   —— 附魔输入直接不匹配（拆分/装块/拆块，堵复制漏洞）
    """
    return {
        "type": "siao:compression",
        "group": group,
        "category": "misc",
        "ingredient": {"item": ingredient},
        "result": {"id": result, "count": result_count},
        "count": count,
        "enchant": enchant,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args()

    if args.clean and OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)

    written = 0

    def emit(recipe_id: str, data: dict) -> None:
        nonlocal written
        (OUT / f"{recipe_id}.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        written += 1

    lines = [("", False)] + [("raw_", True) for _ in [0]]
    # 普通线 + 粗形态线（粗形态只对铁/金/铜）
    for material in VANILLA_BASE:
        for raw in (False, True):
            if raw and material not in RAW_BASE:
                continue
            tag = "raw_" if raw else ""
            group = f"compressed_{material}{'_raw' if raw else ''}"

            for tier in range(1, MAX_TIER + 1):
                prev = item(material, tier - 1, raw)
                cur = item(material, tier, raw)
                # 压缩：9 个上一级 -> 1 个本级（附魔跟着走）
                emit(f"{tag}compress_{material}_{tier}x",
                     compression(prev, cur, 1, 9, group, "preserve"))
                # 拆分：1 个本级 -> 9 个上一级（附魔物品拒绝匹配，防止越拆越多）
                emit(f"{tag}decompress_{material}_{tier}x",
                     compression(cur, prev, 9, 1, group, "preserve"))
                # 装块 / 拆块（方块不参与附魔，同样拒绝）
                blk = block(material, tier, raw)
                emit(f"{tag}pack_{material}_{tier}x_block",
                     compression(cur, blk, 1, 9, group, "preserve"))
                emit(f"{tag}unpack_{material}_{tier}x_block",
                     compression(blk, cur, 9, 1, group, "preserve"))

    # ---- 机器与升级（产物固定，用普通 shaped；只有压缩链需要自定义类型）----
    def shaped(recipe_id: str, pattern: list[str], keys: dict[str, str], result: str,
               count: int = 1) -> None:
        emit(recipe_id, {
            "type": "minecraft:crafting_shaped",
            "category": "misc",
            "pattern": pattern,
            "key": {symbol: {"item": item_id} for symbol, item_id in keys.items()},
            "result": {"id": result, "count": count},
        })

    # ---- 苹果线的基础物品（L4 粗苹果金 / L6 苹果与其他锭合成）----
    emit("craft_raw_apple_gold", {
        "type": "minecraft:crafting_shapeless",
        "category": "misc",
        "ingredients": [{"item": "minecraft:apple"}, {"item": "minecraft:raw_gold"}],
        "result": {"id": f"{MOD_ID}:raw_apple_gold", "count": 1},
    })
    emit("craft_apple_ingot", {
        "type": "minecraft:crafting_shapeless",
        "category": "misc",
        "ingredients": [{"item": "minecraft:apple"}, {"item": "minecraft:gold_ingot"}],
        "result": {"id": "minecraft:apple", "count": 2},
    })
    emit("smelt_raw_apple_gold", {
        "type": "minecraft:smelting",
        "category": "misc",
        "ingredient": {"item": f"{MOD_ID}:raw_apple_gold"},
        "result": {"id": "minecraft:apple", "count": 1},
        "experience": 0.35,
        "cookingtime": 200,
    })

    shaped("craft_apple_compressor",
           ["III", "IPI", "III"],
           {"I": "minecraft:iron_ingot", "P": "minecraft:piston"},
           f"{MOD_ID}:apple_compressor")
    shaped("craft_apple_enchanter",
           ["LLL", "LEL", "LLL"],
           {"L": "minecraft:lapis_lazuli", "E": "minecraft:enchanting_table"},
           f"{MOD_ID}:apple_enchanter")
    shaped("craft_apple_centrifuge",
           ["III", "IHI", "III"],
           {"I": "minecraft:iron_ingot", "H": "minecraft:hopper"},
           f"{MOD_ID}:apple_centrifuge")
    shaped("craft_apple_orchard",
           ["SIS", "IHI", "SIS"],
           {"S": "minecraft:oak_sapling", "I": "minecraft:iron_ingot", "H": "minecraft:hopper"},
           f"{MOD_ID}:apple_orchard")
    shaped("craft_apple_generator",
           ["IRI", "RFR", "IRI"],
           {"I": "minecraft:iron_ingot", "R": "minecraft:redstone", "F": "minecraft:furnace"},
           f"{MOD_ID}:apple_generator")
    shaped("craft_configurator",
           ["I I", " R ", "I I"],
           {"I": "minecraft:iron_ingot", "R": "minecraft:redstone"},
           f"{MOD_ID}:configurator")

    shaped("craft_speed_upgrade", ["SSS", "SRS", "SSS"],
           {"S": "minecraft:sugar", "R": "minecraft:redstone"},
           f"{MOD_ID}:speed_upgrade")
    shaped("craft_energy_upgrade", ["RRR", "RGR", "RRR"],
           {"R": "minecraft:redstone", "G": "minecraft:gold_ingot"},
           f"{MOD_ID}:energy_upgrade")
    shaped("craft_parallel_upgrade", ["III", "IRI", "III"],
           {"I": "minecraft:iron_ingot", "R": "minecraft:redstone"},
           f"{MOD_ID}:parallel_upgrade")
    shaped("craft_anchor_upgrade", ["OOO", "OEO", "OOO"],
           {"O": "minecraft:obsidian", "E": "minecraft:ender_pearl"},
           f"{MOD_ID}:anchor_upgrade")
    shaped("craft_overclock_core", ["GGG", "GSG", "GGG"],
           {"G": "minecraft:gold_ingot", "S": f"{MOD_ID}:singularity_shard"},
           f"{MOD_ID}:overclock_upgrade")
    shaped("craft_quantum_core", ["NNN", "NSN", "NNN"],
           {"N": "minecraft:netherite_ingot", "S": f"{MOD_ID}:singularity_shard"},
           f"{MOD_ID}:quantum_upgrade")
    shaped("craft_fission_core", ["AAA", "ASA", "AAA"],
           {"A": "minecraft:amethyst_shard", "S": f"{MOD_ID}:singularity_shard"},
           f"{MOD_ID}:fission_upgrade")

    # ---- 阶段 6：奇点 / 混沌苹果 / 终极木棍 ---------------------------------
    # 绝对苹果奇点 = 九重压缩苹果锭 ×1（387,420,489 个苹果），方案 A 硬核
    emit("craft_singularity", compression(
        f"{MOD_ID}:compressed_9x_apple", f"{MOD_ID}:singularity", 1, 1, "singularity", "reject"))

    # 终极木棍：原构想 L13 —— 附魔一重压缩粗苹果金**块** → 1 根木棍。
    emit("ultimate_stick", compression(
        f"{MOD_ID}:compressed_1x_raw_apple_block", "minecraft:stick", 1, 1, "ultimate", "require"))

    # 混沌苹果：原构想 L11 —— 附魔一重压缩粗苹果金 + 附魔一重压缩苹果锭。
    # 产物带随机组件，所以是"特殊配方"（无 ingredient 声明，匹配逻辑在 Java 里）
    emit("chaos_apple", {"type": f"{MOD_ID}:chaos_apple"})

    # ---- 铜：楼梯 / 台阶（各 8 个：4 氧化阶 × 涂蜡）-------------------------
    def copper_base_id(stage: str, waxed: bool) -> str:
        if stage == "" and not waxed:
            return "compressed_1x_copper_block"
        block_id = f"{stage}compressed_1x_copper"
        return block_id + "_waxed" if waxed else block_id

    for stage in ("", "exposed_", "weathered_", "oxidized_"):
        for waxed in (False, True):
            suffix = "_waxed" if waxed else ""
            source = f"{MOD_ID}:{copper_base_id(stage, waxed)}"
            stairs_id = f"{stage}compressed_1x_copper_stairs{suffix}"
            slab_id = f"{stage}compressed_1x_copper_slab{suffix}"
            shaped(f"craft_{stairs_id}",
                   ["B  ", "BB ", "BBB"], {"B": source}, f"{MOD_ID}:{stairs_id}", 4)
            shaped(f"craft_{slab_id}",
                   ["BBB"], {"B": source}, f"{MOD_ID}:{slab_id}", 6)

            # 四种整块形状：2x2 基础块 -> 4 个该形状。
            # 之前只生成了楼梯和台阶，cut/polished/grate/chiseled 32 个方块
            # 根本没有合成路径（只能靠创造模式拿）
            for shape_id, pattern in (
                ("cut", ["BB", "BB"]),
                ("polished", ["BB", "BB"]),
                ("grate", ["BB", "BB"]),
                ("chiseled", ["BB", "BB"]),
            ):
                target = f"{stage}compressed_1x_copper_{shape_id}{suffix}"
                shaped(f"craft_{target}", pattern, {"B": source}, f"{MOD_ID}:{target}", 4)

    print(f"配方文件: {written}")


if __name__ == "__main__":
    main()
