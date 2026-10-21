from pathlib import Path
import sys
from zipfile import ZipFile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from package_release import ROOT,release_files,package

def test_manifest_links_privacy_and_zip(tmp_path):
    names=release_files()
    result=package(tmp_path)
    with ZipFile(tmp_path/result['file']) as archive:
        assert archive.testzip() is None
        assert sorted(archive.namelist())==['gephi-3d-explorer/'+n for n in names]
        assert not any('/work/' in n or '/.venv/' in n or n.endswith('/gephi3d-token') for n in archive.namelist())
