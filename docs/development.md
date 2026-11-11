# 本地开发

## 环境

需要 JDK 21、Maven 3.6.3+、Node.js 24、Python 3.11+ 和 Docker Compose。微信调试还需要微信开发者工具与自己的小程序 AppID。

以下 PowerShell 命令均从仓库根目录执行。先按 [README](../README.md#快速开始) 初始化 `deploy/.env`、安装依赖并启动 MySQL。

## 启动各端

每个持续运行的命令使用一个独立终端：

```powershell
# 后端：8080，自动执行 Flyway 迁移
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-backend.ps1
```

```powershell
# 管理端：5173
npm --prefix admin-web run dev
```

```powershell
# 居民 / 维修 H5：5174
npm --prefix miniapp run dev:h5
```

后端健康检查为 `http://localhost:8080/actuator/health`；开发脚本启用 Swagger，可访问 `http://localhost:8080/swagger-ui/index.html`。修改 Java 或环境配置后需重启后端。

管理端和 H5 使用 `/api` 同源代理，默认转发到 `http://127.0.0.1:8080`。使用网关时，在启动前端的终端设置 `$env:VITE_PROXY_TARGET='http://127.0.0.1:8081'`。

本地脚本启用演示模式和 `.local/uploads` 私有文件存储，不要求启动 MinIO、Redis 或 RabbitMQ。不要删除已有 `.env` 或数据库卷来重启项目，以免凭据与已有数据不一致。

## 账号

初始管理员名取自 `BOOTSTRAP_USERNAME`（默认 `admin`），密码取自 `BOOTSTRAP_PASSWORD`。演示账号行为以 `Bootstrap.java` 为准，可配置独立的 `DEMO_PROPERTY_PASSWORD`、`DEMO_RESIDENT_PASSWORD`、`DEMO_WORKER_PASSWORD`。

居民和维修人员可在移动端注册；物业可管理负责小区内的维修账号。微信首次登录身份与账号密码注册的账号不会自动合并。仓库不分发真实测试账号表。

## 微信小程序

1. 在 `miniapp/src/manifest.json` 的 `mp-weixin.appid` 填写自己的 AppID。
2. 在 `deploy/.env` 配置相同的 `WECHAT_APP_ID` 和后端专用的 `WECHAT_APP_SECRET`，重启后端。
3. 设置手机能访问的接口地址，重新构建：

```powershell
# localhost 仅适用于电脑模拟器；手机预览须替换为可达地址
$env:VITE_API_BASE='http://localhost:8080/api'
npm --prefix miniapp run build:mp-weixin
```

微信开发者工具导入 `miniapp/dist/build/mp-weixin`。持续开发可运行 `dev:mp-weixin`，导入 `miniapp/dist/dev/mp-weixin`。两种输出目录不同。

手机预览时，`localhost` / `127.0.0.1` 指向手机自身。改用同局域网可达的电脑地址，或已部署的服务器公网地址。地址在构建时写入，修改环境变量后必须重新构建和扫码。

临时 HTTP 调试需在开发者工具启用不校验合法域名、TLS 和 HTTPS 证书，并在手机预览中开启调试模式；这不是普通用户发布方案。公网调试步骤见 [部署指南](deployment.md)。

## 完整 Spring Cloud 模式

保留 MySQL 运行后，在不同终端分别启动：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 registry
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 backend
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 gateway
```

以上每条命令各占一个终端，端口分别为 8761、8080、8081。前端代理及微信 API 地址改用网关的 8081 端口。也可直接使用 [完整容器部署](deployment.md)。
