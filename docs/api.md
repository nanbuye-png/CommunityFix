# CommunityFix API

基路径 `/api`，单业务服务开发使用 8080，完整 Cloud 模式从网关 8081 进入，容器部署从 Nginx 对外端口进入；登录后携带 `Authorization: Bearer ...`。BIGINT ID 为 JSON 字符串，version/page/size 为整数。统一响应 `{code,message,data,requestId,errorCode}`；HTTP 状态与 code 一致。失败返回明确错误码，不使用 HTTP 200 包装失败。

启用 RabbitMQ 后，微信通知通过 outbox → 队列 → 消费者投递。管理员 outbox 列表新增 `mqPublishAttempts`、`mqNextPublishAt`、`mqLastError`，用于区别队列发布失败和微信发送失败；SENT 语义不变，仍表示微信接口接受。详见[通知队列说明](rabbitmq-notifications.md)。

## 认证与基础信息

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| POST | `/auth/login` | `{username,password}`，返回 token、expiresAt、user、roles、permissions、communityIds |
| POST | `/auth/register` | 注册居民或维修人员；`{username,password,nickname,role?,communityId?}`，role 为 RESIDENT / WORKER，维修注册需选择小区 |
| POST | `/auth/wechat-login` | `{code}`，服务端换取微信身份；需真实配置 |
| POST | `/auth/logout` | 撤销账号已有会话 |
| PATCH | `/system/users/{id}` | 系统管理员修改姓名、联系电话、角色、状态或密码；账号名不可变 |
| POST | `/system/users/{id}/move` | `{oldBindingId,newRoomId,reason}`；原房屋撤销、新房屋生效，保留历史工单地址和双向绑定日志 |
| GET | `/auth/me` | 当前账号和权限范围 |
| GET | `/communities`、`/buildings?communityId=...`、`/rooms?buildingId=...` | 居民绑定所需基础信息，不包含住户身份 |
| GET | `/repair/categories` | 启用分类 |
| POST/GET | `/room-bindings` | `{roomId}` 创建绑定申请；按角色分页查询 |
| POST | `/room-bindings/{id}/review`、`/{id}/revoke` | 审核 `{approved,reason}`；撤销 `{reason}` |

## 工单

创建工单支持 facilityScope（PUBLIC/PRIVATE，省略默认PRIVATE）和locationText（PUBLIC必填），默认自动审批，也可配置人工审批。自费工单派单、开始、完工和验收受已确认报价约束，历史工单不受新计费规则影响。费用API与模拟支付开关见[费用与支付规则](business-flow.md)。

新建、工单动作、评价、投诉必须带 `Idempotency-Key`（8～64 位字母数字、下划线或连字符）。同键不同正文返回 409。相同键相同正文返回第一次结果。列表 `page=1,size=20`，最大 size=100；返回 `{records,total,page,size}`。

```json
{
  "roomId":"1","categoryId":"1","title":"厨房水管漏水",
  "description":"厨房水管连接处有渗漏","priority":"NORMAL","attachmentIds":[],"analysisId":"1"
}
```

`analysisId` 选填，仅在居民确认采用 AI 建议后传入。只关联本人、成功且未关联工单的分析记录，同时保存脱敏后的最终选择；记录不存在、属于他人、已使用或为降级记录时不关联，也不阻止合法人工报修。所有普通业务校验仍执行。

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| POST/GET | `/repair/orders` | 创建 / 按身份分页，支持 status/search 筛选 |
| GET | `/repair/orders/{id}` | 当前可见工单详情 |
| POST | `/repair/orders/{id}/review` | `{version,approved,reason?}` |
| POST | `/repair/orders/{id}/cancel` | `{version,reason}` |
| POST | `/repair/orders/{id}/assign` | `{version,workerId,dispatchType:"MANUAL或SMART",recommendationId?}` |
| POST | `/repair/orders/{id}/accept`、`/start`、`/confirm` | `{version}` |
| POST | `/repair/orders/{id}/reject`、`/revoke`、`/rework` | `{version,reason}` |
| POST | `/repair/orders/{id}/finish` | `{version,content,attachmentIds:[]}` |
| GET | `/repair/orders/{id}/logs`、`/work-records`、`/media` | 状态日志 / 维修记录 / 附件 ID |
| POST | `/repair/orders/{id}/work-records` | 当前维修人员追加 `{content}` |
| POST/GET | `/repair/orders/{id}/evaluations` | 一次评价 `{score:1到5,content?}` / 查询 |
| POST | `/repair/orders/{id}/complaints` | `{content}`，未关闭投诉唯一 |
| POST | `/repair/orders/{id}/recommendations` | 物业获取规则评分候选，不产生派单 |

