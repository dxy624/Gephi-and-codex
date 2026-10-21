"""Preview or explicitly install the skill/MCP, preserving unrelated Codex settings."""
from __future__ import annotations
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import shutil
import sys
import tomlkit
from common import ROOT,runtime_dir,endpoint

def configure(codex_home,python,state,port,install_skill=False,configure_mcp=False,dry_run=False):
    home=Path(codex_home).expanduser().resolve();config=home/'config.toml';skill=home/'skills/gephi-3d'
    before=config.read_text(encoding='utf-8') if config.exists() else ''
    document=tomlkit.parse(before)
    source=ROOT/'skills/gephi-3d/SKILL.md'
    entry={'command':str(Path(python).resolve()),'args':['-X','utf8',str(ROOT/'bridge/server.py')],'startup_timeout_sec':60,'tool_timeout_sec':120,'env':{'GEPHI3D_URL':endpoint(port),'GEPHI3D_TOKEN_FILE':str(Path(state)/'profile/config/gephi3d-token')}}
    skill_changed=install_skill and not skill.exists()
    if install_skill and skill.exists():
        if not (skill/'SKILL.md').is_file() or (skill/'SKILL.md').read_bytes()!=source.read_bytes() or any(p.is_file() and p.name!='SKILL.md' for p in skill.rglob('*')):
            raise RuntimeError('Existing gephi-3d skill differs. Review or rename it; it was not replaced.')
    if 'mcp_servers' in document and not isinstance(document['mcp_servers'],dict):
        # tomlkit Table behaves as dict; other values are invalid for this operation.
        raise RuntimeError('mcp_servers must be a TOML table')
    servers=document.get('mcp_servers',{})
    if configure_mcp and 'gephi-3d' in servers and servers['gephi-3d'].unwrap()!=entry:
        raise RuntimeError('Existing gephi-3d MCP configuration differs. Review it; it was not replaced.')
    config_changed=configure_mcp and 'gephi-3d' not in servers
    if config_changed:
        document.setdefault('mcp_servers',tomlkit.table())['gephi-3d']=entry
    after=tomlkit.dumps(document)
    expected=tomlkit.parse(before).unwrap();actual=tomlkit.parse(after).unwrap()
    expected.setdefault('mcp_servers',{}).pop('gephi-3d',None);actual.setdefault('mcp_servers',{}).pop('gephi-3d',None)
    if expected!=actual: raise RuntimeError('Unrelated configuration changed; operation aborted')
    result={'dry_run':dry_run,'install_skill':install_skill,'configure_mcp':configure_mcp,'skill_change':skill_changed,'config_change':config_changed,'codex_home':str(home),'mcp':entry if configure_mcp else None}
    if dry_run: return result
    backup=None
    if config_changed and config.exists():
        backup=home/'backups'/('gephi3d-config-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'.toml')
        backup.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(config,backup)
    if skill_changed:
        skill.mkdir(parents=True);shutil.copyfile(source,skill/'SKILL.md')
    if config_changed:
        home.mkdir(parents=True,exist_ok=True)
        # Detect edits made since preview/preflight rather than replacing them.
        if (config.read_text(encoding='utf-8') if config.exists() else '')!=before: raise RuntimeError('Codex config changed concurrently; retry after reviewing it')
        temporary=config.with_name('config.gephi3d.tmp')
        with temporary.open('x',encoding='utf-8',newline='') as stream:stream.write(after)
        os.replace(temporary,config)
    result['backup']=str(backup) if backup else None
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--codex-home',type=Path,default=Path(os.environ.get('CODEX_HOME',str(Path.home()/'.codex'))));p.add_argument('--python',type=Path,default=ROOT/'.venv/Scripts/python.exe');p.add_argument('--state-dir',type=Path);p.add_argument('--port',type=int,choices=[8183,8184],default=8184);p.add_argument('--install-skill',action='store_true');p.add_argument('--configure-codex',action='store_true');p.add_argument('--dry-run',action='store_true')
    a=p.parse_args();print(json.dumps(configure(a.codex_home,a.python,runtime_dir(a.state_dir),a.port,a.install_skill,a.configure_codex,a.dry_run),ensure_ascii=False,indent=2))
if __name__=='__main__':
    try:main()
    except (RuntimeError,ValueError) as exc:raise SystemExit(str(exc))
