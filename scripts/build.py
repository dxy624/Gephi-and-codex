"""Build/verify the Windows Gephi 0.11.3 NBM against local API jars."""
from __future__ import annotations
import argparse
import hashlib
from io import BytesIO
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from common import ROOT,NBM,MODULE,resolve_gephi,resolve_java

def write_zip(path,members):
    with ZipFile(path,'w',ZIP_DEFLATED) as archive:
        for name,data in sorted(members.items()):
            entry=ZipInfo(name,(2026,1,1,0,0,0));entry.compress_type=ZIP_DEFLATED;entry.external_attr=0o644<<16;archive.writestr(entry,data)

def verify(path):
    with ZipFile(path) as archive:
        if archive.testzip():raise RuntimeError('Corrupt NBM')
        info=ET.fromstring(archive.read('Info/info.xml'))
        if info.attrib['codenamebase']!='org.gephi.plugins.explorer3d':raise RuntimeError('Unexpected module')
        manifest=info.find('manifest')
        if manifest is None or manifest.attrib['OpenIDE-Module-Specification-Version']!='0.1.0':raise RuntimeError('Unexpected plugin version')
        jar=ZipFile(BytesIO(archive.read(f'netbeans/modules/{MODULE}.jar')))
        for required in ['org/gephi/plugins/explorer3d/Installer.class','META-INF/services/org.gephi.project.spi.WorkspacePersistenceProvider']:
            jar.getinfo(required)
        for name in jar.namelist():
            if name.endswith('.class') and (jar.read(name)[:4]!=b'\xca\xfe\xba\xbe' or int.from_bytes(jar.read(name)[6:8],'big')!=61):raise RuntimeError('Non-Java-17 class')
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--gephi',type=Path);p.add_argument('--jdk',type=Path);p.add_argument('--verify-only',action='store_true');a=p.parse_args()
    if not a.verify_only:
        gephi=resolve_gephi(a.gephi);java=resolve_java(gephi,a.jdk,compiler=True)
        build=ROOT/'build';classes=build/'classes';classes.mkdir(parents=True,exist_ok=True)
        for old in classes.rglob('*.class'):old.unlink()
        jars=sorted(p for p in gephi.rglob('*.jar') if 'locale' not in p.parts)
        sources=sorted((ROOT/'plugin/src').rglob('*.java'))
        args=['--release','17','-encoding','UTF-8','-proc:none','-classpath',os.pathsep.join(map(str,jars)),'-d',str(classes),*map(str,sources)]
        argfile=build/'javac.args';argfile.write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in args),encoding='utf-8')
        # Java argfiles are decoded using the platform charset unless explicitly set.
        subprocess.run([str(java.with_name('javac.exe')),'-J-Dfile.encoding=UTF-8','@'+str(argfile)],check=True)
        manifest=(ROOT/'plugin/manifest.mf').read_bytes();members={'META-INF/MANIFEST.MF':manifest}
        for base in [classes,ROOT/'plugin/resources']:
            for path in sorted(base.rglob('*')):
                if path.is_file():members[path.relative_to(base).as_posix()]=path.read_bytes()
        jar=build/(MODULE+'.jar');write_zip(jar,members)
        config=f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE module PUBLIC "-//NetBeans//DTD Module Status 1.0//EN" "http://www.netbeans.org/dtds/module-status-1_0.dtd">
<module name="org.gephi.plugins.explorer3d">
    <param name="autoload">false</param>
    <param name="eager">false</param>
    <param name="enabled">true</param>
    <param name="jar">modules/{MODULE}.jar</param>
    <param name="reloadable">false</param>
</module>'''
        info=ET.Element('module',{'codenamebase':'org.gephi.plugins.explorer3d','distribution':NBM.name,'downloadsize':str(jar.stat().st_size),'license':'explorer-license','needsrestart':'true'})
        attrs={}
        for line in manifest.decode().replace('\r','').replace('\n ','').splitlines():
            if ': ' in line:
                key,value=line.split(': ',1)
                if key.startswith('OpenIDE-Module'):attrs[key]=value
        attrs.update({'OpenIDE-Module-Name':'Gephi 3D Explorer','OpenIDE-Module-Display-Category':'Visualization','OpenIDE-Module-Short-Description':'Interactive 3D network exploration and Codex bridge (Alpha)'})
        ET.SubElement(info,'manifest',attrs);ET.SubElement(info,'license',{'name':'explorer-license'}).text=(ROOT/'LICENSE').read_text(encoding='utf-8')
        NBM.parent.mkdir(parents=True,exist_ok=True)
        write_zip(NBM,{'Info/info.xml':ET.tostring(info,encoding='utf-8',xml_declaration=True),f'netbeans/modules/{MODULE}.jar':jar.read_bytes(),f'netbeans/config/Modules/{MODULE}.xml':config.encode()})
    digest=verify(NBM)
    if not a.verify_only:(NBM.parent/'SHA256SUMS').write_text(f'{digest}  {NBM.name}\n',encoding='ascii')
    print(f'Java 17 / Gephi 0.11.3 NBM verified: {digest}')
if __name__=='__main__':main()
