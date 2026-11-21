"""Typed MCP tools for the Gephi 3D Explorer plugin. No model API key needed."""
from __future__ import annotations
import json
from functools import wraps
from pathlib import Path
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from gephi3d_client import Gephi3D

mcp = MCPServer('gephi-3d', version='0.1.7-alpha', instructions='Use the local Gephi plugin API for graph generation, import, exploration and export. These tools require no desktop input. Report connection/capability failures; do not fall back to desktop control unless the user explicitly requests interface operation. Inspect rendering through plugin exports, not desktop screenshots.')
client = Gephi3D()

def gephi_tool():
    """Expose actionable local failures rather than MCP's masked unexpected errors."""
    def register(function):
        @wraps(function)
        def invoke(*args, **kwargs):
            try:
                return function(*args, **kwargs)
            except (RuntimeError, ValueError, OSError) as exc:
                raise ToolError(str(exc)) from None
        return mcp.tool()(invoke)
    return register

@gephi_tool()
def gephi3d_import_graph(path: str, synthetic: bool=False) -> dict:
    """Import a local static GEXF into a NEW workspace through the plugin API, without desktop control. Preserves existing graphs, IDs, attributes, direction, weights, colors and supplied XYZ. Convert CSV/XLSX first. Limits: 20 MB, 10000 nodes, 100000 edges. synthetic marks illustrative data, not a verification of truth. Requires plugin 0.1.1+."""
    state = client.state()
    if 'import_graph' not in state.get('capabilities', []):
        raise RuntimeError('The running Gephi plugin does not support API import. Save your work, close Gephi normally, update/restart with the new launcher, and retry. Do not use desktop control as a fallback.')
    return client.command('import_graph', path=path, synthetic=synthetic)


@gephi_tool()
def gephi3d_auto_draw(path: str | None = None, directed: bool | None = None,
                     nodes: int = 60, communities: int = 3, seed: int = 42,
                     node_sheet: str = '节点表', edge_sheet: str = '连接表',
                     png_path: str | None = None, synthetic: bool = False) -> dict:
    """In one call: read local XLSX or GEXF, create a new workspace, run 3D layout, color by community, set edge width by weight and show arrowheads on directed edges. Open the rotatable view and optionally export an absolute PNG path. Excel needs explicit directed=True or False; existing GEXF directions are preserved. Omit path for a synthetic directed, weighted community demo. Use only when an example is requested or an input path is supplied. Requires plugin 0.1.2+."""
    from auto_draw import draw

    return draw(client, path, directed=directed, nodes=nodes, communities=communities, seed=seed,
                node_sheet=node_sheet, edge_sheet=edge_sheet, png_path=png_path, synthetic=synthetic)

@gephi_tool()
def gephi3d_status() -> dict:
    """Read current workspace, revision, mouse selection, layout job and renderer."""
    return client.state()


@gephi_tool()
def gephi3d_supply_chain_map(path: str, basemap: str, city_table: str, leader_ids: list[str],
                             basemap_source: str, enabled: bool = False, city_attribute: str = 'city_code',
                             png_path: str | None = None, synthetic: bool = False) -> dict:
    """Prepare an optional supply-chain map in a new workspace. Use only when geography is requested. Local geographic polygon GeoJSON and city CSV (city_code,city_name,longitude,latitude) must share a datum. Input is GEXF with a node city-code attribute, or a directed CSV/.dta edge table with source,target,source_city_code,target_city_code and optional weight, source/target names and communities. Explicit leaders are firm IDs. Map stays OFF unless explicitly enabled. Report unmatched cities and any unlocated leaders. Requires plugin 0.1.3+."""
    from supply_chain_map import draw_supply_chain
    return draw_supply_chain(client, path, basemap, city_table, leader_ids, basemap_source, enabled,
                             city_attribute, png_path, synthetic)


