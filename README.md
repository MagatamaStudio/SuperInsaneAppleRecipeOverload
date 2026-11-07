# SuperInsaneAppleRecipeOverload

> 一切皆可压缩。尤其是苹果。

Minecraft **1.21.1** / **NeoForge** 模组的「越压越多」玩笑模组。

九个苹果压成一个，一直压到九重（**387,420,489 个苹果**），
最后拿去合一根**普通的木棍**。

## 玩法循环

```
树苗 ──[苹果农场]──> 苹果 ──[压缩机]──> 一重 ──> … ──> 九重 ──> 绝对苹果奇点
                      │
                      └──[发电机]──> FE ──> 喂给所有机器
```

**压缩链只能由机器完成**，工作台做不出来——这是模组的设计前提，不是 bug。

### 机器

| 机器 | 作用 |
|---|---|
| 苹果压缩机 | 压缩 / 拆分 / 装块 / 拆块，四类操作都在这台 |
| 苹果附魔台 | 给压缩单位挂附魔并提升强度（1–9 级） |
| 苹果离心机 | 解附魔；shard 路线下把高重附魔件拆成奇点碎片 |
| 苹果农场 | 树苗 → 苹果，整个压缩链的真正瓶颈 |
| 苹果发电机 | 烧苹果发电，模组唯一的电源 |

### 变种系统

每种材料 × 9 重 × (普通 / 粗形态) × 4 氧化阶 × 2 涂蜡 × 6 形状，
合计 **10,548 个方块**。全部由 `tools/` 下的脚本生成，不手写。

### AE2 集成（可选）

三层递进，装不装 AE2 都能玩：

1. `ICraftingMachine` — 样板供应器可以直接推配方
2. `ME_STORAGE` — 机器在终端里是一个存储单元
3. `IInWorldGridNodeHost` — 机器自己就是网格节点，线缆直连即可

### 配方查看

JEI 和 EMI **各有一套插件**，都会显示「压缩（机器）」分类。
没装哪个就完全不加载哪个的插件类。

## 构建

```bash
./gradlew build          # 产出 build/libs/*.jar
./gradlew runClient      # 开发客户端
./gradlew runServer      # 开发服务端
```

需要 JDK 21。详细说明见 [`docs/BUILDING.md`](docs/BUILDING.md)。

## 生成资源

一万多个方块不可能手写，全部脚本生成：

```bash
python tools/gen_assets.py     # 贴图 / 模型 / 方块状态 / 掉落表 / 语言
python tools/gen_recipes.py    # 828 条配方
python tools/gen_lang_ja.py    # 日文（必须在 gen_assets 之后）
```

## 自检

服务端启动时若带 `-Dsiao.selftest=true`，会跑 **139 项断言**并自动退出：

```bash
./gradlew runServer -Dsiao.selftest=true
```

覆盖配方、附魔、五台机器、槽位矩阵、侧配置、氧化链、AE2 三层、发电、进度、语言。
CI 每次提交都会在真实的 AE2 环境里跑一遍。

## 文档

- [`docs/设计锁定-v1.md`](docs/设计锁定-v1.md) — 设计决策与阶段记录
- [`docs/自决清单.md`](docs/自决清单.md) — 所有自主决定的理由（含踩过的坑）
- [`docs/BUILDING.md`](docs/BUILDING.md) — 构建与生成流程

## 许可

MIT，见 [`LICENSE`](LICENSE)。
