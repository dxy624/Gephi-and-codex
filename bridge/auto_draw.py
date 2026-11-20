"""Run the standard styled-network workflow against the configured Gephi instance."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import random
import sys
from tempfile import NamedTemporaryFile, TemporaryDirectory
import time

import networkx as nx

from gephi3d_client import Gephi3D


def synthetic_network(nodes, groups, seed):
    if not 2 <= nodes <= 1000 or not 1 <= groups <= min(nodes, 30):
        raise ValueError('The demo supports 2–1000 nodes and 1–30 communities')
    rng = random.Random(seed)
    graph = nx.DiGraph()
    communities = [[] for _ in range(groups)]
    for index in range(nodes):
        group = index % groups
        node = f'n{index + 1}'
        communities[group].append(node)
        graph.add_node(node, label=f'节点 {index + 1}', community=f'社区 {group + 1}')

    def add(source, target, weight):
        if source != target and not graph.has_edge(source, target):
            graph.add_edge(source, target, weight=weight)

    for group in communities:
        for index, source in enumerate(group):
            add(source, group[(index + 1) % len(group)], rng.randint(2, 7))
            if index:
                pair = (group[0], source) if index % 3 else (source, group[0])
                add(*pair, rng.randint(9, 32))
            for target in rng.sample(group, k=min(2, len(group))):
                add(source, target, rng.randint(1, 8))
    if groups > 1:
        for index, group in enumerate(communities):
            next_group = communities[(index + 1) % groups]
            for node in rng.sample(group, k=min(3, len(group))):
                add(node, rng.choice(next_group), rng.randint(1, 4))
    return graph


def workbook_network(path, directed, node_sheet, edge_sheet, seed):
    root = str(Path(__file__).resolve().parents[1])
    if root not in sys.path:
        sys.path.insert(0, root)
    from scripts.tables_to_gexf import graph_from_workbook

    if directed is None:
        raise ValueError('For Excel, set directed=True when source points to target, or False when the relationship is mutual')
    if node_sheet == edge_sheet:
        raise ValueError('Choose separate worksheets for nodes and edges')
    graph = graph_from_workbook(path, directed, node_sheet, edge_sheet)
    if not 1 <= len(graph) <= 10_000 or graph.number_of_edges() > 100_000:
        raise ValueError('Excel supports 1–10000 nodes and up to 100000 edges')
    has_groups = any(str(data.get('community', '')).strip() or str(data.get('modularity_class', '')).strip()
                     for _, data in graph.nodes(data=True))
    if not has_groups and graph.number_of_edges():
        groups = nx.community.louvain_communities(nx.Graph(graph), weight=None, seed=seed)
        ordered = sorted(groups, key=lambda group: (-len(group), min(group)))
        for number, group in enumerate(ordered, 1):
            for node in group:
                graph.nodes[node]['community'] = f'社区 {number}'
        return graph, 'detected from network topology'
    return graph, 'input attributes'


def output_path(path):
    if path is None:
        return None
    image = Path(path)
    if not image.is_absolute() or image.suffix.lower() != '.png':
        raise ValueError('The PNG destination must be an absolute .png path')
    if image.exists() or Path(str(image) + '.scene.json').exists():
        raise FileExistsError(f'Choose a new PNG name: {image}')
    # Detect a missing or unwritable parent before opening a new Gephi workspace.
    with NamedTemporaryFile(dir=image.parent):
        pass
    return image


def wait_for_layout(client, workspace, job_id, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = client.request('/state', timeout=5)
        if (state.get('scene') or {}).get('workspace_id') != workspace:
            raise RuntimeError(f'Workspace {workspace} is no longer active. No further changes were made')
        layout = state['layout']
        if layout['job_id'] != job_id:
            raise RuntimeError(f'A different layout started in workspace {workspace}')
        if layout['state'] == 'completed':
            return
        if layout['state'] != 'running':
            raise RuntimeError(f'Layout in workspace {workspace} {layout["state"]}: {layout.get("error", "")}')
        time.sleep(.25)
    raise RuntimeError(f'Layout did not finish in workspace {workspace}. Check its status before retrying')


def draw(client, path=None, *, directed=None, nodes=60, communities=3, seed=42,
         node_sheet='节点表', edge_sheet='连接表', png_path=None, synthetic=False):
    """Import or generate, style, lay out, verify and optionally export a new 3D scene."""
    image = output_path(png_path)
    source = Path(path) if path is not None else None
    if source is not None and (not source.is_absolute() or source.suffix.lower() not in {'.xlsx', '.gexf'}):
        raise ValueError('Select an absolute path to a local .xlsx or .gexf file')
    if source is not None and not source.is_file():
        raise FileNotFoundError(source)
    if source is not None and source.suffix.lower() == '.gexf' and directed is not None:
        raise ValueError('GEXF already specifies edge direction; omit the directed option')

    state = client.request('/state', timeout=5)
    if not {'import_graph', 'visual_presets'} <= set(state.get('capabilities', [])):
        raise RuntimeError('Automatic drawing needs Gephi 3D Explorer 0.1.2 or later')
    if state['layout']['state'] == 'running':
        raise RuntimeError('Wait for the current Gephi layout to finish before starting another drawing')

    group_source = 'input attributes'
    with TemporaryDirectory(prefix='gephi_auto_draw_') as temporary:
        graph = None
        if source is None:
            graph = synthetic_network(nodes, communities, seed)
            synthetic = True
            group_source = 'synthetic groups'
        elif source.suffix.lower() == '.xlsx':
            graph, group_source = workbook_network(source, directed, node_sheet, edge_sheet, seed)
        if graph is not None:
            name = source.stem if source else '合成有向社区网络'
            source = Path(temporary) / (name + '.gexf')
            nx.write_gexf(graph, source, encoding='utf-8', version='1.2draft')
        imported = client.command('import_graph', path=str(source), synthetic=synthetic)

    scene = imported['scene']
    workspace = scene['workspace_id']
    client.command('style', workspace, preset='communities', arrows=True, labels=True, dark=False, edge_width=1.6)
    job = client.command('layout', workspace, iterations=400, milliseconds=12000)['layout']
    wait_for_layout(client, workspace, job['job_id'])
    client.command('focus', workspace, ids=[])
    client.command('open')
    completed = client.command('save_scene', workspace)['scene']
    groups = Counter(node['community'] for node in completed['nodes'] if node['community'])
    directed_edges = sum(edge['directed'] for edge in completed['edges'])
    weights = {edge['weight'] for edge in completed['edges']}
    warnings = list(imported.get('import', {}).get('warnings', []))
    if len(groups) < 2:
        warnings.append('Fewer than two communities are available for distinct colors.')
    if groups and sum(groups.values()) < len(completed['nodes']):
        warnings.append('Some nodes have no community value and retain their original color.')
    if len(groups) > 10:
        warnings.append('The palette repeats colors beyond ten communities.')
    if not directed_edges:
        warnings.append('The input contains no directed edges to mark with arrowheads.')
    if len(weights) < 2:
        warnings.append('There is no variation in input edge weights.')
    result = {
        'success': True, 'workspace_id': workspace, 'synthetic': completed['synthetic'],
        'nodes': len(completed['nodes']), 'edges': len(completed['edges']),
        'communities': dict(sorted(groups.items())), 'community_source': group_source,
        'directed_edges': directed_edges, 'distinct_weights': len(weights),
        'style': completed['visualStyle'], 'arrows': completed['arrows'], 'warnings': warnings,
    }
    if image is not None:
        exported = client.command('export_png', workspace, path=str(image), width=1800, height=1200)
        result['png_path'] = exported['path']
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', help='Local .xlsx or .gexf. Omit for a synthetic demo.')
    direction = parser.add_mutually_exclusive_group()
    direction.add_argument('--directed', action='store_true', default=None)
    direction.add_argument('--undirected', action='store_false', dest='directed')
    parser.add_argument('--png')
    options = parser.parse_args()
    report = draw(Gephi3D(), options.input, directed=options.directed, png_path=options.png)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
