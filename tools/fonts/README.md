# HUD font source

`NotoSansSC-Regular.ttf` is the source font for the in-game HUD/ClickGUI atlas.
It comes from `Noto-Sans-SC-Bold.zip` and supplies both Latin and Simplified
Chinese glyphs.
The file is kept here, outside `mc/resources`, so it is **not** packaged into
`openmaver-b2.2.0-YYYY-MM-DD.jar`; only the generated bitmap atlases under
`mc/resources/assets/openmaver/font/` are shipped.

`OpenSans-Regular.ttf`, `Smerkan-Bold.otf`, and `SourceCodePro-Regular.ttf`
remain available as legacy alternatives.

`LucidaTypewriter.ttf` is an older tracked atlas source. Other local test fonts
(for example Sansern or Segoe UI) are not committed because their licenses do
not allow redistribution.

Regenerate the atlases after changing the font file or any UI string:

```sh
python3 tools/GenerateHudFontAtlas.py
```

To use a different Latin font without editing the script, point
`OPENMAVER_LATIN_FONT` at a TTF/OTF file:

```sh
OPENMAVER_LATIN_FONT=/path/to/font.ttf python3 tools/GenerateHudFontAtlas.py
```