验收后售后：`GET/POST /repair/orders/{id}/aftercare` 查询或创建 `{content}`；`POST /repair/orders/{id}/aftercare/{aftercareId}` 提交 `{version,action,content}`，action 为 `accept/reject/resolve/confirm/reopen`。写操作要求 `Idempotency-Key`，权限、状态及保留原账单的约束见[售后说明](business-flow.md)。

## 消息、文件与其他模块

WebSocket `/notifications/live`：握手后首帧提交 `{type:"auth",token}`，鉴权后推送 `{type:"notifications",latestId,unread}`，仅当前用户消息摘要。JWT不得放入查询串；连接不能执行业务写操作。断线后使用原消息列表补查，协议及安全边界见[在线通知说明](websocket-notifications.md)。

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| POST | `/files` | multipart 字段 file；真实 JPEG/PNG ≤5 MiB，返回 attachmentId |
| GET | `/files/{id}/access-url` | 授权后生成有效 120 秒的私有代理访问 URL |
| GET | `/files/{id}/content` | 签名 URL 的图片下载，只验证签名及过期时间 |
| GET | `/notifications` | 本人消息分页 |
| POST | `/notifications/{id}/read` | 本人标记已读 |
| GET | `/notifications/wechat-subscription` | 返回当前配置的工单订阅模板 ID；未配置时为空 |
| POST | `/notifications/wechat-subscription` | 用户在微信授权成功后提交 `{templateId}`，记录一次发送意愿；仅微信绑定账号可用 |
| GET | `/notifications/outbox?status=FAILED` | 系统管理员分页查看发送状态，不返回 openid 或消息正文 |
| GET | `/notifications/outbox/summary` | 系统管理员读取微信队列 PENDING/PROCESSING/SENT/FAILED 数量；SENT 表示外部接口接受 |
| POST | `/notifications/outbox/{id}/retry` | 系统管理员将微信 FAILED 记录重置为待发送，清空租约/错误并写 NOTIFICATION_RETRY 审计；非失败记录返回 409 |
| GET | `/sla/warnings` | 物业按授权小区分页；active=true 默认只返回当前轮次仍有效阶段，false 包含历史；phase=RESPONSE/COMPLETION、level=1～3、search≤100 字；每工单/轮次/阶段仅返回最高预警等级 |
| GET | `/complaints`、`/complaints/{id}/logs` | 本人 / 管理小区投诉及处理历史 |
| GET | `/complaints/{id}` | 投诉详情 `{complaint,order,logs}`，含关联报修、房屋位置、处理人及历史；仅投诉居民或管理该小区的物业可读 |
| POST | `/complaints/{id}/process` | `{version,status:"PROCESSING或CLOSED",resolution}` |
| GET/POST | `/notices` | 可见公告分页 / 物业新建 `{communityId,title,content}` |
| POST | `/notices/{id}/publish`、`/offline` | 公告发布 / 下架 |
| GET | `/statistics/overview` | 物业统计，start/end 为上海日期，end 不包含在内；范围≤366天 |
| POST | `/ai/repair-analysis` | 居民提交 `{description}`（1～2000 字）；返回 analysisId/categoryId/priority/summary/source；summary固定为居民原文，新增summarySource=ORIGINAL、safetyPriority和safetyNotice；明确险情命中时AI建议保底URGENT。MySQL共享每居民60秒最多5次，超额429 AI_RATE_LIMIT；模型失败或熔断时source=MANUAL、categoryId/priority=null，但独立险情提示保留。模型自由文本不返回，采用建议仅更新分类及紧急程度 |
| GET/POST/PATCH | `/workers`、`/workers/{id}` | 人员查询 / 系统管理员维护档案 |
| GET | `/workers/history` | 当前维修人员参与的派单摘要，不开放其他轮次详情 |
| GET/POST/PATCH | `/manage/communities`、`/buildings`、`/units`、`/rooms` | 基础数据维护，按小区或系统权限校验；PATCH `/{id}` 修改启用状态 |
| GET/POST/PATCH | `/system/users`、`/system/roles` | 系统账号和角色维护；PATCH `/{id}` |
| GET | `/system/permissions`、`/system/logs` | 权限字典与管理审计 |
| GET/POST/DELETE | `/system/admin-communities` | 小区授权；DELETE `/{userId}/{communityId}` |

