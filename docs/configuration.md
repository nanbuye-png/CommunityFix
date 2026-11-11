# 配置说明

配置入口为 `deploy/.env.example`、`backend/src/main/resources/application.yml` 和 `deploy/compose.yml`。复制示例时须替换占位密码；本地开发可通过 `python deploy/init-dev.py` 生成随机凭据。已有配置可用 `--upgrade` 补齐缺失的 Redis / RabbitMQ 密码，不覆盖非空值。

## 基础配置

| 变量 | 用途 |
| --- | --- |
| `DB_PASSWORD` / `DB_ROOT_PASSWORD` | 业务数据库和管理密码 |
| `JWT_SECRET` | Token 签名密钥，至少 32 字节随机值 |
| `BOOTSTRAP_USERNAME` / `BOOTSTRAP_PASSWORD` | 初始管理员；密码 12～72 字符 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 私有附件存储凭据 |
| `WEB_PORT` | Compose 对外 HTTP 端口，默认 8088 |
| `CORS_ORIGINS` | 允许的 Web 来源，包含协议和端口 |
| `DEMO_ENABLED` / `DEMO_SYNC_PASSWORDS` | 演示账号与密码同步，正式环境均关闭 |
| `BILLING_MOCK_ENABLED` | 模拟支付，须同时启用演示模式；不发生真实扣款 |
| `ORDER_AUTO_APPROVE` | 新订单自动审批，默认 `true` |

直接运行 Java 时可配置 `DB_URL`、`DB_USER`、`DB_PASSWORD`；Compose 内部连接由服务名和容器端口组成。参考价在物业后台维护，缺少分类参考价时仍需人工补充报价。

## 微信登录与通知

`WECHAT_APP_ID` 必须与小程序端 AppID 一致，`WECHAT_APP_SECRET` 仅存后端。模板 ID 属于具体小程序，接入其他小程序时应替换为自己的模板并核对字段。

| 通知 | 配置变量 | 字段 |
| --- | --- | --- |
| 报价待确认 | `WECHAT_CONFIRMATION_TEMPLATE_ID` | 订单编号 `character_string1`、费用 `amount3`、下单时间 `time6` |
| 师傅接单 | `WECHAT_ACCEPTANCE_TEMPLATE_ID` | 订单编号 `character_string7`、工单编号 `character_string30`、项目 `thing27`、接单人 `name5`、接单时间 `date4` |
| 师傅完工待验收 | `WECHAT_COMPLETION_TEMPLATE_ID` | 订单编号 `character_string5`、金额 `amount11`、完成时间 `date9` |
| 验收后待支付 | `WECHAT_PAYMENT_TEMPLATE_ID` | 订单编号 `character_string1`、内容 `thing2`、金额 `amount3`、下单时间 `time22`、完成时间 `date10` |

居民订阅入口保留两组授权。发送取决于授权额度、订单当前状态和微信响应，不是每次业务操作必然送达。旧 `WECHAT_QUOTE_*` / `WECHAT_ORDER_*` 配置保留兼容用途，新接入使用上述四个里程碑模板。

## AI

使用兼容 Chat Completions 的供应商，端点必须为完整请求 URL：

```dotenv
AI_ENDPOINT=https://provider.example/v1/chat/completions
AI_API_KEY=replace-with-your-key
AI_MODEL=replace-with-model-id
```

报修和客服默认共用这组配置。`AI_REPAIR_ENDPOINT / AI_REPAIR_API_KEY / AI_REPAIR_MODEL` 或 `AI_SUPPORT_ENDPOINT / AI_SUPPORT_API_KEY / AI_SUPPORT_MODEL` 可独立覆盖场景，每组应全部填写或全部留空。未配置模型不影响普通工单功能，但需要模型的问题会降级提示或转人工。

| 变量 | 默认值 | 行为 |
| --- | --- | --- |
| `AI_REPAIR_MAX_OUTPUT_TOKENS` | 128 | 辅助报修输出上限 |
| `AI_SUPPORT_MAX_OUTPUT_TOKENS` | 1024 | 客服输出上限 |
| `AI_SUPPORT_HISTORY_CHARACTERS` | 6000 | 历史消息字符预算，不等于 Token 总量 |
| `AI_DAILY_TOKEN_WARNING` | 100000 | 按场景每日用量日志提醒，不阻断调用 |
| `AI_PROVIDER_REQUESTS_PER_MINUTE` | 30 | Redis 供应商限流阈值 |
| `AI_FAILURE_THRESHOLD` / `AI_COOLDOWN_SECONDS` | 3 / 60 | 连续失败熔断和冷却 |
| `AI_RETENTION_DAYS` | 30 | AI 数据保留配置 |

常见问题优先匹配已发布 FAQ 及别名，命中时不调用模型。每日用量累计真实供应商调用返回的可用计量；未返回用量时单独记录未知计量，不能据此推算精确账单。详见 [模型模块](model-gateway.md)。

## 中间件

完整 Compose 启用 Redis、RabbitMQ 和 MinIO，只有 Nginx 映射公网端口。本地直启默认关闭 Redis 和 RabbitMQ；关闭队列时微信通知通过数据库 outbox 扫描发送，关闭 Redis 不关闭数据库层的用户限流和熔断。

本地秘密配置、上传文件与备份被 Git 忽略；不要把 AppSecret、模型 Key 或数据库密码写到前端或命令示例中。
