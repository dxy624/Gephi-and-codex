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

For custom data, ask Codex to call `gephi3d_import_graph` with an absolute local static GEXF path. It creates a new workspace and preserves IDs, attributes, edge direction, weights, colors and supplied XYZ. Convert CSV/XLSX to GEXF first. Limits: 20 MB, 10,000 nodes, 100,000 edges; nested nodes and dynamic graphs are unsupported. Reload MCP after updating to discover all 25 tools. An older running plugin must be saved, closed normally and restarted with the updated launcher; no user process is stopped automatically.

Left-drag rotates, right-drag pans, wheel zooms, Ctrl-click changes selection, and double-click focuses a node. Save to a new `.gephi` path to retain the 3D scene. PNG/PDF exports require a writable absolute destination and do not overwrite existing files.

Gephi-AI can only cooperate when connected to the same Gephi instance; equal workspace IDs across processes do not identify the same graph. See [limitations and Chinese instructions](../README.md) and [development guide](DEVELOPMENT.md).
