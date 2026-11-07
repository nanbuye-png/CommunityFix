# 基于Spring Cloud的社区便民报修服务系统的设计与实现

工程代号 CommunityFix。采用 Java 21、Spring Boot 3.5.16、Spring Cloud 2025.0.3、MyBatis-Plus、MySQL 8.4、Vue 3 和 uni-app。实现依据位于相邻的 [`planned`](../planned/README.md) 目录。

本文按 2026-11-07 的工作区实现更新。新环境建议先阅读 [本地启动指南](docs/vscode-local-testing.md)，使用 MySQL + 单业务后端运行，再按需开启注册中心、网关和中间件。

云架构包含 Eureka 注册中心（8761）、Spring Cloud Gateway（8081）和向 Eureka 注册的业务服务（8080）。后台与小程序通过网关访问 `/api/**`；网关使用 `lb://communityfix-service` 做服务发现与负载均衡。业务服务保留房屋绑定、工单和审计的本地事务，后续拆分业务服务时需先确定数据所有权和跨服务一致性。

## 目前可运行的功能

- 居民／维修 AI 客服：通过真实 LLM API 进行日常聊天、多轮追问及社区咨询；支持分小区知识库、授权工单查询、转人工、物业实时接待与 admin 兜底，见 [客服使用与验收说明](docs/support-2026-10-30.md)。
- 居民、维修人员可自主选择身份注册；物业按负责小区管理维修账号、按房屋绑定管理居民账号，系统管理员保留全局管理。入口与操作见 [自主注册说明](docs/self-registration-2026-10-30.md)。
- 四角色账号认证、JWT 撤销、权限及物业小区范围校验；微信登录接口需配置真实 AppID / AppSecret。
- 小区、楼栋、单元、房屋维护；居民绑定申请、物业审核及撤销。
- 系统管理员可编辑用户姓名、联系电话、角色和密码；居民搬家时撤销原绑定并启用新房屋，旧工单地址与审核历史保留。
- 创建、审核、取消、派单、接单、拒单、开始维修、提交结果、验收和返修的工单闭环。
- 自有设施报价、居民确认、验收出账及模拟支付；支付单从点击“去支付”起 30 分钟超时关闭，支持超时后重新发起。公共设施及历史免收工单不产生居民支付，见 [报价与支付说明](docs/payment-phase1.md)。
- 幂等提交、版本冲突、人员容量行锁、状态日志、私有图片上传及短期签名访问。
- 评价、投诉及处理历史、公告、站内消息、小区统计。
- 规则评分派单、SLA 快照和三级预警及物业预警页面；微信接单通知、投递查询和失败重试审计。
- AI 建议确认、采纳结果关联；MySQL 共享限流/熔断、脱敏和人工降级。
- Redis 提供模型供应商跨实例 RPM 限流；RabbitMQ 承载微信订阅通知异步投递；WebSocket 提供站内通知提示。客服消息使用 HTTP 长轮询。
- Vue 管理后台和居民/维修人员共用的小程序页面；小程序也可编译为 H5 进行本地联调。

这是一版已实现核心闭环的开发版本。历史验收包括真实微信登录与接单订阅通知手机联调、辅助报修 AI 合成样例、本地备份恢复、隔离 MinIO 验收及 10 万工单／100 并发用户性能场景。本轮后端隔离回归 127 项通过，并通过真实供应商完成六轮客服 API 对话。生产 HTTPS、最终部署拓扑性能复测、升级回滚、真实微信支付及更大规模 AI 效果评测仍待完成。历次验收有各自范围，详见 [实现状态](docs/implementation-status.md)、[历史验证记录](docs/verification.md)及下文最新验证说明。

## 目录

```text
registry/    Eureka 注册中心
gateway/     Spring Cloud Gateway 网关
backend/     社区业务服务、Flyway 数据库迁移与测试
admin-web/   Vue + TypeScript 管理后台
miniapp/     uni-app 微信小程序及 H5 联调页面
deploy/      Docker Compose、Nginx 和本地开发脚本
docs/        进度、接口和验证说明
.local/      开发依赖缓存、文件、日志（忽略提交）
```

## VS Code 本地测试入口

在 VS Code 打开 `D:\chatgpt-project\CommunityFix`，按 [VS Code 前后端与微信小程序启动指南](docs/vscode-local-testing.md) 操作。指南按终端列出可复制命令，推荐先启动 **MySQL + 单业务后端 + 管理后台 + 微信小程序编译**，再按需启用完整 Spring Cloud 模式。

