"""Shared discovery and isolated runtime paths; importing this module writes nothing."""
from __future__ import annotations
import os
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
import re
import socket
import subprocess
import urllib.request
import json
import errno
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
MODULE_VERSION = '0.1.8'
RELEASE_TAG = 'v0.1.8-alpha'
NBM = ROOT / 'dist' / f'gephi-3d-explorer-{MODULE_VERSION}.nbm'
MODULE = 'org-gephi-plugins-explorer3d'
# JAR checksums from the previous public installers.
PREVIOUS_DESKTOP_MODULES = {
    'c3f5b3ded2f6b79ca103a3596d8cbbb5fc3b6280517f507f71b3747f181ace29',  # 0.1.7
    '7bf361320c9d1c4914baadfb2d45026835bc023dcda7644299058b48eb28ada6',  # 0.1.6
    'a43a2510acfba5d03d77a0696c9cd569668cf049fb6e7854a2dbd8f6492a86e8',  # 0.1.3
    'bc43f8d309eaf769da669f7505d5ab8bb09747dfb3e5dbdbde23da36a10a93a6',  # 0.1.4
}
DESKTOP_PORT = 8183
ISOLATED_PORT = 8184

def desktop_profile():
    roaming = Path(os.environ.get('APPDATA', Path.home()/'AppData/Roaming'))
    return (roaming/'gephi/0.11').resolve()

def runtime_dir(value=None):
    return Path(value or os.environ.get('GEPHI3D_STATE_DIR') or Path(os.environ.get('LOCALAPPDATA', Path.home()/'AppData/Local'))/'Gephi3DExplorer').expanduser().resolve()

def resolve_gephi(value=None):
    explicit = value or os.environ.get('GEPHI_HOME')
    roots = (os.environ.get('ProgramFiles', r'C:\Program Files'),
             os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)'))
    candidates = ([Path(explicit).expanduser()] if explicit else
                  [Path(root)/name for name in ('Gephi-0.11.4', 'Gephi-0.11.3', 'Gephi') for root in roots])
    for path in candidates:
        api = path/'gephi/modules/org-gephi-graph-api.jar'
        if not api.is_file(): continue
        with ZipFile(api) as jar:
            manifest=jar.read('META-INF/MANIFEST.MF').decode().replace('\r','')
        if re.search(r'^OpenIDE-Module-Specification-Version: 0\.11\.[34]$',manifest,re.M): return path.resolve()
    raise RuntimeError('Gephi 0.11.3 or 0.11.4 not found. Use --gephi "installation folder" or set GEPHI_HOME.')

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
                if health.get('success') and re.fullmatch(r'\d+\.\d+\.\d+-alpha', health.get('version', '')):
                    version = tuple(int(part) for part in health['version'].removesuffix('-alpha').split('.'))
                    minimum = tuple(int(part) for part in MODULE_VERSION.split('.'))
                    if version < minimum or not {'geography', 'arrow_style', 'paper_svg', 'view_controls',
                                                 'supply_scope', 'figure_presets', 'edge_years'}.issubset(health.get('capabilities', [])):
                        raise RuntimeError('This is an older Gephi 3D instance. Save your work and close it normally, then launch the updated version; no process was stopped.')
                    return True
        except (OSError,ValueError): pass
    raise RuntimeError(f'Port {port} is occupied by another instance/service. Close it yourself or explicitly choose the other supported port; nothing was stopped.')

def install_module(state,nbm=NBM):
    install_profile(state/'profile', nbm)

def install_profile(profile, nbm=NBM, *, desktop=False, dry_run=False):
    """Install the named NBM files and back up a recognized desktop upgrade."""
    members = (f'modules/{MODULE}.jar', f'config/Modules/{MODULE}.xml')
    module_jar = profile/members[0]
    with ZipFile(nbm) as archive:
        if archive.testzip(): raise RuntimeError('Corrupt NBM')
        contents = {profile/member: archive.read('netbeans/'+member) for member in members}
    pending = {}
    previous = {}
    for destination, data in contents.items():
        if destination.exists():
            existing = destination.read_bytes()
            if existing == data:
                continue
            if desktop:
                if destination != module_jar and re.sub(rb'>\s+<', b'><', existing.strip()) == re.sub(rb'>\s+<', b'><', data.strip()):
                    continue
                known_jar = destination == module_jar and sha256(existing).hexdigest() in PREVIOUS_DESKTOP_MODULES
                if not known_jar:
                    raise RuntimeError(f'An existing desktop plugin file differs: {destination}. '
                                       'Review the 3D Explorer installation in Gephi before continuing.')
                previous[destination] = existing
        pending[destination] = data

    if not pending:
        return False
    if desktop and (profile/'lock').exists():
        raise RuntimeError('Save and close desktop Gephi normally before installing the plugin, then rerun setup.')
    if dry_run:
        return True

    if not desktop:
        for destination, data in pending.items():
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        return True

    profile.mkdir(parents=True, exist_ok=True)
    written = []
    with TemporaryDirectory(prefix='gephi3d-install-', dir=profile) as staging:
        for destination, data in pending.items():
            (Path(staging)/destination.name).write_bytes(data)
            destination.parent.mkdir(parents=True, exist_ok=True)
        if (profile/'lock').exists():
            raise RuntimeError('Desktop Gephi started during setup. Close it normally and rerun setup.')
        backup = None
        if previous:
            stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
            backup = profile/'gephi3d-backups'/f'{stamp}.jar'
            backup.parent.mkdir(parents=True, exist_ok=True)
            with backup.open('xb') as stream:
                stream.write(previous[module_jar])
        try:
            for destination in pending:
                if (profile/'lock').exists():
                    raise RuntimeError('Desktop Gephi started during setup. Close it normally and rerun setup.')
                staged = Path(staging)/destination.name
                if destination in previous:
                    if destination.read_bytes() != previous[destination]:
                        raise RuntimeError(f'Plugin changed during setup: {destination}')
                    staged.replace(destination)
                else:
                    staged.rename(destination)
                written.append(destination)
        except (OSError, RuntimeError):
            for destination in written:
                if destination.is_file() and destination.read_bytes() == pending[destination]:
                    if destination in previous:
                        restore = Path(staging)/destination.name
                        restore.write_bytes(previous[destination])
                        restore.replace(destination)
                    else:
                        destination.unlink()
            raise
        if backup is not None:
            print(f'Previous desktop plugin backed up to {backup}')
    return True

def launch_command(gephi,java,state,port,project=None):
    args=[str(java),'-Xmx3g','--add-opens=java.base/java.net=ALL-UNNAMED','--add-opens=java.desktop/javax.swing=ALL-UNNAMED','--add-opens=java.base/java.nio=ALL-UNNAMED','--add-exports=java.desktop/sun.awt=ALL-UNNAMED','-Dsun.java2d.noddraw=true','-Dsun.awt.noerasebackground=true',f'-Dgephi3d.port={port}',f'-Dnetbeans.home={gephi}/platform',f'-Dnetbeans.dirs={gephi}/gephi',f'-Dnetbeans.user={state}/profile','-cp',os.pathsep.join(str(p) for p in sorted((gephi/'platform/lib').glob('*.jar'))),'org.netbeans.Main','--branding','gephi','--nosplash']
    if project: args.insert(1,f'-Dgephi3d.startProject={Path(project).resolve()}')
    return args
