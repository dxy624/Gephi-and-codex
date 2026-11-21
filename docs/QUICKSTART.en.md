# English quick start

This Alpha targets Windows, Gephi **0.11.3 or 0.11.4**, and Python **3.12**. Use the bundled Java 17 runtime. A separate JDK is needed only to compile source.

Save your work and close desktop Gephi. Extract the release into a permanent folder and open PowerShell there.

```powershell
py -3.12 scripts/setup.py --install-skill --configure-codex --dry-run
py -3.12 scripts/setup.py --install-skill --configure-codex
```

Setup installs the module for the current Windows user and creates a project `.venv`. Pass `--gephi "installation directory"` or set `GEPHI_HOME` if discovery fails. The two flags install the skill and configure the Codex connection. Configuration changes are backed up in the Codex home directory.

Open Gephi from its regular desktop or Start menu shortcut, then restart Codex. Desktop Gephi uses local port 8183. If needed, open **Window → 3D Explorer · 三维探索**.

Use the appearance controls to toggle directed arrowheads, change arrowhead size, and adjust line width. Heavier edges show larger arrows and thicker lines. The controls work in both the regular view and the map, and are saved in the Gephi project.

For independent mode, add `--mode isolated` to setup and open `Start-Gephi-3D.cmd`. Its default port is 8184, with data in `%LOCALAPPDATA%\Gephi3DExplorer`. Restart Codex after switching the connection.

Ask Codex: “Use gephi-3d to generate a synthetic network of 60 nodes in 3 communities. Run a 3D layout, focus node 12, highlight its two-hop neighborhood, separate communities into layers and bookmark the view.”

The skill defaults to plugin API calls and does not use desktop control unless you explicitly request interface operation. Connection or authentication failures are reported, not worked around with mouse or keyboard automation. To inspect rendering, Codex exports a preview through the plugin.

Use `gephi3d_auto_draw` for a two-sheet Excel workbook, a static GEXF file or a synthetic example. It imports or generates the graph, runs the layout, styles it by community, weight and direction, and opens the 3D panel. Local GEXF import preserves graph attributes and supplied XYZ. Limits: 20 MB of GEXF, 10,000 nodes and 100,000 edges. Reload Codex after updating to discover all 29 tools.

For an optional supply-chain map, use `gephi3d_supply_chain_map` with a local Stata DTA, CSV or GEXF dataset, a city-code lookup CSV, a matching local GeoJSON basemap, and chain leader IDs. The map starts off and can be enabled per workspace with the map checkbox in the 3D panel. The [examples](../examples/README.md) list the input columns. Provide your own basemap and data.

To upgrade, save your project and close Gephi, extract the new release into a fresh directory, and run `py -3.12 scripts/setup.py --configure-codex --replace-gephi-connection --dry-run`. Review the preview, then repeat without `--dry-run`. Setup backs up the recognized previous desktop module and the Codex configuration. Add `--mode isolated` to keep using the independent launcher. Restart Gephi and Codex.

Left-drag rotates, right-drag pans, wheel zooms, Ctrl-click changes selection, and double-click focuses a node. Save to a new `.gephi` path to retain the 3D scene. PNG/PDF exports require a writable absolute destination and do not overwrite existing files.

For an editable vector figure, Ctrl-click nodes and choose **导出论文图（SVG）** in the 3D panel, or call `gephi3d_export_paper`. The export emphasizes the selection and its direct links, simplifies the legend, and reports links missing locations in map view.

Gephi-AI can only cooperate when connected to the same Gephi instance; equal workspace IDs across processes do not identify the same graph. See [limitations and Chinese instructions](../README.md) and [development guide](DEVELOPMENT.md).
