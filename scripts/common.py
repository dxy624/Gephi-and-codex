"""Shared discovery and isolated runtime paths; importing this module writes nothing."""
from __future__ import annotations
import os
from pathlib import Path
import re
import socket
import subprocess
import urllib.request
import json
import errno
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
NBM = ROOT / 'dist/gephi-3d-explorer-0.1.0.nbm'
MODULE = 'org-gephi-plugins-explorer3d'

def runtime_dir(value=None):
    return Path(value or os.environ.get('GEPHI3D_STATE_DIR') or Path(os.environ.get('LOCALAPPDATA', Path.home()/'AppData/Local'))/'Gephi3DExplorer').expanduser().resolve()

def resolve_gephi(value=None):
    explicit = value or os.environ.get('GEPHI_HOME')
    candidates = [Path(explicit).expanduser()] if explicit else [Path(os.environ.get(key, fallback))/'Gephi-0.11.3' for key,fallback in [('ProgramFiles',r'C:\Program Files'),('ProgramFiles(x86)',r'C:\Program Files (x86)')]]
    for path in candidates:
        api = path/'gephi/modules/org-gephi-graph-api.jar'
        if not api.is_file(): continue
        with ZipFile(api) as jar:
            manifest=jar.read('META-INF/MANIFEST.MF').decode().replace('\r','')
        if re.search(r'^OpenIDE-Module-Specification-Version: 0\.11\.3$',manifest,re.M): return path.resolve()
    raise RuntimeError('Gephi 0.11.3 not found. Use --gephi "installation folder" or set GEPHI_HOME.')

def resolve_java(gephi, jdk=None, compiler=False):
    explicit=jdk or (os.environ.get('JAVA_HOME') if compiler else None)
    if explicit:
        candidates=[Path(explicit).expanduser()/'bin/java.exe']
    else:
        candidates=sorted(gephi.glob('jre*/**/bin/java.exe'))
        if os.environ.get('JAVA_HOME'): candidates.append(Path(os.environ['JAVA_HOME'])/'bin/java.exe')
    for java in candidates:
        if not java.is_file() or (compiler and not java.with_name('javac.exe').is_file()): continue
        result=subprocess.run([str(java),'-version'],capture_output=True,encoding='utf-8',errors='replace',timeout=15)
        if result.returncode==0 and re.search(r'version "17[.\"]',result.stderr+result.stdout): return java.resolve()
    raise RuntimeError('Java 17 '+('JDK required: use --jdk or JAVA_HOME.' if compiler else 'runtime not found in Gephi; use --jdk or JAVA_HOME.'))

def endpoint(port):
    if port not in (8183,8184): raise ValueError('Supported loopback ports: 8183, 8184')
    return f'http://127.0.0.1:{port}'

def running_instance(state,port):
    """Return True only for this state directory's authenticated plugin."""
    try:
        # On Windows a connect to a closed port may time out instead of refusing.
        # An exclusive bind can prove availability without relying on a timeout.
        with socket.socket() as probe:
            if hasattr(socket,'SO_EXCLUSIVEADDRUSE'):probe.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
            probe.bind(('127.0.0.1',port))
        return False
    except OSError as exc:
        if exc.errno!=errno.EADDRINUSE and getattr(exc,'winerror',None)!=10048:
            raise RuntimeError(f'Cannot inspect local port {port}: {exc}') from exc
    token=state/'profile/config/gephi3d-token'
    if token.is_file():
        request=urllib.request.Request(endpoint(port)+'/health',headers={'Authorization':'Bearer '+token.read_text(encoding='utf-8').strip()})
        try:
            with urllib.request.urlopen(request,timeout=3) as response:
                health=json.load(response)
                if health.get('success') and health.get('version')=='0.1.0-alpha': return True
        except (OSError,ValueError): pass
    raise RuntimeError(f'Port {port} is occupied by another instance/service. Close it yourself or explicitly choose the other supported port; nothing was stopped.')

def install_module(state,nbm=NBM):
    """Install only two explicitly named module files into the isolated profile."""
    destinations={f'netbeans/modules/{MODULE}.jar':state/f'profile/modules/{MODULE}.jar',f'netbeans/config/Modules/{MODULE}.xml':state/f'profile/config/Modules/{MODULE}.xml'}
    with ZipFile(nbm) as archive:
        if archive.testzip(): raise RuntimeError('Corrupt NBM')
        for member,destination in destinations.items():
            data=archive.read(member)
            if destination.exists() and destination.read_bytes()==data: continue
            destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_bytes(data)

def launch_command(gephi,java,state,port,project=None):
    args=[str(java),'-Xmx3g','--add-opens=java.base/java.net=ALL-UNNAMED','--add-opens=java.desktop/javax.swing=ALL-UNNAMED','--add-opens=java.base/java.nio=ALL-UNNAMED','--add-exports=java.desktop/sun.awt=ALL-UNNAMED','-Dsun.java2d.noddraw=true','-Dsun.awt.noerasebackground=true',f'-Dgephi3d.port={port}',f'-Dnetbeans.home={gephi}/platform',f'-Dnetbeans.dirs={gephi}/gephi',f'-Dnetbeans.user={state}/profile','-cp',os.pathsep.join(str(p) for p in sorted((gephi/'platform/lib').glob('*.jar'))),'org.netbeans.Main','--branding','gephi','--nosplash']
    if project: args.insert(1,f'-Dgephi3d.startProject={Path(project).resolve()}')
    return args
