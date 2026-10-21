# Changelog

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

The initial release used module specification version `0.1.0`; both distributions are explicitly Alpha. Feature limits and unverified environments are listed in the README.
