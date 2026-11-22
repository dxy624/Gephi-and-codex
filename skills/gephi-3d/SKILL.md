---
name: gephi-3d
description: 通过 Gephi 3D Explorer 从 Excel、CSV、Stata 或 GEXF 绘制三维网络，查看供应链上下游、年度关系并导出论文图。普通三维物体建模不适用。
---

# Gephi 三维网络探索

## 操作方式

- 默认只通过 `gephi3d_*` MCP 工具与本机插件接口操作。开始绘图前用 `gephi3d_check_connection` 核对已配置的启动方式、版本、能力、工作区和布局状态。旧版可用 `gephi3d_status`；后续命令使用返回的准确 `workspace_id`。
- 生成、导入、布局、打开三维面板、旋转、聚焦、分层、保存和检查结果，都不构成使用电脑控制的授权。不得为这些任务自动调用 computer-use、桌面截图、模拟点击/按键/拖动，或用其他脚本模拟桌面输入。
- 只有用户明确要求“操作界面”“点击菜单”或“允许电脑控制”时，才可按其授权范围使用界面操作；完成后结束控制。用户自行旋转 Gephi 视图不需要 Codex 接管电脑。
- MCP 尚未加载时，可用发行目录 `bridge/gephi3d_client.py check` 或 `state` 访问同一插件接口。其他命令传 `--workspace-id`（适用时）与 `--parameters` JSON。沿用已配置的 Python、URL 和 token 文件。
- 连接失败、认证失败或插件能力不足时，报告具体原因和恢复步骤，不自动转为电脑控制、不自动重装、不关闭进程。超时可能已执行，先读状态核实，不盲目重发生成或导入。

## 数据与生成

- 从表格出图优先调用 `gephi3d_auto_draw`，直接传入本机 XLSX（单表或节点、连接双表）、CSV、DTA 或静态 GEXF。工具在新工作区导入、布局并打开三维面板；仅在用户需要导出时传新的绝对 `png_path` 或 `svg_path`。只有用户要求合成示例时才省略文件。
- 供应商和客户列明确时，箭头从供应商指向客户。通用起点、终点列按用户已给出的数据含义设置 `directed`。字段歧义时用 `source_column` 和 `target_column`。保留文本股票代码的前导零；Excel 使用全零格式显示的编号按显示宽度读取。同年重复关系默认报错，只有确认权重可相加才指定 `duplicates="sum"`。
- 从文件直接看指定企业时，传 `roots`、`direction`（`upstream`、`downstream`、`both`）、`hops` 和用户指定的 `year`。比较两个年份时传 `reference`。用户只说「按年份查看」但未指定哪一年时，可以先显示全部并报告可用年份。需要 Gephi 插件 0.1.8+。供应链表可直接画普通网络；地图按下面的可选流程启用。
- 已有工作区单独调外观时，用 `gephi3d_style`。缺社区字段时，自动绘图按网络结构识别社区，并在交付中注明来源。
- 使用项目里的 `bridge/auto_draw.py` 作为自动绘图的命令行回退，沿用已配置的本机 URL 和 token 文件。若不起作用，先读状态再决定下一步，避免导入两次。
- 只有在用户要求地图时才调用 `gephi3d_supply_chain_map`。使用明确的城市代码、同一坐标基准下的本地 GeoJSON 底图和城市坐标表，链主按用户提供的企业 ID 认定。保留普通视图；`enabled` 默认为关闭，用户可在当前工作区勾选「地理地图」或用 `gephi3d_geography` 切换。报告匹配失败的城市代码、无位置的链主和数据来源。
- 没有数据且只需示例时，用 generate 生成合成网络，默认 60 节点、3 社区、平均度 8、seed 42。模板为 `community`、`layered`（有向无环）、`hub`；明确标注非真实观测。
- 用户指定准确节点、隶属关系或业务结构时，构造对应节点、边表或静态 GEXF。需手动转换分开的节点和边文件时，用发行包中的 `scripts/tables_to_gexf.py`。
- 使用 `gephi3d_import_graph(path, synthetic=False)` 直接导入本机绝对路径的 GEXF，始终新建工作区，保留其他图。示意或模拟数据设 synthetic=True；导入本身不证明数据真实。上限 20 MB、10000 节点、100000 边，仅支持静态平面节点表（层级通过 parent 等属性和连边表示）。
- 导入前 status 的 capabilities 必须包含 `import_graph`，否则说明需要保存当前工作后正常关闭并升级/重启插件。不得转而点击 Gephi 导入菜单。导入失败报告错误，保留已有图；成功后检查实际数量、警告、属性、方向，使用返回的新工作区 ID，无需再次 refresh。
- generate/import 创建新工作区后，按需调用 open 和 layout。Gephi-AI 只有确认连接同一 Gephi 实例时才可协作；相同 workspace_id 不能证明同一实例，不能跨实例导入、统计或保存。

