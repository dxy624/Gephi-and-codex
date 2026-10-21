"""Start/reuse this installation's isolated Gephi; never closes another instance."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys
from common import ROOT,resolve_gephi,resolve_java,runtime_dir,running_instance,install_module,launch_command,endpoint

def main():
    p=argparse.ArgumentParser();p.add_argument('--gephi',type=Path);p.add_argument('--jdk',type=Path);p.add_argument('--state-dir',type=Path);p.add_argument('--port',type=int,choices=[8183,8184]);p.add_argument('--project',type=Path);p.add_argument('--wait',action='store_true');p.add_argument('--dry-run',action='store_true');a=p.parse_args()
    settings=json.loads((ROOT/'.local-settings.json').read_text(encoding='utf-8')) if (ROOT/'.local-settings.json').is_file() else {}
    gephi=resolve_gephi(a.gephi or settings.get('gephi'));java=resolve_java(gephi,a.jdk) if a.jdk or not settings.get('java') else Path(settings['java'])
    if not java.is_file():raise RuntimeError('Configured Java is missing; rerun setup')
    state=runtime_dir(a.state_dir or settings.get('state_dir'));port=a.port or settings.get('port',8184)
    command=launch_command(gephi,java,state,port,a.project)
    if a.dry_run:print(json.dumps({'command':command,'state_dir':str(state)},ensure_ascii=False,indent=2));return
    if running_instance(state,port):
        sys.path.insert(0,str(ROOT/'bridge'));from gephi3d_client import Gephi3D
        Gephi3D(endpoint(port),state/'profile/config/gephi3d-token').command('open')
        print('Opened the existing 3D Explorer.');return
    install_module(state);state.mkdir(parents=True,exist_ok=True)
    with (state/'console.log').open('w',encoding='utf-8') as log:
        process=subprocess.Popen(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    (state/'process-id.txt').write_text(str(process.pid),encoding='ascii')
    if a.wait:
        try:raise SystemExit(process.wait())
        except KeyboardInterrupt:process.terminate();raise
    print('Gephi started. Runtime/log folder: '+str(state))
if __name__=='__main__':
    try:main()
    except Exception as exc:
        # pythonw launchers have no console: surface errors in a normal app dialog.
        if sys.stderr is None:
            import tkinter.messagebox
            tkinter.messagebox.showerror('Gephi 3D Explorer',str(exc))
        else:raise SystemExit(str(exc))
