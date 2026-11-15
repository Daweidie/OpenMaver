# HUD font source

`SourceCodePro-Regular.ttf` is the Latin source font for the in-game
HUD/ClickGUI atlas. Source Code Pro is copyright Adobe and licensed under the
SIL Open Font License 1.1. The file is kept here, outside `mc/resources`, so it
is **not** packaged into `openmaver-mod.jar`; only the generated bitmap atlases
under `mc/resources/assets/openmaver/font/` are shipped.

`LucidaTypewriter.ttf` is the previous tracked atlas source and is kept as a
legacy alternative. Other local test fonts (for example Sansern or Segoe UI)
are not committed because their licenses do not allow redistribution.

Regenerate the atlases after changing the font file or any UI string:

```sh
python3 tools/GenerateHudFontAtlas.py
```

To use a different Latin font without editing the script, point
`OPENMAVER_LATIN_FONT` at a TTF/OTF file:

```sh
OPENMAVER_LATIN_FONT=/path/to/font.ttf python3 tools/GenerateHudFontAtlas.py
```
