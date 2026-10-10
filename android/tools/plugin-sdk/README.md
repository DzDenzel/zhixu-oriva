# ORIVA 官方 TypeScript 插件 SDK

SDK 为契约 v2 的 JS/TS 插件提供类型、字符串桥包装、ES2020 单文件 esbuild 模板及真实签名示例。插件在 QuickJS 中运行，界面由宿主根据贡献声明渲染。同进程边界仍为约定边界，不宣称安全沙箱。

## 1. 构建与复验

Node.js 22+；依赖版本固定在 package-lock.json。以下命令在本目录执行：

```powershell
npm ci
npm run verify
```

模板输出 `dist/plugin-focus-advice-0.1.0.unsigned.pljs`，尚不能安装。使用已授权插件 keystore 签名，密码通过环境变量 `ORIVA_PLUGIN_STOREPASS` 提供，不写入命令输出和证据：

```powershell
powershell -NoProfile -File tools/sign-package.ps1
node tools/validate.mjs dist/plugin-focus-advice-0.1.0.pljs
```

签名脚本默认使用 `android/.plugin-signing/planlock-plugin-dev.p12`（official）。第三方开发者须提供自己来源对应的 keystore、alias，不能使用官方来源冒充可信插件。SDK `validate` 仅检查结构/JS 静态入口；真实安装复用宿主的 JAR 全 payload 验签及来源信任锚。`META-INF` 文件存在不能证明签名有效。

## 2. 包与入口契约

| 项目 | 约定 |
|---|---|
| 包 | `.pljs` ZIP/JAR；`plugin.json` + `index.js`；可选 `assets/`；签名产生 `META-INF/` |
| manifest | `contractVersion: 2`、`type: "js"`、`entryPoint: "entryPoint"` |
| bundle | esbuild target 固定 `es2020`，IIFE，末尾全局 `function entryPoint(event,host)` |
| 入参 | event.kind 为 `load/action/decision`；普通 action 的 fieldId/value 可为 null；表单变更事件含 planId、actionId、fieldId、value:string |
| native host | 只有 `host.postMessage(requestJSON:string):string`；不暴露网络/文件/native Context |
| 返回 | 同步 JSON 可序列化值；示例 `{message,snapshot}`，message 用于界面反馈 |
| 兼容 | 宿主继续支持原契约 v1 DEX；此工具只生成 v2 JS，不重新打包 DEX |

最小入口：

```ts
import { createHost } from '@oriva/plugin-sdk';
import type { PluginEvent, Host } from '@oriva/plugin-sdk';
export function entryPoint(event: PluginEvent, rawHost: Host) {
  const host = createHost(rawHost);
  const snapshot = host.query.today();
  return { message: `今日有 ${snapshot?.arrangements.length ?? 0} 项安排`, snapshot };
}
```

## 3. 消息与能力

请求 `{op,args}` 经 JSON.stringify 后发送，响应字符串解析为 `{ok:true,data}` 或 `{ok:false,error:string,code?:string}`。错误成为 `HostError`；未声明能力始终由宿主拒绝，SDK 不授予权限。

| 方法 | op / args | 必须声明 |
|---|---|---|
| query.today(date?) | query.today / `{date?}`；省略日期由宿主选今日 | QUERY_TODAY |
| query.plan(id) | query.plan / `{id:number}`；非零安全整数，负数为本地离线计划 | QUERY_PLAN |
| query.report(period) | query.report / `{period:day/week/month}` | QUERY_REPORT |
| overlay(text,displayMillis?) | overlay / `{text,displayMillis?}` | OVERLAY |
| home() / haptic() | home / haptic，args `{}` | HOME / HAPTIC |
| feedback(code) | feedback / `{code:string}` | FEEDBACK |

today 返回宿主 AssistantSnapshot 同源的 `{today,arrangements,plans,continuousDays?,todayUnlocks?}`；plan 返回 Room 计划缓存，report 返回统计缓存或 null。查询不触发网络。动作还须真实宿主能力 delegate 可用；界面回调可以返回 message，在 FEEDBACK delegate 不可用时也由宿主展示 Snackbar。被动 load 不应发动作。

## 4. 贡献与专注建议示例

既有 `StatusLine`、`ActionRow`、`CapabilityRequest` 仍走 `plan.detail.protection`。编辑页配置新增 `FormSection`：

```json
{
  "kind": "FormSection", "id": "settings", "slot": "plan.edit.configuration", "title": "配置",
  "fields": [{"id":"enabled","kind":"switch","label":"启用","value":true,"onEvent":"enabled.changed"}]
}
```

每字段有 id/label/onEvent；同一 manifest 全部表单字段 id 必须唯一。switch.value 为 boolean；text.value 为 string；choice.value 为 options 内某值，options 为 `[{value,label}]`。宿主渲染、保存配置并将 onEvent 映射为 actionId；变更事件 value 总是 string。后续 action/decision 的 configuration 包含该用户、计划、插件的已保存值（本次表单事件包含提议值），使配置真正影响插件行为。

`focus-advice` 示例有详情页“运行专注建议”按钮，以及开关、建议风格、专注提示语三种字段。按钮读取真实今日投影生成建议；修改配置返回“已保存…”反馈与只读快照；配置持久化由宿主负责。未装/未启插件时空插槽不出现。

## 5. 验证边界与版本

SDK 本地测试覆盖类型拒绝、七种消息 exact args、畸形响应、能力拒绝、ZIP 路径/重复条目、入口静态校验、真实打包脚本执行及配置反馈。作者预览是 Node 的真实 bundle + 本地投影夹具，不能代替设备 UI、QuickJS 资源上限或宿主缓存同源验收。

| 版本 | 修订 |
|---|---|
| 0.1.0 | REQ-028 首个官方本地 SDK；统一 plugin.json、字符串桥、v2 三类表单、签名工具与完整示例 |
