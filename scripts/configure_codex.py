"""Preview or explicitly install the skill/MCP, preserving unrelated Codex settings."""
from __future__ import annotations
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import shutil
import tomllib
from common import ROOT, DESKTOP_PORT, ISOLATED_PORT, desktop_profile, runtime_dir, endpoint

def configure(codex_home,python,state,port,install_skill=False,configure_mcp=False,dry_run=False,
              *, mode='isolated', replace_connection=False):
    home=Path(codex_home).expanduser().resolve();config=home/'config.toml';skill=home/'skills/gephi-3d'
    before=config.read_text(encoding='utf-8') if config.exists() else ''
    original=tomllib.loads(before)
    source=ROOT/'skills/gephi-3d/SKILL.md'
    profile=desktop_profile() if mode=='desktop' else Path(state)/'profile'
    entry={'command':str(Path(python).resolve()),'args':['-X','utf8',str(ROOT/'bridge/server.py')],'startup_timeout_sec':60,'tool_timeout_sec':120,'env':{'GEPHI3D_URL':endpoint(port),'GEPHI3D_TOKEN_FILE':str(profile/'config/gephi3d-token')}}
    skill_changed=install_skill and not skill.exists()
    if install_skill and skill.exists():
        if not (skill/'SKILL.md').is_file() or (skill/'SKILL.md').read_bytes()!=source.read_bytes() or any(p.is_file() and p.name!='SKILL.md' for p in skill.rglob('*')):
            raise RuntimeError('Existing gephi-3d skill differs. Review or rename it; it was not replaced.')
    servers=original.get('mcp_servers',{})
    if not isinstance(servers,dict):
        raise RuntimeError('mcp_servers must be a TOML table')
    config_changed=configure_mcp and servers.get('gephi-3d')!=entry
    if config_changed and 'gephi-3d' in servers and not replace_connection:
        raise RuntimeError('Existing gephi-3d MCP configuration differs. Review it and use '
                           '--replace-gephi-connection to replace that entry with a backup.')
    result={'dry_run':dry_run,'install_skill':install_skill,'configure_mcp':configure_mcp,'skill_change':skill_changed,'config_change':config_changed,'codex_home':str(home),'mcp':entry if configure_mcp else None}
    if dry_run: return result
    backup=None
    if config_changed:
        import tomlkit
        document=tomlkit.parse(before)
        document.setdefault('mcp_servers',tomlkit.table())['gephi-3d']=entry
        after=tomlkit.dumps(document)
        original.setdefault('mcp_servers',{})['gephi-3d']=entry
        if original!=tomllib.loads(after):
            raise RuntimeError('Unrelated configuration changed; operation aborted')
        home.mkdir(parents=True,exist_ok=True)
        # Detect edits made since preview/preflight rather than replacing them.
        if (config.read_text(encoding='utf-8') if config.exists() else '')!=before: raise RuntimeError('Codex config changed concurrently; retry after reviewing it')
        if config.exists():
            backup=home/'backups'/('gephi3d-config-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'.toml')
            backup.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(config,backup)
        temporary=config.with_name('config.gephi3d.tmp')
        with temporary.open('x',encoding='utf-8',newline='') as stream:stream.write(after)
        try:
            if (config.read_text(encoding='utf-8') if config.exists() else '')!=before:
                raise RuntimeError('Codex config changed concurrently; retry after reviewing it')
            os.replace(temporary,config)
        finally:
            temporary.unlink(missing_ok=True)
    if skill_changed:
        skill.mkdir(parents=True);shutil.copyfile(source,skill/'SKILL.md')
    result['backup']=str(backup) if backup else None
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--codex-home',type=Path,default=Path(os.environ.get('CODEX_HOME',str(Path.home()/'.codex'))));p.add_argument('--python',type=Path,default=ROOT/'.venv/Scripts/python.exe');p.add_argument('--state-dir',type=Path);p.add_argument('--port',type=int,choices=[DESKTOP_PORT,ISOLATED_PORT]);p.add_argument('--mode',choices=['desktop','isolated']);p.add_argument('--install-skill',action='store_true');p.add_argument('--configure-codex',action='store_true');p.add_argument('--replace-gephi-connection',action='store_true');p.add_argument('--dry-run',action='store_true')
    a=p.parse_args()
    mode=a.mode or ('isolated' if a.port is not None else 'desktop')
    if a.port is not None and mode=='desktop':p.error('--port is only available for isolated mode')
    if a.replace_gephi_connection and not a.configure_codex:p.error('--replace-gephi-connection requires --configure-codex')
    port=DESKTOP_PORT if mode=='desktop' else (a.port or ISOLATED_PORT)
    result=configure(a.codex_home,a.python,runtime_dir(a.state_dir),port,a.install_skill,a.configure_codex,a.dry_run,
                     mode=mode,replace_connection=a.replace_gephi_connection)
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':
    try:main()
    except (RuntimeError,ValueError) as exc:raise SystemExit(str(exc))
