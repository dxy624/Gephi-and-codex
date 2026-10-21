import hashlib
import json
from pathlib import Path
import socket
import sys
import tomllib
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from common import ROOT,install_module,resolve_gephi,resolve_java,running_instance
from configure_codex import configure
from build import verify

def tree(path):
    return {str(p.relative_to(path)):hashlib.sha256(p.read_bytes()).hexdigest() for p in path.rglob('*') if p.is_file()} if path.exists() else {}

def test_preview_and_idempotent_preserve_config(tmp_path):
    home=tmp_path/'Codex 中文';home.mkdir();config=home/'config.toml'
    original='# preserve this comment\nmodel = "example"\n[mcp_servers.other]\ncommand = "other-tool"\n'
    config.write_text(original,encoding='utf-8')
    before=tree(home);state=tmp_path/'状态目录';python=tmp_path/'Python space/python.exe'
    result=configure(home,python,state,8184,True,True,True)
    assert result['config_change'] and tree(home)==before and not state.exists()
    result=configure(home,python,state,8184,True,True)
    after=config.read_bytes();parsed=tomllib.loads(after.decode())
    assert parsed['mcp_servers']['other']['command']=='other-tool' and parsed['model']=='example'
    assert '# preserve this comment' in after.decode()
    assert Path(result['backup']).read_text(encoding='utf-8')==original
    result=configure(home,python,state,8184,True,True)
    assert not result['config_change'] and not result['skill_change'] and config.read_bytes()==after

def test_conflicts_leave_config_and_skill_untouched(tmp_path):
    home=tmp_path/'codex';home.mkdir();config=home/'config.toml'
    config.write_text('[mcp_servers.gephi-3d]\ncommand="unrelated"\n',encoding='utf-8')
    before=tree(home)
    with pytest.raises(RuntimeError,match='configuration differs'):configure(home,Path(sys.executable),tmp_path/'state',8184,True,True)
    assert tree(home)==before
    config.write_text('model="other"\n',encoding='utf-8');skill=home/'skills/gephi-3d';skill.mkdir(parents=True);(skill/'SKILL.md').write_text('user edited skill',encoding='utf-8')
    before=tree(home)
    with pytest.raises(RuntimeError,match='skill differs'):configure(home,Path(sys.executable),tmp_path/'state',8184,True,True)
    assert tree(home)==before

def test_nbm_and_exact_module_extraction(tmp_path):
    assert len(verify(ROOT/'dist/gephi-3d-explorer-0.1.0.nbm'))==64
    state=tmp_path/'运行 数据';install_module(state)
    before=tree(state);assert len(before)==2
    install_module(state);assert tree(state)==before

def test_occupied_port_is_not_reused(tmp_path):
    # Use the designated test port, and skip rather than touching existing apps.
    with socket.socket() as server:
        try:server.bind(('127.0.0.1',8183))
        except OSError:pytest.skip('8183 occupied or unavailable; no service changed')
        server.listen()
        with pytest.raises(RuntimeError,match='occupied'):running_instance(tmp_path,8183)

def test_free_port_allows_cold_start(tmp_path):
    with socket.socket() as probe:
        try:probe.bind(('127.0.0.1',8183))
        except OSError:pytest.skip('Test port unavailable; no existing instance changed')
    assert running_instance(tmp_path,8183) is False
