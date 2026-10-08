param(
  [Parameter(Mandatory=$true)][int]$TargetProcessId,
  # 所有字段写成一个串，用 `;` 分隔，每项 "X,Y=文本"。
  # （不能用数组：powershell -File 会把 "a","b" 传成单个字符串 "a,b"，
  #   split 后整串当成一个坐标，五个框只填了第一个。）
  [Parameter(Mandatory=$true)][string]$Fields,
  [string]$Name = "fill"
)

# 在一串输入框里逐个「点击 → 全选 → 剪贴板粘贴 → Ctrl+V」。
#
# 为什么不用 gui-probe.ps1：它会把窗口最大化，改掉坐标系，
# 于是后面每一步的坐标都得重新量一遍。点这里全程用绝对坐标，
# 窗口尺寸始终不变 —— 少一个变量，就少一次「点空了却不知道为什么」。
#
# 为什么用剪贴板而不是逐字符敲：TYPE 会被中文输入法吃掉，
# 而这里的值虽然是 ASCII，仍然比剪贴板慢且易错。
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Fill {
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint f, IntPtr e);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[void][Fill]::SetProcessDPIAware()

$win = (Get-Process -Id $TargetProcessId -ErrorAction Stop).MainWindowHandle
$r = New-Object Fill+RECT
[void][Fill]::GetWindowRect($win, [ref]$r)

for ($i = 0; $i -lt 6; $i++) {
    [void][Fill]::BringWindowToTop($win)
    [Fill]::keybd_event(0x12, 0, 0, [IntPtr]::Zero)
    [void][Fill]::SetForegroundWindow($win)
    [Fill]::keybd_event(0x12, 0, 2, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 200
    if ([Fill]::GetForegroundWindow() -eq $win) { break }
}
if ([Fill]::GetForegroundWindow() -ne $win) { Write-Warning "没能置前，后续输入可能落空" }

foreach ($f in ($Fields -split ';')) {
    if ([string]::IsNullOrWhiteSpace($f)) { continue }
    $pos, $text = $f -split '=', 2
    $x, $y = $pos -split ',' | ForEach-Object { [int]$_ }
    $absX = $r.Left + $x
    $absY = $r.Top + $y

    [void][Fill]::SetCursorPos($absX, $absY)
    Start-Sleep -Milliseconds 220
    [Fill]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 40
    [Fill]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 180

    # Ctrl+A 全选（不是 ^a 那种字符串写法）
    [Fill]::keybd_event(0x11, 0, 0, [IntPtr]::Zero)
    [Fill]::keybd_event(0x41, 0, 0, [IntPtr]::Zero)
    [Fill]::keybd_event(0x41, 0, 2, [IntPtr]::Zero)
    [Fill]::keybd_event(0x11, 0, 2, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 120

    Set-Clipboard -Value $text
    Start-Sleep -Milliseconds 150
    [Fill]::keybd_event(0x11, 0, 0, [IntPtr]::Zero)
    [Fill]::keybd_event(0x56, 0, 0, [IntPtr]::Zero)   # V
    [Fill]::keybd_event(0x56, 0, 2, [IntPtr]::Zero)
    [Fill]::keybd_event(0x11, 0, 2, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 450
    Write-Output "  ($x,$y) <- $text"
}

Start-Sleep -Milliseconds 900
$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size($w, $h)))
$g.Dispose()
$outDir = "C:\Users\lbty\project\Sundays\build\tmp\shots"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$path = Join-Path $outDir "$Name.png"
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"截图：$path"