当前本机的四角色账号、密码及修改方法见 [测试账号表](docs/test-accounts.example.md)。开发前端默认直连 8080，并同时支持 `localhost`、`127.0.0.1` 和电脑当前局域网地址。H5 使用同源 `/api`，微信的 `VITE_API_BASE` 不会再覆盖 H5 请求地址。

单业务服务模式的前端代理和小程序 API 使用 `8080`；完整 Spring Cloud 模式使用 `8081`。小程序开发监听导入 `miniapp/dist/dev/mp-weixin`，一次性构建导入 `miniapp/dist/build/mp-weixin`。真机 API 要使用手机能访问的电脑 IPv4。

## AI 客服与模型配置

客服不再要求先关联工单或命中知识库才调用模型。“你好”“你是谁”、兴趣话题和上下文追问都由模型生成回复；正常回答直接显示为“AI 客服”。未配置模型、超时或响应无效时显示明确的服务提示，不将固定话术伪装成模型回答。

费用、物业规则和工单进度须依据已发布知识或当前用户有权查看的关联工单。后端保留权限检查、知识 ID 引用校验、输入脱敏、消息幂等和人工接管后的迟到回复拦截；自然对话允许没有知识引用。模型生成内容仍需效果评测，提示词和引用校验不能保证每句内容都正确。

只在服务端 `deploy/.env` 配置兼容 Chat Completions 的供应商：

```dotenv
AI_ENDPOINT=https://provider.example/v1/chat/completions
AI_API_KEY=replace-with-your-key
AI_MODEL=replace-with-model-id
```

端点必须是完整请求地址。辅助报修和客服默认共用上述配置；若客服单独使用模型，完整设置 `AI_SUPPORT_ENDPOINT / AI_SUPPORT_API_KEY / AI_SUPPORT_MODEL` 三项。三项全部留空时继承默认配置，仅填写部分会启动失败。协议要求、错误码和限流机制见 [统一模型调用模块](docs/model-gateway.md)。

修改 Java 代码或环境变量后必须重启后端；当前没有 Java 热重载。密钥不进入 H5 或小程序。供应商会收到脱敏后的当前问题、最近会话、已发布知识及授权工单摘要，部署时应确认供应商与数据范围。此功能无需数据库迁移。

## 模拟支付与超时规则

- 物业报价经居民确认，维修验收后生成账单。点击“去支付”才创建支付单并开始 30 分钟计时；重复点击、刷新或换设备复用仍有效的支付单。
- MySQL 保存支付单状态 `PENDING / EXPIRED / SUCCEEDED`，并使用数据库时间判断截止时刻。确认付款在事务锁内再次校验，即使后台扫描暂停，到期单也不能支付。
- 超时仅关闭本次支付单，账单继续待支付，已完成工单保持完成。重新支付使用新单号，旧页面不能确认新单。
- 成功交易、账单状态和站内通知在同一事务中更新，幂等键和唯一约束防止重复记账。`/mock-pay` 必须同时提交 `billId` 与 `attemptNo`，旧客户端需更新。

模拟交易需同时启用 `DEMO_ENABLED=true` 和 `BILLING_MOCK_ENABLED=true`；直接启动时模拟开关默认跟随演示模式，主 Compose 默认关闭。V8 引入报价与账单，V9 新增支付单表，后端启动时由 Flyway 迁移。此流程不涉及真实资金，真实微信支付的渠道关单、查单、回调、退款和对账尚未接入。

## Redis 与消息队列的职责

| 组件 | 当前用途 | 说明 |
| --- | --- | --- |
| MySQL | 业务事务、支付单截止校验、客服任务和消息、通知 outbox | 支付超时扫描与确认均以数据库为准 |
| Redis | 报修和客服共用的供应商 RPM 滑动窗口限流 | 不存支付状态，不使用过期监听关闭支付单；见 [Redis 说明](docs/redis.md) |
| RabbitMQ | 微信订阅通知 outbox 的异步投递、重试与死信 | 不参与支付结果判定或 LLM 请求；见 [通知队列说明](docs/rabbitmq-notifications.md) |
| WebSocket | 在线站内通知提示 | 客服对话使用独立的长轮询接口；见 [在线通知说明](docs/websocket-notifications.md) |

本地直接启动时 Redis、RabbitMQ 默认关闭；主 Compose 显式开启，需提供对应密码。关闭 RabbitMQ 时使用数据库扫描直发微信通知的路径；关闭 Redis 不会关闭 MySQL 用户限流和熔断。

