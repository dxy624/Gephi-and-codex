"""Opt-in acceptance: never attaches to the user's live profile or occupied port."""
import asyncio
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
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
        asyncio.run(check_mcp(client,tmp_path))
    finally:stop()

async def check_mcp(client,tmp_path):
    from mcp import ClientSession,StdioServerParameters
    from mcp.client.stdio import stdio_client
    params=StdioServerParameters(command=sys.executable,args=['-X','utf8',str(ROOT/'bridge/server.py')],env=dict(os.environ,GEPHI3D_URL=client.url,GEPHI3D_TOKEN_FILE=str(client.token_file)))
    async with stdio_client(params) as (reader,writer):
        async with ClientSession(reader,writer) as session:
            await session.initialize();listed=await session.list_tools();assert len(listed.tools)==24
            result=await session.call_tool('gephi3d_status',{});assert not result.is_error
            state=json.loads(result.content[0].text);w=state['scene']['workspace_id']
            result=await session.call_tool('gephi3d_search',{'workspace_id':w,'query':'12'});assert not result.is_error and json.loads(result.content[0].text)['matches']
            output=tmp_path/'高清 视图.png'
            result=await session.call_tool('gephi3d_export',{'workspace_id':w,'path':str(output),'width':6000,'height':4000,'pdf':True});assert not result.is_error,result
            from PIL import Image
            with Image.open(output) as image:
                assert image.size==(6000,4000) and len(image.resize((120,80)).getcolors(9600))>10
            assert output.with_suffix('.pdf').read_bytes().startswith(b'%PDF-') and Path(str(output)+'.scene.json').is_file()
