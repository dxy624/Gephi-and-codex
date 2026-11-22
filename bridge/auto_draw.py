"""Run the standard styled-network workflow against the configured Gephi instance."""
from __future__ import annotations

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import random
from tempfile import NamedTemporaryFile, TemporaryDirectory
import time

import networkx as nx

from gephi3d_client import Gephi3D
from relationship_table import read_network


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


def color_communities(graph, seed):
    has_groups = any(str(data.get('community', '')).strip() or str(data.get('modularity_class', '')).strip()
                     for _, data in graph.nodes(data=True))
    if not has_groups and graph.number_of_edges():
        groups = nx.community.louvain_communities(nx.Graph(graph), weight=None, seed=seed)
        ordered = sorted(groups, key=lambda group: (-len(group), min(group)))
        for number, group in enumerate(ordered, 1):
            for node in group:
                graph.nodes[node]['community'] = f'社区 {number}'
        return 'detected from network topology'
    return 'input attributes'


def output_path(path, suffix='.png'):
    if path is None:
        return None
    image = Path(path)
    if not image.is_absolute() or image.suffix.lower() != suffix:
        raise ValueError(f'输出位置必须是以 {suffix} 结尾的绝对路径。')
    if image.exists() or (suffix == '.png' and Path(str(image) + '.scene.json').exists()):
        raise FileExistsError(f'请为输出选择新文件名：{image}')
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
         node_sheet='节点表', edge_sheet='连接表', png_path=None, synthetic=False,
         source_column=None, target_column=None, duplicates='error', roots=None, direction=None, hops=1,
         year=0, reference=0, min_weight=None, svg_path=None):
    """Import or generate, style, lay out, verify and optionally export a new 3D scene."""
    image = output_path(png_path)
    paper = output_path(svg_path, '.svg')
    source = Path(path) if path is not None else None
    if source is not None and (not source.is_absolute() or source.suffix.lower() not in {'.xlsx', '.csv', '.dta', '.gexf'}):
        raise ValueError('请提供本机 XLSX、CSV、DTA 或 GEXF 文件的绝对路径。')
    if source is not None and not source.is_file():
        raise FileNotFoundError(source)
    if source is not None and source.suffix.lower() == '.gexf' and (directed is not None or source_column or target_column):
        raise ValueError('GEXF 已包含方向和端点，请省略表格方向及列名选项。')
    if min_weight is not None and not math.isfinite(min_weight):
        raise ValueError('边权下限必须是有限数值。')
    if duplicates not in {'error', 'sum'}:
        raise ValueError('重复记录处理方式为 error 或 sum。')
    if direction not in {None, 'upstream', 'downstream', 'both'} or (roots and hops not in {1, 2}):
        raise ValueError('请指定上游、下游或双向，以及一阶或两阶。')
    if direction is not None and not roots:
        raise ValueError('查看上下游需要提供至少一个中心企业代码。')
    if reference and (not year or year == reference):
        raise ValueError('先指定要看的年份，再选择不同的基准年份。')

    state = client.request('/state', timeout=5)
    capabilities = set(state.get('capabilities', []))
    if not {'import_graph', 'visual_presets'} <= capabilities:
        raise RuntimeError('Automatic drawing needs Gephi 3D Explorer 0.1.2 or later')
    if (roots and 'supply_scope' not in capabilities) or (year and 'edge_years' not in capabilities):
        raise RuntimeError('上下游和年份筛选需要 Gephi 3D Explorer 0.1.8 或更新版。')
    if paper and 'paper_svg' not in capabilities:
        raise RuntimeError('可编辑 SVG 需要 Gephi 3D Explorer 0.1.5 或更新版。')
    if state['layout']['state'] == 'running':
        raise RuntimeError('Wait for the current Gephi layout to finish before starting another drawing')

    group_source = 'input attributes'
    table = {}
    original_name = source.name if source is not None else None
    with TemporaryDirectory(prefix='gephi_auto_draw_') as temporary:
        graph = None
        if source is None:
            graph = synthetic_network(nodes, communities, seed)
            synthetic = True
            group_source = 'synthetic groups'
        elif source.suffix.lower() != '.gexf':
            graph = read_network(source, directed, source_column=source_column, target_column=target_column,
                                 node_sheet=node_sheet, edge_sheet=edge_sheet, duplicates=duplicates)
            group_source = color_communities(graph, seed)
            table = input_summary(graph)
        knowledge = graph
        if knowledge is None and (roots or year):
            if source.stat().st_size > 20 * 1024 * 1024:
                raise ValueError('GEXF must be at most 20 MB')
            knowledge = read_network(source)
        if knowledge is not None:
            validate_requested_scope(knowledge, roots, year, reference)
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
    if min_weight is not None:
        client.command('filter', workspace, min_weight=min_weight)
    if year:
        client.command('years', workspace, year=year, reference=reference)
    if roots:
        client.command('supply_scope', workspace, roots=list(dict.fromkeys(roots)), direction=direction or 'both', hops=hops)
    client.command('open')
    completed = client.command('save_scene', workspace)['scene']
    final = client.request('/state', timeout=5)['scene']
    if final['workspace_id'] != workspace:
        raise RuntimeError(f'Workspace {workspace} is no longer active. Check Gephi before exporting.')
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
        'input_name': original_name,
        'input_table': table,
        'view': view_summary(final),
    }
    if image is not None:
        exported = client.command('export_png', workspace, path=str(image), width=1800, height=1200)
        result['png_path'] = exported['path']
    if paper is not None:
        options = {'direction': '供应商 → 客户'} if table.get('supplier_to_customer') else {}
        exported = client.command('export_svg', workspace, path=str(paper), **options)
        result['svg_path'] = exported['path']
        result['svg_counts'] = {'nodes': exported['shown_nodes'], 'edges': exported['shown_edges']}
    return result


