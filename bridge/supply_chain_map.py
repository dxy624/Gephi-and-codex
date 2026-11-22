"""Prepare city-code matches and an optional map layer for an ordinary Gephi project."""
from __future__ import annotations

from collections import Counter, defaultdict
import csv
import json
from math import asin, cos, isfinite, radians, sin, sqrt
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory

import networkx as nx

from auto_draw import draw, output_path


def city_code(value):
    match = re.fullmatch(r'([0-9]{6})(?:\.0+)?', str(value).strip())
    return match[1] if match else None


# Spherical Albers for a China-wide view. One projection is applied to both datasets.
P1, P2 = radians(25), radians(47)
N = (sin(P1) + sin(P2)) / 2
C = cos(P1) ** 2 + 2 * N * sin(P1)


def project(longitude, latitude):
    lon, lat = float(longitude), float(latitude)
    if not -180 <= lon <= 180 or not -90 <= lat <= 90:
        raise ValueError(f'Invalid longitude or latitude: {longitude}, {latitude}')
    theta = N * radians(lon - 105)
    rho = sqrt(C - 2 * N * sin(radians(lat))) / N
    return [round(1200 * rho * sin(theta), 3), round(1200 * (sqrt(C) / N - rho * cos(theta)), 3)]


def city_index(path):
    index = {}
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        required = {'city_code', 'city_name', 'longitude', 'latitude'}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError('City table needs city_code, city_name, longitude and latitude')
        for line, row in enumerate(reader, 2):
            code = city_code(row['city_code'])
            if code is None or not (row['city_name'] or '').strip():
                raise ValueError(f'Bad city code or city name on city table row {line}')
            if code in index:
                raise ValueError(f'Duplicate city code {code} on city table row {line}')
            index[code] = (row['city_name'].strip(), *project(row['longitude'], row['latitude']))
    return index


def map_geometry(path):
    if path.stat().st_size > 20_000_000:
        raise ValueError('Simplify the local basemap to less than 20 MB')
    geometry = json.loads(path.read_text(encoding='utf-8-sig'))
    polygons, lines = [], []

    def path_points(coordinates):
        return [project(point[0], point[1]) for point in coordinates]

    def polygon(rings):
        result = []
        for ring in rings:
            points = path_points(ring)
            if len(points) < 4 or points[0] != points[-1]:
                raise ValueError('Basemap contains an invalid polygon ring')
            result.append(points)
        polygons.append(result)

    def collect(item):
        kind = item.get('type')
        if kind == 'FeatureCollection':
            for feature in item['features']:
                collect(feature['geometry'])
        elif kind == 'Feature':
            collect(item['geometry'])
        elif kind == 'GeometryCollection':
            for shape in item['geometries']:
                collect(shape)
        elif kind == 'Polygon':
            polygon(item['coordinates'])
        elif kind == 'MultiPolygon':
            for rings in item['coordinates']:
                polygon(rings)
        elif kind == 'LineString':
            lines.append(path_points(item['coordinates']))
        elif kind == 'MultiLineString':
            lines.extend(path_points(line) for line in item['coordinates'])
        else:
            raise ValueError(f'Unsupported basemap geometry: {kind}')

    collect(geometry)
    points = [point for area in polygons for ring in area for point in ring]
    points.extend(point for line in lines for point in line)
    if not polygons or not 1 <= len(points) <= 125_000:
        raise ValueError('Basemap needs polygon coverage and at most 125000 vertices')
    return polygons, lines, points


def edge_table(path):
    if path.suffix.lower() == '.csv':
        with path.open(encoding='utf-8-sig', newline='') as stream:
            yield from csv.DictReader(stream)
        return
    try:
        import pandas as pd
    except ImportError as exc:
        raise RuntimeError('Install the updated bridge requirements for .dta support, or convert the same columns to CSV') from exc
    with pd.read_stata(path, convert_categoricals=False, chunksize=20_000) as reader:
        for chunk in reader:
            yield from chunk.astype(object).where(chunk.notna(), None).to_dict('records')


