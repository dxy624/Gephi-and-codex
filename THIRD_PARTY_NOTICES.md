# Third-party components

The project source is under the root MIT license. It uses public APIs and runtime libraries from the user's separately installed Gephi. No Gephi, Java, Python or Maven distribution is included.

| Component | Role | Upstream / license |
|---|---|---|
| Gephi 0.11.3 | Host application and graph/project APIs | [Gephi license notice](https://github.com/gephi/gephi/blob/master/COPYING.txt), dual CDDL-1.0 / GPL-3.0 as specified by upstream |
| NetBeans Platform | Window/module system supplied by Gephi | [Apache NetBeans](https://netbeans.apache.org/), Apache-2.0 |
| JOGL / GlueGen | OpenGL runtime supplied by Gephi | [JOGL notices](https://github.com/JogAmp/jogl/blob/master/LICENSE.txt), upstream BSD-style and other notices |
| JOML | Matrix calculations supplied by Gephi | [JOML](https://github.com/JOML-CI/JOML), MIT |
| Gson | JSON supplied by Gephi | [Gson](https://github.com/google/gson), Apache-2.0 |
| MCP Python SDK | Python tool server/client | [SDK](https://github.com/modelcontextprotocol/python-sdk), MIT |
| ReportLab | PDF export | [ReportLab](https://www.reportlab.com/), BSD-style |
| NetworkX | Data conversion and reference calculations | [NetworkX](https://networkx.org/), BSD-3-Clause |
| openpyxl | XLSX input | [openpyxl](https://openpyxl.readthedocs.io/), MIT |
| tomlkit | Preserve TOML configuration formatting | [tomlkit](https://github.com/python-poetry/tomlkit), MIT |

Dependencies are downloaded by the user's Python package installer and retain their own notices, including transitive dependencies. Development dependencies are declared separately. Gephi-AI is optional and is not bundled, copied or required by this release. Compatibility with Gephi does not imply endorsement by its maintainers.
