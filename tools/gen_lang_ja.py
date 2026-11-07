"""从 zh_cn 生成 ja_jp。

中文名是严格按「前缀 + 形状 + 氧化阶 + 重数 + 材料 + 后缀」拼出来的，
所以按词元替换就能得到结构正确的日文名，不需要逐条手翻一万多条。

**替换顺序很重要**：先长后短（`下界合金` 要排在 `合金` 前面，
`粗苹果金` 要排在 `苹果` 前面），否则会被拆错。
"""

import json
from pathlib import Path

LANG_DIR = Path("src/main/resources/assets/siao/lang")

# 词元替换表，按顺序应用（先长后短）
TOKENS = [
    # ---- 材料（先长后短）----
    ("苹果下界合金", "リンゴネザライト合金"),
    ("苹果钻石合金", "リンゴダイヤモンド合金"),
    ("苹果铁合金", "リンゴ鉄合金"),
    ("苹果金合金", "リンゴ金合金"),
    ("苹果铜合金", "リンゴ銅合金"),
    ("粗苹果金", "粗いリンゴ金"),
    ("下界合金锭", "ネザライトインゴット"),
    ("下界合金", "ネザライト"),
    ("下界石英", "ネザー水晶"),
    ("苹果锭", "リンゴインゴット"),
    ("铁锭", "鉄インゴット"),
    ("金锭", "金インゴット"),
    ("铜锭", "銅インゴット"),
    ("绿宝石", "エメラルド"),
    ("青金石", "ラピスラズリ"),
    ("紫水晶", "アメジスト"),
    ("钻石", "ダイヤモンド"),
    ("红石", "レッドストーン"),
    ("煤", "石炭"),
    ("粗铁", "粗い鉄"),
    ("粗金", "粗い金"),
    ("粗铜", "粗い銅"),
    # ---- 形状 ----
    ("楼梯", "階段"),
    ("台阶", "ハーフブロック"),
    ("雕纹", "模様入り"),
    ("格栅", "格子"),
    ("切制", "切った"),
    ("磨制", "磨かれた"),
    # ---- 氧化阶 ----
    ("斑驳", "露出した"),
    ("锈蚀", "風化した"),
    ("氧化", "酸化した"),
    ("涂蜡", "蝋付けされた"),
    # ---- 通用词 ----
    ("压缩", "圧縮"),
    ("块", "ブロック"),
    ("附魔", "エンチャントされた"),
]

# 非组合式条目：手写
DIRECT = {
    # 标签页
    "itemGroup.siao.main": "超狂気リンゴレシピ過負荷",
    "itemGroup.siao.blocks": "圧縮ブロック",
    "itemGroup.siao.cut": "切ったブロック",
    "itemGroup.siao.polished": "磨かれたブロック",
    "itemGroup.siao.grate": "格子ブロック",
    "itemGroup.siao.chiseled": "模様入りブロック",
    "itemGroup.siao.stairs": "階段とハーフブロック",
    # 机器
    "block.siao.apple_compressor": "リンゴ圧縮機",
    "block.siao.apple_enchanter": "リンゴエンチャント台",
    "block.siao.apple_centrifuge": "リンゴ遠心分離機",
    "block.siao.apple_orchard": "リンゴ農園",
    "block.siao.apple_generator": "リンゴ発電機",
    # 特殊物品
    "item.siao.configurator": "リンゴコンフィギュレーター",
    "item.siao.singularity": "絶対リンゴ特異点",
    "item.siao.singularity_shard": "特異点の破片",
    "item.siao.chaos_apple": "混沌リンゴ",
    "item.siao.raw_apple_gold": "粗いリンゴ金",
    # 升级
    "item.siao.speed_upgrade": "速度アップグレード",
    "item.siao.energy_upgrade": "エネルギーアップグレード",
    "item.siao.parallel_upgrade": "並列アップグレード",
    "item.siao.anchor_upgrade": "アンカーアップグレード",
    "item.siao.overclock_upgrade": "オーバークロックコア",
    "item.siao.quantum_upgrade": "量子コア",
    "item.siao.fission_upgrade": "核分裂コア",
    # 界面
    "prefix.siao.enchanted": "エンチャントされた",
    "side.siao.both": "双方向",
    "side.siao.input": "入力のみ",
    "side.siao.output": "出力のみ",
    "side.siao.none": "閉鎖",
    "tooltip.siao.equivalent": "%s 個の%sに相当",
    "tooltip.siao.packing": "9 個でブロックに梱包可能：圧縮機をスニーク右クリックで梱包モード切替",
    "jei.siao.compression": "圧縮（機械）",
}

# 进度
ADVANCEMENTS = {
    "root": ("超狂気リンゴレシピ過負荷", "リンゴを九個で一つに圧縮する"),
    "first_compress": ("一重圧縮", "リンゴ圧縮機を作り、最初の一重圧縮を作る"),
    "tier3": ("三重圧縮", "三重まで圧縮する — 729 個のリンゴ"),
    "tier9": ("九重圧縮", "九重まで圧縮する — 387,420,489 個のリンゴ"),
    "machine": ("産業の始まり", "最初の機械を作る"),
    "enchant": ("エンチャント圧縮", "圧縮単位にエンチャントを付ける"),
    "singularity": ("絶対リンゴ特異点", "九重圧縮から特異点を作る"),
}


def translate(zh: str) -> str:
    out = zh
    for zh_token, ja_token in TOKENS:
        out = out.replace(zh_token, ja_token)
    return out


def main() -> None:
    zh = json.loads((LANG_DIR / "zh_cn.json").read_text(encoding="utf-8"))
    ja = {}

    for key, value in zh.items():
        if key.startswith(("item.siao.", "block.siao.")):
            ja[key] = DIRECT.get(key, translate(value))
        elif key.startswith("advancement.siao."):
            _, _, adv_id, field = key.split(".")
            title, description = ADVANCEMENTS.get(adv_id, (value, value))
            ja[key] = title if field == "title" else description
        else:
            ja[key] = DIRECT.get(key, translate(value))

    (LANG_DIR / "ja_jp.json").write_text(
        json.dumps(dict(sorted(ja.items())), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    print(f"ja_jp: {len(ja)} 条")


if __name__ == "__main__":
    main()