def read_network(path):
    suffix = path.suffix.lower()
    if suffix == '.gexf':
        return nx.read_gexf(path)
    if suffix not in {'.csv', '.dta'}:
        raise ValueError('Supply chain data must be a local GEXF, CSV or Stata .dta file')
    root = str(Path(__file__).resolve().parents[1])
    if root not in sys.path:
        sys.path.insert(0, root)
    from scripts.tables_to_gexf import add_relationship, edge_year, serialize_relationship_years

    graph = nx.DiGraph()
    city_records = {}
    for line, row in enumerate(edge_table(path), 2):
        if 'source' not in row and {'supplier_symbol', 'customer_symbol'} <= row.keys():
            row = {**row, 'source': row['supplier_symbol'], 'target': row['customer_symbol'],
                   'source_city_code': row.get('supplier_city_code'), 'target_city_code': row.get('customer_city_code')}
        if not {'source', 'target', 'source_city_code', 'target_city_code'} <= row.keys():
            raise ValueError('The edge table needs source, target, source_city_code and target_city_code')
        year = edge_year(row.get('year'), line)
        ids = []
        for side in ('source', 'target'):
            if row[side] is None or not str(row[side]).strip():
                raise ValueError(f'Missing firm ID at row {line}')
            value = row[side]
            firm = str(int(value) if isinstance(value, float) and value.is_integer() else value).strip()
            ids.append(firm)
            if firm not in graph:
                graph.add_node(firm, label=firm)
            node = graph.nodes[firm]
            for column, attribute in ((f'{side}_city_code', 'city_code'), (f'{side}_name', 'label'),
                                      (f'{side}_community', 'community')):
                value = row.get(column)
                if value is None or not str(value).strip():
                    continue
                value = (city_code(value) or str(value).strip()) if attribute == 'city_code' else str(value).strip()
                existing = node.get(attribute)
                if attribute == 'city_code' and year is not None:
                    recorded = city_records.get(firm)
                    if recorded is None or year > recorded:
                        city_records[firm] = year
                        node[attribute] = value
                    elif year == recorded and existing != value:
                        raise ValueError(f'Conflicting city code for {firm} in {year} at row {line}')
                    continue
                if existing is not None and existing != value and not (attribute == 'label' and existing == firm):
                    raise ValueError(f'Conflicting {attribute} for {firm} at row {line}; choose a period or location definition first')
                node[attribute] = value
        weight = float(1 if row.get('weight') in {None, ''} else row['weight'])
        if not isfinite(weight) or weight < 0:
            raise ValueError(f'Invalid weight at row {line}')
        try:
            add_relationship(graph, *ids, weight, year, {}, line)
        except ValueError as exc:
            if graph.has_edge(*ids) and str(exc).startswith('Duplicate edge'):
                raise ValueError(f'Duplicate firm pair at row {line}; choose a year or aggregate duplicate rows first') from exc
            raise
        if len(graph) > 10_000 or graph.number_of_edges() > 100_000:
            raise ValueError('Network exceeds 10000 firms or 100000 links')
    serialize_relationship_years(graph)
    if city_records:
        graph.graph['city_reference'] = '最近一次有记录的城市代码（各年共用）'
    return graph


