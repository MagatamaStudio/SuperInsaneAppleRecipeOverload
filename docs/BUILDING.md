# 构建说明（本机环境）

## ⚡ 默认命令：一律加 `--offline`

```powershell
.\gradlew.bat build --offline
```

**为什么必须加**：本机用户级 `~/.gradle/gradle.properties` 写死了 Clash 代理 `127.0.0.1:7897`。
Clash 没启动时 Gradle 会走代理并直接 `Connection refused`；而 `maven.neoforged.net` 直连又不通。
`--offline` 让 Gradle 完全使用本地缓存，两个问题一起绕开。

**流量**：项目依赖已经全部缓存完毕，日常 `build --offline` **0 流量**。

## 组件与位置

| 组件 | 位置 | 说明 |
|---|---|---|
| JDK 21 | `C:\Program Files\Java\jdk-21.0.10` | 已在 `gradle.properties` 的 `org.gradle.java.installations.paths` 里声明 |
| Gradle 8.9 | `C:\gradle-8.9\gradle-8.9` + wrapper 已预置发行版 | `gradlew` 不会重新下载 |
| ModDevGradle 2.0.141 | `gradle/offline-repo/` | **不入库**，用脚本重建，见下 |
| NeoForge 21.1.230 | Gradle 缓存 | 含 userdev / sources / moddev-config |

## 首次构建：先重建离线插件仓库

`gradle/offline-repo/` 里有第三方 jar，**不纳入版本管理**。换台机器/清空后先跑一次：

```powershell
pwsh -File gradle/setup-offline-repo.ps1
```

脚本做三件事（约 2 MB）：
1. 从 Gradle Plugin Portal 拉 ModDevGradle `2.0.141` 的 marker POM / 实体 POM / jar
2. 拉它的 runtime 依赖 `gradle-idea-ext:1.2`
3. 从 Maven Central 拉 `gson:2.10.1`（含父 POM）

拉完 `gradle/offline-repo/` 就是一个标准本地 Maven 仓库，`settings.gradle` 已经把它放在
`pluginManagement` 的首位，之后一律 `--offline` 构建。
如果目录缺失，`settings.gradle` 会直接抛出带修复命令的提示，不会给你一串解析异常。

## 需要联网时才做的事

```powershell
# 1. 启动代理（用完记得关）
Start-Process "C:\KujouShiori\ClashVerge\Clash Verge\clash-verge.exe"

# 2. 去掉 --offline
.\gradlew.bat build

# 3. 关掉代理
Get-Process clash-verge,verge-mihomo | Stop-Process -Force
```

## 素材（assets）现状：**已齐，不需要额外下载**

实测 `~/.gradle/caches/neoformruntime/assets` 已缓存 **786 MB / 3911 个对象**，
对照 `indexes/17.json`（1.21.1 的素材索引）**完整率 100%，零缺失**。
所以 `runClient` 不会产生素材下载。

`downloadAssets` 唯一会做的事是补下索引 JSON（约 0.4 MB）——只有索引缺失时才会发生。

### 如果将来这个缓存被清空
别让它重新下几百 MB，直接把系统里的启动器素材接过来（零拷贝）：

```powershell
$target = "D:\MC\PCL CE\.minecraft\assets"          # PCL 启动器的素材目录（含 indexes/17.json 和 objects/）
$link   = "$env:USERPROFILE\.gradle\caches\neoformruntime\assets"
Remove-Item $link -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Junction -Path $link -Target $target | Out-Null
```

项目 `gradle.properties` 里的 `launcher_assets_dir` / `launcher_asset_index` 就是给这一步用的备忘。

## `run*` 任务的联网需求（与 `build` 不同）

`build` / `compileJava` 可以全程 `--offline`。但 `runClient` / `runServer` / `runData` 依赖
`prepare*Run` 任务解析 `neoForgeConfigOnly` 配置，其中会 **HEAD 一次**
`neoforge-21.1.230-moddev-config.json`。这一下必须能连到 `maven.neoforged.net`：

| 场景 | 处理 |
|---|---|
| `prepare*Run` 显示 `UP-TO-DATE` | 直接 `--offline` 即可 |
| 改了 `build.gradle` / 清过 `build/` 后首次跑 | 临时开一次 Clash 代理，跑完关掉（流量约几百 KB） |

