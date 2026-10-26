# 部署你自己的知序后端

后端只运行在你自己的机器上。第一次设置会创建一个所有者账号，随后在 App 中用该账号登录。

**安装默认拉取公开的 linux/amd64 镜像，不需要在电脑上安装 Java 或 Maven。也可以选择 `--build` / `-Build` 从源码构建，编译在 Docker 内完成。arm64、NAS 特定型号和公网证书均未实测。**

v2.1.0 客户端支持导入自建后端连接配置、多设备同步和冲突确认。安装包与镜像版本以 GitHub Releases 为准。

## 三步开始

1. 安装并启动 [Docker Desktop](https://docs.docker.com/desktop/)（Windows / macOS），或在 Linux / NAS 上安装 Docker Engine 和 Compose **2.24.4+**。Windows 使用 Linux 容器。首次下载需要联网；安装好后，局域网内使用不需要公网。
2. 从本仓库下载源码 ZIP，解压并打开其中的 `deploy` 文件夹，在该目录执行一条安装命令：

   Linux / macOS / NAS：
   ```sh
   sh install.sh
   ```
   Windows PowerShell：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install.ps1
   ```

   **可选：从源码构建**（须保留 ZIP 中与 deploy 并列的 backend 文件夹）：
   ```sh
   sh install.sh --build
   ```
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install.ps1 -Build
   ```
3. 打开终端显示的初始化页，输入一次性初始化口令，设置实例名称、所有者账号与密码，再确认**手机能访问的地址**。家用主机通常是 `http://192.168.1.你的主机地址:8080`；云服务器应使用 `https://你的域名`。将生成的二维码保存为图片，或复制公开连接配置，进入 App 的「设置 → 后端连接」导入，预览确认后保存，再用所有者账号和密码登录。扫码配置只负责连接，不会替你登录。

请使用 v2.1.0 或更新的 App，v2.0.0 尚无「后端连接」入口。实时摄像扫码依赖系统扫码应用，图片导入和文本粘贴可直接使用。

## 重复运行与口令

重新执行安装命令会复用原 `.env` 和命名卷，不重建账号、不清空数据。初始化成功后，初始化页关闭，一次性口令永久失效；其他设备连接后直接登录原账号。查看尚未使用的一次性口令：

```sh
sh install.sh --show-setup
```
```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -ShowSetup
```

`.env` 自动生成，仅当前用户可读，含数据库、Redis、JWT 和初始化秘密。不要上传、截图或发送给别人。二维码和连接 JSON 只含公开字段，不含密码。若安装被中断，保留 `.env` 后重试；文件不完整时脚本会拒绝启动，请保留原文件后人工核查，切勿删除已有实例的密钥文件来“重置”。

默认只把后端 8080 端口暴露给主机与局域网；数据库和 Redis 不开放主机端口。部署模式禁用公开注册、验证码签发/登录/找回和匿名游客注册，所有者初始化不需要验证码。安装完成会列出本机私网 IPv4 初始化地址候选，由你选择手机能访问的网卡地址；检测失败可用 Linux `ip addr` 或 Windows `ipconfig` 查看。不要把未配置 HTTPS 的服务直接开放到公网。

## 配置

通常只需浏览器初始化。以下项目可按需要编辑 `.env`，随后重跑安装命令；不要改变已有实例的数据库口令或 JWT 密钥。

| 项目 | 用途 |
|---|---|
| ORIVA_IMAGE | 指定已发布版本或 `ghcr.io/…@sha256:…`；默认 `ghcr.io/dzdenzel/zhixu-oriva-backend:2.1.1` |
| ORIVA_PORT | 主机端口，默认 8080；与其他服务冲突时换为 18080 等空闲端口 |
| ORIVA_BIND_ADDRESS | 默认 `0.0.0.0`，允许家中其他设备访问；启用 HTTPS 后仅绑定回环 |
| ORIVA_DOMAIN | 可选 HTTPS 域名，默认空；填写后安装脚本自动加入 Caddy 编排 |
| PLANLOCK_PUBLIC_PLUGINS_CATALOG_URL | 可选独立公共插件目录前缀（HTTPS），空值使用本实例 `/api/v1/plugins/catalog/` |
| ORIVA_TIMEZONE | 默认 UTC，与既有部署一致；已有数据不要随意修改，否则会改变时间解释 |
| COMPOSE_PROJECT_NAME | 默认 `oriva`；同一台主机多套实例须在首次启动前设不同项目名和端口 |

连接地址接受站点根路径或 `/api/v1/`，不接受密码、查询或片段。公网必须 HTTPS；数字私网地址、localhost、`.local`、`.lan` 等局域网主机自动生成 `lanAllowed=true`。手机中的 localhost 指向手机自身，不能代替你的电脑 IP。地址由你确认，初始化页不会信任请求头来推断公网地址。

## 可选：公网 HTTPS

先把域名 A / AAAA 记录指向自己的服务器，并允许入站 TCP 80 / 443（可选 UDP 443）。在 `.env` 填写 `ORIVA_DOMAIN=你的域名`，重跑安装命令。Caddy 会申请证书并将请求转给内部后端，数据库与 Redis 仍不公开。未填域名时不启动代理。需 Compose 2.24.4+ 支持覆盖端口定义。自动证书申请尚未使用真实域名验证。

浏览器初始化时填写 HTTPS 域名。公网机器不要使用 HTTP 提交初始化口令或账号密码；首次配置可通过 SSH 端口转发在 localhost 打开页面，再确认 HTTPS 访问地址。

认证来源默认取实际连接地址。HTTPS overlay 只显式信任 `caddy` 服务解析得到的地址，按 `X-Forwarded-For` 从右向左跳过受信代理，以最近的不受信地址计数；直连请求中的转发头无效。使用其他代理时，在 `.env` 设置 `PLANLOCK_TRUSTED_PROXIES`，逗号分隔填写代理的精确 IP 或内部服务名；不要填客户端地址或整个内网网段。普通 HTTP 部署留空。后端不全局改写请求地址，初始化仍由用户明确确认访问地址。

## 两台设备连接同一实例

1. 只在主机初始化一次，保存生成的公开连接配置或二维码；它不包含所有者密码。
2. 两台设备分别进入「设置 → 后端连接」，导入同一配置并核对实例名与地址。内网场景两台设备都须能访问主机；推荐地址仍须由用户确认，不能使用 Docker 网桥、VMware 仅主机地址或手机的 localhost。
3. 点击「前往登录」，在两台设备分别使用同一个所有者账号与密码登录。登录前本地功能照常使用，登录后才开始同步。
4. 在一台设备创建日程或计划，在另一台下拉刷新；反向再检查一次。主机离线时先保留本地改动，恢复后再次同步。切换实例触发本地数据归属保护时，请回到原实例并登录原账号，勿清除未上传数据。

## 备份与恢复

停止升级、迁移操作后执行备份；数据库使用一致性事务快照，不停用正常读写。输出目录必须是新目录，包含 `database.sql`、`.env` 和 `SHA256SUMS`，**其中有个人数据和全部部署秘密，请离线保管**。Redis 仅为缓存和短期验证码，不在备份中。

```sh
sh backup.sh ../oriva-backup-01
```
```powershell
powershell -ExecutionPolicy Bypass -File .\backup.ps1 -OutputDirectory ..\oriva-backup-01
```

恢复必须用**另一个全新目录和全新 Compose 项目名**；脚本拒绝覆盖已有目录、容器或命名卷，先核对校验和，再导入数据库并启动后端。下例使用 8081，避免影响原实例：

```sh
sh restore.sh ../oriva-backup-01 ../oriva-restored oriva-restored 8081
```
```powershell
powershell -ExecutionPolicy Bypass -File .\restore.ps1 -BackupDirectory ..\oriva-backup-01 -TargetDirectory ..\oriva-restored -ProjectName oriva-restored -Port 8081
```

仅恢复自己生成且可信的备份；校验和检测损坏，不证明来源。恢复保留账号、密码、稳定 instanceId 和业务数据，使用空 Redis 缓存；原公网域名默认不启用，切换 DNS / HTTPS 后再在新目录配置。

**App 当前按完整 API URL 保护本地数据归属。** 恢复到不同 URL 后，应先用一个新的 App 工作区（测试时用隔离安装）登录检查原数据；包含旧实例本地数据的安装不能直接切换到新 URL 接管。正式迁移时保持原访问 URL，例如确认新主机恢复无误后切换原域名 DNS，再在原 App 登录。不要卸载或清除持有唯一未上传数据的 App。稳定 instanceId 暂不代表 App 已支持地址迁移。

## 升级与回退

先备份，再把原部署目录 `.env` 中的 `ORIVA_IMAGE` 改为 `ghcr.io/dzdenzel/zhixu-oriva-backend:2.1.1`（或发布页公布的 digest），然后重跑安装命令。安装脚本沿用已有 `.env`，不会自动切换镜像版本。Flyway 验证迁移历史并只执行新的迁移，失败时后端不会就绪；不要运行 `clean` / `repair` 或删除迁移历史来跳过错误。MySQL DDL 不保证整批迁移原子回滚，失败后应保留现场和日志，必要时把**升级前备份恢复到新项目**；脚本不会声称自动回滚能消除所有部分 DDL。

回退先在新项目恢复升级前备份并固定原镜像，核对数据后再切流；不要直接让旧程序连接已经升级的库。`.env` 随备份固定旧密钥和镜像，原数据库、卷均保留。不得执行 `docker compose down -v` 来“修复”启动问题。

## 日志与排障

在 `deploy` 目录可查看状态和最近日志（分享前先去除账号、地址、SQL 等隐私）：

```sh
docker compose --env-file .env -f docker-compose.yml ps
docker compose --env-file .env -f docker-compose.yml logs --tail 100 backend
```

| 现象 | 检查 |
|---|---|
| 找不到 Docker / engine 未就绪 | 启动 Docker Desktop，选择 Linux 容器；Linux 检查 Docker 服务和当前用户权限 |
| 拉镜像 404 / denied | 核对 `ORIVA_IMAGE` 的仓库与标签；网络无法访问 GHCR 时，使用源码 ZIP 和本地 `--build` / `-Build` |
| 端口已占用 | 在首次启动前修改 ORIVA_PORT，避免占用别的后端；不要结束不属于本实例的进程 |
| 手机连不上电脑 | 同一 Wi-Fi / 网段，使用电脑实际局域网 IP；放行对应端口；关闭访客 Wi-Fi 的设备隔离；检查 VPN |
| 电脑能打开，手机打不开 | 不要把 localhost、127.0.0.1、Docker 内服务名发给手机；云端确认 DNS 与 HTTPS |
| 后端未健康 | 查看 backend / mysql / redis 日志；常见为数据库未就绪、磁盘不足或迁移失败，保留数据和错误记录 |
| 忘记所有者密码 | 目前不提供绕过登录的重置通道；一次性初始化口令不能重开账号，勿删除部署状态表 |
| 换地址后同步暂停 | App 实例归属保护正常触发，按上面的恢复地址说明处理 |

## 费用、版本与安全边界

你承担主机、磁盘、网络和可选域名费用，项目不运营托管服务。公开 GitHub 仓库的标准托管构建通常适用免费额度，GHCR 的策略以 GitHub 当前规则为准。

镜像工作流发布 linux/amd64，并附 `backend-image-manifest`（源码提交、镜像标签 / digest、迁移文件及摘要），可按 digest 固定版本。arm64 尚未构建验证，不能据此宣称全部 NAS 支持。

健康接口 `/api/v1/health` 不需要登录，只报告状态、版本、迁移版本、实例 ID 和初始化状态，不含秘密。镜像通过 `VERSION` 构建参数报告版本，未指定时为 `development`；源码直跑默认为 `2.1.1`。

部署模式禁用公开注册，以及验证码签发、验证码登录和验证码找回；请使用所有者账号密码登录。密码登录与密保答案共用失败预算：同一账号或同一来源地址在 15 分钟窗口内累计失败 5 次，后续尝试暂时拒绝；正确认证清零当前账号与来源的计数。已存在账号按用户 ID 计数，大小写等账号别名不能重置预算。Redis 不可用时拒绝认证，不降级放行。未显式配置受信代理时，来源只使用服务器连接地址；受信代理规则见上面的公网 HTTPS 说明。窗口从首次失败开始计算，拒绝请求不延长窗口。

`/auth/device` 不接受匿名请求创建用户或为已知设备签发令牌；只有有效 Bearer 令牌的用户与设备都匹配既有设备时，才允许更新时间并续发令牌。App 导入后直接进行所有者密码登录；明确的游客注册拒绝会等待登录，不计入同步失败；真实网络故障仍显示可重试状态。源码开发模式的验证码注册、登录、找回与游客行为保持原样。当前无所有者账号密码绕过/找回通道；请妥善保管账号密码和备份。公开部署仍须在反向代理 / 主机层限制资源与访问频率。

发布流水线以 MySQL 8.0.43 实跑迁移与幂等测试，拒绝跳过测试。测试端点可用 `F37_DB_HOST` / `F37_DB_PORT` 注入；测试账号由 `F37_DB_USER` / `F37_DB_PASSWORD` 指定，仅使用可创建/删除隔离测试 schema 的测试实例，勿指向生产数据库。