def validate_requested_scope(graph, roots, year, reference):
    if roots:
        if not graph.is_directed():
            raise ValueError('上下游需要有向关系。请确认供应商和客户对应的列。')
        missing = set(roots).difference(graph.nodes)
        if missing:
            raise ValueError('关系表中找不到企业代码：' + '、'.join(sorted(missing)))
    if year:
        available = set()
        for _, _, edge in graph.edges(data=True):
            if edge.get('year_weights'):
                available.update(int(y) for y in json.loads(edge['year_weights']))
            elif edge.get('year') not in (None, ''):
                number = float(edge['year'])
                if number.is_integer():
                    available.add(int(number))
        if year not in available or (reference and reference not in available):
            options = '、'.join(map(str, sorted(available))) or '无年度记录'
            raise ValueError('数据没有所选年份。可用年份：' + options)


def view_summary(scene):
    keys = ('visible_nodes', 'visible_edges', 'filters', 'years', 'supply_scope', 'geography')
    return {key: scene[key] for key in keys if key in scene}


def input_summary(graph):
    if 'input_columns' not in graph.graph:
        return {}
    return {
        'columns': graph.graph['input_columns'],
        'records': graph.graph['input_rows'],
        'supplier_to_customer': graph.graph['supplier_roles'] and graph.is_directed(),
        'firms_with_city_code': sum(bool(node.get('city_code')) for _, node in graph.nodes(data=True)),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', help='Local XLSX, CSV, DTA or GEXF. Omit for a synthetic demo.')
    direction = parser.add_mutually_exclusive_group()
    direction.add_argument('--directed', action='store_true', default=None)
    direction.add_argument('--undirected', action='store_false', dest='directed')
    parser.add_argument('--png')
    parser.add_argument('--svg')
    parser.add_argument('--root', action='append', dest='roots')
    parser.add_argument('--direction', choices=['upstream', 'downstream', 'both'])
    parser.add_argument('--hops', type=int, default=1)
    parser.add_argument('--year', type=int, default=0)
    parser.add_argument('--reference', type=int, default=0)
    parser.add_argument('--min-weight', type=float)
    parser.add_argument('--source-column')
    parser.add_argument('--target-column')
    parser.add_argument('--duplicates', choices=['error', 'sum'], default='error')
    options = parser.parse_args()
    report = draw(Gephi3D(), options.input, directed=options.directed, png_path=options.png, svg_path=options.svg,
                  roots=options.roots, direction=options.direction, hops=options.hops, year=options.year,
                  reference=options.reference, min_weight=options.min_weight, source_column=options.source_column,
                  target_column=options.target_column, duplicates=options.duplicates)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