完整字段以控制器 DTO 注解为准，工单状态约束见 `OrderRules`、`OrderService` 与相关集成测试。系统管理员没有默认跨小区工单读取权限。

辅助报修与 AI 客服共用[统一模型调用模块](model-gateway.md)。供应商 HTTP 429 记录为 `AI_PROVIDER_RATE_LIMIT`，区别于本地用户限流 `AI_RATE_LIMIT`；超时、空响应和无效答案分别记录为 `AI_TIMEOUT`、`AI_EMPTY_RESPONSE`、`AI_INVALID_RESPONSE`。这些模型失败沿用业务人工降级流程，不直接将供应商 HTTP 状态转发给前端。

启用 Redis 后，跨实例模型窗口超额记录 `AI_PROVIDER_LOCAL_RATE_LIMIT`，Redis 不可用记录 `AI_LIMITER_UNAVAILABLE`，均通过上述人工降级流程处理，不发送供应商请求。


## 费用与参考价

费用操作使用版本和 `Idempotency-Key` 防止重复提交。报价金额与明细格式以 `BillingController` 的 DTO 为准；API 的 `unitPriceFen`、`laborFen`、`materialFen` 使用整数分，前端按元展示且最多两位小数。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/reference-prices?communityId=...` | 当前小区分类参考价 |
| GET | `/reference-prices/history?communityId=...&categoryId=...` | 参考价调整历史 |
| POST | `/reference-prices?communityId=...&categoryId=...` | 按版本保存参考价，要求幂等键 |

以下路径均以 `/repair/orders/{id}/billing` 为前缀：

| 方法 | 子路径 | 内容 |
| --- | --- | --- |
| GET | 空 | 当前报价、账单和支付状态 |
| POST | `/quotes` | `{revision,items:[{name,quantity,unitPriceFen}],reason}` |
| POST | `/decision` | `{quoteId,accepted}`，居民确认或拒绝 |
| POST | `/scope` | `{version,facilityScope,locationText,reason}`，纠正公共 / 自费归属 |
| POST | `/reference-price` | 当前工单分类参考价：`{version,enabled,laborFen,materialFen,basis}` |
| POST | `/payment-attempts` | `{billId}`，创建或复用有效支付单 |
| POST | `/mock-pay` | `{billId,attemptNo}`，确认当前模拟支付单 |

以上写操作均要求 `Idempotency-Key`。模拟支付仅在演示模式和模拟支付开关同时开启时可用。

## 客服与知识库

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/support/options` | 当前身份可选的小区与客服选项 |
| POST / GET | `/support/sessions` | 创建 `{communityId,orderId,role}` / 查询会话 |
| GET | `/support/sessions/{id}` | 会话详情 |
| GET | `/support/sessions/{id}/events?after=...&wait=true` | 长轮询增量消息 |
| POST | `/support/sessions/{id}/messages` | `{clientId,content}`，客户端消息 ID 用于幂等 |
| POST | `/support/sessions/{id}/{action}` | `{version}`，action 为 handoff / cancel-wait / claim / takeover / close |
| POST | `/support/sessions/{id}/read` | `{seq}` 已读位置 |
| POST | `/support/heartbeat` | `{online}` 客服在线心跳 |
| GET / POST / PATCH | `/support/knowledge`、`/support/knowledge/{id}` | 知识列表、新建和编辑，支持 aliases、发布状态及版本 |
| GET | `/support/knowledge/statistics` | 当前授权范围内的 FAQ 使用统计 |

已发布常见问答及别名匹配时直接回答，不调用模型；其余问题按知识检索、授权上下文和模型配置处理。人工接管、权限校验和消息重试规则以服务端状态为准。
