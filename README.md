# 知序 ORIVA

**知你所想，序你所行。**

知序 ORIVA 是个人时间助理与通用插件平台。用日程和计划组织想做的事，用执行记录了解时间去向；本地助理提供查询与操作入口，扩展能力通过插件接入。

## 功能

- 日程、计划与提醒，支持本地记录、离线修改和联网补传。
- 执行报告与统计，支持 JSON、CSV、ICS 导出。
- 本地助理：基于应用内数据理解常见时间管理指令。
- 插件工坊：官方和第三方来源，校验下载摘要、签名、来源与申请能力。
- 双插件路线：DEX 插件承载 Android 系统能力，JS/TS 插件运行在 QuickJS 中，并通过宿主契约贡献界面。
- 主题切换、权限引导、账户同步与备份密码管理。

## 技术栈与模块

| 目录 | 职责 |
|---|---|
| `android/app` | Kotlin / Jetpack Compose 应用入口与导航 |
| `android/core-api`、`core-record`、`core-sync` | Retrofit 接口、Room 本地记录、同步与离线补传 |
| `android/core-engine`、`core-dnd`、`core-security` | 执行引擎、勿扰接入、安全与备份密码 |
| `android/core-design`、`feature-*` | 共享设计组件与计划、向导、统计、设置界面 |
| `android/core-plugin`、`plugin-force-blocker` | 通用插件宿主与 DEX 示例 |
| `android/tools/plugin-sdk` | JS/TS 插件 SDK、模板、打包与验证工具 |
| `backend/planlock-*` | Java 21 / Spring Boot / MyBatis：账户、计划、日程、策略、记录与同步 |
| `backend/plugins` | 文件驱动的插件目录及已签名示例包 |

后端使用 MySQL 8、Redis 和 Flyway；Android 最低版本为 Android 7.0（API 24）。

## 下载与联网范围

