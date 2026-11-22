"""Read local relation tables for either a plain network or a geographic view."""
from __future__ import annotations

import csv
from math import isfinite
from pathlib import Path
import re
import sys

import networkx as nx

ROOT = str(Path(__file__).resolve().parents[1])
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)
from scripts.tables_to_gexf import add_relationship, edge_year, read_sheet_data, serialize_relationship_years

UPSTREAM = ('supplier_symbol', 'supplier_code', 'supplier_id', 'supplier', '供应商股票代码', '供应商代码', '供应商')
DOWNSTREAM = ('customer_symbol', 'customer_code', 'customer_id', 'customer', '客户股票代码', '客户代码', '客户')
SOURCE = ('source', 'source_id', '源节点', '起点')
TARGET = ('target', 'target_id', '目标节点', '终点')
OPTIONAL = {
    'year': ('year', '年份', '年度'),
    'weight': ('weight', '边权', '权重'),
    'source_city_code': ('source_city_code', 'supplier_city_code', '供应商城市代码', '供应商所在城市代码'),
    'target_city_code': ('target_city_code', 'customer_city_code', '客户城市代码', '客户所在城市代码'),
    'source_city_name': ('source_city_name', 'supplier_city_name', '供应商城市', '供应商所在城市名称'),
    'target_city_name': ('target_city_name', 'customer_city_name', '客户城市', '客户所在城市名称'),
    'source_name': ('source_name', 'supplier_name', '供应商名称'),
    'target_name': ('target_name', 'customer_name', '客户名称'),
    'source_community': ('source_community', 'supplier_community', '供应商社区'),
    'target_community': ('target_community', 'customer_community', '客户社区'),
    'source_industry': ('source_industry', 'supplier_industry', '供应商行业'),
    'target_industry': ('target_industry', 'customer_industry', '客户行业'),
}
NODE_FIELDS = {
    'label': ('企业名称', '节点名称', '公司名称'),
    'city_code': ('城市代码', '城市编码'),
    'city_name': ('城市名称', '城市'),
    'community': ('社区', '社群'),
    'industry': ('行业',),
}


def city_code(value):
    match = re.fullmatch(r'([0-9]{6})(?:\.0+)?', str(value).strip())
    return match[1] if match else None


def header_index(headers):
    index = {}
    for field in headers:
        if field is None or not str(field).strip():
            continue
        key = str(field).strip().casefold()
        if key in index:
            raise ValueError(f'关系表包含重复列名：{field}')
        index[key] = field
    return index


def resolve_columns(headers, directed, source_column=None, target_column=None):
    index = header_index(headers)

    def fields(aliases):
        return [index[key] for key in aliases if key in index]

    if source_column is not None or target_column is not None:
        if not source_column or not target_column or source_column.strip().casefold() == target_column.strip().casefold():
            raise ValueError('请指定两列不同的起点和终点字段。')
        a, b = index.get(source_column.strip().casefold()), index.get(target_column.strip().casefold())
        if a is None or b is None:
            raise ValueError(f'找不到指定列。当前字段：{", ".join(map(str, index.values()))}')
        pair = (a, b)
        supplier_roles = str(a).strip().casefold() in UPSTREAM and str(b).strip().casefold() in DOWNSTREAM
    else:
        candidate_pairs = [(a, b) for a in fields(UPSTREAM) for b in fields(DOWNSTREAM)]
        supplier_roles = bool(candidate_pairs)
        if not candidate_pairs:
            candidate_pairs = [(a, b) for a in fields(SOURCE) for b in fields(TARGET)]
        if len(candidate_pairs) != 1:
            raise ValueError('无法唯一匹配关系表的起点和终点。请指定 source_column 和 target_column。'
                             f' 当前字段：{", ".join(map(str, index.values()))}')
        pair = candidate_pairs[0]
    if directed is None:
        if not supplier_roles:
            raise ValueError('请确认起点指向终点（directed=True），或关系为双向（directed=False）。')
        directed = True
    mapping = {'source': pair[0], 'target': pair[1]}
    for name, aliases in OPTIONAL.items():
        found = fields(aliases)
        if found:
            mapping[name] = found[0]
    return mapping, directed, supplier_roles