## 完整 Spring Cloud 本地启动（Windows PowerShell）

前置：JDK 21、Maven 3.6.3 以上、Node 24、Python 3、已启动的 Docker Desktop。在 CommunityFix 根目录运行：

```powershell
python deploy/init-dev.py
docker compose --env-file deploy/.env -f deploy/compose.yml -f deploy/compose.dev.yml up -d mysql
```

等待 MySQL 健康检查通过，分别在三个终端按顺序启动注册中心、业务服务、网关：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 -Service registry
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 -Service backend
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-cloud.ps1 -Service gateway
```

每条命令保持运行，在新终端执行下一条；首次运行会下载 Maven 依赖。业务服务脚本加载 `deploy/.env`，连接 `127.0.0.1:3307` 的专用 MySQL，启用演示数据，并使用 `.local/uploads` 私有文件目录。它不要求本地 MinIO / Redis 已启动。核心事务、认证撤销和幂等均以 MySQL 为依据。首次启动自动迁移数据库，只有显式启用演示模式才创建演示小区、房屋和账号。直接运行 `deploy/run-backend.ps1` 可单独调试业务服务；开发前端默认代理到 8080，以下完整 Spring Cloud 示例显式指定 8081 网关。

另开终端启动管理后台：

```powershell
cd admin-web
npm ci
$env:VITE_PROXY_TARGET = 'http://127.0.0.1:8081'
npm run dev
```

管理后台地址：[http://localhost:5173](http://localhost:5173)。物业账号 `property`、系统账号 `admin`。当前本机测试密码见 [测试账号表](docs/test-accounts.example.md)，由 `deploy/.env` 的 `BOOTSTRAP_PASSWORD` 管理。

再开终端启动移动端 H5：

```powershell
cd miniapp
npm ci
$env:VITE_PROXY_TARGET = 'http://127.0.0.1:8081'
npm run dev:h5
```

移动端地址：[http://localhost:5174](http://localhost:5174)。选择“使用账号登录”，居民账号 `resident`、维修账号 `worker`。`resident2/property2` 用于跨居民、跨小区隔离测试。`DEMO_SYNC_PASSWORDS=true` 时，重启业务后端会将既有演示账号密码同步为各角色的配置值；角色配置为空时回退到 `BOOTSTRAP_PASSWORD`。同步关闭时保留原密码，业务数据不会重建。所有模式均严格要求密码至少 12 个字符、最多 72 个 UTF-8 字节，不允许纯空白；不合规时后端报错并停止启动。生产模式不自动同步已有账号密码。

## 微信小程序

先在 `deploy/.env` 中设置 `WECHAT_APP_ID`、`WECHAT_APP_SECRET`，并将同一 AppID 填入 `miniapp/src/manifest.json` 的 `mp-weixin.appid`。AppSecret 仅供后端使用，不能写入小程序源码。修改后重启业务服务。

本机微信开发者工具模拟器联调时，启动 MySQL、注册中心、业务服务和网关，然后执行：

```powershell
cd miniapp
$env:VITE_API_BASE = 'http://127.0.0.1:8081/api'
npm run build:mp-weixin
```

在微信开发者工具导入 `miniapp/dist/build/mp-weixin`。模拟器可在工具的“详情 → 本地设置”临时关闭合法域名校验；该设置不会使手机上的 `127.0.0.1` 指向电脑。

手机临时联调时，先用 `ipconfig` 查看电脑当前 Wi-Fi IPv4，确保手机与电脑处于可互访的同一局域网。校园网可能隔离终端，此时可让电脑连接手机热点；手机流量无法直接访问校园网 `10.x.x.x` 私有地址。将 `VITE_API_BASE` 设为 `http://电脑当前IPv4:8081/api` 后重新构建，在开发者工具重新编译并用真机调试或新预览包测试；手机开发调试模式需要允许跳过服务器域名校验。先用手机浏览器访问 `http://电脑当前IPv4:8081/actuator/health`，确认显示 `UP`，再测试微信登录。地址改变后必须重新构建，不能沿用旧预览包。

16 GB 内存机器可只启动 Docker MySQL 和业务服务：在项目根目录运行 `deploy/run-backend.ps1`（不加 `-Cloud`），跳过 Eureka、Gateway 和管理后台。此时小程序的 `VITE_API_BASE` 应为 `http://电脑当前IPv4:8080/api`，健康检查地址相应改为 `http://电脑当前IPv4:8080/actuator/health`。这是本地联调方式，完整 Spring Cloud 部署仍使用注册中心与网关。校园网是否允许手机访问电脑，必须以上述手机浏览器测试为准；电脑自己能访问该地址不能证明终端间互通。

