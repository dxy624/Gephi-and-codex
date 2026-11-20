# 合成示例与表格格式

此目录只有合成网络，不代表真实人物、机构或观测关系。

- [community60.gexf](community60.gexf)：60 个节点、240 条无向边、3 个社区，生成 seed 为 42，目标平均度为 8。附带三维位置与配色。
- [nodes.csv](nodes.csv) / [edges.csv](edges.csv)：同一网络的节点表与边表，UTF-8 编码。
- [预览图](../docs/assets/preview.png)：自动绘图入口生成的三社区有向加权示例。

节点表必须有 `id`；边表必须有 `source`、`target`，可包含 `weight`。ID 作为字符串保留；Excel 中有前导零的 ID 应存成文本。节点 ID 不得重复，边端点必须存在；方向需要明确指定，重复边默认报错。

转换同一示例：

```powershell
.venv/Scripts/python.exe scripts/tables_to_gexf.py --nodes examples/nodes.csv --edges examples/edges.csv --output build/converted.gexf --undirected
```

请先创建可写输出目录。确实需要把重复边权重合并时显式传 `--duplicates sum`。转换程序不覆盖现有输出，不把缺失端点自动生成为节点。

## 供应链地图

给 Codex 以下本地数据：

| 文件 | 最少字段或格式 |
| --- | --- |
| 供应链 CSV 或 `.dta` | `source`、`target`、`source_city_code`、`target_city_code`。可加 `weight`、`source_name`、`target_name`、`source_community`、`target_community` |
| 或静态 GEXF | 节点保留 `city_code`，边保留真实方向和权重 |
| 城市坐标 CSV | `city_code`、`city_name`、`longitude`、`latitude`。城市代码为六位，和关系表采用同一套编码 |
| 底图 | 用户提供本地 GeoJSON 多边形和来源说明。城市坐标和底图应采用同一坐标基准，坐标顺序为经度、纬度 |

CSV 和 `.dta` 的边按 `source → target` 构建。给出链主的企业 ID。同一企业在各行的城市代码须一致；多年数据先选择期间或合并成每个方向的一条关系。地图默认关闭，准备好后在三维面板手动勾选。城市匹配失败的企业仍可在普通视图查看。