@gephi_tool()
def gephi3d_geography(workspace_id: int, enabled: bool) -> dict:
    """Turn the prepared map on or off in this workspace. Switches between saved geographic and ordinary camera views without moving the original nodes. Requires plugin 0.1.3+."""
    if 'geography' not in client.state().get('capabilities', []):
        raise RuntimeError('Restart Gephi with Gephi 3D Explorer 0.1.3 or later')
    return client.command('geography', workspace_id, enabled=enabled)

@gephi_tool()
def gephi3d_open() -> dict:
    """Open the dockable 3D Explorer panel inside Gephi."""
    return client.command('open')

@gephi_tool()
def gephi3d_generate(template: str='community', nodes: int=60, communities: int=3, average_degree: float=8, seed: int=42) -> dict:
    """Generate a SYNTHETIC network in a new workspace, preserving existing work. Templates: community, layered (DAG), hub. Returns actual counts; requested degree may be infeasible for sparse layered graphs."""
    return client.command('generate', template=template, nodes=nodes, communities=communities, average_degree=average_degree, seed=seed)

@gephi_tool()
def gephi3d_search(workspace_id: int, query: str) -> dict:
    """Find up to 100 nodes by ID or label; returns Chinese attributes and 3D positions."""
    return client.command('search', workspace_id, query=query)

@gephi_tool()
def gephi3d_node(workspace_id: int, id: str) -> dict:
    """Read exact node attributes, position, degree and pinned state."""
    return client.command('node', workspace_id, id=id)

@gephi_tool()
def gephi3d_select(workspace_id: int, ids: list[str]) -> dict:
    """Replace the selection; the same selection is visible to mouse and MCP."""
    return client.command('select', workspace_id, ids=ids)

@gephi_tool()
def gephi3d_focus(workspace_id: int, ids: list[str] | None=None) -> dict:
    """Focus nodes or fit the full graph with an empty ID list."""
    return client.command('focus', workspace_id, ids=ids or [])

@gephi_tool()
def gephi3d_camera(workspace_id: int, yaw: float | None=None, pitch: float | None=None, distance: float | None=None, perspective: bool | None=None) -> dict:
    """Set camera orientation in radians, zoom distance and perspective/orthographic projection. Positions stay unchanged."""
    return client.command('camera', workspace_id, **{k:v for k,v in locals().items() if k!='workspace_id' and v is not None})

@gephi_tool()
def gephi3d_neighbors(workspace_id: int, id: str, hops: int=1, isolate: bool=False) -> dict:
    """Highlight undirected h-hop neighborhood; dim background or isolate the result."""
    return client.command('neighbors', workspace_id, id=id, hops=hops, isolate=isolate)

@gephi_tool()
def gephi3d_path(workspace_id: int, source: str, target: str, directed: bool=True, isolate: bool=False) -> dict:
    """Highlight a shortest unweighted hop path. directed=True respects directed edges. Empty result means no path."""
    return client.command('path', workspace_id, source=source, target=target, directed=directed, isolate=isolate)

@gephi_tool()
def gephi3d_communities(workspace_id: int, communities: list[str], isolate: bool=False) -> dict:
    """Highlight given community attribute values and their connecting edges."""
    return client.command('highlight_community', workspace_id, communities=communities, isolate=isolate)

@gephi_tool()
def gephi3d_clear_highlight(workspace_id: int) -> dict:
    """Clear highlight and isolation, retaining filters."""
    return client.command('clear_highlight', workspace_id)

@gephi_tool()
def gephi3d_filter(workspace_id: int, min_degree: int=0, min_weight: float=-1e100, community: str='',
                   node_query: str='', city_query: str='', hops: int=0, roots: list[str] | None=None) -> dict:
    """Replace view filters. Leave fields empty and hops zero to clear. With 0.1.7+, match node name/code and city, or show 1–2 hops about roots (defaults to current selection). Hops use links meeting min_weight."""
    options = dict(min_degree=min_degree, min_weight=min_weight, community=community)
    if node_query or city_query or hops or roots is not None:
        require_view_controls()
        options.update(node_query=node_query, city_query=city_query, hops=hops)
        if roots is not None:
            options['roots'] = roots
    return client.command('filter', workspace_id, **options)

