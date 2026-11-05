# 重建本地离线插件仓库（gradle/offline-repo）
#
# 背景：ModDevGradle 官方只发布在 maven.neoforged.net，而部分网络环境下该域名不可达，
#       且机器上可能存在指向未运行代理的全局 Gradle 代理配置。
#       本脚本把插件及其依赖抓成一个本地 Maven 仓库，之后构建即可全程 --offline。
#
# 用法：  pwsh -File gradle/setup-offline-repo.ps1
#         pwsh -File gradle/setup-offline-repo.ps1 -Force     # 强制重建

param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$ModDevVersion = '2.0.141'
$IdeaExtVersion = '1.2'
$GsonVersion = '2.10.1'
$Ae2Version = '19.2.18'

$repoDir = Join-Path $PSScriptRoot 'offline-repo'
$pluginPortal = 'https://plugins.gradle.org/m2'
$central = 'https://repo1.maven.org/maven2'

function Save-File([string]$Url, [string]$Dest) {
    if ((Test-Path $Dest) -and -not $Force) {
        Write-Host "  跳过（已存在）: $([System.IO.Path]::GetFileName($Dest))"
        return
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $Dest) | Out-Null
    Write-Host "  下载: $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing -TimeoutSec 120
}

Write-Host "== 目标目录: $repoDir"
if ($Force -and (Test-Path $repoDir)) {
    Remove-Item $repoDir -Recurse -Force
}

# 1) ModDevGradle 插件（marker POM + 实体 POM + jar）
$markerDir = "$repoDir/net/neoforged/moddev/net.neoforged.moddev.gradle.plugin/$ModDevVersion"
Save-File "$pluginPortal/net/neoforged/moddev/net.neoforged.moddev.gradle.plugin/$ModDevVersion/net.neoforged.moddev.gradle.plugin-$ModDevVersion.pom" `
    "$markerDir/net.neoforged.moddev.gradle.plugin-$ModDevVersion.pom"

$mdgDir = "$repoDir/net/neoforged/moddev-gradle/$ModDevVersion"
Save-File "$pluginPortal/net/neoforged/moddev-gradle/$ModDevVersion/moddev-gradle-$ModDevVersion.pom" `
    "$mdgDir/moddev-gradle-$ModDevVersion.pom"
Save-File "$pluginPortal/net/neoforged/moddev-gradle/$ModDevVersion/moddev-gradle-$ModDevVersion.jar" `
    "$mdgDir/moddev-gradle-$ModDevVersion.jar"

# 2) idea-ext（MDG 的 runtime 依赖）
$ieDir = "$repoDir/gradle/plugin/org/jetbrains/gradle/plugin/idea-ext/gradle-idea-ext/$IdeaExtVersion"
Save-File "$pluginPortal/gradle/plugin/org/jetbrains/gradle/plugin/idea-ext/gradle-idea-ext/$IdeaExtVersion/gradle-idea-ext-$IdeaExtVersion.pom" `
    "$ieDir/gradle-idea-ext-$IdeaExtVersion.pom"
Save-File "$pluginPortal/gradle/plugin/org/jetbrains/gradle/plugin/idea-ext/gradle-idea-ext/$IdeaExtVersion/gradle-idea-ext-$IdeaExtVersion.jar" `
    "$ieDir/gradle-idea-ext-$IdeaExtVersion.jar"

# 3) gson（idea-ext 的传递依赖）
$gsonDir = "$repoDir/com/google/code/gson/gson/$GsonVersion"
Save-File "$central/com/google/code/gson/gson/$GsonVersion/gson-$GsonVersion.pom" `
    "$gsonDir/gson-$GsonVersion.pom"
Save-File "$central/com/google/code/gson/gson/$GsonVersion/gson-$GsonVersion.jar" `
    "$gsonDir/gson-$GsonVersion.jar"
# 父 POM 也要在，否则 Gradle 解析父级会失败
Save-File "$central/com/google/code/gson/gson-parent/$GsonVersion/gson-parent-$GsonVersion.pom" `
    "$repoDir/com/google/code/gson/gson-parent/$GsonVersion/gson-parent-$GsonVersion.pom"

# 4) AE2 api jar（compileOnly 依赖，只用到 appeng.api.*）
$ae2Dir = "$repoDir/org/appliedenergistics/appliedenergistics2/$Ae2Version"
Save-File "$central/org/appliedenergistics/appliedenergistics2/$Ae2Version/appliedenergistics2-$Ae2Version.pom" `
    "$ae2Dir/appliedenergistics2-$Ae2Version.pom"
Save-File "$central/org/appliedenergistics/appliedenergistics2/$Ae2Version/appliedenergistics2-$Ae2Version-api.jar" `
    "$ae2Dir/appliedenergistics2-$Ae2Version-api.jar"

Write-Host ""
Write-Host "== 完成，共 $((Get-ChildItem $repoDir -Recurse -File).Count) 个文件"
Write-Host "   现在可以运行： .\gradlew.bat build --offline"