低内存模式需要审核房屋绑定时，可临时另开终端启动管理后台，并让其 API 代理直连业务服务：

```powershell
cd admin-web
$env:VITE_PROXY_TARGET = 'http://127.0.0.1:8080'
npm run dev
```

打开 `http://127.0.0.1:5173`，使用物业账号 `property` 进入“房屋绑定审核”。演示账号 `admin` 是系统管理员，不负责小区房屋审核；密码从本机 `deploy/.env` 的 `BOOTSTRAP_PASSWORD` 读取。

可单独启动 Compose 的 `minio` 服务，再用 `deploy/run-backend.ps1 -Minio` 将业务服务切到对象存储模式；该脚本默认使用本地目录。隔离环境已完成 MinIO 本地验收，镜像获取方式及环境限制见 [完整验收结果](docs/local-acceptance-results.md)，不等同于生产环境验收。

正式环境需使用手机可访问的完整 HTTPS API 地址（例如 `https://repair.example.com/api`），并在小程序后台配置 request/uploadFile/downloadFile 合法域名。构建命令现在会拒绝缺少 `VITE_API_BASE` 的微信包，避免生成无法请求后端的相对路径 `/api`。H5 代理 `/api` 只用于本地联调。

微信工单订阅消息使用“接单成功通知”模板，已选择“工单编号”和“订单状态”关键词，当前字段为 `character_string30`、`phrase16`；26 位工单编号已通过真实投递。在 `deploy/.env` 填写 `WECHAT_ORDER_TEMPLATE_ID`、`WECHAT_ORDER_NUMBER_FIELD`、`WECHAT_ORDER_STATUS_FIELD`。构建小程序时还需在 `miniapp/.env` 设置同一个公开模板 ID 为 `VITE_WECHAT_ORDER_TEMPLATE_ID`。未配置时“我的”页不显示订阅入口，工单仍产生站内消息。居民主动点击订阅并接受后，服务端记录一次发送意愿；该居民工单下一次被维修人员接单时，在业务事务内写入 outbox，后台任务使用租约领取并最多尝试 5 次。系统管理员可在“微信通知投递”查看统计和错误、手动重试失败记录；重试写入审计日志。其他工单状态仅发站内通知。外部发送采用至少一次语义。

AI 配置仅写入服务端 `deploy/.env`：`AI_ENDPOINT` 填完整 Chat Completions 地址（兼容服务通常为基础 URL 加 `/chat/completions`），`AI_API_KEY`、`AI_MODEL` 分别为凭证和模型。可设置 `AI_FAILURE_THRESHOLD=3`、`AI_COOLDOWN_SECONDS=60`、`AI_RETENTION_DAYS=30`；修改后重启后端。小程序不持有密钥。每居民 60 秒最多 5 次分析，状态通过 MySQL 在实例间共享；外部请求超过 10 秒或结果无效时返回人工模式。居民确认采纳后才更新表单，仍可继续编辑，提交时关联本人成功分析和最终字段。真实样例及局限见 [AI 联调记录](docs/ai-evaluation.md)。

## 测试与构建

```powershell
# 推荐：使用随机 MySQL 测试库及一次性 Redis / RabbitMQ，结束后清理
# 前置：Docker Desktop 与本地 communityfix-mysql-1 已运行，MySQL 映射 3307
python -X utf8 deploy/test-backend-isolated.py

# 注册中心、网关、业务服务一起打包
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/build-cloud.ps1

# 三个服务启动后验证注册、转发与权限
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/verify-cloud.ps1

# 管理后台
cd admin-web
npm run build

# 小程序
cd ../miniapp
npm run typecheck
$env:VITE_API_BASE = 'http://127.0.0.1:8080/api' # 单服务模拟器；网关模式改为 8081
npm run build:mp-weixin
npm run build:h5
```

H5 常规产物为 `miniapp/dist/build/h5`，微信常规产物为 `miniapp/dist/build/mp-weixin`。两端共用源码，但必须分别构建；构建 H5 不会自动更新微信包。开发者工具需要导入正确目录并重新编译。客服的本轮修改在后端，已有兼容前端加载新后端即可获得模型回复。