## 连续探索

- “这个节点”“我选中的”依据 status 的实际选区；名字先 search，再用准确 ID 调用 select、focus、neighbors、path。确实不唯一时再澄清。
- 工作区或修订冲突时重读状态，不对新工作区盲目重试。相机 yaw/pitch 为弧度；调整相机、标签和样式保留布局，仅生成新图或用户要求时重新布局。
- 布局异步返回 job_id，查询 status 等待 completed，勿重复提交。每轮最多 1000 次或 30 秒；失败报告 job error，取消后等待停止再导入、分层或导出。
- neighbors 为无向 h 阶邻接；path 为按边数的最短路径，默认尊重有向边。含源节点的高亮数量与其他邻居数分开说明。
- 高亮、隔离和筛选只影响显示；clear_highlight 保留属性筛选，filter 默认值恢复全部。layer 按属性映射 Z 轴，固定节点保持不动；分层后布局仅优化层内位置。
- 0.1.8+ 用 `gephi3d_supply_scope` 设置上下游方向和阶数，按用户需求用 `gephi3d_clear_scope_block` 解除挡住结果的单项条件。用 `gephi3d_figure_preset` 应用工程预设，`gephi3d_years` 按边的实际年度记录切换或对比。
- bookmark 保存或恢复观察角度；history 保留最近 15 个场景命令，鼠标相机拖动不逐帧进入历史。

## 保存与结果检查

- 用 status、node 和 save_scene 检查数量、选区、坐标、层级及书签。需要查看图像时用插件 export 生成预览并读取该图片，不启用桌面截图或控制鼠标验证旋转。
- 汇报 `view.visible_nodes`、`view.visible_edges`、实际年份及已选中心；把输入合计与视图结果分开。使用地图时按 `supply_scope` 报告定位覆盖和挡住结果的条件，区分数据里有城市代码与实际匹配到坐标。
- 优先使用用户指定的输出目录；目录不可写时明确报告，不声称保存成功、不静默换位置、不用界面绕过权限。
- 导出前等待布局完成并保存视角。PNG 默认 6000×4000，可附含栅格图和中文说明的 PDF；需要新的绝对文件名，不能覆盖现有文件，PNG 同时写出 `.scene.json`。
- save_project 保存完整 `.gephi` 工程及各工作区场景；save_scene 输出独立 JSON，当前无 JSON 导回接口。检查返回路径和文件结果。
- 论文图用 `gephi3d_export_paper` 或自动绘图的 `svg_path`。Gephi 面板的「导出论文图（SVG）」会先打开整页预览。0.1.9+ 可用 `gephi3d_preview_paper` 生成与 SVG 同排版的预览 PNG。选用 `options.marks` 标注重点节点，`options.caption` 添加自定义图注，`options.weight_meaning` 指明边权含义；同时传 `options.left_year` 和 `options.right_year` 即可导出共用视角、坐标、配色和线宽标尺的并排组图。旧工程没有年度关系数据时，重新导入原始关系表。

## 连接恢复

安装需要发行包里的插件和 MCP。桌面配置默认端口 8183，从原 Gephi 图标启动。独立配置默认 8184，从发行目录运行 `Start-Gephi-3D.cmd`。以连接检查返回的配置为准。需要展示界面时用 `gephi3d_open` 打开三维面板。

URL 与 `GEPHI3D_TOKEN_FILE` 必须属于同一实例。token 由本机插件生成，不展示、不上传。旧插件需先保存工作、正常关闭后再启动新版；Codex 未发现新增工具时重新加载 MCP/重开 Codex。保留当前图，不生成新图试探连接。
