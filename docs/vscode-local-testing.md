# 在 VS Code 启动前后端和微信小程序

2026-10-29 更新：H5 支持两个标签页分别登录。居民入口为 http://localhost:5174/#/pages/login/index?role=RESIDENT ，维修入口为 http://localhost:5174/#/pages/login/index?role=WORKER 。更新后重启后端执行 V5 迁移，并重新登录。物业可在“维修人员”页面直接创建账号；权限和验证详情见 [双身份与维修人员管理说明](worker-management-2026-10-29.md)。

适用环境：Windows + PowerShell。以下路径按当前项目位置 `D:\chatgpt-project\CommunityFix` 编写。

日常功能测试先用单业务服务模式：Docker 运行 MySQL，VS Code 分别运行后端、管理后台和小程序编译。需要验证注册发现和网关时，再使用本文第 8 节的完整 Spring Cloud 模式。

## 1. 打开项目并检查环境

在 VS Code 中选择“文件 → 打开文件夹”，打开：

```text
D:\chatgpt-project\CommunityFix
```

选择“终端 → 新建终端”，使用 PowerShell。后面每个持续运行的服务都单独占一个终端，可将终端重命名为“后端”“管理后台”“微信编译”。新终端的工作目录和环境变量可能与前一个不同，因此下面每组命令都显式设置目录。

首次使用时检查：

```powershell
Set-Location D:\chatgpt-project\CommunityFix
java -version
mvn.cmd -version
node --version
npm --version
python --version
docker compose version
```

项目使用 JDK 21、Maven 3.6.3 及以上、Node.js 24、Python 3；Docker Desktop 需要已启动。修改 PATH 或安装依赖工具后，重新打开 VS Code 终端；必要时重启 VS Code。

另外需要微信开发者工具。VS Code 负责修改代码和编译小程序，微信开发者工具负责运行微信模拟器、预览和真机调试。

## 2. 准备配置、数据库和前端依赖

先在一个空闲终端执行：

```powershell
Set-Location D:\chatgpt-project\CommunityFix
python deploy/init-dev.py
docker compose --env-file deploy/.env -f deploy/compose.yml -f deploy/compose.dev.yml up -d mysql
docker compose --env-file deploy/.env -f deploy/compose.yml -f deploy/compose.dev.yml ps mysql
```

等待 MySQL 的状态包含 `healthy`，再启动后端；如果仍在初始化，稍后重新执行最后一条命令。MySQL 映射到 `127.0.0.1:3307`。

`init-dev.py` 只在配置不存在时生成 `deploy/.env`，已有文件会原样保留。用 VS Code 打开这份文件，查看 `BOOTSTRAP_PASSWORD`，供后面的演示账号登录使用。它同时保存数据库和供应商凭据，不要复制到前端源码或提交到仓库。

已有数据库不会因重新启动而重置。不要为了重新启动而删除 `.env` 或 Docker 数据卷；修改 `.env` 的数据库密码也不会自动修改已有 MySQL 用户密码。

首次安装，或 `package-lock.json` 更新后，分别安装两端依赖：

```powershell
Set-Location D:\chatgpt-project\CommunityFix\admin-web
npm ci

Set-Location D:\chatgpt-project\CommunityFix\miniapp
npm ci
```

依赖已安装且锁文件没有变化时，可直接跳到下一节。本地单业务服务模式使用 `.local/uploads` 保存私有图片，不需要启动 MinIO、Redis 或 Nginx。当前 MinIO 镜像代理的 403 不阻碍这一模式，因此这里仅启动 Compose 的 `mysql` 服务。

## 3. 终端一：启动业务后端

```powershell
Set-Location D:\chatgpt-project\CommunityFix
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-backend.ps1
```

保持终端运行。脚本会加载 `deploy/.env`，连接开发数据库、启用演示模式及本地图片存储，并关闭 Eureka 注册。首次运行需要下载 Maven 依赖，数据库迁移由 Flyway 自动执行。

