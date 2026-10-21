"""Typed MCP tools for the Gephi 3D Explorer plugin. No model API key needed."""
from __future__ import annotations
import json
from functools import wraps
from pathlib import Path
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from gephi3d_client import Gephi3D

mcp = MCPServer('gephi-3d', version='0.1.1-alpha', instructions='Use the local Gephi plugin API for graph generation, import, exploration and export. These tools require no desktop input. Report connection/capability failures; do not fall back to desktop control unless the user explicitly requests interface operation. Inspect rendering through plugin exports, not desktop screenshots.')
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
def gephi3d_status() -> dict:
    """Read current workspace, revision, mouse selection, layout job and renderer."""
    return client.state()

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
def gephi3d_filter(workspace_id: int, min_degree: int=0, min_weight: float=-1e100, community: str='') -> dict:
    """Set view filters; 0 degree, very low weight and empty community restore all."""
    return client.command('filter', workspace_id, min_degree=min_degree, min_weight=min_weight, community=community)

@gephi_tool()
def gephi3d_layer(workspace_id: int, attribute: str='community', spacing: float=160) -> dict:
    """Map attribute values to Z layers; numeric values sort numerically. Pinned nodes remain fixed."""
    return client.command('layer', workspace_id, attribute=attribute, spacing=spacing)

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
def gephi3d_style(workspace_id: int, dark: bool | None=None, labels: bool | None=None, node_scale: float | None=None, edge_width: float | None=None) -> dict:
    """Adjust background, labels, node scale and line width without resetting layout."""
    return client.command('style', workspace_id, **{k:v for k,v in locals().items() if k!='workspace_id' and v is not None})

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