def require_view_controls() -> None:
    if 'view_controls' not in client.state().get('capabilities', []):
        raise ToolError('The connected Gephi plugin needs version 0.1.7 or later for these view controls.')

@gephi_tool()
def gephi3d_layer(workspace_id: int, attribute: str='community', spacing: float=160) -> dict:
    """Map attribute values to Z layers; numeric values sort numerically. Pinned nodes remain fixed."""
    return client.command('layer', workspace_id, attribute=attribute, spacing=spacing)

@gephi_tool()
def gephi3d_layer_visibility(workspace_id: int, hidden: list[str]) -> dict:
    """Replace hidden layer values for the current layer attribute. An empty list shows all. Empty string denotes the missing-value layer."""
    require_view_controls()
    return client.command('layer_visibility', workspace_id, hidden=hidden)

@gephi_tool()
def gephi3d_node_colors(workspace_id: int, field: str='', mode: str='category') -> dict:
    """Color nodes by field using category or number (gradient). Empty field restores the preset. Selection remains highlighted."""
    require_view_controls()
    return client.command('node_colors', workspace_id, field=field, mode=mode)

@gephi_tool()
def gephi3d_paper_legend(workspace_id: int, position: str | None=None, hidden: list[str] | None=None,
                         labels: dict[str, str] | None=None) -> dict:
    """Set SVG legend position (top/bottom), hidden items and renamed items. Keys include selection, focus, direction, weight_low and weight_high. SVG text data-legend-item attributes identify other items. Provided lists/maps replace the corresponding settings."""
    require_view_controls()
    options = {k: v for k, v in locals().items() if k != 'workspace_id' and v is not None}
    return client.command('paper_legend', workspace_id, **options)

@gephi_tool()
def gephi3d_pin(workspace_id: int, ids: list[str], pinned: bool=True) -> dict:
    """Pin or unpin node positions in this 3D scene."""
    return client.command('pin', workspace_id, ids=ids, pinned=pinned)

@gephi_tool()
def gephi3d_layout(workspace_id: int, iterations: int=500, milliseconds: int=30000) -> dict:
    """Start an asynchronous octree 3D FR layout. Maximum 1000 iterations/30 seconds. Poll status; do not resubmit a running job."""
    return client.command('layout', workspace_id, iterations=iterations, milliseconds=milliseconds)

@gephi_tool()
def gephi3d_cancel_layout(workspace_id: int) -> dict:
    """Cancel the current layout, preserving the last displayed coordinates."""
    return client.command('cancel_layout', workspace_id)

@gephi_tool()
def gephi3d_bookmark(workspace_id: int, name: str, restore: bool=False) -> dict:
    """Save or restore a named camera view in the current workspace."""
    return client.command('bookmark', workspace_id, name=name, restore=restore)

@gephi_tool()
def gephi3d_style(workspace_id: int, dark: bool | None=None, labels: bool | None=None,
                  node_scale: float | None=None, edge_width: float | None=None,
                  preset: str | None=None, arrows: bool | None=None,
                  arrow_scale: float | None=None) -> dict:
    """Style the scene without moving nodes. Presets: classic, communities, weighted, keywords (0.1.2+). Edge width sets the line multiplier (0.5–5). Set arrows to show/hide arrowheads on directed edges. With plugin 0.1.4+, the toggle also works on maps and arrow_scale controls head size (0.4–3) independently of line width."""
    options = {key: value for key, value in locals().items() if key != 'workspace_id' and value is not None}
    if preset is not None or arrows is not None or arrow_scale is not None:
        capabilities = client.state().get('capabilities', [])
        if (preset is not None or arrows is not None) and 'visual_presets' not in capabilities:
            raise ToolError('The connected Gephi plugin does not support visual presets. Restart with plugin 0.1.2 or later.')
        if arrow_scale is not None and 'arrow_style' not in capabilities:
            raise ToolError('The connected Gephi plugin does not support arrow sizes. Install Gephi 3D Explorer 0.1.4 or later.')
    return client.command('style', workspace_id, **options)

