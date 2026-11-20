import pytest

def pytest_addoption(parser):
    parser.addoption('--run-live',action='store_true',help='Start Gephi with test profiles; never attach to an existing instance')
    parser.addoption('--gephi',default=None,help='Gephi 0.11.3 or 0.11.4 installation folder')
    parser.addoption('--gephi-test-port',type=int,choices=(8183,8184),default=8183,
                     help='Free loopback port reserved for the test Gephi')

def pytest_collection_modifyitems(config,items):
    if config.getoption('--run-live'):return
    for item in items:
        if 'live' in item.keywords:item.add_marker(pytest.mark.skip(reason='Use --run-live for isolated Gephi tests'))
