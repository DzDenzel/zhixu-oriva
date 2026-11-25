# 插件目录部署格式（v1 / W2~W4）

目录数据由 UTF-8 `catalog.json` 提供，平台代码不登记插件实例。支持两种部署形态：

1. **后端目录服务（`PluginCatalogController`）**：`planlock.plugins.directory` 指向本目录（默认 `plugins`），按 `/api/v1/plugins/catalog/{source}` 提供 `official` 与 `third-party` 目录，由服务端实时计算 `downloadUrl` 与文件 `sha256`。
2. **静态托管（GitHub Pages / GitHub Raw / 对象存储）**：无后端控制器时，客户端 `PluginCatalogClient` 直接拉取静态 `catalog.json`，静态文件必须包含 `sourceId`、`sourceType` 及每条插件预计算的 `downloadUrl`（支持相对路径）与 `sha256`。

---

## 一、后端目录输入格式（`backend/plugins/{official,third-party}/catalog.json`）

```json
{
  "catalogFormatVersion": 1,
  "plugins": [
    {
      "artifact": "example-1.0.0.plp",
      "id": "example",
      "name": "示例",
      "version": "1.0.0",
      "contractVersion": 1,
      "type": "dex",
      "entryPoint": "example.Plugin",
      "requiredCapabilities": [],
      "description": "插件说明",
      "author": "ORIVA Community",
      "homepage": "https://example.org/plugins/example",
      "repository": "https://github.com/example/oriva-plugin-example",
      "license": "MIT"
    }
  ]
}
```

### 字段与校验规则

| 字段 | 位置 | 约束 |
|---|---|---|
| `catalogFormatVersion` | 顶层（可选） | 正整数（`>= 1`）；缺省兼容按 `1` 处理，透传至响应；传入 `null`、非整数、字符串或 `<= 0` 将拒绝整份目录 |
| `plugins` | 顶层（必填） | 数组；空数组表示该源无目录项 |
| `artifact` | 条目（必填） | 来源目录下文件名，仅含 `[A-Za-z0-9._-]`，不得为 `.` / `..` 或含路径分隔符 |
| `id` / `name` / `version` / `entryPoint` | 条目（必填） | 非空字符串；同源 `id` 唯一 |
| `contractVersion` | 条目（必填） | 正整数（`>= 1`）；安装端仍负责校验支持范围 |
| `type` | 条目（可选） | `"dex"`（默认）或 `"js"`（`js` 需 `contractVersion == 2`） |
| `requiredCapabilities` | 条目（必填） | 非空字符串组成的数组，可为空 |
| `description` | 条目（必填） | 字符串，可为空 |
| `author` / `license` | 条目（可选） | 纯文本字符串（`<= 200` 字符、无控制字符）；缺省或空串返回时省略 |
| `homepage` / `repository` | 条目（可选） | URL 字符串（`<= 2048` 字符、无空白与控制字符、无 `user:pass@`）；**仅允许 `http://` 或 `https://`**，严禁 `javascript:`、`data:`、`file:` 等危险 scheme |

每次请求重新读取 `catalog.json`，无需修改控制器或重启服务。不存在 `catalog.json` 时返回 `catalogFormatVersion: 1` 与空 `plugins` 列表；磁盘文件缺失的条目不展示。无效 JSON、非法 `catalogFormatVersion`、非法 URL scheme、非法字段类型/路径或重复 `id` 将使该源目录请求失败（fail-closed）。

后端输出响应结构为：

- 顶层：`catalogFormatVersion`、`sourceId`、`sourceType`、`plugins`
- 条目：`id`、`name`、`version`、`contractVersion`、`entryPoint`、`requiredCapabilities`、`description`、`downloadUrl`、`sha256`，以及非空时透传的可选字段 `author`、`homepage`、`repository`、`license`。

---

## 二、静态托管格式（GitHub Pages / Raw 直连）

静态托管没有服务端动态计算 `sha256` 与封装 `sourceId`/`sourceType` 的能力，客户端直接消费的静态 `catalog.json` 必须满足：

```json
{
  "catalogFormatVersion": 1,
  "sourceId": "community-showcase",
  "sourceType": "THIRD_PARTY",
  "plugins": [
    {
      "id": "focus-advice",
      "name": "专注建议",
      "version": "0.1.0",
      "contractVersion": 2,
      "type": "js",
      "entryPoint": "entryPoint",
      "requiredCapabilities": ["QUERY_TODAY", "FEEDBACK"],
      "description": "生成专注建议。",
      "downloadUrl": "./artifacts/plugin-focus-advice-0.1.0.pljs",
      "sha256": "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
      "author": "ORIVA Team",
      "homepage": "https://example.org/focus-advice",
      "repository": "https://github.com/example/focus-advice",
      "license": "MIT"
    }
  ]
}
```

- **不可直接将 `backend/plugins/*/catalog.json` 原样上传为静态页给客户端消费**：源清单使用 `artifact` 且不含顶层 `sourceId`/`sourceType` 与条目 `sha256`；发布到 GitHub Pages / Raw 前须由构建脚本计算并写入 `sha256` 与 `downloadUrl`。
- **相对路径与降级保护**：`downloadUrl` 可使用相对路径（如 `./artifacts/foo.pljs`），客户端会以 `catalog.json` 的 URL 为基准解析，并禁止从 `https://` 目录降级到公网 `http://` 包地址。
- **HTTPS 与信任锚边界**：自定义第三方源在公网仅允许 `https://`（私有内网 `http://` 仅在显式开启 `lanAllowed` 时可用），不得使用保留 `sourceId`（`official` / `third-party`）。**即使静态目录格式完整且 SHA-256 匹配，未配置受信任签名证书指纹（信任锚）的来源仍会被客户端拒绝安装。**

---

## 三、部署与运维纪律

部署先写包文件，再将完整的新 `catalog.json` 通过同文件系统临时文件重命名替换；撤回条目时先更新目录，再移除包。目录内容仅用于发现展示，安装与信任判断仍以包内签名 `plugin.json`、所属来源的信任锚和下载 SHA-256 为准；编辑目录不能代替重新签名。
