Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectDir=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$resourceDir=Join-Path $projectDir 'src/main/resources/assets/wireless_eu/textures/blocks/broadcaster'
$previewDir=Join-Path (Split-Path -Parent $projectDir) '设计草案/GTCEu-16x-全等级-dev12'
[IO.Directory]::CreateDirectory($resourceDir) | Out-Null
[IO.Directory]::CreateDirectory($previewDir) | Out-Null
$tiers=@('lv','mv','hv','ev','iv','luv','zpm','uv','uhv','uev','uiv','uxv','opv','max')
$wifi=@('   #####   ',' ##     ## ','#         #','   #####   ','  #     #  ','    ###    ','   #   #   ','           ','     #     ')
$bolt=@('    ###','   ### ','  ###  ',' ###   ','#######','   ### ','  ###  ','  ##   ',' ##    ',' #     ','#      ')
function PixelBox($g,$x,$y,$w,$h,$hex) {
    $brush=New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml($hex))
    $g.FillRectangle($brush,[int]$x,[int]$y,[int]$w,[int]$h)
    $brush.Dispose()
}
function Glyph($g,$rows,$x,$y,$hex) {
    for($gy=0;$gy -lt $rows.Length;$gy++) {
        for($gx=0;$gx -lt $rows[$gy].Length;$gx++) {
            if($rows[$gy][$gx] -eq '#') { PixelBox $g ($x+$gx) ($y+$gy) 1 1 $hex }
        }
    }
}
# Uses GTCEu edge structure as reference, with a continuous redrawn metal surface.
# Source: local GTCEu 2.8.7-beta, build #2901, assets/gregtech/textures/blocks/casings/voltage.
$jar=[IO.Compression.ZipFile]::OpenRead((Join-Path $projectDir 'libs/gregtech-1.12.2-2901.jar'))
function ReadCasing($tier,$face) {
    $stream=$jar.GetEntry('assets/gregtech/textures/blocks/casings/voltage/'+$tier+'/'+$face+'.png').Open()
    try {
        $original=[Drawing.Bitmap]::FromStream($stream)
        $copy=New-Object Drawing.Bitmap $original
        $original.Dispose()
        return ,$copy
    } finally { $stream.Dispose() }
}
function CleanSurface($bmp,$baseTone) {
    # Redraw one continuous metal surface; there is no separately colored icon badge.
    for($y=0;$y -lt 16;$y++) {
        for($x=0;$x -lt 16;$x++) {
            $offset=[int][Math]::Floor((($x-2)+($y-2))*0.3)
            $tone=[Math]::Max(0,[Math]::Min(255,$baseTone+3-$offset))
            if($x -lt 2 -or $x -ge 14 -or $y -lt 2 -or $y -ge 14) {
                $original=[int]$bmp.GetPixel($x,$y).R
                $tone=[Math]::Max($baseTone-22,[Math]::Min($baseTone+14,$original))
            }
            $bmp.SetPixel($x,$y,[Drawing.Color]::FromArgb(255,$tone,$tone,$tone))
        }
    }
}
$all=@()
try {
    foreach($tier in $tiers) {
        $tierDir=Join-Path $resourceDir $tier
        [IO.Directory]::CreateDirectory($tierDir) | Out-Null
        $faces=@{
            front=(ReadCasing $tier 'side'); top=(ReadCasing $tier 'top')
            side=(ReadCasing $tier 'side'); rear=(ReadCasing $tier 'side')
            bottom=(ReadCasing $tier 'bottom')
        }
        $samples=New-Object 'System.Collections.Generic.List[int]'
        for($y=0;$y -lt 16;$y++) {
            for($x=0;$x -lt 16;$x++) {
                if($x -lt 2 -or $x -ge 14 -or $y -lt 2 -or $y -ge 14) {
                    $samples.Add([int]$faces['front'].GetPixel($x,$y).R)
                }
            }
        }
        $samples.Sort()
        $tone=[Math]::Max(155,[Math]::Min(239,$samples[[int][Math]::Floor($samples.Count*0.75)]-10))
        foreach($face in @('front','top','side','rear')) { CleanSurface $faces[$face] $tone }
        $g=[Drawing.Graphics]::FromImage($faces['front'])
        Glyph $g $wifi 2 3 '#292929'
        $g.Dispose()
        foreach($face in @('top','side','rear')) {
            $g=[Drawing.Graphics]::FromImage($faces[$face])
            Glyph $g $bolt 4 2 '#CB951E'
            $g.Dispose()
        }
        foreach($face in @('front','top','side','rear','bottom')) {
            $faces[$face].Save((Join-Path $tierDir ($face+'.png')),[Drawing.Imaging.ImageFormat]::Png)
        }
        $all+=,$faces
    }
} finally { $jar.Dispose() }
$mask=New-Object Drawing.Bitmap 16,16
$g=[Drawing.Graphics]::FromImage($mask)
Glyph $g $wifi 2 3 '#292929'
$g.Dispose()
$mask.Save((Join-Path $resourceDir 'front_emissive.png'),[Drawing.Imaging.ImageFormat]::Png)
$mask.Dispose()
$mask=New-Object Drawing.Bitmap 16,16
$g=[Drawing.Graphics]::FromImage($mask)
Glyph $g $bolt 4 2 '#CB951E'
$g.Dispose()
$mask.Save((Join-Path $resourceDir 'bolt_emissive.png'),[Drawing.Imaging.ImageFormat]::Png)
$mask.Dispose()
function Label($g,$s,$x,$y,$size) {
    $font=New-Object Drawing.Font 'Microsoft YaHei UI',$size,([Drawing.FontStyle]::Regular),([Drawing.GraphicsUnit]::Pixel)
    $g.DrawString($s,$font,[Drawing.Brushes]::LightGray,[single]$x,[single]$y); $font.Dispose()
}
function GraphicsFor($bmp) {
    $g=[Drawing.Graphics]::FromImage($bmp)
    $g.Clear([Drawing.Color]::FromArgb(45,48,52))
    $g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode=[Drawing.Drawing2D.PixelOffsetMode]::Half
    $g.TextRenderingHint=[Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    return ,$g
}
$sheet=New-Object Drawing.Bitmap 2240,1000
$g=GraphicsFor $sheet
Label $g 'Wireless EU / 全等级广播器材质 / 16×16' 28 18 30
Label $g 'GTCEu 风格专用机壳，无中央圆点 / 圆槽 / 图标底板 · 静态预览，未模拟游戏光照与自发光' 28 60 19
for($i=0;$i -lt $all.Length;$i++) {
    $faces=$all[$i]; $x=160+($i%7)*320; $y=150+[int][Math]::Floor($i/7)*420
    Label $g $tiers[$i].ToUpper() ($x-120) ($y-26) 26
    $top=[Drawing.Point[]]@((New-Object Drawing.Point $x,$y),(New-Object Drawing.Point ($x+126),($y+63)),(New-Object Drawing.Point ($x-126),($y+63)))
    $front=[Drawing.Point[]]@((New-Object Drawing.Point ($x-126),($y+63)),(New-Object Drawing.Point $x,($y+126)),(New-Object Drawing.Point ($x-126),($y+214)))
    $side=[Drawing.Point[]]@((New-Object Drawing.Point $x,($y+126)),(New-Object Drawing.Point ($x+126),($y+63)),(New-Object Drawing.Point $x,($y+277)))
    $g.DrawImage($faces['top'],$top); $g.DrawImage($faces['front'],$front); $g.DrawImage($faces['side'],$side)
    $g.DrawImage($faces['front'],(New-Object Drawing.Rectangle ($x-80),($y+296),64,64),0,0,16,16,[Drawing.GraphicsUnit]::Pixel)
    $g.DrawImage($faces['side'],(New-Object Drawing.Rectangle ($x+16),($y+296),64,64),0,0,16,16,[Drawing.GraphicsUnit]::Pixel)
}
$sheet.Save((Join-Path $previewDir 'broadcaster-all-tiers.png'),[Drawing.Imaging.ImageFormat]::Png)
$g.Dispose();$sheet.Dispose()
$sheet=New-Object Drawing.Bitmap 1900,860
$g=GraphicsFor $sheet
Label $g '全等级面图：正面 / 顶面 / 侧面 / 背面 / 底面' 24 15 24
$faceNames=@('front','top','side','rear','bottom')
for($i=0;$i -lt $all.Length;$i++) {
    $x=24+$i*134
    Label $g $tiers[$i].ToUpper() $x 65 20
    for($j=0;$j -lt 5;$j++) {
        $g.DrawImage($all[$i][$faceNames[$j]],(New-Object Drawing.Rectangle $x,(100+$j*148),128,128),0,0,16,16,[Drawing.GraphicsUnit]::Pixel)
    }
}
$sheet.Save((Join-Path $previewDir 'broadcaster-all-faces.png'),[Drawing.Imaging.ImageFormat]::Png)
$g.Dispose();$sheet.Dispose()
foreach($faces in $all) { foreach($bmp in $faces.Values) { $bmp.Dispose() } }
Write-Output ('Generated 70 tier textures + 2 emissive masks: '+$resourceDir)
Write-Output $previewDir
