# English quick start

This Alpha targets Windows, Gephi **0.11.3**, and Python **3.12**. The Java 17 runtime bundled with Gephi is preferred; a separate JDK is only needed to compile source.

Extract the entire release into a permanent folder, then open PowerShell there:

```powershell
py -3.12 scripts/setup.py --install-skill --configure-codex --dry-run
py -3.12 scripts/setup.py --install-skill --configure-codex
```

Pass `--gephi "installation directory"` if discovery fails, or set `GEPHI_HOME`. Setup creates a project `.venv`. The two explicit flags install the skill and add the MCP entry; omit them to leave Codex untouched. Conflicting existing entries are rejected. Config changes are backed up in the Codex home directory.

Reload Codex and double-click `Start-Gephi-3D.cmd`. The isolated profile uses loopback port 8184 and stores runtime data in `%LOCALAPPDATA%\Gephi3DExplorer`. It does not modify the original Gephi profile. Never publish the generated local token or config backups.

Ask Codex: “Use gephi-3d to generate a synthetic network of 60 nodes in 3 communities. Run a 3D layout, focus node 12, highlight its two-hop neighborhood, separate communities into layers and bookmark the view.”

The skill defaults to plugin API calls and does not use desktop control unless you explicitly request interface operation. Connection or authentication failures are reported, not worked around with mouse or keyboard automation. To inspect rendering, Codex exports a preview through the plugin.

Use `gephi3d_auto_draw` for a two-sheet Excel workbook, a static GEXF file or a synthetic example. It imports or generates the graph, runs the layout, styles it by community, weight and direction, and opens the 3D panel. Local GEXF import preserves graph attributes and supplied XYZ. Limits: 20 MB of GEXF, 10,000 nodes and 100,000 edges. Reload Codex after updating to discover all 28 tools.

For an optional supply-chain map, use `gephi3d_supply_chain_map` with a local Stata DTA, CSV or GEXF dataset, a city-code lookup CSV, a matching local GeoJSON basemap, and chain leader IDs. The map starts off and can be enabled per workspace with the map checkbox in the 3D panel. The [examples](../examples/README.md) list the input columns. Provide your own basemap and data.

To upgrade, save your work and close the older Gephi instance, then install from the new folder. If setup reports that a previous skill or MCP entry differs, back up and update the `gephi-3d` entry explicitly. The installer protects existing settings from silent replacement.

Left-drag rotates, right-drag pans, wheel zooms, Ctrl-click changes selection, and double-click focuses a node. Save to a new `.gephi` path to retain the 3D scene. PNG/PDF exports require a writable absolute destination and do not overwrite existing files.

Gephi-AI can only cooperate when connected to the same Gephi instance; equal workspace IDs across processes do not identify the same graph. See [limitations and Chinese instructions](../README.md) and [development guide](DEVELOPMENT.md).
