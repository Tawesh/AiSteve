# ============================================================
#  AiSteve 升级助手：把旧版 Steve AI Mod 的配置迁移到 AiSteve
#
#  背景：
#    模组已改名，内部 ID 由 `steve` 改为 `aisteve`，因此配置文件
#    从  config\steve-common.toml   变为  config\aisteve-common.toml
#    你的 DeepSeek API Key 存在旧文件里，本脚本负责自动搬过来。
#
#  用法：
#    1) 先【完全关闭游戏】
#    2) 执行：
#         powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\migrate-to-aisteve.ps1"
#    3) 删除 mods 里的旧 jar（steve-ai-mod-*.jar），放入新的 aisteve-1.1.0-all.jar
#    4) 启动游戏 → 执行 /as cleanup → /as create <名字>
# ============================================================

param(
    # Forge 实例目录（版本隔离模式）。如路径不同请用 -InstanceDir 指定。
    [string]$InstanceDir = "D:\my_game\.minecraft\versions\1.20.1-Forge_47.4.10"
)

$ErrorActionPreference = "Stop"

$oldCfg = Join-Path $InstanceDir "config\steve-common.toml"
$newCfg = Join-Path $InstanceDir "config\aisteve-common.toml"
$modsDir = Join-Path $InstanceDir "mods"

Write-Host "=== AiSteve 迁移助手 ===" -ForegroundColor Cyan
Write-Host ("实例目录: " + $InstanceDir)
Write-Host ""

# ---- 1) 检查游戏是否仍在运行 ----
$javaProcs = Get-Process -Name javaw, java -ErrorAction SilentlyContinue
if ($javaProcs) {
    Write-Host "!! 检测到 Java 进程仍在运行，请先【完全关闭游戏】再执行。" -ForegroundColor Red
    $javaProcs | Select-Object Id, ProcessName, StartTime | Format-Table -AutoSize | Out-String | Write-Host
    exit 1
}

# ---- 2) 迁移配置 ----
if (-not (Test-Path $oldCfg)) {
    Write-Host "· 未找到旧配置（可能已经是 aisteve 或尚未配置）：" -ForegroundColor Yellow
    Write-Host ("  " + $oldCfg)
    if (Test-Path $newCfg) {
        Write-Host "· 新配置已存在，无需迁移：" -ForegroundColor Green
        Write-Host ("  " + $newCfg)
    } else {
        Write-Host "· 启动一次游戏后会自动生成 config\aisteve-common.toml，再填入你的 Key 即可。" -ForegroundColor Yellow
    }
} else {
    Write-Host "· 发现旧配置，开始迁移..." -ForegroundColor Cyan

    if (Test-Path $newCfg) {
        Copy-Item $newCfg "$newCfg.bak" -Force
        Write-Host ("  已备份现有新配置 -> " + $newCfg + ".bak")
    }

    # 直接把旧文件内容作为新配置的基础
    Copy-Item $oldCfg $newCfg -Force
    Write-Host ("  " + $oldCfg + "  ->  " + $newCfg) -ForegroundColor Green

    # 校验关键字段是否搬过来了
    $text = Get-Content $newCfg -Raw
    $checks = @()
    if ($text -match 'provider\s*=\s*"(deepseek|openai|groq|gemini)"') {
        $checks += ("  provider = " + $Matches[1] + "  OK")
    } else {
        $checks += "  !! 未找到 provider 字段"
    }
    if ($text -match 'apiKey\s*=\s*"([^"]+)"') {
        $k = $Matches[1]
        $checks += ("  apiKey = " + $k.Substring(0, [Math]::Min(7, $k.Length)) + "****  OK")
    } else {
        $checks += "  !! 未找到 apiKey（请手动填写）"
    }
    Write-Host "  迁移结果："
    $checks | ForEach-Object { Write-Host $_ }

    Write-Host "  旧文件保留未删除（确认新配置可用后可自行删除）：" -ForegroundColor DarkGray
    Write-Host ("  " + $oldCfg) -ForegroundColor DarkGray
}

Write-Host ""

# ---- 3) 检查旧 jar ----
$oldJars = Get-ChildItem $modsDir -Filter "steve-ai-mod-*.jar" -ErrorAction SilentlyContinue
$newJars = Get-ChildItem $modsDir -Filter "aisteve-*.jar" -ErrorAction SilentlyContinue

if ($oldJars) {
    Write-Host "!! mods 目录里还有旧版 jar，必须删除，否则会冲突：" -ForegroundColor Red
    $oldJars | ForEach-Object { Write-Host ("   " + $_.Name) -ForegroundColor Red }
    Write-Host "   执行以下命令删除：" -ForegroundColor Yellow
    Write-Host ('   Remove-Item "' + ($oldJars | Select-Object -First 1).FullName + '" -Force') -ForegroundColor White
} else {
    Write-Host "· mods 目录里没有旧版 jar，OK" -ForegroundColor Green
}

if ($newJars) {
    Write-Host "· 已找到新版 jar：" -ForegroundColor Green
    $newJars | ForEach-Object { Write-Host ("   " + $_.Name) -ForegroundColor Green }
} else {
    Write-Host "!! mods 目录里还没有新版 jar，请把下面这个复制过去：" -ForegroundColor Red
    Write-Host '   <仓库>\build\libs\aisteve-1.1.0-all.jar' -ForegroundColor White
}

Write-Host ""
Write-Host "=== 接下来 ===" -ForegroundColor Cyan
Write-Host " 1) 启动游戏"
Write-Host " 2) 执行  /as cleanup      （清理旧存档里那个旧模组的 AI 实体）"
Write-Host " 3) 执行  /as create <名字>  （重新创建）"
Write-Host " 4) 以后用  /as <指令>      下达任务（不再是 /tai）"
Write-Host ""