在 [GitHub Releases](https://github.com/DzDenzel/zhixu-oriva/releases) 下载正式签名 APK、源码 ZIP、`SHA256SUMS` 和公开签名证书。安装前按下文校验。

v2.0.0 APK 使用真实后端接口，默认地址为 `http://10.0.2.2:8080/api/v1/`，面向 Android 模拟器连接宿主机后端。**这不是公网服务地址**：账户、同步和插件下载需要可访问的后端。真机连接自建服务时，按下文用自己的地址构建；本仓库不提供托管服务。

正式发布证书与历史开发版证书不同，不能直接覆盖安装历史 debug 版本。已有数据的用户请先在原版本完成同步或自行备份，并确认恢复方式；不要为安装直接卸载含唯一数据的旧版本。应用包名保持 `com.planlock.app`。

## 构建 Android

需要 JDK 21、Android SDK 36、Build Tools 36.0.0、NDK 27.0.12077973、CMake 3.22.1。Gradle 由仓库 wrapper 固定为 9.0.0，首次构建需要下载依赖。

PowerShell 示例，在仓库根目录执行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21' # 替换为本机 JDK 21 路径
$env:ANDROID_HOME = 'C:/Android/Sdk'            # 替换为本机 SDK 路径
Set-Location android
./gradlew.bat -g .gradle-home testDebugUnitTest lintDebug assembleDebug
./gradlew.bat --stop -g .gradle-home
```

**debug 构建默认使用本地模拟数据接口。连接真实后端须显式附加 `-PPLANLOCK_USE_MOCK=false`**；例如连接自建服务：

```powershell
./gradlew.bat -g .gradle-home assembleDebug -PPLANLOCK_USE_MOCK=false -PPLANLOCK_API_BASE_URL=https://your-server.example/api/v1/
./gradlew.bat --stop -g .gradle-home
```

默认模拟器地址可直接用于本机联调；真机须配置可访问的主机名或局域网地址。release 仅为默认模拟器主机 `10.0.2.2` 放行 HTTP，其他地址须使用 HTTPS；debug 可用于局域网 HTTP 联调。部署时优先使用 HTTPS。`release` 构建强制使用真实接口，生成 unsigned APK：

```powershell
./gradlew.bat -g .gradle-home clean testReleaseUnitTest lintRelease :app:assembleRelease -PPLANLOCK_USE_MOCK=false
./gradlew.bat --stop -g .gradle-home
```

产物位于 `android/app/build/outputs/apk/release/app-release-unsigned.apk`。发布者使用自有 keystore，通过 Android SDK 的 `zipalign` 和 `apksigner` 签名；本仓库不分发 APK 或插件的私钥及口令。自行签名生成的 APK 与官方发布证书不同，不能覆盖官方版本。

## 构建与启动后端

需要 JDK 21、Maven 3.9+、MySQL 8 和 Redis。创建 UTF-8 数据库 `planlock`，准备具备该库权限的用户；在当前终端为环境变量赋值。敏感值请从自己的本地凭据管理方式注入：

| 环境变量 | 含义 |
|---|---|
| `PLANLOCK_DB_USER` | MySQL 用户，默认 `root`；部署时使用专用用户 |
| `PLANLOCK_DB_PASSWORD` | MySQL 口令，必须提供 |
| `PLANLOCK_REDIS_HOST` / `PLANLOCK_REDIS_PORT` | 默认 `localhost` / `6379` |
| `PLANLOCK_REDIS_PASSWORD` | Redis 口令，必须提供；无认证的本地 Redis 可显式设为空值 |
| `PLANLOCK_JWT_SECRET` | 自行生成并妥善保存的随机 JWT 密钥，至少 32 字节；必须提供 |
| `PLANLOCK_PLUGIN_DIRECTORY` | 插件目录，默认相对当前工作目录的 `plugins` |

MySQL 默认连接 `localhost:3306/planlock`。需要替换连接串时设置 Spring Boot 标准环境变量 `SPRING_DATASOURCE_URL`；`SPRING_DATASOURCE_PASSWORD`、`SPRING_DATA_REDIS_PASSWORD` 也可直接覆盖对应配置。

```powershell
Set-Location backend
mvn clean test package
java -jar planlock-app/target/planlock-app-1.0.0.jar
```

保持启动目录为 `backend/`，便可找到仓库中的 `plugins/`。默认监听 8080 端口。后端 Maven 坐标 `1.0.0` 是模块坐标，Android 发布版本为 `2.0.0`。

Flyway 在空库自动执行 V1—V3 迁移。已有数据库接入前先备份，核对结构与迁移历史，明确基线后再启动；不要对存量库直接导入 `schema.sql` 或重复执行旧建表脚本。普通测试不触碰真实数据库；数据库迁移集成测试须按 `backend/planlock-app/scripts/test-f37.ps1` 的显式参数运行。

## 插件开发

JS/TS SDK 需要 Node.js 22+：

```powershell
Set-Location android/tools/plugin-sdk
npm ci
npm run verify
```

包契约与签名操作见 [SDK README](android/tools/plugin-sdk/README.md)，目录部署见 [插件目录说明](backend/plugins/README.md)。DEX 示例打包脚本同样从 `ORIVA_PLUGIN_STOREPASS` 读取签名口令；官方和第三方来源的签名私钥不随源码提供。自行生成的签名不会自动取得既有来源的信任，须配置自己的来源和信任关系。

## 校验发布文件

下载同一 Release 中的 `SHA256SUMS`，先核对 APK 与源码 ZIP 的 SHA-256：

```powershell
Get-FileHash ./zhixu-oriva-2.0.0.apk -Algorithm SHA256
Get-FileHash ./zhixu-oriva-2.0.0-source.zip -Algorithm SHA256
```

或在提供 `sha256sum` 的环境运行 `sha256sum -c SHA256SUMS`。使用 Android SDK Build Tools 校验 APK 签名：

```powershell
apksigner verify --verbose --print-certs ./zhixu-oriva-2.0.0.apk
```

将 `Signer #1 certificate SHA-256 digest` 与 Release 说明中的证书指纹对照。Release 附带的 `.cer` 是公开证书，可用 `keytool -printcert -file oriva-release.cer` 查看；它不包含私钥。

## 许可证

项目自有代码按 [MIT License](LICENSE) 开源。第三方组件保留各自许可：嵌入的 QuickJS 为 MIT（见源码目录 `LICENSE`）；理念词字体 Ma Shan Zheng 子集遵循 SIL Open Font License 1.1（见 `android/app/src/main/assets/licenses/zhixu_motto_OFL.txt`）。其他构建依赖遵循各自许可证。
