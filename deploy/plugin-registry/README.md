# ORIVA 开源插件站静态生成与 PR 收录模板

本目录提供一套零第三方运行时依赖的静态插件站工具链，用于社区维护者托管个人或社区第三方插件源（Morphology B，兼容知序 ORIVA Android 客户端「设置 → 插件 → 自定义第三方来源」）。

> 协议规范见仓库知识库 `Obsidian/04-技术实现/插件目录部署规范.md`。本目录产物严格保持零真实私钥入库与零外部 CDN 脚本/字体依赖。

## 目录结构

```text
deploy/plugin-registry/
├── registry_tool.py          # 校验与确定性静态生成 CLI（Python 3 标准库 + JDK JarSignerVerifier）
├── JarSignerVerifier.java    # JDK JarFile(file, true) 逐条目真实签名者与公钥 SPKI 核验器
├── registry.json             # 源清单（Morphology A 输入：含 sourceId / trustedFingerprints / 插件列表）
├── artifacts/                # 已签名的 .pljs / .plp 插件包
├── web/                      # 墨序（ORIVA Ink）纯静态零外链展示站点前端（index.html / style.css / app.js）
├── tests/                    # 正反例单元与对抗测试（临时密钥库用完即毁）
└── README.md                 # 本指南
```

## 前置环境

- Python 3.10+（仅使用标准库）
- JDK 17+（需要 `java` 运行 `JarSignerVerifier.java`，以及在生成/签发测试包时使用 `jarsigner` / `keytool`，在 `PATH` 或 `JAVA_HOME/bin` 中可用）

## 一、提交新插件的 PR 流程

1. **打包插件**：
   - JS 插件（`type: "js"`）：`contractVersion` 须为 `2`，ZIP 根目录须包含 `plugin.json` 与 `index.js`，`entryPoint` 须为合法 JS 函数名。
   - DEX 插件（`type: "dex"`）：ZIP 根目录须包含 `plugin.json` 与有效的 `classes.dex`。
   - `id` 与 `version` 须符合客户端落盘路径段白名单（`[A-Za-z0-9._-]+`，且不为 `.` 或 `..`）。
   - 包内 `plugin.json` 的 `sourceId` 必须与目标源 `registry.json` 的 `sourceId` 一致，且 `sourceType` 必须为 `"THIRD_PARTY"`（禁止冒用保留 ID `official` / `third-party`）。
   - 严禁在 `META-INF/` 下放置除 `MANIFEST.MF` 与配对 `*.SF` / `*.(RSA|DSA|EC)` 之外的普通文件，且 `META-INF/` 须严格大写；所有签名元数据文件（`MANIFEST.MF`、`*.SF`、`*.RSA/DSA/EC`）均不得为空（0 字节），也不允许包含不被任何负载归因的多余或损坏签名配对。
   - **算法与证书有效期策略**：`JarSignerVerifier.java` 与 Android `SignedJarPluginPackageVerifier` 均通过 `JarFile(file, true)` 生效宿主 `jdk.jar.disabledAlgorithms`（禁用弱摘要与弱密钥）；社区签名建议使用 `RSA >= 2048` 或 `ECDSA P-256` 搭配 `SHA-256`。与 Android 离线证书钉扎模型一致，两者均不要求商业 CA PKIX 链，也不因自签名证书过期日期单独阻断已入锚包（维护者可通过 `inspect-cert` / `keytool -printcert` 巡检时间）。
2. **使用作者签名证书签名（本地私钥绝不入库）**：
   ```sh
   # 生成签名证书（若已有签名密钥库可跳过）
   keytool -genkeypair -alias my-plugin-key -keyalg RSA -keysize 2048 -validity 3650 \
     -storetype PKCS12 -keystore my-signing-key.p12

   # 对 .pljs 或 .plp 签名（确保覆盖所有负载文件）
   jarsigner -keystore my-signing-key.p12 -storetype PKCS12 artifacts/my-plugin-1.0.0.pljs my-plugin-key
   ```