@gephi_tool()
def gephi3d_history(workspace_id: int, redo: bool=False) -> dict:
    """Undo or redo a scene operation (last 15 commands); network creation is preserved in its workspace."""
    return client.command('redo' if redo else 'undo', workspace_id)

@gephi_tool()
def gephi3d_refresh(workspace_id: int) -> dict:
    """Reload topology/attributes after imports or Gephi-AI changes, retaining 3D positions by ID."""
    return client.command('refresh', workspace_id)

@gephi_tool()
def gephi3d_export(workspace_id: int, path: str, width: int=6000, height: int=4000, pdf: bool=False) -> dict:
    """Export current locked view to PNG + scene JSON; optionally PDF embedding the PNG and Chinese legend. Path must be a new absolute .png path. Pause layout first."""
    if pdf and Path(path).with_suffix('.pdf').exists(): raise ValueError('PDF already exists')
    result=client.command('export_png', workspace_id, path=path, width=width, height=height)
    if pdf:
        from reportlab.pdfgen import canvas
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        pdfmetrics.registerFont(UnicodeCIDFont('STSong-Light'))
        pdf_path=Path(path).with_suffix('.pdf'); c=canvas.Canvas(str(pdf_path),pagesize=(900,660))
        c.drawImage(path,20,70,width=860,height=570,preserveAspectRatio=True,anchor='c')
        c.setFont('STSong-Light',11)
        scene=json.loads(Path(path+'.scene.json').read_text(encoding='utf-8'))
        c.drawString(25,45,'三维网络 · '+('合成示例，非真实观测' if scene['synthetic'] else scene['source'])[:100])
        c.drawString(25,25,f"节点 {len(scene['nodes'])} · 边 {len(scene['edges'])} · 视图修订 {scene['revision']}")
        c.save();result['pdf_path']=str(pdf_path)
    return result

@gephi_tool()
def gephi3d_export_paper(workspace_id: int, path: str, width_mm: float = 183,
                         title: str = '', direction: str = '',
                         width: int = 1200, height: int = 800) -> dict:
    """Export editable SVG with a compact legend and emphasis on selected nodes and their connections. The export can fit the focused group without moving the live camera. Set paper width in mm. The optional direction describes the arrow meaning. Use a new absolute .svg path and pause the layout first. SVG requires plugin 0.1.5+; automatic focus styling and framing require 0.1.6+."""
    if 'paper_svg' not in client.state().get('capabilities', []):
        raise ToolError('The connected Gephi plugin does not support editable paper export. Install Gephi 3D Explorer 0.1.5 or later.')
    return client.command('export_svg', workspace_id, path=path, width_mm=width_mm,
                          title=title, direction=direction, width=width, height=height)


@gephi_tool()
def gephi3d_save_project(workspace_id: int, path: str) -> dict:
    """Save the current project, including per-workspace 3D scenes, to a new absolute .gephi path. Pause layout first."""
    return client.command('save_project', workspace_id, path=path)

@gephi_tool()
def gephi3d_save_scene(workspace_id: int, path: str) -> dict:
    """Save a JSON reproduction snapshot. The plugin also persists scene state inside .gephi projects."""
    output=Path(path)
    if not output.is_absolute(): raise ValueError('Absolute path required')
    result=client.command('save_scene',workspace_id)
    with output.open('x',encoding='utf-8') as f: json.dump(result['scene'],f,ensure_ascii=False,indent=2)
    return {'success':True,'path':str(output)}

if __name__=='__main__': mcp.run(transport='stdio')
