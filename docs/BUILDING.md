# 构建说明

> **权威构建在 GitHub Actions**（`.github/workflows/build.yml`）：
> `build` 出 jar，`selftest` 真跑服务端跑 45 项自检。本地只需要能起客户端。

---

## 日常只需要三件事

```powershell
# 1. 进游戏看效果（这是唯一需要在本地做的验证）
.\gradlew.bat runClient --offline

# 2. 改完贴图/配方后重新生成
python tools/gen_assets.py        # 贴图 + 模型 + blockstate + lang + 战利品表 + data map
python tools/gen_recipes.py       # 569 条配方

# 3. 改完配方/代码后自查（跑完自动关服）
$env:SIAO_SELFTEST='true'; .\gradlew.bat runServer --offline
```

日常命令一律加 `--offline`：本机 `~/.gradle/gradle.properties` 写死了 Clash 代理，
Clash 没开时 Gradle 会 `Connection refused`，而 `maven.neoforged.net` 直连又不通。
依赖已全部缓存，`--offline` 是 **0 流量**。

---

## 换机器 / 清缓存后要先做一次

### 1. 重建离线插件仓库

`gradle/offline-repo/` 里是 ModDevGradle 及其依赖（约 2 MB，**不入库**）：

```powershell
pwsh -File gradle/setup-offline-repo.ps1
```

脚本从 Plugin Portal 拉 MDG `2.0.141` 的 marker/实体 POM/jar、`gradle-idea-ext:1.2`、
Maven Central 的 `gson:2.10.1`（含父 POM），落成一个标准本地 Maven 仓库。
`settings.gradle` 把它放在 `pluginManagement` 首位；目录缺失时会在 `--offline` 下
抛出带修复命令的提示（有网时这个检查不生效，CI 就是走 plugin portal）。

### 2. JDK 路径写**用户级**，不要写项目级

```powershell
# 追加到 ~/.gradle/gradle.properties，不是项目里的那个
org.gradle.java.installations.paths=C\:\\Program Files\\Java\\jdk-21.0.10
```

**写进项目级的后果**：CI 的 Linux runner 会把 `C:\Program Files\...` 当 **URI** 解析，
直接抛 `URISyntaxException: Illegal character in opaque part at index 2`，
`createMinecraftArtifacts` 都进不去，整条流水线全红。踩过一次。

---

## 素材（已齐，不需要下载）

`~/.gradle/caches/neoformruntime/assets` 已缓存 786 MB / 3911 个对象，对照 `indexes/17.json`
完整率 100%，`runClient` 不会下载素材。

万一缓存被清空，别重下几百 MB，直接接用启动器的（零拷贝）：

```powershell
$target = "D:\MC\PCL CE\.minecraft\assets"
$link   = "$env:USERPROFILE\.gradle\caches\neoformruntime\assets"
Remove-Item $link -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Junction -Path $link -Target $target | Out-Null
```

`gradle.properties` 里的 `launcher_assets_dir` / `launcher_asset_index` 是这一步的备忘。

---

## ⚠️ 配方 JSON 的两个坑

**1. `Ingredient` 不接受裸物品 id 字符串**

```json
"ingredient": { "item": "siao:compressed_3x_coal" }     ✅
"ingredient": "siao:compressed_3x_coal"                  ❌ 解析失败
```

**2. 压缩链是自定义类型 `siao:compression`**，不是 `crafting_shaped`。别照原版格式手改：

```json
{
  "type": "siao:compression",
  "ingredient": { "item": "minecraft:iron_ingot" },
  "result": { "id": "siao:compressed_1x_iron", "count": 1 },
  "count": 9,
  "enchant": "preserve"
}
```

- `result` 只是**展示产物**（JEI/EMI/配方书用），真产物在 `assemble()` 里按输入组件现算
- `count` = 输入个数
- `enchant` = `preserve`（压缩，附魔跟着走）/ `reject`（拆分、装块）/ `require`（终极木棍，必须附魔）

写错的话服务器**照样能启动**，只是日志里刷 `Parsing error loading recipe`、配方静默消失。
所以改完配方一定跑一次自检。

---

