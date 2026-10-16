Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectDir=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$resourceDir=Join-Path $projectDir 'src/main/resources/assets/wireless_eu/textures/blocks/broadcaster'
$previewDir=Join-Path (Split-Path -Parent $projectDir) '设计草案/广播器材质-dev9去线'
[IO.Directory]::CreateDirectory($resourceDir) | Out-Null
[IO.Directory]::CreateDirectory($previewDir) | Out-Null
$palette=@{
    edge='#80808F'; line='#9696A7'; body='#C6C6DA'; hi='#E9E9F8'; shade='#ADADC2'
    inset='#B8B8CE'; dark='#777786'; rim='#36363B'; black='#151617'
    slot='#51545B'; steel='#979AA2'; lightsteel='#C2C5CC'
    signal='#BDC7D0'; yellow='#DAC36B'; yellowshade='#A18B44'
}
function Fill($g,$x,$y,$w,$h,$name) {
    $b=New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml($palette[$name]))
    $g.FillRectangle($b,[int]$x,[int]$y,[int]$w,[int]$h); $b.Dispose()
}
function Wifi($g,$x,$y,$name) {
    Fill $g ($x+3) $y 8 1 $name
    Fill $g ($x+1) ($y+1) 2 1 $name; Fill $g ($x+11) ($y+1) 2 1 $name
    Fill $g $x ($y+2) 1 2 $name; Fill $g ($x+13) ($y+2) 1 2 $name
    Fill $g ($x+4) ($y+3) 6 1 $name
    Fill $g ($x+2) ($y+4) 2 1 $name; Fill $g ($x+10) ($y+4) 2 1 $name
    Fill $g ($x+5) ($y+6) 4 1 $name
    Fill $g ($x+6) ($y+8) 2 1 $name
}
function Casing {
    $bmp=New-Object Drawing.Bitmap 32,32
    $g=[Drawing.Graphics]::FromImage($bmp)
    Fill $g 0 0 32 32 'edge'; Fill $g 2 0 28 32 'body'
    Fill $g 2 2 28 2 'hi'; Fill $g 2 28 28 2 'hi'
    Fill $g 2 4 2 24 'hi'; Fill $g 28 4 2 24 'shade'
    Fill $g 8 8 16 16 'inset'
    Fill $g 10 8 12 1 'hi'; Fill $g 8 10 1 12 'hi'
    Fill $g 22 10 1 12 'shade'; Fill $g 10 23 12 1 'shade'
    foreach($x in @(0,28)) { foreach($y in @(0,28)) { Fill $g $x $y 4 4 'edge' } }
    $g.Dispose(); return ,$bmp
}
function Face($kind,$tier) {
    $bmp=Casing; $g=[Drawing.Graphics]::FromImage($bmp)
    switch($kind) {
        'front' {
            Fill $g 5 5 22 21 'rim'; Fill $g 6 6 20 20 'black'
            Wifi $g 9 7 'signal'
            $bolt=@('    ### ','   ###  ','  ###   ',' ###### ','    ### ','   ###  ','  ###   ',' ###    ',' ##     ',' #      ')
            for($row=0;$row -lt $bolt.Length;$row++) {
                for($col=0;$col -lt $bolt[$row].Length;$col++) {
                    if($bolt[$row][$col] -eq '#') { Fill $g (12+$col) (16+$row) 1 1 'yellow' }
                }
            }
        }
        'top' {
            Fill $g 9 8 14 2 'shade'; Fill $g 9 10 14 1 'hi'
            Wifi $g 9 13 'line'
        }
        'side' {
            Fill $g 10 11 12 1 'shade'; Fill $g 10 12 12 1 'hi'
            Fill $g 10 15 12 1 'shade'; Fill $g 10 16 12 1 'hi'
            Fill $g 10 19 12 1 'shade'; Fill $g 10 20 12 1 'hi'
        }
        'rear' {
            Fill $g 9 9 14 14 'dark'; Fill $g 10 10 12 12 'shade'
            foreach($y in @(12,15,18)) { Fill $g 12 $y 8 1 'slot' }
            Fill $g 10 10 1 1 'lightsteel'; Fill $g 21 21 1 1 'edge'
        }
        'bottom' {
            Fill $g 10 10 12 12 'shade'; Fill $g 11 11 10 10 'inset'
            Fill $g 11 11 10 1 'hi'; Fill $g 11 12 1 9 'hi'
            foreach($x in @(9,21)) { Fill $g $x 9 2 2 'dark'; Fill $g $x 21 2 2 'dark' }
        }
    }
    if($tier -ne 3) {
        if($kind -eq 'side') {
            Fill $g 9 9 14 14 'inset'
            if($tier -le 5) {
                $count=$tier
                $start=15-[int][Math]::Floor(($count-1)*1.5)
                for($j=0;$j -lt $count;$j++) {
                    Fill $g 10 ($start+$j*3) 12 1 'shade'
                    Fill $g 10 ($start+$j*3+1) 12 1 'hi'
                }
            } elseif($tier -le 8) {
                for($j=0;$j -lt ($tier-3);$j++) {
                    $y=9+$j*3
                    Fill $g 9 $y 6 1 'shade'; Fill $g 9 ($y+1) 6 1 'hi'
                    Fill $g 17 $y 6 1 'shade'; Fill $g 17 ($y+1) 6 1 'hi'
                }
                Fill $g 15 10 1 12 'line'; Fill $g 16 10 1 12 'hi'
            } else {
                foreach($x in @(9,17)) { foreach($y in @(9,17)) {
                    Fill $g $x $y 6 6 'shade'
                    Fill $g ($x+1) ($y+1) 5 1 'hi'
                    Fill $g ($x+1) ($y+3) 4 1 'line'
                }}
                for($j=0;$j -lt ($tier-8);$j++) { Fill $g (10+$j*2) 25 1 1 'dark' }
                if($tier -ge 12) {
                    Fill $g 15 10 1 12 'dark'; Fill $g 16 10 1 12 'hi'
                }
            }
        }
        if($kind -eq 'top') {
            $pairs=1+[int][Math]::Floor(($tier-1)/3)
            for($j=0;$j -lt $pairs;$j++) {
                Fill $g (10+$j*3) 8 1 2 'dark'
            }
            if($tier -ge 4) { Fill $g 9 23 14 1 'shade'; Fill $g 9 24 14 1 'hi' }
            if($tier -ge 9) {
                foreach($x in @(9,21)) { foreach($y in @(11,22)) {
                    Fill $g $x $y 2 1 'dark'; Fill $g $x ($y+1) 1 1 'hi'
                }}
            }
        }
        if($kind -eq 'rear' -and $tier -ge 6) {
            Fill $g 12 10 8 1 'slot'; Fill $g 12 21 8 1 'slot'
            if($tier -ge 9) { Fill $g 15 10 2 12 'shade' }
        }
        if($kind -eq 'bottom' -and $tier -ge 9) {
            Fill $g 14 12 4 1 'line'; Fill $g 14 18 4 1 'line'
        }
    }
    $g.Dispose(); return ,$bmp
}

