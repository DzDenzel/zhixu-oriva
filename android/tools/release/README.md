# 可复用发布工具

默认从当前仓库的 `main` 构建，无克隆步骤。Python 3.10+，其余依赖与仓库根 README 一致。本工具不创建密钥，不读取内部知识库，不配置 CI。

## 每次发布

1. 在同一主仓库完成修改与验收，更新 `android/app/build.gradle.kts` 的 `versionName`、递增 `versionCode`，提交所有公开文件。首次发布也使用这个仓库。
2. 预览命令，核对版本、仓库位置和输出目录：

```powershell
python android/tools/release/release.py plan
```

3. 当前 PowerShell 配置本机工具路径。下列目录仅为示例，按实际安装位置替换：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:ANDROID_HOME = 'G:/SDK'
$env:PATH = 'G:/Node/Node22;G:/Maven/apache-maven-3.9.10/bin;' + $env:PATH
```

后端所需 `PLANLOCK_DB_PASSWORD`、`PLANLOCK_REDIS_PASSWORD`、`PLANLOCK_JWT_SECRET` 从自己的本地凭据管理方式注入。不要将口令写进脚本或提交仓库。

```powershell
python android/tools/release/release.py build
```

需要公网后端时，在 build 命令附加 `--api-base-url https://your-server.example/api/v1/`。不传则沿用项目的模拟器默认值，不能当作公网包。build 执行 Android 单测/lint/release、后端测试/打包和 SDK 验证，使用本仓库 `android/.gradle-home`，并在 Android 构建结束时显式停止同一 home 的 daemon。测试范围、跳过项和警告仍需在发布报告中说明。

4. 在当前会话配置已有正式密钥的路径与参数（密钥位于仓库外或被忽略的私有目录）：

- `ORIVA_RELEASE_KEYSTORE`：已有 keystore 的绝对路径。
- `ORIVA_RELEASE_KEY_ALIAS`：原发布 alias。
- `ORIVA_RELEASE_STOREPASS`：由本地凭据管理方式注入，不写入命令行参数。
- `ORIVA_RELEASE_CERTIFICATE`：对应的公开 DER `.cer` 文件路径。
- `ORIVA_RELEASE_CERT_SHA256`：原发布证书 SHA-256 指纹。

```powershell
python android/tools/release/release.py package
```

package 检查工作区、构建 commit 和 unsigned APK 摘要，执行 zipalign、签名、实际 APK 版本/非 debug/真实接口/证书检查，审计并从同一 commit 归档源码，生成清单、校验和与独立留存。私钥与口令不进入资产。相同版本已打包时拒绝覆盖；后续新版本复用原密钥，保证签名一致。

5. 写本版本发布说明，包含功能范围、后端限制与升级说明。获得公开分发授权后执行：

```powershell
python android/tools/release/release.py publish --notes work/releases/v2.0.1/notes.md
python android/tools/release/release.py verify
```

`v2.0.1` 是示例，目录应匹配当前 Android 版本。默认目标 `DzDenzel/zhixu-oriva`，origin 必须与目标完全一致；如用于其他仓库，使用 `--repo owner/repo`。publish 仅推当前 main 和当前版本 tag，从不 force push，不覆盖旧 tag 或已公开资产。既有 main 可正常快进推送。遇到非快进错误，先由开发者解决分支差异。

中断后直接重跑同一 publish 命令：优先按保存的 Release ID 恢复；状态丢失时通过授权分页列表发现同 tag 草稿。按远端 tag/Release 实际状态恢复，同摘要已上传资产复用，不同摘要或额外资产停止。全部资产成功才公开草稿。已公开且完全一致时核验后结束；修改或补充已公开 Release 内容需另行处理，不静默替换。

## 克隆与证据

- 默认 verify：匿名下载五件资产、核对服务端摘要/本地字节、比较源码 ZIP 与提交归档，并核对公开 tag；不克隆。
- 首次发布或 README/构建链显著变化时：显式 `verify --clone-check`。只创建本版本接收者验证副本；已有副本则校验复用。该步骤不修改主仓库，也不从验证副本发布。按 README 的接收者构建复验另行执行，不把文件比对称作构建成功。
- 产物：`release/github-v<version>/`；日志、构建记录、留存和下载证据：`work/releases/v<version>/`。这些目录均不入库。Windows批处理参数不支持双引号、换行或百分号字符，工具会明确拒绝；含空格路径和字面 & 参数支持。每个版本独立，不覆盖历史。
- 同一主仓库串行操作，由 `work/releases/.release.lock` 保护；进程被强制结束后锁可能残留。先检查锁中的 PID 和相关进程，确认本轮已停止后再移除锁，禁止在活动构建中移除。
- 旧日期脚本和旧克隆仅作历史证据，不作为新版本发布入口。

## 工具回归测试

```powershell
python -m unittest discover -s android/tools/release -p test_release.py -v
```

测试在临时本地 Git 仓库执行；GitHub、上传和远端推送由测试替身模拟，不公开任何数据。公网连通、真实签名构建和实际发布需要独立执行相应步骤。