## 自检：唯一能证明「配方真的能匹配」的东西

```powershell
$env:SIAO_SELFTEST='true'; .\gradlew.bat runServer --offline
```

日志里应该出现：

```
[SIAO-TEST] PASS  9 普通铁锭：产物 = 一重压缩铁锭
...
[SIAO-TEST] RESULT pass=45 fail=0
```

`SiaoSelfTest.java` 覆盖：配方匹配、附魔穿透、混合输入拒绝、附魔不可下泄、
三台机器的真实产出（在真世界里放方块、喂料、跑 tick）、铜的氧化/涂蜡 data map、
以及配方与方块的总量校验。

**为什么非要有它**：`matches()` 写错了服务端日志是**看不出来**的——配方加载成功、
没有任何报错，只是在游戏里怎么摆都不出东西。同样的，`ItemStackHandler.insertItem`
会先查 `isItemValid`，把输出槽设成不收外部插入会连机器自己也挡住，症状是
「进度条一直在跑但不出货」。这些 `build` 全部检测不到。

CI 里 grep 的是纯 ASCII 的 `RESULT pass=N fail=0`，因为日志文件编码见下。

---

## 视觉测试（GUI 截图 / AE2 真网格 / 平衡观测）

`SiaoVisualTest.java` 会真的开一个客户端进测试世界，跑完 30 项断言、
截图、写 `run/siao-visual/report.md` 后自动退出：

```powershell
# 前置：run/mods 里要有 AE2 和 EMI；测试世界存在（复制自「新的世界」）
Copy-Item -Recurse "run\saves\新的世界" "run\saves\siao_visualtest" -Force
.\gradlew.bat runVisualtest --offline
```

覆盖：7 个机器 GUI 开屏截图、AE2 真网格直连（推样板、E2E 压锭、产物回网）、
平衡数字（60 tick/苹果、九重 3.87 亿、能量闭环）、5 个发布交付物（创造页签、
压缩机优先级、配置器循环切面、楼梯/台阶命名、EMI 分类）。

要点（多轮运行踩过的坑）：

- **必须 `--offline`**；游戏 cwd = `run/`，日志 GBK 编码，监控用 `tail -F`
- 每轮跑前重置世界和截图目录：`rm -rf run/siao-visual run/saves/siao_visualtest`（或 PowerShell `Remove-Item -Recurse -Force`）再复制
- 测试窗口会在后台跑，别依赖它可见；harness 首 tick 已关 vsync、限 30 FPS、静音 MouseHandler（不抢系统光标）
- 基线：`通过 30 / 失败 0`；断言名以 `SIAO-VISUAL` 前缀打日志

---

## 环境坑备忘

| 坑 | 说明 |
|---|---|
| 全局代理 | `~/.gradle/gradle.properties` 的 `systemProp.*.proxyHost` 优先于项目级，项目里改不掉，只能靠 `--offline` |
| GitHub 被 hosts 屏蔽 | `github.com` / `raw.githubusercontent.com` 指向 `127.0.0.1`。查源码用本地 `_research/`，或 `https://cdn.jsdelivr.net/gh/<owner>/<repo>@<ref>/<path>` |
| `maven.neoforged.net` 直连不通 | 需要时走代理 |
| 首次构建要反编译 | MDG 跑 NeoForm 流水线约 90 秒，产物在 `~/.gradle/caches/neoformruntime/intermediate_results`（约 200 MB，本地生成非下载） |
| 下划线开头的配方文件名会被**静默跳过** | 不加载也不报错。排查时别拿 `_test_xxx.json` 当样本，会得到假阴性 |
| `run/logs/latest.log` 是本机 ANSI 代码页（GBK） | 用 UTF-8 编辑器打开，自己打的中文日志会乱码。搜英文标记（`SIAO`、`SIAO-TEST`、`Parsing error`）就好，别以为日志坏了 |


## 语言文件

gen_assets.py 只生成 zh_cn / n_us。日文由 	ools/gen_lang_ja.py 从中日词元映射生成，**必须在 gen_assets 之后跑**：

``` 
python tools/gen_assets.py
python tools/gen_recipes.py
python tools/gen_lang_ja.py
```
