# Changelog

## v0.1.13-alpha — 2026-11-23

- 新增「自定义组图」。在「导出论文图（SVG）」窗口的「子图」菜单进入「编排子图」。
- 可将 2–4 幅子图排成一行或两列，自动编号。支持当前网络、年度网络、关系数量柱状图和 PNG 示意图混排。
- 年度网络共用视角、坐标、配色和线宽标尺。柱状图沿用当前筛选，取景裁切不改变统计。
- 网络和柱状图保留可编辑的 SVG 元素。PNG 示意图嵌入文件，支持透明背景和黑白预览。

## v0.1.12-alpha — 2026-11-22

- 新增「论文样式」设置。在「导出论文图（SVG）」窗口，入口位于「成图宽度」和「标题」之间。
- 支持沿用当前、论文浅色和黑白打印三套配色，并可选择字体、按毫米设置页边距。
- 彩色模式可单独指定重点节点和重点关系颜色。黑白年度叠加对比使用虚线标出基准年独有的关系。
- 整页预览与可编辑 SVG 使用同一套样式。单图和双年份组图均可使用。

## v0.1.11-alpha — 2026-11-22

- 去掉论文图图注前单独的「注」字。
- 默认隐藏自动说明，图下方显示图例。需要时仍可开启自动说明或填写自定义图注。
- 本版包含 0.1.10 的整页预览、重点标注和双年份组图。

## v0.1.10-alpha — 2026-11-22

- 整页预览可按毫米设定版面，实时调整节点、线宽、箭头和字号。
- 支持手动标注重点企业、淡化背景连线，并分别编辑图例和图注。
- 双年份组图共用视角、节点位置、配色和线宽标尺。
- 论文图例将两档线宽合为一组，自动图注采用更紧凑的版式。
- 此版同时包含此前在本地完成的表格自动导入和论文图预览功能。

## v0.1.8-alpha — 2026-11-22

- Add directed supplier/customer exploration over one or two hops, geographic coverage and filter diagnostics.
- Add reusable project figure presets and SVG metadata for reproducing the view.
- Preserve annual relationship records through import and compare two years with added, removed and shared edges.
- Add MCP tools for supply-chain scope, clearing a blocking criterion, figure presets and years.

## v0.1.7-alpha — 2026-11-21

- Add desktop graph filters for node name or code, city, community, minimum degree/weight, and one- or two-hop neighborhoods.
- Add a layer visibility dialog and shared visibility rules for the viewport and exports.
- Add categorical and continuous node coloring by an existing field.
- Add editable SVG legend entries and top/bottom positioning. Save view settings in `.gephi` projects.
- Extend the MCP filter tool and add tools for layer visibility, field coloring and paper legends.

## v0.1.6-alpha — 2026-11-21

- 在三维面板加入「导出论文图（SVG）」，可填写图题、成图宽度和箭头含义。节点、连线、箭头及文字为可编辑对象。
- 精简图例，突出选中节点和一阶关系，支持现有最短路径高亮，淡化背景并在需要时为重点关系重新取景。地理视图注明缺少城市定位的重点关系。
- 加入 `gephi3d_export_paper`，随发行包提供 29 个 MCP 工具和 Gephi 模块 0.1.6。

## v0.1.5-alpha — 2026-11-20

- Toggle directed arrowheads in ordinary and geographic views.
- Adjust arrowhead size and line width separately while retaining weight differences.
- Save appearance settings with the Gephi project and include them in exports.
- Back up and upgrade the desktop module from the previous release. This release bundles module 0.1.4.

## v0.1.4-alpha — 2026-11-20

- Install the Gephi module for the current Windows user by default. Start Gephi from its desktop or Start menu shortcut to draw with Codex.
- Keep the independent launcher and add an explicit mode for connecting Codex to it.
- Preview and back up explicit connection replacements when switching installations or modes. The bundled Gephi module version is 0.1.3.

## v0.1.3-alpha — 2026-11-20

- Add an optional, per-workspace geographic view with a manual toggle that defaults to off. Restore the ordinary layout and camera when switching back.
- Match companies by city code using a local GeoJSON basemap and city-coordinate table. Accept Stata `.dta` or CSV edge data, or static GEXF with city-code attributes. Report unmatched codes.
- Highlight chain leaders and enlarge their communities. Use edge direction for arrowheads and weight for line width.
- Add one-call 3D drawing from a two-sheet XLSX, static GEXF or a synthetic example, with optional PNG export.
- Add community, weighted and keyword presets with clearer labels. Persist styles and optional basemaps in Gephi projects.

## v0.1.1-alpha — 2026-10-21

- Default to plugin API operations; desktop control requires an explicit user request, including when importing or checking the rendered result.
- Add `gephi3d_import_graph`, bringing the MCP bridge to 25 tools. Import local static GEXF into a fresh workspace, preserving IDs, attributes, direction, weights, colors and supplied XYZ.
- Validate GEXF before committing; retain existing workspaces, report import warnings and deduplicate repeated import requests.
- Report connection, authentication and old-plugin capability failures without desktop fallback. Show the panel without requesting keyboard focus for API actions.
- Add isolated real-Gephi import acceptance and transport failure tests. The module specification version is now `0.1.1`.

## v0.1.0-alpha — 2026-10-21

- Initial local distribution of the Gephi 0.11.3 module, 24-tool MCP bridge and Chinese Agent Skill.
- Real 3D orbit/pan/zoom, selection, node search, neighborhood/path highlighting, layers, pinning, bookmarks and scene history.
- Synthetic community/DAG/hub templates; octree FR layout; project scene persistence; PNG/PDF exports.
- Portable Windows setup, isolated profile, configuration preview/conflict detection, explicit release manifest and SHA-256 checksums.

Feature limits and verified platforms are listed in the README.
