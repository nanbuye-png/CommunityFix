# Redis 接入与验收

2026-11-03：后端使用 Spring Data Redis / Lettuce。首个业务用途是统一模型网关的跨实例供应商请求限流，报修与客服均经过这一入口。

## 行为

- 每个 `namespace + SHA256(endpoint + model)` 共用一个 60 秒滑动窗口，默认最多接纳 30 次。两个场景、多个后端实例使用相同端点和模型时共享；不同密钥仍共享，属于保守限制。不同端点或模型分别计数，不能代表供应商账号级总配额。
- 使用 Redis 服务端时间、ZSET 与 Lua 原子完成过期清理、判断、登记和设置 TTL。成员是随机请求 ID，键不含明文端点、密钥或住户信息。空闲 60 秒后自动清理。
- 超额返回内部码 `AI_PROVIDER_LOCAL_RATE_LIMIT`，Redis 连接失败、超时或未知结果返回 `AI_LIMITER_UNAVAILABLE`。两种情况均不调用模型、不修改供应商熔断状态，业务继续人工报修或客服兜底。
- 限流发生在 MySQL 熔断准入之前；被熔断阻止的请求也可能消耗窗口名额。这是保守准入计数，不是实际供应商调用量或账单。
- 原每居民 60 秒 5 次的 MySQL 请求限制、共享熔断、权限与会话撤销不变。Redis 中不缓存授权结果或模型回答。
- Redis 丢失数据或主动清理后窗口会重新计数；AOF 也不能保证故障时完全不丢额度。当前限制是 RPM，不是 TPM、余额检查或供应商配额提升。

脚本实现依据 [Redis Lua 原子执行说明](https://redis.io/docs/latest/develop/programmability/eval-intro/)；Spring 的脚本加载与缓存使用[官方脚本执行接口](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)。

## 配置与启动

| 配置 | 默认值 / 用途 |
| --- | --- |
| REDIS_ENABLED | 本机直接启动默认 false；主 Compose 和隔离验收 Compose 显式启用 |
| REDIS_HOST / REDIS_PORT | localhost / 6379；容器后端使用 redis:6379 |
| REDIS_PASSWORD | Redis 密码；主 Compose 必填 |
| REDIS_DATABASE | 0，本机直接启动可配置 |
| REDIS_NAMESPACE | communityfix；不同环境使用不同命名空间，同环境实例保持一致 |
| AI_PROVIDER_REQUESTS_PER_MINUTE | 30，范围 1～100000；按供应商实际配额设置，同环境实例保持一致 |

连接与命令超时均为 500ms。`REDIS_ENABLED=false` 关闭新 Redis 限流，不关闭原 MySQL 用户限流和熔断；用于明确的本地开发选择，故障时不会自动切换为此模式。启用时 Redis 健康检查影响总体健康状态，但不加入默认 readiness 分组，以保留普通业务服务能力；部署探针应使用 readiness，Redis 健康单独告警。

主 Compose 已配置密码认证、健康检查、AOF 卷和后端启动依赖，不映射 Redis 宿主机端口。已有 `deploy/.env` 没有被自动修改，使用更新后的主 Compose 前需添加 `REDIS_PASSWORD`。本机后端连接容器 Redis 时需自行提供仅绑定回环地址的开发端口映射；不要暴露到公网。配置模板中的密码均为占位符。

隔离验收 Compose 使用测试网络内无密码 Redis，不开放 Redis 宿主端口；不能将该配置直接用于生产。

## 本地验证

运行 `python -X utf8 deploy/test-backend-isolated.py`。脚本会创建随机临时 MySQL 库和带随机密码、随机回环端口的一次性 Redis 容器，运行结束清理。不会读取真实供应商配置或调用真实模型。

测试覆盖：两个独立 Redis 客户端并发 40 次只通过 5 次、窗口旧记录清理、TTL、命名空间隔离、停用时不连接、连接故障时安全降级、网关拒绝后不调用模型或修改熔断状态。其他后端业务测试继续运行。

本轮结果：100 项后端测试全部通过，失败、错误、跳过均为 0；临时库、临时账号与 Redis 容器清理通过。报告：`.local/acceptance/tests-348140831e55/report.json`。主 Compose 使用示例环境文件执行 `config --quiet` 校验通过。连接失败分支使用模拟异常测试，本轮未进行运行中容器断网及重启故障演练。

本次未部署正式环境。正式上线前仍须验收 Redis 宕机/重启/恢复演练、监控、密码管理、持久化与容量，以及最终供应商限额参数。