已验证的冒烟测试命令（不生成世界、不开窗口、13 秒退出）：

```powershell
.\gradlew.bat runData --offline
```

日志里出现 `Mod List: ... Super Insane Apple Recipe Overload 0.1.0 (siao)`
和 `SIAO 已装填：108 压缩单位 / 27 粗形态 / 135 方块` 即代表注册矩阵装载正常。

## 资源生成

贴图 / 模型 / blockstate / lang / 配方全部由脚本生成，产物已入库：

```powershell
python tools/gen_assets.py --clean    # 贴图 + 模型 + blockstate + lang
python tools/gen_recipes.py --clean   # 540 条合成配方
```

从 `mc-assets/item-assets/` 的 256×256 原版渲染图出发，输出物品 128×128、方块 64×64。
改动材料表后重跑即可，不需要手点任何一个 JSON。

### ⚠️ 配方 JSON 的坑（已踩过）

**1. `Ingredient` 不接受裸物品 id 字符串**，必须是对象形式：

```json
"ingredient": { "item": "siao:compressed_3x_coal" }     ✅
"ingredient": "siao:compressed_3x_coal"                  ❌ 解析失败
```

**2. 现在配方全部是自定义类型 `siao:compression`**，不再是 `crafting_shaped` /
`crafting_shapeless`。别照着原版格式手改：

```json
{
  "type": "siao:compression",
  "group": "compressed_iron",
  "category": "misc",
  "ingredient": { "item": "minecraft:iron_ingot" },
  "result": { "id": "siao:compressed_1x_iron", "count": 1 },
  "count": 9,
  "enchant": "preserve"
}
```

`result` 只是**展示产物**（JEI/EMI/配方书用），真产物在 `assemble()` 里现算；
`count` 是输入个数，`enchant` 是 `preserve`（压/装块）或 `reject`（拆/拆块）。

写错的话服务器照样能启动，但日志里有 540 条 `Parsing error loading recipe`，
配方在游戏里静默消失 —— 改完配方务必跑一次服务端校验。

### 配方校验流程

```powershell
$env:SIAO_SELFTEST='true'
.\gradlew.bat runServer --offline     # 跑完自检自动关服
```

日志里应该出现：

```
[SIAO-TEST] PASS  9 普通铁锭：匹配
...
[SIAO-TEST] ===== 通过 18 / 失败 0 =====
```

自检用例在 `SiaoSelfTest.java`：覆盖普通压缩、附魔穿透、混合输入拒绝、
附魔不可下泄、装块拆块、以及 540 条配方是否全部装载。
没有它，`matches()` 写错了服务端日志是**看不出来**的（配方加载成功、只是不工作）。

只想看配方有没有解析错误、不跑自检：

```powershell
.\gradlew.bat runServer --offline
Select-String run\logs\latest.log -Pattern "Parsing error loading recipe"
```

**坑**：下划线开头的配方文件名会被静默跳过（不加载也不报错），
排查时别拿 `_test_xxx.json` 当样本，会得到假阴性。

**坑**：`run/logs/latest.log` 是本机 ANSI 代码页（GBK）写的，不是 UTF-8。
用 VSCode 之类的 UTF-8 编辑器打开，自己打的中文日志会显示成乱码 —— 搜索英文标记
（`SIAO-TEST`、`SIAO`、`Parsing error`）就行，别以为日志坏了。

## 环境坑备忘

1. **全局代理**：`~/.gradle/gradle.properties` 里的 `systemProp.*.proxyHost` 优先于项目级同名配置，
   项目里改不掉，只能靠 `--offline` 或命令行 `-D`。
2. **GitHub 被 hosts 屏蔽**：`github.com` / `raw.githubusercontent.com` 全指向 `127.0.0.1`。
   查源码请用本地 `_research/`，或用 jsDelivr：`https://cdn.jsdelivr.net/gh/<owner>/<repo>@<ref>/<path>`。
3. **`maven.neoforged.net` 直连不通**：需要时走代理。
4. 首次构建 MDG 会跑 NeoForm 反编译流水线，约 90 秒，产物缓存在
   `~/.gradle/caches/neoformruntime/intermediate_results`（约 200 MB，本地生成，非下载）。
