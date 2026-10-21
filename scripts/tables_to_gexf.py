"""Convert node/edge CSV or XLSX without losing string IDs or Chinese labels."""
import argparse
import csv
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

def convert(nodes,edges,output,directed,duplicates='error'):
    ns,es=rows(nodes),rows(edges);g=nx.DiGraph() if directed else nx.Graph()
    for row in ns:
        if 'id' not in row or row['id'] is None or not str(row['id']).strip():raise ValueError('Node id is required')
        id=str(row.pop('id'))
        if id in g:raise ValueError(f'Duplicate node ID: {id}')
        attrs={str(k):v for k,v in row.items() if k is not None and v is not None}
        g.add_node(id,**attrs)
    for index,row in enumerate(es,2):
        if row.get('source') is None or row.get('target') is None:raise ValueError(f'Missing endpoints at row {index}')
        a,b=str(row.pop('source')),str(row.pop('target'))
        if a not in g or b not in g:raise ValueError(f'Unknown endpoint at row {index}: {a}, {b}')
        raw_weight=row.pop('weight',1)
        weight=float(1 if raw_weight is None or raw_weight=='' else raw_weight)
        if not math.isfinite(weight) or weight<0:raise ValueError(f'Invalid weight at row {index}')
        if g.has_edge(a,b):
            if duplicates=='error':raise ValueError(f'Duplicate edge: {a}, {b}; choose --duplicates sum explicitly if appropriate')
            g[a][b]['weight']+=weight;continue
        g.add_edge(a,b,weight=weight,**{str(k):v for k,v in row.items() if k is not None and v is not None})
    output=Path(output)
    if output.exists():raise FileExistsError(output)
    nx.write_gexf(g,output,encoding='utf-8')
    return {'nodes':len(g),'edges':g.number_of_edges(),'directed':directed}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--nodes',required=True);p.add_argument('--edges',required=True);p.add_argument('--output',required=True)
    group=p.add_mutually_exclusive_group(required=True);group.add_argument('--directed',action='store_true');group.add_argument('--undirected',action='store_true')
    p.add_argument('--duplicates',choices=['error','sum'],default='error');a=p.parse_args()
    print(convert(a.nodes,a.edges,a.output,a.directed,a.duplicates))