在浏览器打开 [后端健康检查](http://localhost:8080/actuator/health)，确认返回 `"status":"UP"`。此模式的 API 基地址是：

```text
http://localhost:8080/api
```

也可打开 [Swagger UI](http://localhost:8080/swagger-ui/index.html) 查看接口。运行受保护接口时，需要先通过登录接口取得 Token，再在 Authorize 中填写。

修改 Java 代码或 `deploy/.env` 后，在该终端按 `Ctrl+C` 停止，再重新执行启动命令。当前启动方式没有配置 Java 自动热重载。不要再从 Java 插件重复启动同一个后端，以免占用相同端口；直接点击 Java 主类运行也不会自动执行本项目脚本中的环境配置加载。

## 4. 终端二：启动管理后台

另开 PowerShell 终端：

```powershell
Set-Location D:\chatgpt-project\CommunityFix\admin-web
$env:VITE_PROXY_TARGET = 'http://127.0.0.1:8080'
npm run dev
```

打开 [管理后台](http://localhost:5173)。当前管理后台默认代理到单业务服务的 `8080`，上面的环境变量用于明确指定目标；完整 Spring Cloud 模式需要显式改成 `8081`。开发服务同时监听本机和局域网地址，端口被占用时直接提示，不会悄悄换成其他端口。

| 账号 | 角色 | 测试用途 |
| --- | --- | --- |
| `property` | 物业管理员 | 房屋绑定审核、工单审核、派单、投诉、统计 |
| `admin` | 系统管理员 | 用户、角色、小区授权、微信通知投递管理 |
| `resident` | 居民 | 在小程序或 H5 提交、查询及验收报修 |
| `worker` | 维修人员 | 在小程序或 H5 接单、开始维修、提交结果 |
| `resident2` / `property2` | 其他居民 / 其他小区物业 | 数据权限隔离测试 |

当前本机四种身份的账号和密码见 [测试账号表](test-accounts.example.md)：密码分别为 `<LOCAL_ROLE_PASSWORD_0>`、`<LOCAL_ROLE_PASSWORD_1>`、`<LOCAL_ROLE_PASSWORD_2>`、`<LOCAL_ROLE_PASSWORD_3>`。启用 `DEMO_SYNC_PASSWORDS=true` 时，修改 `deploy/.env` 中对应角色的密码配置并重启后端，会同步既有演示账号密码并撤销旧会话；关闭同步则保留页面中单独修改的密码。所有密码至少 12 个字符，不合规时后端启动失败。业务数据不会被重建。`admin` 不代替 `property` 审核居民房屋申请。

管理后台页面修改后由 Vite 更新；修改 `VITE_PROXY_TARGET` 后需要停止并重新运行 `npm run dev`。

## 5. 终端三：编译并运行微信小程序

### 5.1 配置微信 AppID

确认 `miniapp/src/manifest.json` 中 `mp-weixin.appid` 是你的小程序 AppID。已有正确配置时保留原值。

测试真实微信登录时，还需要在 `deploy/.env` 配置相同的 `WECHAT_APP_ID` 和对应 `WECHAT_APP_SECRET`，然后重启后端。AppSecret 只保存在后端配置中。只测试报修业务时，可以先在小程序登录页切换到“使用账号登录”，使用 `resident` 或 `worker`。

### 5.2 开发监听模式：改代码后自动重新编译

新开 PowerShell 终端，先使用电脑模拟器地址：

```powershell
Set-Location D:\chatgpt-project\CommunityFix\miniapp
$env:VITE_API_BASE = 'http://127.0.0.1:8080/api'
npm run dev:mp-weixin
```

等待首次编译完成，保持终端运行。在微信开发者工具中导入以下目录，并使用相同 AppID：

```text
D:\chatgpt-project\CommunityFix\miniapp\dist\dev\mp-weixin
```

不要导入 `miniapp/src` 或项目根目录。打开开发者工具“详情 → 本地设置”，本地 HTTP 调试时勾选“不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书”相关选项，然后编译运行。开发监听会更新产物；若模拟器没有刷新，可点击开发者工具中的“编译”。

### 5.3 一次性构建模式

如果希望编译一次后关闭终端，使用：

```powershell
Set-Location D:\chatgpt-project\CommunityFix\miniapp
$env:VITE_API_BASE = 'http://127.0.0.1:8080/api'
npm run build:mp-weixin
```

这次需要导入的目录是：

```text
D:\chatgpt-project\CommunityFix\miniapp\dist\build\mp-weixin
```

`dev:mp-weixin` 对应 `dist/dev`，`build:mp-weixin` 对应 `dist/build`。切换模式后，确认开发者工具打开的是对应产物，避免一直运行旧包。

两种微信编译命令都要求在运行它们的同一个终端里显式设置完整的 `VITE_API_BASE`，且以 `/api` 结尾。构建前置检查直接读取进程环境变量，不能仅依赖 Vite 稍后加载的 `.env`。环境变量也不会自动传到另一个已经打开的终端。

## 6. 手机真机测试

`127.0.0.1` 在手机上指向手机自己。真机测试要改成手机能访问的电脑局域网 IPv4。

1. 让手机和电脑连接可互访的同一网络。校园网若隔离设备，可以尝试让电脑连接手机热点。
2. 在 VS Code 空闲终端运行 `ipconfig`，找到当前联网网卡的 IPv4。不要使用 Docker/WSL 虚拟网卡地址，也不要直接沿用以前的 IP。
3. 保持后端运行，先用手机浏览器访问 `http://电脑IPv4:8080/actuator/health`，确认看到 `UP`。
4. 停止小程序编译终端，在同一个终端修改 API 地址并重新启动编译。

例如电脑当前 IPv4 是 `192.168.1.23`，命令为：

```powershell
Set-Location D:\chatgpt-project\CommunityFix\miniapp
$env:VITE_API_BASE = 'http://192.168.1.23:8080/api'
npm run dev:mp-weixin
```

这里的 `192.168.1.23` 只是示例，务必换成你当前的电脑地址。使用一次性构建时，最后一条命令改成 `npm run build:mp-weixin`，并打开相应的 `dist/build/mp-weixin`。

在微信开发者工具重新编译后，使用真机调试或生成新的预览包；本地 HTTP 调试需在当前真机调试环境允许跳过服务器域名校验。电脑 IP 或 API 端口改变后，需要重启编译任务并重新进入对应新包。

手机健康检查无法访问时，先处理网络互访和 Windows 对 Java 的入站访问；开发者工具的“不校验合法域名”不能解决网络不通。应从你本机 VS Code 的普通 PowerShell 终端启动后端。此前出现过受限执行环境内启动后端时电脑自访正常、手机无法连接的情况，因此以手机浏览器的实际结果为准。

以上是本地开发联调。正式小程序发布需要可访问的 HTTPS API，以及小程序后台配置的 request/uploadFile/downloadFile 合法域名。

## 7. 可选：在浏览器启动移动端 H5

不方便反复切换微信账号时，可以用 H5 测试居民或维修人员页面。另开终端：

```powershell
Set-Location D:\chatgpt-project\CommunityFix\miniapp
$env:VITE_API_BASE = '/api'
$env:VITE_PROXY_TARGET = 'http://127.0.0.1:8080'
npm run dev:h5
```

打开 [移动端 H5](http://localhost:5174)，点击“使用账号登录”。H5 固定默认使用同源 `/api` 代理，不受为微信构建设置的 `VITE_API_BASE` 影响，上面的 `/api` 设置可省略。若确实需要 H5 跨域直连，可单独设置 `VITE_H5_API_BASE`；微信编译继续使用完整的 `VITE_API_BASE`。H5 可用于业务页面测试，真实微信登录、订阅授权和微信环境行为仍在微信开发者工具或手机中测试。

浏览器后台和 H5 均支持 `localhost`、`127.0.0.1` 和电脑当前局域网 IPv4。通过 Vite 同源 `/api` 代理访问时无需逐个添加局域网 IP 的 CORS 配置；若显式配置 H5 跨域直连，需让后端 `CORS_ORIGINS` 包含实际页面来源，并重启后端。

## 8. 可选：完整 Spring Cloud 模式

验证注册中心和网关时，先用 `Ctrl+C` 停止第 3 节的单业务后端，然后分别在三个 PowerShell 终端启动以下命令。每个终端先执行 `Set-Location D:\chatgpt-project\CommunityFix`：

```powershell
# 终端 A：注册中心，等待 http://localhost:8761/actuator/health 返回 UP
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 -Service registry
```

```powershell
# 终端 B：向 Eureka 注册的业务后端，等待 8080 健康检查返回 UP
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 -Service backend
```

```powershell
# 终端 C：网关，等待 http://localhost:8081/actuator/health 返回 UP
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 -Service gateway
```

再在空闲终端执行连通性检查，等待注册发现就绪：

```powershell
Set-Location D:\chatgpt-project\CommunityFix
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/verify-cloud.ps1
```

确认输出 `PASS` 后，将管理后台和 H5 的 `VITE_PROXY_TARGET` 改成 `http://127.0.0.1:8081`，重启对应 Vite 终端；微信小程序的 `VITE_API_BASE` 改成 `http://127.0.0.1:8081/api`，真机则使用 `http://电脑IPv4:8081/api`，再重新编译。

| 模式 | 后端进程 | 管理端/H5 代理 | 小程序模拟器 API |
| --- | --- | --- | --- |
| 单业务服务 | backend | `http://127.0.0.1:8080` | `http://127.0.0.1:8080/api` |
| 完整 Spring Cloud | registry + backend + gateway | `http://127.0.0.1:8081` | `http://127.0.0.1:8081/api` |

不要同时启动两份占用 `8080` 的业务后端。仅网关健康为 `UP` 不表示业务实例已经完成注册，需结合 `verify-cloud.ps1` 的结果判断。

## 9. 建议先完成的一轮业务测试

1. 管理后台用 `property` 登录，确认工作台、房屋绑定列表和工单列表能正常加载。
2. 小程序用 `resident` 账号登录，提交一张测试报修。若使用真实微信新用户，先申请房屋绑定，再由 `property` 审核；新微信身份不会自动继承演示居民的房屋。
3. 在管理后台审核工单并派给演示维修人员。
4. 在 H5 或小程序切换到 `worker`，依次接单、开始维修、提交维修结果。
5. 回到原报修居民，验收并评价；查看工单状态、记录及站内通知。也可再用一张工单验证返修流程。

需要测试 AI 建议时，在 `deploy/.env` 配置 `AI_ENDPOINT`、`AI_API_KEY`、`AI_MODEL` 并重启后端；未配置或调用失败时仍可人工填写报修。

微信订阅通知另外需要 `deploy/.env` 的模板配置，以及 `miniapp/.env` 中相同的公开 `VITE_WECHAT_ORDER_TEMPLATE_ID`，配置后重启后端和小程序编译。居民要先在小程序“我的”页主动接受订阅；当前只在维修人员接单时向原报修居民发送微信模板通知，其他状态通过站内消息查看。详细配置见 [README 的微信小程序说明](../README.md#微信小程序)。

## 10. 常见问题和停止方式

| 现象 | 优先检查 |
| --- | --- |
| 找不到 `java`、`mvn.cmd`、`node`、`npm` 或 `python` | 工具是否安装并加入 PATH；重新打开 VS Code 和终端 |
| 提示禁止运行 `npm.ps1` | 将示例的 `npm` 改为 `npm.cmd`，无需修改系统执行策略 |
| Docker 无法连接或找不到 Compose | 确认 Docker Desktop 引擎已就绪，执行 `docker compose version` |
| 后端启动报数据库连接或认证失败 | MySQL 是否 healthy、是否使用 3307、`.env` 凭据是否与已有数据卷一致 |
| 提示端口已被占用 | 检查旧 VS Code 终端、Java 运行按钮或其他后台服务；停止自己之前启动的重复进程 |
| 后台页面能打开，但 API 代理报错 | 单服务模式代理须指向 8080；完整模式指向 8081；改变量后重启 Vite |
| 微信编译提示缺少 `VITE_API_BASE` | 在运行 npm 的同一个 PowerShell 终端设置 `$env:VITE_API_BASE` |
| 修改代码后小程序没有变化 | 确认开发者工具导入的是当前命令对应的 `dist/dev` 或 `dist/build` 目录 |
| 模拟器能用，手机提示网络连接失败 | 手机不能使用 127.0.0.1；先从手机浏览器验证电脑 IPv4 的健康检查 |
| 微信登录失败，但账号登录正常 | AppID 是否一致、后端 AppSecret 是否正确加载；修改配置后重启后端 |
| 新微信用户没有可报修房屋 | 先申请绑定，再使用物业账号审核；返回报修页刷新房屋列表 |
| 修改 `.env` 后仍提示密码错误 | 确认修改的是对应角色密码配置，同步开关为 true，并重启业务后端；同步关闭时使用该账号原密码 |
| 后端提示密码配置不合规并退出 | 所有模式至少 12 个字符、最多 72 个 UTF-8 字节，不允许纯空白；修正对应配置后重新启动 |

端口排查可以在空闲终端执行：

```powershell
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object LocalPort -in 8080,8081,8761,5173,5174 |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

结束测试时，在各个后端、Vite 和小程序编译终端按 `Ctrl+C`。MySQL 可以保留运行；需要停止时执行：

```powershell
Set-Location D:\chatgpt-project\CommunityFix
docker compose --env-file deploy/.env -f deploy/compose.yml -f deploy/compose.dev.yml stop mysql
```

这会停止数据库容器并保留数据。日常停止和启动不需要执行 `down -v`。

当前实施状态见 [项目进度](implementation-status.md)，已执行的验证见 [验证记录](verification.md)。
