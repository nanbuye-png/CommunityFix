# RabbitMQ 通知链路

2026-11-03。本轮接入微信订阅通知的异步投递；站内通知仍在业务事务内落库，四种身份继续通过现有通知接口查看。RabbitMQ 是服务端消息队列，不向浏览器直接推送；后续新增的[WebSocket在线通知](websocket-notifications.md)独立读取站内通知摘要。

## 路径与保证

```text
工单接单事务 → 站内通知 + 微信订阅 outbox
                              ↓ 事务提交后定时扫描
                      RabbitMQ 持久化交换机/队列
                              ↓ 消费者
                      MySQL 条件领取及两分钟租约
                              ↓
                      微信接口 → SENT / 待重试 / FAILED
```

现有微信规则保留：仅接单成功事件、已绑定微信且有订阅意愿的居民产生微信 outbox；没有扩大通知对象或模板范围。队列正文只包含十进制 outbox ID，不包含 openid、报修正文、地址或凭据，消费者从数据库读取对应记录。

relay 每 5 秒最多扫描 50 条，用数据库条件更新争抢发布资格，跨实例只有一个扫描者取得当前发布租约。消息持久化，并检查 correlated publisher confirm 与 mandatory return。收到 broker ACK 只表示发布被接收，**不会将业务状态改成 SENT**。确认超时、拒绝或未路由时保存 `MQ_PUBLISH_FAILED`，30 秒后可补发；此类失败不消耗微信发送次数。

发布成功但数据库仍待处理的记录，2 分钟后可重新发布，覆盖发布后崩溃、确认丢失、消费者宕机等情况。消息可能重复，消费者复用 outbox 的条件更新、attempts 和租约；已 SENT/FAILED、尚未到重试时间或有有效租约的记录不会重新发送。外部微信接口成功后、数据库记账之前崩溃仍可能重复通知，这是至少一次投递，不承诺恰好一次。

消费者使用 Spring 容器 AUTO 确认：处理方法返回、数据库结果保存后才 ACK，不是 RabbitMQ 的无确认消费。微信发送失败记录回数据库，按原 30/60/120/240 秒退避，正常失败路径最多 5 次后标记 FAILED，管理员可通过既有接口审计重试。数据库异常等未处理错误拒绝重入主队列并进入死信，避免即时死循环；数据库 outbox 仍可到期补发。非法 ID 也进入死信，不触发发送。死信队列需运维监控并在排查后人工清理，不自动无限重放。

确认语义依据 [RabbitMQ 官方说明](https://www.rabbitmq.com/docs/confirms)，重复投递处理依据[可靠性指南](https://www.rabbitmq.com/docs/reliability)。

## 配置

| 变量 | 默认值 / 用途 |
| --- | --- |
| RABBITMQ_ENABLED | 直接启动默认 false，主 Compose 与隔离验收 Compose 显式 true |
| RABBITMQ_HOST / RABBITMQ_PORT | localhost / 5672；容器使用 rabbitmq:5672 |
| RABBITMQ_USERNAME / RABBITMQ_PASSWORD | communityfix / 空；主 Compose 强制要求密码 |
| RABBITMQ_VHOST | /；不同环境用独立 broker 或 vhost |

主 Compose 增加 RabbitMQ 4.2 管理镜像、持久化卷、认证、健康检查和依赖，不暴露 AMQP 或管理端口到宿主机。上线前应固定镜像 digest、配置备份与运维告警；单节点容器不代表高可用集群。已有 `deploy/.env` 未改动，启动更新后的 Compose 前须添加 `RABBITMQ_PASSWORD`。修改现有卷的账号密码不能只依靠 DEFAULT 环境变量完成轮换。

连接超时 2 秒，发布确认等待上限 5 秒；单次发布失败即结束当前批次。broker 故障不会回滚已经提交的工单或影响站内消息落库，未发送 outbox 保留并继续尝试。依赖健康检查与普通业务 readiness 分开管理。

开启时停用旧的直接投递定时任务；关闭时保留原数据库扫描直发路径，供显式回退。所有实例应统一开关；切换前先停止原模式消费者，并等待在途发送结束。回退不要删除 outbox 或队列。

交换机：`communityfix.notification.v1`；路由键：`wechat`；工作队列：`communityfix.notification.wechat.v1`；死信交换机与队列：`communityfix.notification.dead.v1`。

迁移 `V7__notification_rabbit.sql` 添加发布尝试次数、下次发布时间、发布错误和索引。数据库状态仍为 PENDING / PROCESSING / SENT / FAILED；SENT 仍仅表示微信接口接受，不表示用户已阅读。

## 验证与边界

`python -X utf8 deploy/test-backend-isolated.py` 现在会创建一次性 RabbitMQ、Redis 及临时 MySQL 库，测试后清理。集成测试使用真实 broker 和数据库、模拟微信发送器，覆盖发布消费、重复处理、失败退避及终止、未路由恢复、非法消息死信、过期处理租约恢复。

本轮不调用真实微信、真实 AI，不部署正式环境。运行中 broker 宕机/重启、磁盘故障、集群故障转移及真实微信通知需后续签收演练；本地协议测试不能替代这些生产验证。

最终回归：105 项通过，失败、错误、跳过均为 0，含 5 项真实 RabbitMQ / MySQL 通知集成测试；临时数据库、账号和测试容器清理通过。报告为 `.local/acceptance/tests-37dd8007ef9e/report.json`，主 Compose 配置解析及差异空白检查通过。早期测试发现探针以 root 抢先创建 Erlang cookie 的权限竞争，已改用 rabbitmq 用户；同时关闭隔离测试环境其他上下文的后台客服任务，避免抢占手动测试任务。
