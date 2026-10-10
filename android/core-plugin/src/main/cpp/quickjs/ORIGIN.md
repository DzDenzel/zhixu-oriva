# QuickJS 源码来源

固定版本：2026-06-04。引擎源码按 MIT 许可保存，原始版权及许可见本目录 `LICENSE`。

- 官方发布源：https://bellard.org/quickjs/quickjs-2026-06-04.tar.xz
- 官方 C API 文档：https://bellard.org/quickjs/quickjs.html
- 原始压缩包 SHA-256：`b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a`
- 仅保留引擎及其编译依赖；不编译 `quickjs-libc.c`，不注册 `std` / `os` / 文件或网络模块。
- 原始引擎源文件未作修改；JNI 与 Android 构建配置在上级目录。

更新引擎版本时必须更新固定版本与校验和，并复验死循环、内存、栈、Unicode 和能力拒绝测试。SDK 编译目标仍固定为 ES2020。
