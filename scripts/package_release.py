"""Allowlist-only release packaging and local-link/privacy checks."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path,PurePosixPath
import re
from urllib.parse import unquote
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from common import ROOT
from build import verify

def release_files(root=ROOT):
    names=json.loads((root/'release-files.json').read_text(encoding='utf-8'))
    if len(names)!=len(set(names)) or names!=sorted(names):raise RuntimeError('Release manifest must be sorted and unique')
    for name in names:
        path=PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name:raise RuntimeError('Invalid manifest path')
        if any(part in {'work','build','.venv','__pycache__','.pytest_cache','.git','private-backups'} for part in path.parts):raise RuntimeError('Private/runtime directory in manifest')
        if path.name in {'gephi3d-token','config.toml','.local-settings.json'} or path.suffix in {'.pyc','.log','.gephi'}:raise RuntimeError('Private/runtime file in manifest')
        file=root/name
        if not file.is_file() or file.is_symlink():raise RuntimeError(f'Missing or symlinked release file: {name}')
        if file.suffix in {'.py','.md','.json','.txt','.ini','.cmd','.xml','.mf'}:
            text=file.read_text(encoding='utf-8')
            if re.search(r'[A-Za-z]:[\\/]Users[\\/][^\\/\s]+',text,re.I):raise RuntimeError('Personal absolute path in '+name)
            if re.search(r'\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,})\b',text):raise RuntimeError('Credential-like string in '+name)
            if file.suffix=='.md':
                for target in re.findall(r'\]\(([^)]+)\)',text):
                    target=unquote(target.split('#',1)[0].strip('<>'))
                    if not target or re.match(r'^[a-z]+:',target,re.I):continue
                    resolved=(file.parent/target).resolve()
                    if not resolved.is_relative_to(root.resolve()) or not resolved.exists():raise RuntimeError(f'Broken/local external link: {name}: {target}')
                    if resolved.is_file() and resolved.relative_to(root).as_posix() not in names:raise RuntimeError('Linked file is not in the manifest: '+target)
    digest=verify(root/'dist/gephi-3d-explorer-0.1.1.nbm')
    if (root/'dist/SHA256SUMS').read_text(encoding='ascii')!=f'{digest}  gephi-3d-explorer-0.1.1.nbm\n':raise RuntimeError('NBM checksum mismatch')
    return names

def package(output_dir,root=ROOT):
    names=release_files(root);output_dir=Path(output_dir).resolve();output_dir.mkdir(parents=True,exist_ok=True)
    archive_path=output_dir/'gephi-3d-explorer-v0.1.1-alpha.zip'
    with ZipFile(archive_path,'w',ZIP_DEFLATED) as archive:
        for name in names:
            info=ZipInfo('gephi-3d-explorer/'+name,(2026,1,1,0,0,0));info.compress_type=ZIP_DEFLATED;info.external_attr=0o644<<16;archive.writestr(info,(root/name).read_bytes())
    digest=hashlib.sha256(archive_path.read_bytes()).hexdigest()
    (output_dir/'SHA256SUMS').write_text(f'{digest}  {archive_path.name}\n'+(root/'dist/SHA256SUMS').read_text(encoding='ascii'),encoding='ascii')
    return {'file':archive_path.name,'files':len(names),'sha256':digest,'bytes':archive_path.stat().st_size}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output-dir',type=Path,default=ROOT/'build/release');args=parser.parse_args();print(json.dumps(package(args.output_dir),indent=2))
