import pytest

def pytest_addoption(parser):
    parser.addoption('--run-live',action='store_true',help='Start an isolated Gephi on free port 8183; never attach to an existing instance')
    parser.addoption('--gephi',default=None,help='Gephi 0.11.3 installation folder')

def pytest_collection_modifyitems(config,items):
    if config.getoption('--run-live'):return
    for item in items:
        if 'live' in item.keywords:item.add_marker(pytest.mark.skip(reason='Use --run-live for isolated Gephi tests'))
