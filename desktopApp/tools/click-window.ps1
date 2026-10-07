param(
    [Parameter(Mandatory = $true)][int]$TargetProcessId,
    [Parameter(Mandatory = $true)][int]$X,      # 窗口内相对坐标（物理像素，左上角为 0,0）
    [Parameter(Mandatory = $true)][int]$Y,
    [int]$Clicks = 1,
    [string]$OutDir = "C:\Users\lbty\project\Sundays\build\tmp\shots",
    [string]$Name = "click"
)

# 窗口内相对坐标点击 + 截图。DPI-aware，且**不改窗口尺寸**。
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class CS {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint f, IntPtr e);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[void][CS]::SetProcessDPIAware()

$win = (Get-Process -Id $TargetProcessId -ErrorAction Stop).MainWindowHandle
$r = New-Object CS+RECT
[void][CS]::GetWindowRect($win, [ref]$r)

# ⚠️ 置前必须**确认成功**，否则后面全是白干。
#
# `SetForegroundWindow` 在前台是别的应用（比如 Android Studio）时会被 Windows 拒绝，
# 失败时**不报错、也不置前**。于是脚本自以为切到了 sundays，
# 实际鼠标点在别的窗口上、截屏截到的也是别的窗口 ——
# 表现就是「脚本说点到了，截图却和上一次一模一样」。
# ALT 点一下再放行是 Windows 通用的放行手法（焦点是别的进程时才生效），再配重试。
function Activate-Window {
    for ($i = 0; $i -lt 6; $i++) {
        [void][CS]::ShowWindow($win, 9)   # SW_RESTORE：从最小化恢复
        [void][CS]::BringWindowToTop($win)
        [CS]::keybd_event(0x12, 0, 0, [IntPtr]::Zero)   # ALT down
        [void][CS]::SetForegroundWindow($win)
        [CS]::keybd_event(0x12, 0, 2, [IntPtr]::Zero)   # ALT up
        Start-Sleep -Milliseconds 250
        if ([CS]::GetForegroundWindow() -eq $win) { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

$ok = Activate-Window
if (-not $ok) { Write-Warning "没能把窗口 $TargetProcessId 置前（Windows 拒绝了），后续点击与截图可能落到别的窗口上" }

$absX = $r.Left + $X
$absY = $r.Top + $Y
[void][CS]::SetCursorPos($absX, $absY)
Start-Sleep -Milliseconds 350
for ($i = 0; $i -lt $Clicks; $i++) {
    [CS]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 40
    [CS]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 60
}
Start-Sleep -Milliseconds 900

$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size($w, $h)))
$g.Dispose()
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$path = Join-Path $OutDir "$Name.png"
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"点击 ($X,$Y) x$Clicks → 屏幕 ($absX,$absY)，置前=$ok，截图：$path"