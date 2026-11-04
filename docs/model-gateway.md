# 统一模型调用模块

2026-11-03。辅助报修与 AI 客服现通过后端进程内的 `ModelGateway` 调用模型。该模块与用于转发 HTTP API 的 Spring Cloud Gateway 是不同模块，无需新增服务或数据库表。

## 调用边界

```text
辅助报修 AiController ─┐
                     ├→ ModelGateway → ChatCompletionsClient → 模型供应商
AI 客服 SupportAi ────┘       ↓
                        ModelRoutes / AiGuard
```

- `ModelRoutes`：根据 REPAIR / SUPPORT 选择端点、密钥和模型；启动时读取配置。
- `ChatCompletionsClient`：唯一的模型 HTTP 协议适配器，负责认证、请求、结构化 JSON 解析和供应商错误分类。
- `ModelGateway`：统一调用结果、耗时与用量日志，以及共享熔断的准入、成功和失败登记。HTTP 请求期间不持有数据库事务。
- 业务模块：继续负责用户权限、输入脱敏、知识检索及提示词、业务字段校验与持久化。校验由网关在登记成功之前回调执行；未知分类、无效紧急等级或伪造引用均算调用失败。

报修仍只采用分类和紧急程度，摘要保留居民原文，已识别险情的 URGENT 保底及失败时独立提示保留。客服仍执行工单权限、知识引用校验和任务代次检查。本次重构不代表客服答案内容质量已经验收通过。

## 更换供应商

当前适配器要求供应商兼容 **Chat Completions** 请求，并支持 `response_format: {"type":"json_object"}`、`temperature: 0`、`max_tokens: 1024`；响应使用 `choices[0].message.content` 中的 JSON 字符串。端点填写完整请求 URL，模块不会自动拼接路径。

两项业务默认共同使用原配置，无需迁移现有密钥：

```dotenv
AI_ENDPOINT=https://provider.example/v1/chat/completions
AI_API_KEY=replace-with-your-key
AI_MODEL=replace-with-model-id
```

可为某项业务单独配置供应商，例如只切换客服：

```dotenv
AI_SUPPORT_ENDPOINT=https://another-provider.example/v1/chat/completions
AI_SUPPORT_API_KEY=replace-with-support-key
AI_SUPPORT_MODEL=replace-with-support-model-id
```

报修对应 `AI_REPAIR_ENDPOINT / AI_REPAIR_API_KEY / AI_REPAIR_MODEL`。每组覆盖配置必须三项一起设置；全部为空则继承旧配置，只填部分将启动失败，避免把旧供应商密钥发送到新地址。修改配置后需要重启后端；Compose 方式需要重建相应服务容器以加载新环境变量。示例配置与 Compose 已包含六个可选变量。

未配置可用模型时继续走原有降级通路。端点校验拒绝非 HTTP(S)、缺少主机、内嵌用户名密码和 fragment 的 URL。正式环境使用 HTTPS，密钥保留在服务端部署配置中。不要提交真实密钥。

目前没有自动供应商切换、配置管理页面或热更新。更换为同协议供应商可通过配置接入，但仍需确认上述参数兼容性并重新验收；使用不同协议的供应商需要新增协议适配器，不必在两个业务模块分别编写 HTTP 调用。

## 失败、限流与观测

连接超时 3 秒，单次 HTTP 请求超时 10 秒；不自动重试，避免限流期间重复消耗额度。同一端点与模型共用已有 MySQL 熔断状态，默认累计失败 3 次后熔断 60 秒，随后仅允许一个共享恢复探测；成功清零。不同端点或模型分别计算；相同端点和模型但不同密钥仍共享熔断。

| 错误码 | 含义 |
| --- | --- |
| AI_NOT_CONFIGURED | 当前业务没有完整模型配置 |
| AI_CIRCUIT_OPEN | 熔断阻止请求，未调用供应商 |
| AI_PROVIDER_RATE_LIMIT | 供应商返回 HTTP 429；仅凭状态码不能区分余额、配额与速率限制 |
| AI_PROVIDER_AUTH_ERROR | 供应商返回 HTTP 401 / 403 |
| AI_PROVIDER_HTTP_ERROR | 其他非 200 响应 |
| AI_TIMEOUT / AI_NETWORK_ERROR / AI_INTERRUPTED | 超时、网络故障、调用线程被中断 |
| AI_INVALID_CONFIG | 构建请求时发现配置不合法 |
| AI_EMPTY_RESPONSE | 响应没有有效文本内容 |
| AI_TRUNCATED_RESPONSE | 供应商标记 finish_reason=length |
| AI_INVALID_RESPONSE | JSON 或业务字段校验失败 |

报修错误码保存于 `ai_analysis_record.error_code`；客服保留在消息 metadata 的 `errorCode`。业务层上下文、数据库等其他异常仍可能记录原有 `AI_UNAVAILABLE` / `CONTEXT_UNAVAILABLE`，不能将这些错误统称为额度不足。前端接口字段及人工降级行为保持兼容。

网关输出 `model_call` 日志，包含场景、结果、耗时、HTTP 状态、供应商返回的 prompt/completion/total token 数。缺少或无效的用量记为 null，不能据此计算零费用。日志不写提示词、回答、原始供应商错误正文、端点、密钥或请求头；业务原有脱敏记录规则保留。当前用量仅供日志诊断，未新增账单统计。

已有用户请求限流与共享熔断保留。后续已接入 [Redis 共享 RPM 限流](redis.md)，启用后在熔断准入前检查端点与模型的共享窗口；新增 `AI_PROVIDER_LOCAL_RATE_LIMIT` 和 `AI_LIMITER_UNAVAILABLE` 降级码。尚无 TPM 调度。统一入口本身不会提高供应商额度，也不会修复模型分类、拒答或危险内容等能力问题。[RabbitMQ用于微信通知投递](rabbitmq-notifications.md)，[WebSocket用于在线通知](websocket-notifications.md)，二者不参与模型请求；Kafka 不引入。

## 验证

使用 `python -X utf8 deploy/test-backend-isolated.py` 在临时 MySQL 库执行后端回归。新增测试覆盖旧配置继承、独立场景配置、部分覆盖拒绝、异常 URL、请求协议、用量解析、供应商 HTTP 错误、空响应、错误 JSON、截断、超时、业务校验和熔断结果登记；原报修安全、客服引用、权限及任务测试一并运行。

本轮结果：94 项通过，失败 / 错误 / 跳过均为 0，临时数据库和账号清理通过。原始报告保存在 `.local/acceptance/tests-d09a45fc7b74/report.json`。

本次仅使用本地模拟模型端点，不消耗真实供应商额度；不执行模型训练、真实模型效果评测或正式环境部署。供应商实际协议兼容与真实质量验收仍须在切换后单独进行。
