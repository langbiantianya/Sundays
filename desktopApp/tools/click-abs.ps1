param(
    [Parameter(Mandatory = $true)][int]$X,   # 屏幕绝对坐标（物理像素）
    [Parameter(Mandatory = $true)][int]$Y,
    [string]$OutDir = "C:\Users\lbty\project\Sundays\build\tmp\shots",
    [string]$Name = "abs-click"
)

# 屏幕**绝对坐标**点击 + 全屏截图。
# 系统对话框（java.awt.FileDialog）是**另一个窗口**，不在 sundays 的窗口矩形里，
# 用 click-window.ps1 那套「窗口内相对坐标」会点偏，所以单独留这一个。
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class ABS {
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
"@
[void][ABS]::SetProcessDPIAware()

[void][ABS]::SetCursorPos($X, $Y)
Start-Sleep -Milliseconds 350
[ABS]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 40
[ABS]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 900

$vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap $vs.Width, $vs.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($vs.Left, $vs.Top, 0, 0, (New-Object System.Drawing.Size($vs.Width, $vs.Height)))
$g.Dispose()
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$path = Join-Path $OutDir "$Name.png"
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"点击屏幕 ($X,$Y)，全屏截图：$path"