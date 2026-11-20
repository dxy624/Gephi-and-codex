# Changelog

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
