# 统一行尾为 CRLF 并去掉 UTF-8 BOM（core.autocrlf=true 的仓库约定）
# 用法：powershell -File tools/fix-crlf.ps1 <文件路径>...
param([Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Paths)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

foreach ($p in $Paths) {
    if (-not (Test-Path -LiteralPath $p)) { Write-Host "跳过（不存在）: $p"; continue }
    $raw = [System.IO.File]::ReadAllText($p)
    # 去 BOM
    if ($raw.Length -gt 0 -and $raw[0] -eq [char]0xFEFF) { $raw = $raw.Substring(1) }
    # 统一换行：先全转 LF，再全转 CRLF（幂等）
    $t = $raw -replace "`r`n", "`n"
    $t = $t -replace "`r", "`n"
    $t = $t -replace "`n", "`r`n"
    # 补回文件末尾换行
    if (-not $t.EndsWith("`r`n")) { $t += "`r`n" }
    [System.IO.File]::WriteAllText((Resolve-Path -LiteralPath $p).Path, $t, $utf8NoBom)
    Write-Host "已规范: $p"
}