def source_rows(path):
    if path.suffix.lower() == '.csv':
        with path.open(encoding='utf-8-sig', newline='') as stream:
            rows = csv.DictReader(stream)
            header_index(rows.fieldnames or ())
            yield from enumerate(rows, 2)
        return
    try:
        import pandas as pd
    except ImportError as exc:
        raise RuntimeError('读取 .dta 需要发行包中的 pandas。请运行安装程序，或提供同样字段的 CSV。') from exc
    with pd.read_stata(path, convert_categoricals=False, chunksize=20_000) as reader:
        number = 2
        for chunk in reader:
            for row in chunk.astype(object).where(chunk.notna(), None).to_dict('records'):
                yield number, row
                number += 1


def identifier(value):
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    return '' if value is None else str(value).strip()


def normalize_edges(rows, columns, keep_attributes):
    used = set(columns.values())
    for line, raw in rows:
        row = {name: raw.get(header) for name, header in columns.items()}
        attrs = {str(key): value for key, value in raw.items()
                 if keep_attributes and key is not None and key not in used and value is not None}
        for key in (*columns, '_annual_weights'):
            attrs.pop(key, None)
        for side in ('source', 'target'):
            row[side] = identifier(row[side])
            if not row[side]:
                raise ValueError(f'第 {line} 行缺少企业或节点编号。')
        yield line, row, attrs


def make_network(rows, columns, directed, supplier_roles, duplicates, nodes=None):
    graph = nx.DiGraph() if directed else nx.Graph()
    if nodes is not None:
        for line, row in nodes:
            node_id = identifier(row.get('id'))
            if not node_id or node_id in graph:
                raise ValueError(f'第 {line} 行的节点 id 缺失或重复。')
            graph.add_node(node_id, **{str(k): v for k, v in row.items() if k not in (None, 'id') and v is not None})
        if len(graph) > 10_000:
            raise ValueError('Gephi 3D 支持至多 10000 个节点。')
    cities = {}
    observed = 0
    for line, row, attrs in normalize_edges(rows, columns, nodes is not None):
        year = edge_year(row.get('year'), line)
        a, b = row['source'], row['target']
        for side in ('source', 'target'):
            firm = row[side]
            if firm not in graph:
                if nodes is not None:
                    raise ValueError(f'第 {line} 行的端点 {firm} 不在节点表。')
                graph.add_node(firm, label=firm)
            data = graph.nodes[firm]
            for suffix, field in (('name', 'label'), ('community', 'community'), ('industry', 'industry')):
                value = identifier(row.get(f'{side}_{suffix}'))
                if not value:
                    continue
                existing = data.get(field)
                if not existing or field == 'label' and existing == firm:
                    data[field] = value
                elif nodes is None and str(existing) != value:
                    raise ValueError(f'第 {line} 行的企业 {firm} 存在冲突的 {field}。请先确认采用哪个值。')
            code = identifier(row.get(f'{side}_city_code'))
            name = identifier(row.get(f'{side}_city_name'))
            if not code and not name:
                continue
            code = city_code(code) or code
            prior = cities.get(firm)
            date = year or 0
            if prior is None or (code and not prior[1]) or (bool(code) == bool(prior[1]) and date > prior[0]):
                cities[firm] = (date, code, name)
            elif date == prior[0]:
                if code and prior[1] and code != prior[1]:
                    raise ValueError(f'第 {line} 行的企业 {firm} 在同一年度有冲突的城市代码。')
                cities[firm] = (date, code or prior[1], name or prior[2])
        value = row.get('weight')
        try:
            weight = float(1 if value in (None, '') else value)
        except (ValueError, TypeError, OverflowError) as exc:
            raise ValueError(f'第 {line} 行的权重无效。') from exc
        if not isfinite(weight) or weight < 0:
            raise ValueError(f'第 {line} 行的权重无效。')
        add_relationship(graph, a, b, weight, year, attrs, line, duplicates)
        observed += 1
        if len(graph) > 10_000 or graph.number_of_edges() > 100_000:
            raise ValueError('Gephi 3D 支持至多 10000 个节点、100000 条边。')
    if not graph:
        raise ValueError('关系表没有可导入的关系。')
    for firm, (_, code, name) in cities.items():
        data = graph.nodes[firm]
        if code and not data.get('city_code'):
            data['city_code'] = code
        if name and not data.get('city_name'):
            data['city_name'] = name
    serialize_relationship_years(graph)
    if any(date for date, _, _ in cities.values()):
        graph.graph['city_reference'] = '最近一次有记录的城市代码（各年共用）'
    graph.graph.update(input_columns=columns, input_rows=observed, supplier_roles=supplier_roles)
    return graph


