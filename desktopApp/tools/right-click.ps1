param(
    [Parameter(Mandatory = $true)][int]$X,   # 屏幕绝对坐标（物理像素）
    [Parameter(Mandatory = $true)][int]$Y,
    [string]$OutDir = "C:\Users\lbty\project\Sundays\build\tmp\shots",
    [string]$Name = "right-click"
)

# 屏幕绝对坐标**右键** + 全屏截图。
#
# 为什么不能拿 click-abs.ps1 加个参数顶过去：那次调用只发 `MOUSEEVENTF_LEFTDOWN/UP`，
# 而右键菜单（`Modifier.onRightClick`，见 shared/ui/RightClick.kt）认的是
# `PointerEvent.buttons.isSecondaryPressed` —— 只按左键的话**什么都不会弹**，
# 而画面上看起来与「菜单不响应」一模一样。这正是本文件存在的理由：
# 「点了没反应」有三种可能（产品不响应 / 送错了键 / 量的地方不对），
# 只有换一个**送对键的通道**再验一次，才能区分。
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class RCLICK {
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
"@
[void][RCLICK]::SetProcessDPIAware()

# MOUSEEVENTF_RIGHTDOWN = 0x0008，MOUSEEVENTF_RIGHTUP = 0x0010
[void][RCLICK]::SetCursorPos($X, $Y)
Start-Sleep -Milliseconds 350
[RCLICK]::mouse_event(0x0008, 0, 0, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 80
[RCLICK]::mouse_event(0x0010, 0, 0, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 900

$vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap $vs.Width, $vs.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($vs.X, $vs.Y, 0, 0, (New-Object System.Drawing.Size($vs.Width, $vs.Height)))
$g.Dispose()
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$path = Join-Path $OutDir "$Name.png"
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "右键屏幕 ($X,$Y)，全屏截图：$path"
