"""Unified install entrypoint. Dry-run uses only the Python standard library."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import venv
from common import ROOT,resolve_gephi,resolve_java,runtime_dir

def main():
    p=argparse.ArgumentParser();p.add_argument('--gephi',type=Path);p.add_argument('--jdk',type=Path);p.add_argument('--state-dir',type=Path);p.add_argument('--codex-home',type=Path,default=Path(os.environ.get('CODEX_HOME',str(Path.home()/'.codex'))));p.add_argument('--port',type=int,choices=[8183,8184],default=8184);p.add_argument('--install-skill',action='store_true');p.add_argument('--configure-codex',action='store_true');p.add_argument('--dry-run',action='store_true')
    a=p.parse_args()
    if sys.platform!='win32' or sys.version_info[:2]!=(3,12):raise SystemExit('This Alpha installer requires Windows and Python 3.12.')
    gephi=resolve_gephi(a.gephi);java=resolve_java(gephi,a.jdk);state=runtime_dir(a.state_dir)
    settings={'gephi':str(gephi),'java':str(java),'state_dir':str(state),'port':a.port}
    print(json.dumps(dict(settings,dry_run=a.dry_run,install_skill=a.install_skill,configure_codex=a.configure_codex,codex_home=str(a.codex_home)),ensure_ascii=False,indent=2))
    if a.dry_run:
        if (a.install_skill or a.configure_codex) and (ROOT/'.venv/Scripts/python.exe').is_file():
            args=[str(ROOT/'.venv/Scripts/python.exe'),'-X','utf8',str(ROOT/'scripts/configure_codex.py'),'--dry-run','--codex-home',str(a.codex_home),'--state-dir',str(state),'--port',str(a.port)]
            if a.install_skill:args.append('--install-skill')
            if a.configure_codex:args.append('--configure-codex')
            subprocess.run(args,check=True)
        return
    venv_path=ROOT/'.venv';python=venv_path/'Scripts/python.exe'
    if not python.is_file():venv.EnvBuilder(with_pip=True).create(venv_path)
    version=subprocess.check_output([str(python),'-c','import sys;print("%d.%d"%sys.version_info[:2])'],text=True).strip()
    if version!='3.12':raise RuntimeError('Existing .venv is not Python 3.12. Rename it and rerun setup.')
    subprocess.run([str(python),'-m','pip','install','--disable-pip-version-check','-r',str(ROOT/'requirements.txt')],check=True)
    # Preflight/perform requested configuration before storing launch settings.
    if a.install_skill or a.configure_codex:
        args=[str(python),'-X','utf8',str(ROOT/'scripts/configure_codex.py'),'--codex-home',str(a.codex_home),'--state-dir',str(state),'--port',str(a.port)]
        if a.install_skill:args.append('--install-skill')
        if a.configure_codex:args.append('--configure-codex')
        subprocess.run(args,check=True)
    (ROOT/'.local-settings.json').write_text(json.dumps(settings,indent=2),encoding='utf-8')
    print('Setup complete. Run Start-Gephi-3D.cmd. Reload Codex to load the MCP configuration.')
if __name__=='__main__':
    try:main()
    except (RuntimeError,subprocess.CalledProcessError) as exc:raise SystemExit(str(exc))
