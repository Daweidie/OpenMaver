# HUD font source

`LucidaTypewriter.ttf` is the Latin source font for the in-game HUD/ClickGUI
atlas. It was copied from the Lucida fonts bundled with the Oracle/Mojang
legacy JRE (`lib/fonts/LucidaTypewriterRegular.ttf`).

Lucida is a registered trademark of Bigelow & Holmes. The file is kept here,
outside `mc/resources`, so it is **not** packaged into `openmaver-mod.jar`;
only the generated bitmap atlases under
`mc/resources/assets/openmaver/font/` are shipped.

Regenerate the atlases after changing this file or any UI string:

```sh
python3 tools/GenerateHudFontAtlas.py
```

To use a different Latin font without editing the script, point
`OPENMAVER_LATIN_FONT` at a TTF file:

```sh
OPENMAVER_LATIN_FONT=/path/to/lucon.ttf python3 tools/GenerateHudFontAtlas.py
```
