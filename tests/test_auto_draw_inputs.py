"""Check input inference before any operation can create a Gephi workspace."""
import json
from pathlib import Path
import sys

import openpyxl
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'bridge'))
from auto_draw import draw
from gephi3d_client import Gephi3D
from relationship_table import read_network


def test_single_sheet_keeps_explicit_zeros_and_role_direction(tmp_path):
    path = tmp_path/'关系.xlsx'
    book = openpyxl.Workbook()
    sheet = book.active
    sheet.append(['供应商代码', '客户代码', '年份', '权重'])
    sheet.append([2608, '000001', 2024, 1.5])
    sheet['A2'].number_format = '000000'
    book.save(path)
    graph = read_network(path, directed=None)
    assert graph.is_directed() and set(graph.edges) == {('002608', '000001')}
    assert graph.graph['input_columns']['source'] == '供应商代码'
    assert json.loads(graph['002608']['000001']['year_weights']) == {'2024': 1.5}


def test_explicit_confirmation_for_direction_and_same_year_duplicates(tmp_path):
    path = tmp_path/'table.csv'
    path.write_text('source,target,year\n001,002,2024\n001,002,2024\n', encoding='utf-8')
    with pytest.raises(ValueError, match='directed=True'):
        read_network(path, directed=None)
    with pytest.raises(ValueError, match='Duplicate edge'):
        read_network(path, directed=True)
    graph = read_network(path, directed=True, duplicates='sum')
    assert json.loads(graph['001']['002']['year_weights']) == {'2024': 2}


def test_named_workbook_preserves_isolated_nodes_and_edge_attributes(tmp_path):
    book = openpyxl.Workbook()
    nodes = book.active
    nodes.title = '节点表'
    nodes.append(['id', 'label'])
    nodes.append(['001', '甲'])
    nodes.append(['002', '乙'])
    nodes.append(['003', '孤立节点'])
    edges = book.create_sheet('连接表')
    edges.append(['source', 'target', 'weight', 'label', 'quantity'])
    edges.append(['001', '002', 2, '订单', 10])
    edges['E2'].number_format = '000000'
    file = tmp_path/'双表.xlsx'
    book.save(file)
    graph = read_network(file, directed=True)
    assert set(graph.nodes) == {'001', '002', '003'}
    assert graph['001']['002'] == {'weight': 2, 'label': '订单', 'quantity': 10}


def test_invalid_request_does_not_import(tmp_path):
    class Client:
        def request(self, path, timeout):
            assert path == '/state'
            return {'layout': {'state': 'idle'},
                    'capabilities': ['import_graph', 'visual_presets', 'supply_scope', 'edge_years']}

        def command(self, *args, **kwargs):
            pytest.fail('Invalid input must not create a workspace')

    data = tmp_path/'table.csv'
    data.write_text('supplier_symbol,customer_symbol,year\n001,002,2024\n', encoding='utf-8')
    with pytest.raises(ValueError, match='找不到企业代码'):
        draw(Client(), str(data), roots=['003'], direction='upstream')
    with pytest.raises(ValueError, match='可用年份：2024'):
        draw(Client(), str(data), roots=['002'], direction='upstream', year=2021)


def test_connection_check_reports_the_configured_launcher(tmp_path):
    report = Gephi3D('http://127.0.0.1:8184', tmp_path/'nonexistent').diagnose()
    assert report['reason'] == 'missing_token' and report['connection'] == 'isolated'
    assert 'Start-Gephi-3D.cmd' in report['next_step']
