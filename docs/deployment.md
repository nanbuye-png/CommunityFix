# 服务器部署

## 部署内容

主配置 `deploy/compose.yml` 启动 MySQL、Redis、RabbitMQ、MinIO、Eureka、业务服务、网关和 Nginx，仅 Nginx 的 HTTP 端口对外映射。管理端静态文件由 Nginx 提供；小程序需要单独编译并导入微信开发者工具。

Java 服务使用 Docker 多阶段构建，不要求服务器安装 JDK。管理端需要 Node.js 24 构建，可在构建机执行后把 `admin-web/dist` 传到服务器同一路径；该目录不提交 Git。

## 首次部署

以下命令使用 Linux shell，在仓库根目录执行。服务器需安装 Git、Docker Compose；本机构建管理端时还需 Node.js 24。

```sh
git clone https://github.com/nanbuye-png/CommunityFix.git
cd CommunityFix
cp deploy/.env.example deploy/.env
```

编辑 `deploy/.env`：

- 替换所有 `replace-with-...` 密码与密钥，占位值不可直接用于部署。
- 设置 `BOOTSTRAP_USERNAME` 和 12～72 字符的初始管理员密码。
- 根据实际入口设置 `WEB_PORT` 和 `CORS_ORIGINS`，例如 `http://服务器公网IP:8088`。
- 关闭 `DEMO_ENABLED`、`DEMO_SYNC_PASSWORDS`、`BILLING_MOCK_ENABLED`。专用演示环境可按需启用模拟流程。
- 需要微信登录时设置真实 `WECHAT_APP_ID` / `WECHAT_APP_SECRET`；AI 配置可暂时留空。

```sh
npm --prefix admin-web ci
npm --prefix admin-web run build
docker compose --env-file deploy/.env -f deploy/compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/compose.yml ps
```

构建需要访问 Maven 仓库和容器镜像源；拉取失败应先处理网络，不要把部分启动视为成功。确认管理端产物已存在再启动 Nginx，否则页面无法提供。

云安全组和主机防火墙放通实际 `WEB_PORT`。通过浏览器访问 `http://服务器公网IP:端口` 登录管理员，并检查基础数据及 API。排障时读取对应服务日志：

```sh
docker compose --env-file deploy/.env -f deploy/compose.yml logs --tail=100 backend gateway nginx
```

主配置默认不暴露 MySQL、Redis、RabbitMQ、MinIO、Eureka 或后端端口。`compose.dev.yml` 用于本机联调，不是公网部署的必需文件。

## 公网 IP 微信调试

可以先使用 HTTP 公网接口做开发调试，不要求手机与服务器处于同一局域网。接口地址示例为 `http://服务器公网IP:8088/api`，以真实映射端口为准。

在开发机 PowerShell 中：

```powershell
$env:VITE_API_BASE='http://服务器公网IP:8088/api'
npm --prefix miniapp run build:mp-weixin
```

先把示例中的中文占位地址换成真实 IP。配置小程序 AppID 与后端保持一致，导入 `miniapp/dist/build/mp-weixin`，启用开发者工具“不校验合法域名、TLS 版本及 HTTPS 证书”，手机预览也开启调试模式，再测试登录。后端还必须能向微信接口发起 HTTPS 请求。

HTTP 调试使用测试数据。这种方式不等于普通居民可直接使用的发布环境；正式发布仍需配置 HTTPS、有效证书与微信合法域名。Compose 当前不提供自动签发证书或 TLS 终止，需要部署者在入口补充。

## 更新与恢复

1. 记录当前提交版本，备份配置、数据库和附件，并验证备份可恢复。
2. 更新源码，重新安装锁定依赖并构建管理端。
3. 执行 `docker compose ... up -d --build` 重建服务；后端启动自动执行 Flyway 迁移。
4. 检查日志、管理员登录、工单查询、附件访问及微信真机流程。

不要修改已经应用的 Flyway 迁移，也不要通过删除数据卷处理启动错误。数据库迁移不保证向后兼容，回退代码前需检查数据库兼容性，必要时按验证过的备份方案恢复。

[本地文件备份](backup-restore.md) 适用于本地存储模式；MinIO 与 MySQL 协调恢复流程见 [完整容器验收](local-acceptance.md)。这些脚本不能替代生产备份调度、外部备份保留和实际恢复演练。
