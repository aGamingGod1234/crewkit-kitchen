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
- [Google Brand Resource Center](https://about.google/brand-resource-center/)
- [Gemini icon source](https://commons.wikimedia.org/wiki/File:Google-gemini.svg)
- [KIMI Brand Guidelines](https://moonshotai.github.io/Branding-Guide/)
- [KIMI Brand Guidelines](https://moonshotai.github.io/Branding-Guide/) (the supplied K-with-dot reference is used for the raster)

Most logos get a 56x56 raster inside the 64px face square with a 4px clear margin;
KIMI uses the supplied complete 64x64 black-background face tile. Every mark is
placed on the front face only. The dominant body swatches
use these reference anchors, with dark edge shading only for model readability:

These higher-resolution files are consumed by the mod's renderer. They are not
drop-in replacements for the vanilla skin-upload screen, which expects the
standard skin layout.

| Skin | Body / primary anchor |
| --- | --- |
| ChatGPT / OpenAI | `#10A37F` |
| Claude | `#D97757` |
| DeepSeek | white body with `#5786FE` blue |
| Gemini | `#8AB4F8` light blue with Google blue `#4285F4`, purple `#A142F4`, green `#34A853`, and red `#EA4335` |
| Kimi | `#1783FF` blue with `#004BAA` shading |

These references remain attribution and design-reference links only. No external
asset is fetched during the build. Use of a company's marks may require permission
under that company's terms. The pixel approximations should not imply endorsement.
