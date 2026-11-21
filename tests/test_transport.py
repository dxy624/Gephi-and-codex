"""Connection failures stay explicit; capability checks protect older plugins."""
from io import BytesIO
from pathlib import Path
import sys
import urllib.error
import pytest
from mcp.server.mcpserver.exceptions import ToolError

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'bridge'))
from gephi3d_client import Gephi3D


def test_missing_token_and_authentication_are_actionable(tmp_path,monkeypatch):
    token=tmp_path/'token'
    client=Gephi3D('http://127.0.0.1:8183',token)
    with pytest.raises(RuntimeError,match='token file is missing'):client.state()
    token.write_text('test-only-token',encoding='utf-8')
    def unauthorized(*args,**kwargs):
        raise urllib.error.HTTPError(client.url,401,'Unauthorized',{},BytesIO(b'{}'))
    monkeypatch.setattr('urllib.request.urlopen',unauthorized)
    with pytest.raises(RuntimeError,match='same Gephi instance'):client.state()


def test_connection_loss_does_not_retry_a_command(tmp_path,monkeypatch):
    token=tmp_path/'token';token.write_text('test-only-token',encoding='utf-8')
    client=Gephi3D('http://127.0.0.1:8183',token);calls=[]
    def unavailable(*args,**kwargs):
        calls.append(args)
        raise urllib.error.URLError('connection refused')
    monkeypatch.setattr('urllib.request.urlopen',unavailable)
    with pytest.raises(RuntimeError,match='Cannot connect'):client.command('import_graph',path='example.gexf')
    assert len(calls)==1
    def timeout(*args,**kwargs):
        calls.append(args);raise TimeoutError('request deadline')
    monkeypatch.setattr('urllib.request.urlopen',timeout)
    with pytest.raises(RuntimeError,match='outcome is unknown'):client.command('generate',nodes=60)
    assert len(calls)==2


def test_old_plugin_is_rejected_before_import(monkeypatch):
    import server
    class OldPlugin:
        def state(self):return {'success':True,'version':'0.1.0-alpha'}
        def command(self,*args,**kwargs):pytest.fail('Unsupported import must not be submitted')
    monkeypatch.setattr(server,'client',OldPlugin())
    with pytest.raises(ToolError,match='does not support API import'):
        server.gephi3d_import_graph('example.gexf')


def test_visual_presets_require_plugin_support(monkeypatch):
    import server

    class Plugin:
        capabilities = []
        calls = []

        def state(self):
            return {'success': True, 'capabilities': self.capabilities}

        def command(self, operation, workspace_id, **parameters):
            self.calls.append((operation, workspace_id, parameters))
            return {'success': True}

    plugin = Plugin()
    monkeypatch.setattr(server, 'client', plugin)
    with pytest.raises(ToolError, match='does not support visual presets'):
        server.gephi3d_style(7, preset='weighted')
    assert not plugin.calls
    server.gephi3d_style(7, dark=False)
    plugin.capabilities = ['visual_presets']
    server.gephi3d_style(7, preset='weighted', arrows=False)
    with pytest.raises(ToolError, match='does not support arrow sizes'):
        server.gephi3d_style(7, arrow_scale=1.8)
    plugin.capabilities.append('arrow_style')
    server.gephi3d_style(7, arrows=True, arrow_scale=1.8, edge_width=2.2)
    assert plugin.calls == [
        ('style', 7, {'dark': False}),
        ('style', 7, {'preset': 'weighted', 'arrows': False}),
        ('style', 7, {'arrows': True, 'arrow_scale': 1.8, 'edge_width': 2.2}),
    ]


def test_paper_export_requires_support_and_preserves_options(monkeypatch):
    import server

    class Plugin:
        capabilities = []

        def state(self):
            return {'capabilities': self.capabilities}

        def command(self, operation, workspace_id, **parameters):
            assert operation == 'export_svg' and workspace_id == 7
            assert parameters == {
                'path': 'paper.svg', 'width_mm': 89, 'title': '企业关系',
                'direction': '供应商 → 客户', 'width': 1200, 'height': 800,
            }
            return {'success': True, 'path': 'paper.svg'}

    plugin = Plugin()
    monkeypatch.setattr(server, 'client', plugin)
    with pytest.raises(ToolError, match='does not support editable paper export'):
        server.gephi3d_export_paper(7, 'paper.svg')
    plugin.capabilities.append('paper_svg')
    result = server.gephi3d_export_paper(7, 'paper.svg', 89, '企业关系', '供应商 → 客户')
    assert result['success']
