"""Local Gephi 3D transport, also usable from scripts before MCP is reloaded."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import uuid

class GephiConnectionError(RuntimeError):
    def __init__(self, reason: str, message: str):
        super().__init__(message)
        self.reason = reason


class Gephi3D:
    def __init__(self, url: str | None = None, token_file: str | Path | None = None):
        self.url = (url or os.environ.get('GEPHI3D_URL', 'http://127.0.0.1:8183')).rstrip('/')
        if self.url not in {'http://127.0.0.1:8183', 'http://127.0.0.1:8184'}:
            raise ValueError('This local bridge supports loopback ports 8183 and 8184')
        self.token_file = Path(token_file or os.environ.get('GEPHI3D_TOKEN_FILE', str(Path(os.environ.get('APPDATA', str(Path.home()/'AppData/Roaming')))/'gephi/0.11/config/gephi3d-token')))

    def request(self, path: str, payload: dict | None = None, timeout: float = 90) -> dict:
        if not self.token_file.is_file():
            raise GephiConnectionError('missing_token', 'Gephi 3D token file is missing. ' + self.startup_hint())
        try:
            token = self.token_file.read_text(encoding='utf-8').strip()
        except (OSError, UnicodeError) as exc:
            raise GephiConnectionError('unreadable_token', 'Gephi 3D token is unreadable. Check the configured token file.') from exc
        headers = {'Authorization': 'Bearer '+token, 'Content-Type': 'application/json'}
        request = urllib.request.Request(self.url+path, data=json.dumps(payload, ensure_ascii=False, allow_nan=False).encode() if payload is not None else None, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                result = json.load(response)
        except urllib.error.HTTPError as exc:
            if exc.code in (401, 403):
                raise GephiConnectionError('authentication', f'Gephi 3D authentication failed (HTTP {exc.code}). Check that the URL and token file belong to the same Gephi instance.') from exc
            try: detail = json.load(exc).get('error', str(exc))
            except (ValueError, AttributeError): detail = str(exc)
            raise RuntimeError(detail) from exc
        except urllib.error.URLError as exc:
            if isinstance(exc.reason, TimeoutError):
                raise GephiConnectionError('timeout', 'Gephi 3D timed out; the command outcome is unknown. Read status before retrying.') from exc
            raise GephiConnectionError('unreachable', f'Cannot connect to Gephi 3D at {self.url}. ' + self.startup_hint()) from exc
        except (TimeoutError, ConnectionError) as exc:
            raise GephiConnectionError('timeout', 'Gephi 3D request timed out or the connection was lost; the command outcome is unknown. Read status before retrying.') from exc
        except json.JSONDecodeError as exc:
            raise GephiConnectionError('wrong_service', 'The configured port did not return a Gephi plugin response.') from exc
        if not isinstance(result, dict):
            raise GephiConnectionError('wrong_service', 'The configured port did not return a Gephi plugin response.')
        if result.get('success') is False: raise RuntimeError(result.get('error', 'Gephi command failed'))
        return result

    def startup_hint(self) -> str:
        if self.url.endswith(':8183'):
            return '从桌面或开始菜单打开 Gephi，确认已安装并启用 3D Explorer。'
        return '运行发行包的 Start-Gephi-3D.cmd，打开已配置的独立实例。'

    def diagnose(self) -> dict:
        mode = 'desktop' if self.url.endswith(':8183') else 'isolated'
        result = {'connection': mode, 'port': int(self.url.rsplit(':', 1)[-1])}
        try:
            state = self.request('/state', timeout=5)
            if not isinstance(state.get('version'), str) or not isinstance(state.get('capabilities'), list):
                raise GephiConnectionError('wrong_service', 'The configured port did not return a Gephi plugin status.')
        except GephiConnectionError as exc:
            issue = exc.reason
            if issue in {'authentication', 'unreadable_token'}:
                next_step = '检查 Codex 的连接配置，确认端口和密钥文件属于同一个 Gephi 实例。'
            elif issue in {'timeout', 'wrong_service'}:
                next_step = '检查这个 Gephi 实例是否响应，以及该端口是否被其他程序占用，然后重试连接检查。'
            else:
                next_step = self.startup_hint()
            return {**result, 'connected': False, 'reason': issue, 'message': str(exc), 'next_step': next_step}
        capabilities = state.get('capabilities', [])
        ready = {'import_graph', 'visual_presets', 'supply_scope', 'edge_years'} <= set(capabilities)
        return {**result, 'connected': True, 'auto_draw_ready': ready, 'plugin_version': state.get('version'),
                'capabilities': capabilities, 'workspace': state.get('scene'), 'layout': state.get('layout'),
                'next_step': '保存工程，正常关闭 Gephi，再升级到 0.1.8 或更新版。' if not ready else
                '如果 Gephi 连接正常而 Codex 仍找不到新工具，请重新打开 Codex。'}

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
    if args.operation == 'check':
        result = client.diagnose()
    elif args.operation == 'state':
        result = client.state()
    else:
        result = client.command(args.operation, args.workspace_id, **json.loads(args.parameters))
    print(json.dumps(result, ensure_ascii=False, indent=2))
if __name__ == '__main__': main()
