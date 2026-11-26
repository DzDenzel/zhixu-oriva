# ORIVA 开源插件站静态生成与 PR 收录模板

本目录提供一套零第三方运行时依赖的静态插件站工具链，用于社区维护者托管个人或社区第三方插件源（Morphology B，兼容知序 ORIVA Android 客户端「设置 → 插件 → 自定义第三方来源」）。

> 协议规范见仓库知识库 `Obsidian/04-技术实现/插件目录部署规范.md`。本目录产物严格保持零真实私钥入库与零外部 CDN 脚本/字体依赖。

## 目录结构

```text
deploy/plugin-registry/
├── registry_tool.py          # 校验与确定性静态生成 CLI（Python 3 标准库 + JDK jarsigner/keytool）
├── registry.json             # 源清单（Morphology A 输入：含 sourceId / trustedFingerprints / 插件列表）
├── artifacts/                # 已签名的 .pljs / .plp 插件包
├── web/                      # 墨序（ORIVA Ink）纯静态零外链展示站点前端（index.html / style.css / app.js）
├── tests/                    # 正反例单元与对抗测试（临时密钥库用完即毁）
└── README.md                 # 本指南
```

## 前置环境

- Python 3.10+（仅使用标准库）
- JDK 17+（需要 `jarsigner` 与 `keytool`，在 `PATH` 或 `JAVA_HOME/bin` 中可用）

## 一、提交新插件的 PR 流程

1. **打包插件**：
   - JS 插件（`type: "js"`）：`contractVersion` 须为 `2`，ZIP 根目录须包含 `plugin.json` 与 `index.js`，`entryPoint` 须为合法 JS 函数名。
   - DEX 插件（`type: "dex"`）：ZIP 根目录须包含 `plugin.json` 与有效的 `classes.dex`。
   - 包内 `plugin.json` 的 `sourceId` 必须与目标源 `registry.json` 的 `sourceId` 一致，且 `sourceType` 必须为 `"THIRD_PARTY"`（禁止冒用保留 ID `official` / `third-party`）。
2. **使用作者签名证书签名（本地私钥绝不入库）**：
   ```sh
   # 生成签名证书（若已有签名密钥库可跳过）
   keytool -genkeypair -alias my-plugin-key -keyalg RSA -keysize 2048 -validity 3650 \
     -storetype PKCS12 -keystore my-signing-key.p12

   # 对 .pljs 或 .plp 签名（确保覆盖所有负载文件）
   jarsigner -keystore my-signing-key.p12 -storetype PKCS12 artifacts/my-plugin-1.0.0.pljs my-plugin-key
   ```
3. **提取证书 SHA-256 指纹与包信息**：
   ```sh
   python3 deploy/plugin-registry/registry_tool.py inspect-cert --package deploy/plugin-registry/artifacts/my-plugin-1.0.0.pljs
   ```
   将签名包放入 `deploy/plugin-registry/artifacts/`（严禁提交 `.p12` / `.jks` / `.keystore` / `.pem` / `.key` 等私钥文件），并在 `registry.json` 中登记插件条目（`trustedFingerprints` 须包含签名证书 SHA-256 指纹）。
4. **登记可选安全元数据（W3）**：
   - 支持可选 `author`、`license`（纯文本 ≤ 200 字符，无控制字符）及 `homepage`、`repository`（仅限 `http://` 或 `https://`，禁止 `javascript:` / `data:` / `file:` / 内嵌凭据）。

## 二、本地校验与静态站构建

```sh
# 1. 运行全套正反例单测
python3 -m unittest discover -s deploy/plugin-registry/tests -v

# 2. 校验当前目录清单与全部签名包
python3 deploy/plugin-registry/registry_tool.py validate --registry deploy/plugin-registry/registry.json --json

# 3. 确定性生成静态站产物到 deploy/plugin-registry/dist/
python3 deploy/plugin-registry/registry_tool.py build --registry deploy/plugin-registry/registry.json --output deploy/plugin-registry/dist --clean

# 4. 本地静态预览（请勿直接双击 file:// 打开，浏览器会拦截 fetch）
python3 -m http.server 8080 --directory deploy/plugin-registry/dist
```

打开 `http://127.0.0.1:8080` 后，即可点击「复制地址」「复制 ID」「复制指纹」并按页面指引添加到知序 Android 客户端。

## 三、在知序 Android 客户端接入

1. 若托管于 GitHub Pages 或 GitHub Raw，确保站点提供 HTTPS `catalog.json`（如 `https://org.github.io/oriva-plugins/catalog.json`）。
2. 在手机打开知序 **设置 → 插件**，阅读进程隔离风险提示后关闭「安全模式」。
3. 点击 **自定义第三方来源 → 添加来源**：
   - **目录 JSON 地址**：粘贴站点 `catalog.json` 地址；
   - **来源 ID**：填入站点显示的 `sourceId`（配置信任锚时必填，须与目录及签名包一致）；
   - **信任锚指纹**：粘贴站点显示的 64 位 SHA-256 指纹（若留空，客户端仅允许浏览发现目录，绝不会跳过签名校验安装）。

## 四、CI 自动校验

`.github/workflows/plugin-registry.yml` 使用最小权限（`permissions: contents: read`）：
- PR 修改 `deploy/plugin-registry/**` 时自动运行全量单测、`validate` 与 `build` 干跑，校验 `SHA256SUMS`，并上传 `plugin-registry-site` 预览产物；
- 任何 ZIP 路径穿越、未签名/篡改/错证书/混签包、保留 `sourceId`、危险 URL 或清单字段不一致均直接失败阻断合并。
