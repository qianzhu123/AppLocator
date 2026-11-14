# AppLocator 发版脚本：把最新构建的 APK 推入局域网更新通道
# 用法: .\release.ps1 [版本号，如 1.6]
#   自动读取 versionCode/versionName 从 app/build.gradle.kts，可省略版本号参数
param([string]$Version)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$apk  = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "APK 不存在，先跑 gradlew assembleDebug" }

if (-not $Version) {
    $kts = Get-Content (Join-Path $root "app\build.gradle.kts") -Raw
    $code = [regex]::Match($kts, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    $ver  = [regex]::Match($kts, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
} else {
    $ver = $Version
    # 从 gradle 里照抄 versionCode，调用者只给版本名时也够用
    $kts = Get-Content (Join-Path $root "app\build.gradle.kts") -Raw
    $code = [regex]::Match($kts, 'versionCode\s*=\s*(\d+)').Groups[1].Value
}

$dist = Join-Path $PSScriptRoot "dist-update"
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item $apk (Join-Path $dist "applocator-latest.apk") -Force
@{ versionCode = [int]$code; versionName = $ver; apkSize = (Get-Item $apk).Length } |
    ConvertTo-Json | Set-Content (Join-Path $dist "meta.json") -Encoding utf8
Write-Host "dist-update 已更新: v$ver (code $code), $((Get-Item $apk).Length) bytes"
