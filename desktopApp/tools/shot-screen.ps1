param(
  [string]$OutDir = "C:\Users\lbty\project\Sundays\build\tmp\shots",
  [string]$Name = "screen"
)

# 整屏截图。
#
# 什么时候必须用它而不是 shot-window.ps1：
#   窗口比屏幕高（应用默认 1440x900 物理 > 工作区 824）时，窗口底部落在屏幕外，
#   截窗口只能截到可见的那部分，看不到「下一步」按钮被顶到哪儿去了；
#   而且 GetWindowRect 在 DPI 感知 / 虚拟化 / 最大化几种状态下会和实际画面对不上，
#   此时按窗口矩形推算坐标只会点空。整屏没有这些换算，最可靠。
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
[void][System.Windows.Forms.Screen]::AllScreens

$vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap $vs.Width, $vs.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($vs.X, $vs.Y, 0, 0, (New-Object System.Drawing.Size($vs.Width, $vs.Height)))
$g.Dispose()
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$path = Join-Path $OutDir "$Name.png"
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"整屏 ${($vs.Width)}x${($vs.Height)}（虚拟屏幕，左上角 $($vs.X),$($vs.Y)），截图：$path"
