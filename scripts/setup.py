"""Unified install entrypoint. Dry-run uses only the Python standard library."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import venv
from common import (
    DESKTOP_PORT, ISOLATED_PORT, ROOT, desktop_profile, install_profile,
    resolve_gephi, resolve_java, runtime_dir,
)
from configure_codex import configure

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gephi', type=Path)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--state-dir', type=Path, help='Runtime directory for the independent launcher')
    parser.add_argument('--codex-home', type=Path, default=Path(os.environ.get('CODEX_HOME', Path.home()/'.codex')))
    parser.add_argument('--mode', choices=('desktop', 'isolated'), help='Desktop Gephi is the default')
    parser.add_argument('--port', type=int, choices=(DESKTOP_PORT, ISOLATED_PORT), help='Independent mode override')
    parser.add_argument('--install-skill', action='store_true')
    parser.add_argument('--configure-codex', action='store_true')
    parser.add_argument('--replace-gephi-connection', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if sys.platform != 'win32' or sys.version_info[:2] != (3, 12):
        raise SystemExit('This Alpha installer requires Windows and Python 3.12.')

    mode = args.mode or ('isolated' if args.port is not None else 'desktop')
    if args.port is not None and mode == 'desktop':
        parser.error('--port is only available for isolated mode')
    if args.replace_gephi_connection and not args.configure_codex:
        parser.error('--replace-gephi-connection requires --configure-codex')
    isolated_port = args.port or ISOLATED_PORT
    connection_port = DESKTOP_PORT if mode == 'desktop' else isolated_port
    gephi = resolve_gephi(args.gephi)
    java = resolve_java(gephi, args.jdk)
    state = runtime_dir(args.state_dir)
    profile = desktop_profile() if mode == 'desktop' else state/'profile'
    settings = {'gephi': str(gephi), 'java': str(java), 'state_dir': str(state), 'port': isolated_port}
    venv_path = ROOT/'.venv'
    python = venv_path/'Scripts/python.exe'
    codex_preview = None
    if args.install_skill or args.configure_codex:
        codex_preview = configure(args.codex_home, python, state, connection_port,
                                  args.install_skill, args.configure_codex, dry_run=True,
                                  mode=mode, replace_connection=args.replace_gephi_connection)
    desktop_change = mode == 'desktop' and install_profile(profile, desktop=True, dry_run=True)
    print(json.dumps(dict(settings, mode=mode, profile=str(profile), connection_port=connection_port,
                          desktop_plugin_change=desktop_change, dry_run=args.dry_run,
                          codex=codex_preview), ensure_ascii=False, indent=2))
    if args.dry_run:
        return

    if not python.is_file():
        venv.EnvBuilder(with_pip=True).create(venv_path)
    version = subprocess.check_output(
        [str(python), '-c', 'import sys;print("%d.%d"%sys.version_info[:2])'], text=True,
    ).strip()
    if version != '3.12':
        raise RuntimeError('Existing .venv is not Python 3.12. Rename it and rerun setup.')
    subprocess.run([str(python), '-m', 'pip', 'install', '--disable-pip-version-check',
                    '-r', str(ROOT/'requirements.txt')], check=True)
    if mode == 'desktop':
        install_profile(profile, desktop=True)
    if codex_preview is not None:
        command = [str(python), '-X', 'utf8', str(ROOT/'scripts/configure_codex.py'),
                   '--codex-home', str(args.codex_home), '--state-dir', str(state), '--mode', mode]
        if mode == 'isolated':
            command.extend(['--port', str(connection_port)])
        if args.install_skill:
            command.append('--install-skill')
        if args.configure_codex:
            command.append('--configure-codex')
        if args.replace_gephi_connection:
            command.append('--replace-gephi-connection')
        subprocess.run(command, check=True)
    (ROOT/'.local-settings.json').write_text(json.dumps(settings, indent=2), encoding='utf-8')
    if mode == 'desktop':
        print('Setup complete. Start Gephi from the desktop or Start menu. Reload Codex to load the MCP configuration.')
    else:
        print('Setup complete. Run Start-Gephi-3D.cmd. Reload Codex to load the MCP configuration.')

if __name__=='__main__':
    try:main()
    except (OSError,RuntimeError,ValueError,subprocess.CalledProcessError) as exc:raise SystemExit(str(exc))
