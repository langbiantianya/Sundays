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
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[void][CS]::SetProcessDPIAware()

$win = (Get-Process -Id $TargetProcessId -ErrorAction Stop).MainWindowHandle
$r = New-Object CS+RECT
[void][CS]::GetWindowRect($win, [ref]$r)
[void][CS]::BringWindowToTop($win)
[void][CS]::SetForegroundWindow($win)
Start-Sleep -Milliseconds 600

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
"点击 ($X,$Y) x$Clicks → 屏幕 ($absX,$absY)，截图：$path"