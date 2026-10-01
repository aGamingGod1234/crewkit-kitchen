# Regenerate the 16px waypoint sprites from the same skins used for agent bodies.
# Minecraft skins place the front of the head at (8, 8) in a 64x64 layout
# and its optional outer layer at (40, 8). The bundled skins are 8x scale.
Add-Type -AssemblyName System.Drawing
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$assets = Join-Path $root 'src/main/resources/assets/arenaagents'
$manifest = Get-Content -Raw -LiteralPath (Join-Path $assets 'identity/agent_visual_manifest.json') | ConvertFrom-Json
$brands = @{}
foreach ($brand in $manifest.brandSkins) { $brands[$brand.key] = $brand }
$targetDirectory = Join-Path $assets 'textures/gui/sprites/hud/locator_bar_dot/agent'

foreach ($provider in $manifest.providers) {
    foreach ($family in $provider.families) {
        for ($variant = 0; $variant -lt $family.variants.Count; $variant++) {
            $brandKey = switch ($provider.key) {
                'codex' { 'openai' }
                'gemini' { if ($family.key -eq 'claude') { 'claude' } else { 'gemini' } }
                default { $provider.key }
            }
            $skinId = $brands[$brandKey].variants[$variant].texturePath.Split(':', 2)[1]
            $skinPath = Join-Path $assets ($skinId.Replace('/', [IO.Path]::DirectorySeparatorChar))
            $skin = [System.Drawing.Bitmap]::new($skinPath)
            try {
                if ($skin.Width -ne $skin.Height -or $skin.Width % 64 -ne 0) {
                    throw "Invalid Minecraft skin size: $skinPath"
                }
                $scale = [int]($skin.Width / 64)
                $icon = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
                try {
                    for ($y = 0; $y -lt 16; $y++) {
                        for ($x = 0; $x -lt 16; $x++) {
                            $pixelX = [int][Math]::Floor($x / 2)
                            $pixelY = [int][Math]::Floor($y / 2)
                            $offset = [int][Math]::Floor($scale / 2)
                            $base = $skin.GetPixel((8 + $pixelX) * $scale + $offset, (8 + $pixelY) * $scale + $offset)
                            $outer = $skin.GetPixel((40 + $pixelX) * $scale + $offset, (8 + $pixelY) * $scale + $offset)
                            $alpha = $outer.A / 255.0
                            $color = [System.Drawing.Color]::FromArgb(
                                255,
                                [int][Math]::Round($outer.R * $alpha + $base.R * (1 - $alpha)),
                                [int][Math]::Round($outer.G * $alpha + $base.G * (1 - $alpha)),
                                [int][Math]::Round($outer.B * $alpha + $base.B * (1 - $alpha)))
                            $icon.SetPixel($x, $y, $color)
                        }
                    }
                    $target = Join-Path $targetDirectory ($family.variants[$variant].transportCode + '.png')
                    $icon.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
                } finally { $icon.Dispose() }
            } finally { $skin.Dispose() }
        }
    }
}
