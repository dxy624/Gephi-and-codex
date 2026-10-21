"""Local Gephi 3D transport, also usable from scripts before MCP is reloaded."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import uuid

class Gephi3D:
    def __init__(self, url: str | None = None, token_file: str | Path | None = None):
        self.url = (url or os.environ.get('GEPHI3D_URL', 'http://127.0.0.1:8183')).rstrip('/')
        if self.url not in {'http://127.0.0.1:8183', 'http://127.0.0.1:8184'}:
            raise ValueError('This local bridge supports loopback ports 8183 and 8184')
        self.token_file = Path(token_file or os.environ.get('GEPHI3D_TOKEN_FILE', str(Path(os.environ.get('APPDATA', str(Path.home()/'AppData/Roaming')))/'gephi/0.11/config/gephi3d-token')))

    def request(self, path: str, payload: dict | None = None, timeout: float = 90) -> dict:
        if not self.token_file.is_file():
            raise RuntimeError(f'Gephi 3D plugin is not running, or token path is incorrect: {self.token_file}')
        token = self.token_file.read_text(encoding='utf-8').strip()
        headers = {'Authorization': 'Bearer '+token, 'Content-Type': 'application/json'}
        request = urllib.request.Request(self.url+path, data=json.dumps(payload, ensure_ascii=False, allow_nan=False).encode() if payload is not None else None, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                result = json.load(response)
        except urllib.error.HTTPError as exc:
            try: detail = json.load(exc).get('error', str(exc))
            except (ValueError, AttributeError): detail = str(exc)
            raise RuntimeError(detail) from exc
        except urllib.error.URLError as exc:
            raise RuntimeError('Cannot connect to Gephi 3D. Start Gephi with the plugin enabled.') from exc
        if result.get('success') is False: raise RuntimeError(result.get('error', 'Gephi command failed'))
        return result

    def state(self) -> dict:
        return self.request('/state')

    def command(self, operation: str, workspace_id: int | None = None, request_id: str | None = None, **parameters) -> dict:
        payload = dict(parameters, operation=operation, request_id=request_id or str(uuid.uuid4()))
        if workspace_id is not None: payload['workspace_id'] = workspace_id
        return self.request('/command', payload)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', default='state', nargs='?')
    parser.add_argument('--workspace-id', type=int)
    parser.add_argument('--parameters', default='{}', help='JSON object')
    args = parser.parse_args()
    client = Gephi3D()
    result = client.state() if args.operation == 'state' else client.command(args.operation, args.workspace_id, **json.loads(args.parameters))
    print(json.dumps(result, ensure_ascii=False, indent=2))
if __name__ == '__main__': main()
