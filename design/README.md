# OpenMaver ClickGUI · 视觉设计稿

参考 Vape / Zenith 的现代极简风格：深色半透明毛玻璃、圆角面板、细霓虹青蓝描边、
左侧分类栏 + 右侧高密度模块列表。全部为**正视视角、零透视**的平面设计。

## 文件

| 文件 | 说明 |
| --- | --- |
| `clickgui.html` | 单文件高保真原型，无外部依赖，可直接双击打开 |
| `clickgui-combat-8k.png` | 7680 × 4800（Combat 分类，Aim Assist 展开） |
| `clickgui-render-4k.png` | 3840 × 2400（Render 分类，Player ESP 展开，紫色分类强调） |
| `export.sh` | 用无头 Firefox 重新导出图片 |

## 画板与网格

- 画板：`1600 × 1000`（16:10）
- 面板：`1400 × 860`，圆角 `18px`，居中留白 `100 / 70`
- 分类栏：`258px`；内容区左右留白 `26px`
- 模块行：`54px` 高，行间距 `7px`，圆角 `10px`
- 展开设置区：三列网格，间距 `13 × 22`，滑块轨道 `3px`，滑块点 `10px`
- 字号：正文 `11–13px`，标题 `22px`，微型标签 `8–9px`（大写 + 字距）
- 数值、键位全部使用等宽字体

## 色板

| 用途 | 色值 |
| --- | --- |
| 画布底 | `#04060A` |
| 面板玻璃 | `rgba(9,13,20,.74)` + `backdrop-filter: blur(30px)` |
| 霓虹描边 | `rgba(116,231,255,.28)` |
| 主文字 / 次文字 / 弱文字 | `#EEF3FB` / `#A5AFBF` / `#6B7586` |
| 主强调色 | `#5CE1FF` |
| 分类强调 | Combat `#5CE1FF` · Movement `#6E9BFF` · Render `#B18CFF` · Player `#5BF0B0` · Misc `#FFC46B` |
| 开启态绿 | `#5BF0B0` |

字体：`Noto Sans`（UI）+ `JetBrains Mono`（数值 / 键位）。导出机器缺少字体时会回退到系统无衬线 / 等宽字体。

## 交互（HTML 原型）

- 顶部搜索：`⌘K` / `Ctrl K` 聚焦，实时过滤当前分类
- `ALL / ENABLED / DISABLED` 分段筛选，右侧 A–Z 排序可切换
- 左侧分类切换，强调色随分类变化
- 点击左侧圆点开关模块；点击模块行展开 / 收起设置
- 滑块可拖动，复选框可勾选；展开区数字即时更新
- 底部状态栏与右上 Profile 卡的启用数量会随开关联动

深链接（方便出图）：

```
clickgui.html?cat=render&open=esp
clickgui.html?scale=2          # 2x 导出
clickgui.html?scale=4.8        # 8K 导出
```

## 重新导出

```bash
./export.sh
```

依赖：`firefox`（无头截图）、`python3`（仅用于打印尺寸）。脚本会为每张图创建独立临时
profile，因此不会干扰正在运行的 Firefox。