3. **提取证书 SHA-256 指纹与包信息（仅作诊断，不授予信任）**：
   ```sh
   python3 deploy/plugin-registry/registry_tool.py inspect-cert --package deploy/plugin-registry/artifacts/my-plugin-1.0.0.pljs
   ```
   > **安全提示**：`inspect-cert`（含其 `commonSigners` 为空或非空的输出）**仅作诊断排查用途，绝不授予任何信任**；`validate` 与 `build` 始终严格要求所有负载的共同签名者交集非空且命中 `trustedFingerprints`。
   将签名包放入 `deploy/plugin-registry/artifacts/`（严禁提交 `.p12` / `.jks` / `.keystore` / `.pem` / `.key` 等私钥文件），并在 `registry.json` 中登记插件条目（`trustedFingerprints` 须包含全负载共签叶证书 SHA-256 指纹；全仓上限为 `<= 256` 条目且累计包体积 `<= 100 MiB`）。
4. **字段对齐与目录编辑权规则（W3）**：
   - **强对齐核心字段**：`id`、`name`、`version`、`contractVersion`（`1..2147483647`）、`type`、`entryPoint`、`requiredCapabilities`、`sourceId`、`sourceType` 必须与签名包内 `plugin.json` 完全一致。
   - **`description` 与可选元数据**：`description` 在目录和签名包内各自校验（无控制字符），`registry.json` 中的 `description` 用于下载前目录展示（安装后详情页仍以已验签包内 `plugin.json` 为准）；可选元数据 `author`、`license`（纯文本 ≤ 200 字符，无控制字符）及 `homepage`、`repository`（仅限 `http://` 或 `https://`，禁止 `javascript:` / `data:` / `file:` / 内嵌凭据）可在 `registry.json` 中为既有旧包补充或省略，若目录与签名包内同时提供非空值则必须一致。

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

## 四、CI 自动校验与仓库保护门禁（S3）

`.github/workflows/plugin-registry.yml` 固定全部 Action 到完整 40 位官方提交 SHA、禁用 `persist-credentials` 并保持最小权限（`permissions: contents: read`）：
- **完全隔离的可信基线与 `python3 -I`**：对 `pull_request` 事件，工作流从受保护的 `github.event.pull_request.base.sha` 提取可信基线到候选工作区之外的 **`${{ runner.temp }}/trusted_verifier`**，并在 **`${{ runner.temp }}`** 下以 **`python3 -I`**（隔离模式，禁止候选仓库根目录进入 `sys.path`）运行单元测试、校验候选 `deploy/plugin-registry/registry.json` 并输出构建产物至 **`${{ runner.temp }}/plugin-registry-dist`**，绝不在候选工作区内创建 `_trusted_verifier/` 或 `dist/`，也不存在执行候选 PR 校验代码的 bootstrap 自跑退路（首次引入目录须由维护者人工审计后通过 `workflow_dispatch` / 受保护分支合入）。
- **高敏感变更告警与人工审批门禁**：若 PR 修改了 `.github/workflows/plugin-registry.yml`、`registry_tool.py`、`JarSignerVerifier.java`、`web/`、`tests/` 或变更了 `trustedFingerprints`/`sourceId`/`sourceType`/`catalogFormatVersion`，工作流会在 Summary 与 Annotations 中显式发出维护者人工审批告警。
- **仓库维护者必配分支保护（含 `CODEOWNERS` 边界说明）**：由于发版源码审计脚本 `android/tools/release/release.py:audit_archive` 将 `.github/` 目录严格限定在 `.github/workflows/**` 白名单内，本公仓未直接放入 `.github/CODEOWNERS`（以免触发 `Outside public boundary` 发版拦截）。启用独立开源插件站托管或部署公仓时，须在 GitHub Repository Settings → Rulesets / Branch Protection 为 `main` 开启：
  1. Require status check `validate-and-build` to pass；
  2. Require pull request reviews before merging + Require review from Code Owners（在托管仓或扩展白名单后配置 `.github/CODEOWNERS`，将 `.github/workflows/plugin-registry.yml`、`deploy/plugin-registry/registry_tool.py`、`deploy/plugin-registry/JarSignerVerifier.java` 与 `deploy/plugin-registry/registry.json` 指向 `@DzDenzel`），确保新增信任锚与校验器修改必须由维护者显式批准。
