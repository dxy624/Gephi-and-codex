# 合成示例与表格格式

此目录只有合成网络，不代表真实人物、机构或观测关系。

- [community60.gexf](community60.gexf)：60 个节点、240 条无向边、3 个社区，生成 seed 为 42，目标平均度为 8。附带三维位置与配色。
- [nodes.csv](nodes.csv) / [edges.csv](edges.csv)：同一网络的节点表与边表，UTF-8 编码。
- [预览图](../docs/assets/preview.png)：该合成网络的三维显示效果。

节点表必须有 `id`；边表必须有 `source`、`target`，可包含 `weight`。ID 作为字符串保留；Excel 中有前导零的 ID 应存成文本。节点 ID 不得重复，边端点必须存在；方向需要明确指定，重复边默认报错。

转换同一示例：

```powershell
.venv/Scripts/python.exe scripts/tables_to_gexf.py --nodes examples/nodes.csv --edges examples/edges.csv --output build/converted.gexf --undirected
```

请先创建可写输出目录。确实需要把重复边权重合并时显式传 `--duplicates sum`。转换程序不覆盖现有输出，不把缺失端点自动生成为节点。