# Colors sampled from GTM 7.5.3, but all block pixels below are newly drawn.
# Columns: edge, seam, body, highlight, shade, inset, dark fasteners.
$colors=@(
    @('lv','312E29','4A4641','6D6963','85807B','56524D','635F59','3D3934'),
    @('mv','446265','63828B','8FB7CC','BED7E3','7294A2','83A9BA','526E73'),
    @('hv','80808F','9696A7','C6C6DA','E9E9F8','ADADC2','B8B8CE','777786'),
    @('ev','59525C','7D6C83','B392BE','D1ABD9','8F7897','A185AB','6B5F70'),
    @('iv','243335','42545F','76819F','8F9CB8','516375','63728C','344549'),
    @('luv','222222','5E5E5E','B9B9B9','DADADA','7D7D7D','9B9B9B','404040'),
    @('zpm','1D1A21','3B353B','514A50','6E6C6D','2E2931','464046','2E2931'),
    @('uv','263826','3E6047','577960','6B8D74','305239','4B6D54','25462D'),
    @('uhv','666666','9B9B9B','D9D9D9','E9E9E9','B2B2B2','C6C6C6','707070'),
    @('uev','1A4F2C','276E4F','399D83','45BCA5','2E7E61','338D71','215E3D'),
    @('uiv','8B3E07','B4690E','EFBE35','FDF55F','CD8511','E2A21D','9D500B'),
    @('uxv','500003','681624','812F3D','954351','5A0816','752331','4F000A'),
    @('opv','390D43','5C156C','891E91','A724A6','64166A','7B1B82','591355'),
    @('max','211C28','302A3C','483E59','544D6C','292332','40374F','302A3C')
)
$keys=@('edge','line','body','hi','shade','inset','dark')
$all=@()
for($i=0;$i -lt $colors.Length;$i++) {
    $row=$colors[$i]; $tier=$i+1
    for($j=0;$j -lt $keys.Length;$j++) { $palette[$keys[$j]]='#'+$row[$j+1] }
    $tierDir=Join-Path $resourceDir $row[0]
    [IO.Directory]::CreateDirectory($tierDir) | Out-Null
    $faces=@{}
    foreach($kind in @('front','top','side','rear','bottom')) {
        $faces[$kind]=Face $kind $tier
        $faces[$kind].Save((Join-Path $tierDir ($kind+'.png')),[Drawing.Imaging.ImageFormat]::Png)
    }
    $all+=,$faces
}
$mask=New-Object Drawing.Bitmap 32,32
for($y=0;$y -lt 32;$y++) { for($x=0;$x -lt 32;$x++) {
    $pixel=$all[2]['front'].GetPixel($x,$y)
    if($pixel.ToArgb() -eq [Drawing.ColorTranslator]::FromHtml($palette['yellow']).ToArgb() -or
       $pixel.ToArgb() -eq [Drawing.ColorTranslator]::FromHtml($palette['signal']).ToArgb()) {
        $mask.SetPixel($x,$y,$pixel)
    }
}}
$mask.Save((Join-Path $resourceDir 'front_emissive.png'),[Drawing.Imaging.ImageFormat]::Png)
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
Label $g 'Wireless EU / 全等级广播器材质 / 32×32' 28 18 30
Label $g 'GTM 等级配色 · Wi-Fi + 闪电 · 静态像素预览，未模拟游戏光照与自发光' 28 60 19
for($i=0;$i -lt $all.Length;$i++) {
    $faces=$all[$i]; $x=160+($i%7)*320; $y=150+[int][Math]::Floor($i/7)*420
    Label $g $colors[$i][0].ToUpper() ($x-120) ($y-26) 26
    $top=[Drawing.Point[]]@((New-Object Drawing.Point $x,$y),(New-Object Drawing.Point ($x+126),($y+63)),(New-Object Drawing.Point ($x-126),($y+63)))
    $front=[Drawing.Point[]]@((New-Object Drawing.Point ($x-126),($y+63)),(New-Object Drawing.Point $x,($y+126)),(New-Object Drawing.Point ($x-126),($y+214)))
    $side=[Drawing.Point[]]@((New-Object Drawing.Point $x,($y+126)),(New-Object Drawing.Point ($x+126),($y+63)),(New-Object Drawing.Point $x,($y+277)))
    $g.DrawImage($faces['top'],$top); $g.DrawImage($faces['front'],$front); $g.DrawImage($faces['side'],$side)
    $g.DrawImage($faces['front'],(New-Object Drawing.Rectangle ($x-80),($y+296),64,64),0,0,32,32,[Drawing.GraphicsUnit]::Pixel)
    $g.DrawImage($faces['side'],(New-Object Drawing.Rectangle ($x+16),($y+296),64,64),0,0,32,32,[Drawing.GraphicsUnit]::Pixel)
}
$sheet.Save((Join-Path $previewDir 'broadcaster-all-tiers.png'),[Drawing.Imaging.ImageFormat]::Png)
$g.Dispose();$sheet.Dispose()
$sheet=New-Object Drawing.Bitmap 1900,860
$g=GraphicsFor $sheet
Label $g '全等级面图：正面 / 顶面 / 侧面 / 背面 / 底面' 24 15 24
$faceNames=@('front','top','side','rear','bottom')
for($i=0;$i -lt $all.Length;$i++) {
    $x=24+$i*134
    Label $g $colors[$i][0].ToUpper() $x 65 20
    for($j=0;$j -lt 5;$j++) {
        $g.DrawImage($all[$i][$faceNames[$j]],(New-Object Drawing.Rectangle $x,(100+$j*148),128,128),0,0,32,32,[Drawing.GraphicsUnit]::Pixel)
    }
}
$sheet.Save((Join-Path $previewDir 'broadcaster-all-faces.png'),[Drawing.Imaging.ImageFormat]::Png)
$g.Dispose();$sheet.Dispose()
foreach($faces in $all) { foreach($bmp in $faces.Values) { $bmp.Dispose() } }
Write-Output ('Generated 70 tier textures + 1 emissive mask: '+$resourceDir)
Write-Output $previewDir
