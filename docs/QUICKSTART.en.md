# English quick start

This Alpha targets Windows, Gephi **0.11.3 or 0.11.4**, and Python **3.12**. Use the bundled Java 17 runtime. A separate JDK is needed only to compile source.

Save your work and close desktop Gephi. Extract the release into a permanent folder and open PowerShell there.

```powershell
py -3.12 scripts/setup.py --install-skill --configure-codex --dry-run
py -3.12 scripts/setup.py --install-skill --configure-codex
```

Setup installs the module for the current Windows user and creates a project `.venv`. Pass `--gephi "installation directory"` or set `GEPHI_HOME` if discovery fails. The two flags install the skill and configure the Codex connection. Configuration changes are backed up in the Codex home directory.

Open Gephi from its regular desktop or Start menu shortcut, then restart Codex. Desktop Gephi uses local port 8183. If needed, open **Window → 3D Explorer · 三维探索**.

For independent mode, add `--mode isolated` to setup and open `Start-Gephi-3D.cmd`. Its default port is 8184, with data in `%LOCALAPPDATA%\Gephi3DExplorer`. Restart Codex after switching the connection.

Ask Codex: “Use gephi-3d to generate a synthetic network of 60 nodes in 3 communities. Run a 3D layout, focus node 12, highlight its two-hop neighborhood, separate communities into layers and bookmark the view.”

The skill defaults to plugin API calls and does not use desktop control unless you explicitly request interface operation. Connection or authentication failures are reported, not worked around with mouse or keyboard automation. To inspect rendering, Codex exports a preview through the plugin.

Use `gephi3d_auto_draw` for a two-sheet Excel workbook, a static GEXF file or a synthetic example. It imports or generates the graph, runs the layout, styles it by community, weight and direction, and opens the 3D panel. Local GEXF import preserves graph attributes and supplied XYZ. Limits: 20 MB of GEXF, 10,000 nodes and 100,000 edges. Reload Codex after updating to discover all 28 tools.

For an optional supply-chain map, use `gephi3d_supply_chain_map` with a local Stata DTA, CSV or GEXF dataset, a city-code lookup CSV, a matching local GeoJSON basemap, and chain leader IDs. The map starts off and can be enabled per workspace with the map checkbox in the 3D panel. The [examples](../examples/README.md) list the input columns. Provide your own basemap and data.

To switch an existing connection, run `py -3.12 scripts/setup.py --configure-codex --replace-gephi-connection --dry-run`, review the preview, then repeat without `--dry-run`. Add `--mode isolated` if that is the target. If the installed skill differs and you want to update it, move the old skill folder outside the skills directory before installing the new copy.

Left-drag rotates, right-drag pans, wheel zooms, Ctrl-click changes selection, and double-click focuses a node. Save to a new `.gephi` path to retain the 3D scene. PNG/PDF exports require a writable absolute destination and do not overwrite existing files.

Gephi-AI can only cooperate when connected to the same Gephi instance; equal workspace IDs across processes do not identify the same graph. See [limitations and Chinese instructions](../README.md) and [development guide](DEVELOPMENT.md).
