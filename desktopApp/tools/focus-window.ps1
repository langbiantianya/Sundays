param(
  [Parameter(Mandatory=$true)][int]$TargetProcessId,
  # 可选：把窗口缩到指定尺寸（逻辑像素）。不给就不动尺寸。
  # 应用默认 1152x720 逻辑 = 1440x900 物理，比本机工作区（1536x824 物理）还高，
  # 向导底部的「下一步 / 保存」按钮会被顶到屏幕外 —— 点不到，也截不全。
  [int]$Width = 0,
  [int]$Height = 0
)

# 窗口跑出屏幕边界时，CopyFromScreen 抓不到完整画面，点击坐标也会落到别的窗口上。
# 这里只做一件事：把主窗口挪回屏幕内的左上角。
Add-Type -Name WinMove -Namespace NativeMove -MemberDefinition @"
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
"@

$p = Get-Process -Id $TargetProcessId -ErrorAction Stop
$h = $p.MainWindowHandle
if ($h -eq [IntPtr]::Zero) { throw "进程 $TargetProcessId 没有主窗口" }

# SWP_NOZORDER(0x0004)：保持 z 序
$flags = 0x0004
if ($Width -le 0 -or $Height -le 0) { $flags = $flags -bor 0x0001 }  # SWP_NOSIZE
[NativeMove.WinMove]::SetWindowPos($h, [IntPtr]::Zero, 0, 0, $Width, $Height, $flags) | Out-Null
[NativeMove.WinMove]::ShowWindow($h, 9) | Out-Null   # SWP_RESTORE
[NativeMove.WinMove]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 600

$r = New-Object NativeMove.WinMove+RECT
[NativeMove.WinMove]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.Right - $r.Left
$hgt = $r.Bottom - $r.Top
Write-Output "窗口: L=$($r.Left) T=$($r.Top) R=$($r.Right) B=$($r.Bottom)  宽=$w 高=$hgt (逻辑)"

# 底部是否还在屏幕内 —— 这是「点不到下一步」类问题的唯一判据
Add-Type -AssemblyName System.Windows.Forms
$wa = [System.Windows.Forms.Screen]::FromHandle($h).WorkingArea
if ($r.Bottom -gt $wa.Bottom) {
  Write-Warning "窗口底边 $($r.Bottom) 超出工作区 $($wa.Bottom)，底部控件点不到 —— 用 -Width/-Height 缩小"
} else {
  Write-Output "窗口完整落在工作区内（$($wa.Width)x$($wa.Height)）"
}