def workbook_table(path, directed, node_sheet, edge_sheet, source_column, target_column, duplicates):
    from openpyxl import load_workbook

    if node_sheet == edge_sheet:
        raise ValueError('请为节点表和关系表选择不同的工作表。')
    book = load_workbook(path, read_only=True, data_only=True)
    try:
        if node_sheet != '节点表' and node_sheet not in book.sheetnames:
            raise ValueError(f'找不到节点工作表 {node_sheet}。')
        if edge_sheet in book.sheetnames:
            candidate = edge_sheet
        elif edge_sheet != '连接表':
            raise ValueError(f'找不到关系工作表 {edge_sheet}。')
        elif len(book.sheetnames) == 1:
            candidate = book.sheetnames[0]
        else:
            candidates = []
            for name in book.sheetnames:
                header = next(book[name].values, ())
                try:
                    resolve_columns(header, True, source_column, target_column)
                except ValueError:
                    continue
                candidates.append(name)
            if len(candidates) != 1:
                raise ValueError('请指定关系工作表 edge_sheet。可用工作表：' + '、'.join(book.sheetnames))
            candidate = candidates[0]
        cols, direction, supplier = resolve_columns(next(book[candidate].values, ()), directed, source_column, target_column)
        edge_rows = read_sheet_data(book, candidate, set(), {cols['source'], cols['target']})
        nodes = None
        if node_sheet in book.sheetnames and node_sheet != candidate:
            index = header_index(next(book[node_sheet].values, ()))
            id_columns = [index[key] for key in ('node_id', 'firm_code', 'stock_code', '企业代码', '股票代码', '节点编号')
                          if key in index]
            node_id = index.get('id') or (id_columns[0] if len(id_columns) == 1 else None)
            if node_id is None:
                raise ValueError(f'节点表 {node_sheet} 缺少可唯一识别的 ID 列，请使用 id。')
            nodes = normalize_nodes(read_sheet_data(book, node_sheet, {node_id}, {node_id}), node_id, index)
        return make_network(edge_rows, cols, direction, supplier, duplicates, nodes)
    finally:
        book.close()


def normalize_nodes(rows, node_id, header):
    for line, raw in rows:
        row = {**raw, 'id': raw[node_id]}
        for field, aliases in NODE_FIELDS.items():
            if field not in row or row[field] in (None, ''):
                value = next((raw[header[alias]] for alias in aliases if alias in header
                              and raw[header[alias]] not in (None, '')), None)
                if value is not None:
                    row[field] = value
        yield line, row


def read_network(path, directed=True, *, source_column=None, target_column=None, duplicates='error',
                 node_sheet='节点表', edge_sheet='连接表'):
    suffix = path.suffix.lower()
    if suffix == '.gexf':
        return nx.read_gexf(path)
    if suffix == '.xlsx':
        return workbook_table(path, directed, node_sheet, edge_sheet, source_column, target_column, duplicates)
    if suffix not in {'.csv', '.dta'}:
        raise ValueError('关系表须为 XLSX、CSV、DTA 或 GEXF。')
    rows = source_rows(path)
    first = next(rows, None)
    if first is None:
        raise ValueError('关系表没有数据行。')
    from itertools import chain
    columns, direction, supplier = resolve_columns(first[1], directed, source_column, target_column)
    return make_network(chain((first,), rows), columns, direction, supplier, duplicates)
