param([string]$PreviewDirectory = 'work/zhixu-visual-20261003')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$res = Join-Path $project 'android/app/src/main/res'
$ns = 'http://schemas.android.com/apk/res/android'
$colors = @{}
foreach ($file in Get-ChildItem (Join-Path $res 'values') -Filter '*.xml') {
    [xml]$doc = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    foreach ($node in $doc.SelectNodes('/resources/color')) { $colors[$node.name] = $node.InnerText }
}
function ColorOf([string]$value) {
    while ($value.StartsWith('@color/')) { $value = $colors[$value.Substring(7)] }
    if (-not $value) { throw 'Unresolved vector color' }
    return [System.Drawing.ColorTranslator]::FromHtml($value)
}
function Attr($node, [string]$name) { return $node.GetAttribute($name, $ns) }
function Number([string]$value) { return [float]::Parse($value, [Globalization.CultureInfo]::InvariantCulture) }
function PathOf([string]$data) {
    $tokens = [regex]::Matches($data, '[MCLZ]|-?\d+(?:\.\d+)?')
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $i = 0; $x = 0.0; $y = 0.0
    while ($i -lt $tokens.Count) {
        $command = $tokens[$i++].Value
        switch ($command) {
            M { $x = Number $tokens[$i++].Value; $y = Number $tokens[$i++].Value; $path.StartFigure() }
            L {
                $nx = Number $tokens[$i++].Value; $ny = Number $tokens[$i++].Value
                $path.AddLine([float]$x, [float]$y, $nx, $ny); $x = $nx; $y = $ny
            }
            C {
                $x1 = Number $tokens[$i++].Value; $y1 = Number $tokens[$i++].Value
                $x2 = Number $tokens[$i++].Value; $y2 = Number $tokens[$i++].Value
                $nx = Number $tokens[$i++].Value; $ny = Number $tokens[$i++].Value
                $path.AddBezier([float]$x, [float]$y, $x1, $y1, $x2, $y2, $nx, $ny)
                $x = $nx; $y = $ny
            }
            Z { $path.CloseFigure() }
            default { throw "Unsupported path command: $command" }
        }
    }
    return ,$path
}
function DrawVector($graphics, [string]$file) {
    [xml]$vector = Get-Content -LiteralPath $file -Raw -Encoding UTF8
    foreach ($node in $vector.SelectNodes('/vector/path')) {
        $path = PathOf (Attr $node 'pathData')
        $gradient = $node.SelectSingleNode('.//gradient')
        if ($gradient) {
            $items = @($gradient.SelectNodes('item'))
            $start = if ($items.Count) { ColorOf (Attr $items[0] 'color') } else { ColorOf (Attr $gradient 'startColor') }
            $end = if ($items.Count) { ColorOf (Attr $items[-1] 'color') } else { ColorOf (Attr $gradient 'endColor') }
            $brush = [Drawing.Drawing2D.LinearGradientBrush]::new(
                [Drawing.PointF]::new((Number (Attr $gradient 'startX')), (Number (Attr $gradient 'startY'))),
                [Drawing.PointF]::new((Number (Attr $gradient 'endX')), (Number (Attr $gradient 'endY'))), $start, $end)
            if ($items.Count) {
                $blend = [Drawing.Drawing2D.ColorBlend]::new($items.Count)
                $blend.Colors = [Drawing.Color[]]@($items | ForEach-Object { ColorOf (Attr $_ 'color') })
                $blend.Positions = [float[]]@($items | ForEach-Object { Number (Attr $_ 'offset') })
                $brush.InterpolationColors = $blend
            }
        } else { $brush = [Drawing.SolidBrush]::new((ColorOf (Attr $node 'fillColor'))) }
        try { $graphics.FillPath($brush, $path) } finally { $brush.Dispose(); $path.Dispose() }
    }
}
function ExportIcon([int]$size, [bool]$round, [string]$destination) {
    $side = $size * 4
    $bitmap = [Drawing.Bitmap]::new($side, $side, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [Drawing.Graphics]::FromImage($bitmap)
    $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $mask = [Drawing.Drawing2D.GraphicsPath]::new()
    if ($round) { $mask.AddEllipse(0, 0, $side, $side) } else {
        $diameter = [float]($side * .4)
        $mask.AddArc(0, 0, $diameter, $diameter, 180, 90)
        $mask.AddArc(($side - $diameter), 0, $diameter, $diameter, 270, 90)
        $mask.AddArc(($side - $diameter), ($side - $diameter), $diameter, $diameter, 0, 90)
        $mask.AddArc(0, ($side - $diameter), $diameter, $diameter, 90, 90)
        $mask.CloseFigure()
    }
    $g.SetClip($mask)
    # Adaptive icon visible viewport: 72 of 108 units, centered; same glyph scale.
    $matrix = [Drawing.Drawing2D.Matrix]::new([float]($side / 72), 0, 0, [float]($side / 72), [float](-18 * $side / 72), [float](-18 * $side / 72))
    $g.Transform = $matrix
    DrawVector $g (Join-Path $res 'drawable/ic_launcher_background.xml')
    DrawVector $g (Join-Path $res 'drawable/ic_launcher_foreground.xml')
    $g.Dispose(); $mask.Dispose(); $matrix.Dispose()
    $output = [Drawing.Bitmap]::new($size, $size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $og = [Drawing.Graphics]::FromImage($output)
    $og.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
    $og.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $og.DrawImage($bitmap, 0, 0, $size, $size)
    $og.Dispose(); $bitmap.Dispose()
    try { $output.Save($destination, [Drawing.Imaging.ImageFormat]::Png) } finally { $output.Dispose() }
}
foreach ($density in @(@('mdpi',48), @('hdpi',72), @('xhdpi',96), @('xxhdpi',144), @('xxxhdpi',192))) {
    $directory = Join-Path $res ('mipmap-' + $density[0])
    ExportIcon $density[1] $false (Join-Path $directory 'ic_launcher.png')
    ExportIcon $density[1] $true (Join-Path $directory 'ic_launcher_round.png')
}
$preview = Join-Path $project $PreviewDirectory
New-Item -ItemType Directory -Path $preview -Force | Out-Null
ExportIcon 512 $false (Join-Path $preview 'launcher-preview.png')
ExportIcon 512 $true (Join-Path $preview 'launcher-round-preview.png')
Write-Output 'Exported 10 density assets and 2 previews from native vector resources.'