普通 `mvn test` 只执行独立单元测试；真实 MySQL 集成测试由 `CF_INTEGRATION=true` 显式开启，隔离脚本会设置此开关。`deploy/run-backend.ps1 -Test` 使用现有开发数据库并可能改变演示数据，日常回归优先使用上述隔离脚本，不要针对生产数据库运行。

真实客服 API 评测需主动运行以下命令，会读取服务端 AI 配置、向供应商发送合成对话并消耗额度：

```powershell
python -X utf8 deploy/evaluate-support-chat.py --run
```

脚本建立随机测试库和专用账号，通过正式消息接口、实际客服任务调度及长轮询验证六轮对话，结束后清理资源，报告位于 `.local/support-chat-*/report.json`。不带 `--run` 时仅列出问题，不调用供应商。报告 `PASS` 表示全部收到模型回复，相关性和事实准确性需另行审阅。

2026-11-07 最新验证：

- 后端 127 项通过，失败／错误／跳过均为 0，清理通过；报告 `.local/acceptance/tests-cc1fa0d6aaa5/report.json`。
- 真实客服六轮 API 对话均返回 `source=AI`，覆盖问候、身份介绍、兴趣建议、指代追问、无依据费用及报修入口；报告 `.local/support-chat-f5a7e95a995b/report.json`。首轮曾出现回复截断，收紧回复长度后本轮通过，不代表供应商永不超时或截断。
- H5 已观察到真实模型回复自动显示。本轮真实 LLM 的微信模拟器连续对话、手机后台恢复仍未完成；此前两端的本地兜底测试不能替代这项验收。

接口：[API 简表](docs/api.md)。本地网关入口为 `http://localhost:8081/api`，注册中心为 [http://localhost:8761](http://localhost:8761)。本地业务服务显式开启 OpenAPI `/v3/api-docs` 和 [Swagger UI](http://localhost:8080/swagger-ui/index.html)，调试受保护接口仍需在 Authorize 中填写登录所得 Token；生产默认关闭接口文档。各服务健康检查分别为 `/actuator/health`；网关不代理管理端点。

## 容器与部署

先构建 `admin-web/dist`，准备生产 `.env` 并设置 `DEMO_ENABLED=false`，使用基础 Compose：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml up -d --build
```

基础配置只将 Nginx 的 8088 端口暴露给外部，注册中心、网关、业务服务和数据服务保持内部访问；`compose.dev.yml` 才会将开发调试端口绑定到本机。本轮已完成独立 Compose 项目的完整容器和 MinIO 本地验收；固定 MinIO 预构建镜像入口仍失败，本轮从核对过的官方发布提交构建镜像。生产 HTTPS 仍待完成。复现入口为 `python deploy/acceptance.py`，不读取或覆盖现有开发配置，详见 [本地完整验收指南](docs/local-acceptance.md)及[本轮结果](docs/local-acceptance-results.md)。

[Spring Cloud 官方兼容矩阵](https://spring.io/projects/spring-cloud/)列明 2025.0.x 对应 Spring Boot 3.5.x；[2025.0.3 发布说明](https://spring.io/blog/2026/06/11/spring-cloud-2025-0-3-aka-northfields-has-been-released/)说明该版本线已结束开源支持。正式上线前需完成 Boot 4 / Cloud 2025.1 及 MyBatis-Plus 的兼容性升级和回归测试。

生产必须自行提供有效域名/HTTPS、备份位置、供应商凭证及存储配置。启动前更换开发凭证，禁止加载演示账号。数据库迁移已在真实 MySQL 8.4 空库执行成功；破坏性升级、回滚和生产 MinIO 恢复仍需要独立演练。

MySQL + 本地私有图片开发模式提供 `deploy/backup-local.py`，历史结果见 [备份恢复操作](docs/backup-restore.md)。新增 `deploy/acceptance.py backup/restore` 已实测独立 MinIO 环境协调备份及恢复到备份镜像版本，核对 38 表、4 对象和恢复附件 API 后清理副本；生产环境仍需按实际部署另行演练，见 [本地完整验收指南](docs/local-acceptance.md)。

性能验收使用 `python deploy/performance-local.py`：在随机新建的测试库中初始化 10 万张合成工单，以 100 个独立用户预热 2 分钟、采样 10 分钟，结束后自动清理测试库和账号。它直连专用端口的隔离后端，不修改现有业务数据，也不加载真实 AI/微信凭据。参数、负载比例、指标和环境限制见 [本地隔离性能验收](docs/performance.md)。
