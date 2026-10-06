<#
.SYNOPSIS
    启动后的 sundays 桌面应用探针 —— 用**真实窗口**截图 + 注入按键/文本。

.DESCRIPTION
    回答一个具体问题：在这个环境里，能不能**操作真的跑起来的应用**？
    结论（实测，见 desktopApp/TEST_CASES.md §5）：
      - 鼠标（SendInput `mouse_event` 与 `PostMessage` 两种投递）**都进不去**；
      - **键盘（`keybd_event`）进得去** —— Tab / Shift+Tab / Enter / Space 能改配色、
        开关紧凑模式、翻页、打开对话框。

    所以这个脚本只做键盘。鼠标那半边没留在代码里，但它的结论是有意的 ——
    它证明的是「不能靠鼠标」，而不是「没试过鼠标」。

    ⚠️ **SendInput 的文字通道实测是坏的**：无论成功与否它都可能**静默失败**
    （结构体大小不对 / 权限不够时返回 0），而「界面毫无反应」和「应用收不到输入」
    长得一模一样，最容易误判成后者。所以 Write-Text **检查返回值**，失败就退回
    `keybd_event` 逐字符（只支持 ASCII / 符号 —— 文件路径够用，中文打不了）。

.PARAMETER TargetProcessId
    目标 java 进程的 PID。用 `Get-Process java | ? MainWindowTitle -eq 'sundays'` 找；
    注意 Android Studio 打开同名项目时也会有一个 `sundays` 窗口，别认错。

.PARAMETER Actions
    逗号分隔的动作序列：
      TAB:3 / SHIFTTAB:2 / DOWN:2 / UP:2 / LEFT / RIGHT
      ENTER / SPACE / ESC / CTRLA / BACKSPACE:5
      TYPE:任意文本（含中文，走 KEYEVENTF_UNICODE）
      WAIT:800        等待毫秒
      SHOT:名字       截图到 -OutDir

.EXAMPLE
    # 启动（务必隔离 user.home，见 build.gradle.kts 的 sundaysUserHome）
    .\gradlew.bat :desktopApp:run -PsundaysUserHome=$env:TEMP\sundays-probe

    # 探一下
    .\tools\gui-probe.ps1 -TargetProcessId 6504 -Actions "SHOT:launch;TAB:1;ENTER;WAIT:800;SHOT:after"

.NOTES
    单选组（如明暗模式三档）在 Tab 序里**只占一站**，组内移动用方向键 ——
    盲按 Tab 计数时把 3 个单选当 3 站会一直够不到后面的按钮。
#>
param(
    [Parameter(Mandatory = $true)][int]$TargetProcessId,
    [string]$OutDir = "$PSScriptRoot\shots",
    [string]$Actions = "SHOT:start"
)

