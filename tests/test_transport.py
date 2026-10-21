"""Connection failures stay explicit; API import refuses unsupported old plugins."""
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
