"""Convert node/edge CSV or XLSX without losing string IDs or Chinese labels."""
import argparse
import csv
import json
import math
from pathlib import Path
import networkx as nx

def rows(path):
    path=Path(path)
    if path.suffix.lower()=='.csv':
        with path.open(encoding='utf-8-sig',newline='') as f:return list(csv.DictReader(f))
    if path.suffix.lower()=='.xlsx':
        import openpyxl
        wb=openpyxl.load_workbook(path,read_only=True,data_only=True)
        try:
            data=iter(wb.active.values);header=next(data);return [dict(zip(header,row)) for row in data if any(x is not None for x in row)]
        finally:wb.close()
    raise ValueError('Expected .csv or .xlsx')


def edge_year(value, row):
    if value is None or str(value).strip() == '':
        return None
    try:
        year = float(value)
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValueError(f'Invalid relationship year at row {row}') from exc
    if not math.isfinite(year) or not year.is_integer() or not 1 <= year <= 9999:
        raise ValueError(f'Invalid relationship year at row {row}')
    return int(year)


def add_relationship(graph, source, target, weight, year, attributes, row, duplicates='error'):
    if graph.has_edge(source, target):
        edge = graph[source][target]
        annual = edge.get('_annual_weights')
        if (annual is None) != (year is None):
            raise ValueError(f'The same relationship has both dated and undated rows at row {row}')
        if (year is None or year in annual) and duplicates == 'error':
            raise ValueError(f'Duplicate edge at row {row}: {source}, {target}; '
                             'choose --duplicates sum explicitly if appropriate')
        total = edge['weight'] + weight
        if not math.isfinite(total):
            raise ValueError(f'Edge weight overflow at row {row}')
        edge['weight'] = total
        if year is not None:
            annual[year] = annual.get(year, 0) + weight
    else:
        graph.add_edge(source, target, weight=weight, **attributes)
        if year is not None:
            graph[source][target]['_annual_weights'] = {year: weight}


def serialize_relationship_years(graph):
    for _, _, edge in graph.edges(data=True):
        annual = edge.pop('_annual_weights', None)
        if annual is not None:
            edge['year_weights'] = json.dumps(annual, sort_keys=True, separators=(',', ':'), allow_nan=False)


def graph_from_rows(node_rows, edge_rows, directed, duplicates='error'):
    g=nx.DiGraph() if directed else nx.Graph()
    for index, original in node_rows:
        row = original.copy()
        if 'id' not in row or row['id'] is None or not str(row['id']).strip():raise ValueError(f'Node id is required at row {index}')
        id=str(row.pop('id'))
        if id in g:raise ValueError(f'Duplicate node ID at row {index}: {id}')
        attrs={str(k):v for k,v in row.items() if k is not None and v is not None}
        g.add_node(id,**attrs)
    for index, original in edge_rows:
        row = original.copy()
        if row.get('source') is None or row.get('target') is None:raise ValueError(f'Missing endpoints at row {index}')
        a,b=str(row.pop('source')),str(row.pop('target'))
        if a not in g or b not in g:raise ValueError(f'Unknown endpoint at row {index}: {a}, {b}')
        raw_weight=row.pop('weight',1)
        try:
            weight=float(1 if raw_weight is None or raw_weight=='' else raw_weight)
        except (ValueError, TypeError, OverflowError) as exc:
            raise ValueError(f'Invalid weight at row {index}') from exc
        if not math.isfinite(weight) or weight<0:raise ValueError(f'Invalid weight at row {index}')
        year = edge_year(row.pop('year', None), index)
        attrs = {str(k): v for k,v in row.items() if k is not None and v is not None}
        add_relationship(g, a, b, weight, year, attrs, index, duplicates)
    serialize_relationship_years(g)
    return g


def read_sheet_data(book, name, required):
    if name not in book.sheetnames:
        raise ValueError(f'Worksheet {name!r} not found. Available sheets: {", ".join(book.sheetnames)}')
    data = iter(book[name].values)
    header = next(data, ())
    titles = [value for value in header if value is not None]
    if len(titles) != len(set(titles)):
        raise ValueError(f'Worksheet {name!r} has duplicate column names in row 1')
    missing = required.difference(titles)
    if missing:
        raise ValueError(f'Worksheet {name!r} is missing columns: {", ".join(sorted(missing))}')
    for number, row in enumerate(data, 2):
        if any(value is not None for value in row):
            yield f'{number} ({name})', dict(zip(header, row))


def graph_from_workbook(path, directed, node_sheet='节点表', edge_sheet='连接表'):
    from openpyxl import load_workbook

    book = load_workbook(path, read_only=True, data_only=True)
    try:
        nodes = read_sheet_data(book, node_sheet, {'id'})
        edges = read_sheet_data(book, edge_sheet, {'source', 'target'})
        return graph_from_rows(nodes, edges, directed)
    finally:
        book.close()


def convert(nodes,edges,output,directed,duplicates='error'):
    g = graph_from_rows(enumerate(rows(nodes), 2), enumerate(rows(edges), 2), directed, duplicates)
    output=Path(output)
    if output.exists():raise FileExistsError(output)
    nx.write_gexf(g,output,encoding='utf-8')
    return {'nodes':len(g),'edges':g.number_of_edges(),'directed':directed}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--nodes',required=True);p.add_argument('--edges',required=True);p.add_argument('--output',required=True)
    group=p.add_mutually_exclusive_group(required=True);group.add_argument('--directed',action='store_true');group.add_argument('--undirected',action='store_true')
    p.add_argument('--duplicates',choices=['error','sum'],default='error');a=p.parse_args()
    print(convert(a.nodes,a.edges,a.output,a.directed,a.duplicates))
