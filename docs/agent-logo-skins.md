# Agent logo skins

The bundled branded agent PNGs are 512×512 Minecraft textures with the vanilla
64×64 UV layout preserved. The visible 8×8 head face therefore has a 64px raster
for the logo, while ordinary player and mob textures are not changed. The marks
are rasterized from the referenced online logo sources rather than redrawn as
approximate pixel-art silhouettes:

- [OpenAI Design Guidelines](https://openai.com/brand/)
- [OpenAI vector mark used for rasterization](https://github.com/simple-icons/simple-icons/blob/develop/icons/openai.svg)
- [Anthropic Claude](https://www.anthropic.com/claude)
- [Claude app icon source](https://claude.ai/images/claude_app_icon.png)
- [Claude vector mark](https://cdn.jsdelivr.net/gh/glincker/thesvg@main/public/icons/claude-ai/default.svg)
- [DeepSeek Terms of Use](https://cdn.deepseek.com/policies/en-US/deepseek-terms-of-use.html)
- [DeepSeek vector mark](https://github.com/simple-icons/simple-icons/blob/develop/icons/deepseek.svg)
- [Cursor official brand guidelines](https://cursor.com/brand) and [source assets](https://ptht05hbb1ssoooe.public.blob.vercel-storage.com/assets/brand/cursor-brand-assets.zip), `General Logos/Cube/PNG/CUBE_2D_LIGHT.png`
- [Google Brand Resource Center](https://about.google/brand-resource-center/)
- [Gemini icon source](https://commons.wikimedia.org/wiki/File:Google-gemini.svg)

Every logo gets a 56x56 raster inside the 64px face square with a 4px clear margin,
placed on the front face only. Codex agents wear the OpenAI skin, Claude agents
(and Gemini's Antigravity-hosted Claude models) wear the Claude skin, and the other
Gemini models wear the Gemini skin. The dominant body swatches
use these reference anchors, with dark edge shading only for model readability:

These higher-resolution files are consumed by the mod's renderer. They are not
drop-in replacements for the vanilla skin-upload screen, which expects the
standard skin layout.

| Skin | Body / primary anchor |
| --- | --- |
| ChatGPT / OpenAI | `#10A37F` |
| Claude | `#D97757` |
| DeepSeek | white body with `#5786FE` blue |
| Gemini | Google blue `#4285F4` with darker `#2553A0` shading, purple `#A142F4`, green `#34A853`, and red `#EA4335` |

These references remain attribution and design-reference links only. No external
asset is fetched during the build. Use of a company's marks may require permission
under that company's terms. The pixel approximations should not imply endorsement.
