param(
    [Parameter(Mandatory = $true)][int]$TargetProcessId,
    [string]$OutDir,
    [string]$Name = "shot"
)

# 全窗口截图，**不动窗口尺寸、不置前最大化**。
#
# ⚠️ 必须先 SetProcessDPIAware：PowerShell 默认是 DPI-unaware，
# 此时 GetWindowRect 返回**逻辑**坐标（1152x720），而 CopyFromScreen 取的是**物理**像素。
# 两者混用只会截到窗口左上角一块 1152x720 —— 看上去就像「界面被画到窗口外」，
# 实则是截图没截全。旧脚本（shot-as-is.ps1）就踩过这个坑，见 TEST_CASES.md §9.12。
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class SS2 {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

[void][SS2]::SetProcessDPIAware()

if (-not $OutDir) { $OutDir = "C:\Users\lbty\project\Sundays\build\tmp\shots" }
$win = (Get-Process -Id $TargetProcessId -ErrorAction Stop).MainWindowHandle
if ($win -eq [IntPtr]::Zero) { throw "进程 $TargetProcessId 没有主窗口" }
[void][SS2]::BringWindowToTop($win)
[void][SS2]::SetForegroundWindow($win)
Start-Sleep -Milliseconds 800

$r = New-Object SS2+RECT
[void][SS2]::GetWindowRect($win, [ref]$r)
[void][SS2]::SetCursorPos($r.Right - 12, $r.Bottom - 12)
Start-Sleep -Milliseconds 400

$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size($w, $h)))
$g.Dispose()
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$path = Join-Path $OutDir "$Name.png"
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"窗口 ${w}x${h}（物理像素），截图：$path"