# ⚠️ PowerShell 里 $Pid / $HOME / $input 是**只读自动变量**，别拿来当形参或局部变量
#    （踩过一次：`-TargetPid` 直接报 "Cannot overwrite variable Pid"）。
#    这里所有形参与局部变量都刻意避开这些名字。

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Probe {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint f, IntPtr e);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION { [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION u; }
    [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
}
"@

$script:INPUT_KEYBOARD   = 1
$script:KEYEVENTF_KEYUP   = 0x0002
$script:KEYEVENTF_UNICODE = 0x0004

# SizeOf 必须传**实例**；传 Type 拿到的是 RuntimeType，Marshal 会直接抛。
$script:InputSize = [System.Runtime.InteropServices.Marshal]::SizeOf((New-Object Probe+INPUT))
$script:Rect = New-Object Probe+RECT
$script:Win = (Get-Process -Id $TargetProcessId -ErrorAction Stop).MainWindowHandle
if ($script:Win -eq [IntPtr]::Zero) { throw "进程 $TargetProcessId 没有主窗口" }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

function Set-Foreground {
    [void][Probe]::ShowWindow($script:Win, 3)   # SW_MAXIMIZE：窗口不够大时向导/对话框底部会被裁掉
    Start-Sleep -Milliseconds 450
    [void][Probe]::BringWindowToTop($script:Win)
    [void][Probe]::SetForegroundWindow($script:Win)
    Start-Sleep -Milliseconds 550
    [void][Probe]::GetWindowRect($script:Win, [ref]$script:Rect)
    "  [置前] 成功=$([Probe]::GetForegroundWindow() -eq $script:Win) " +
        "窗口=$($script:Rect.Right - $script:Rect.Left)x$($script:Rect.Bottom - $script:Rect.Top)"
}

function Send-Keys { param([int[]]$ks)
    foreach ($vk in $ks) {
        [Probe]::keybd_event([byte]$vk, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 25
        [Probe]::keybd_event([byte]$vk, 0, 2, [IntPtr]::Zero); Start-Sleep -Milliseconds 55
    }
}
function Press-Key { param([int]$vk, [int]$times) for ($i = 0; $i -lt $times; $i++) { Send-Keys @($vk) } }
function Press-ShiftKey { param([int]$vk, [int]$times)
    [Probe]::keybd_event(0x10, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 50
    Press-Key $vk $times
    [Probe]::keybd_event(0x10, 0, 2, [IntPtr]::Zero); Start-Sleep -Milliseconds 80
}

function Write-Text { param([string]$text)
    # 首选 SendInput + KEYEVENTF_UNICODE（能打任意字符，含中文）
    $buf = New-Object 'Probe+INPUT[]' ($text.Length * 2)
    for ($i = 0; $i -lt $text.Length; $i++) {
        $code = [uint16][int][char]$text[$i]
        $buf[$i * 2].type = $script:INPUT_KEYBOARD
        $buf[$i * 2].u.ki.wScan = $code
        $buf[$i * 2].u.ki.dwFlags = $script:KEYEVENTF_UNICODE
        $buf[$i * 2 + 1].type = $script:INPUT_KEYBOARD
        $buf[$i * 2 + 1].u.ki.wScan = $code
        $buf[$i * 2 + 1].u.ki.dwFlags = $script:KEYEVENTF_UNICODE -bor $script:KEYEVENTF_KEYUP
    }
    $sent = [Probe]::SendInput([uint32]$buf.Length, $buf, $script:InputSize)
    Start-Sleep -Milliseconds 320

    if ($sent -ne $buf.Length) {
        # ⚠️ SendInput 会**静默失败**：结构体大小不对 / 权限不够时返回 0，界面毫无反应 ——
        #   而「界面毫无反应」和「应用收不到输入」长得一模一样，极易误判成后者。
        #   所以这里检查返回值，并退回 keybd_event 逐字符（只支持 ASCII / 符号）。
        "  [TYPE 降级] SendInput 只送进 $sent/$($buf.Length) 个事件，改用 keybd_event"
        foreach ($ch in $text.ToCharArray()) { Send-AsciiChar $ch }
        Start-Sleep -Milliseconds 320
    }
}

# ASCII 字符 → 虚拟键码（带 Shift 的记第二个元素）。
# 只覆盖**路径**会用到的那批：字母数字、\ / : . - _ 与空格。中文打不了（需要 UNICODE 通道）。
function Send-AsciiChar { param([char]$ch)
    $shift = $false
    if ($ch -ge 'a' -and $ch -le 'z') { $vk = [int][char]([char]::ToUpperInvariant($ch)) }
    else {
        switch ($ch) {
            '0' { $vk = 0x30 } '1' { $vk = 0x31 } '2' { $vk = 0x32 } '3' { $vk = 0x33 } '4' { $vk = 0x34 }
            '5' { $vk = 0x35 } '6' { $vk = 0x36 } '7' { $vk = 0x37 } '8' { $vk = 0x38 } '9' { $vk = 0x39 }
            'A' { $vk = 0x41 } 'B' { $vk = 0x42 } 'C' { $vk = 0x43 } 'D' { $vk = 0x44 } 'E' { $vk = 0x45 }
            'F' { $vk = 0x46 } 'G' { $vk = 0x47 } 'H' { $vk = 0x48 } 'I' { $vk = 0x49 } 'J' { $vk = 0x4A }
            'K' { $vk = 0x4B } 'L' { $vk = 0x4C } 'M' { $vk = 0x4D } 'N' { $vk = 0x4E } 'O' { $vk = 0x4F }
            'P' { $vk = 0x50 } 'Q' { $vk = 0x51 } 'R' { $vk = 0x52 } 'S' { $vk = 0x53 } 'T' { $vk = 0x54 }
            'U' { $vk = 0x55 } 'V' { $vk = 0x56 } 'W' { $vk = 0x57 } 'X' { $vk = 0x58 } 'Y' { $vk = 0x59 }
            'Z' { $vk = 0x5A }
            ' ' { $vk = 0x20 }
            '/' { $vk = 0xBF }
            '\' { $vk = 0xDC }
            '.' { $vk = 0xBE }
            ',' { $vk = 0xBC }
            '-' { $vk = 0xBD }
            '_' { $vk = 0xBD; $shift = $true }
            ':' { $vk = 0xBA; $shift = $true }
            default { return }   # 打不了就跳过，别把整条文本丢掉
        }
    }
    if ($shift) { [Probe]::keybd_event(0x10, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 15 }
    [Probe]::keybd_event([byte]$vk, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 15
    [Probe]::keybd_event([byte]$vk, 0, 2, [IntPtr]::Zero); Start-Sleep -Milliseconds 15
    if ($shift) { [Probe]::keybd_event(0x10, 0, 2, [IntPtr]::Zero); Start-Sleep -Milliseconds 15 }
}

function Save-Shot { param([string]$name)
    $r = $script:Rect
    $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size($w, $h)))
    $g.Dispose()
    $path = Join-Path $OutDir "$name.png"
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    "  [截图] $path"
}

Set-Foreground

foreach ($raw in ($Actions -split ';')) {
    $raw = $raw.Trim(); if (-not $raw) { continue }
    $verb = $raw; $arg = $null
    if ($raw.Contains(':')) { $parts = $raw.Split(':', 2); $verb = $parts[0]; $arg = $parts[1] }
    $count = if ($arg -and $arg -match '^\d+$') { [int]$arg } else { 1 }
    switch ($verb.ToUpper()) {
        'TAB'       { Press-Key 0x09 $count; "  [TAB x$count]" }
        'SHIFTTAB'  { Press-ShiftKey 0x09 $count; "  [SHIFT+TAB x$count]" }
        'DOWN'      { Press-Key 0x28 $count; "  [DOWN x$count]" }
        'UP'        { Press-Key 0x26 $count; "  [UP x$count]" }
        'LEFT'      { Press-Key 0x25 $count; "  [LEFT x$count]" }
        'RIGHT'     { Press-Key 0x27 $count; "  [RIGHT x$count]" }
        'ENTER'     { Send-Keys @(0x0D); "  [ENTER]" }
        'SPACE'     { Send-Keys @(0x20); "  [SPACE]" }
        'ESC'       { Send-Keys @(0x1B); "  [ESC]" }
        'BACKSPACE' { Press-Key 0x08 $count; "  [BACKSPACE x$count]" }
        'CTRLA'     { [Probe]::keybd_event(0x11,0,0,[IntPtr]::Zero); Start-Sleep -Milliseconds 50
                       Send-Keys @(0x41)
                       [Probe]::keybd_event(0x11,0,2,[IntPtr]::Zero); "  [CTRL+A]" }
        'TYPE'      { Write-Text $arg; "  [TYPE] $arg" }
        'WAIT'      { Start-Sleep -Milliseconds $count; "  [WAIT ${count}ms]" }
        'SHOT'      { Save-Shot $arg }
        # 把鼠标挪到窗口右下角（通常是空白）**再截图**。
        #
        # ⚠️ 这一条不是可有可无的整洁癖，而是**判读的前提**：Compose 会给「鼠标悬停」
        # 的元素画高亮，而 `SetCursorPos` 挪过的指针会一直停在那儿。于是截图里
        # 「哪个元素亮着」= **鼠标在哪**，而不是「焦点在哪」—— 两者会分叉，
        # 于是把「焦点在名称框」读成「焦点在 POSTGRESQL 卡片」，
        # 整条 Tab 计数全部错位。踩过之后每张截图前都应该先 PARK。
        'PARK'      {
            $r = $script:Rect
            [void][Probe]::SetCursorPos($r.Right - 12, $r.Bottom - 12)
            Start-Sleep -Milliseconds 350
            "  [PARK] 鼠标移到 ($($r.Right - 12),$($r.Bottom - 12))"
        }
        default     { "  [未知动作] $raw" }
    }
    Start-Sleep -Milliseconds 250
}
"完成。"
