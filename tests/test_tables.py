import sys
import json
from pathlib import Path
import pytest
import networkx as nx
import openpyxl
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tables_to_gexf import convert

def test_string_ids_chinese_zero_weight(tmp_path):
    n=tmp_path/'nodes.xlsx';e=tmp_path/'edges.csv';out=tmp_path/'network.gexf'
    wb=openpyxl.Workbook();s=wb.active;s.append(['id','label','community']);s.append(['001','中文节点','甲']);s.append(['002','另一个节点','乙']);wb.save(n)
    e.write_text('source,target,weight\n001,002,0\n',encoding='utf-8')
    assert convert(n,e,out,True)=={'nodes':2,'edges':1,'directed':True}
    g=nx.read_gexf(out);assert g.nodes['001']['label']=='中文节点' and g['001']['002']['weight']==0

def test_bad_endpoints_duplicate_and_nan(tmp_path):
    n=tmp_path/'nodes.csv';e=tmp_path/'edges.csv';out=tmp_path/'result.gexf'
    n.write_text('id,label\na,A\nb,B\n',encoding='utf-8')
    e.write_text('source,target,weight\na,c,1\n',encoding='utf-8')
    with pytest.raises(ValueError,match='Unknown endpoint'):convert(n,e,out,False)
    e.write_text('source,target,weight\na,b,nan\n',encoding='utf-8')
    with pytest.raises(ValueError,match='Invalid weight'):convert(n,e,out,False)
    e.write_text('source,target,weight\na,b,1\nb,a,2\n',encoding='utf-8')
    with pytest.raises(ValueError,match='Duplicate edge'):convert(n,e,out,False)
    convert(n,e,out,False,'sum');assert nx.read_gexf(out)['a']['b']['weight']==3


def test_years_survive_pair_merging(tmp_path):
    nodes = tmp_path / 'nodes.csv'
    edges = tmp_path / 'edges.csv'
    result = tmp_path / 'annual.gexf'
    nodes.write_text('id\n001\n002\n', encoding='utf-8')
    edges.write_text('source,target,year,weight\n001,002,2020,2\n001,002,2021,5\n', encoding='utf-8')
    convert(nodes, edges, result, True)
    pair = nx.read_gexf(result)['001']['002']
    assert pair['weight'] == 7 and json.loads(pair['year_weights']) == {'2020': 2, '2021': 5}
    edges.write_text('source,target,year,weight\n001,002,2021,2\n001,002,2021,5\n', encoding='utf-8')
    with pytest.raises(ValueError, match='Duplicate edge'):
        convert(nodes, edges, tmp_path / 'duplicate.gexf', True)
