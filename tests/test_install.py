import hashlib
from io import BytesIO
import json
from pathlib import Path
import socket
import sys
import tomllib
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from common import NBM, desktop_profile, install_module, install_profile, running_instance
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
    assert len(verify(NBM))==64
    state=tmp_path/'运行 数据';install_module(state)
    before=tree(state);assert len(before)==2
    install_module(state);assert tree(state)==before

def test_desktop_install_preview_and_conflicts(tmp_path, monkeypatch):
    roaming = tmp_path/'用户资料 Roaming'
    monkeypatch.setenv('APPDATA', str(roaming))
    profile = desktop_profile()
    assert profile == roaming/'gephi/0.11'
    assert install_profile(profile, desktop=True, dry_run=True)
    assert not roaming.exists()
    profile.mkdir(parents=True)
    lock = profile/'lock'
    lock.touch()
    with pytest.raises(RuntimeError, match='Save and close desktop Gephi'):
        install_profile(profile, desktop=True)
    assert list(profile.iterdir()) == [lock]
    lock.unlink()
    assert install_profile(profile, desktop=True)
    before = tree(profile)
    assert len(before) == 2
    assert not install_profile(profile, desktop=True)
    assert tree(profile) == before
    xml = profile/'config/Modules/org-gephi-plugins-explorer3d.xml'
    xml.write_bytes(b'previous module')
    before = tree(profile)
    with pytest.raises(RuntimeError, match='existing desktop plugin file differs'):
        install_profile(profile, desktop=True)
    assert tree(profile) == before

def test_existing_connection_switches_with_explicit_backup(tmp_path, monkeypatch):
    home = tmp_path/'Codex config'
    home.mkdir()
    config = home/'config.toml'
    config.write_text('[mcp_servers.other]\ncommand = "other tool"\n', encoding='utf-8')
    state = tmp_path/'independent'
    python = Path(sys.executable)
    monkeypatch.setenv('APPDATA', str(tmp_path/'桌面 AppData'))
    configure(home, python, state, 8184, configure_mcp=True)
    original = config.read_bytes()
    with pytest.raises(RuntimeError, match='configuration differs'):
        configure(home, python, state, 8183, configure_mcp=True, mode='desktop')
    preview = configure(home, python, state, 8183, configure_mcp=True, mode='desktop',
                        replace_connection=True, dry_run=True)
    assert preview['config_change'] and config.read_bytes() == original
    result = configure(home, python, state, 8183, configure_mcp=True, mode='desktop',
                       replace_connection=True)
    assert Path(result['backup']).read_bytes() == original
    settings = tomllib.loads(config.read_text(encoding='utf-8'))['mcp_servers']
    assert settings['other'] == {'command': 'other tool'}
    assert settings['gephi-3d']['env'] == {
        'GEPHI3D_URL': 'http://127.0.0.1:8183',
        'GEPHI3D_TOKEN_FILE': str(desktop_profile()/'config/gephi3d-token'),
    }


def test_known_desktop_upgrade_backs_up_previous_module(tmp_path, monkeypatch):
    profile = tmp_path/'Desktop profile'
    assert install_profile(profile, desktop=True)
    module = profile/'modules/org-gephi-plugins-explorer3d.jar'
    current = module.read_bytes()
    old = b'module from the previous installer'
    module.write_bytes(old)
    registration = profile/'config/Modules/org-gephi-plugins-explorer3d.xml'
    formatted_registration = registration.read_bytes().replace(b'><param', b'>\n    <param')
    registration.write_bytes(formatted_registration)
    monkeypatch.setattr('common.PREVIOUS_DESKTOP_MODULES', {hashlib.sha256(old).hexdigest()})
    before = tree(profile)
    assert install_profile(profile, desktop=True, dry_run=True)
    assert tree(profile) == before
    assert install_profile(profile, desktop=True)
    assert module.read_bytes() == current
    assert registration.read_bytes() == formatted_registration
    backups = list((profile/'gephi3d-backups').glob('*.jar'))
    assert len(backups) == 1 and backups[0].read_bytes() == old
    assert not install_profile(profile, desktop=True)

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


def test_recognized_old_instance_is_not_reused_or_stopped(tmp_path,monkeypatch):
    token=tmp_path/'profile/config/gephi3d-token';token.parent.mkdir(parents=True);token.write_text('test-only-token',encoding='utf-8')
    with socket.socket() as server:
        try:server.bind(('127.0.0.1',8183))
        except OSError:pytest.skip('Test port unavailable; no existing instance changed')
        server.listen()
        monkeypatch.setattr('urllib.request.urlopen',lambda *a,**k:BytesIO(b'{"success":true,"version":"0.1.0-alpha"}'))
        with pytest.raises(RuntimeError,match='Save your work and close it normally'):running_instance(tmp_path,8183)
        monkeypatch.setattr('urllib.request.urlopen',lambda *a,**k:BytesIO(b'{"success":true,"version":"0.1.1-alpha","capabilities":["import_graph"]}'))
        with pytest.raises(RuntimeError, match='Save your work and close it normally'):
            running_instance(tmp_path,8183)
        monkeypatch.setattr('urllib.request.urlopen',lambda *a,**k:BytesIO(b'{"success":true,"version":"0.1.3-alpha","capabilities":["import_graph","geography"]}'))
        with pytest.raises(RuntimeError, match='Save your work and close it normally'):
            running_instance(tmp_path,8183)
        monkeypatch.setattr('urllib.request.urlopen',lambda *a,**k:BytesIO(b'{"success":true,"version":"0.1.4-alpha","capabilities":["import_graph","geography","arrow_style"]}'))
        with pytest.raises(RuntimeError, match='Save your work and close it normally'):
            running_instance(tmp_path,8183)
        monkeypatch.setattr('urllib.request.urlopen', lambda *a, **k: BytesIO(
            b'{"success":true,"version":"0.1.6-alpha","capabilities":["import_graph","geography","arrow_style","paper_svg"]}'))
        with pytest.raises(RuntimeError, match='Save your work and close it normally'):
            running_instance(tmp_path,8183)
        monkeypatch.setattr('urllib.request.urlopen', lambda *a, **k: BytesIO(
            b'{"success":true,"version":"0.1.7-alpha","capabilities":["geography","arrow_style","paper_svg","view_controls"]}'))
        assert running_instance(tmp_path,8183) is True
