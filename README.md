# Gephi 3D Explorer

**v0.1.3-alpha · Windows · Gephi 0.11.3**

在 Gephi 内旋转、分层和探索三维网络，也可让 Codex 用中文控制同一个视图。此仓库包含 Gephi 插件、独立 MCP 服务和 `gephi-3d` Skill，三者配合使用。

**默认通过插件接口执行，不接管鼠标键盘。** 连接失败会报告原因；只有用户明确要求操作界面时，才允许使用电脑控制。图像检查使用插件导出，无需桌面截图或拖动验证。

![合成网络三维视图](docs/assets/preview.png)

**English:** An experimental dockable 3D network explorer for Gephi 0.11.3 on Windows, controlled through mouse interaction or Codex MCP tools. Includes a Gephi module, a Python MCP bridge and an Agent Skill. This is an Alpha release; the example network is synthetic. See [English quick start](docs/QUICKSTART.en.md).

## 1. 安装前置软件

- [Gephi 0.11.3](https://github.com/gephi/gephi/releases/tag/v0.11.3) Windows 版。
- [Python 3.12](https://www.python.org/downloads/windows/)；安装时包含 Python Launcher。
- 已安装 Codex。此插件本身不需要 OpenAI API Key；Codex 按其自身登录和使用规则运行。

普通使用优先使用 Gephi 自带 Java 17，无需另装 JDK 或 Maven。只有重新编译源码才需要 **JDK 17**。

下载后请先把整个 ZIP 解压到准备长期保留的位置。不要在压缩包内部启动；安装后移动目录需要重新建立 Python 环境并更新 MCP 路径。

## 2. 安装插件与连接

在解压目录中打开 PowerShell，先预览：

```powershell
py -3.12 scripts/setup.py --install-skill --configure-codex --dry-run
```

确认后安装：

```powershell
py -3.12 scripts/setup.py --install-skill --configure-codex
```

安装会建立本项目的 `.venv`、安装依赖、安装个人技能并新增 `gephi-3d` MCP 配置。已有同名但内容不同的技能或连接会报告冲突，不会直接覆盖。修改配置前会备份到 Codex 用户目录的 `backups` 中，其他配置保留。

Gephi 不在默认位置时指定安装目录；目录应包含 `gephi` 和 `platform` 子目录：

```powershell
py -3.12 scripts/setup.py --gephi "D:\Apps\Gephi-0.11.3" --install-skill --configure-codex
```

也可设置 `GEPHI_HOME`。只准备运行环境、不修改个人 Codex：运行不带 `--install-skill --configure-codex` 的安装命令。

安装成功后重新打开 Codex。Skill 的安装与 MCP/Gephi 的安装是不同步骤；仅从 GitHub 安装 Skill 不能获得完整运行环境。[Codex 技能](https://developers.openai.com/codex/skills) · [MCP 配置](https://developers.openai.com/codex/mcp)

已有旧版时，先保存工作并关闭旧 Gephi。把新版解压到独立目录，运行 `py -3.12 scripts/setup.py` 准备新版依赖。如果安装器报告已有不同的技能或连接，先备份原技能或配置，再按预览信息更新 `gephi-3d` 这一项。新版启动器只从当前目录的 NBM 安装模块。

## 3. 启动

双击根目录 **Start-Gephi-3D.cmd**。启动程序会从附带的 [NBM](dist/gephi-3d-explorer-0.1.3.nbm) 中安装本模块到独立配置，并打开 Gephi。

默认本地服务为 `127.0.0.1:8184`，运行数据和日志在 `%LOCALAPPDATA%\Gephi3DExplorer`。同一实例已运行时复用其窗口；端口被其他实例占用时报告冲突，不关闭其他进程。正常关闭 Gephi 窗口即可退出。

如面板未显示，进入 **窗口 → 3D Explorer · 三维探索**。

| 鼠标 | 行为 |
|---|---|
| 左键拖动 | 旋转 |
| 右键或中键拖动 | 平移 |
| 滚轮 | 缩放 |
| 点击 / Ctrl 点击 | 单选 / 增减选区 |
| 双击节点 | 聚焦 |
| Shift 拖动 | 按屏幕矩形框选；Ctrl 可追加 |
| 悬停 | 查看节点简要属性 |

## 4. 用中文绘图

在 Codex 中输入：

> 使用 gephi-3d，生成一个 60 节点、3 社区的合成网络，按社区配色并运行三维布局。找到节点 12，聚焦它，高亮两阶邻居；把社区分层，保存观察视角。

也可以自行点击面板的“生成示例网络”和“三维布局”。已有数据可对 Codex 说：

> 使用 gephi-3d，通过插件接口把指定绝对路径的 GEXF 导入新工作区，保留已有图；按 level 属性分层，运行三维布局并保存视角。不要使用电脑控制。

`gephi3d_import_graph` 直接导入静态 GEXF，保留中文 ID、属性、方向、权重、颜色和已有 XYZ；未提供坐标的节点获得初始三维位置。CSV/XLSX 先转换成 GEXF。[示例与数据格式](examples/README.md)

导入上限为 20 MB、10000 节点、100000 边；不支持动态图或嵌套 GEXF 节点，行政层级可使用普通节点、parent 属性和有向边表示。错误文件不会覆盖已有图。

若运行中的是旧插件，先保存工作并正常关闭 Gephi，再通过新版启动入口打开。更新后重开 Codex，即可加载 28 个 MCP 工具。

要一次完成导入、布局、配色和展示，可让 Codex 调用 `gephi3d_auto_draw`。它能直接读带节点表和连接表的 XLSX，或读静态 GEXF；按社区配色，用权重控制线宽，有向边才显示箭头。没有数据时，可以明确要求生成合成例子。

供应链地理图使用 `gephi3d_supply_chain_map`。提供一份 GEXF、CSV 或 Stata `.dta` 关系数据，一个本地 GeoJSON 底图，以及与底图坐标基准一致的城市中心坐标表。按城市代码匹配企业，指定链主编号后，地图上会放大链主社区、加粗社区内连线。地图**默认关闭**，在当前工作区右侧勾选「地理地图」即可手动切换；关闭会恢复普通视图。发行包中的预览是合成网络。底图和真实企业数据由用户提供。[字段与示例](examples/README.md)

## 5. 保存与导出

向 Codex 明确指定**可写文件夹的完整路径**。保存 `.gephi` 以保留网络、三维坐标、相机和书签；导出默认 6000×4000 PNG，也可生成嵌入该图和中文说明的 PDF。导出附带场景 JSON。

现有文件不会被覆盖，请使用新文件名。目录权限失败时应选择可写位置或自行授权；管理员安装目录不适合作为结果目录。

三维坐标与 Gephi 原来的二维坐标分开保存。原有 Gephi-AI 若连接另一个实例，不能用它保存或修改当前三维实例中的图。

## 已知限制与验证

这是可运行原型，仍缺完整筛选面板、层隐藏控件、任意属性配色和完整论文图例编辑；当前以 MCP 完成筛选与部分样式调整。框选暂不显示拖拽矩形，标签数量有限。无时间动画、SVG 或三轴拖拽编辑，JSON 快照暂不能导回。

只验证 Windows＋Gephi 0.11.3。9,000 节点 / 36,000 边曾在 Intel 集成显卡、1920×1080 短时离屏旋转中约为 62 FPS；这不是持续交互性能承诺，也未验证独立显卡。保存恢复、布局取消、数据转换和工具调用的记录见 [验收说明](docs/VALIDATION.md)。

## 开发与发布

[开发、测试与构建](docs/DEVELOPMENT.md) · [版本说明](CHANGELOG.md) · [第三方组件](THIRD_PARTY_NOTICES.md) · [MIT 许可证](LICENSE)

仓库不附带 Gephi、JDK、Python 安装程序或任何本机访问 token。`release-files.json` 是发布文件清单，打包脚本只读取清单内文件。