def prepare_geography(graph, boundary, city_table, leader_ids, source, city_attribute='city_code'):
    if not 1 <= len(graph) <= 10_000 or graph.number_of_edges() > 100_000:
        raise ValueError('Network needs 1–10000 firms and at most 100000 links')
    if not source.strip() or len(source) > 180:
        raise ValueError('Provide a short basemap source label')
    unknown = set(leader_ids).difference(graph)
    if unknown:
        raise ValueError('Unknown chain leader IDs: ' + ', '.join(sorted(unknown)))
    groups_given = any(str(node.get('community', node.get('modularity_class', ''))).strip()
                       for _, node in graph.nodes(data=True))
    if not groups_given and graph.number_of_edges():
        groups = nx.community.louvain_communities(nx.Graph(graph), weight=None, seed=42)
        for number, group in enumerate(sorted(groups, key=lambda g: (-len(g), min(g))), 1):
            for firm in group:
                graph.nodes[firm]['community'] = f'社区 {number}'
    leader_groups = sorted({str(value) for firm in leader_ids
                            if (value := graph.nodes[firm].get('community', graph.nodes[firm].get('modularity_class')))})
    cities = city_index(city_table)
    matched = defaultdict(list)
    missing = Counter()
    for firm, node in graph.nodes(data=True):
        raw = node.get(city_attribute)
        code = city_code(raw)
        if code in cities:
            name = cities[code][0]
            node.update(city_code=code, city_name=name)
            matched[code].append(firm)
        else:
            missing[str(raw) if raw not in {None, ''} else '(missing)'] += 1
    if not matched:
        raise ValueError(f'No city codes matched. Examples: {dict(missing.most_common(8))}')
    polygons, lines, points = map_geometry(boundary)
    positions, markers = {}, []
    lead_set = set(leader_ids)
    for code, firms in matched.items():
        name, x, y = cities[code]
        markers.append({'code': code, 'name': name, 'x': x, 'y': y, 'count': len(firms)})
        points.append([x, y])
        # Place leaders at the front of same-city stacks in the initial overhead view.
        for order, firm in enumerate(sorted(firms, key=lambda firm: (firm in lead_set, firm))):
            positions[firm] = [x, y, 2 + order * min(7, 120 / len(firms))]
    xs, ys = zip(*points)
    layer = {'source': source, 'direction': '供应商 → 客户', 'polygons': polygons, 'lines': lines, 'cities': markers,
             'positions': positions, 'leaders': sorted(lead_set), 'communities': leader_groups,
             'bounds': [min(xs), min(ys), max(xs), max(ys)]}
    report = {'matched_firms': len(positions), 'matched_cities': len(markers), 'unmatched_firms': sum(missing.values()),
              'unmatched_codes': dict(missing.most_common(30)), 'leaders_without_location': sorted(lead_set - positions.keys()),
              'leader_communities': leader_groups, 'community_source': 'input' if groups_given else 'network topology'}
    if reference := graph.graph.get('city_reference'):
        layer['locationReference'] = reference
        report['location_reference'] = reference
    return layer, report


def draw_supply_chain(client, path, basemap, city_table, leader_ids, basemap_source,
                      enabled=False, city_attribute='city_code', png_path=None, synthetic=False):
    files = tuple(Path(p) for p in (path, basemap, city_table))
    if any(not f.is_absolute() or not f.is_file() for f in files):
        raise ValueError('Supply chain data, basemap and city table must be existing absolute paths')
    if png_path is not None and not enabled:
        raise ValueError('Map PNG export needs an explicit enabled=True')
    image = output_path(png_path)
    state = client.request('/state', timeout=5)
    if 'geography' not in state.get('capabilities', []):
        raise RuntimeError('Restart Gephi with Gephi 3D Explorer 0.1.3 or later')
    network, layer, cities = files
    graph = read_network(network)
    geography, report = prepare_geography(graph, layer, cities, leader_ids, basemap_source, city_attribute)
    with TemporaryDirectory(prefix='gephi_supply_map_') as directory:
        data = Path(directory) / (network.stem + '.gexf')
        prepared = Path(directory) / 'geography.json'
        nx.write_gexf(graph, data, encoding='utf-8', version='1.2draft')
        with prepared.open('w', encoding='utf-8') as stream:
            json.dump(geography, stream, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
        if prepared.stat().st_size > 10_000_000:
            raise ValueError('Simplify basemap before importing: prepared layer exceeds 10 MB')
        result = draw(client, str(data), synthetic=synthetic)
        workspace = result['workspace_id']
        layout = client.command('geography', workspace, map_path=str(prepared), enabled=enabled)
    result['geography'] = {**report, 'enabled': layout['scene']['geography']['enabled']}
    if image is not None:
        result['png_path'] = client.command('export_png', workspace, path=str(image), width=1800, height=1400)['path']
    return result
