# 插件目录部署格式

目录数据由每个来源下的 UTF-8 `catalog.json` 提供，平台代码不登记插件实例。`planlock.plugins.directory` 指向本目录，默认值 `plugins` 相对服务启动目录解析。支持现有 `official` / `third-party` 两个来源，来源身份由 URL 路由决定。

```json
{
  "plugins": [
    {
      "artifact": "example-1.0.0.plp",
      "id": "example",
      "name": "示例",
      "version": "1.0.0",
      "contractVersion": 1,
      "entryPoint": "example.Plugin",
      "requiredCapabilities": [],
      "description": "插件说明"
    }
  ]
}
```

| 字段 | 约束 |
|---|---|
| `plugins` | 数组；空数组表示该源无目录项 |
| `artifact` | 来源目录下文件名，只含 ASCII 字母、数字、点、下划线、连字符；不得为 `.` / `..` 或带路径 |
| `id` / `name` / `version` / `entryPoint` | 非空字符串；同源 id 唯一 |
| `contractVersion` | 正整数；安装端仍负责支持版本校验 |
| `requiredCapabilities` | 非空字符串组成的数组，可为空；安装端负责能力契约校验 |
| `description` | 字符串，可为空 |

每次请求重新读取文件，无需改控制器或重启服务。不存在 `catalog.json` 返回空列表；文件缺失的条目不展示。无效 JSON、字段类型或路径、重复 id 导致该源目录请求失败，避免静默生成不可用条目。额外字段不会改变当前 API 返回形状。

响应继续是 `sourceId` / `sourceType` / `plugins`；每条仅返回 `id` / `name` / `version` / `contractVersion` / `entryPoint` / `requiredCapabilities` / `description` / `downloadUrl` / `sha256`。下载路径与实际文件 SHA-256 由服务计算，清单不能指定任意下载 URL 或摘要。下载端点保持原路径校验。

部署先写包文件，再将完整的新 `catalog.json` 通过同文件系统临时文件重命名替换；撤回条目时先更新目录，再移除包。目录内容是发现信息，安装和信任判断仍以包内签名清单、所选来源的信任锚和下载摘要为准；编辑目录不能代替重新签名。既有官方目录描述与签名清单描述不同，当前保留原展示内容，不改签名包。

开发声明样例只能证明目录发现、搜索和下载完整性，不能作为签名包安装证据。目录服务不从包内读取 DEX，不执行插件，也不引入数据库或热更新缓存。
