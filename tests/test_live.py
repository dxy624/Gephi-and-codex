"""Opt-in acceptance: never attaches to the user's live profile or occupied port."""
import asyncio
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid
from zipfile import ZipFile
import pytest
import networkx as nx

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT/'bridge'),str(ROOT/'scripts')]
from gephi3d_client import Gephi3D
from common import resolve_gephi,resolve_java,install_module,launch_command,endpoint

pytestmark=pytest.mark.live

def wait_for(client,predicate,timeout=45):
    deadline=time.monotonic()+timeout;last=None
    while time.monotonic()<deadline:
        try:
            last=client.state()
            if predicate(last):return last
        except (RuntimeError,OSError):pass
        time.sleep(.2)
    raise AssertionError(f'Gephi did not reach expected state: {last}')

def test_isolated_end_to_end(tmp_path,request):
    gephi=resolve_gephi(request.config.getoption('--gephi'));java=resolve_java(gephi)
    port=8183
    # Binding failure is a hard precondition failure; never reuse a user process.
    with socket.socket() as probe:probe.bind(('127.0.0.1',port))
    state=tmp_path/'Gephi 测试 state';install_module(state)
    client=Gephi3D(endpoint(port),state/'profile/config/gephi3d-token')
    process=None
    def start(project=None):
        log=(tmp_path/f'java-{time.time_ns()}.log').open('w',encoding='utf-8')
        try:return subprocess.Popen(launch_command(gephi,java,state,port,project),cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        finally:log.close()
    def stop():
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
    try:
        process=start();wait_for(client,lambda s:True)
        w=client.command('generate',template='community',nodes=60,communities=3,average_degree=8,seed=42)['scene']['workspace_id']
        client.command('layout',w,iterations=100);wait_for(client,lambda s:s['layout']['state']=='completed')
        assert any(n['id']=='n12' for n in client.command('search',w,query='12')['matches'])
        client.command('layer',w,attribute='community',spacing=220)
        client.command('select',w,ids=['n12']);client.command('neighbors',w,id='n12',hops=2);client.command('focus',w,ids=['n12'])
        client.command('bookmark',w,name='中文视角')
        before=client.command('save_scene',w)['scene']
        assert len(before['nodes'])==60 and len(before['edges'])==240 and len({n['z'] for n in before['nodes']})==3
        graph=nx.Graph();graph.add_nodes_from(n['id'] for n in before['nodes']);graph.add_edges_from((before['nodes'][e['source']]['id'],before['nodes'][e['target']]['id']) for e in before['edges'])
        assert set(before['highlight'])==set(nx.single_source_shortest_path_length(graph,'n12',cutoff=2))
        project=tmp_path/'三维工程.gephi';assert client.command('save_project',w,path=str(project))['success']
        assert project.is_file()
        stop();process=start(project)
        wait_for(client,lambda s:s.get('has_workspace',False))
        deadline=time.monotonic()+20
        while not (state/'profile/restored-scene.json').is_file() and time.monotonic()<deadline:time.sleep(.1)
        restored=json.loads((state/'profile/restored-scene.json').read_text(encoding='utf-8'))
        for key in ['nodes','edges','yaw','pitch','distance','bookmarks','selection','highlight','layerAttribute']:assert before[key]==restored[key],key
        imported_project,imported_before=asyncio.run(check_mcp(client,tmp_path))
        stop();(state/'profile/restored-scene.json').unlink();process=start(imported_project)
        wait_for(client,lambda s:s.get('has_workspace',False))
        deadline=time.monotonic()+20
        while not (state/'profile/restored-scene.json').is_file() and time.monotonic()<deadline:time.sleep(.1)
        imported_after=json.loads((state/'profile/restored-scene.json').read_text(encoding='utf-8'))
        for key in ['nodes','edges','yaw','pitch','distance','bookmarks','selection','highlight','layerAttribute']:
            assert imported_before[key]==imported_after[key],key
    finally:stop()

async def check_mcp(client,tmp_path):
    from mcp import ClientSession,StdioServerParameters
    from mcp.client.stdio import stdio_client
    params=StdioServerParameters(command=sys.executable,args=['-X','utf8',str(ROOT/'bridge/server.py')],env=dict(os.environ,GEPHI3D_URL=client.url,GEPHI3D_TOKEN_FILE=str(client.token_file)))
    async with stdio_client(params) as (reader,writer):
        async with ClientSession(reader,writer) as session:
            await session.initialize();listed=await session.list_tools();assert len(listed.tools)==25
            assert 'gephi3d_import_graph' in {tool.name for tool in listed.tools}
            result=await session.call_tool('gephi3d_status',{});assert not result.is_error
            state=json.loads(result.content[0].text);w=state['scene']['workspace_id']
            result=await session.call_tool('gephi3d_search',{'workspace_id':w,'query':'12'});assert not result.is_error and json.loads(result.content[0].text)['matches']
            output=tmp_path/'高清 视图.png'
            result=await session.call_tool('gephi3d_export',{'workspace_id':w,'path':str(output),'width':6000,'height':4000,'pdf':True});assert not result.is_error,result
            from PIL import Image
            with Image.open(output) as image:
                assert image.size==(6000,4000) and len(image.resize((120,80)).getcolors(9600))>10
            assert output.with_suffix('.pdf').read_bytes().startswith(b'%PDF-') and Path(str(output)+'.scene.json').is_file()
            return await check_api_import(session,client,tmp_path,w)


async def check_api_import(session,client,tmp_path,previous):
    """A real typed MCP import needs no desktop automation or Gephi-AI instance."""
    graph=nx.DiGraph()
    graph.add_node('中国',label='中国',level=1,community='全国')
    graph.add_node('JS',label='江苏省',level=2,community='江苏',parent='中国')
    graph.add_node('南京',label='南京市',level=3,community='江苏',parent='JS')
    graph.add_node('AH',label='安徽省',level=2,community='安徽',parent='中国')
    graph.add_node('合肥',label='合肥市',level=3,community='安徽',parent='AH')
    graph.add_node('001',label='字符串编号',level=3,community='编号示例',parent='中国')
    graph.nodes['南京']['viz']={'position':{'x':21.,'y':32.,'z':43.},'color':{'r':10,'g':90,'b':180,'a':1.}}
    graph.add_edge('中国','JS',weight=0.);graph.add_edge('JS','南京',weight=2.5)
    graph.add_edge('中国','AH',weight=1.);graph.add_edge('AH','合肥',weight=1.)
    path=tmp_path/'自定义 层级网络.gexf';nx.write_gexf(graph,path)
    before=client.command('save_scene',previous)['scene']
    # Validation failures must not create/select a workspace or change the existing scene.
    examples={
        'malformed.gexf':'<gexf>',
        'duplicate.gexf':'<gexf xmlns="http://www.gexf.net/1.2draft"><graph><nodes><node id="x"/><node id="x"/></nodes></graph></gexf>',
        'dangling.gexf':'<gexf xmlns="http://www.gexf.net/1.2draft"><graph><nodes><node id="x"/></nodes><edges><edge source="x" target="missing"/></edges></graph></gexf>',
        'external.gexf':'<!DOCTYPE gexf [<!ENTITY x SYSTEM "file:///not-read">]><gexf/>',
    }
    for filename,content in examples.items():
        bad=tmp_path/filename;bad.write_text(content,encoding='utf-8')
        result=await session.call_tool('gephi3d_import_graph',{'path':str(bad)})
        assert result.is_error,filename
        assert result.content[0].text!='Error executing tool gephi3d_import_graph',filename
        assert client.command('save_scene',previous)['scene']==before,filename
    result=await session.call_tool('gephi3d_import_graph',{'path':str(path)})
    assert not result.is_error,result
    imported=json.loads(result.content[0].text);w=imported['scene']['workspace_id']
    assert w!=previous and imported['import']['previous_workspace_id']==previous
    assert imported['import']['nodes']==6 and imported['import']['edges']==4
    scene=client.command('save_scene',w)['scene'];nodes={n['id']:n for n in scene['nodes']}
    assert nodes['南京']['label']=='南京市' and nodes['南京']['attributes'].get('parent')=='JS',nodes['南京']
    assert nodes['南京']['attributes']['level']=='3'
    assert nodes['001']['label']=='字符串编号'
    assert (nodes['南京']['x'],nodes['南京']['y'],nodes['南京']['z'])==(21.,32.,43.)
    assert tuple(round(nodes['南京'][c]*255) for c in ['r','g','b'])==(10,90,180)
    assert nodes['中国']['r']+nodes['中国']['g']+nodes['中国']['b']>1
    assert all(e['directed'] for e in scene['edges'])
    assert sorted(e['weight'] for e in scene['edges'])==[0.,1.,1.,2.5]
    assert not scene['synthetic']
    key=str(uuid.uuid4())
    a=client.command('import_graph',path=str(path),synthetic=True,request_id=key)
    b=client.command('import_graph',path=str(path),synthetic=True,request_id=key)
    assert a==b and a['scene']['synthetic']
    w=a['scene']['workspace_id']
    client.command('layer',w,attribute='level',spacing=200)
    client.command('layout',w,iterations=20);wait_for(client,lambda s:s['layout']['state']=='completed')
    client.command('focus',w,ids=['南京']);client.command('neighbors',w,id='南京',hops=2)
    client.command('camera',w,yaw=.9,pitch=.4);client.command('bookmark',w,name='接口导入观察角度')
    # Saving proves the original workspace and its complete scene were retained.
    project=tmp_path/'导入后 保留原图.gephi'
    assert client.command('save_project',w,path=str(project))['success']
    with ZipFile(project) as archive:
        scenes=[]
        for name in archive.namelist():
            if 'gephi3d-explorer-v1' not in name:continue
            data=archive.read(name);scenes.append(json.loads(data[4:].decode('utf-8')))
        assert any(scene==before for scene in scenes)
        assert any('接口导入观察角度' in scene['bookmarks'] for scene in scenes)
    output=tmp_path/'接口导入 预览.png'
    result=await session.call_tool('gephi3d_export',{'workspace_id':w,'path':str(output),'width':1200,'height':800,'pdf':True})
    assert not result.is_error,result
    assert output.is_file() and output.with_suffix('.pdf').is_file()
    return project,client.command('save_scene',w)['scene']
