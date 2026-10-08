param(
    [Parameter(Mandatory = $true)][int]$TargetProcessId,
    [int]$X = -1,          # 窗口内相对坐标（物理像素，左上角为 0,0）
    [int]$Y = -1,
    [int]$Clicks = 1,
    # 滚轮档数（**正数向下**）。向导内容列比窗口高，底部「下一步 / 保存」在折叠线以下，
    # 缩窗口没用（内容不重排），只能滚。
    #
    # ⚠️ Windows WHEEL_DELTA 的方向与直觉相反：**正数向上滚，负数向下滚**。
    # 早期版本这里直接传 `Scroll * 120`，于是「向下滚」其实在往上滚 ——
    # 而内容本来就在顶部，看起来就像「滚不动」，误判成 verticalScroll 失效。
    # （真机复盘见 TEST_CASES.md §9.25。）
    [int]$Scroll = 0,
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

# 滚轮模式下也把光标放到内容区里，否则 Windows 可能把滚轮发给光标下别的窗口
[void][CS]::SetCursorPos($absX, $absY)
Start-Sleep -Milliseconds 350

if ($Scroll -ne 0) {
    # MOUSEEVENTF_WHEEL(0x0800)，一格 = 120；向下滚要传**负值**（正数是向上）
    $notches = [Math]::Abs($Scroll)
    $delta = if ($Scroll -gt 0) { -120 } else { 120 }
    for ($i = 0; $i -lt $notches; $i++) {
        [CS]::mouse_event(0x0800, 0, 0, $delta, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 150
    }
} else {
    for ($i = 0; $i -lt $Clicks; $i++) {
        [CS]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 40
        [CS]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 60
    }
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
if ($Scroll -ne 0) {
    "滚动 ($X,$Y) x$Scroll → 屏幕 ($absX,$absY)，置前=$ok，截图：$path"
} else {
    "点击 ($X,$Y) x$Clicks → 屏幕 ($absX,$absY)，置前=$ok，截图：$path"
}
