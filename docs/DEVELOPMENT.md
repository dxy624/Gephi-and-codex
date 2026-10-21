# 开发、构建与发布

## 依赖与测试

在 Windows＋Python 3.12 环境中先运行根目录 README 的安装步骤，然后：

```powershell
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
.venv/Scripts/python.exe -m pytest -q
```

默认只运行本地数据转换、配置、插件包与打包测试，不启动或操作任何 Gephi。

实际 Gephi 验收须明确启用：

```powershell
.venv/Scripts/python.exe -m pytest -q --run-live --gephi "D:\Apps\Gephi-0.11.3"
```

测试在临时目录创建专用 profile，使用 **8183**；端口占用会失败，不连接已有实例。测试会创建合成图、保存和重新打开工程，并检查中文搜索、三维状态、24 个 MCP 工具、6000×4000 PNG 和 PDF。测试结束只终止自己创建的 Java 进程。

## 编译插件

需要 JDK 17；不需要 Maven，不下载 Java 依赖，以本机 Gephi API 编译：

```powershell
py -3.12 scripts/build.py --gephi "D:\Apps\Gephi-0.11.3" --jdk "D:\Java\jdk-17"
py -3.12 scripts/build.py --verify-only
```

也可设置 `GEPHI_HOME` 和 `JAVA_HOME`。结果写入 `dist`，编译中间文件写入被忽略的 `build`。归档使用固定时间戳，便于相同工具链下重建核对。

## 自定义路径

setup/run_dev 接受 `--state-dir`，默认 `%LOCALAPPDATA%\Gephi3DExplorer`；运行目录可通过 `GEPHI3D_STATE_DIR` 指定。setup/configure_codex 接受 `--codex-home`，默认 `CODEX_HOME` 或用户的 `.codex`。支持的本地端口为 8183/8184，默认 8184；改端口时同时更新安装生成的 MCP 环境配置。

高级用户也可通过普通 Gephi 插件管理器安装 NBM，此时默认端口为 8183，MCP token_file 必须指向该 Gephi 用户目录的 `config/gephi3d-token`。不要把 token 或完整个人配置加入版本控制。

## 发行包

`release-files.json` 明确列出每个公开文件。更新清单后运行：

```powershell
py -3.12 scripts/package_release.py --output-dir build/release
```

脚本验证清单路径、文件存在、NBM 校验值、Markdown 本地链接及打包内容；不扫描或复制运行目录。输出 ZIP 及 SHA256SUMS。发布时附 NBM、ZIP、校验文件与 CHANGELOG；当前流程不会创建 GitHub 仓库或上传文件。
